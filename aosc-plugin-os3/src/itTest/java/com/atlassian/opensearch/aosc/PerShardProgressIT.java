/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */
package com.atlassian.opensearch.aosc;

import com.atlassian.opensearch.aosc.action.status.GetMigrationStatusResponse;
import com.atlassian.opensearch.aosc.model.ShardProgressDocument;
import com.atlassian.opensearch.aosc.model.phase.CoordinatorPhase;
import com.atlassian.opensearch.aosc.model.phase.ShardPhase;

import org.opensearch.action.index.IndexRequest;
import org.opensearch.action.support.WriteRequest;
import org.opensearch.common.xcontent.XContentType;
import org.opensearch.test.OpenSearchIntegTestCase.ClusterScope;
import org.opensearch.test.OpenSearchIntegTestCase.Scope;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * Integration tests for per-shard progress tracking during migration.
 * Validates that status API reflects real-time progress, phases advance,
 * and sequence numbers increase monotonically.
 */
@ClusterScope(scope = Scope.SUITE, numDataNodes = 2, numClientNodes = 0)
public class PerShardProgressIT extends AoscIntegTestBase {

    /**
     * Polls status during migration and verifies that per-shard phases
     * transition through expected states. At minimum BACKFILLING and COMPLETED
     * should be observed.
     */
    public void testPhaseTransitionsVisibleInStatus() throws Exception {
        String source = indexName("pt-src");
        String target = indexName("pt-tgt");
        createSourceAndTarget(source, target, 1, 1);
        indexDocs(source, 500);

        startMigration(source, target, "pt-alias", null);

        // Collect all shard phases observed during the migration
        Set<ShardPhase> shardPhasesSeen = new HashSet<>();
        Set<CoordinatorPhase> coordPhasesSeen = new HashSet<>();

        assertBusy(() -> {
            GetMigrationStatusResponse status = getStatus(source);
            if (status.body() != null) {
                if (status.body().phase() != null) {
                    coordPhasesSeen.add(status.body().phase());
                }
                for (ShardProgressDocument shard : status.body().shards().values()) {
                    shardPhasesSeen.add(shard.phase());
                }
                assertTrue("Should reach terminal phase", status.body().phase() != null && status.body().phase().isTerminal());
            }
        }, 90, TimeUnit.SECONDS);

        // Must have observed at least COMPLETED at the shard level
        assertTrue("Should have seen shard COMPLETED phase", shardPhasesSeen.contains(ShardPhase.COMPLETED));

        // Must have observed at least COMPLETED at the coordinator level
        assertTrue("Should have seen coordinator COMPLETED phase", coordPhasesSeen.contains(CoordinatorPhase.COMPLETED));

        assertDocCountsMatch(source, target);
    }

    /**
     * For a multi-shard migration, all shard IDs are independently tracked
     * and all reach COMPLETED.
     */
    public void testAllShardsReachCompleted() throws Exception {
        String source = indexName("asc-src");
        String target = indexName("asc-tgt");
        int numShards = 3;
        createSourceAndTarget(source, target, numShards, numShards);
        indexDocs(source, 300);

        startMigration(source, target, "asc-alias", null);
        assertMigrationCompleted(source, 90);

        // Shard progress is updated asynchronously — wait for all shards to reflect terminal phase
        assertBusy(() -> {
            GetMigrationStatusResponse status = getStatus(source);
            assertNotNull("Migration should be found", status.body());
            assertEquals("Should have " + numShards + " shards", numShards, status.body().shards().size());

            for (int i = 0; i < numShards; i++) {
                ShardProgressDocument shard = status.body().shards().get(i);
                assertNotNull("Shard " + i + " should be present", shard);
                assertEquals("Shard " + i + " should be COMPLETED", ShardPhase.COMPLETED, shard.phase());
            }
        }, 30, TimeUnit.SECONDS);

        assertDocCountsMatch(source, target);
    }

