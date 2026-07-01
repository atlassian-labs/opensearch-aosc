/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */
package com.atlassian.opensearch.aosc.service.coordinator;

import com.atlassian.opensearch.aosc.AoscSettings;
import com.atlassian.opensearch.aosc.AoscTestUtil;
import com.atlassian.opensearch.aosc.model.AoscMigrationsClusterState;
import com.atlassian.opensearch.aosc.model.MigrationDocument;
import com.atlassian.opensearch.aosc.model.MigrationMetadata;
import com.atlassian.opensearch.aosc.model.MigrationRequestOptions;
import com.atlassian.opensearch.aosc.model.ShardProgressDocument;
import com.atlassian.opensearch.aosc.model.ShardRoutingMode;
import com.atlassian.opensearch.aosc.model.phase.CoordinatorPhase;
import com.atlassian.opensearch.aosc.model.phase.ShardPhase;
import com.atlassian.opensearch.aosc.model.transform.InlineTransformScript;
import com.atlassian.opensearch.aosc.utils.AoscLogger;

import com.carrotsearch.randomizedtesting.annotations.ThreadLeakScope;

import org.apache.lucene.search.TotalHits;

import org.opensearch.action.admin.indices.alias.IndicesAliasesRequest;
import org.opensearch.action.admin.indices.flush.FlushRequest;
import org.opensearch.action.admin.indices.flush.FlushResponse;
import org.opensearch.action.admin.indices.readonly.AddIndexBlockRequest;
import org.opensearch.action.admin.indices.readonly.AddIndexBlockResponse;
import org.opensearch.action.admin.indices.refresh.RefreshRequest;
import org.opensearch.action.admin.indices.refresh.RefreshResponse;
import org.opensearch.action.admin.indices.settings.put.UpdateSettingsRequest;
import org.opensearch.action.search.SearchRequest;
import org.opensearch.action.search.SearchResponse;
import org.opensearch.action.support.clustermanager.AcknowledgedResponse;
import org.opensearch.cluster.ClusterState;
import org.opensearch.cluster.ClusterStateUpdateTask;
import org.opensearch.cluster.service.ClusterService;
import org.opensearch.common.settings.ClusterSettings;
import org.opensearch.common.settings.Settings;
import org.opensearch.common.unit.TimeValue;
import org.opensearch.core.action.ActionListener;
import org.opensearch.index.IndexNotFoundException;
import org.opensearch.search.SearchHit;
import org.opensearch.search.SearchHits;
import org.opensearch.test.OpenSearchTestCase;
import org.opensearch.threadpool.ThreadPool;
import org.opensearch.transport.client.AdminClient;
import org.opensearch.transport.client.Client;
import org.opensearch.transport.client.IndicesAdminClient;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.mockito.ArgumentCaptor;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tests for {@link MigrationCoordinator} using AwaitableStateMachine + ShardGate.
 */
@ThreadLeakScope(ThreadLeakScope.Scope.NONE)
public class MigrationCoordinatorTests extends OpenSearchTestCase {

    private Client mockClient;
    private ClusterService mockClusterService;
    private ThreadPool mockThreadPool;
    private MigrationDocumentService mockMigrationDocumentService;
    private IndicesAdminClient mockIndicesAdmin;
    private final List<ScheduledExecutorService> testSchedulers = new ArrayList<>();

