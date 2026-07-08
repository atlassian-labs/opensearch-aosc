/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */
package com.atlassian.opensearch.aosc;

import com.atlassian.opensearch.aosc.action.clear.ClearClusterStateAction;
import com.atlassian.opensearch.aosc.action.clear.ClearClusterStateBody;
import com.atlassian.opensearch.aosc.action.clear.ClearClusterStateRequest;
import com.atlassian.opensearch.aosc.action.clear.ClearClusterStateResponse;
import com.atlassian.opensearch.aosc.action.clear.ClearClusterStateResult;
import com.atlassian.opensearch.aosc.action.list.ListMigrationsAction;
import com.atlassian.opensearch.aosc.action.list.ListMigrationsBody;
import com.atlassian.opensearch.aosc.action.list.ListMigrationsRequest;
import com.atlassian.opensearch.aosc.action.list.ListMigrationsResponse;
import com.atlassian.opensearch.aosc.action.status.GetMigrationStatusResponse;
import com.atlassian.opensearch.aosc.model.MigrationSummary;
import com.atlassian.opensearch.aosc.service.coordinator.AoscCoordinatorService;

import org.opensearch.common.unit.TimeValue;
import org.opensearch.test.OpenSearchIntegTestCase.ClusterScope;
import org.opensearch.test.OpenSearchIntegTestCase.Scope;

import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasKey;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;

/**
 * Integration tests for the {@code _clear_state} API (B045).
 */
@ClusterScope(scope = Scope.TEST, numDataNodes = 1, numClientNodes = 0)
public class ClearStateIT extends AoscIntegTestBase {

    // ---- Helpers ----

    private ClearClusterStateResponse clearState(boolean dryRun, boolean tryClose, String migrationId, boolean detailed) {
        ClearClusterStateBody body = new ClearClusterStateBody(dryRun, tryClose, migrationId, detailed);
        return client().execute(ClearClusterStateAction.INSTANCE, new ClearClusterStateRequest(body)).actionGet();
    }

    private ClearClusterStateResponse clearAll() {
        return clearState(false, true, null, false);
    }

    private List<MigrationSummary> listActiveMigrations() {
        ListMigrationsResponse resp = client().execute(
            ListMigrationsAction.INSTANCE,
            new ListMigrationsRequest(new ListMigrationsBody(Collections.emptyList(), 100))
        ).actionGet();
        return resp.body().migrations().stream().filter(m -> !m.phase().isTerminal()).collect(Collectors.toList());
    }

    private int activeCoordinatorCount() {
        int count = 0;
        for (AoscCoordinatorService svc : internalCluster().getInstances(AoscCoordinatorService.class)) {
            count += svc.activeCoordinatorCount();
        }
        return count;
    }

    // ---- Tests ----

    public void testClearOnEmptyClusterReturnsEmptyMigrations() {
        ClearClusterStateResponse resp = clearAll();
        ClearClusterStateResult result = resp.body();
        assertFalse("clearAll() passes dryRun=false explicitly", result.dryRun());
        assertTrue(result.tryClose());
        assertThat(result.migrations().entrySet(), is(empty()));
    }

    public void testClearIsIdempotent() {
        ClearClusterStateResponse first = clearAll();
        ClearClusterStateResponse second = clearAll();
        assertThat(first.body().migrations().entrySet(), is(empty()));
        assertThat(second.body().migrations().entrySet(), is(empty()));
    }

    public void testDryRunDoesNotModifyState() throws Exception {
        String source = indexName("cs-dry-src");
        String target = indexName("cs-dry-tgt");
        createSourceAndTarget(source, target, 1, 1);
        indexDocs(source, 5);

        String migrationId = startMigration(source, target, source + "-alias", null);

        // Dry run
        ClearClusterStateResponse resp = clearState(true, true, null, false);
        ClearClusterStateResult result = resp.body();
        assertTrue(result.dryRun());
        assertThat(result.migrations(), hasKey(migrationId));
        ClearClusterStateResult.MigrationAction action = result.migrations().get(migrationId);
        assertThat(action.clusterState(), equalTo("would_clear"));
        assertThat(action.activeMigrations(), equalTo("would_clear"));
        assertFalse(action.closed());

        // Migration should still be listed
        assertThat(listActiveMigrations(), is(notNullValue()));
        assertFalse("Migration should still exist after dry run", listActiveMigrations().isEmpty());
    }

