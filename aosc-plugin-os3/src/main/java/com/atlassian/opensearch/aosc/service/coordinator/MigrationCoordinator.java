/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */
package com.atlassian.opensearch.aosc.service.coordinator;

import com.atlassian.opensearch.aosc.AoscSettings;
import com.atlassian.opensearch.aosc.model.AoscMigrationsClusterState;
import com.atlassian.opensearch.aosc.model.CutoverContext;
import com.atlassian.opensearch.aosc.model.MigrationDocument;
import com.atlassian.opensearch.aosc.model.MigrationMetadata;
import com.atlassian.opensearch.aosc.model.MigrationRequestOptions;
import com.atlassian.opensearch.aosc.model.ShardProgressDocument;
import com.atlassian.opensearch.aosc.model.ShardRoutingMode;
import com.atlassian.opensearch.aosc.model.phase.CoordinatorPhase;
import com.atlassian.opensearch.aosc.model.phase.ShardPhase;
import com.atlassian.opensearch.aosc.model.transform.TransformScript;
import com.atlassian.opensearch.aosc.statemachine.AwaitableStateMachine;
import com.atlassian.opensearch.aosc.utils.AoscLogger;
import com.atlassian.opensearch.aosc.utils.IndexOperationUtils;
import com.atlassian.opensearch.aosc.utils.LC;
import com.atlassian.opensearch.aosc.utils.MigrationAuditLogger;

import org.opensearch.cluster.metadata.IndexMetadata;
import org.opensearch.cluster.service.ClusterService;
import org.opensearch.common.settings.ClusterSettings;
import org.opensearch.common.unit.TimeValue;
import org.opensearch.core.index.Index;
import org.opensearch.core.index.shard.ShardId;
import org.opensearch.index.query.QueryBuilder;
import org.opensearch.threadpool.ThreadPool;
import org.opensearch.transport.client.Client;

import java.io.Closeable;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import static com.atlassian.opensearch.aosc.utils.AoscLogger.kv;

/**
 * Manages the lifecycle of a single AOSC migration on the ClusterManager node.
 *
 * <p>Uses {@link AwaitableStateMachine} for phase transitions, {@link ShardGate} for
 * shard convergence tracking, and {@link ClusterStateShardBatcher} for batched shard
 * status writes to cluster state.</p>
 */
class MigrationCoordinator implements Closeable {

    private final String migrationId;
    private final AoscLogger logger;
    private final MigrationDocumentService migrationDocumentService;
    private final IndexOperationUtils indexOperationUtils;
    private final CutoverService cutoverService;
    private final ClusterService clusterService;
    private final ClusterStateUpdateHelper clusterStateUpdateHelper;
    private final ThreadPool threadPool;

    private final ExecutorService smExecutor;
    private final AwaitableStateMachine<CoordinatorPhase> sm;
    private final ClusterStateShardBatcher batcher;
    private final Runnable onTerminalReached;
    private final ShardLivenessChecker livenessChecker;

    // Immutable migration config — set once at construction, never changes
    private final String sourceIndex;
    private final String targetIndex;
    private final String alias;
    private final MigrationRequestOptions options;
    private final TransformScript transformScript;
    private final ShardRoutingMode routingMode;
    private final long startTimeMillis;
    private final Set<Integer> shardOrdinals;

    // Volatile: set by handlers on smExecutor, read by transport threads via buildStatusDocument().
    // cutoverContext is set in the COMPLETING SM path only — no compound atomicity needed.
    private volatile ShardGate currentGate;
    private volatile CutoverContext cutoverContext;
    // First-writer-wins failure reason. Set by failWithReason() (transport threads) and
    // waitForShards() (SM executor). AtomicReference ensures the first failure reason is
    // preserved even under concurrent calls.
    private final AtomicReference<String> failureReason = new AtomicReference<>();
    // Migration-level resource metadata — tracks write blocks, rebalance, alias swap
    private final AtomicReference<MigrationMetadata> currentMeta = new AtomicReference<>(MigrationMetadata.EMPTY);
    // In-memory shard phases — updated eagerly by acceptShardUpdate (always fresh)
    private final ConcurrentHashMap<Integer, ShardProgressDocument> latestShardSnapshots = new ConcurrentHashMap<>();

