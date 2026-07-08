/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */
package com.atlassian.opensearch.aosc.service.worker;

import com.atlassian.opensearch.aosc.AoscSettings;
import com.atlassian.opensearch.aosc.model.MigrationMetadata;
import com.atlassian.opensearch.aosc.model.MigrationRequestOptions;
import com.atlassian.opensearch.aosc.model.ShardProgressDocument;
import com.atlassian.opensearch.aosc.model.ShardRoutingMode;
import com.atlassian.opensearch.aosc.model.phase.ShardPhase;
import com.atlassian.opensearch.aosc.service.bulk.BulkWriter;
import com.atlassian.opensearch.aosc.service.bulk.BulkWriterFactory;
import com.atlassian.opensearch.aosc.service.worker.TranslogReplayEngine.ReplayResult;
import com.atlassian.opensearch.aosc.statemachine.AwaitableStateMachine;
import com.atlassian.opensearch.aosc.transform.TransformFunction;
import com.atlassian.opensearch.aosc.utils.AoscLogger;
import com.atlassian.opensearch.aosc.utils.LC;
import com.atlassian.opensearch.aosc.utils.MigrationAuditLogger;
import com.atlassian.opensearch.aosc.utils.ShardHandle;

import org.opensearch.client.Client;
import org.opensearch.common.settings.ClusterSettings;
import org.opensearch.common.unit.TimeValue;
import org.opensearch.threadpool.ThreadPool;

import java.io.Closeable;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static com.atlassian.opensearch.aosc.utils.AoscLogger.kv;

/**
 * Per-shard migration worker using {@link AwaitableStateMachine}.
 *
 * <p>Self-driving: each handler returns a {@code CompletableFuture<ShardPhase>} that
 * resolves to the next state. The SM auto-advances through the pipeline until a
 * terminal state is reached.</p>
 */
public class ShardMigrationWorker implements Closeable {

    private static final int MAX_PHASE_UPDATE_RETRIES = 10;
    private static final long STATUS_RETRY_BASE_DELAY_MS = 5_000;
    private static final long STATUS_MAX_BACKOFF_MS = 60_000;
    private static final long STATUS_GIVE_UP_TIMEOUT_MS = 30 * 60_000L; // safety net; the retry count is the real bound
    private static final long STATUS_PER_ATTEMPT_TIMEOUT_MS = 30_000;
    private static final long PROGRESS_TICK_INTERVAL_MS = 10_000;
    private static final long LEASE_RENEWAL_INTERVAL_MS = 10_000;

    private final String migrationId;
    private final ShardHandle shardHandle;
    private final MigrationRequestOptions options;
    private final Client client;
    private final ThreadPool threadPool;
    private final int shardId;
    private final AoscLogger logger;
    private final ExecutorService smExecutor;
    private final AwaitableStateMachine<ShardPhase> sm;
    private final ShardStatusReporter statusReporter;
    private final RetentionLeaseManager leaseManager;
    private final TranslogReplayEngine replayEngine;
    private final BackfillEngine backfillEngine;
    private final TranslogReplayEngine convergenceEngine;
    private final TranslogReplayEngine catchUpEngine;

    private volatile ThreadPool.Cancellable leaseRenewalTask;
    private final AtomicBoolean closed = new AtomicBoolean(false);
    private final AtomicBoolean signalledToCompleteConvergence = new AtomicBoolean(false);
    // Set in onConverging, read in onConverged to wait for engine to finish draining
    private volatile CompletableFuture<ReplayResult> convergenceEngineCompletionFuture;

    // ---- In-flight write tracking ----
    // lastProgressWrite removed — was dead code with a race condition

