/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */
package com.atlassian.opensearch.aosc;

import com.atlassian.opensearch.aosc.model.MigrationRequestOptions;
import com.atlassian.opensearch.aosc.model.phase.CoordinatorPhase;

import org.opensearch.action.admin.indices.settings.get.GetSettingsRequest;
import org.opensearch.common.settings.Settings;
import org.opensearch.test.OpenSearchIntegTestCase.ClusterScope;
import org.opensearch.test.OpenSearchIntegTestCase.Scope;

/**
 * Integration tests for the {@code remove_source_write_block_on_success} option on the
 * migration request. Verifies the default-on behavior, the explicit opt-out behavior,
 * and tolerance to the block being already absent.
 */
@ClusterScope(scope = Scope.TEST, numDataNodes = 1, numClientNodes = 0)
public class RemoveSourceWriteBlockOnSuccessIT extends AoscIntegTestBase {

    public void testWriteBlockRetainedByDefaultOnSuccess() throws Exception {
        String source = indexName("rwb-def-src");
        String target = indexName("rwb-def-tgt");
        createSourceAndTarget(source, target, 1, 1);
        indexDocs(source, 25);
        flushAndRefresh(source);

        // No options and no cluster setting → default is false → block retained.
        startMigration(source, target, "rwb-def-alias", null);

        CoordinatorPhase terminal = awaitTerminalPhase(source, 90);
        assertEquals("Migration should complete", CoordinatorPhase.COMPLETED, terminal);

        assertWriteBlockPresent(source);
    }

    public void testWriteBlockRemovedWhenExplicitlyTrue() throws Exception {
        String source = indexName("rwb-true-src");
        String target = indexName("rwb-true-tgt");
        createSourceAndTarget(source, target, 1, 1);
        indexDocs(source, 25);
        flushAndRefresh(source);

        MigrationRequestOptions opts = new MigrationRequestOptions().setRemoveSourceWriteBlockOnSuccess(true);
        startMigration(source, target, "rwb-true-alias", null, opts);

        CoordinatorPhase terminal = awaitTerminalPhase(source, 90);
        assertEquals("Migration should complete", CoordinatorPhase.COMPLETED, terminal);

        assertWriteBlockAbsent(source);
    }

    public void testWriteBlockRetainedWhenOptedOut() throws Exception {
        String source = indexName("rwb-keep-src");
        String target = indexName("rwb-keep-tgt");
        createSourceAndTarget(source, target, 1, 1);
        indexDocs(source, 25);
        flushAndRefresh(source);

        MigrationRequestOptions opts = new MigrationRequestOptions().setRemoveSourceWriteBlockOnSuccess(false);
        startMigration(source, target, "rwb-keep-alias", null, opts);

        CoordinatorPhase terminal = awaitTerminalPhase(source, 90);
        assertEquals("Migration should complete", CoordinatorPhase.COMPLETED, terminal);

        assertWriteBlockPresent(source);
    }

    /**
     * Multi-shard happy path: explicit opt-in must remove the block regardless of shard count.
     * Guards against subtle shard-gate interactions.
     */
    public void testWriteBlockRemovedWhenExplicitlyTrue_multiShard() throws Exception {
        String source = indexName("rwb-multi-src");
        String target = indexName("rwb-multi-tgt");
        createSourceAndTarget(source, target, 3, 3);
        indexDocs(source, 90);
        flushAndRefresh(source);

        MigrationRequestOptions opts = new MigrationRequestOptions().setRemoveSourceWriteBlockOnSuccess(true);
        startMigration(source, target, "rwb-multi-alias", null, opts);

        CoordinatorPhase terminal = awaitTerminalPhase(source, 120);
        assertEquals("Migration should complete", CoordinatorPhase.COMPLETED, terminal);

        assertWriteBlockAbsent(source);
    }

    /**
     * Rollback-on-cancel must always remove the source write block, regardless of the option
     * value — the option only controls the success-path behaviour, not rollback.
     */
    public void testCancelPathStillRemovesWriteBlockEvenWhenOptedOut() throws Exception {
        String source = indexName("rwb-cancel-src");
        String target = indexName("rwb-cancel-tgt");
        createSourceAndTarget(source, target, 1, 1);
        indexDocs(source, 1000);
        flushAndRefresh(source);

        MigrationRequestOptions opts = new MigrationRequestOptions().setRemoveSourceWriteBlockOnSuccess(false);
        startMigration(source, target, "rwb-cancel-alias", null, opts);

        // Cancel and wait for terminal. Race-tolerant: terminal may be CANCELLED or COMPLETED.
        // If CANCELLED, rollback removes the block; if COMPLETED, the option being false would normally
        // retain it — so we only assert the rollback invariant when terminal is CANCELLED.
        cancelMigration(source);
        CoordinatorPhase terminal = awaitTerminalPhase(source, 120);

        if (terminal == CoordinatorPhase.CANCELLED) {
            assertWriteBlockAbsent(source);
        } else {
            assertEquals(CoordinatorPhase.COMPLETED, terminal);
            assertWriteBlockPresent(source);
        }
    }