    /**
     * @param migrationId     migration identifier (cluster state key)
     * @param resumeFromPhase state machine phase to resume from, or {@code null} for new migration
     * @param entry           initial cluster state entry for this migration
     * @param client          OpenSearch client for index admin operations
     * @param clusterService  used to build {@link ClusterStateUpdateHelper}
     * @param threadPool          schedules batcher flushes
     * @param migrationDocumentService     persists the Tier-1 migration document
     * @param onTerminalReached   called after a terminal phase is durably persisted
     */
    MigrationCoordinator(
        AoscLogger parentLogger,
        String migrationId,
        CoordinatorPhase resumeFromPhase,
        AoscMigrationsClusterState.Entry entry,
        Client client,
        ClusterService clusterService,
        ThreadPool threadPool,
        MigrationDocumentService migrationDocumentService,
        Runnable onTerminalReached
    ) {
        this.migrationId = Objects.requireNonNull(migrationId, "migrationId");
        this.migrationDocumentService = Objects.requireNonNull(migrationDocumentService, "migrationDocumentService");
        this.onTerminalReached = Objects.requireNonNull(onTerminalReached, "onTerminalReached");
        Objects.requireNonNull(entry, "entry");
        this.sourceIndex = entry.sourceIndex();
        this.targetIndex = entry.targetIndex();
        this.alias = entry.alias();
        this.options = entry.options();
        this.transformScript = entry.transformScript();
        this.routingMode = entry.routingMode();
        this.startTimeMillis = entry.startTimeMillis();
        this.shardOrdinals = Set.copyOf(entry.shards().keySet());
        this.logger = parentLogger.with(LC.MIGRATION_ID, migrationId)
            .with(LC.SOURCE_INDEX, entry.sourceIndex())
            .with(LC.TARGET_INDEX, entry.targetIndex());
        this.indexOperationUtils = new IndexOperationUtils(logger, Objects.requireNonNull(client, "client"));
        this.cutoverService = new CutoverService(logger.with(LC.PHASE, "cutover"), client, this.indexOperationUtils, migrationId);
        this.clusterService = Objects.requireNonNull(clusterService, "clusterService");
        this.clusterStateUpdateHelper = new ClusterStateUpdateHelper(clusterService, logger);
        this.threadPool = Objects.requireNonNull(threadPool, "threadPool");
        this.smExecutor = Executors.newSingleThreadExecutor(r -> new Thread(r, "aosc-coordinator-" + migrationId));

        // Create batcher for batched shard status writes
        this.batcher = new ClusterStateShardBatcher(logger, migrationId, clusterStateUpdateHelper, threadPool);

        // Create liveness checker — starts periodic check immediately, but no-ops
        // until a gate is set (gate supplier returns null before ACTIVE).
        // Liveness timing is a cluster-wide tuning knob, read from cluster settings.
        ClusterSettings clusterSettings = clusterService.getClusterSettings();
        TimeValue checkInterval = clusterSettings.get(AoscSettings.LIVENESS_CHECK_INTERVAL);
        TimeValue livenessTimeout = clusterSettings.get(AoscSettings.LIVENESS_TIMEOUT);
        this.livenessChecker = new ShardLivenessChecker(logger, threadPool, () -> currentGate, checkInterval, livenessTimeout);

        this.sm = buildStateMachine();

        // Restore migration-level resource metadata from cluster state (survives failover)
        currentMeta.set(entry.meta());

        // Seed all shard-related state (latestShardSnapshots, heartbeats, gate, batcher) via
        // acceptShardUpdate. The batcher writes are no-ops since they're the same phases already
        // in cluster state, but this consolidates all shard bookkeeping in one path.
        entry.shards()
            .forEach(
                (shardOrd, shardState) -> acceptShardUpdate(
                    shardOrd,
                    ShardProgressDocument.builder().phase(shardState.phase()).error(shardState.failure()).meta(shardState.meta()).build()
                )
            );

        if (resumeFromPhase != null && resumeFromPhase != CoordinatorPhase.INITIALIZING) {
            sm.setContext("shardStates", entry.shards());
            sm.resumeAtState(resumeFromPhase);
        }
    }

    // ---- Public API ----

    /** Start the migration (called once from AoscCoordinatorService on GENERIC thread). */
    void start() {
        sm.start();
    }