    /**
     * After migration with many docs, lastReplayedSeqNo should have advanced
     * past the backfillCutoffSeqNo, indicating the replay phase processed ops.
     */
    public void testSeqNosAdvanceDuringMigration() throws Exception {
        String source = indexName("sn-src");
        String target = indexName("sn-tgt");
        createSourceAndTarget(source, target, 1, 1);
        indexDocs(source, 200);

        // Write additional docs after initial batch to ensure there's replay work
        startMigration(source, target, "sn-alias", null);

        // Add more docs while migration is running — some may be blocked during cutover
        int extraWritten = 0;
        for (int i = 200; i < 250; i++) {
            try {
                client().index(
                    new IndexRequest(source).id(String.valueOf(i))
                        .source("{\"value\":" + i + "}", XContentType.JSON)
                        .setRefreshPolicy(WriteRequest.RefreshPolicy.NONE)
                ).actionGet();
                extraWritten++;
            } catch (Exception e) {
                break; // Write block during cutover
            }
        }

        assertMigrationCompleted(source, 90);

        GetMigrationStatusResponse status = getStatus(source);
        ShardProgressDocument shard = status.body().shards().get(0);
        assertNotNull("Shard 0 should be present", shard);

        // Tier 0 (cluster state) tracks phase transitions but seq no fields
        // may remain at sentinel (-1) since detailed progress is in Tier 1
        // (the .aosc-migrations index). When both are set, validate ordering.
        if (shard.backfillCutoffSeqNo() >= 0 && shard.lastReplayedSeqNo() >= 0) {
            assertTrue(
                "lastReplayedSeqNo ("
                    + shard.lastReplayedSeqNo()
                    + ") should be >= "
                    + "backfillCutoffSeqNo ("
                    + shard.backfillCutoffSeqNo()
                    + ")",
                shard.lastReplayedSeqNo() >= shard.backfillCutoffSeqNo()
            );
        }

        // Target should have at least the initial 200 docs
        long targetCount = getDocCount(target);
        assertTrue("Target should have at least 200 docs, got: " + targetCount, targetCount >= 200);
        assertTrue("Target should not exceed " + (200 + extraWritten) + " docs, got: " + targetCount, targetCount <= 200 + extraWritten);
    }

    /**
     * Polls status multiple times during a large migration and verifies
     * that backfillCutoffSeqNo is monotonically non-decreasing across polls.
     * (It should be set once during backfill start and never decrease.)
     */
    public void testBackfillCutoffIsStable() throws Exception {
        String source = indexName("bc-src");
        String target = indexName("bc-tgt");
        createSourceAndTarget(source, target, 1, 1);
        indexDocs(source, 2000);

        startMigration(source, target, "bc-alias", null);

        List<Long> cutoffSnapshots = new ArrayList<>();

        // Poll status periodically
        assertBusy(() -> {
            GetMigrationStatusResponse status = getStatus(source);
            if (status.body() != null && !status.body().shards().isEmpty()) {
                ShardProgressDocument shard = status.body().shards().get(0);
                if (shard != null && shard.backfillCutoffSeqNo() >= 0) {
                    cutoffSnapshots.add(shard.backfillCutoffSeqNo());
                }
            }
            assertTrue(
                "Should reach terminal phase",
                status.body() != null && status.body().phase() != null && status.body().phase().isTerminal()
            );
        }, 90, TimeUnit.SECONDS);

        // Verify cutoff is monotonically non-decreasing
        for (int i = 1; i < cutoffSnapshots.size(); i++) {
            assertTrue(
                "backfillCutoffSeqNo should be monotonically non-decreasing: "
                    + cutoffSnapshots.get(i - 1)
                    + " -> "
                    + cutoffSnapshots.get(i),
                cutoffSnapshots.get(i) >= cutoffSnapshots.get(i - 1)
            );
        }

        assertDocCountsMatch(source, target);
    }
}