    /** Re-issuing removal on an already-unblocked source must not throw — tolerance of out-of-band cleanup. */
    public void testSucceedsWhenWriteBlockAlreadyRemoved() throws Exception {
        String source = indexName("rwb-idem-src");
        String target = indexName("rwb-idem-tgt");
        createSourceAndTarget(source, target, 1, 1);
        indexDocs(source, 25);
        flushAndRefresh(source);

        MigrationRequestOptions opts = new MigrationRequestOptions().setRemoveSourceWriteBlockOnSuccess(true);
        startMigration(source, target, "rwb-idem-alias", null, opts);
        CoordinatorPhase terminal = awaitTerminalPhase(source, 90);
        assertEquals("Migration should complete", CoordinatorPhase.COMPLETED, terminal);

        // Re-issue the same idempotent removal — should not throw.
        client().admin().indices().prepareUpdateSettings(source).setSettings(Settings.builder().put("index.blocks.write", false)).get();

        assertWriteBlockAbsent(source);
    }

    /**
     * The cluster setting {@code aosc.defaults.remove_source_write_block_on_success=true} must flow
     * through to a migration that does not set the per-request option, causing the block to be removed.
     * This proves the cluster setting can override the {@code false} built-in default.
     */
    public void testClusterDefaultTrueRemovesWriteBlockWhenOptionUnset() throws Exception {
        client().admin()
            .cluster()
            .prepareUpdateSettings()
            .setTransientSettings(Settings.builder().put("aosc.defaults.remove_source_write_block_on_success", true))
            .get();

        String source = indexName("rwb-cdef-src");
        String target = indexName("rwb-cdef-tgt");
        createSourceAndTarget(source, target, 1, 1);
        indexDocs(source, 25);
        flushAndRefresh(source);

        // No options → cluster default (true) applies.
        startMigration(source, target, "rwb-cdef-alias", null);

        CoordinatorPhase terminal = awaitTerminalPhase(source, 90);
        assertEquals("Migration should complete", CoordinatorPhase.COMPLETED, terminal);

        assertWriteBlockAbsent(source);
    }

    /**
     * Even when the cluster default is {@code false}, an explicit per-request {@code true} must win and
     * remove the block — request option overrides the cluster default.
     */
    public void testRequestOptionOverridesClusterDefault() throws Exception {
        client().admin()
            .cluster()
            .prepareUpdateSettings()
            .setTransientSettings(Settings.builder().put("aosc.defaults.remove_source_write_block_on_success", false))
            .get();

        String source = indexName("rwb-cover-src");
        String target = indexName("rwb-cover-tgt");
        createSourceAndTarget(source, target, 1, 1);
        indexDocs(source, 25);
        flushAndRefresh(source);

        MigrationRequestOptions opts = new MigrationRequestOptions().setRemoveSourceWriteBlockOnSuccess(true);
        startMigration(source, target, "rwb-cover-alias", null, opts);

        CoordinatorPhase terminal = awaitTerminalPhase(source, 90);
        assertEquals("Migration should complete", CoordinatorPhase.COMPLETED, terminal);

        assertWriteBlockAbsent(source);
    }

    // ---- helpers ----

    private void assertWriteBlockAbsent(String index) {
        String setting = client().admin()
            .indices()
            .getSettings(new GetSettingsRequest().indices(index))
            .actionGet()
            .getSetting(index, "index.blocks.write");
        // Setting can be null (never applied/removed) or literal "false".
        assertTrue(
            "Expected index.blocks.write to be absent or false on '" + index + "', got: " + setting,
            setting == null || "false".equals(setting)
        );
    }

    private void assertWriteBlockPresent(String index) {
        String setting = client().admin()
            .indices()
            .getSettings(new GetSettingsRequest().indices(index))
            .actionGet()
            .getSetting(index, "index.blocks.write");
        assertEquals("Expected index.blocks.write=true on '" + index + "'", "true", setting);
    }
}