    /** Cancel the migration. Thread-safe — can be called from any thread. */
    void cancel() {
        ShardGate gate = currentGate;
        if (gate != null) gate.cancel();
        sm.transitionTo(CoordinatorPhase.CANCELLING);
    }

    /** Fail the migration with a reason. Thread-safe. */
    void failWithReason(String reason) {
        ShardGate gate = currentGate;
        if (gate != null) gate.cancel();
        // First writer wins — if another thread already set a reason, we keep the original.
        failureReason.compareAndSet(null, reason);
        sm.transitionTo(CoordinatorPhase.FAILING);
    }

    /** Called by transport action when a shard reports its phase. Thread-safe. */
    CompletableFuture<Void> acceptShardUpdate(int shardId, ShardProgressDocument progress) {
        if (!shardOrdinals.contains(shardId)) {
            return CompletableFuture.failedFuture(
                new IllegalArgumentException("Unknown shard " + shardId + " for migration " + migrationId)
            );
        }
        // Record heartbeat for liveness tracking
        livenessChecker.heartbeatReceived(shardIdFor(shardId));
        // Update in-memory cache with full progress
        latestShardSnapshots.put(shardId, progress);
        // Notify gate immediately
        ShardGate gate = currentGate;
        if (gate != null) {
            gate.shardReported(shardIdFor(shardId), progress.phase(), progress.error());
        }
        // Queue the durable write to cluster state (batched)
        // Tier-1 writes happen once at migration terminal, not per-shard terminal
        return batcher.queueUpdate(shardId, progress);
    }

    /** Current coordinator phase. Thread-safe (volatile read). */
    CoordinatorPhase phase() {
        return sm.currentState();
    }

    /** Source index name. */
    String sourceIndex() {
        return sourceIndex;
    }

    /** Build a snapshot MigrationDocument reflecting the coordinator's current state. */
    MigrationDocument buildStatusDocument() {
        MigrationDocument.MigrationDocumentBuilder builder = MigrationDocument.builder()
            .migrationId(migrationId)
            .sourceIndex(sourceIndex)
            .targetIndex(targetIndex)
            .transformScript(transformScript)
            .alias(alias)
            .phase(sm.currentState())
            .options(options)
            .shardRoutingMode(routingMode)
            .startTimeMillis(startTimeMillis)
            .lastUpdatedMillis(System.currentTimeMillis())
            .transitionHistory(sm.history())
            .meta(currentMeta.get());
        if (cutoverContext != null) {
            builder.cutoverContext(cutoverContext);
        }
        String error = failureReason.get();
        if (error != null) {
            builder.errorMessage(error);
        }
        builder.shards(new HashMap<>(latestShardSnapshots));
        return builder.build();
    }

    @Override
    public void close() {
        livenessChecker.close();
        batcher.close();
        // Fire SM close and shut down executor. SM close cancels in-flight handlers.
        // We don't block here — blocking risks deadlock if the SM is waiting for a transport
        // response from a node that's already shutting down.
        sm.closeAsync().whenComplete((v, e) -> smExecutor.shutdown());
    }

    // ---- Handler implementations ----

    private CompletableFuture<CoordinatorPhase> onInitializing(AwaitableStateMachine.Context<CoordinatorPhase> ctx) {
        logger.info("Initializing migration: {} → {}", sourceIndex, targetIndex);

        Map<String, String> transientSettings = options.getTransientTargetSettings();

        // applySettings is lazy (inside thenCompose) to enforce: doc persisted → originals captured → settings applied.
        // applySettings failures are fatal; disableRebalance failures are non-fatal (see inline exceptionally below).
        return createMigrationDocument().thenCompose(doc -> {
            if (transientSettings == null || transientSettings.isEmpty()) {
                return CompletableFuture.completedFuture(null);
            }
            // Capture originals now — after migration doc is persisted, before overwriting settings
            IndexMetadata targetMeta = clusterService.state().metadata().index(targetIndex);
            Map<String, String> originals = IndexOperationUtils.captureSettings(targetMeta, transientSettings.keySet());
            currentMeta.updateAndGet(m -> m.toBuilder().putOriginalTargetSettings(originals).build());
            return indexOperationUtils.applySettings(targetIndex, transientSettings);
        }).thenCompose(v -> indexOperationUtils.disableRebalance(sourceIndex).exceptionally(e -> {
            logger.warn("Failed to disable rebalance — migration may fail on shard relocation", e);
            return null;
        })).thenApply(v -> {
            currentMeta.updateAndGet(m -> m.toBuilder().put(MigrationMetadata.REBALANCE_DISABLED, true).build());
            logger.info("Transitioning to ACTIVE");
            return CoordinatorPhase.ACTIVE;
        });
    }

