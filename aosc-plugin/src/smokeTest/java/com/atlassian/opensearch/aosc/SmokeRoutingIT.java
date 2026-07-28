/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */
package com.atlassian.opensearch.aosc;

import org.opensearch.client.Request;
import org.opensearch.client.ResponseException;

import java.util.Map;

/**
 * Smoke tests for routing-aware migration over REST.
 * Validates SAME_SHARD, SPLIT_SHARD, and custom routing scenarios
 * through the HTTP API.
 */
public class SmokeRoutingIT extends AoscSmokeTestBase {

    /**
     * SAME_SHARD: 3→3, no routing, 500 docs.
     */
    public void testSameShardNoRouting() throws Exception {
        String source = indexName("ssn-src");
        String target = indexName("ssn-tgt");
        createSourceAndTarget(source, target, 3, 3);
        bulkIndex(source, 500);

        startMigration(source, target, indexName("ssn-alias"));
        waitForCompletion(source, 90);
        assertDocCountsMatch(source, target);
    }

    /**
     * SAME_SHARD: 2→2, with custom routing.
     */
    public void testSameShardWithCustomRouting() throws Exception {
        String source = indexName("sscr-src");
        String target = indexName("sscr-tgt");
        createSourceAndTarget(source, target, 2, 2);

        // Index docs with explicit routing
        for (int i = 0; i < 200; i++) {
            String routing = "tenant-" + (i % 5);
            indexDocWithRouting(source, String.valueOf(i), routing, Map.of("value", i, "tenant", routing));
        }
        refreshIndex(source);

        startMigration(source, target, indexName("sscr-alias"));
        waitForCompletion(source, 90);
        assertDocCountsMatch(source, target);

        // Verify docs accessible with correct routing in target
        for (int i = 0; i < 10; i++) {
            String routing = "tenant-" + (i % 5);
            Map<String, Object> doc = getDocWithRouting(target, String.valueOf(i), routing);
            assertTrue("Doc " + i + " should exist with routing", (Boolean) doc.get("found"));
        }
    }

    /**
     * SPLIT_SHARD: 2→4, no routing.
     */
    public void testSplitShard2to4() throws Exception {
        String source = indexName("sp24-src");
        String target = indexName("sp24-tgt");
        createSourceAndTarget(source, target, 2, 4);
        bulkIndex(source, 500);

        startMigration(source, target, indexName("sp24-alias"));
        waitForCompletion(source, 90);
        assertDocCountsMatch(source, target);
    }

    /**
     * SPLIT_SHARD: 3→6, no routing.
     */
    public void testSplitShard3to6() throws Exception {
        String source = indexName("sp36-src");
        String target = indexName("sp36-tgt");
        createSourceAndTarget(source, target, 3, 6);
        bulkIndex(source, 600);

        startMigration(source, target, indexName("sp36-alias"));
        waitForCompletion(source, 90);
        assertDocCountsMatch(source, target);
    }

    /**
     * SPLIT_SHARD: 2→4 with custom routing — docs land on correct target shards.
     */
    public void testSplitShardWithCustomRouting() throws Exception {
        String source = indexName("spcr-src");
        String target = indexName("spcr-tgt");
        createSourceAndTarget(source, target, 2, 4);

        for (int i = 0; i < 200; i++) {
            String routing = "org-" + (i % 4);
            indexDocWithRouting(source, String.valueOf(i), routing, Map.of("value", i, "org", routing));
        }
        refreshIndex(source);

        startMigration(source, target, indexName("spcr-alias"));
        waitForCompletion(source, 90);
        assertDocCountsMatch(source, target);

        // Verify routing is preserved
        for (int i = 0; i < 10; i++) {
            String routing = "org-" + (i % 4);
            Map<String, Object> doc = getDocWithRouting(target, String.valueOf(i), routing);
            assertTrue("Doc " + i + " should exist with routing", (Boolean) doc.get("found"));
        }
    }

    /**
     * BULK_API: non-multiple shard counts (2→3) with accept_data_loss flag.
     */
    public void testNonMultipleShards() throws Exception {
        String source = indexName("nm-src");
        String target = indexName("nm-tgt");
        createSourceAndTarget(source, target, 2, 3);
        bulkIndex(source, 300);

        startMigration(source, target, indexName("nm-alias"), null, Map.of("accept_data_loss_if_custom_routing_is_used", true));
        waitForCompletion(source, 90);
        assertDocCountsMatch(source, target);
    }

    /**
     * Non-multiple shard counts (2→3) WITHOUT accept_data_loss flag should be rejected.
     */
    public void testNonMultipleRejectedWithoutFlag() throws Exception {
        String source = indexName("rej-src");
        String target = indexName("rej-tgt");
        createSourceAndTarget(source, target, 2, 3);
        bulkIndex(source, 100);

        try {
            // Start without accept_data_loss flag
            Request request = new Request("POST", "/_plugins/_aosc/" + source + "/_start");
            request.setJsonEntity("{\"target\":{\"index\":\"" + target + "\"},\"alias\":\"" + indexName("rej-alias") + "\"}");
            client().performRequest(request);
            fail("Non-multiple shard migration without accept_data_loss should be rejected");
        } catch (ResponseException e) {
            int statusCode = e.getResponse().getStatusLine().getStatusCode();
            assertTrue("Should return 4xx or 5xx, got: " + statusCode, statusCode >= 400);
        }
    }

    /**
     * Migration with replicas on source — primaries only should be used.
     */
    public void testSourceWithReplicas() throws Exception {
        String source = indexName("rep-src");
        String target = indexName("rep-tgt");
        createTestIndex(source, 2, 1);
        createTestIndex(target, 2, 0);
        waitForGreen(source);
        waitForGreen(target);
        bulkIndex(source, 300);

        startMigration(source, target, indexName("rep-alias"));
        waitForCompletion(source, 90);
        assertDocCountsMatch(source, target);
    }
}
