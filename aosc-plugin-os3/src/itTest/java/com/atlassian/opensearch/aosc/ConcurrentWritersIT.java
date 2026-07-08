/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */
package com.atlassian.opensearch.aosc;

import com.atlassian.opensearch.aosc.service.bulk.BulkWriteHelper;

import org.opensearch.common.settings.Settings;
import org.opensearch.index.IndexingPressure;
import org.opensearch.test.OpenSearchIntegTestCase;

import org.junit.After;
import org.junit.Before;

/**
 * Integration tests for concurrent writers and adaptive backpressure.
 *
 * <p>Tests run against a real cluster with tight indexing pressure (10MB) to
 * exercise overload retry paths. Validates that migrations complete and all
 * docs are transferred under various concurrency and pressure configurations.</p>
 */
@OpenSearchIntegTestCase.ClusterScope(scope = OpenSearchIntegTestCase.Scope.TEST, numDataNodes = 2)
public class ConcurrentWritersIT extends AoscIntegTestBase {

    private long savedBaseRetryDelayMs;

    @Before
    public void speedUpRetries() {
        savedBaseRetryDelayMs = BulkWriteHelper.baseRetryDelayMs;
        BulkWriteHelper.baseRetryDelayMs = 100;
    }

    @After
    public void restoreRetries() {
        BulkWriteHelper.baseRetryDelayMs = savedBaseRetryDelayMs;
    }

    /**
     * Adaptive W=4: migration completes and all docs transferred.
     */
    public void testAdaptiveConcurrentWritersCompletesMigration() throws Exception {
        String source = indexName("concurrent-src");
        String target = indexName("concurrent-tgt");

        client().admin()
            .cluster()
            .prepareUpdateSettings()
            .setTransientSettings(
                Settings.builder().put("aosc.backfill.controller.type", "adaptive").put("aosc.backfill.controller.concurrency.max", 4)
            )
            .get();

        try {
            createSourceAndTarget(source, target, 2, 2);
            indexDocs(source, 3000);

            startMigration(source, target, source + "-alias", null);
            assertMigrationCompleted(source, 120);
            assertDocCountsMatch(source, target);
        } finally {
            clearAdaptiveSettings();
        }
    }

    /**
     * Non-adaptive W=1 baseline: serial writer completes migration.
     */
    public void testSerialWriterCompletesMigration() throws Exception {
        String source = indexName("serial-src");
        String target = indexName("serial-tgt");

        client().admin()
            .cluster()
            .prepareUpdateSettings()
            .setTransientSettings(Settings.builder().put("aosc.backfill.controller.type", "fixed"))
            .get();

        try {
            createSourceAndTarget(source, target, 2, 2);
            indexDocs(source, 1000);

            startMigration(source, target, source + "-alias", null);
            assertMigrationCompleted(source, 120);
            assertDocCountsMatch(source, target);
        } finally {
            clearAdaptiveSettings();
        }
    }

    /**
     * Large docs (200 × 100KB) with tight indexing pressure (10MB) force
     * overload retries. Migration must still complete with all docs transferred.
     */
    public void testBackpressureWithLargeDocsStillCompletes() throws Exception {
        String source = indexName("backpressure-src");
        String target = indexName("backpressure-tgt");

        client().admin()
            .cluster()
            .prepareUpdateSettings()
            .setTransientSettings(
                Settings.builder()
                    .put("aosc.backfill.controller.type", "adaptive")
                    .put("aosc.backfill.controller.concurrency.max", 2)
                    .put("aosc.backfill.controller.batch.size", 50)
            )
            .get();

        try {
            createSourceAndTarget(source, target, 1, 1);
            indexLargeDocs(source, 100, 50_000);

            startMigration(source, target, source + "-alias", null);
            assertMigrationCompleted(source, 180);
            assertDocCountsMatch(source, target);
        } finally {
            clearAdaptiveSettings();
        }
    }

    private void clearAdaptiveSettings() throws Exception {
        client().admin()
            .cluster()
            .prepareUpdateSettings()
            .setTransientSettings(
                Settings.builder()
                    .putNull("aosc.backfill.controller.type")
                    .putNull("aosc.backfill.controller.concurrency.max")
                    .putNull("aosc.backfill.controller.batch.size")
            )
            .get();
    }

    private void indexLargeDocs(String index, int count, int sizeBytes) {
        String padding = "x".repeat(sizeBytes);
        for (int i = 0; i < count; i++) {
            client().prepareIndex(index).setId("large-" + i).setSource("data", padding).get();
        }
        client().admin().indices().prepareRefresh(index).get();
    }

    @Override
    protected Settings nodeSettings(int nodeOrdinal) {
        return Settings.builder().put(super.nodeSettings(nodeOrdinal)).put(IndexingPressure.MAX_INDEXING_BYTES.getKey(), "10mb").build();
    }
}