    private CompletableFuture<CoordinatorPhase> onActive(AwaitableStateMachine.Context<CoordinatorPhase> ctx) {
        logger.info("Migration active — waiting for all shards to converge");
        return waitForShards(ctx, ShardPhase.CONVERGED, CoordinatorPhase.PREPARING_TARGET);
    }

    private CompletableFuture<CoordinatorPhase> onPreparingTarget(AwaitableStateMachine.Context<CoordinatorPhase> ctx) {
        Map<String, String> transientSettings = options.getTransientTargetSettings();
        if (transientSettings == null || transientSettings.isEmpty()) {
            logger.info("No transient target settings, proceeding to CUTTING_OVER");
            return CompletableFuture.completedFuture(CoordinatorPhase.CUTTING_OVER);
        }

        Map<String, String> originalSettings = currentMeta.get().originalTargetSettings();
        if (originalSettings.isEmpty()) {
            logger.warn("No original target settings captured, proceeding to CUTTING_OVER");
            return CompletableFuture.completedFuture(CoordinatorPhase.CUTTING_OVER);
        }

        TimeValue timeout = TimeValue.timeValueSeconds(options.getTargetReadyTimeoutSeconds());

        logger.info("Restoring original target settings and waiting for GREEN (timeout: {})", timeout);
        return indexOperationUtils.applySettings(targetIndex, originalSettings)
            .thenCompose(v -> indexOperationUtils.waitForGreen(targetIndex, timeout))
            .thenApply(v -> {
                logger.info("Target is GREEN, proceeding to CUTTING_OVER");
                return CoordinatorPhase.CUTTING_OVER;
            });
    }

    private CompletableFuture<CoordinatorPhase> onCuttingOver(AwaitableStateMachine.Context<CoordinatorPhase> ctx) {
        logger.info("Applying write block to source index");

        return indexOperationUtils.applyWriteBlock(sourceIndex).thenApply(v -> {
            currentMeta.updateAndGet(m -> m.toBuilder().put(MigrationMetadata.WRITE_BLOCK_APPLIED, true).build());
            return v;
        }).thenCompose(v -> indexOperationUtils.flushIndex(sourceIndex)).thenApply(v -> {
            logger.info("Source write-blocked and flushed, transitioning to CATCHING_UP");
            return CoordinatorPhase.CATCHING_UP;
        });
    }

    private CompletableFuture<CoordinatorPhase> onCatchingUp(AwaitableStateMachine.Context<CoordinatorPhase> ctx) {
        logger.info("Waiting for all shards to complete catch-up");
        return waitForShards(ctx, ShardPhase.COMPLETED, CoordinatorPhase.COMPLETING);
    }

