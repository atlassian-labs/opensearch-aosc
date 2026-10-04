/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */
package com.atlassian.opensearch.aosc;

import com.atlassian.opensearch.aosc.model.ShardRoutingMode;

import org.opensearch.ExceptionsHelper;
import org.opensearch.Version;
import org.opensearch.action.delete.DeleteRequest;
import org.opensearch.action.get.GetRequest;
import org.opensearch.action.index.IndexRequest;
import org.opensearch.action.support.WriteRequest;
import org.opensearch.cluster.metadata.IndexMetadata;
import org.opensearch.cluster.routing.OperationRouting;
import org.opensearch.common.settings.Settings;
import org.opensearch.common.unit.TimeValue;
import org.opensearch.common.xcontent.XContentType;
import org.opensearch.test.OpenSearchIntegTestCase.ClusterScope;
import org.opensearch.test.OpenSearchIntegTestCase.Scope;

/** {@code SHRINK_SHARD} (2^k·N → N): replayed deletes reach the one target shard that owns the source shard's documents. */
@ClusterScope(scope = Scope.SUITE, numDataNodes = 2, numClientNodes = 0)
public class ShrinkShardIT extends AoscIntegTestBase {

    private static final String SENTINEL = "sentinel";

    @Override
    protected Settings nodeSettings(int nodeOrdinal) {
        // Slow backfill so the sentinel delete lands after the backfill cutoff and is replayed.
        return Settings.builder()
            .put(super.nodeSettings(nodeOrdinal))
            .put("aosc.backfill.controller.type", "fixed")
            .put("aosc.backfill.controller.batch.size", 1)
            .put("aosc.backfill.read.page_size", 25)
            .build();
    }

    public void testShrinkReplaysCustomRoutedDeleteWithoutConsent() throws Exception {
        String source = indexName("shr-src");
        String target = indexName("shr-tgt");
        createSourceAndTarget(source, target, 4, 2);
        String routing = routingAwayFromIdShard(target, SENTINEL);
        indexRouted(source, SENTINEL, routing, -1);
        for (int i = 0; i < 500; i++) {
            indexRouted(source, "filler-" + i, "tenant-" + (i % 7), i);
        }
        client().admin().indices().prepareRefresh(source).get();

        // No accept_data_loss_if_custom_routing_is_used: the target shard of every source shard is known.
        startMigration(source, target, "shr-alias", null);
        assertBusyWithFixedSleepTime(() -> {
            var status = getStatus(source).body();
            assertEquals(ShardRoutingMode.SHRINK_SHARD, status.shardRoutingMode());
            assertFalse(status.phase().isTerminal());
            assertTrue(client().get(new GetRequest(target, SENTINEL).routing(routing)).actionGet().isExists());
        }, TimeValue.timeValueSeconds(60), TimeValue.timeValueMillis(100));

        client().delete(new DeleteRequest(source, SENTINEL).routing(routing).setRefreshPolicy(WriteRequest.RefreshPolicy.IMMEDIATE))
            .actionGet();
        client().admin().indices().prepareFlush(source).get();

        assertMigrationCompleted(source, 120);
        client().admin().indices().prepareRefresh(source, target).get();
        assertFalse(client().get(new GetRequest(target, SENTINEL).routing(routing)).actionGet().isExists());
        assertDocCountsMatch(source, target);
    }

    public void testShrinkWithDifferentRoutingShardsIsRejectedBefore39() throws Exception {
        String source = indexName("shrr-src");
        String target = indexName("shrr-tgt");
        createIndex(source, Settings.builder().put("index.number_of_shards", 4).put("index.number_of_replicas", 0).build());
        createIndex(
            target,
            Settings.builder()
                .put("index.number_of_shards", 2)
                .put("index.number_of_routing_shards", 2)
                .put("index.number_of_replicas", 0)
                .build()
        );
        ensureGreen(source, target);
        indexDocs(source, 50);

        if (Version.CURRENT.onOrAfter(Version.fromString("3.9.0"))) {
            // 3.9+: deletes replay with their recorded routing, so the hash space doesn't matter.
            startMigration(source, target, "shrr-alias", null);
            assertMigrationCompleted(source, 90);
            assertDocCountsMatch(source, target);
        } else {
            Exception e = expectThrows(Exception.class, () -> startMigration(source, target, "shrr-alias", null));
            assertTrue(ExceptionsHelper.stackTrace(e), ExceptionsHelper.stackTrace(e).contains("index.number_of_routing_shards=1024"));
        }
    }

    /** A routing value that hashes to a different target shard than the id, so only a routed delete can reach the doc. */
    private String routingAwayFromIdShard(String index, String id) {
        IndexMetadata meta = clusterService().state().metadata().index(index);
        int idShard = OperationRouting.generateShardId(meta, id, null);
        for (int i = 0;; i++) {
            String routing = "tenant-" + i;
            if (OperationRouting.generateShardId(meta, id, routing) != idShard) {
                return routing;
            }
        }
    }

    private void indexRouted(String index, String id, String routing, int value) {
        client().index(
            new IndexRequest(index).id(id)
                .routing(routing)
                .source("{\"value\":" + value + "}", XContentType.JSON)
                .setRefreshPolicy(WriteRequest.RefreshPolicy.NONE)
        ).actionGet();
    }
}
