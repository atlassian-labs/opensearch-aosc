/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */
package com.atlassian.opensearch.aosc;

import com.atlassian.opensearch.aosc.model.MigrationDocument;
import com.atlassian.opensearch.aosc.model.MigrationRequestOptions;
import com.atlassian.opensearch.aosc.model.phase.CoordinatorPhase;

import org.opensearch.action.get.GetRequest;
import org.opensearch.action.get.GetResponse;
import org.opensearch.common.xcontent.json.JsonXContent;
import org.opensearch.core.xcontent.DeprecationHandler;
import org.opensearch.core.xcontent.NamedXContentRegistry;
import org.opensearch.core.xcontent.XContentParser;
import org.opensearch.test.OpenSearchIntegTestCase.ClusterScope;
import org.opensearch.test.OpenSearchIntegTestCase.Scope;

import java.util.concurrent.TimeUnit;

/**
 * Integration tests for the cutover validation and metadata recording.
 * Tests the CutoverService's doc count validation, tolerance handling,
 * and metadata persistence in the migration document.
 *
 * Uses Scope.TEST for a fresh cluster per test to avoid interference.
 */
@ClusterScope(scope = Scope.TEST, numDataNodes = 1, numClientNodes = 0)
public class CutoverValidationIT extends AoscIntegTestBase {

    /**
     * After a successful migration, the migration document in .aosc-migrations
     * should contain cutover_context with accurate doc counts and timing.
     */
    public void testCutoverContextPersistedOnCompletion() throws Exception {
        String source = indexName("ctx-src");
        String target = indexName("ctx-tgt");
        createSourceAndTarget(source, target, 1, 1);
        int numDocs = 50;
        indexDocs(source, numDocs);
        flushAndRefresh(source);

        String migrationId = startMigration(source, target, "ctx-alias", null);

        CoordinatorPhase terminal = awaitTerminalPhase(source, 90);
        assertEquals("Migration should complete", CoordinatorPhase.COMPLETED, terminal);

        // The cutover context write and cluster state update are async —
        // wait for the document to be updated with cutover context
        assertBusy(() -> {
            MigrationDocument doc = readMigrationDocument(migrationId);
            assertNotNull("Migration document should exist", doc);
            assertNotNull("Cutover context should be persisted", doc.cutoverContext());
        }, 10, TimeUnit.SECONDS);

        // Read again for detailed assertions
        MigrationDocument migDoc = readMigrationDocument(migrationId);
        assertTrue("Doc count validation should have passed", migDoc.cutoverContext().docCountValidationPassed());
        assertTrue("Alias swap should have succeeded", migDoc.cutoverContext().aliasSwapSucceeded());

        // Doc counts should reflect the actual documents
        assertEquals("Source doc count should match", numDocs, migDoc.cutoverContext().sourceDocCount());
        assertEquals("Target doc count should match", numDocs, migDoc.cutoverContext().targetDocCount());

        // Tolerance should be 0 (default)
        assertEquals("Doc count tolerance should be 0", 0, migDoc.cutoverContext().docCountTolerance());

        // Timing should be positive
        assertTrue("Cutover start should be > 0", migDoc.cutoverContext().cutoverStartMillis() > 0);
        assertTrue("Cutover end should be > 0", migDoc.cutoverContext().cutoverEndMillis() > 0);
        assertTrue("Duration should be >= 0", migDoc.cutoverContext().durationMillis() >= 0);

        // Error should be null on success
        assertNull("Error message should be null on success", migDoc.cutoverContext().errorMessage());
    }

    /**
     * Migration with docCountTolerance set should complete even if doc counts
     * differ within the tolerance window. This scenario uses a small tolerance
     * to verify the option is wired through correctly.
     */
    public void testMigrationSucceedsWithDocCountTolerance() throws Exception {
        String source = indexName("tol-src");
        String target = indexName("tol-tgt");
        createSourceAndTarget(source, target, 1, 1);
        indexDocs(source, 100);
        flushAndRefresh(source);

        // Set tolerance to 5 — should still complete for an exact-match scenario
        MigrationRequestOptions options = new MigrationRequestOptions().setDocCountTolerance(5);
        String migrationId = startMigration(source, target, "tol-alias", null, options);

        CoordinatorPhase terminal = awaitTerminalPhase(source, 90);
        assertEquals("Migration should complete", CoordinatorPhase.COMPLETED, terminal);

        assertBusy(() -> {
            MigrationDocument doc = readMigrationDocument(migrationId);
            assertNotNull("Cutover context should exist", doc.cutoverContext());
        }, 10, TimeUnit.SECONDS);
        MigrationDocument migDoc = readMigrationDocument(migrationId);
        assertEquals("Tolerance should be 5", 5, migDoc.cutoverContext().docCountTolerance());
        assertTrue("Doc count validation should pass", migDoc.cutoverContext().docCountValidationPassed());
        assertTrue("Alias swap should succeed", migDoc.cutoverContext().aliasSwapSucceeded());
    }

    /**
     * Migration with docCountTolerance=0 should still succeed when source
     * and target have exactly matching doc counts (the normal case).
     */
    public void testMigrationSucceedsWithZeroTolerance() throws Exception {
        String source = indexName("zt-src");
        String target = indexName("zt-tgt");
        createSourceAndTarget(source, target, 1, 1);
        indexDocs(source, 25);
        flushAndRefresh(source);

        MigrationRequestOptions options = new MigrationRequestOptions().setDocCountTolerance(0);
        String migrationId = startMigration(source, target, "zt-alias", null, options);

        CoordinatorPhase terminal = awaitTerminalPhase(source, 90);
        assertEquals("Migration should complete with zero tolerance", CoordinatorPhase.COMPLETED, terminal);

        assertBusy(() -> {
            MigrationDocument doc = readMigrationDocument(migrationId);
            assertNotNull("Cutover context should exist", doc.cutoverContext());
        }, 10, TimeUnit.SECONDS);
        MigrationDocument migDoc = readMigrationDocument(migrationId);
        assertEquals(
            "Source and target doc counts should match",
            migDoc.cutoverContext().sourceDocCount(),
            migDoc.cutoverContext().targetDocCount()
        );
    }

    // ---- Helpers ----

    /**
     * Read a MigrationDocument from the .aosc-migrations system index by ID.
     * Refreshes the index first to ensure we read the latest version.
     */
    private MigrationDocument readMigrationDocument(String migrationId) throws Exception {
        // Refresh to ensure the doc is searchable
        try {
            client().admin().indices().prepareRefresh(".aosc-migrations").get();
        } catch (Exception e) {
            logger.warn("Failed to refresh .aosc-migrations", e);
        }

        GetRequest getRequest = new GetRequest(".aosc-migrations", migrationId);
        GetResponse response = client().get(getRequest).actionGet();
        if (!response.isExists()) {
            logger.warn("Migration document {} not found in .aosc-migrations", migrationId);
            return null;
        }
        String source = response.getSourceAsString();
        logger.info("Migration document {}: {}", migrationId, source);
        try (
            XContentParser parser = JsonXContent.jsonXContent.createParser(
                NamedXContentRegistry.EMPTY,
                DeprecationHandler.THROW_UNSUPPORTED_OPERATION,
                source
            )
        ) {
            return MigrationDocument.fromXContent(parser);
        }
    }
}