    private CompletableFuture<CoordinatorPhase> onCompleting(AwaitableStateMachine.Context<CoordinatorPhase> ctx) {
        logger.info("Starting cutover sequence");

        int tolerance = options.getDocCountTolerance() != null ? options.getDocCountTolerance() : 0;
        boolean removeWriteBlockOnSuccess = options.shouldRemoveSourceWriteBlockOnSuccess();
        QueryBuilder validationQuery = options.getValidationQueryBuilder();

        // Cutover (alias swap) is the point of no return. After it succeeds, post-cutover
        // cleanup (write-block removal, rebalance restore) is best-effort — failures are logged
        // but must not trigger FAILING, which would roll back the alias and cause data loss.
        return cutoverService.executeCutover(sourceIndex, targetIndex, alias, tolerance, validationQuery).thenApply(cutoverCtx -> {
            currentMeta.updateAndGet(m -> m.toBuilder().put(MigrationMetadata.ALIAS_SWAPPED, true).build());
            logger.info(
                "Cutover complete",
                kv(LC.EVENT, "cutover_complete"),
                kv(LC.SOURCE_COUNT, cutoverCtx.sourceDocCount()),
                kv(LC.TARGET_COUNT, cutoverCtx.targetDocCount())
            );
            this.cutoverContext = cutoverCtx;
            return null;
        }).thenCompose(v -> {
            if (!removeWriteBlockOnSuccess) {
                logger.info("Leaving source index '{}' write-blocked (remove_source_write_block_on_success=false)", sourceIndex);
                return CompletableFuture.<Void>completedFuture(null);
            }
            return bestEffort(
                () -> retrying(() -> indexOperationUtils.removeWriteBlock(sourceIndex), "remove write block on success").thenApply(x -> {
                    currentMeta.updateAndGet(m -> m.toBuilder().put(MigrationMetadata.WRITE_BLOCK_APPLIED, false).build());
                    return null;
                }),
                "remove source write block"
            );
        }).thenCompose(v -> bestEffort(() -> indexOperationUtils.restoreRebalance(sourceIndex).thenApply(x -> {
            currentMeta.updateAndGet(m -> m.toBuilder().put(MigrationMetadata.REBALANCE_DISABLED, false).build());
            return null;
        }), "restore rebalance")).thenApply(v -> CoordinatorPhase.COMPLETED);
    }

    private CompletableFuture<CoordinatorPhase> onCancelling(AwaitableStateMachine.Context<CoordinatorPhase> ctx) {
        logger.warn("Cancelling migration — rolling back then waiting for shards");
        return rollbackState().thenCompose(v -> waitForShardsTerminal()).thenApply(v -> CoordinatorPhase.CANCELLED);
    }

    private CompletableFuture<CoordinatorPhase> onFailing(AwaitableStateMachine.Context<CoordinatorPhase> ctx) {
        failureReason.compareAndSet(null, "unknown");
        String reason = failureReason.get();
        logger.error("Migration failing: {} — rolling back then waiting for shards", reason);
        return rollbackState().thenCompose(v -> waitForShardsTerminal()).thenApply(v -> CoordinatorPhase.FAILED);
    }

    private CompletableFuture<Void> waitForShardsTerminal() {
        Set<ShardId> shards = shardOrdinals.stream().map(this::shardIdFor).collect(Collectors.toSet());
        ShardGate gate = new ShardGate(logger, shards, ShardGate.anyTerminal(), false);
        currentGate = gate;
        preFillGate(gate);
        return gate.awaitable().thenAccept(result -> logger.info("All shards reached terminal for shutdown"));
    }

    // ---- State machine construction ----

    // Use CoordinatorPhase.TERMINALS — defined on the enum itself

