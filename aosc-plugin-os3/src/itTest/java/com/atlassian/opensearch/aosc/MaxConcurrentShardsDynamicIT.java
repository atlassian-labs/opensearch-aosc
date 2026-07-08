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

import org.opensearch.common.settings.Settings;
import org.opensearch.test.OpenSearchIntegTestCase.ClusterScope;
import org.opensearch.test.OpenSearchIntegTestCase.Scope;

import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Integration tests for dynamic updates to {@code aosc.backfill.max_concurrent_per_node}
 * during an active migration.
 *
 * <p>Uses {@link Scope#TEST} so each test gets a fresh cluster with independent settings.</p>
 */
@ClusterScope(scope = Scope.TEST, numDataNodes = 2, numClientNodes = 0)
public class MaxConcurrentShardsDynamicIT extends AoscIntegTestBase {

    @Override
    protected Settings nodeSettings(int nodeOrdinal) {
        return Settings.builder()
            .put(super.nodeSettings(nodeOrdinal))
            .put("aosc.backfill.max_concurrent_per_node", 0) // start paused
            .build();
    }

    /**
     * Start with limit=0 (emergency pause), verify no progress, then increase
     * to 10 and verify migration completes. Tests the full pause→unpause cycle
     * via dynamic setting update.
     */
    public void testDynamicIncreaseUnpausesThrottledShards() throws Exception {
        String source = indexName("dyn1-src");
        String target = indexName("dyn1-tgt");
        createSourceAndTarget(source, target, 4, 4);
        indexDocs(source, 300);

        startMigration(source, target, "dyn1-alias", null);

        // With limit=0, all shards should be parked in PENDING — no backfill progress
        assertBusy(() -> {
            GetMigrationStatusResponse status = getStatus(source);
            assertTrue("Migration should be found", status.body() != null);
            Map<Integer, ShardProgressDocument> shards = status.body().shards();
            // Shards should exist but none should have started backfilling
            boolean anyBackfillStarted = shards.values()
                .stream()
                .anyMatch(spd -> spd.backfill() != null && spd.backfill().documentsIndexed() > 0);
            assertFalse("No shards should have backfill progress while paused", anyBackfillStarted);
        }, 10, TimeUnit.SECONDS);

        // Unpause — dynamically increase limit to 10
        client().admin()
            .cluster()
            .prepareUpdateSettings()
            .setTransientSettings(Settings.builder().put("aosc.backfill.max_concurrent_per_node", 10))
            .get();

        // Migration should now complete
        assertMigrationCompleted(source, 120);
        assertDocCountsMatch(source, target);
    }

    /**
     * Start with a generous limit (10), begin migration, then dynamically reduce
     * to 1. Migration should still complete — the decrease should not revoke
     * existing permits or cause deadlock.
     */
    public void testDynamicDecreaseDoesNotBreakActiveMigration() throws Exception {
        // Override node settings for this test — start with limit=10
        // (nodeSettings sets 0, so we override dynamically before starting)
        client().admin()
            .cluster()
            .prepareUpdateSettings()
            .setTransientSettings(Settings.builder().put("aosc.backfill.max_concurrent_per_node", 10))
            .get();

        String source = indexName("dyn2-src");
        String target = indexName("dyn2-tgt");
        createSourceAndTarget(source, target, 6, 6);
        indexDocs(source, 500);

        startMigration(source, target, "dyn2-alias", null);

        // Wait for some shards to start backfilling
        assertBusy(() -> {
            GetMigrationStatusResponse status = getStatus(source);
            assertTrue("Migration should be found", status.body() != null);
            boolean anyStarted = status.body()
                .shards()
                .values()
                .stream()
                .anyMatch(spd -> spd.backfill() != null && spd.backfill().documentsIndexed() > 0);
            assertTrue("At least one shard should have started backfilling", anyStarted);
        }, 30, TimeUnit.SECONDS);

        // Dynamically reduce limit to 1
        client().admin()
            .cluster()
            .prepareUpdateSettings()
            .setTransientSettings(Settings.builder().put("aosc.backfill.max_concurrent_per_node", 1))
            .get();

        // Migration should still complete — existing permits are not revoked
        assertMigrationCompleted(source, 120);
        assertDocCountsMatch(source, target);
    }
}
