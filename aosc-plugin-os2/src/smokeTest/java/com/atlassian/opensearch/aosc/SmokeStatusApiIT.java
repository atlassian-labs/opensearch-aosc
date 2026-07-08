/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */
package com.atlassian.opensearch.aosc;

import java.util.List;
import java.util.Map;

/**
 * Smoke tests for the AOSC status and list REST APIs.
 * Validates response shape, per-shard progress, and terminal states
 * through the HTTP surface.
 */
public class SmokeStatusApiIT extends AoscSmokeTestBase {

    /**
     * Status shows per-shard data matching source shard count.
     */
    @SuppressWarnings("unchecked")
    public void testStatusShowsPerShardData() throws Exception {
        String source = indexName("spd-src");
        String target = indexName("spd-tgt");
        createSourceAndTarget(source, target, 3, 3);
        bulkIndex(source, 2000);

        startMigration(source, target, indexName("spd-alias"));

        // Poll until we see per-shard data
        Map<String, Object> status = null;
        long deadline = System.currentTimeMillis() + 60_000;
        while (System.currentTimeMillis() < deadline) {
            status = getStatus(source);
            if (Boolean.TRUE.equals(status.get("found"))) {
                Map<String, Object> shards = (Map<String, Object>) status.get("shards");
                if (shards != null && shards.size() == 3) {
                    break;
                }
            }
            Thread.sleep(200);
        }

        assertNotNull("Status should be available", status);
        Map<String, Object> shards = (Map<String, Object>) status.get("shards");
        assertEquals("Should have 3 shards", 3, shards.size());

        for (Map.Entry<String, Object> entry : shards.entrySet()) {
            Map<String, Object> shard = (Map<String, Object>) entry.getValue();
            assertNotNull("Shard " + entry.getKey() + " should have phase", shard.get("phase"));
        }

        waitForCompletion(source, 90);
    }

    /**
     * After completion, phase is COMPLETED and all shards are terminal.
     */
    @SuppressWarnings("unchecked")
    public void testTerminalStateAfterCompletion() throws Exception {
        String source = indexName("ts-src");
        String target = indexName("ts-tgt");
        createSourceAndTarget(source, target, 3, 3);
        bulkIndex(source, 300);

        startMigration(source, target, indexName("ts-alias"));
        Map<String, Object> status = waitForCompletion(source, 90);

        assertCoordinatorPhase(status, "COMPLETED");
        assertNotNull("migration_id should be present", status.get("migration_id"));
        assertAllShardsTerminal(source);
    }

    /**
     * List API shows active migration and includes expected fields.
     */
    @SuppressWarnings("unchecked")
    public void testListShowsActiveMigration() throws Exception {
        String source = indexName("la-src");
        String target = indexName("la-tgt");
        createSourceAndTarget(source, target, 1, 1);
        bulkIndex(source, 2000);

        startMigration(source, target, indexName("la-alias"));

        // Poll until OUR migration appears (default status=all returns prior tests' completed
        // migrations too, so we cannot break on first non-empty response).
        Map<String, Object> listResp = null;
        Map<String, Object> ourMigration = null;
        long deadline = System.currentTimeMillis() + 60_000;
        while (System.currentTimeMillis() < deadline) {
            listResp = listMigrations();
            List<Map<String, Object>> migrations = (List<Map<String, Object>>) listResp.get("migrations");
            if (migrations != null) {
                for (Map<String, Object> m : migrations) {
                    if (source.equals(m.get("source_index"))) {
                        ourMigration = m;
                        break;
                    }
                }
            }
            if (ourMigration != null) break;
            Thread.sleep(200);
        }

        assertNotNull("List should be available", listResp);
        assertNotNull("Our migration should appear in list (source=" + source + ")", ourMigration);
        assertNotNull("Should have phase", ourMigration.get("phase"));
        assertNotNull("Should have migration_id", ourMigration.get("migration_id"));

        waitForCompletion(source, 90);
    }

    /**
     * Status shard count matches source shards, not target, when split (2→4).
     */
    @SuppressWarnings("unchecked")
    public void testStatusShardCountMatchesSource() throws Exception {
        String source = indexName("sc-src");
        String target = indexName("sc-tgt");
        createSourceAndTarget(source, target, 2, 4);
        bulkIndex(source, 300);

        startMigration(source, target, indexName("sc-alias"));
        Map<String, Object> status = waitForCompletion(source, 90);

        Map<String, Object> shards = (Map<String, Object>) status.get("shards");
        assertEquals("Shard count should match source (2), not target (4)", 2, shards.size());
    }
}
