/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */
package com.atlassian.opensearch.aosc;

import org.opensearch.action.bulk.BulkRequest;
import org.opensearch.action.get.GetRequest;
import org.opensearch.action.get.GetResponse;
import org.opensearch.action.index.IndexRequest;
import org.opensearch.action.support.WriteRequest;
import org.opensearch.common.settings.Settings;
import org.opensearch.common.xcontent.XContentType;
import org.opensearch.test.OpenSearchIntegTestCase.ClusterScope;
import org.opensearch.test.OpenSearchIntegTestCase.Scope;

import java.util.Map;

/**
 * Integration tests for migrations with high shard counts. Validates that
 * the worker-per-shard model scales correctly and produces exact parity
 * with many concurrent shard workers.
 */
@ClusterScope(scope = Scope.SUITE, numDataNodes = 2, numClientNodes = 0)
public class HighShardCountIT extends AoscIntegTestBase {

    /**
     * 10-shard source → 10-shard target, 2000 docs → exact parity.
     */
    public void testHighShardSameShard() throws Exception {
        String source = indexName("hss-src");
        String target = indexName("hss-tgt");
        createSourceAndTarget(source, target, 10, 10);
        indexDocs(source, 2000);

        startMigration(source, target, "hss-alias", null);
        assertMigrationCompleted(source, 120);
        assertDocCountsMatch(source, target);
    }

    /**
     * 5-shard source → 20-shard target (4x split), 2000 docs → exact parity.
     */
    public void testHighShardSplit() throws Exception {
        String source = indexName("hssp-src");
        String target = indexName("hssp-tgt");
        createSourceAndTarget(source, target, 5, 20);
        indexDocs(source, 2000);

        startMigration(source, target, "hssp-alias", null);
        assertMigrationCompleted(source, 120);
        assertDocCountsMatch(source, target);
    }

    /**
     * 10 shards with concurrent inserts/updates/deletes — parity within tolerance.
     */
    public void testHighShardWithConcurrentWrites() throws Exception {
        String source = indexName("hscw-src");
        String target = indexName("hscw-tgt");
        createSourceAndTarget(source, target, 10, 10);
        indexDocs(source, 1000);

        startMigration(source, target, "hscw-alias", null);

        // Concurrent inserts
        for (int i = 1000; i < 1200; i++) {
            try {
                indexDoc(source, String.valueOf(i), Map.of("value", i));
            } catch (Exception e) {
                break;
            }
        }

        // Concurrent updates
        for (int i = 0; i < 100; i++) {
            try {
                indexDoc(source, String.valueOf(i), Map.of("value", i * 10));
            } catch (Exception e) {
                break;
            }
        }

        // Concurrent deletes
        for (int i = 900; i < 950; i++) {
            try {
                client().prepareDelete(source, String.valueOf(i)).setRefreshPolicy(WriteRequest.RefreshPolicy.NONE).get();
            } catch (Exception e) {
                break;
            }
        }

        assertMigrationCompleted(source, 120);

        // Target captures source state at cutover — some concurrent writes may not
        // have been applied before cutover, so counts may diverge slightly.
        long targetCount = getDocCount(target);
        assertTrue("Target should have at least 950 docs, got: " + targetCount, targetCount >= 950);
        assertTrue("Target should have at most 1200 docs, got: " + targetCount, targetCount <= 1200);
    }

    /**
     * 10 shards with custom routing — docs land on correct shards in target.
     */
    public void testHighShardWithCustomRouting() throws Exception {
        String source = indexName("hscr-src");
        String target = indexName("hscr-tgt");

        createIndex(source, Settings.builder().put("index.number_of_shards", 10).put("index.number_of_replicas", 0).build());
        createIndex(target, Settings.builder().put("index.number_of_shards", 10).put("index.number_of_replicas", 0).build());
        ensureGreen(source, target);

        for (int i = 0; i < 1000; i++) {
            String routing = "tenant-" + (i % 10);
            client().index(
                new IndexRequest(source).id(String.valueOf(i))
                    .source("{\"value\":" + i + ",\"tenant\":\"" + routing + "\"}", XContentType.JSON)
                    .routing(routing)
                    .setRefreshPolicy(WriteRequest.RefreshPolicy.NONE)
            ).actionGet();
        }
        client().admin().indices().prepareRefresh(source).get();

        startMigration(source, target, "hscr-alias", null);
        assertMigrationCompleted(source, 120);
        assertDocCountsMatch(source, target);

        // Verify routing correctness on a sample
        for (int i = 0; i < 100; i++) {
            String routing = "tenant-" + (i % 10);
            GetResponse resp = client().get(new GetRequest(target, String.valueOf(i)).routing(routing)).actionGet();
            assertTrue("Doc " + i + " should exist with routing " + routing, resp.isExists());
            assertEquals(routing, resp.getSourceAsMap().get("tenant"));
        }
    }

    /**
     * 10 shards with bulk API indexing — docs indexed in bulk batches migrate correctly.
     */
    public void testHighShardBulkApi() throws Exception {
        String source = indexName("hsb-src");
        String target = indexName("hsb-tgt");
        createSourceAndTarget(source, target, 10, 10);

        // Index using bulk API in batches of 200
        for (int batch = 0; batch < 10; batch++) {
            BulkRequest bulkRequest = new BulkRequest();
            for (int i = batch * 200; i < (batch + 1) * 200; i++) {
                bulkRequest.add(new IndexRequest(source).id(String.valueOf(i)).source("{\"value\":" + i + "}", XContentType.JSON));
            }
            bulkRequest.setRefreshPolicy(WriteRequest.RefreshPolicy.NONE);
            client().bulk(bulkRequest).actionGet();
        }
        client().admin().indices().prepareRefresh(source).get();

        startMigration(source, target, "hsb-alias", null);
        assertMigrationCompleted(source, 120);

        assertEquals("Target should have 2000 docs", 2000, getDocCount(target));
        assertDocCountsMatch(source, target);
    }
}