    public void testClearRemovesMigrationFromClusterState() throws Exception {
        String source = indexName("cs-clr-src");
        String target = indexName("cs-clr-tgt");
        createSourceAndTarget(source, target, 1, 1);
        indexDocs(source, 5);

        String migrationId = startMigration(source, target, source + "-alias", null);

        // Clear state
        ClearClusterStateResponse resp = clearAll();
        ClearClusterStateResult result = resp.body();
        assertThat(result.migrations(), hasKey(migrationId));
        ClearClusterStateResult.MigrationAction action = result.migrations().get(migrationId);
        assertThat(action.sourceIndex(), equalTo(source));
        assertThat(action.clusterState(), equalTo("cleared"));
        assertTrue(action.closed());

        // Second clear should return empty — nothing left in cluster state
        ClearClusterStateResponse resp2 = clearAll();
        assertThat(resp2.body().migrations().entrySet(), is(empty()));

        // Coordinator count should be zero
        assertBusyWithFixedSleepTime(
            () -> assertThat(activeCoordinatorCount(), is(0)),
            TimeValue.timeValueSeconds(5),
            TimeValue.timeValueMillis(200)
        );
    }

    public void testClearWithMigrationIdTargetsSingleMigration() throws Exception {
        String source1 = indexName("cs-id1-src");
        String target1 = indexName("cs-id1-tgt");
        String source2 = indexName("cs-id2-src");
        String target2 = indexName("cs-id2-tgt");
        createSourceAndTarget(source1, target1, 1, 1);
        createSourceAndTarget(source2, target2, 1, 1);
        indexDocs(source1, 3);
        indexDocs(source2, 3);

        String migrationId1 = startMigration(source1, target1, source1 + "-alias", null);
        String migrationId2 = startMigration(source2, target2, source2 + "-alias", null);

        // Clear only migration 1
        ClearClusterStateResponse resp = clearState(false, true, migrationId1, false);
        assertThat(resp.body().migrations(), hasKey(migrationId1));
        assertThat(resp.body().migrations().size(), equalTo(1));
        assertThat(resp.body().migrations().get(migrationId1).clusterState(), equalTo("cleared"));

        // Migration 2 should still be in cluster state (status API uses cluster state)
        GetMigrationStatusResponse status2 = getStatus(source2);
        assertNotNull("Migration 2 should still have status", status2);
    }

    public void testClearWithUnknownMigrationIdReturnsEmpty() {
        ClearClusterStateResponse resp = clearState(false, true, "nonexistent-id", false);
        assertThat(resp.body().migrations().entrySet(), is(empty()));
    }

    public void testTryCloseFalseDoesNotCloseCoordinators() throws Exception {
        String source = indexName("cs-noclose-src");
        String target = indexName("cs-noclose-tgt");
        createSourceAndTarget(source, target, 1, 1);
        indexDocs(source, 5);

        String migrationId = startMigration(source, target, source + "-alias", null);

        // Clear with try_close=false
        ClearClusterStateResponse resp = clearState(false, false, null, false);
        ClearClusterStateResult.MigrationAction action = resp.body().migrations().get(migrationId);
        assertThat(action.clusterState(), equalTo("cleared"));
        assertThat(action.activeMigrations(), equalTo("not_found"));
        assertFalse(action.closed());
    }

    public void testDetailedIncludesEntry() throws Exception {
        String source = indexName("cs-det-src");
        String target = indexName("cs-det-tgt");
        createSourceAndTarget(source, target, 1, 1);
        indexDocs(source, 5);

        startMigration(source, target, source + "-alias", null);

        // Dry run with detailed to inspect the entry
        ClearClusterStateResponse resp = clearState(true, true, null, true);
        ClearClusterStateResult.MigrationAction action = resp.body().migrations().values().iterator().next();
        assertNotNull("Entry should be present when detailed=true", action.entry());
        assertThat(action.entry().sourceIndex(), equalTo(source));
        assertThat(action.entry().targetIndex(), equalTo(target));
    }
}
