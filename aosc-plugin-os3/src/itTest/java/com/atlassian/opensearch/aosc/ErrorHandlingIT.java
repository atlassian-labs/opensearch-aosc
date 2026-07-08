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

import org.opensearch.action.admin.indices.alias.get.GetAliasesRequest;
import org.opensearch.action.admin.indices.alias.get.GetAliasesResponse;
import org.opensearch.common.settings.Settings;
import org.opensearch.test.OpenSearchIntegTestCase.ClusterScope;
import org.opensearch.test.OpenSearchIntegTestCase.Scope;

/**
 * Integration tests for error handling during migration.
 * Uses Scope.TEST to get a fresh cluster for each test since these tests
 * modify cluster state destructively (deleting indices, setting blocks).
 */
@ClusterScope(scope = Scope.TEST, numDataNodes = 1, numClientNodes = 0)
public class ErrorHandlingIT extends AoscIntegTestBase {

    public void testMigrationFailsIfTargetSetReadOnly() throws Exception {
        String source = indexName("ro-src");
        String target = indexName("ro-tgt");
        createSourceAndTarget(source, target, 1, 1);
        indexDocs(source, 100);

        // Set target to read-only BEFORE starting migration
        client().admin().indices().prepareUpdateSettings(target).setSettings(Settings.builder().put("index.blocks.write", true)).get();

        startMigration(source, target, "ro-alias", null);

        // Migration should fail since target is read-only
        CoordinatorPhase terminal = awaitTerminalPhase(source, 90);
        assertTrue(
            "Migration should fail, got: " + terminal,
            terminal == CoordinatorPhase.FAILED || terminal == CoordinatorPhase.CANCELLED
        );
    }

    /**
     * When extra documents are pre-indexed into the target before migration,
     * the cutover doc count validation should detect the mismatch and fail
     * the migration (when tolerance is 0).
     *
     * The target has extra docs that weren't in the source, so after backfill
     * completes, target count &gt; source count, exceeding tolerance=0.
     *
     * After failure, the alias should still point to the source index (rollback in onEnterFailing).
     */
    public void testMigrationFailsOnDocCountMismatch() throws Exception {
        String source = indexName("dcm-src");
        String target = indexName("dcm-tgt");
        String alias = "dcm-alias";
        createSourceAndTarget(source, target, 1, 1);

        // Set up the alias on the source index before migration
        client().admin().indices().prepareAliases().addAlias(source, alias).get();

        // Index docs into source
        indexDocs(source, 50);
        flushAndRefresh(source);

        // Start migration with tolerance=0 — the surplus will cause failure
        MigrationRequestOptions options = new MigrationRequestOptions().setDocCountTolerance(0);
        startMigration(source, target, alias, null, options);

        // Inject orphan docs into target after migration starts (pre-start validation
        // rejects non-empty targets, so we inject after the start call).
        // These "orphan" docs in target will cause target count > source count.
        for (int i = 0; i < 100; i++) {
            client().prepareIndex(target).setId("orphan-" + i).setSource("field", "orphan-value-" + i).get();
        }
        flushAndRefresh(target);

        CoordinatorPhase terminal = awaitTerminalPhase(source, 90);
        assertEquals("Migration should fail due to doc count mismatch", CoordinatorPhase.FAILED, terminal);

        // Verify alias was rolled back to the source index (not left on target)
        GetAliasesResponse aliasResponse = client().admin().indices().getAliases(new GetAliasesRequest(alias)).actionGet();
        assertTrue("After failure, alias should point to source index", aliasResponse.getAliases().containsKey(source));
        assertFalse("After failure, alias should NOT point to target index", aliasResponse.getAliases().containsKey(target));
    }
}
