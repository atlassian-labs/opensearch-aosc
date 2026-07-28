/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */
package com.atlassian.opensearch.aosc;

import com.atlassian.opensearch.aosc.model.phase.CoordinatorPhase;
import com.atlassian.opensearch.aosc.service.bulk.BulkWriteHelper;

import org.opensearch.action.bulk.BulkRequest;
import org.opensearch.action.index.IndexRequest;
import org.opensearch.action.support.WriteRequest;
import org.opensearch.common.settings.Settings;
import org.opensearch.common.xcontent.XContentType;
import org.opensearch.index.IndexingPressure;
import org.opensearch.test.OpenSearchIntegTestCase;

import org.junit.After;
import org.junit.Before;

import java.util.concurrent.TimeUnit;

/**
 * Integration tests for the adaptive batch size feature.
 *
 * <p>These tests run against a real in-process OpenSearch cluster with the AOSC
 * plugin loaded. They verify that:
 * <ul>
 *   <li>Adaptive batch cluster settings are registered and accepted.</li>
 *   <li>Migration completes with adaptive batch enabled (small docs — happy path).</li>
 *   <li>Migration completes with adaptive batch disabled (legacy FixedBatchController).</li>
 *   <li>Large docs succeed with adaptive batch via OVERLOAD → halve → retry.</li>
 *   <li>Large docs also succeed with fixed batch thanks to BatchBuilder's byte cap.</li>
 * </ul>
 */
@OpenSearchIntegTestCase.ClusterScope(scope = OpenSearchIntegTestCase.Scope.TEST, numDataNodes = 2)
public class AdaptiveBatchIT extends AoscIntegTestBase {

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
     * Verify that the new adaptive batch cluster settings are registered and
     * can be set/read without error.
     */
    public void testAdaptiveBatchSettingsRegistered() {
        client().admin()
            .cluster()
            .prepareUpdateSettings()
            .setTransientSettings(
                Settings.builder().put("aosc.backfill.controller.type", "adaptive").put("aosc.backfill.controller.batch.max_bytes", "32mb")
            )
            .get();

        Settings transientSettings = client().admin().cluster().prepareState().get().getState().metadata().transientSettings();
        assertEquals("adaptive", transientSettings.get("aosc.backfill.controller.type"));
        assertEquals("32mb", transientSettings.get("aosc.backfill.controller.batch.max_bytes"));

        client().admin()
            .cluster()
            .prepareUpdateSettings()
            .setTransientSettings(
                Settings.builder().putNull("aosc.backfill.controller.type").putNull("aosc.backfill.controller.batch.max_bytes")
            )
            .get();
    }

    /**
     * Happy path: adaptive batch enabled with small docs — migration should complete
     * and all documents should be migrated.
     */
    public void testAdaptiveBatchCompletesWithSmallDocs() throws Exception {
        String source = indexName("adaptive-small-src");
        String target = indexName("adaptive-small-tgt");

        client().admin()
            .cluster()
            .prepareUpdateSettings()
            .setTransientSettings(
                Settings.builder().put("aosc.backfill.controller.type", "adaptive").put("aosc.backfill.controller.batch.max_bytes", "64mb")
            )
            .get();

        try {
            createSourceAndTarget(source, target, 2, 2);
            indexDocs(source, 500);

            startMigration(source, target, source + "-alias", null);
            assertMigrationCompleted(source, 120);
            assertDocCountsMatch(source, target);
        } finally {
            client().admin()
                .cluster()
                .prepareUpdateSettings()
                .setTransientSettings(
                    Settings.builder().putNull("aosc.backfill.controller.type").putNull("aosc.backfill.controller.batch.max_bytes")
                )
                .get();
        }
    }

    /**
     * Adaptive batch disabled — migration with small docs should still complete
     * using the legacy FixedBatchSizeController.
     */
    public void testFixedBatchCompletesWithSmallDocs() throws Exception {
        String source = indexName("fixed-small-src");
        String target = indexName("fixed-small-tgt");

        client().admin()
            .cluster()
            .prepareUpdateSettings()
            .setTransientSettings(Settings.builder().put("aosc.backfill.controller.type", "fixed"))
            .get();

        try {
            createSourceAndTarget(source, target, 2, 2);
            indexDocs(source, 500);

            startMigration(source, target, source + "-alias", null);
            assertMigrationCompleted(source, 120);
            assertDocCountsMatch(source, target);
        } finally {
            client().admin()
                .cluster()
                .prepareUpdateSettings()
                .setTransientSettings(Settings.builder().putNull("aosc.backfill.controller.type"))
                .get();
        }
    }

