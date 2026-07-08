/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */
package com.atlassian.opensearch.aosc;

import com.atlassian.opensearch.aosc.action.list.ListMigrationsAction;
import com.atlassian.opensearch.aosc.action.list.ListMigrationsBody;
import com.atlassian.opensearch.aosc.action.list.ListMigrationsRequest;
import com.atlassian.opensearch.aosc.action.list.ListMigrationsResponse;
import com.atlassian.opensearch.aosc.model.MigrationSummary;
import com.atlassian.opensearch.aosc.model.phase.CoordinatorPhase;

import org.opensearch.test.OpenSearchIntegTestCase.ClusterScope;
import org.opensearch.test.OpenSearchIntegTestCase.Scope;

import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Integration tests for the {@code _list} endpoint via {@link ListMigrationsAction}.
 */
@ClusterScope(scope = Scope.TEST, numDataNodes = 2, numClientNodes = 0)
public class ListMigrationsIT extends AoscIntegTestBase {

    public void testListReturnsEmptyWhenNoMigrations() {
        ListMigrationsResponse response = listMigrations(List.of(), 50);
        assertEquals(0, response.body().migrations().size());
    }

    public void testListShowsActiveMigration() throws Exception {
        String source = indexName("list-src");
        String target = indexName("list-tgt");
        createSourceAndTarget(source, target, 1, 1);
        indexDocs(source, 50);
        startMigration(source, target, "list-alias", null);
        assertMigrationCompleted(source, 90);

        // Tier-1 write is async — wait for persistFinalState to land.
        assertBusy(() -> {
            ListMigrationsResponse response = listMigrations(List.of(), 50);
            assertTrue("Should have at least 1 migration", response.body().migrations().size() >= 1);

            MigrationSummary found = findBySource(response, source);
            assertNotNull("Migration for " + source + " should be in list", found);
            assertEquals(source, found.sourceIndex());
            assertEquals(target, found.targetIndex());
            assertEquals(CoordinatorPhase.COMPLETED, found.phase());
            assertTrue("startTimeMillis should be positive", found.startTimeMillis() > 0);
            assertTrue("lastUpdatedMillis should be positive", found.lastUpdatedMillis() > 0);
        }, 30, TimeUnit.SECONDS);
    }

    public void testListShowsMultipleMigrations() throws Exception {
        String source1 = indexName("multi1-src");
        String target1 = indexName("multi1-tgt");
        String source2 = indexName("multi2-src");
        String target2 = indexName("multi2-tgt");

        createSourceAndTarget(source1, target1, 1, 1);
        createSourceAndTarget(source2, target2, 1, 1);
        indexDocs(source1, 20);
        indexDocs(source2, 20);

        startMigration(source1, target1, "alias1", null);
        startMigration(source2, target2, "alias2", null);
        assertMigrationCompleted(source1, 90);
        assertMigrationCompleted(source2, 90);

        ListMigrationsResponse response = listMigrations(List.of(), 50);
        assertTrue("Should have at least 2 migrations", response.body().migrations().size() >= 2);

        assertNotNull(findBySource(response, source1));
        assertNotNull(findBySource(response, source2));
    }

    public void testListFilterByPhase() throws Exception {
        String source = indexName("filter-src");
        String target = indexName("filter-tgt");
        createSourceAndTarget(source, target, 1, 1);
        indexDocs(source, 20);
        startMigration(source, target, "filter-alias", null);
        CoordinatorPhase terminalPhase = awaitTerminalPhase(source, 90);
        assertTrue("Should be terminal", terminalPhase.isTerminal());

        // Tier-1 write (persistFinalState) is async — wait for it to land before querying.
        assertBusy(() -> {
            // Filter by the actual terminal phase — should find it.
            ListMigrationsResponse matchingFilter = listMigrations(List.of(terminalPhase), 50);
            assertNotNull("Expected " + source + " in " + terminalPhase + " filter", findBySource(matchingFilter, source));

            // Filter by ACTIVE — should NOT find it (it's terminal).
            ListMigrationsResponse activeOnly = listMigrations(List.of(CoordinatorPhase.ACTIVE), 50);
            assertNull("Terminal migration should not appear in ACTIVE filter", findBySource(activeOnly, source));
        }, 30, TimeUnit.SECONDS);
    }

    public void testListSizeParam() throws Exception {
        String source1 = indexName("size1-src");
        String target1 = indexName("size1-tgt");
        String source2 = indexName("size2-src");
        String target2 = indexName("size2-tgt");

        createSourceAndTarget(source1, target1, 1, 1);
        createSourceAndTarget(source2, target2, 1, 1);
        indexDocs(source1, 10);
        indexDocs(source2, 10);

        startMigration(source1, target1, "sz1", null);
        assertMigrationCompleted(source1, 90);
        startMigration(source2, target2, "sz2", null);
        assertMigrationCompleted(source2, 90);

        // Size=1 should return only 1 (most recent)
        ListMigrationsResponse limited = listMigrations(List.of(), 1);
        assertEquals(1, limited.body().migrations().size());
    }

    public void testListResponseIncludesLastUpdatedMillis() throws Exception {
        String source = indexName("upd-src");
        String target = indexName("upd-tgt");
        createSourceAndTarget(source, target, 1, 1);
        indexDocs(source, 10);
        startMigration(source, target, "upd-alias", null);
        assertMigrationCompleted(source, 90);

        ListMigrationsResponse response = listMigrations(List.of(), 50);
        MigrationSummary doc = findBySource(response, source);
        assertNotNull(doc);
        assertTrue("lastUpdatedMillis >= startTimeMillis", doc.lastUpdatedMillis() >= doc.startTimeMillis());
    }

    public void testListResponseIncludesAlias() throws Exception {
        String source = indexName("alias-src");
        String target = indexName("alias-tgt");
        createSourceAndTarget(source, target, 1, 1);
        indexDocs(source, 10);
        startMigration(source, target, "my-alias", null);
        assertMigrationCompleted(source, 90);

        ListMigrationsResponse response = listMigrations(List.of(), 50);
        MigrationSummary doc = findBySource(response, source);
        assertNotNull(doc);
        assertEquals("my-alias", doc.alias());
    }

    // ---- Helpers ----

    private ListMigrationsResponse listMigrations(List<CoordinatorPhase> statusFilter, int size) {
        return client().execute(ListMigrationsAction.INSTANCE, new ListMigrationsRequest(new ListMigrationsBody(statusFilter, size)))
            .actionGet();
    }

    private MigrationSummary findBySource(ListMigrationsResponse response, String sourceIndex) {
        return response.body().migrations().stream().filter(m -> sourceIndex.equals(m.sourceIndex())).findFirst().orElse(null);
    }
}
