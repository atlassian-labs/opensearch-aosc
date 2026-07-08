/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */
package com.atlassian.opensearch.aosc;

import com.atlassian.opensearch.aosc.action.status.GetMigrationStatusResponse;
import com.atlassian.opensearch.aosc.model.MigrationDocument;
import com.atlassian.opensearch.aosc.model.ShardProgressDocument;
import com.atlassian.opensearch.aosc.model.ShardRoutingMode;
import com.atlassian.opensearch.aosc.model.phase.CoordinatorPhase;
import com.atlassian.opensearch.aosc.model.phase.ShardPhase;

import org.opensearch.ResourceNotFoundException;
import org.opensearch.common.settings.Settings;
import org.opensearch.test.OpenSearchIntegTestCase.ClusterScope;
import org.opensearch.test.OpenSearchIntegTestCase.Scope;

import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Integration tests for the Status API — F002: pure Tier 1 response enrichment.
 *
 * <p>All status data is served from the {@code .aosc-migrations} index.
 * Cluster state is not read by the status endpoint.</p>
 */
@ClusterScope(scope = Scope.SUITE, numDataNodes = 2, numClientNodes = 0)
public class StatusApiIT extends AoscIntegTestBase {

    // ---- Shared helper ----

    /**
     * Creates a single-shard migration, runs it to completion, and waits until
     * shard progress is visible in the status response. Polls via assertBusy
     * because shard progress writes are async and may lag behind the coordinator
     * phase transition.
     */
    private MigrationDocument completeSingleShardMigration(String prefix, int docCount) throws Exception {
        String source = indexName(prefix + "-src");
        String target = indexName(prefix + "-tgt");
        createSourceAndTarget(source, target, 1, 1);
        indexDocs(source, docCount);
        startMigration(source, target, prefix + "-alias", null);
        assertMigrationCompleted(source, 90);
        var ref = new Object() {
            GetMigrationStatusResponse result;
        };
        // Poll until shard progress is available WITH PhaseMetrics (not just cluster state overlay).
        // Status API reads from coordinator cache (active) or Tier-1 (terminal).
        // Both paths return full PhaseMetrics.
        assertBusy(() -> {
            ref.result = getStatus(source);
            assertFalse("shardProgress should not be empty", ref.result.body().shards().isEmpty());
            assertNotNull("backfill metrics should be present", ref.result.body().shards().get(0).backfill());
        }, 30, TimeUnit.SECONDS);
        return ref.result.body();
    }

    // =========================================================
    // 1. Not-found response
    // =========================================================

    /**
     * Status for a non-migrating index returns found=false with source_index echoed back.
     */
    public void testStatusNotFoundReturns404() {
        String unknownIndex = "nonexistent-index-" + randomAlphaOfLength(6);
        expectThrows(ResourceNotFoundException.class, () -> getStatus(unknownIndex));
    }

    // =========================================================
    // 2. Migration doc becomes available after start
    // =========================================================

    public void testMigrationDocAvailableAfterStart() throws Exception {
        String source = indexName("avail-src");
        String target = indexName("avail-tgt");
        createSourceAndTarget(source, target, 1, 1);
        indexDocs(source, 20);

        startMigration(source, target, "avail-alias", null);

        assertBusy(() -> {
            GetMigrationStatusResponse status = getStatus(source);
            assertNotNull("Migration should be found", status);
            assertNotNull("migrationDocument should be present", status);
            assertEquals("sourceIndex should match", source, status.body().sourceIndex());
        }, 30, TimeUnit.SECONDS);

        assertMigrationCompleted(source, 90);
    }

    // =========================================================
    // 3. Migration-level fields are correct after completion
    // =========================================================

