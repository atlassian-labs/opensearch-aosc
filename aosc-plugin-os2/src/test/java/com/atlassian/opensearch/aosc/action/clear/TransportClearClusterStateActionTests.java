/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */
package com.atlassian.opensearch.aosc.action.clear;

import com.atlassian.opensearch.aosc.model.AoscMigrationsClusterState;
import com.atlassian.opensearch.aosc.model.MigrationMetadata;
import com.atlassian.opensearch.aosc.model.MigrationRequestOptions;
import com.atlassian.opensearch.aosc.model.ShardRoutingMode;
import com.atlassian.opensearch.aosc.model.phase.CoordinatorPhase;
import com.atlassian.opensearch.aosc.model.phase.ShardPhase;
import com.atlassian.opensearch.aosc.service.coordinator.AoscCoordinatorService;

import org.opensearch.action.support.ActionFilters;
import org.opensearch.cluster.ClusterName;
import org.opensearch.cluster.ClusterState;
import org.opensearch.cluster.ClusterStateUpdateTask;
import org.opensearch.cluster.service.ClusterService;
import org.opensearch.core.action.ActionListener;
import org.opensearch.tasks.TaskManager;
import org.opensearch.test.OpenSearchTestCase;
import org.opensearch.transport.TransportService;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import org.mockito.ArgumentCaptor;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class TransportClearClusterStateActionTests extends OpenSearchTestCase {

    private ClusterService clusterService;
    private AoscCoordinatorService coordinatorService;

    @Override
    public void setUp() throws Exception {
        super.setUp();
        clusterService = mock(ClusterService.class);
        coordinatorService = mock(AoscCoordinatorService.class);
        when(coordinatorService.getActiveMigrationIds()).thenReturn(Set.of());
        when(coordinatorService.removeAndClose(any())).thenReturn(Set.of());
    }

    private TransportClearClusterStateAction createAction() {
        TransportService transportService = mock(TransportService.class);
        when(transportService.getTaskManager()).thenReturn(mock(TaskManager.class));
        return new TransportClearClusterStateAction(
            transportService,
            clusterService,
            null,
            new ActionFilters(Collections.emptySet()),
            null,
            coordinatorService
        );
    }

    private static ClusterState emptyState() {
        return ClusterState.builder(new ClusterName("test")).build();
    }

    private static AoscMigrationsClusterState.Entry buildEntry(String migrationId, String sourceIndex, CoordinatorPhase phase) {
        Map<Integer, AoscMigrationsClusterState.ShardMigrationClusterState> shards = new HashMap<>();
        shards.put(
            0,
            AoscMigrationsClusterState.ShardMigrationClusterState.builder()
                .phase(ShardPhase.PENDING)
                .lastReplayedSeqNo(-1)
                .backfillCutoffSeqNo(-1)
                .failure(null)
                .meta(MigrationMetadata.EMPTY)
                .build()
        );

        return AoscMigrationsClusterState.Entry.builder()
            .migrationId(migrationId)
            .sourceIndex(sourceIndex)
            .targetIndex(sourceIndex + "-target")
            .alias(sourceIndex + "-alias")
            .transformScript(null)
            .options(new MigrationRequestOptions())
            .phase(phase)
            .routingMode(ShardRoutingMode.SAME_SHARD)
            .startTimeMillis(System.currentTimeMillis())
            .shards(shards)
            .failure(phase == CoordinatorPhase.FAILED ? "some failure" : null)
            .meta(MigrationMetadata.EMPTY)
            .build();
    }

    private static ClusterState stateWith(AoscMigrationsClusterState.Entry... entries) {
        Map<String, AoscMigrationsClusterState.Entry> map = new HashMap<>();
        for (AoscMigrationsClusterState.Entry e : entries) {
            map.put(e.migrationId(), e);
        }
        return ClusterState.builder(new ClusterName("test"))
            .putCustom(AoscMigrationsClusterState.TYPE, new AoscMigrationsClusterState(map))
            .build();
    }

    // ---- Empty cluster ----

    public void testClearWithNoEntriesReturnsEmptyMigrations() {
        ActionListener<ClearClusterStateResponse> listener = mockListener();
        createAction().clusterManagerOperation(
            new ClearClusterStateRequest(ClearClusterStateBody.builder().dryRun(false).build()),
            emptyState(),
            listener
        );

        ArgumentCaptor<ClearClusterStateResponse> captor = ArgumentCaptor.forClass(ClearClusterStateResponse.class);
        verify(listener).onResponse(captor.capture());
        ClearClusterStateResult result = captor.getValue().body();
        assertFalse(result.dryRun());
        assertTrue(result.tryClose());
        assertTrue(result.migrations().isEmpty());
        verify(clusterService, never()).submitStateUpdateTask(anyString(), any(ClusterStateUpdateTask.class));
    }

    // ---- Dry run ----

    public void testDryRunDoesNotModifyState() {
        ClusterState state = stateWith(buildEntry("mig-1", "src-idx", CoordinatorPhase.ACTIVE));
        when(coordinatorService.getActiveMigrationIds()).thenReturn(Set.of("mig-1"));

        ActionListener<ClearClusterStateResponse> listener = mockListener();
        ClearClusterStateRequest request = new ClearClusterStateRequest(new ClearClusterStateBody(true, true, null, false));
        createAction().clusterManagerOperation(request, state, listener);

        // No cluster state update submitted
        verify(clusterService, never()).submitStateUpdateTask(anyString(), any(ClusterStateUpdateTask.class));
        // No coordinators closed
        verify(coordinatorService, never()).removeAndClose(any());

        ArgumentCaptor<ClearClusterStateResponse> captor = ArgumentCaptor.forClass(ClearClusterStateResponse.class);
        verify(listener).onResponse(captor.capture());
        ClearClusterStateResult result = captor.getValue().body();
        assertTrue(result.dryRun());
        assertEquals(1, result.migrations().size());

        ClearClusterStateResult.MigrationAction action = result.migrations().get("mig-1");
        assertEquals("src-idx", action.sourceIndex());
        assertEquals("ACTIVE", action.phase());
        assertEquals("would_clear", action.clusterState());
        assertEquals("would_clear", action.activeMigrations());
        assertFalse(action.closed());
        assertNull(action.entry());
    }

    public void testDryRunWithDetailedIncludesEntry() {
        AoscMigrationsClusterState.Entry entry = buildEntry("mig-1", "src-idx", CoordinatorPhase.FAILING);
        ClusterState state = stateWith(entry);

        ActionListener<ClearClusterStateResponse> listener = mockListener();
        ClearClusterStateRequest request = new ClearClusterStateRequest(new ClearClusterStateBody(true, true, null, true));
        createAction().clusterManagerOperation(request, state, listener);

        ArgumentCaptor<ClearClusterStateResponse> captor = ArgumentCaptor.forClass(ClearClusterStateResponse.class);
        verify(listener).onResponse(captor.capture());
        ClearClusterStateResult.MigrationAction action = captor.getValue().body().migrations().get("mig-1");
        assertNotNull(action.entry());
        assertEquals("src-idx", action.entry().sourceIndex());
    }

    // ---- Migration ID filter ----

    public void testMigrationIdFilterTargetsSingleEntry() throws Exception {
        ClusterState state = stateWith(
            buildEntry("mig-1", "src-1", CoordinatorPhase.ACTIVE),
            buildEntry("mig-2", "src-2", CoordinatorPhase.FAILING)
        );
        when(coordinatorService.getActiveMigrationIds()).thenReturn(Set.of("mig-1", "mig-2"));
        when(coordinatorService.removeAndClose(any())).thenReturn(Set.of("mig-1"));

        ActionListener<ClearClusterStateResponse> listener = mockListener();
        ClearClusterStateRequest request = new ClearClusterStateRequest(new ClearClusterStateBody(false, true, "mig-1", false));
        createAction().clusterManagerOperation(request, state, listener);

        // Should submit a cluster state update
        ArgumentCaptor<ClusterStateUpdateTask> taskCaptor = ArgumentCaptor.forClass(ClusterStateUpdateTask.class);
        verify(clusterService).submitStateUpdateTask(anyString(), taskCaptor.capture());

        // Execute the task and verify only mig-1 is removed, mig-2 remains
        ClusterState newState = taskCaptor.getValue().execute(state);
        AoscMigrationsClusterState aoscState = newState.custom(AoscMigrationsClusterState.TYPE);
        assertNotNull(aoscState);
        assertNull(aoscState.getEntry("mig-1"));
        assertNotNull(aoscState.getEntry("mig-2"));
    }

    public void testUnknownMigrationIdReturnsEmptyResult() {
        ClusterState state = stateWith(buildEntry("mig-1", "src-1", CoordinatorPhase.ACTIVE));

        ActionListener<ClearClusterStateResponse> listener = mockListener();
        ClearClusterStateRequest request = new ClearClusterStateRequest(new ClearClusterStateBody(false, true, "nonexistent", false));
        createAction().clusterManagerOperation(request, state, listener);

        ArgumentCaptor<ClearClusterStateResponse> captor = ArgumentCaptor.forClass(ClearClusterStateResponse.class);
        verify(listener).onResponse(captor.capture());
        assertTrue(captor.getValue().body().migrations().isEmpty());
        verify(clusterService, never()).submitStateUpdateTask(anyString(), any(ClusterStateUpdateTask.class));
    }

    // ---- try_close ----

    public void testTryCloseCallsRemoveAndClose() throws Exception {
        ClusterState state = stateWith(buildEntry("mig-1", "src-idx", CoordinatorPhase.FAILED));
        when(coordinatorService.getActiveMigrationIds()).thenReturn(Set.of("mig-1"));
        when(coordinatorService.removeAndClose(any())).thenReturn(Set.of("mig-1"));

        ActionListener<ClearClusterStateResponse> listener = mockListener();
        ClearClusterStateRequest request = new ClearClusterStateRequest(new ClearClusterStateBody(false, true, null, false));
        createAction().clusterManagerOperation(request, state, listener);

        ArgumentCaptor<ClusterStateUpdateTask> taskCaptor = ArgumentCaptor.forClass(ClusterStateUpdateTask.class);
        verify(clusterService).submitStateUpdateTask(anyString(), taskCaptor.capture());

        // Simulate successful cluster state processing
        taskCaptor.getValue().clusterStateProcessed("test", state, emptyState());

        verify(coordinatorService).removeAndClose(any());

        ArgumentCaptor<ClearClusterStateResponse> respCaptor = ArgumentCaptor.forClass(ClearClusterStateResponse.class);
        verify(listener).onResponse(respCaptor.capture());
        ClearClusterStateResult.MigrationAction action = respCaptor.getValue().body().migrations().get("mig-1");
        assertEquals("cleared", action.clusterState());
        assertEquals("cleared", action.activeMigrations());
        assertTrue(action.closed());
    }

    public void testTryCloseFalseSkipsRemoveAndClose() throws Exception {
        ClusterState state = stateWith(buildEntry("mig-1", "src-idx", CoordinatorPhase.FAILED));
        when(coordinatorService.getActiveMigrationIds()).thenReturn(Set.of("mig-1"));

        ActionListener<ClearClusterStateResponse> listener = mockListener();
        ClearClusterStateRequest request = new ClearClusterStateRequest(new ClearClusterStateBody(false, false, null, false));
        createAction().clusterManagerOperation(request, state, listener);

        ArgumentCaptor<ClusterStateUpdateTask> taskCaptor = ArgumentCaptor.forClass(ClusterStateUpdateTask.class);
        verify(clusterService).submitStateUpdateTask(anyString(), taskCaptor.capture());

        taskCaptor.getValue().clusterStateProcessed("test", state, emptyState());

        verify(coordinatorService, never()).removeAndClose(any());

        ArgumentCaptor<ClearClusterStateResponse> respCaptor = ArgumentCaptor.forClass(ClearClusterStateResponse.class);
        verify(listener).onResponse(respCaptor.capture());
        ClearClusterStateResult.MigrationAction action = respCaptor.getValue().body().migrations().get("mig-1");
        assertEquals("cleared", action.clusterState());
        assertEquals("not_found", action.activeMigrations());
        assertFalse(action.closed());
    }

    // ---- Cluster state update task ----

    public void testUpdateTaskRemovesAllEntries() throws Exception {
        when(coordinatorService.getActiveMigrationIds()).thenReturn(Set.of("mig-1", "mig-2"));
        ClusterState state = stateWith(
            buildEntry("mig-1", "src-1", CoordinatorPhase.ACTIVE),
            buildEntry("mig-2", "src-2", CoordinatorPhase.FAILED)
        );

        ActionListener<ClearClusterStateResponse> listener = mockListener();
        createAction().clusterManagerOperation(new ClearClusterStateRequest(ClearClusterStateBody.builder().dryRun(false).build()), state, listener);

        ArgumentCaptor<ClusterStateUpdateTask> captor = ArgumentCaptor.forClass(ClusterStateUpdateTask.class);
        verify(clusterService).submitStateUpdateTask(anyString(), captor.capture());

        ClusterState newState = captor.getValue().execute(state);
        // After removing all entries, the custom is still present but empty
        AoscMigrationsClusterState aoscState = newState.custom(AoscMigrationsClusterState.TYPE);
        assertTrue(aoscState == null || aoscState.entries().isEmpty());
    }

    public void testUpdateTaskOnFailureCallsListenerOnFailure() throws Exception {
        ClusterState state = stateWith(buildEntry("mig-1", "src-idx", CoordinatorPhase.ACTIVE));

        @SuppressWarnings("unchecked")
        ActionListener<ClearClusterStateResponse> listener = mock(ActionListener.class);
        createAction().clusterManagerOperation(
            new ClearClusterStateRequest(ClearClusterStateBody.builder().dryRun(false).build()),
            state,
            listener
        );

        ArgumentCaptor<ClusterStateUpdateTask> captor = ArgumentCaptor.forClass(ClusterStateUpdateTask.class);
        verify(clusterService).submitStateUpdateTask(anyString(), captor.capture());

        RuntimeException error = new RuntimeException("simulated failure");
        captor.getValue().onFailure("test", error);

        verify(listener).onFailure(error);
    }

    // ---- Orphan detection ----

    public void testOrphanedCoordinatorWithNoClusterStateEntry() throws Exception {
        // Coordinator exists in-memory but not in cluster state
        when(coordinatorService.getActiveMigrationIds()).thenReturn(Set.of("orphan-1"));
        when(coordinatorService.removeAndClose(any())).thenReturn(Set.of("orphan-1"));

        ActionListener<ClearClusterStateResponse> listener = mockListener();
        ClusterState state = emptyState();
        createAction().clusterManagerOperation(new ClearClusterStateRequest(ClearClusterStateBody.builder().dryRun(false).build()), state, listener);

        // Cluster state update is still submitted (execute() is a no-op when aoscState is null)
        ArgumentCaptor<ClusterStateUpdateTask> taskCaptor = ArgumentCaptor.forClass(ClusterStateUpdateTask.class);
        verify(clusterService).submitStateUpdateTask(anyString(), taskCaptor.capture());

        // The task should be a no-op — state unchanged
        ClusterState newState = taskCaptor.getValue().execute(state);
        assertNull(newState.custom(AoscMigrationsClusterState.TYPE));

        // Simulate successful processing
        taskCaptor.getValue().clusterStateProcessed("test", state, newState);

        ArgumentCaptor<ClearClusterStateResponse> captor = ArgumentCaptor.forClass(ClearClusterStateResponse.class);
        verify(listener).onResponse(captor.capture());
        ClearClusterStateResult.MigrationAction action = captor.getValue().body().migrations().get("orphan-1");
        assertNotNull(action);
        assertEquals("not_found", action.clusterState());
        assertEquals("cleared", action.activeMigrations());
        assertTrue(action.closed());
    }

    // ---- Helpers ----

    @SuppressWarnings("unchecked")
    private static ActionListener<ClearClusterStateResponse> mockListener() {
        return mock(ActionListener.class);
    }
}
