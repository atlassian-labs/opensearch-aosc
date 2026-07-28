/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */
package com.atlassian.opensearch.aosc.service.coordinator;

import com.atlassian.opensearch.aosc.action.cancel.CancelMigrationResponse;
import com.atlassian.opensearch.aosc.action.cancel.CancelMigrationResult;
import com.atlassian.opensearch.aosc.action.start.StartMigrationRequest;
import com.atlassian.opensearch.aosc.action.start.StartMigrationResponse;
import com.atlassian.opensearch.aosc.action.start.StartMigrationResult;
import com.atlassian.opensearch.aosc.action.status.GetMigrationStatusResponse;
import com.atlassian.opensearch.aosc.action.update.UpdateShardMigrationStatusRequest;
import com.atlassian.opensearch.aosc.action.update.UpdateShardMigrationStatusResponse;
import com.atlassian.opensearch.aosc.model.AoscMigrationsClusterState;
import com.atlassian.opensearch.aosc.model.MigrationDocument;
import com.atlassian.opensearch.aosc.model.MigrationRequest;
import com.atlassian.opensearch.aosc.model.MigrationRequestOptions;
import com.atlassian.opensearch.aosc.model.ShardProgressDocument;
import com.atlassian.opensearch.aosc.model.ShardRoutingMode;
import com.atlassian.opensearch.aosc.model.phase.CoordinatorPhase;
import com.atlassian.opensearch.aosc.model.phase.ShardPhase;
import com.atlassian.opensearch.aosc.utils.AoscLogger;
import com.atlassian.opensearch.aosc.utils.LC;
import com.atlassian.opensearch.aosc.utils.SyntheticRoutingHelper;

import org.opensearch.cluster.ClusterChangedEvent;
import org.opensearch.cluster.ClusterState;
import org.opensearch.cluster.ClusterStateApplier;
import org.opensearch.cluster.ClusterStateUpdateTask;
import org.opensearch.cluster.metadata.IndexMetadata;
import org.opensearch.cluster.routing.IndexRoutingTable;
import org.opensearch.cluster.routing.ShardRouting;
import org.opensearch.cluster.service.ClusterService;
import org.opensearch.common.inject.Inject;
import org.opensearch.threadpool.ThreadPool;
import org.opensearch.transport.client.Client;

import java.io.Closeable;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Coordinator service that runs on the ClusterManager node and orchestrates AOSC migrations.
 *
 * <p>Thin orchestrator — creates/destroys per-migration {@link MigrationCoordinator} instances
 * and routes cluster state events and shard updates to them.</p>
 */
public class AoscCoordinatorService implements ClusterStateApplier, Closeable {

    private final AoscLogger logger;
    private final Client client;
    private final ClusterService clusterService;
    private final ThreadPool threadPool;
    private final MigrationDocumentService migrationDocumentService;

    /** Per-migration coordinators: migrationId → coordinator. */
    private final Map<String, MigrationCoordinator> activeMigrations = new ConcurrentHashMap<>();

    @Inject
    public AoscCoordinatorService(
        AoscLogger logger,
        Client client,
        ClusterService clusterService,
        ThreadPool threadPool,
        MigrationDocumentService migrationDocumentService
    ) {
        this.logger = Objects.requireNonNull(logger, "logger").forClass(AoscCoordinatorService.class).with(LC.ROLE, "coordinator");
        this.client = Objects.requireNonNull(client, "client");
        this.clusterService = Objects.requireNonNull(clusterService, "clusterService");
        this.threadPool = Objects.requireNonNull(threadPool, "threadPool");
        this.migrationDocumentService = Objects.requireNonNull(migrationDocumentService, "migrationDocumentService");

        clusterService.addStateApplier(this);
    }

    @Override
    public void applyClusterState(ClusterChangedEvent event) {
        try {
            boolean isClusterManager = event.localNodeClusterManager();
            boolean wasClusterManager = event.previousState().nodes().isLocalNodeElectedClusterManager();
            boolean justBecameCM = isClusterManager && !wasClusterManager;

            if (!isClusterManager && wasClusterManager) {
                handleLostClusterManagerRole();
                return;
            }

            if (isClusterManager) {
                // Pre-build the .aosc-migrations index on CM election so it is ready
                // before any migration starts. Dispatched to GENERIC because
                // ensureIndexExists() issues a CreateIndexRequest (transport action)
                // which cannot run on the cluster state applier thread.
                if (justBecameCM) {
                    threadPool.executor(ThreadPool.Names.GENERIC)
                        .execute(() -> migrationDocumentService.ensureIndexExists().whenComplete((v, ex) -> {
                            if (ex != null) {
                                logger.warn(
                                    "Failed to pre-create .aosc-migrations index on CM election; will retry on first migration",
                                    ex
                                );
                            }
                        }));
                }

                AoscMigrationsClusterState previousState = event.previousState().custom(AoscMigrationsClusterState.TYPE);
                AoscMigrationsClusterState newState = event.state().custom(AoscMigrationsClusterState.TYPE);
                boolean aoscStateChanged = newState != null && !newState.equals(previousState);

                if (aoscStateChanged || justBecameCM) {
                    handleRegularClusterUpdates(event.state());
                }

                // Validate active migrations on any routing or node change — detects shard
                // relocation, node failure, source index deletion. Separate from AOSC state
                // changes because routing changes don't modify our custom cluster state.
                if (!activeMigrations.isEmpty() && (event.routingTableChanged() || event.nodesChanged())) {
                    validateActiveMigrations(event.state());
                }
            }
        } catch (Exception e) {
            logger.error("Unexpected error in applyClusterState — swallowing to avoid breaking cluster state application", e);
        }
    }