    private AwaitableStateMachine<CoordinatorPhase> buildStateMachine() {
        var builder = AwaitableStateMachine.builder("coordinator-" + migrationId, smExecutor, CoordinatorPhase.INITIALIZING)
            // Linear pipeline
            .permit(CoordinatorPhase.INITIALIZING, CoordinatorPhase.ACTIVE)
            .permit(CoordinatorPhase.ACTIVE, CoordinatorPhase.PREPARING_TARGET)
            .permit(CoordinatorPhase.PREPARING_TARGET, CoordinatorPhase.CUTTING_OVER)
            .permit(CoordinatorPhase.CUTTING_OVER, CoordinatorPhase.CATCHING_UP)
            .permit(CoordinatorPhase.CATCHING_UP, CoordinatorPhase.COMPLETING)
            .permit(CoordinatorPhase.COMPLETING, CoordinatorPhase.COMPLETED)
            // Cancel/fail from any non-terminal; fail overrides cancel
            .permitFromAnyExcept(CoordinatorPhase.CANCELLING, CoordinatorPhase.TERMINALS)
            .permitFromAnyExcept(CoordinatorPhase.FAILING, CoordinatorPhase.TERMINALS)
            .permit(CoordinatorPhase.CANCELLING, CoordinatorPhase.CANCELLED)
            .permit(CoordinatorPhase.FAILING, CoordinatorPhase.FAILED);
        CoordinatorPhase.TERMINALS.forEach(builder::terminal);
        return builder
            // Handlers
            .handler(CoordinatorPhase.INITIALIZING, this::onInitializing)
            .handler(CoordinatorPhase.ACTIVE, this::onActive)
            .handler(CoordinatorPhase.PREPARING_TARGET, this::onPreparingTarget)
            .handler(CoordinatorPhase.CUTTING_OVER, this::onCuttingOver)
            .handler(CoordinatorPhase.CATCHING_UP, this::onCatchingUp)
            .handler(CoordinatorPhase.COMPLETING, this::onCompleting)
            .handler(CoordinatorPhase.CANCELLING, this::onCancelling)
            .handler(CoordinatorPhase.FAILING, this::onFailing)
            // Write barrier: persist phase to cluster state only. Tier-1 parent doc is updated
            // once at terminal (alongside shard progress bulk write) — not on every transition.
            .writeBarrier(
                (from, to) -> clusterStateUpdateHelper.submitPhaseAndMetaUpdate(migrationId, to, currentMeta.get())
                    .thenCompose(updatedEntry -> {
                        MigrationAuditLogger.recordCoordinatorPhaseTransition(migrationId, from.name(), to.name(), null);
                        return CompletableFuture.completedFuture(null);
                    })
            )
            .onTerminalReached(terminalState -> {
                MigrationAuditLogger.recordMigrationTerminal(migrationId, terminalState.name(), null);
                MigrationDocument finalDoc = buildStatusDocument();
                Map<Integer, ShardProgressDocument> shardSnapshot = new HashMap<>(latestShardSnapshots);
                return migrationDocumentService.persistFinalState(finalDoc, shardSnapshot).exceptionally(e -> {
                    logger.error("Failed to persist final state to Tier-1", e);
                    return null;
                }).whenComplete((v, e) -> {
                    try {
                        onTerminalReached.run();
                    } catch (Exception ex) {
                        logger.error("onTerminalReached callback failed", ex);
                    }
                });
            })
            .onFailure(ctx -> {
                logger.error("Unhandled error in phase {}: {}", ctx.failedInState(), ctx.message(), ctx.cause());
                failureReason.compareAndSet(null, ctx.cause() != null ? ctx.cause().getMessage() : ctx.message());
                ctx.sm().transitionTo(CoordinatorPhase.FAILING);
            })
            .build();
    }

    // ---- Helper methods ----

    /** Wait for all shards to reach targetPhase, then advance to nextPhase. */
    private CompletableFuture<CoordinatorPhase> waitForShards(
        AwaitableStateMachine.Context<CoordinatorPhase> ctx,
        ShardPhase targetPhase,
        CoordinatorPhase nextPhase
    ) {
        Set<ShardId> shards = shardOrdinals.stream().map(this::shardIdFor).collect(Collectors.toSet());
        ShardGate gate = new ShardGate(logger, shards, targetPhase);
        currentGate = gate;
        preFillGate(gate);
        return gate.awaitable().thenApply(result -> {
            switch (result.outcome()) {
                case ALL_REACHED_TARGET:
                    return nextPhase;
                case SHARD_FAILED:
                    String shardError = "shard " + result.failedShard() + " failed: " + result.failureReason();
                    logger.error(shardError);
                    failureReason.compareAndSet(null, shardError);
                    return CoordinatorPhase.FAILING;
                case CANCELLED:
                    return CoordinatorPhase.CANCELLING;
                default:
                    throw new IllegalStateException("Unknown gate outcome: " + result.outcome());
            }
        });
    }

    /** Absorbs errors from a post-cutover cleanup operation. */
    private CompletableFuture<Void> bestEffort(Supplier<CompletableFuture<Void>> action, String label) {
        return action.get().exceptionally(e -> {
            logger.warn("Post-cutover cleanup [{}] failed (non-fatal): {}", label, e.getMessage());
            return null;
        });
    }

