/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */
package com.atlassian.opensearch.aosc.service.worker;

import com.atlassian.opensearch.aosc.AoscSettings;
import com.atlassian.opensearch.aosc.model.AoscMigrationsClusterState;
import com.atlassian.opensearch.aosc.model.MigrationRequestOptions;
import com.atlassian.opensearch.aosc.model.phase.CoordinatorPhase;
import com.atlassian.opensearch.aosc.service.coordinator.AoscCoordinatorService;
import com.atlassian.opensearch.aosc.transform.TransformFactory;
import com.atlassian.opensearch.aosc.utils.AoscLogger;
import com.atlassian.opensearch.aosc.utils.LC;
import com.atlassian.opensearch.aosc.utils.ShardHandle;
import com.atlassian.opensearch.aosc.utils.SyntheticRoutingHelper;

import org.opensearch.client.Client;
import org.opensearch.cluster.ClusterChangedEvent;
import org.opensearch.cluster.ClusterState;
import org.opensearch.cluster.ClusterStateListener;
import org.opensearch.cluster.metadata.IndexMetadata;
import org.opensearch.cluster.routing.ShardRouting;
import org.opensearch.cluster.service.ClusterService;
import org.opensearch.common.Nullable;
import org.opensearch.common.settings.Settings;
import org.opensearch.core.index.shard.ShardId;
import org.opensearch.index.shard.IndexEventListener;
import org.opensearch.index.shard.IndexShard;
import org.opensearch.threadpool.ThreadPool;

import java.io.Closeable;
import java.io.IOException;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

/**
 * Node-local shard lifecycle service that creates, drives, and cleans up
 * per-shard {@link ShardMigrationWorker} instances.
 *
 * <p>Mirrors {@link AoscCoordinatorService} on the data-node side:</p>
 * <ul>
 *   <li>On each cluster state change, creates workers for local shards that
 *       belong to an active migration (via {@code computeIfAbsent})</li>
 *   <li>Signals existing workers via cancel/signalCatchUp</li>
 *   <li>Cancels workers when the coordinator signals cancellation/failure</li>
 *   <li>Removes and closes workers for terminal entries</li>
 * </ul>
 */
public class AoscShardService implements ClusterStateListener, IndexEventListener, Closeable {

    private final AoscLogger logger;

    private final ClusterService clusterService;
    private final Client client;
    private final ThreadPool threadPool;
    private final TransformFactory transformFactory;
    private final ConcurrentMap<String, ShardMigrationWorker> shardWorkers = new ConcurrentHashMap<>();
    private final BackfillPermitManager permitManager;

    /**
     * Registry of local primary shards, populated by {@link #afterIndexShardStarted} and
     * cleared by {@link #beforeIndexShardClosed}. Uses {@link ShardHandle} to cache immutable
     * shard identity and provide guarded access to live shard state.
     */
    // Package-private for testing
    final ConcurrentMap<ShardId, ShardHandle> localShards = new ConcurrentHashMap<>();

    public AoscShardService(
        AoscLogger logger,
        ClusterService clusterService,
        Client client,
        ThreadPool threadPool,
        TransformFactory transformFactory
    ) {
        this.logger = Objects.requireNonNull(logger, "logger").forClass(AoscShardService.class).with(LC.ROLE, "shard_worker");
        this.clusterService = Objects.requireNonNull(clusterService, "clusterService");
        this.client = Objects.requireNonNull(client, "client");
        this.threadPool = Objects.requireNonNull(threadPool, "threadPool");
        this.transformFactory = Objects.requireNonNull(transformFactory, "transformFactory");

        int initialConcurrency = AoscSettings.MAX_CONCURRENT_PER_NODE.get(clusterService.getSettings());
        this.permitManager = new BackfillPermitManager(logger, initialConcurrency);
        clusterService.getClusterSettings()
            .addSettingsUpdateConsumer(AoscSettings.MAX_CONCURRENT_PER_NODE, permitManager::updateMaxPermits);

        clusterService.addListener(this);
    }

    /**
     * Called when a shard becomes active (started) on this node — including during node recovery
     * for shards that existed before the node restarted. Registers the shard in {@link #localShards}
     * so it can be picked up by {@link #handleMigrationEntry} on the next cluster state change.
     */
    @Override
    public void afterIndexShardStarted(IndexShard indexShard) {
        if (indexShard.routingEntry().primary()) {
            ShardHandle shardHandle = new ShardHandle(logger, indexShard, threadPool);
            localShards.put(indexShard.shardId(), shardHandle);
            logger.info("Registered local primary shard [{}]", indexShard.shardId());
            reevaluateMigrationsForShard(shardHandle);
        }
    }