    /**
     * Large docs that would exceed indexing pressure with a fixed batch size should
     * succeed with adaptive batch via OVERLOAD → observe → halve → unack → buffer → retry.
     *
     * <p>5000 × 100KB = 500MB which exceeds the ~200MB indexing pressure limit,
     * forcing AIMD to progressively halve the batch size until writes succeed.</p>
     */
    public void testAdaptiveBatchHandlesLargeDocsOverload() throws Exception {
        String source = indexName("adaptive-large-src");
        String target = indexName("adaptive-large-tgt");

        client().admin()
            .cluster()
            .prepareUpdateSettings()
            .setTransientSettings(
                Settings.builder()
                    .put("aosc.backfill.controller.type", "adaptive")
                    // Mirror production default: 512 MB AIMD ceiling vs 100 MB pressure limit.
                    // EWMA underestimates doc size initially so the first realised batch
                    // overshoots the limit, triggering AIMD multiplicative decrease until
                    // batches fit within 100 MB and writes succeed.
                    .put("aosc.backfill.controller.batch.max_bytes", "512mb")
            )
            .get();

        try {
            createSourceAndTarget(source, target, 1, 1);

            int docCount = 2000;
            int docSizeBytes = 100 * 1024; // 100 KB — 2000 × 100 KB = ~200 MB total
            String padding = randomPadding(docSizeBytes);

            int chunkSize = 100;
            for (int start = 0; start < docCount; start += chunkSize) {
                BulkRequest bulk = new BulkRequest();
                int end = Math.min(start + chunkSize, docCount);
                for (int i = start; i < end; i++) {
                    bulk.add(
                        new IndexRequest(source).id(String.valueOf(i))
                            .source("{\"title\":\"doc-" + i + "\",\"body\":\"" + padding + "\"}", XContentType.JSON)
                    );
                }
                bulk.setRefreshPolicy(WriteRequest.RefreshPolicy.NONE);
                client().bulk(bulk).actionGet();
            }
            client().admin().indices().prepareRefresh(source).get();
            assertEquals("Source should have all docs", docCount, getDocCount(source));

            startMigration(source, target, source + "-alias", null);
            assertMigrationCompleted(source, 300);
            assertDocCountsMatch(source, target);
        } finally {
            client().admin()
                .cluster()
                .prepareUpdateSettings()
                .setTransientSettings(
                    Settings.builder().putNull("aosc.backfill.controller.type").putNull("aosc.backfill.controller.batch.max_bytes")
                )
                .get();
        }
    }

    /**
     * Large docs with fixed batch size get STALLED — the migration stays ACTIVE with
     * no progress because each 100MB batch sits right at the indexing pressure limit.
     *
     * <p>The {@code BatchBuilder} caps at {@code maxBatchBytes=100MB} but with 100KB docs
     * the resulting ~100MB bulks still trigger indexing pressure backoff. The
     * {@code SimpleWriteController} retries with exponential backoff indefinitely (never
     * fatal), so the migration stays ACTIVE. Cancel it cleanly after verifying the stall.</p>
     */
    public void testFixedBatchStalledWithLargeDocsOverload() throws Exception {
        String source = indexName("fixed-large-src");
        String target = indexName("fixed-large-tgt");

        client().admin()
            .cluster()
            .prepareUpdateSettings()
            .setTransientSettings(Settings.builder().put("aosc.backfill.controller.type", "fixed"))
            .get();

        try {
            createSourceAndTarget(source, target, 1, 1);

            int docCount = 2000;
            int docSizeBytes = 100 * 1024; // 100 KB — 2000 × 100 KB = ~200 MB total
            String padding = randomPadding(docSizeBytes);

            int chunkSize = 100;
            for (int start = 0; start < docCount; start += chunkSize) {
                BulkRequest bulk = new BulkRequest();
                int end = Math.min(start + chunkSize, docCount);
                for (int i = start; i < end; i++) {
                    bulk.add(
                        new IndexRequest(source).id(String.valueOf(i))
                            .source("{\"title\":\"doc-" + i + "\",\"body\":\"" + padding + "\"}", XContentType.JSON)
                    );
                }
                bulk.setRefreshPolicy(WriteRequest.RefreshPolicy.NONE);
                client().bulk(bulk).actionGet();
            }
            client().admin().indices().prepareRefresh(source).get();
            assertEquals("Source should have all docs", docCount, getDocCount(source));

            startMigration(source, target, source + "-alias", null);

            // Poll until overload-backoff cycles have fired. The migration should
            // remain ACTIVE because fixed batch + 100KB docs triggers indexing pressure.
            final String src = source;
            assertBusy(() -> {
                CoordinatorPhase p = getStatus(src).body().phase();
                assertEquals("Fixed batch migration should be stuck in ACTIVE", CoordinatorPhase.ACTIVE, p);
                long count = getDocCount(target);
                assertTrue("Target should have fewer docs than source (stalled)", count < docCount);
            }, 20, TimeUnit.SECONDS);

            cancelMigration(source);
            CoordinatorPhase terminal = awaitTerminalPhase(source, 30);
            assertEquals("Migration should cancel cleanly", CoordinatorPhase.CANCELLED, terminal);
        } finally {
            client().admin()
                .cluster()
                .prepareUpdateSettings()
                .setTransientSettings(Settings.builder().putNull("aosc.backfill.controller.type"))
                .get();
        }
    }

    @Override
    protected Settings nodeSettings(int nodeOrdinal) {
        return Settings.builder()
            .put(super.nodeSettings(nodeOrdinal))
            // Explicit pressure limit makes the overload tests deterministic regardless
            // of JVM heap size (default is 10% of heap, which varies by environment).
            .put(IndexingPressure.MAX_INDEXING_BYTES.getKey(), "100mb")
            .build();
    }

    private static String randomPadding(int bytes) {
        StringBuilder sb = new StringBuilder(bytes);
        String chars = "abcdefghijklmnopqrstuvwxyz0123456789 ";
        for (int i = 0; i < bytes; i++) {
            sb.append(chars.charAt(i % chars.length()));
        }
        return sb.toString();
    }
}