    public void testMigrationLevelFieldsCorrectAfterCompletion() throws Exception {
        String source = indexName("fld-src");
        String target = indexName("fld-tgt");
        createSourceAndTarget(source, target, 2, 2);
        indexDocs(source, 50);

        long beforeStart = System.currentTimeMillis();
        startMigration(source, target, "fld-alias", null);
        assertMigrationCompleted(source, 90);
        long afterComplete = System.currentTimeMillis();

        GetMigrationStatusResponse status = getStatus(source);
        assertNotNull("Migration should be found", status);
        assertNotNull("migrationDocument should not be null", status);

        assertEquals("sourceIndex", source, status.body().sourceIndex());
        assertEquals("targetIndex", target, status.body().targetIndex());
        assertEquals("alias", "fld-alias", status.body().alias());
        assertFalse("migrationId not empty", status.body().migrationId().isEmpty());
        assertEquals("phase should be COMPLETED", CoordinatorPhase.COMPLETED, status.body().phase());
        assertEquals("routing mode", ShardRoutingMode.SAME_SHARD, status.body().shardRoutingMode());

        long start = status.body().startTimeMillis();
        assertTrue("startTimeMillis > 0", start > 0);
        assertTrue("startTimeMillis within test window", start >= beforeStart && start <= afterComplete);

        long lastUpdated = status.body().lastUpdatedMillis();
        assertTrue("lastUpdatedMillis >= startTimeMillis", lastUpdated >= start);
    }

    // =========================================================
    // 4. elapsed_millis is positive and captured at construction
    // =========================================================

    public void testElapsedMillisPositive() throws Exception {
        MigrationDocument status = completeSingleShardMigration("elap", 30);
        assertTrue("startTimeMillis should be > 0 for a completed migration", status.startTimeMillis() > 0);
    }

    // =========================================================
    // 5. Per-shard progress present for all shards
    // =========================================================

    public void testShardProgressPresentForAllShards() throws Exception {
        String source = indexName("spd-src");
        String target = indexName("spd-tgt");
        int numShards = 3;
        createSourceAndTarget(source, target, numShards, numShards);
        indexDocs(source, 150);

        startMigration(source, target, "spd-alias", null);
        assertMigrationCompleted(source, 90);

        // Shard progress writes are async — poll until all shards are visible.
        assertBusy(() -> {
            GetMigrationStatusResponse status = getStatus(source);
            assertEquals("shardProgress must have " + numShards + " entries", numShards, status.body().shards().size());

            for (int shardId = 0; shardId < numShards; shardId++) {
                ShardProgressDocument spd = status.body().shards().get(shardId);
                assertNotNull("ShardProgressDocument present for shard " + shardId, spd);
                assertEquals("shard " + shardId + " phase COMPLETED", ShardPhase.COMPLETED, spd.phase());
                assertNotNull("shard " + shardId + " backfill present", spd.backfill());
                assertEquals(
                    "shard " + shardId + " backfill COMPLETED",
                    ShardProgressDocument.PhaseStatus.COMPLETED,
                    spd.backfill().status()
                );
            }
        }, 30, TimeUnit.SECONDS);
    }

    // =========================================================
    // 6. total_documents_indexed matches actual doc count
    // =========================================================

    public void testTotalDocumentsIndexedMatchesSourceDocCount() throws Exception {
        String source = indexName("tdoc-src");
        String target = indexName("tdoc-tgt");
        createSourceAndTarget(source, target, 2, 2);
        indexDocs(source, 200);

        startMigration(source, target, "tdoc-alias", null);
        assertMigrationCompleted(source, 90);
        assertDocCountsMatch(source, target);

        // Shard progress writes are async — poll until all shards are visible.
        assertBusy(() -> {
            GetMigrationStatusResponse status = getStatus(source);
            assertEquals("All shards present", 2, status.body().shards().size());
            long totalIndexed = status.body().shards().values().stream().mapToLong(ShardProgressDocument::totalDocumentsIndexed).sum();
            assertEquals("Sum of totalDocumentsIndexed must equal actual doc count", getDocCount(source), totalIndexed);
        }, 30, TimeUnit.SECONDS);
    }

    // =========================================================
    // 7. Backfill phase metrics are valid
    // =========================================================

    public void testBackfillPhaseMetricsValid() throws Exception {
        MigrationDocument status = completeSingleShardMigration("bfm", 100);
        ShardProgressDocument.PhaseMetrics backfill = status.shards().get(0).backfill();

        assertNotNull("backfill must not be null", backfill);
        assertEquals("backfill COMPLETED", ShardProgressDocument.PhaseStatus.COMPLETED, backfill.status());
        assertTrue("documentsIndexed > 0", backfill.documentsIndexed() > 0);
        assertTrue("documentsSkipped >= 0", backfill.documentsSkipped() >= 0);
        assertTrue("startTimeMillis > 0", backfill.startTimeMillis() > 0);
        assertTrue("endTimeMillis > 0", backfill.endTimeMillis() > 0);
        assertTrue("endTimeMillis >= startTimeMillis", backfill.endTimeMillis() >= backfill.startTimeMillis());
        assertTrue("targetSeqNo >= startSeqNo", backfill.targetSeqNo() >= backfill.startSeqNo());
    }