    private CompletableFuture<Void> rollbackState() {
        ShardGate gate = currentGate;
        if (gate != null) gate.cancel();

        // After a successful alias swap, rolling back would cause data loss — writes
        // are already landing on the target. Skip the alias rollback in this case.
        MigrationMetadata meta = currentMeta.get();
        CompletableFuture<Void> aliasRollback;
        if (meta != null && meta.getBoolean(MigrationMetadata.ALIAS_SWAPPED)) {
            logger.error("Alias already swapped to target — skipping alias rollback to prevent data loss");
            aliasRollback = CompletableFuture.completedFuture(null);
        } else {
            aliasRollback = retrying(() -> indexOperationUtils.swapAlias(targetIndex, sourceIndex, alias), "alias rollback").thenApply(
                v -> {
                    currentMeta.updateAndGet(m -> m.toBuilder().put(MigrationMetadata.ALIAS_SWAPPED, false).build());
                    return null;
                }
            );
        }

        return aliasRollback.thenCompose(v -> retrying(() -> indexOperationUtils.restoreRebalance(sourceIndex), "restore rebalance"))
            .thenApply(v -> {
                currentMeta.updateAndGet(m -> m.toBuilder().put(MigrationMetadata.REBALANCE_DISABLED, false).build());
                return null;
            })
            .thenCompose(v -> retrying(() -> indexOperationUtils.removeWriteBlock(sourceIndex), "remove write block"))
            .thenApply(v -> {
                currentMeta.updateAndGet(m -> m.toBuilder().put(MigrationMetadata.WRITE_BLOCK_APPLIED, false).build());
                return null;
            })
            .thenCompose(v -> restoreTransientTargetSettings());
    }

    /**
     * Best-effort restore of transient target index settings — same logic as onPreparingTarget.
     * Failures are logged and swallowed since the target index may already have been deleted.
     */
    private CompletableFuture<Void> restoreTransientTargetSettings() {
        Map<String, String> transientSettings = options.getTransientTargetSettings();
        if (transientSettings == null || transientSettings.isEmpty()) {
            return CompletableFuture.completedFuture(null);
        }
        Map<String, String> originalSettings = currentMeta.get().originalTargetSettings();
        if (originalSettings.isEmpty()) {
            logger.debug("No original target settings captured, skipping restore on rollback");
            return CompletableFuture.completedFuture(null);
        }
        logger.info("Restoring original target settings on rollback");
        return indexOperationUtils.applySettings(targetIndex, originalSettings).exceptionally(e -> {
            logger.warn("Failed to restore transient target settings on {} — target may have been deleted", targetIndex, e);
            return null;
        });
    }

    private static final int ROLLBACK_MAX_RETRIES = 3;

    private CompletableFuture<Void> retrying(Supplier<CompletableFuture<Void>> action, String label) {
        return retryAttempt(action, label, ROLLBACK_MAX_RETRIES);
    }

    private CompletableFuture<Void> retryAttempt(Supplier<CompletableFuture<Void>> action, String label, int remaining) {
        return action.get().handle((v, e) -> {
            if (e == null) return CompletableFuture.<Void>completedFuture(null); // success
            if (remaining <= 1) {
                logger.warn("Rollback step '{}' failed after {} attempts, giving up", label, ROLLBACK_MAX_RETRIES, e);
                return CompletableFuture.<Void>completedFuture(null); // give up
            }
            logger.warn("Rollback step '{}' failed, {} retries left", label, remaining - 1, e);
            return retryAttempt(action, label, remaining - 1); // retry
        }).thenCompose(f -> f);
    }

    private ShardId shardIdFor(int shardOrd) {
        return new ShardId(new Index(sourceIndex, "_na_"), shardOrd);
    }

    /** Builds and indexes the initial {@link MigrationDocument} for this migration. */
    private CompletableFuture<MigrationDocument> createMigrationDocument() {
        MigrationDocument doc = MigrationDocument.builder()
            .migrationId(migrationId)
            .sourceIndex(sourceIndex)
            .targetIndex(targetIndex)
            .transformScript(transformScript)
            .alias(alias)
            .phase(CoordinatorPhase.INITIALIZING)
            .options(options)
            .shardRoutingMode(routingMode)
            .startTimeMillis(startTimeMillis)
            .lastUpdatedMillis(startTimeMillis)
            .build();

        return migrationDocumentService.createMigrationDocument(doc);
    }

    /** Returns an unmodifiable view of the current shard progress cache. Used by Status API. */
    Map<Integer, ShardProgressDocument> shardProgressCache() {
        return Collections.unmodifiableMap(latestShardSnapshots);
    }

    /** Pre-fill gate from in-memory shard phases (always fresh — updated by acceptShardUpdate). */
    private void preFillGate(ShardGate gate) {
        latestShardSnapshots.forEach((shardOrd, progress) -> gate.shardReported(shardIdFor(shardOrd), progress.phase(), progress.error()));
    }

}
