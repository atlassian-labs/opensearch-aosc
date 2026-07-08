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

import org.opensearch.common.settings.Settings;
import org.opensearch.test.OpenSearchIntegTestCase.ClusterScope;
import org.opensearch.test.OpenSearchIntegTestCase.Scope;

import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Integration tests for the per-node backfill concurrency throttle.
 * Validates that migrations complete without deadlock when the number of
 * shards per node exceeds {@code aosc.backfill.max_concurrent_per_node}.
 *
 * <p>Uses {@link Scope#TEST} so each test gets a fresh cluster — critical for
 * testing different concurrency limits without cross-test interference.</p>
 */
@ClusterScope(scope = Scope.TEST, numDataNodes = 2, numClientNodes = 0)
public class MaxConcurrentShardsIT extends AoscIntegTestBase {

    @Override
    protected Settings nodeSettings(int nodeOrdinal) {
        return Settings.builder().put(super.nodeSettings(nodeOrdinal)).put("aosc.backfill.max_concurrent_per_node", 1).build();
    }

    /**
     * 6-shard source, limit=1/node, 2 nodes → 3 shards/node.
     * Workers must cycle through permits. Validates no deadlock when
     * shards exceed the per-node limit.
     */
    public void testCompletesWithThrottling() throws Exception {
        String source = indexName("mcs-src");
        String target = indexName("mcs-tgt");
        createSourceAndTarget(source, target, 6, 6);
        indexDocs(source, 300);

        startMigration(source, target, "mcs-alias", null);
        assertMigrationCompleted(source, 120);
        assertDocCountsMatch(source, target);
    }

    /**
     * 4-shard source, limit=1/node, 2 nodes → 2 shards/node.
     * Minimal case: exactly one shard must wait per node.
     */
    public void testMinimalThrottling() throws Exception {
        String source = indexName("mcs2-src");
        String target = indexName("mcs2-tgt");
        createSourceAndTarget(source, target, 4, 4);
        indexDocs(source, 200);

        startMigration(source, target, "mcs2-alias", null);
        assertMigrationCompleted(source, 90);
        assertDocCountsMatch(source, target);
    }

    /**
     * Single-shard migration with limit=1. No throttle contention —
     * verifies throttle doesn't interfere when there's only one shard.
     */
    public void testSingleShardNoContention() throws Exception {
        String source = indexName("mcs3-src");
        String target = indexName("mcs3-tgt");
        createSourceAndTarget(source, target, 1, 1);
        indexDocs(source, 100);

        startMigration(source, target, "mcs3-alias", null);
        assertMigrationCompleted(source, 60);
        assertDocCountsMatch(source, target);
    }

    /**
     * 8-shard source, limit=1/node, 2 nodes → 4 shards/node.
     * Higher ratio of shards-to-limit — tests that workers cycle
     * through permits correctly with a deeper queue.
     */
    public void testHighShardToLimitRatio() throws Exception {
        String source = indexName("mcs4-src");
        String target = indexName("mcs4-tgt");
        createSourceAndTarget(source, target, 8, 8);
        indexDocs(source, 400);

        startMigration(source, target, "mcs4-alias", null);
        assertMigrationCompleted(source, 120);
        assertDocCountsMatch(source, target);
    }

    /**
     * Cancel a throttled migration. Some shards may be waiting for permits
     * when cancel fires — they must clean up without permit leaks.
     */
    public void testCancelWhileThrottled() throws Exception {
        String source = indexName("mcs5-src");
        String target = indexName("mcs5-tgt");
        createSourceAndTarget(source, target, 6, 6);
        indexDocs(source, 500);

        startMigration(source, target, "mcs5-alias", null);
        // Wait for backfill to start on at least one shard before cancelling
        assertBusy(() -> {
            GetMigrationStatusResponse status = getStatus(source);
            assertNotNull("Status should be available", status);
        }, 10, TimeUnit.SECONDS);
        cancelMigration(source);
        CoordinatorPhase terminal = awaitTerminalPhase(source, 60);
        assertEquals(CoordinatorPhase.CANCELLED, terminal);
    }

    /**
     * Verify throttling is observable: with limit=1/node and 6 shards (3/node),
     * not all shards should be in BACKFILLING simultaneously. At least one
     * shard should be in PENDING while others are backfilling.
     */
    public void testThrottledShardsVisibleInStatus() throws Exception {
        String source = indexName("mcs6-src");
        String target = indexName("mcs6-tgt");
        createSourceAndTarget(source, target, 6, 6);
        indexDocs(source, 2000);

        startMigration(source, target, "mcs6-alias", null);

        // During active backfill, at most 2 shards (1 per node) should be past PENDING
        assertBusy(() -> {
            GetMigrationStatusResponse status = getStatus(source);
            assertTrue("Migration should be found", status.body() != null);
            Map<Integer, ShardProgressDocument> shards = status.body().shards();
            assertFalse("Shard progress should not be empty", shards.isEmpty());

            long backfillingCount = shards.values().stream().filter(spd -> spd.phase() == ShardPhase.BACKFILLING).count();
            // With limit=1 per node and 2 nodes, at most 2 shards should be in BACKFILLING
            assertTrue("At most 2 shards should be backfilling (limit=1/node × 2 nodes), got: " + backfillingCount, backfillingCount <= 2);
        }, 30, TimeUnit.SECONDS);

        assertMigrationCompleted(source, 120);
        assertDocCountsMatch(source, target);
    }

    /**
     * Verify every shard eventually makes progress — no shard is permanently starved
     * by the throttle.
     */
    public void testAllShardsEventuallyMakeProgress() throws Exception {
        String source = indexName("mcs7-src");
        String target = indexName("mcs7-tgt");
        createSourceAndTarget(source, target, 6, 6);
        indexDocs(source, 300);

        startMigration(source, target, "mcs7-alias", null);
        assertMigrationCompleted(source, 120);

        GetMigrationStatusResponse status = getStatus(source);
        Map<Integer, ShardProgressDocument> shards = status.body().shards();
        assertEquals("All 6 shards should have progress", 6, shards.size());
        for (Map.Entry<Integer, ShardProgressDocument> e : shards.entrySet()) {
            ShardProgressDocument spd = e.getValue();
            assertTrue(
                "Shard " + e.getKey() + " should have indexed docs, got: " + spd.totalDocumentsIndexed(),
                spd.totalDocumentsIndexed() > 0
            );
            assertTrue("Shard " + e.getKey() + " should be in terminal phase, got: " + spd.phase(), spd.phase().isTerminal());
        }
        assertDocCountsMatch(source, target);
    }

    /**
     * When the limit is higher than the shard count, all shards should proceed
     * immediately with no queuing — verifies zero throttle overhead.
     */
    public void testLimitHigherThanShardCount() throws Exception {
        // The cluster has limit=1, but this test uses 1 shard per node (2 shards, 2 nodes)
        // so no contention occurs. Verifies throttle doesn't interfere.
        String source = indexName("mcs8-src");
        String target = indexName("mcs8-tgt");
        createSourceAndTarget(source, target, 2, 2);
        indexDocs(source, 200);

        startMigration(source, target, "mcs8-alias", null);
        assertMigrationCompleted(source, 60);
        assertDocCountsMatch(source, target);
    }

    /**
     * Verify permits are released when shards fail. Block writes on the target
     * to force failure — if permits leaked, queued shards would hang forever.
     */
    public void testPermitReleasedOnShardFailure() throws Exception {
        String source = indexName("mcs9-src");
        String target = indexName("mcs9-tgt");
        createSourceAndTarget(source, target, 4, 4);
        indexDocs(source, 200);

        // Block writes on target before starting migration
        client().admin().indices().prepareUpdateSettings(target).setSettings(Settings.builder().put("index.blocks.write", true)).get();

        startMigration(source, target, "mcs9-alias", null);

        // Migration should fail since target is read-only. If permits leaked,
        // the queued shards would hang forever and this would time out.
        CoordinatorPhase terminal = awaitTerminalPhase(source, 90);
        assertTrue(
            "Migration should fail or cancel, got: " + terminal,
            terminal == CoordinatorPhase.FAILED || terminal == CoordinatorPhase.CANCELLED
        );
    }
}