    /**
     * Called just before a shard is closed on this node. Removes the shard from {@link #localShards}
     * and stops any running worker for it.
     */
    @Override
    public void beforeIndexShardClosed(ShardId shardId, IndexShard indexShard, Settings indexSettings) {
        ShardHandle removed = localShards.remove(shardId);
        if (removed != null) {
            logger.info("Deregistered local primary shard [{}]", shardId);
        }
        // Workers are NOT closed here. When the source index is deleted, the coordinator
        // detects it via validateActiveMigrations → failWithReason → FAILING persisted to CS.
        // The next clusterChanged with FAILING/CANCELLING cancels the workers via handleMigrationEntry,
        // which drives them to terminal and notifies the coordinator's gate normally.
    }

    /**
     * Detects replica-to-primary promotions and registers them in {@link #localShards}.
     * Needed because {@link #afterIndexShardStarted} doesn't fire on promotion.
     */
    @Override
    public void shardRoutingChanged(IndexShard indexShard, @Nullable ShardRouting oldRouting, ShardRouting newRouting) {
        if (newRouting.primary() && (oldRouting == null || !oldRouting.primary())) {
            ShardId shardId = indexShard.shardId();
            ShardHandle shardHandle = new ShardHandle(logger, indexShard, threadPool);
            localShards.put(shardId, shardHandle);
            logger.info("Registered promoted primary shard [{}]", shardId);
            reevaluateMigrationsForShard(shardHandle);
        }
    }

    @Override
    public void clusterChanged(ClusterChangedEvent event) {

        AoscMigrationsClusterState aoscState = event.state().custom(AoscMigrationsClusterState.TYPE);
        AoscMigrationsClusterState previousAoscState = event.previousState().custom(AoscMigrationsClusterState.TYPE);

        // null-safe equals
        if (Objects.equals(aoscState, previousAoscState)) {
            return;
        }

        if (aoscState != null) {
            for (Map.Entry<String, AoscMigrationsClusterState.Entry> migEntry : aoscState.entries().entrySet()) {
                AoscMigrationsClusterState.Entry previousEntry = previousAoscState == null
                    ? null
                    : previousAoscState.getEntry(migEntry.getKey());
                try {
                    handleMigrationEntry(event.state(), migEntry.getKey(), migEntry.getValue(), previousEntry);
                } catch (Exception e) {
                    logger.error("Error handling migration entry [{}]", migEntry.getKey(), e);
                }
            }
        }

        // Sweep: close workers whose migration is no longer in cluster state (e.g. after clear-state)
        sweepOrphanedWorkers(aoscState != null ? aoscState.entries().keySet() : Set.of());
    }

    /**
     * Handles a single migration entry from cluster state. Creates new workers for shards that are
     * now local primaries, resumes existing workers, and skips entries that haven't changed.
     * Terminal entries are ignored — workers for those shards have already been cleaned up.
     *
     * <p>Worker creation is async (document fetch first), but {@code computeIfAbsent} ensures at most
     * one worker per shard even under concurrent cluster state events.</p>
     */
    private void handleMigrationEntry(
        ClusterState state,
        String migrationId,
        AoscMigrationsClusterState.Entry entry,
        AoscMigrationsClusterState.Entry previousEntry
    ) {
        if (entry.phase().isTerminal()) {
            // Clean up shard workers for terminal migrations
            String prefix = migrationId + "::";
            shardWorkers.entrySet().removeIf(e -> {
                if (e.getKey().startsWith(prefix)) {
                    e.getValue().close();
                    return true;
                }
                return false;
            });
            // Safety net: release any permits still held by this migration.
            // Individual worker close() calls releasePermit() best-effort, but
            // this bulk release catches any that leaked (e.g. race between
            // acquire() completion and close()).
            permitManager.releaseAllForMigration(migrationId);
            return;
        }

        // Cancel/fail: signal existing workers even if their IndexShards are gone
        // (e.g. source index deleted). Workers are tracked by migrationId::shardId.
        CoordinatorPhase coordPhase = entry.phase();
        if (coordPhase == CoordinatorPhase.CANCELLING || coordPhase == CoordinatorPhase.FAILING) {
            String prefix = migrationId + "::";
            shardWorkers.forEach((key, worker) -> {
                if (key.startsWith(prefix)) {
                    logger.info("Cancelling shard worker {} due to coordinator phase {}", key, coordPhase);
                    worker.cancel();
                }
            });
        }

        List<ShardHandle> primaryShards = localShards.values()
            .stream()
            .filter(shard -> shard.indexName().equals(entry.sourceIndex()))
            .collect(Collectors.toList());
        primaryShards.removeIf(shard -> !shouldUpdateShard(shard, entry, previousEntry));

        for (ShardHandle primaryShard : primaryShards) {
            String key = migrationId + "::" + primaryShard.shardNum();

            AtomicBoolean created = new AtomicBoolean(false);
            String workerKey = key;
            ShardMigrationWorker shardWorker = shardWorkers.computeIfAbsent(key, (k) -> {
                created.set(true);
                return createShardWorker(state, entry, primaryShard, workerKey);
            });

            if (created.get()) {
                logger.info("Created shard worker [{}] for migration [{}]", workerKey, migrationId);
                shardWorker.start();
            } else {
                // Signal worker based on coordinator phase
                if (coordPhase == CoordinatorPhase.CATCHING_UP
                    || coordPhase == CoordinatorPhase.COMPLETING
                    || coordPhase == CoordinatorPhase.COMPLETED) {
                    shardWorker.signalCatchUp();
                }
            }
        }
    }

