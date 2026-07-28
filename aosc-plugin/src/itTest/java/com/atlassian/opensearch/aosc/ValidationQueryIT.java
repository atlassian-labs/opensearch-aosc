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

import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Integration tests for validation_query support in the cutover phase.
 * Verifies that when a validation_query is provided, doc counts at cutover
 * are scoped to only matching documents — enabling partial-index migrations.
 *
 * Uses Scope.TEST for a fresh cluster per test to avoid interference.
 */
@ClusterScope(scope = Scope.TEST, numDataNodes = 1, numClientNodes = 0)
public class ValidationQueryIT extends AoscIntegTestBase {

    public void testMigrationWithMatchAllValidationQuery() throws Exception {
        String source = indexName("vq-all-src");
        String target = indexName("vq-all-tgt");
        createSourceAndTarget(source, target, 1, 1);
        int numDocs = 30;
        indexDocs(source, numDocs);
        flushAndRefresh(source);

        MigrationRequestOptions options = new MigrationRequestOptions();
        options.setValidationQuery(Map.of("match_all", Map.of()));
        String migrationId = startMigration(source, target, "vq-all-alias", null, options);

        CoordinatorPhase terminal = awaitTerminalPhase(source, 90);
        assertEquals("Migration should complete", CoordinatorPhase.COMPLETED, terminal);

        assertBusy(() -> {
            MigrationDocument doc = readMigrationDocument(migrationId);
            assertNotNull("Cutover context should exist", doc.cutoverContext());
        }, 10, TimeUnit.SECONDS);

        MigrationDocument migDoc = readMigrationDocument(migrationId);
        assertTrue("Doc count validation should pass", migDoc.cutoverContext().docCountValidationPassed());
        assertTrue("Alias swap should succeed", migDoc.cutoverContext().aliasSwapSucceeded());
        assertEquals("Source doc count should match", numDocs, migDoc.cutoverContext().sourceDocCount());
        assertEquals("Target doc count should match", numDocs, migDoc.cutoverContext().targetDocCount());
    }

    public void testMigrationWithTermValidationQuery() throws Exception {
        String source = indexName("vq-term-src");
        String target = indexName("vq-term-tgt");
        createSourceAndTarget(source, target, 1, 1);

        int numDocs = 25;
        for (int i = 0; i < numDocs; i++) {
            indexDoc(source, String.valueOf(i), Map.of("value", i, "status", "active"));
        }
        flushAndRefresh(source);

        MigrationRequestOptions options = new MigrationRequestOptions();
        options.setValidationQuery(Map.of("term", Map.of("status", "active")));
        String migrationId = startMigration(source, target, "vq-term-alias", null, options);

        CoordinatorPhase terminal = awaitTerminalPhase(source, 90);
        assertEquals("Migration should complete", CoordinatorPhase.COMPLETED, terminal);

        assertBusy(() -> {
            MigrationDocument doc = readMigrationDocument(migrationId);
            assertNotNull("Cutover context should exist", doc.cutoverContext());
        }, 10, TimeUnit.SECONDS);

        MigrationDocument migDoc = readMigrationDocument(migrationId);
        assertTrue("Doc count validation should pass", migDoc.cutoverContext().docCountValidationPassed());
        assertTrue("Alias swap should succeed", migDoc.cutoverContext().aliasSwapSucceeded());
        assertEquals(
            "Filtered doc counts should match",
            migDoc.cutoverContext().sourceDocCount(),
            migDoc.cutoverContext().targetDocCount()
        );
    }

    public void testMigrationWithValidationQueryAndTolerance() throws Exception {
        String source = indexName("vq-tol-src");
        String target = indexName("vq-tol-tgt");
        createSourceAndTarget(source, target, 1, 1);
        int numDocs = 40;
        indexDocs(source, numDocs);
        flushAndRefresh(source);

        MigrationRequestOptions options = new MigrationRequestOptions();
        options.setValidationQuery(Map.of("match_all", Map.of()));
        options.setDocCountTolerance(5);
        String migrationId = startMigration(source, target, "vq-tol-alias", null, options);

        CoordinatorPhase terminal = awaitTerminalPhase(source, 90);
        assertEquals("Migration should complete", CoordinatorPhase.COMPLETED, terminal);

        assertBusy(() -> {
            MigrationDocument doc = readMigrationDocument(migrationId);
            assertNotNull("Cutover context should exist", doc.cutoverContext());
        }, 10, TimeUnit.SECONDS);

        MigrationDocument migDoc = readMigrationDocument(migrationId);
        assertTrue("Doc count validation should pass", migDoc.cutoverContext().docCountValidationPassed());
        assertTrue("Alias swap should succeed", migDoc.cutoverContext().aliasSwapSucceeded());
        assertEquals("Tolerance should be 5", 5, migDoc.cutoverContext().docCountTolerance());
    }

    private MigrationDocument readMigrationDocument(String migrationId) throws Exception {
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
