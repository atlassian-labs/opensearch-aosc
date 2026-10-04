/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */
package com.atlassian.opensearch.aosc;

import com.atlassian.opensearch.aosc.model.DeleteRoutingStrategy;
import com.atlassian.opensearch.aosc.model.ShardRoutingMode;

import org.opensearch.action.delete.DeleteRequest;
import org.opensearch.action.get.GetRequest;
import org.opensearch.action.index.IndexRequest;
import org.opensearch.action.support.WriteRequest;
import org.opensearch.common.settings.Settings;
import org.opensearch.common.unit.TimeValue;
import org.opensearch.common.xcontent.XContentType;
import org.opensearch.test.OpenSearchIntegTestCase.ClusterScope;
import org.opensearch.test.OpenSearchIntegTestCase.Scope;

@ClusterScope(scope = Scope.SUITE, numDataNodes = 2, numClientNodes = 0)
public class TranslogDeleteRoutingIT extends AoscIntegTestBase {

    private static final String ROUTING = "tenant-delete-routing";

    @Override
    protected Settings nodeSettings(int nodeOrdinal) {
        return Settings.builder()
            .put(super.nodeSettings(nodeOrdinal))
            .put("aosc.backfill.controller.type", "fixed")
            .put("aosc.backfill.controller.batch.size", 1)
            .put("aosc.backfill.read.page_size", 25)
            .build();
    }

    public void testCustomRoutedDeleteReplaysAcrossArbitraryTopology() throws Exception {
        String source = indexName("trd-src");
        String target = indexName("trd-tgt");
        String sentinel = "sentinel";
        createSourceAndTarget(source, target, 2, 3);
        indexRouted(source, sentinel, ROUTING, -1);
        for (int i = 0; i < 500; i++) {
            indexRouted(source, "filler-" + i, ROUTING, i);
        }
        client().admin().indices().prepareRefresh(source).get();

        startMigration(source, target, "trd-alias", null);
        assertBusyWithFixedSleepTime(() -> {
            var status = getStatus(source).body();
            assertEquals(ShardRoutingMode.BULK_API, status.shardRoutingMode());
            assertEquals(DeleteRoutingStrategy.TRANSLOG_ROUTING, status.deleteRoutingStrategy());
            assertFalse(status.phase().isTerminal());
            assertTrue(client().get(new GetRequest(target, sentinel).routing(ROUTING)).actionGet().isExists());
        }, TimeValue.timeValueSeconds(60), TimeValue.timeValueMillis(100));

        client().delete(new DeleteRequest(source, sentinel).routing(ROUTING).setRefreshPolicy(WriteRequest.RefreshPolicy.IMMEDIATE))
            .actionGet();
        client().admin().indices().prepareFlush(source).get();

        assertMigrationCompleted(source, 120);
        client().admin().indices().prepareRefresh(source, target).get();
        assertFalse(client().get(new GetRequest(source, sentinel).routing(ROUTING)).actionGet().isExists());
        assertFalse(client().get(new GetRequest(target, sentinel).routing(ROUTING)).actionGet().isExists());
        assertDocCountsMatch(source, target);
    }

    public void testUnroutedDeleteUsesIdRouting() throws Exception {
        String source = indexName("tru-src");
        String target = indexName("tru-tgt");
        String sentinel = "sentinel";
        createSourceAndTarget(source, target, 2, 3);
        indexUnrouted(source, sentinel, -1);
        for (int i = 0; i < 250; i++) {
            indexUnrouted(source, "filler-" + i, i);
        }
        client().admin().indices().prepareRefresh(source).get();

        startMigration(source, target, "tru-alias", null);
        assertBusyWithFixedSleepTime(
            () -> assertTrue(client().get(new GetRequest(target, sentinel)).actionGet().isExists()),
            TimeValue.timeValueSeconds(60),
            TimeValue.timeValueMillis(100)
        );

        client().delete(new DeleteRequest(source, sentinel).setRefreshPolicy(WriteRequest.RefreshPolicy.IMMEDIATE)).actionGet();

        assertMigrationCompleted(source, 120);
        client().admin().indices().prepareRefresh(source, target).get();
        assertFalse(client().get(new GetRequest(target, sentinel)).actionGet().isExists());
        assertDocCountsMatch(source, target);
    }

    private void indexRouted(String index, String id, String routing, int value) {
        client().index(
            new IndexRequest(index).id(id)
                .routing(routing)
                .source("{\"value\":" + value + "}", XContentType.JSON)
                .setRefreshPolicy(WriteRequest.RefreshPolicy.NONE)
        ).actionGet();
    }

    private void indexUnrouted(String index, String id, int value) {
        client().index(
            new IndexRequest(index).id(id)
                .source("{\"value\":" + value + "}", XContentType.JSON)
                .setRefreshPolicy(WriteRequest.RefreshPolicy.NONE)
        ).actionGet();
    }
}