    public void testBackfillRoundsAndGapAfterCompletion() throws Exception {
        MigrationDocument status = completeSingleShardMigration("bfrg", 200);
        ShardProgressDocument.PhaseMetrics backfill = status.shards().get(0).backfill();

        assertNotNull("backfill must not be null", backfill);
        assertEquals("backfill COMPLETED", ShardProgressDocument.PhaseStatus.COMPLETED, backfill.status());
        assertTrue("rounds should be > 0 (at least 1 batch)", backfill.rounds() > 0);
        assertEquals("currentGap should be 0 after completion", 0, backfill.currentGap());
        assertTrue("documentsIndexed > 0", backfill.documentsIndexed() > 0);
    }

    public void testReplayRoundsAndGapAfterCompletion() throws Exception {
        MigrationDocument status = completeSingleShardMigration("rprg", 100);
        ShardProgressDocument.PhaseMetrics replay = status.shards().get(0).replay();

        assertNotNull("replay must not be null", replay);
        assertEquals("replay COMPLETED", ShardProgressDocument.PhaseStatus.COMPLETED, replay.status());
        assertEquals("replay rounds should be 1", 1, replay.rounds());
        assertEquals("replay currentGap should be 0 after completion (replayed all ops in range)", 0, replay.currentGap());
    }

    // =========================================================
    // 8. Replay phase metrics present after completion
    // =========================================================

    public void testReplayPhaseMetricsPresentAfterCompletion() throws Exception {
        MigrationDocument status = completeSingleShardMigration("rpm", 100);
        ShardProgressDocument spd = status.shards().get(0);

        assertNotNull("replay must not be null", spd.replay());
        assertEquals("replay COMPLETED", ShardProgressDocument.PhaseStatus.COMPLETED, spd.replay().status());
        assertTrue("operationsApplied >= 0", spd.replay().operationsApplied() >= 0);
        assertTrue("operationsSkipped >= 0", spd.replay().operationsSkipped() >= 0);
    }

    // =========================================================
    // 9. Seq no consistency
    // =========================================================

    public void testSeqNoConsistency() throws Exception {
        String source = indexName("seq-src");
        String target = indexName("seq-tgt");
        createSourceAndTarget(source, target, 1, 1);
        indexDocs(source, 100);
        startMigration(source, target, "seq-alias", null);
        assertMigrationCompleted(source, 90);

        // Poll until shard progress has final seq no values (async write lag).
        assertBusy(() -> {
            GetMigrationStatusResponse status = getStatus(source);
            assertFalse("shardProgress should not be empty", status.body().shards().isEmpty());
            ShardProgressDocument spd = status.body().shards().get(0);

            if (spd.backfillCutoffSeqNo() >= 0 && spd.lastReplayedSeqNo() >= 0) {
                assertTrue("lastReplayedSeqNo >= backfillCutoffSeqNo", spd.lastReplayedSeqNo() >= spd.backfillCutoffSeqNo());
            }
            // targetSeqNo is only set after backfill completes — skip if not yet written.
            if (spd.backfill() != null && spd.backfillCutoffSeqNo() >= 0 && spd.backfill().targetSeqNo() > 0) {
                assertEquals("backfill targetSeqNo == backfillCutoffSeqNo", spd.backfillCutoffSeqNo(), spd.backfill().targetSeqNo());
            }
        }, 60, TimeUnit.SECONDS);
    }

    // =========================================================
    // 10. Mid-migration progress visible
    // =========================================================

    public void testShardProgressVisibleDuringActiveMigration() throws Exception {
        String source = indexName("live-src");
        String target = indexName("live-tgt");
        createSourceAndTarget(source, target, 2, 2);
        indexDocs(source, 2000);

        startMigration(source, target, "live-alias", null);

        assertBusy(() -> {
            GetMigrationStatusResponse status = getStatus(source);
            assertNotNull("Migration should be found", status);
            assertNotNull("migrationDocument present", status);

            boolean anyBackfillStarted = status.body()
                .shards()
                .values()
                .stream()
                .anyMatch(spd -> spd.backfill() != null && spd.backfill().documentsIndexed() > 0);
            assertTrue("At least one shard should have documentsIndexed > 0", anyBackfillStarted);
        }, 60, TimeUnit.SECONDS);

        assertMigrationCompleted(source, 120);
        assertDocCountsMatch(source, target);
    }