    // ---- Mutable progress state ----
    private volatile long backfillCutoffSeqNo = -1;
    private volatile long lastReplayedSeqNo = -1;
    // First-writer-wins failure reason — set by failWithReason() (external threads),
    // onTermination callback (SM executor), or engine error paths. AtomicReference ensures
    // the first failure reason is preserved under concurrent calls.
    private final AtomicReference<String> failureReason = new AtomicReference<>();
    private final BackfillPermitManager permitManager;
    // Resource metadata — tracks held leases and checkpoints for shutdown visibility
    private final AtomicReference<MigrationMetadata> currentMeta = new AtomicReference<>(MigrationMetadata.EMPTY);
    private static final int MAX_CONSECUTIVE_LEASE_RENEWAL_FAILURES = 3;
    private final AtomicInteger consecutiveLeaseRenewalFailures = new AtomicInteger(0);
    private final Runnable onTerminalReachedCallback;

    private final AtomicReference<ShardProgressDocument.PhaseMetrics> backfillMetrics;
    private final AtomicReference<ShardProgressDocument.PhaseMetrics> replayMetrics;
    private final AtomicReference<ShardProgressDocument.PhaseMetrics> convergenceMetrics;
    private final AtomicReference<ShardProgressDocument.PhaseMetrics> catchingUpMetrics;

    public ShardMigrationWorker(
        AoscLogger parentLogger,
        String migrationId,
        ShardHandle shardHandle,
        String targetIndex,
        int sourceShardCount,
        ShardRoutingMode routingMode,
        String[] syntheticRoutings,
        TransformFunction transform,
        MigrationRequestOptions options,
        Client client,
        ThreadPool threadPool,
        BackfillPermitManager permitManager,
        Runnable onTerminalReached,
        ClusterSettings clusterSettings
    ) {
        this.migrationId = Objects.requireNonNull(migrationId, "migrationId");
        this.shardHandle = Objects.requireNonNull(shardHandle, "shardHandle");
        this.options = Objects.requireNonNull(options, "options");
        this.client = Objects.requireNonNull(client, "client");
        this.threadPool = Objects.requireNonNull(threadPool, "threadPool");
        this.permitManager = Objects.requireNonNull(permitManager, "permitManager");
        Objects.requireNonNull(clusterSettings, "clusterSettings");
        this.onTerminalReachedCallback = onTerminalReached;

        this.shardId = shardHandle.shardNum();
        this.logger = parentLogger.with(LC.MIGRATION_ID, migrationId)
            .with(LC.SHARD, shardId)
            .with(LC.SOURCE_INDEX, shardHandle.indexName())
            .with(LC.TARGET_INDEX, targetIndex);
        this.smExecutor = Executors.newSingleThreadExecutor(r -> new Thread(r, "aosc-sm-" + migrationId + "-shard-" + shardId));
        this.leaseManager = new RetentionLeaseManager(logger, client, shardHandle.shardId(), migrationId);

        Objects.requireNonNull(targetIndex, "targetIndex");
        Objects.requireNonNull(routingMode, "routingMode");
        Objects.requireNonNull(transform, "transform");

        // Initialize metrics before engine constructors — lambdas capture these AtomicReference fields
        this.backfillMetrics = new AtomicReference<>(ShardProgressDocument.PhaseMetrics.notStarted());
        this.replayMetrics = new AtomicReference<>(ShardProgressDocument.PhaseMetrics.notStarted());
        this.convergenceMetrics = new AtomicReference<>(ShardProgressDocument.PhaseMetrics.notStarted());
        this.catchingUpMetrics = new AtomicReference<>(ShardProgressDocument.PhaseMetrics.notStarted());

        AoscLogger backfillLog = logger.with(LC.PHASE, "backfill");
        AoscLogger replayLog = logger.with(LC.PHASE, "replay");
        AoscLogger convergenceLog = logger.with(LC.PHASE, "convergence");
        AoscLogger catchUpLog = logger.with(LC.PHASE, "catchup");

        BulkWriterFactory writerFactory = new BulkWriterFactory(client, threadPool, clusterSettings);
        BulkWriter backfillWriter = writerFactory.createBackfillWriter(backfillLog);
        BulkWriter replayWriter = writerFactory.createReplayWriter(replayLog);
        BulkWriter convergenceWriter = writerFactory.createReplayWriter(convergenceLog);
        BulkWriter catchUpWriter = writerFactory.createReplayWriter(catchUpLog);

        this.backfillEngine = new BackfillEngine(
            backfillLog,
            backfillWriter,
            shardHandle,
            targetIndex,
            transform,
            () -> clusterSettings.get(AoscSettings.BACKFILL_READ_PAGE_SIZE),
            this::onBackfillStarted,
            this::onBackfillProgress
        );
        this.replayEngine = new TranslogReplayEngine(
            replayLog,
            replayWriter,
            shardHandle,
            targetIndex,
            transform,
            routingMode,
            sourceShardCount,
            syntheticRoutings,
            (from, target) -> onTranslogStarted(replayMetrics, from, target),
            (applied, skipped, last, target, round) -> onTranslogProgress(replayMetrics, applied, skipped, last, target, round),
            threadPool
        );
        this.convergenceEngine = new TranslogReplayEngine(
            convergenceLog,
            convergenceWriter,
            shardHandle,
            targetIndex,
            transform,
            routingMode,
            sourceShardCount,
            syntheticRoutings,
            (from, target) -> onTranslogStarted(convergenceMetrics, from, target),
            (applied, skipped, last, target, round) -> onTranslogProgress(convergenceMetrics, applied, skipped, last, target, round),
            threadPool
        );
        this.catchUpEngine = new TranslogReplayEngine(
            catchUpLog,
            catchUpWriter,
            shardHandle,
            targetIndex,
            transform,
            routingMode,
            sourceShardCount,
            syntheticRoutings,
            (from, target) -> onTranslogStarted(catchingUpMetrics, from, target),
            (applied, skipped, last, target, round) -> onTranslogProgress(catchingUpMetrics, applied, skipped, last, target, round),
            threadPool
        );

        // Timers are started in start(), not constructor — avoids resource leak if start() is never called

        this.sm = buildStateMachine();
        this.statusReporter = new ShardStatusReporter(
            logger,
            client,
            threadPool,
            r -> new Thread(r, "aosc-shard-reporter-" + migrationId + "-shard-" + shardId),
            migrationId,
            shardId,
            this::buildProgress, // built at send time -> current phase
            PROGRESS_TICK_INTERVAL_MS,
            STATUS_RETRY_BASE_DELAY_MS,
            STATUS_MAX_BACKOFF_MS,
            STATUS_GIVE_UP_TIMEOUT_MS,
            STATUS_PER_ATTEMPT_TIMEOUT_MS,
            MAX_PHASE_UPDATE_RETRIES
        );
    }