    /**
     * Accept a shard status update from a worker. Fails with {@link IllegalStateException}
     * for unknown migration IDs. Delegates to the per-migration coordinator.
     */
    public CompletableFuture<UpdateShardMigrationStatusResponse> acceptShardUpdate(UpdateShardMigrationStatusRequest request) {
        MigrationCoordinator coordinator = activeMigrations.get(request.body().migrationId());
        if (coordinator == null) {
            logger.warn("Received shard update for unknown migration: {}", request.body().migrationId());
            return CompletableFuture.failedFuture(
                new IllegalStateException("No active migration with id: " + request.body().migrationId())
            );
        }

        return coordinator.acceptShardUpdate(request.body().shardId(), request.body().progress())
            .thenApply(v -> new UpdateShardMigrationStatusResponse());
    }

    /**
     * Start a new migration. Creates an entry in cluster state and a MigrationDocument in Tier 1.
     *
     * @return a future that completes with the migration response once the cluster state update is committed
     */
    public CompletableFuture<StartMigrationResponse> startMigration(StartMigrationRequest request) {
        CompletableFuture<StartMigrationResponse> future = new CompletableFuture<>();
        String migrationId = UUID.randomUUID().toString();
        MigrationRequest migrationRequest = request.body();
        String sourceIndex = migrationRequest.getSourceIndex();
        String targetIndex = migrationRequest.getTargetIndex();

        clusterService.submitStateUpdateTask("aosc-start-migration-" + migrationId, new ClusterStateUpdateTask() {
            @Override
            public ClusterState execute(ClusterState currentState) throws Exception {
                AoscMigrationsClusterState aoscState = currentState.custom(AoscMigrationsClusterState.TYPE);
                if (aoscState != null) {
                    AoscMigrationsClusterState.Entry existing = aoscState.getEntryBySourceIndex(sourceIndex);
                    if (existing != null && !existing.phase().isTerminal()) {
                        throw new IllegalStateException("Active migration already exists for source index: " + sourceIndex);
                    }
                    // Remove stale terminal entry for the same source index so the new entry can take its place
                    if (existing != null && existing.phase().isTerminal()) {
                        aoscState = aoscState.withoutEntry(existing.migrationId());
                    }
                } else {
                    aoscState = new AoscMigrationsClusterState(new HashMap<>());
                }

                IndexMetadata sourceMeta = currentState.metadata().index(sourceIndex);
                IndexMetadata targetMeta = currentState.metadata().index(targetIndex);
                ShardRoutingMode routingMode = SyntheticRoutingHelper.detectRoutingMode(sourceMeta, targetMeta);

                // Populate initial shard entries from the source index
                int numShards = sourceMeta.getNumberOfShards();
                Map<Integer, AoscMigrationsClusterState.ShardMigrationClusterState> shards = new HashMap<>();
                for (int i = 0; i < numShards; i++) {
                    shards.put(
                        i,
                        AoscMigrationsClusterState.ShardMigrationClusterState.builder()
                            .phase(ShardPhase.PENDING)
                            .lastReplayedSeqNo(-1)
                            .backfillCutoffSeqNo(-1)
                            .build()
                    );
                }

                MigrationRequestOptions resolvedOptions = migrationRequest.getOptions();

                AoscMigrationsClusterState.Entry entry = AoscMigrationsClusterState.Entry.builder()
                    .migrationId(migrationId)
                    .sourceIndex(sourceIndex)
                    .targetIndex(targetIndex)
                    .alias(migrationRequest.getAlias())
                    .transformScript(migrationRequest.getTransformScript())
                    .options(resolvedOptions)
                    .phase(CoordinatorPhase.INITIALIZING)
                    .routingMode(routingMode)
                    .startTimeMillis(System.currentTimeMillis())
                    .shards(shards)
                    .build();

                aoscState = aoscState.withEntry(entry);
                return ClusterState.builder(currentState).putCustom(AoscMigrationsClusterState.TYPE, aoscState).build();
            }

            @Override
            public void onFailure(String source, Exception e) {
                future.completeExceptionally(e);
            }

            @Override
            public void clusterStateProcessed(String source, ClusterState oldState, ClusterState newState) {
                // Coordinator creation is handled by applyClusterState → handleRegularClusterUpdates.
                future.complete(new StartMigrationResponse(StartMigrationResult.builder().migrationId(migrationId).accepted(true).build()));
            }
        });

        return future;
    }