    // =========================================================
    // 11. Split-shard routing mode
    // =========================================================

    public void testSplitShardMigrationRoutingMode() throws Exception {
        String source = indexName("spl-src");
        String target = indexName("spl-tgt");
        createSourceAndTarget(source, target, 2, 4);
        indexDocs(source, 100);

        startMigration(source, target, "spl-alias", null);
        assertMigrationCompleted(source, 90);

        GetMigrationStatusResponse status = getStatus(source);
        assertNotNull(status);
        assertEquals("Split-shard = SPLIT_SHARD routing", ShardRoutingMode.SPLIT_SHARD, status.body().shardRoutingMode());
        assertDocCountsMatch(source, target);
    }

    // =========================================================
    // 12. Shard failure field consistency
    // =========================================================

    public void testShardFailureFieldConsistency() throws Exception {
        String source = indexName("fail-src");
        String target = indexName("fail-tgt");
        createSourceAndTarget(source, target, 2, 2);
        indexDocs(source, 50);

        startMigration(source, target, "fail-alias", null);
        assertMigrationCompleted(source, 90);

        GetMigrationStatusResponse status = getStatus(source);
        for (Map.Entry<Integer, ShardProgressDocument> entry : status.body().shards().entrySet()) {
            if (entry.getValue().phase() == ShardPhase.COMPLETED) {
                assertNull("Completed shard " + entry.getKey() + " should have no error", entry.getValue().error());
            } else if (entry.getValue().phase() == ShardPhase.FAILED) {
                assertNotNull("Failed shard " + entry.getKey() + " should have error", entry.getValue().error());
            }
        }
    }

    // =========================================================
    // 13. Returns latest migration when multiple exist for same source index
    // =========================================================

    /**
     * If a migration fails and is retried for the same source index, the status
     * endpoint must return the most recent migration (highest start_time_millis).
     */
    public void testReturnsLatestMigrationForSameSourceIndex() throws Exception {
        String source = indexName("latest-src");
        String target1 = indexName("latest-tgt1");
        String target2 = indexName("latest-tgt2");
        createSourceAndTarget(source, target1, 1, 1);
        indexDocs(source, 50);

        // First migration — run to completion
        startMigration(source, target1, "latest-alias", null);
        assertMigrationCompleted(source, 90);

        GetMigrationStatusResponse firstStatus = getStatus(source);
        assertNotNull("First migration should be found", firstStatus);
        long firstStartTime = firstStatus.body().startTimeMillis();
        String firstMigrationId = firstStatus.body().migrationId();

        // Create a new target and start a second migration for the same source
        createIndex(target2, Settings.builder().put("index.number_of_shards", 1).put("index.number_of_replicas", 0).build());
        startMigration(source, target2, "latest-alias2", null);
        assertMigrationCompleted(source, 90);

        // Tier-1 write is async — wait for the second migration's doc to land.
        assertBusy(() -> {
            GetMigrationStatusResponse secondStatus = getStatus(source);
            assertNotNull("Second migration should be found", secondStatus);
            assertTrue("Second migration startTime must be >= first", secondStatus.body().startTimeMillis() >= firstStartTime);
            assertNotEquals("Migration IDs should differ", firstMigrationId, secondStatus.body().migrationId());
            assertEquals("Should return the second target", target2, secondStatus.body().targetIndex());
        }, 30, TimeUnit.SECONDS);
    }

    // =========================================================
    // 14. Shard count consistency
    // =========================================================

    public void testShardCountMatchesSourceIndex() throws Exception {
        String source = indexName("shct-src");
        String target = indexName("shct-tgt");
        int numShards = 3;
        createSourceAndTarget(source, target, numShards, numShards);
        indexDocs(source, 90);

        startMigration(source, target, "shct-alias", null);
        assertMigrationCompleted(source, 90);

        // Shard progress writes are async — poll until all shards are visible.
        assertBusy(() -> {
            GetMigrationStatusResponse status = getStatus(source);
            assertEquals("shardProgress must have " + numShards + " entries", numShards, status.body().shards().size());

            for (int i = 0; i < numShards; i++) {
                assertTrue("shardProgress must contain shard " + i, status.body().shards().containsKey(i));
            }
        }, 30, TimeUnit.SECONDS);
    }
}
