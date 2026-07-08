/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */
package com.atlassian.opensearch.aosc;

import org.opensearch.client.Request;

import java.util.Map;

/**
 * Core migration smoke tests — validates the full migration lifecycle,
 * various data scenarios, and cutover behaviour through the HTTP REST API.
 */
public class SmokeMigrationCoreIT extends AoscSmokeTestBase {

    /**
     * Full lifecycle: start → poll → complete → verify doc parity + content.
     */
    public void testFullMigrationLifecycle() throws Exception {
        String source = indexName("full-src");
        String target = indexName("full-tgt");
        createSourceAndTarget(source, target, 2, 2);
        bulkIndex(source, 500);

        Map<String, Object> startResp = startMigration(source, target, indexName("full-alias"));
        assertTrue("Should be accepted", (Boolean) startResp.get("accepted"));
        assertNotNull("Should have migration_id", startResp.get("migration_id"));

        Map<String, Object> status = waitForCompletion(source, 90);
        assertCoordinatorPhase(status, "COMPLETED");
        assertAllShardsTerminal(source);
        assertDocCountsMatch(source, target);

        // Verify content
        Map<String, Object> doc = getDoc(target, "0");
        assertTrue("Doc should exist", (Boolean) doc.get("found"));
        @SuppressWarnings("unchecked")
        Map<String, Object> docSource = (Map<String, Object>) doc.get("_source");
        assertEquals("Doc value should be 0", 0, docSource.get("value"));
    }

    /**
     * Empty source — migration completes immediately with 0 docs in target.
     */
    public void testEmptySourceMigration() throws Exception {
        String source = indexName("emp-src");
        String target = indexName("emp-tgt");
        createSourceAndTarget(source, target, 1, 1);

        startMigration(source, target, indexName("emp-alias"));
        waitForCompletion(source, 60);
        assertEquals("Target should have 0 docs", 0, getDocCount(target));
    }

    /**
     * Large batch — 5000 docs across 5 shards.
     */
    public void testLargeBatchMigration() throws Exception {
        String source = indexName("lb-src");
        String target = indexName("lb-tgt");
        createSourceAndTarget(source, target, 5, 5);
        bulkIndex(source, 5000);

        startMigration(source, target, indexName("lb-alias"));
        waitForCompletion(source, 120);
        assertDocCountsMatch(source, target);
    }

    /**
     * Split shard migration (2→4) — all docs present in target.
     */
    public void testSplitShardMigration() throws Exception {
        String source = indexName("splt-src");
        String target = indexName("splt-tgt");
        createSourceAndTarget(source, target, 2, 4);
        bulkIndex(source, 500);

        startMigration(source, target, indexName("splt-alias"));
        waitForCompletion(source, 90);
        assertDocCountsMatch(source, target);
    }

    /**
     * Alias swap — after migration, alias points to target.
     */
    public void testAliasSwapAfterMigration() throws Exception {
        String source = indexName("as-src");
        String target = indexName("as-tgt");
        String alias = indexName("as-alias");
        createSourceAndTarget(source, target, 2, 2);
        bulkIndex(source, 500);

        // Pre-create alias on source
        Request aliasReq = new Request("POST", "/_aliases");
        aliasReq.setJsonEntity("{\"actions\":[{\"add\":{\"index\":\"" + source + "\",\"alias\":\"" + alias + "\"}}]}");
        client().performRequest(aliasReq);

        startMigration(source, target, alias);
        waitForCompletion(source, 90);

        assertAliasPointsTo(alias, target);
        assertDocCountsMatch(source, target);
    }

    /**
     * Concurrent inserts during migration — target has at least the original docs.
     */
    public void testConcurrentInsertsDuringMigration() throws Exception {
        String source = indexName("ci-src");
        String target = indexName("ci-tgt");
        createSourceAndTarget(source, target, 2, 2);
        bulkIndex(source, 500);

        startMigration(source, target, indexName("ci-alias"));

        for (int i = 500; i < 700; i++) {
            try {
                indexDoc(source, String.valueOf(i), Map.of("value", i));
            } catch (Exception e) {
                break;
            }
        }

        waitForCompletion(source, 90);
        long targetCount = getDocCount(target);
        assertTrue("Target should have at least 500 docs, got: " + targetCount, targetCount >= 500);
    }

    /**
     * Concurrent deletes during migration — target within expected range.
     */
    public void testConcurrentDeletesDuringMigration() throws Exception {
        String source = indexName("cd-src");
        String target = indexName("cd-tgt");
        createSourceAndTarget(source, target, 2, 2);
        bulkIndex(source, 500);

        startMigration(source, target, indexName("cd-alias"));

        for (int i = 0; i < 50; i++) {
            try {
                client().performRequest(new Request("DELETE", "/" + source + "/_doc/" + i));
            } catch (Exception e) {
                break;
            }
        }

        waitForCompletion(source, 90);
        long targetCount = getDocCount(target);
        assertTrue("Target should have at least 450 docs, got: " + targetCount, targetCount >= 450);
        assertTrue("Target should have at most 500 docs, got: " + targetCount, targetCount <= 500);
    }

    /**
     * Cancel a migration via REST.
     */
    public void testCancelMigration() throws Exception {
        String source = indexName("canc-src");
        String target = indexName("canc-tgt");
        createSourceAndTarget(source, target, 1, 1);
        bulkIndex(source, 5000);

        startMigration(source, target, indexName("canc-alias"));

        // Wait for active
        long deadline = System.currentTimeMillis() + 60_000;
        while (System.currentTimeMillis() < deadline) {
            try {
                getStatus(source);
                break;
            } catch (org.opensearch.client.ResponseException e) {
                if (e.getResponse().getStatusLine().getStatusCode() == 404) {
                    Thread.sleep(200);
                    continue;
                }
                throw e;
            }
        }

        Map<String, Object> cancelResp = cancelMigration(source);
        assertTrue("Cancel should be accepted", (Boolean) cancelResp.get("accepted"));

        Map<String, Object> terminal = waitForTerminalPhase(source, 120);
        String phase = (String) terminal.get("phase");
        assertTrue("Should reach CANCELLED or COMPLETED, got: " + phase, "CANCELLED".equals(phase) || "COMPLETED".equals(phase));
    }

    /**
     * Migration with a transform script.
     */
    public void testMigrationWithTransform() throws Exception {
        assumeTrue("Painless scripting module required", isPainlessAvailable());
        String source = indexName("xfm-src");
        String target = indexName("xfm-tgt");
        createSourceAndTarget(source, target, 1, 1);
        bulkIndex(source, 200);

        startMigration(source, target, indexName("xfm-alias"), "ctx._source.version = 2", null);
        waitForCompletion(source, 90);
        assertDocCountsMatch(source, target);

        Map<String, Object> doc = getDoc(target, "0");
        assertTrue("Doc should exist", (Boolean) doc.get("found"));
        @SuppressWarnings("unchecked")
        Map<String, Object> docSource = (Map<String, Object>) doc.get("_source");
        assertEquals("Should have version=2", 2, docSource.get("version"));
    }

    /**
     * High shard count — 10 shards, 2000 docs.
     */
    public void testHighShardCount() throws Exception {
        String source = indexName("hs-src");
        String target = indexName("hs-tgt");
        createSourceAndTarget(source, target, 10, 10);
        bulkIndex(source, 2000);

        startMigration(source, target, indexName("hs-alias"));
        waitForCompletion(source, 120);
        assertDocCountsMatch(source, target);
    }
}
