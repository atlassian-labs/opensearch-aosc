/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */
package com.atlassian.opensearch.aosc;

import java.util.Map;

/**
 * Smoke tests for the adaptive writer pipeline.
 * Validates that adaptive batch sizing and concurrent writer limits work
 * in a real multi-node Docker environment.
 */
public class SmokeAdaptiveWriterIT extends AoscSmokeTestBase {

    /**
     * Enable adaptive batch sizing and verify migration completes successfully.
     */
    public void testAdaptiveMigrationCompletes() throws Exception {
        String source = indexName("adp-src");
        String target = indexName("adp-tgt");

        try {
            // Enable adaptive batch sizing
            updateClusterSetting("aosc.backfill.controller.type", "adaptive");

            createSourceAndTarget(source, target, 2, 2);
            bulkIndex(source, 500);

            Map<String, Object> startResp = startMigration(source, target, indexName("adp-alias"));
            assertTrue("Should be accepted", (Boolean) startResp.get("accepted"));
            assertNotNull("Should have migration_id", startResp.get("migration_id"));

            Map<String, Object> status = waitForCompletion(source, 90);
            assertCoordinatorPhase(status, "COMPLETED");
            assertAllShardsTerminal(source);
            assertDocCountsMatch(source, target);
        } finally {
            clearClusterSetting("aosc.backfill.controller.type");
        }
    }

    /**
     * Enable adaptive with max_concurrency=2 and verify migration completes.
     */
    public void testConcurrentWritersMigrationCompletes() throws Exception {
        String source = indexName("cw-src");
        String target = indexName("cw-tgt");

        try {
            // Enable adaptive and set max concurrent writers
            updateClusterSetting("aosc.backfill.controller.type", "adaptive");
            updateClusterSetting("aosc.backfill.controller.concurrency.max", 2);

            createSourceAndTarget(source, target, 2, 2);
            bulkIndex(source, 500);

            Map<String, Object> startResp = startMigration(source, target, indexName("cw-alias"));
            assertTrue("Should be accepted", (Boolean) startResp.get("accepted"));
            assertNotNull("Should have migration_id", startResp.get("migration_id"));

            Map<String, Object> status = waitForCompletion(source, 90);
            assertCoordinatorPhase(status, "COMPLETED");
            assertAllShardsTerminal(source);
            assertDocCountsMatch(source, target);
        } finally {
            clearClusterSetting("aosc.backfill.controller.type");
            clearClusterSetting("aosc.backfill.controller.concurrency.max");
        }
    }
}