    // ---- Public API ----

    public void start() {
        if (closed.get()) {
            logger.warn("Cannot start worker [{}] — already closed", migrationId + "/shard-" + shardId);
            return;
        }
        statusReporter.start();
        this.leaseRenewalTask = threadPool.scheduleWithFixedDelay(
            this::renewLease,
            TimeValue.timeValueMillis(LEASE_RENEWAL_INTERVAL_MS),
            ThreadPool.Names.GENERIC
        );
        sm.start();
    }

    public void cancel() {
        sm.transitionTo(ShardPhase.CANCELLING);
    }

    public void failWithReason(String reason) {
        failureReason.compareAndSet(null, reason);
        sm.transitionTo(ShardPhase.FAILING);
    }

    public void signalCatchUp() {
        signalledToCompleteConvergence.set(true);
    }

    public ShardPhase currentPhase() {
        return sm.currentState();
    }

    public String migrationId() {
        return migrationId;
    }

    public int shardId() {
        return shardId;
    }

    public long lastReplayedSeqNo() {
        return lastReplayedSeqNo;
    }

    public String sourceIndex() {
        return shardHandle.indexName();
    }

    private void cancelTimers() {
        if (leaseRenewalTask != null) leaseRenewalTask.cancel();
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) return;
        cancelTimers();
        statusReporter.close();
        // Cancel engines — this sets their cancelled flag AND eagerly releases index resources
        backfillEngine.cancel();
        replayEngine.cancel();
        convergenceEngine.cancel();
        catchUpEngine.cancel();
        releasePermit();
        // Best-effort lease release — prevents orphaned leases on plugin restart / node shutdown
        // when the SM never reaches a terminal state (COMPLETING/CANCELLING/FAILING).
        leaseManager.release();
        // Fire SM close and shut down executor. We don't block here — blocking risks deadlock
        // if the SM is waiting for a transport response from a node that's already shutting down.
        sm.closeAsync().whenComplete((v, e) -> smExecutor.shutdown());
    }

    // ---- State machine construction ----

    private AwaitableStateMachine<ShardPhase> buildStateMachine() {
        AwaitableStateMachine.Builder<ShardPhase> builder = AwaitableStateMachine.builder(
            migrationId + "/shard-" + shardId,
            smExecutor,
            ShardPhase.PENDING
        );

        // Linear pipeline
        builder.permit(ShardPhase.PENDING, ShardPhase.ACQUIRING_LEASE)
            .permit(ShardPhase.ACQUIRING_LEASE, ShardPhase.BACKFILLING)
            .permit(ShardPhase.BACKFILLING, ShardPhase.REPLAYING)
            .permit(ShardPhase.REPLAYING, ShardPhase.CONVERGING)
            .permit(ShardPhase.CONVERGING, ShardPhase.CONVERGED)
            .permit(ShardPhase.CONVERGED, ShardPhase.CATCHING_UP)
            .permit(ShardPhase.CATCHING_UP, ShardPhase.COMPLETING)
            .permit(ShardPhase.COMPLETING, ShardPhase.COMPLETED);

        // Cancel/fail from any non-terminal
        builder.permitFromAnyExcept(ShardPhase.CANCELLING, ShardPhase.TERMINALS)
            .permitFromAnyExcept(ShardPhase.FAILING, ShardPhase.TERMINALS)
            .permit(ShardPhase.CANCELLING, ShardPhase.CANCELLED)
            .permit(ShardPhase.FAILING, ShardPhase.FAILED);

        // Terminals
        ShardPhase.TERMINALS.forEach(builder::terminal);

        // Handlers
        builder.handler(ShardPhase.PENDING, this::onPending)
            .handler(ShardPhase.ACQUIRING_LEASE, this::onAcquiringLease)
            .handler(ShardPhase.BACKFILLING, this::onBackfilling)
            .handler(ShardPhase.REPLAYING, this::onReplaying)
            .handler(ShardPhase.CONVERGING, this::onConverging)
            .handler(ShardPhase.CONVERGED, this::onConverged)
            .handler(ShardPhase.CATCHING_UP, this::onCatchingUp)
            .handler(ShardPhase.COMPLETING, this::onCompleting)
            .handler(ShardPhase.CANCELLING, this::onCancelling)
            .handler(ShardPhase.FAILING, this::onFailing);

        // Write barrier — notify coordinator of each phase transition. The SM awaits this future
        // before running the next handler, so the transition is confirmed (or the shard fails) first.
        builder.writeBarrier((from, to) -> {
            MigrationAuditLogger.recordShardPhaseTransition(migrationId, shardId, from.name(), to.name(), null);
            return statusReporter.reportTransition();
        });

        // Terminal callback — runs AFTER write barrier (coordinator already notified).
        // Lease release is in pre-terminal handlers (onCompleting/cleanupAndTerminate).
        builder.onTerminalReached(terminalState -> {
            cancelTimers();
            if (onTerminalReachedCallback != null) {
                onTerminalReachedCallback.run();
            }
            return CompletableFuture.completedFuture(null);
        });

        // Failure handler
        builder.onFailure(ctx -> {
            logger.error("Unhandled error in shard phase {}: {}", ctx.failedInState(), ctx.message(), ctx.cause());
            failureReason.compareAndSet(null, ctx.cause() != null ? ctx.cause().getMessage() : ctx.message());
            if (ctx.failedInState() != ShardPhase.FAILING && ctx.failedInState() != ShardPhase.CANCELLING) {
                ctx.sm().transitionTo(ShardPhase.FAILING);
            }
        });

        return builder.build();
    }

    // ---- Phase handlers ----

    private CompletableFuture<ShardPhase> onPending(AwaitableStateMachine.Context<ShardPhase> ctx) {
        logger.info("Starting shard migration worker — acquiring backfill permit");
        return permitManager.acquire(migrationId, shardId).thenApply(v -> {
            logger.info("Backfill permit acquired");
            return ShardPhase.ACQUIRING_LEASE;
        });
    }

    private CompletableFuture<ShardPhase> onAcquiringLease(AwaitableStateMachine.Context<ShardPhase> ctx) {
        logger.info("Acquiring retention lease");
        if (backfillCutoffSeqNo >= 0) {
            logger.info("Lease already acquired, backfillCutoffSeqNo={}", backfillCutoffSeqNo);
            return CompletableFuture.completedFuture(ShardPhase.BACKFILLING);
        }
        long gcp = shardHandle.getGlobalCheckpoint();
        return leaseManager.acquire(gcp).thenApply(v -> {
            backfillCutoffSeqNo = gcp;
            currentMeta.set(MigrationMetadata.builder().put(MigrationMetadata.ACTIVE_LEASE, leaseManager.leaseId()).build());
            logger.info("Lease acquired, backfillCutoffSeqNo={}", backfillCutoffSeqNo);
            return ShardPhase.BACKFILLING;
        });
    }

    private CompletableFuture<ShardPhase> onBackfilling(AwaitableStateMachine.Context<ShardPhase> ctx) {
        // Phase entry logged by SM transition record
        return backfillEngine.start().thenApply(result -> {
            backfillMetrics.updateAndGet(
                m -> m.toBuilder()
                    .status(ShardProgressDocument.PhaseStatus.COMPLETED)
                    .documentsIndexed(result.documentsProcessed())
                    .endTimeMillis(System.currentTimeMillis())
                    .build()
            );
            logger.info("Backfill complete: {} docs processed in {} batches", result.documentsProcessed(), result.batchCount());
            return ShardPhase.REPLAYING;
        });
    }

    private CompletableFuture<ShardPhase> onReplaying(AwaitableStateMachine.Context<ShardPhase> ctx) {
        // Phase entry logged by SM transition record
        long fromSeqNo = lastReplayedSeqNo >= 0 ? lastReplayedSeqNo + 1 : backfillCutoffSeqNo + 1;
        long targetSeqNo = shardHandle.getGlobalCheckpoint();
        return replayEngine.replayRange(fromSeqNo, targetSeqNo).thenApply(result -> {
            lastReplayedSeqNo = result.lastProcessedSeqNo();
            replayMetrics.updateAndGet(
                m -> m.toBuilder()
                    .status(ShardProgressDocument.PhaseStatus.COMPLETED)
                    .startSeqNo(fromSeqNo)
                    .targetSeqNo(targetSeqNo)
                    .lastProcessedSeqNo(result.lastProcessedSeqNo())
                    .operationsApplied(result.operationsReplayed())
                    .operationsSkipped(result.operationsSkipped())
                    .endTimeMillis(System.currentTimeMillis())
                    .currentGap(Math.max(0, result.targetSeqNo() - result.lastProcessedSeqNo()))
                    .rounds(1)
                    .build()
            );
            logger.info(
                "Replay complete",
                kv(LC.EVENT, "replay_complete"),
                kv(LC.OPS_APPLIED, result.operationsReplayed()),
                kv(LC.OPS_SKIPPED, result.operationsSkipped())
            );
            return ShardPhase.CONVERGING;
        });
    }

    /**
     * CONVERGING: starts the convergence engine which loops internally, detecting convergence
     * via callback and continuing until signalCatchUp() is called. The engine handles both CONVERGING
     * (approaching convergence threshold) and CONVERGED (keeping fresh while waiting for signal).
     * Returns CATCHING_UP when signalled.
     */
    private CompletableFuture<ShardPhase> onConverging(AwaitableStateMachine.Context<ShardPhase> ctx) {

        long fromSeqNo = lastReplayedSeqNo + 1;
        int threshold = options.getConvergenceThresholdPerShard() != null ? options.getConvergenceThresholdPerShard() : 100;
        int maxRounds = options.getMaxConvergenceRoundsPerShard();

        CompletableFuture<ShardPhase> convergedFuture = new CompletableFuture<>();

        convergenceEngineCompletionFuture = convergenceEngine.replayUntilSignalledToComplete((rounds, currentGap) -> {
            if (!convergedFuture.isDone() && maxRounds > 0 && rounds >= maxRounds) {
                logger.error(
                    "Convergence rounds exceeded limit: rounds={}, max={}, currentGap={}, threshold={}",
                    rounds,
                    maxRounds,
                    currentGap,
                    threshold
                );
                failWithReason("Convergence rounds exceeded limit: " + rounds + " >= " + maxRounds);
                return;
            }
            if (currentGap <= threshold) {
                convergedFuture.complete(ShardPhase.CONVERGED); // atomic — only first call wins
            }
        }, signalledToCompleteConvergence, fromSeqNo);

        // Propagate engine errors to convergedFuture (e.g. engine crash before convergence)
        convergenceEngineCompletionFuture.exceptionally(ex -> {
            if (!convergedFuture.isDone()) {
                convergedFuture.completeExceptionally(ex);
            }
            return null;
        });

        return convergedFuture;
    }

    /**
     * CONVERGED: convergence engine is still running in background (started in onConverging).
     * Waits for convergenceEngineCompletionFuture to complete (which happens when signalCatchUp() is called,
     * setting signalledToCompleteConvergence=true, causing the engine to exit its loop).
     * Then captures the engine's final ReplayResult into metrics and transitions to CATCHING_UP.
     */
    private CompletableFuture<ShardPhase> onConverged(AwaitableStateMachine.Context<ShardPhase> ctx) {
        releasePermit();
        logger.info("Shard converged — waiting for catch-up signal");
        CompletableFuture<ReplayResult> done = convergenceEngineCompletionFuture;
        if (done == null) {
            return CompletableFuture.completedFuture(ShardPhase.CATCHING_UP);
        }
        return done.thenApply(result -> {
            // Authoritative update from engine result — progress callback values may lag
            convergenceMetrics.updateAndGet(
                m -> m.toBuilder()
                    .status(ShardProgressDocument.PhaseStatus.COMPLETED)
                    .targetSeqNo(result.targetSeqNo())
                    .lastProcessedSeqNo(result.lastProcessedSeqNo())
                    .operationsApplied(result.operationsReplayed())
                    .operationsSkipped(result.operationsSkipped())
                    .endTimeMillis(System.currentTimeMillis())
                    .currentGap(Math.max(0, result.targetSeqNo() - result.lastProcessedSeqNo()))
                    .build()
            );
            return ShardPhase.CATCHING_UP;
        });
    }

    private CompletableFuture<ShardPhase> onCatchingUp(AwaitableStateMachine.Context<ShardPhase> ctx) {
        shardHandle.flushBestEffort();
        long fromSeqNo = lastReplayedSeqNo + 1;
        long targetSeqNo = shardHandle.getGlobalCheckpoint();
        return catchUpEngine.replayRange(fromSeqNo, targetSeqNo).thenApply(result -> {
            lastReplayedSeqNo = result.lastProcessedSeqNo();
            catchingUpMetrics.updateAndGet(
                m -> m.toBuilder()
                    .status(ShardProgressDocument.PhaseStatus.COMPLETED)
                    .startSeqNo(fromSeqNo)
                    .targetSeqNo(targetSeqNo)
                    .lastProcessedSeqNo(result.lastProcessedSeqNo())
                    .operationsApplied(result.operationsReplayed())
                    .operationsSkipped(result.operationsSkipped())
                    .endTimeMillis(System.currentTimeMillis())
                    .currentGap(Math.max(0, result.targetSeqNo() - result.lastProcessedSeqNo()))
                    .rounds(1)
                    .build()
            );
            logger.info(
                "Catch-up complete",
                kv(LC.EVENT, "catchup_complete"),
                kv(LC.OPS_APPLIED, result.operationsReplayed()),
                kv(LC.OPS_SKIPPED, result.operationsSkipped())
            );
            return ShardPhase.COMPLETING;
        });
    }

    private CompletableFuture<ShardPhase> onCompleting(AwaitableStateMachine.Context<ShardPhase> ctx) {
        logger.info("Completing shard migration");
        return leaseManager.release().exceptionally(e -> {
            logger.warn("Failed to release lease on completion (proceeding)", e);
            return null;
        }).thenApply(v -> {
            currentMeta.updateAndGet(m -> m.toBuilder().remove(MigrationMetadata.ACTIVE_LEASE).build());
            return ShardPhase.COMPLETED;
        });
    }

    private CompletableFuture<ShardPhase> onCancelling(AwaitableStateMachine.Context<ShardPhase> ctx) {
        return cleanupAndTerminate(ShardPhase.CANCELLED);
    }

    private CompletableFuture<ShardPhase> onFailing(AwaitableStateMachine.Context<ShardPhase> ctx) {
        String reason = failureReason.get();
        logger.error("Shard migration failing: {}", reason != null ? reason : "unknown");
        return cleanupAndTerminate(ShardPhase.FAILED);
    }

    private CompletableFuture<ShardPhase> cleanupAndTerminate(ShardPhase terminal) {
        // Mark the active phase's metrics as failed/cancelled
        ShardPhase failedAt = sm.currentState();
        markEngineTermination(failedAt, ShardProgressDocument.PhaseStatus.FAILED);
        backfillEngine.cancel();
        replayEngine.cancel();
        convergenceEngine.cancel();
        catchUpEngine.cancel();
        releasePermit(); // safety net — write barrier should have released already
        // Release lease before reaching terminal (awaited — lease must be released durably)
        return leaseManager.release().exceptionally(e -> {
            logger.warn("Failed to release lease during {} cleanup (proceeding)", terminal, e);
            return null;
        }).thenApply(v -> {
            currentMeta.updateAndGet(m -> m.toBuilder().remove(MigrationMetadata.ACTIVE_LEASE).build());
            return terminal;
        });
    }

    private void releasePermit() {
        permitManager.release(migrationId, shardId);
    }

    // ---- Engine start callbacks ----

    private void onBackfillStarted() {
        backfillMetrics.updateAndGet(
            m -> m.toBuilder()
                .status(ShardProgressDocument.PhaseStatus.IN_PROGRESS)
                .startTimeMillis(System.currentTimeMillis())
                .rounds(0)
                .build()
        );
    }

    private void onBackfillProgress(long docsProcessed, int batchCount, long totalDocs) {
        backfillMetrics.updateAndGet(
            m -> m.toBuilder().documentsIndexed(docsProcessed).rounds(batchCount).currentGap(Math.max(0, totalDocs - docsProcessed)).build()
        );
    }

    /** Shared start handler for all three translog engines (replay, convergence, catch-up). */
    private void onTranslogStarted(AtomicReference<ShardProgressDocument.PhaseMetrics> ref, long fromSeqNo, long targetSeqNo) {
        ref.updateAndGet(
            m -> m.toBuilder()
                .status(ShardProgressDocument.PhaseStatus.IN_PROGRESS)
                .startTimeMillis(System.currentTimeMillis())
                .startSeqNo(fromSeqNo)
                .targetSeqNo(targetSeqNo)
                .currentGap(Math.max(0, targetSeqNo - fromSeqNo + 1))
                .rounds(0)
                .build()
        );
    }

    /** Shared progress handler for replay and catch-up (fixed target, gap goes straight down). */
    private void onTranslogProgress(
        AtomicReference<ShardProgressDocument.PhaseMetrics> ref,
        long opsReplayed,
        long opsSkipped,
        long lastProcessedSeqNo,
        long targetSeqNo,
        int round
    ) {
        lastReplayedSeqNo = Math.max(lastProcessedSeqNo, lastReplayedSeqNo);
        ref.updateAndGet(m -> {
            long actualTargetSeqNo = Math.max(m.targetSeqNo(), targetSeqNo); // To handle callback on failure
            return m.toBuilder()
                .operationsApplied(opsReplayed)
                .operationsSkipped(opsSkipped)
                .targetSeqNo(actualTargetSeqNo)
                .lastProcessedSeqNo(lastProcessedSeqNo)
                .currentGap(Math.max(0, actualTargetSeqNo - lastProcessedSeqNo))
                .rounds(round)
                .build();
        });
    }

    private void markEngineTermination(ShardPhase failedAt, ShardProgressDocument.PhaseStatus status) {
        long now = System.currentTimeMillis();
        switch (failedAt) {
            case BACKFILLING:
                backfillMetrics.updateAndGet(m -> m.toBuilder().status(status).endTimeMillis(now).build());
                break;
            case REPLAYING:
                replayMetrics.updateAndGet(m -> m.toBuilder().status(status).endTimeMillis(now).build());
                break;
            case CONVERGING:
            case CONVERGED:
                convergenceMetrics.updateAndGet(m -> m.toBuilder().status(status).endTimeMillis(now).build());
                break;
            case CATCHING_UP:
                catchingUpMetrics.updateAndGet(m -> m.toBuilder().status(status).endTimeMillis(now).build());
                break;
            default:
                break;
        }
    }

    // ---- Lease renewal ----

    private void renewLease() {
        long seqNo = lastReplayedSeqNo;
        if (seqNo < 0) return;
        leaseManager.renew(seqNo).whenComplete((v, ex) -> {
            if (ex != null) {
                int failures = consecutiveLeaseRenewalFailures.incrementAndGet();
                logger.warn(
                    "Lease renewal failed at seqNo [{}] ({}/{} consecutive failures)",
                    seqNo,
                    failures,
                    MAX_CONSECUTIVE_LEASE_RENEWAL_FAILURES,
                    ex
                );
                if (failures >= MAX_CONSECUTIVE_LEASE_RENEWAL_FAILURES) {
                    failWithReason(
                        "retention lease renewal failed " + failures + " consecutive times at seqNo " + seqNo + ": " + ex.getMessage()
                    );
                }
            } else {
                consecutiveLeaseRenewalFailures.set(0);
                logger.trace("Lease renewed at seqNo [{}]", seqNo);
            }
        });
    }

    // ---- Progress snapshot ----

    /** Build a point-in-time snapshot of this shard's progress for the current phase. */
    private ShardProgressDocument buildProgress() {
        ShardPhase phase = sm.currentState();
        return ShardProgressDocument.builder()
            .phase(phase)
            .lastReplayedSeqNo(lastReplayedSeqNo)
            .targetSeqNo(shardHandle.getGlobalCheckpointSafe())
            .backfillCutoffSeqNo(backfillCutoffSeqNo)
            .error(phase == ShardPhase.FAILING || phase == ShardPhase.FAILED ? failureReason.get() : null)
            .backfill(backfillMetrics.get())
            .replay(replayMetrics.get())
            .convergence(convergenceMetrics.get())
            .catchingUp(catchingUpMetrics.get())
            .transitionHistory(sm.history())
            .meta(currentMeta.get())
            .build();
    }

}