    /**
     * Cancel a migration by source index name. Finds the active in-memory coordinator and signals
     * it to cancel. The coordinator's state machine handles the {@code CANCELLING → CANCELLED}
     * transition via cluster state updates as part of its normal flow.
     *
     * <p>Only works on the cluster manager node where {@code activeMigrations} is populated.
     * The transport action ensures routing to the cluster manager.
     *
     * @return a future that completes immediately with the cancel response
     */
    /**
     * Returns the live {@link MigrationDocument} (with authoritative phase) for an active
     * migration matching the given source index, or {@code null} if not found.
     * Used by the List API to overlay live data onto Tier-1 docs.
     */
    /**
     * Returns live {@link MigrationDocument}s for all active migrations.
     * Used by the List API to include migrations not yet in Tier-1.
     */
    public List<MigrationDocument> getAllActiveMigrationDocuments() {
        List<MigrationDocument> result = new ArrayList<>();
        for (MigrationCoordinator coordinator : activeMigrations.values()) {
            result.add(coordinator.buildStatusDocument());
        }
        return result;
    }

    /**
     * Returns the number of coordinators still in the active map (including terminal coordinators
     * whose async {@code onTerminalReached} cleanup has not yet finished).
     */
    public int activeCoordinatorCount() {
        return activeMigrations.size();
    }

    public MigrationDocument getActiveMigrationDocument(String sourceIndex) {
        for (MigrationCoordinator coordinator : activeMigrations.values()) {
            if (coordinator.sourceIndex().equals(sourceIndex)) {
                return coordinator.buildStatusDocument();
            }
        }
        return null;
    }

    public GetMigrationStatusResponse getActiveStatusFromCache(String sourceIndex) {
        for (MigrationCoordinator coordinator : activeMigrations.values()) {
            if (coordinator.sourceIndex().equals(sourceIndex)) {
                return new GetMigrationStatusResponse(coordinator.buildStatusDocument());
            }
        }
        return null;
    }

    public CompletableFuture<CancelMigrationResponse> cancelMigration(String sourceIndex) {
        String targetMigrationId = null;
        for (Map.Entry<String, MigrationCoordinator> entry : activeMigrations.entrySet()) {
            if (entry.getValue().sourceIndex().equals(sourceIndex)) {
                targetMigrationId = entry.getKey();
                break;
            }
        }

        if (targetMigrationId == null) {
            return CompletableFuture.failedFuture(
                new IllegalStateException("No active migration found for source index: [" + sourceIndex + "]")
            );
        }

        MigrationCoordinator coordinator = activeMigrations.get(targetMigrationId);
        if (coordinator == null) {
            return CompletableFuture.failedFuture(
                new IllegalStateException("No active migration found for source index: [" + sourceIndex + "]")
            );
        }

        CoordinatorPhase currentPhase = coordinator.phase();

        // Reject if already in a terminal phase
        if (currentPhase.isTerminal()) {
            return CompletableFuture.failedFuture(
                new IllegalStateException(
                    "Cannot cancel migration for [" + sourceIndex + "] — migration is already in terminal phase: " + currentPhase
                )
            );
        }

        coordinator.cancel();
        return CompletableFuture.completedFuture(
            new CancelMigrationResponse(CancelMigrationResult.builder().accepted(true).phase(coordinator.phase()).build())
        );
    }

    // --- Helper methods ---

    /**
     * Called when this node loses the ClusterManager role. Closes all active coordinators and clears
     * the migration map — the new ClusterManager will rebuild state from cluster state on its next
     * {@link #applyClusterState} call.
     */
    private void handleLostClusterManagerRole() {
        activeMigrations.values().forEach(MigrationCoordinator::close);
        activeMigrations.clear();
    }