    @Override
    public void setUp() throws Exception {
        super.setUp();
        mockClient = mock(Client.class);
        mockClusterService = mock(ClusterService.class);
        mockThreadPool = mock(ThreadPool.class);
        mockMigrationDocumentService = mock(MigrationDocumentService.class);

        // Mock admin client chain
        AdminClient mockAdminClient = mock(AdminClient.class);
        mockIndicesAdmin = mock(IndicesAdminClient.class);
        when(mockClient.admin()).thenReturn(mockAdminClient);
        when(mockAdminClient.indices()).thenReturn(mockIndicesAdmin);

        // Mock scheduleWithFixedDelay: run the task on a real scheduler so liveness checks fire
        doAnswer(invocation -> {
            Runnable task = invocation.getArgument(0);
            TimeValue interval = invocation.getArgument(1);
            ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "test-scheduler");
                t.setDaemon(true);
                return t;
            });
            testSchedulers.add(scheduler);
            long millis = interval.millis();
            ScheduledFuture<?> future = scheduler.scheduleAtFixedRate(task, millis, millis, TimeUnit.MILLISECONDS);
            return new ThreadPool.Cancellable() {
                private volatile boolean cancelled;

                @Override
                public boolean cancel() {
                    cancelled = true;
                    future.cancel(false);
                    scheduler.shutdown();
                    return true;
                }

                @Override
                public boolean isCancelled() {
                    return cancelled;
                }
            };
        }).when(mockThreadPool).scheduleWithFixedDelay(any(), any(), any());

        // Mock ClusterState and submitStateUpdateTask to complete immediately
        ClusterState mockState = mock(ClusterState.class);
        when(mockClusterService.state()).thenReturn(mockState);
        when(mockClusterService.getSettings()).thenReturn(Settings.EMPTY);
        when(mockClusterService.getClusterSettings()).thenReturn(new ClusterSettings(Settings.EMPTY, new HashSet<>(AoscSettings.ALL)));

        // Make submitStateUpdateTask execute the task and call clusterStateProcessed
        doAnswer(invocation -> {
            org.opensearch.cluster.ClusterStateUpdateTask task = invocation.getArgument(1);
            try {
                ClusterState newState = task.execute(mockState);
                task.clusterStateProcessed(invocation.getArgument(0), mockState, newState);
            } catch (Exception e) {
                task.onFailure(invocation.getArgument(0), e);
            }
            return null;
        }).when(mockClusterService).submitStateUpdateTask(any(String.class), any(ClusterStateUpdateTask.class));

        // Mock createMigrationDocument to succeed
        when(mockMigrationDocumentService.createMigrationDocument(any(MigrationDocument.class))).thenReturn(
            CompletableFuture.completedFuture(null)
        );
        // Mock persistFinalState to succeed (called on migration terminal)
        when(mockMigrationDocumentService.persistFinalState(any(), any())).thenReturn(CompletableFuture.completedFuture(null));

        // Mock IndexOperationUtils methods for transient settings and green status waits
        mockUpdateSettingsSuccess();
        mockAliasSuccess();
    }

    @Override
    public void tearDown() throws Exception {
        testSchedulers.forEach(ScheduledExecutorService::shutdownNow);
        testSchedulers.clear();
        super.tearDown();
    }

    // ---- Test: Cancel signal routes to CANCELLING ----

    public void testCancelTransitionsToCancelling() throws Exception {
        Map<Integer, AoscMigrationsClusterState.ShardMigrationClusterState> shards = new HashMap<>();
        shards.put(
            0,
            AoscMigrationsClusterState.ShardMigrationClusterState.builder()
                .phase(ShardPhase.BACKFILLING)
                .lastReplayedSeqNo(0L)
                .backfillCutoffSeqNo(0L)
                .failure(null)
                .meta(MigrationMetadata.EMPTY)
                .build()
        );
        AoscMigrationsClusterState.Entry entry = entryWithShards("migration-cancel", CoordinatorPhase.ACTIVE, shards);

        // Mock updateSettings to succeed (for rollback operations)
        mockUpdateSettingsSuccess();
        mockAliasSuccess();

        MigrationCoordinator coordinator = new MigrationCoordinator(
            AoscLogger.create(MigrationCoordinator.class),
            "migration-cancel",
            CoordinatorPhase.ACTIVE,
            entry,
            mockClient,
            mockClusterService,
            mockThreadPool,
            mockMigrationDocumentService,
            () -> {}
        );

        // Start the SM so it enters ACTIVE and waits on the gate
        coordinator.start();

        // Allow SM to start
        Thread.sleep(50);

        // Cancel — should cancel the gate and transition to CANCELLING
        coordinator.cancel();
        Thread.sleep(50);

        // onCancelling waits for all shards to reach terminal — report shard 0 as CANCELLED
        coordinator.acceptShardUpdate(0, ShardProgressDocument.builder().phase(ShardPhase.CANCELLED).build());

        assertBusy(() -> {
            CoordinatorPhase phase = coordinator.phase();
            assertTrue(
                "Expected CANCELLING or CANCELLED but got: " + phase,
                phase == CoordinatorPhase.CANCELLING || phase == CoordinatorPhase.CANCELLED
            );
        }, 5, TimeUnit.SECONDS);

        coordinator.close();
    }

    // ---- Test: failWithReason triggers FAILING phase ----

    public void testFailWithReasonTransitionsToFailing() throws Exception {
        Map<Integer, AoscMigrationsClusterState.ShardMigrationClusterState> shards = new HashMap<>();
        shards.put(
            0,
            AoscMigrationsClusterState.ShardMigrationClusterState.builder()
                .phase(ShardPhase.BACKFILLING)
                .lastReplayedSeqNo(-1)
                .backfillCutoffSeqNo(-1)
                .failure(null)
                .meta(MigrationMetadata.EMPTY)
                .build()
        );
        AoscMigrationsClusterState.Entry entry = entryWithShards("migration-fail", CoordinatorPhase.ACTIVE, shards);

        // Mock alias and settings for rollback
        mockAliasSuccess();
        mockUpdateSettingsSuccess();

        MigrationCoordinator coordinator = new MigrationCoordinator(
            AoscLogger.create(MigrationCoordinator.class),
            "migration-fail",
            CoordinatorPhase.ACTIVE,
            entry,
            mockClient,
            mockClusterService,
            mockThreadPool,
            mockMigrationDocumentService,
            () -> {}
        );

        coordinator.start();
        Thread.sleep(50);

        coordinator.failWithReason("Source shard [0] is UNASSIGNED (not STARTED)");

        assertBusy(() -> {
            CoordinatorPhase phase = coordinator.phase();
            assertTrue(
                "Expected FAILING or FAILED but got: " + phase,
                phase == CoordinatorPhase.FAILING || phase == CoordinatorPhase.FAILED
            );
        }, 5, TimeUnit.SECONDS);

        coordinator.close();
    }

    // ---- Test: onFailing performs alias rollback ----

    @SuppressWarnings("unchecked")
    public void testFailingPhasePerformsAliasRollback() throws Exception {
        mockAliasSuccess();
        mockUpdateSettingsSuccess();

        Map<Integer, AoscMigrationsClusterState.ShardMigrationClusterState> shards = new HashMap<>();
        shards.put(
            0,
            AoscMigrationsClusterState.ShardMigrationClusterState.builder()
                .phase(ShardPhase.BACKFILLING)
                .lastReplayedSeqNo(-1)
                .backfillCutoffSeqNo(-1)
                .failure(null)
                .meta(MigrationMetadata.EMPTY)
                .build()
        );
        AoscMigrationsClusterState.Entry entry = entryWithShards("migration-rollback", CoordinatorPhase.ACTIVE, shards);

        MigrationCoordinator coordinator = new MigrationCoordinator(
            AoscLogger.create(MigrationCoordinator.class),
            "migration-rollback",
            CoordinatorPhase.ACTIVE,
            entry,
            mockClient,
            mockClusterService,
            mockThreadPool,
            mockMigrationDocumentService,
            () -> {}
        );

        coordinator.start();
        Thread.sleep(50);

        coordinator.failWithReason("Test failure");
        Thread.sleep(50);

        // onFailing waits for all shards to reach terminal — report shard 0 as FAILED
        coordinator.acceptShardUpdate(0, ShardProgressDocument.builder().phase(ShardPhase.FAILED).error("test").build());

        // Wait for FAILED — the handler must complete (including alias rollback) before reaching terminal
        assertBusy(() -> {
            CoordinatorPhase phase = coordinator.phase();
            assertEquals("Expected FAILED but got: " + phase, CoordinatorPhase.FAILED, phase);
        }, 5, TimeUnit.SECONDS);

        // Verify alias rollback was attempted (swapAlias calls client.admin().indices().aliases())
        verify(mockIndicesAdmin, atLeastOnce()).aliases(any(IndicesAliasesRequest.class), any(ActionListener.class));

        coordinator.close();
    }

    // ---- Test: acceptShardUpdate routes to gate ----

    public void testAcceptShardUpdateSignalsGate() throws Exception {
        Map<Integer, AoscMigrationsClusterState.ShardMigrationClusterState> shards = new HashMap<>();
        shards.put(
            0,
            AoscMigrationsClusterState.ShardMigrationClusterState.builder()
                .phase(ShardPhase.BACKFILLING)
                .lastReplayedSeqNo(0L)
                .backfillCutoffSeqNo(0L)
                .failure(null)
                .meta(MigrationMetadata.EMPTY)
                .build()
        );
        AoscMigrationsClusterState.Entry entry = entryWithShards("migration-gate", CoordinatorPhase.ACTIVE, shards);

        // Mock settings for write block + cutover
        mockUpdateSettingsSuccess();
        mockAliasSuccess();

        MigrationCoordinator coordinator = new MigrationCoordinator(
            AoscLogger.create(MigrationCoordinator.class),
            "migration-gate",
            CoordinatorPhase.ACTIVE,
            entry,
            mockClient,
            mockClusterService,
            mockThreadPool,
            mockMigrationDocumentService,
            () -> {}
        );

        coordinator.start();

        // Wait for the SM handler to run and create the gate
        Thread.sleep(200);

        // Report shard 0 as CONVERGED — should satisfy the gate and advance to CUTTING_OVER
        coordinator.acceptShardUpdate(0, ShardProgressDocument.builder().phase(ShardPhase.CONVERGED).build());

        assertBusy(() -> {
            CoordinatorPhase phase = coordinator.phase();
            assertTrue(
                "Expected CUTTING_OVER or beyond but got: " + phase,
                phase == CoordinatorPhase.CUTTING_OVER
                    || phase == CoordinatorPhase.CATCHING_UP
                    || phase == CoordinatorPhase.COMPLETING
                    || phase == CoordinatorPhase.COMPLETED
            );
        }, 5, TimeUnit.SECONDS);

        coordinator.close();
    }

    // ---- Test: Shard failure causes FAILING ----

    public void testShardFailureCausesFailing() throws Exception {
        Map<Integer, AoscMigrationsClusterState.ShardMigrationClusterState> shards = new HashMap<>();
        shards.put(
            0,
            AoscMigrationsClusterState.ShardMigrationClusterState.builder()
                .phase(ShardPhase.BACKFILLING)
                .lastReplayedSeqNo(0L)
                .backfillCutoffSeqNo(0L)
                .failure(null)
                .meta(MigrationMetadata.EMPTY)
                .build()
        );
        AoscMigrationsClusterState.Entry entry = entryWithShards("migration-shard-fail", CoordinatorPhase.ACTIVE, shards);

        mockAliasSuccess();
        mockUpdateSettingsSuccess();

        MigrationCoordinator coordinator = new MigrationCoordinator(
            AoscLogger.create(MigrationCoordinator.class),
            "migration-shard-fail",
            CoordinatorPhase.ACTIVE,
            entry,
            mockClient,
            mockClusterService,
            mockThreadPool,
            mockMigrationDocumentService,
            () -> {}
        );

        coordinator.start();

        // Wait for the SM handler to run and create the gate
        Thread.sleep(200);

        // Report shard 0 as FAILED — gate should complete with SHARD_FAILED → coordinator goes to FAILING
        coordinator.acceptShardUpdate(0, ShardProgressDocument.builder().phase(ShardPhase.FAILED).error("shard error").build());

        assertBusy(() -> {
            CoordinatorPhase phase = coordinator.phase();
            assertTrue(
                "Expected FAILING or FAILED but got: " + phase,
                phase == CoordinatorPhase.FAILING || phase == CoordinatorPhase.FAILED
            );
        }, 5, TimeUnit.SECONDS);

        coordinator.close();
    }

    // ---- Test: migration terminal triggers bulk Tier-1 write for all shards ----

    public void testMigrationTerminalWritesAllShardsToTier1() throws Exception {
        Map<Integer, AoscMigrationsClusterState.ShardMigrationClusterState> shards = new HashMap<>();
        shards.put(
            0,
            AoscMigrationsClusterState.ShardMigrationClusterState.builder()
                .phase(ShardPhase.BACKFILLING)
                .lastReplayedSeqNo(0L)
                .backfillCutoffSeqNo(0L)
                .failure(null)
                .meta(MigrationMetadata.EMPTY)
                .build()
        );
        AoscMigrationsClusterState.Entry entry = entryWithShards("migration-tier1", CoordinatorPhase.ACTIVE, shards);

        // Mock alias and settings for rollback in FAILING handler
        mockAliasSuccess();
        mockUpdateSettingsSuccess();

        AtomicBoolean terminalCallbackRan = new AtomicBoolean(false);

        MigrationCoordinator coordinator = new MigrationCoordinator(
            AoscLogger.create(MigrationCoordinator.class),
            "migration-tier1",
            CoordinatorPhase.ACTIVE,
            entry,
            mockClient,
            mockClusterService,
            mockThreadPool,
            mockMigrationDocumentService,
            () -> terminalCallbackRan.set(true)
        );

        coordinator.start();
        assertBusy(() -> assertEquals(CoordinatorPhase.ACTIVE, coordinator.phase()), 5, TimeUnit.SECONDS);

        // Trigger migration failure so coordinator reaches terminal
        coordinator.failWithReason("test failure");

        // Report shard 0 as FAILED with full progress — satisfies the gate
        ShardProgressDocument progress = ShardProgressDocument.builder().phase(ShardPhase.FAILED).error("test error").build();
        coordinator.acceptShardUpdate(0, progress);

        // Wait for the coordinator to reach FAILED terminal state
        assertBusy(() -> assertEquals(CoordinatorPhase.FAILED, coordinator.phase()), 5, TimeUnit.SECONDS);

        // onTerminalReached should have been called, which triggers bulk Tier-1 write
        assertBusy(() -> assertTrue("Terminal callback should have run", terminalCallbackRan.get()), 5, TimeUnit.SECONDS);

        // Verify persistFinalState was called
        assertBusy(() -> verify(mockMigrationDocumentService).persistFinalState(any(), any()), 5, TimeUnit.SECONDS);

        coordinator.close();
    }

    // ---- Test: acceptShardUpdate for unknown shard ordinal fails exceptionally ----

    public void testAcceptShardUpdateUnknownShardFails() throws Exception {
        Map<Integer, AoscMigrationsClusterState.ShardMigrationClusterState> shards = new HashMap<>();
        shards.put(
            0,
            AoscMigrationsClusterState.ShardMigrationClusterState.builder()
                .phase(ShardPhase.BACKFILLING)
                .lastReplayedSeqNo(0L)
                .backfillCutoffSeqNo(0L)
                .failure(null)
                .meta(MigrationMetadata.EMPTY)
                .build()
        );
        AoscMigrationsClusterState.Entry entry = entryWithShards("migration-unknown-shard", CoordinatorPhase.ACTIVE, shards);

        MigrationCoordinator coordinator = new MigrationCoordinator(
            AoscLogger.create(MigrationCoordinator.class),
            "migration-unknown-shard",
            CoordinatorPhase.ACTIVE,
            entry,
            mockClient,
            mockClusterService,
            mockThreadPool,
            mockMigrationDocumentService,
            () -> {}
        );

        ShardProgressDocument progress = ShardProgressDocument.builder().phase(ShardPhase.BACKFILLING).build();

        CompletableFuture<Void> result = coordinator.acceptShardUpdate(999, progress);

        assertTrue(result.isCompletedExceptionally());
        Exception thrown = expectThrows(ExecutionException.class, () -> result.get(5, TimeUnit.SECONDS));
        assertTrue(thrown.getCause() instanceof IllegalArgumentException);

        coordinator.close();
    }

    // ---- Mock helpers ----

    @SuppressWarnings("unchecked")
    private void mockAliasSuccess() {
        doAnswer(invocation -> {
            ActionListener<AcknowledgedResponse> listener = invocation.getArgument(1);
            listener.onResponse(new AcknowledgedResponse(true) {
            });
            return null;
        }).when(mockIndicesAdmin).aliases(any(IndicesAliasesRequest.class), any(ActionListener.class));
    }

    @SuppressWarnings("unchecked")
    private void mockUpdateSettingsSuccess() {
        doAnswer(invocation -> {
            ActionListener<AcknowledgedResponse> listener = invocation.getArgument(1);
            listener.onResponse(new AcknowledgedResponse(true) {
            });
            return null;
        }).when(mockIndicesAdmin).updateSettings(any(UpdateSettingsRequest.class), any(ActionListener.class));
    }

    // ---- Test: a cutover failure surfaces a real error_message instead of "unknown" ----

    /**
     * A doc-count mismatch at cutover throws inside the COMPLETING handler, reaching the state
     * machine's generic onFailure rather than failWithReason(). This asserts that path now records
     * the real reason in the status document instead of the "unknown" default applied by onFailing.
     */
    @SuppressWarnings("unchecked")
    public void testCutoverDocCountMismatchSurfacesRealErrorMessage() throws Exception {
        mockUpdateSettingsSuccess();
        mockAliasSuccess();
        mockAddBlockSuccess();
        mockFlushSuccess();
        mockRefreshSuccess();
        // Source has 1000 docs, target only 844 (156 short, as on shard 116); tolerance defaults to 0,
        // so cutover doc-count validation throws DocCountValidationException.
        mockSearchDocCounts("source-idx", 1000L, "target-idx", 844L);

        Map<Integer, AoscMigrationsClusterState.ShardMigrationClusterState> shards = new HashMap<>();
        shards.put(
            0,
            AoscMigrationsClusterState.ShardMigrationClusterState.builder()
                .phase(ShardPhase.BACKFILLING)
                .lastReplayedSeqNo(0L)
                .backfillCutoffSeqNo(0L)
                .failure(null)
                .meta(MigrationMetadata.EMPTY)
                .build()
        );
        AoscMigrationsClusterState.Entry entry = entryWithShards("migration-cutover-doccount", CoordinatorPhase.ACTIVE, shards);

        MigrationCoordinator coordinator = new MigrationCoordinator(
            AoscLogger.create(MigrationCoordinator.class),
            "migration-cutover-doccount",
            CoordinatorPhase.ACTIVE,
            entry,
            mockClient,
            mockClusterService,
            mockThreadPool,
            mockMigrationDocumentService,
            () -> {}
        );

        coordinator.start();
        Thread.sleep(50);

        // Drive the shard to COMPLETED so the coordinator runs through both gates (CONVERGED, then
        // COMPLETED) into COMPLETING, where cutover validates doc counts and fails.
        coordinator.acceptShardUpdate(0, ShardProgressDocument.builder().phase(ShardPhase.COMPLETED).build());

        assertBusy(() -> {
            String error = coordinator.buildStatusDocument().errorMessage();
            assertNotNull("error_message should be populated on cutover failure", error);
            assertNotEquals("error_message must not fall back to the 'unknown' default", "unknown", error);
            assertTrue(
                "error_message should describe the doc-count mismatch but was: " + error,
                error.contains("Doc count validation failed") && error.contains("diff=156")
            );
        }, 10, TimeUnit.SECONDS);

        coordinator.close();
    }

    @SuppressWarnings("unchecked")
    private void mockRefreshSuccess() {
        doAnswer(invocation -> {
            ActionListener<RefreshResponse> listener = invocation.getArgument(1);
            listener.onResponse(mock(RefreshResponse.class));
            return null;
        }).when(mockIndicesAdmin).refresh(any(RefreshRequest.class), any(ActionListener.class));
    }

    @SuppressWarnings("unchecked")
    private void mockFlushSuccess() {
        doAnswer(invocation -> {
            ActionListener<FlushResponse> listener = invocation.getArgument(1);
            listener.onResponse(mock(FlushResponse.class));
            return null;
        }).when(mockIndicesAdmin).flush(any(FlushRequest.class), any(ActionListener.class));
    }

    @SuppressWarnings("unchecked")
    private void mockAddBlockSuccess() {
        doAnswer(invocation -> {
            ActionListener<AddIndexBlockResponse> listener = invocation.getArgument(1);
            listener.onResponse(mock(AddIndexBlockResponse.class));
            return null;
        }).when(mockIndicesAdmin).addBlock(any(AddIndexBlockRequest.class), any(ActionListener.class));
    }

    @SuppressWarnings("unchecked")
    private void mockSearchDocCounts(String sourceIndex, long sourceCount, String targetIndex, long targetCount) {
        doAnswer(invocation -> {
            SearchRequest req = invocation.getArgument(0);
            ActionListener<SearchResponse> listener = invocation.getArgument(1);
            String requestedIndex = req.indices()[0];
            long count;
            if (requestedIndex.equals(sourceIndex)) {
                count = sourceCount;
            } else if (requestedIndex.equals(targetIndex)) {
                count = targetCount;
            } else {
                listener.onFailure(new IllegalArgumentException("Unexpected index: " + requestedIndex));
                return null;
            }
            SearchHits hits = new SearchHits(new SearchHit[0], new TotalHits(count, TotalHits.Relation.EQUAL_TO), 1.0f);
            SearchResponse response = mock(SearchResponse.class);
            when(response.getHits()).thenReturn(hits);
            listener.onResponse(response);
            return null;
        }).when(mockClient).search(any(SearchRequest.class), any(ActionListener.class));
    }

    // ---- Test: rollback restores transient target settings on failure ----

    @SuppressWarnings("unchecked")
    public void testFailingPhaseRestoresTransientTargetSettings() throws Exception {
        mockAliasSuccess();
        mockUpdateSettingsSuccess();

        // Build metadata with original target settings captured (simulating what onInitializing does)
        MigrationMetadata metaWithOriginals = MigrationMetadata.builder()
            .putOriginalTargetSettings(Map.of("index.number_of_replicas", "1", "index.refresh_interval", "1s"))
            .build();

        Map<Integer, AoscMigrationsClusterState.ShardMigrationClusterState> shards = new HashMap<>();
        shards.put(
            0,
            AoscMigrationsClusterState.ShardMigrationClusterState.builder()
                .phase(ShardPhase.BACKFILLING)
                .lastReplayedSeqNo(-1)
                .backfillCutoffSeqNo(-1)
                .failure(null)
                .meta(MigrationMetadata.EMPTY)
                .build()
        );
        AoscMigrationsClusterState.Entry entry = AoscMigrationsClusterState.Entry.builder()
            .migrationId("migration-restore-settings")
            .sourceIndex("source-idx")
            .targetIndex("target-idx")
            .alias("my-alias")
            .transformScript(new InlineTransformScript("ctx._source.version = 2", null))
            .options(AoscTestUtil.defaultMigrationOptions())
            .phase(CoordinatorPhase.ACTIVE)
            .routingMode(ShardRoutingMode.BULK_API)
            .startTimeMillis(System.currentTimeMillis())
            .shards(shards)
            .failure(null)
            .meta(metaWithOriginals)
            .build();

        MigrationCoordinator coordinator = new MigrationCoordinator(
            AoscLogger.create(MigrationCoordinator.class),
            "migration-restore-settings",
            CoordinatorPhase.ACTIVE,
            entry,
            mockClient,
            mockClusterService,
            mockThreadPool,
            mockMigrationDocumentService,
            () -> {}
        );

        coordinator.start();
        Thread.sleep(50);

        coordinator.failWithReason("Test failure — expect target settings restore");
        Thread.sleep(50);

        // Report shard 0 as FAILED so the coordinator can reach terminal
        coordinator.acceptShardUpdate(0, ShardProgressDocument.builder().phase(ShardPhase.FAILED).error("test").build());

        assertBusy(() -> {
            CoordinatorPhase phase = coordinator.phase();
            assertEquals("Expected FAILED but got: " + phase, CoordinatorPhase.FAILED, phase);
        }, 5, TimeUnit.SECONDS);

        // Verify updateSettings was called (covers both transient restore + rebalance/write-block rollback)
        verify(mockIndicesAdmin, atLeastOnce()).updateSettings(any(UpdateSettingsRequest.class), any(ActionListener.class));
        // Verify alias rollback was also attempted
        verify(mockIndicesAdmin, atLeastOnce()).aliases(any(IndicesAliasesRequest.class), any(ActionListener.class));

        coordinator.close();
    }

    // ---- Test: rollback restores transient target settings on cancel ----

    @SuppressWarnings("unchecked")
    public void testCancellingPhaseRestoresTransientTargetSettings() throws Exception {
        mockAliasSuccess();
        mockUpdateSettingsSuccess();

        // Build metadata with original target settings captured
        MigrationMetadata metaWithOriginals = MigrationMetadata.builder()
            .putOriginalTargetSettings(Map.of("index.number_of_replicas", "1", "index.refresh_interval", "1s"))
            .build();

        Map<Integer, AoscMigrationsClusterState.ShardMigrationClusterState> shards = new HashMap<>();
        shards.put(
            0,
            AoscMigrationsClusterState.ShardMigrationClusterState.builder()
                .phase(ShardPhase.BACKFILLING)
                .lastReplayedSeqNo(-1)
                .backfillCutoffSeqNo(-1)
                .failure(null)
                .meta(MigrationMetadata.EMPTY)
                .build()
        );
        AoscMigrationsClusterState.Entry entry = AoscMigrationsClusterState.Entry.builder()
            .migrationId("migration-cancel-restore")
            .sourceIndex("source-idx")
            .targetIndex("target-idx")
            .alias("my-alias")
            .transformScript(new InlineTransformScript("ctx._source.version = 2", null))
            .options(AoscTestUtil.defaultMigrationOptions())
            .phase(CoordinatorPhase.ACTIVE)
            .routingMode(ShardRoutingMode.BULK_API)
            .startTimeMillis(System.currentTimeMillis())
            .shards(shards)
            .failure(null)
            .meta(metaWithOriginals)
            .build();

        MigrationCoordinator coordinator = new MigrationCoordinator(
            AoscLogger.create(MigrationCoordinator.class),
            "migration-cancel-restore",
            CoordinatorPhase.ACTIVE,
            entry,
            mockClient,
            mockClusterService,
            mockThreadPool,
            mockMigrationDocumentService,
            () -> {}
        );

        coordinator.start();
        Thread.sleep(50);

        coordinator.cancel();
        Thread.sleep(50);

        // Report shard 0 as CANCELLED so the coordinator can reach terminal
        coordinator.acceptShardUpdate(0, ShardProgressDocument.builder().phase(ShardPhase.CANCELLED).build());

        assertBusy(() -> {
            CoordinatorPhase phase = coordinator.phase();
            assertEquals("Expected CANCELLED but got: " + phase, CoordinatorPhase.CANCELLED, phase);
        }, 5, TimeUnit.SECONDS);

        // Verify updateSettings was called (covers transient restore + rebalance/write-block rollback)
        verify(mockIndicesAdmin, atLeastOnce()).updateSettings(any(UpdateSettingsRequest.class), any(ActionListener.class));
        // Verify alias rollback was also attempted
        verify(mockIndicesAdmin, atLeastOnce()).aliases(any(IndicesAliasesRequest.class), any(ActionListener.class));

        coordinator.close();
    }

    // ---- Test: rollback tolerates transient restore failure (target already deleted) ----

    @SuppressWarnings("unchecked")
    public void testFailingPhaseSwallowsTransientRestoreFailure() throws Exception {
        mockAliasSuccess();

        // Make updateSettings fail for the first call (target settings restore) then succeed for subsequent calls
        // (rebalance restore, write block removal). No retries — matches onPreparingTarget single-attempt pattern.
        AtomicInteger updateSettingsCalls = new AtomicInteger(0);
        doAnswer(invocation -> {
            ActionListener<AcknowledgedResponse> listener = invocation.getArgument(1);
            int callNumber = updateSettingsCalls.incrementAndGet();
            if (callNumber == 1) {
                // First call is the target settings restore — fails (target already deleted)
                listener.onFailure(new IndexNotFoundException("target-idx"));
            } else {
                // Subsequent calls (rebalance, write-block) succeed
                listener.onResponse(new AcknowledgedResponse(true) {
                });
            }
            return null;
        }).when(mockIndicesAdmin).updateSettings(any(UpdateSettingsRequest.class), any(ActionListener.class));

        MigrationMetadata metaWithOriginals = MigrationMetadata.builder()
            .putOriginalTargetSettings(Map.of("index.number_of_replicas", "1"))
            .build();

        Map<Integer, AoscMigrationsClusterState.ShardMigrationClusterState> shards = new HashMap<>();
        shards.put(
            0,
            AoscMigrationsClusterState.ShardMigrationClusterState.builder()
                .phase(ShardPhase.BACKFILLING)
                .lastReplayedSeqNo(-1)
                .backfillCutoffSeqNo(-1)
                .failure(null)
                .meta(MigrationMetadata.EMPTY)
                .build()
        );
        AoscMigrationsClusterState.Entry entry = AoscMigrationsClusterState.Entry.builder()
            .migrationId("migration-restore-swallow")
            .sourceIndex("source-idx")
            .targetIndex("target-idx")
            .alias("my-alias")
            .transformScript(new InlineTransformScript("ctx._source.version = 2", null))
            .options(AoscTestUtil.defaultMigrationOptions())
            .phase(CoordinatorPhase.ACTIVE)
            .routingMode(ShardRoutingMode.BULK_API)
            .startTimeMillis(System.currentTimeMillis())
            .shards(shards)
            .failure(null)
            .meta(metaWithOriginals)
            .build();

        MigrationCoordinator coordinator = new MigrationCoordinator(
            AoscLogger.create(MigrationCoordinator.class),
            "migration-restore-swallow",
            CoordinatorPhase.ACTIVE,
            entry,
            mockClient,
            mockClusterService,
            mockThreadPool,
            mockMigrationDocumentService,
            () -> {}
        );

        coordinator.start();
        Thread.sleep(50);

        coordinator.failWithReason("Test failure — target deleted");
        Thread.sleep(50);

        coordinator.acceptShardUpdate(0, ShardProgressDocument.builder().phase(ShardPhase.FAILED).error("test").build());

        // Coordinator should still reach FAILED even though transient settings restore failed
        assertBusy(() -> {
            CoordinatorPhase phase = coordinator.phase();
            assertEquals("Expected FAILED but got: " + phase, CoordinatorPhase.FAILED, phase);
        }, 5, TimeUnit.SECONDS);

        coordinator.close();
    }

    // ---- Test: rollback skips restore when no transient settings configured ----

    @SuppressWarnings("unchecked")
    public void testRollbackSkipsRestoreWhenNoTransientSettingsConfigured() throws Exception {
        mockAliasSuccess();
        mockUpdateSettingsSuccess();

        // Build options without transient target settings
        MigrationRequestOptions optionsWithoutTransient = AoscTestUtil.defaultMigrationOptions().setTransientTargetSettings(null);

        Map<Integer, AoscMigrationsClusterState.ShardMigrationClusterState> shards = new HashMap<>();
        shards.put(
            0,
            AoscMigrationsClusterState.ShardMigrationClusterState.builder()
                .phase(ShardPhase.BACKFILLING)
                .lastReplayedSeqNo(-1)
                .backfillCutoffSeqNo(-1)
                .failure(null)
                .meta(MigrationMetadata.EMPTY)
                .build()
        );
        AoscMigrationsClusterState.Entry entry = AoscMigrationsClusterState.Entry.builder()
            .migrationId("migration-no-transient")
            .sourceIndex("source-idx")
            .targetIndex("target-idx")
            .alias("my-alias")
            .transformScript(new InlineTransformScript("ctx._source.version = 2", null))
            .options(optionsWithoutTransient)
            .phase(CoordinatorPhase.ACTIVE)
            .routingMode(ShardRoutingMode.BULK_API)
            .startTimeMillis(System.currentTimeMillis())
            .shards(shards)
            .failure(null)
            .meta(MigrationMetadata.EMPTY)
            .build();

        MigrationCoordinator coordinator = new MigrationCoordinator(
            AoscLogger.create(MigrationCoordinator.class),
            "migration-no-transient",
            CoordinatorPhase.ACTIVE,
            entry,
            mockClient,
            mockClusterService,
            mockThreadPool,
            mockMigrationDocumentService,
            () -> {}
        );

        coordinator.start();
        assertBusy(() -> assertEquals(CoordinatorPhase.ACTIVE, coordinator.phase()), 5, TimeUnit.SECONDS);

        coordinator.failWithReason("Test failure — no transient settings to restore");
        assertBusy(
            () -> assertTrue(
                "Expected FAILING or FAILED",
                coordinator.phase() == CoordinatorPhase.FAILING || coordinator.phase() == CoordinatorPhase.FAILED
            ),
            5,
            TimeUnit.SECONDS
        );

        coordinator.acceptShardUpdate(0, ShardProgressDocument.builder().phase(ShardPhase.FAILED).error("test").build());

        assertBusy(() -> {
            CoordinatorPhase phase = coordinator.phase();
            assertEquals("Expected FAILED but got: " + phase, CoordinatorPhase.FAILED, phase);
        }, 5, TimeUnit.SECONDS);

        // Alias rollback should still happen
        verify(mockIndicesAdmin, atLeastOnce()).aliases(any(IndicesAliasesRequest.class), any(ActionListener.class));

        // Verify that no updateSettings call targeted "target-idx" (transient restore was skipped).
        // Source-index calls (restoreRebalance, removeWriteBlock) are expected.
        ArgumentCaptor<UpdateSettingsRequest> settingsCaptor = ArgumentCaptor.forClass(UpdateSettingsRequest.class);
        verify(mockIndicesAdmin, atLeastOnce()).updateSettings(settingsCaptor.capture(), any(ActionListener.class));
        for (UpdateSettingsRequest captured : settingsCaptor.getAllValues()) {
            assertFalse(
                "updateSettings should not have targeted target-idx, but did: " + Arrays.toString(captured.indices()),
                Arrays.asList(captured.indices()).contains("target-idx")
            );
        }

        coordinator.close();
    }

    // ---- Entry helpers ----

    /**
     * Verify liveness checker is wired: heartbeats recorded via acceptShardUpdate
     * are forwarded to the checker.
     */
    public void testLivenessCheckerReceivesHeartbeats() throws Exception {
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
        AoscMigrationsClusterState.Entry entry = entryWithShards("migration-liveness", CoordinatorPhase.ACTIVE, shards);

        MigrationCoordinator coordinator = new MigrationCoordinator(
            AoscLogger.create(MigrationCoordinator.class),
            "migration-liveness",
            CoordinatorPhase.ACTIVE,
            entry,
            mockClient,
            mockClusterService,
            mockThreadPool,
            mockMigrationDocumentService,
            () -> {}
        );

        // After construction, acceptShardUpdate should have seeded heartbeats
        // Send another update — should not throw (heartbeat forwarded to checker)
        ShardProgressDocument progress = ShardProgressDocument.builder().phase(ShardPhase.BACKFILLING).build();
        coordinator.acceptShardUpdate(0, progress);

        // Verify shard state was recorded
        assertEquals(ShardPhase.BACKFILLING, coordinator.shardProgressCache().get(0).phase());

        coordinator.close();
    }

    private static AoscMigrationsClusterState.Entry entryWithShards(
        String migrationId,
        CoordinatorPhase phase,
        Map<Integer, AoscMigrationsClusterState.ShardMigrationClusterState> shards
    ) {
        return AoscMigrationsClusterState.Entry.builder()
            .migrationId(migrationId)
            .sourceIndex("source-idx")
            .targetIndex("target-idx")
            .alias("my-alias")
            .transformScript(new InlineTransformScript("ctx._source.version = 2", null))
            .options(AoscTestUtil.defaultMigrationOptions())
            .phase(phase)
            .routingMode(ShardRoutingMode.BULK_API)
            .startTimeMillis(System.currentTimeMillis())
            .shards(shards)
            .failure(null)
            .meta(MigrationMetadata.EMPTY)
            .build();
    }
}