    /**
     * Constructs a new {@link ShardMigrationWorker} for the given shard. Pure computation — no I/O,
     * no registration. The caller is responsible for registering via {@code computeIfAbsent} and
     * calling {@link ShardMigrationWorker#start()}.
     */
    private ShardMigrationWorker createShardWorker(
        ClusterState state,
        AoscMigrationsClusterState.Entry entry,
        ShardHandle primaryShard,
        String workerKey
    ) {
        IndexMetadata targetMeta = state.metadata().index(entry.targetIndex());
        IndexMetadata sourceMeta = state.metadata().index(entry.sourceIndex());

        MigrationRequestOptions options = entry.options();

        return new ShardMigrationWorker(
            logger,
            entry.migrationId(),
            primaryShard,
            entry.targetIndex(),
            sourceMeta.getNumberOfShards(),
            entry.routingMode(),
            SyntheticRoutingHelper.computeSyntheticRoutings(targetMeta),
            transformFactory.create(entry.transformScript(), sourceMeta, targetMeta),
            options,
            client,
            threadPool,
            permitManager,
            // No self-removal: the shard worker stays in shardWorkers until the coordinator
            // entry reaches terminal phase, at which point handleMigrationEntry removes it.
            // Early removal caused a race where clusterChanged would recreate a new worker
            // (from PENDING) before the coordinator reached terminal, resetting shard progress.
            null,
            clusterService.getClusterSettings()
        );
    }

    /**
     * Returns {@code true} if the shard worker should be created or resumed for this cluster state event.
     * Returns {@code false} if neither the coordinator phase nor the shard's own state has changed,
     * avoiding unnecessary work on unrelated cluster state updates.
     */
    private boolean shouldUpdateShard(
        ShardHandle shard,
        AoscMigrationsClusterState.Entry entry,
        AoscMigrationsClusterState.Entry previousEntry
    ) {
        int shardId = shard.shardNum();
        AoscMigrationsClusterState.ShardMigrationClusterState previousShardState = previousEntry == null
            ? null
            : previousEntry.shards().get(shardId);
        AoscMigrationsClusterState.ShardMigrationClusterState currentShardState = entry.shards().get(shardId);

        String workerKey = entry.migrationId() + "::" + shardId;
        if (!shardWorkers.containsKey(workerKey)) {
            return true;
        }
        return previousEntry == null
            || previousEntry.phase() != entry.phase()
            || previousShardState == null
            || previousShardState != currentShardState;
    }

    /** Re-evaluates active migrations for a newly registered primary shard. */
    private void reevaluateMigrationsForShard(ShardHandle shardHandle) {
        threadPool.executor(ThreadPool.Names.GENERIC).execute(() -> {
            ClusterState state = clusterService.state();
            AoscMigrationsClusterState aoscState = state.custom(AoscMigrationsClusterState.TYPE);
            if (aoscState != null) {
                for (AoscMigrationsClusterState.Entry entry : aoscState.entries().values()) {
                    if (entry.sourceIndex().equals(shardHandle.indexName()) && !entry.phase().isTerminal()) {
                        handleMigrationEntry(state, entry.migrationId(), entry, null);
                    }
                }
            }
        });
    }

    /** Closes and removes shard workers for migrations no longer present in cluster state. */
    private void sweepOrphanedWorkers(Set<String> activeMigrationIds) {
        Set<String> orphanedMigrations = new HashSet<>();
        shardWorkers.entrySet().removeIf(entry -> {
            if (!activeMigrationIds.contains(entry.getValue().migrationId())) {
                logger.warn("Closing orphaned shard worker [{}] — migration no longer in cluster state", entry.getKey());
                String migrationId = entry.getValue().migrationId();
                try {
                    entry.getValue().close();
                } catch (Exception e) {
                    logger.error("Error closing orphaned shard worker [{}]", entry.getKey(), e);
                }
                orphanedMigrations.add(migrationId);
                return true;
            }
            return false;
        });
        orphanedMigrations.forEach(permitManager::releaseAllForMigration);
    }

    @Override
    public void close() throws IOException {
        clusterService.removeListener(this);
        permitManager.cancelWaiters();
        shardWorkers.values().forEach(ShardMigrationWorker::close);
        shardWorkers.clear();
    }
}