    /**
     * Called on every cluster state update while this node is ClusterManager.
     * Removes coordinators for terminal entries, creates coordinators for new/recovered non-terminal entries
     * (via {@code computeIfAbsent}), and resumes all active coordinators with the latest entry.
     */
    private void handleRegularClusterUpdates(ClusterState state) {
        AoscMigrationsClusterState aoscState = state.custom(AoscMigrationsClusterState.TYPE);
        if (aoscState == null) {
            return;
        }

        for (AoscMigrationsClusterState.Entry entry : aoscState.entries().values()) {
            if (entry.phase().isTerminal()) {
                // Don't close the coordinator here — the SM's onTerminalReached callback
                // handles cleanup after the write barrier and terminal handler complete.
                // Eager close here would destroy the batcher before shard write barriers
                // finish, causing "Batcher closed" errors on shard terminal phase updates.
                continue;
            }

            AtomicBoolean isNew = new AtomicBoolean(false);
            MigrationCoordinator coordinator = activeMigrations.computeIfAbsent(entry.migrationId(), migrationId -> {
                isNew.set(true);
                return new MigrationCoordinator(
                    logger,
                    entry.migrationId(),
                    entry.phase(),
                    entry,
                    client,
                    clusterService,
                    threadPool,
                    migrationDocumentService,
                    () -> threadPool.generic().execute(() -> {
                        MigrationCoordinator c = activeMigrations.remove(migrationId);
                        if (c != null) c.close();
                    })
                );
            });

            if (isNew.get()) {
                // Must dispatch to GENERIC — start() initiates transport actions
                // which cannot be called on the cluster state applier thread.
                // Works for both fresh (INITIALIZING) and resumed (any phase) coordinators.
                threadPool.executor(ThreadPool.Names.GENERIC).execute(coordinator::start);
            }
        }
    }

    /**
     * Validates all active migrations against the current cluster state routing table.
     * Called on any routing or node change — independent of AOSC metadata changes.
     * Checks shard routing state directly: no stored node IDs needed.
     */
    private void validateActiveMigrations(ClusterState state) {
        for (Map.Entry<String, MigrationCoordinator> active : activeMigrations.entrySet()) {
            String migrationId = active.getKey();
            MigrationCoordinator coordinator = active.getValue();

            // Skip validation for migrations already shutting down or completing cutover.
            // In COMPLETING, all shards are done — source shard relocation is harmless.
            CoordinatorPhase phase = coordinator.phase();
            if (phase != null
                && (phase.isTerminal()
                    || phase == CoordinatorPhase.FAILING
                    || phase == CoordinatorPhase.CANCELLING
                    || phase == CoordinatorPhase.COMPLETING)) {
                continue;
            }

            String sourceIndex = coordinator.sourceIndex();

            // Index-level check: source index deleted
            if (!state.routingTable().hasIndex(sourceIndex)) {
                failMigration(migrationId, coordinator, "Source index [" + sourceIndex + "] no longer exists");
                continue;
            }

            IndexRoutingTable routingTable = state.routingTable().index(sourceIndex);
            Map<Integer, ShardProgressDocument> shardProgress = coordinator.shardProgressCache();

            // Per-shard checks: inspect routing state directly
            for (int i = 0; i < routingTable.shards().size(); i++) {
                ShardRouting primary = routingTable.shard(i).primaryShard();
                if (primary == null) {
                    failMigration(migrationId, coordinator, "Source shard [" + i + "] has no primary");
                    break;
                }
                // Skip relocation/state checks for shards that already completed migration
                ShardProgressDocument progress = shardProgress.get(i);
                if (progress != null && progress.phase() == ShardPhase.COMPLETED) {
                    continue;
                }
                if (primary.relocating()) {
                    failMigration(
                        migrationId,
                        coordinator,
                        "Source shard [" + i + "] is relocating to node [" + primary.relocatingNodeId() + "]"
                    );
                    break;
                }
                if (!primary.started()) {
                    failMigration(migrationId, coordinator, "Source shard [" + i + "] is " + primary.state() + " (not STARTED)");
                    break;
                }
            }
        }
    }

    private void failMigration(String migrationId, MigrationCoordinator coordinator, String reason) {
        logger.warn("Failing migration [{}]: {}", migrationId, reason);
        coordinator.failWithReason(reason);
    }

    /** Returns the set of migration IDs currently tracked in-memory. */
    public Set<String> getActiveMigrationIds() {
        return Set.copyOf(activeMigrations.keySet());
    }

    public Set<String> removeAndClose(Set<String> migrationIds) {
        Set<String> removed = new HashSet<>();
        for (String migrationId : migrationIds) {
            MigrationCoordinator coordinator = activeMigrations.remove(migrationId);
            if (coordinator != null) {
                removed.add(migrationId);
                coordinator.close();
            }
        }

        return removed;
    }

    @Override
    public void close() throws IOException {
        clusterService.removeApplier(this);
        activeMigrations.values().forEach(MigrationCoordinator::close);
        activeMigrations.clear();
    }
}
