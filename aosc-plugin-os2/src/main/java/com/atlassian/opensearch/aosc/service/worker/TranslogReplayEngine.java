/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */
package com.atlassian.opensearch.aosc.service.worker;

import com.atlassian.opensearch.aosc.model.IndexDoc;
import com.atlassian.opensearch.aosc.model.ShardRoutingMode;
import com.atlassian.opensearch.aosc.service.bulk.BulkWriter;
import com.atlassian.opensearch.aosc.service.bulk.ThreadSafeDocSource;
import com.atlassian.opensearch.aosc.service.bulk.WriteOp;
import com.atlassian.opensearch.aosc.transform.TransformFunction;
import com.atlassian.opensearch.aosc.utils.AoscLogger;
import com.atlassian.opensearch.aosc.utils.AsyncUtils;
import com.atlassian.opensearch.aosc.utils.LC;
import com.atlassian.opensearch.aosc.utils.ShardHandle;

import org.opensearch.action.delete.DeleteRequest;
import org.opensearch.action.index.IndexRequest;
import org.opensearch.common.xcontent.XContentHelper;
import org.opensearch.index.translog.Translog;
import org.opensearch.threadpool.ThreadPool;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import static com.atlassian.opensearch.aosc.utils.AoscLogger.kv;

/**
 * Shard-local translog replay engine. Opens a translog changes snapshot
 * on the source shard and replays INDEX/DELETE/NO_OP operations to the
 * target index via Bulk API.
 *
 * <p>One instance per shard migration phase. Separate instances are used for
 * REPLAYING, CONVERGING, and CATCHING_UP phases. Counters are cumulative
 * across all rounds within a single instance.</p>
 *
 * <p>Thread-safety: {@link #cancel()} and progress getters are thread-safe.
 * {@code replayRange()} must not be called concurrently — one range at a time.</p>
 */
public class TranslogReplayEngine {

    /**
     * Fired once when the engine begins real work.
     *
     * @param fromSeqNo   first sequence number to replay (inclusive)
     * @param targetSeqNo target sequence number (inclusive); for convergence this is the initial GCP
     */
    @FunctionalInterface
    public interface StartCallback {
        void onStart(long fromSeqNo, long targetSeqNo);
    }

    /**
     * Fired after each bulk batch is flushed, and once on terminal (success/cancel/error).
     *
     * @param operationsReplayed cumulative INDEX+DELETE ops replayed so far
     * @param operationsSkipped  cumulative NO_OP ops skipped so far
     * @param lastProcessedSeqNo the sequence number of the last operation processed
     * @param targetSeqNo        the target sequence number for the current round
     * @param round              current round (1 for single-range replay, 1..N for convergence)
     */
    @FunctionalInterface
    public interface ProgressCallback {
        void onProgress(long operationsReplayed, long operationsSkipped, long lastProcessedSeqNo, long targetSeqNo, int round);
    }

    @FunctionalInterface
    public interface ConvergenceRoundCallback {
        void onRoundComplete(int round, long remainingGap);
    }

    private final BulkWriter bulkWriter;
    private final ShardHandle shardHandle;
    private final String targetIndex;
    private final TransformFunction transform;
    private final ShardRoutingMode routingMode;
    private final int sourceShardCount;
    private final String[] syntheticRoutings;
    private final StartCallback startCallback;
    private final ProgressCallback progressCallback;
    private final ThreadPool threadPool;
    private final AoscLogger logger;
    private final int shardId;

    private final AtomicBoolean cancelled = new AtomicBoolean(false);
    private final AtomicBoolean started = new AtomicBoolean(false);
    private final CompletableFuture<ReplayResult> finishedFuture = new CompletableFuture<>();

    private final AtomicLong totalOperationsReplayed = new AtomicLong(0);
    private final AtomicLong totalOperationsSkipped = new AtomicLong(0);
    private volatile long lastProcessedSeqNo = -1;
    private volatile int currentRound = 0;

    public TranslogReplayEngine(
        AoscLogger logger,
        BulkWriter bulkWriter,
        ShardHandle shardHandle,
        String targetIndex,
        TransformFunction transform,
        ShardRoutingMode routingMode,
        int sourceShardCount,
        String[] syntheticRoutings,
        StartCallback startCallback,
        ProgressCallback progressCallback,
        ThreadPool threadPool
    ) {
        this.logger = Objects.requireNonNull(logger, "logger").forClass(TranslogReplayEngine.class);
        this.bulkWriter = Objects.requireNonNull(bulkWriter, "bulkWriter");
        this.shardHandle = Objects.requireNonNull(shardHandle, "shardHandle");
        this.targetIndex = Objects.requireNonNull(targetIndex, "targetIndex");
        this.transform = Objects.requireNonNull(transform, "transform");
        this.routingMode = Objects.requireNonNull(routingMode, "routingMode");
        this.sourceShardCount = sourceShardCount;
        this.syntheticRoutings = syntheticRoutings;
        this.startCallback = startCallback;
        this.progressCallback = progressCallback;
        this.threadPool = Objects.requireNonNull(threadPool, "threadPool");
        this.shardId = shardHandle.shardNum();
        finishedFuture.whenComplete((r, e) -> {
            if (progressCallback != null) {
                progressCallback.onProgress(
                    totalOperationsReplayed.get(),
                    totalOperationsSkipped.get(),
                    lastProcessedSeqNo,
                    r != null ? r.targetSeqNo() : -1,
                    currentRound
                );
            }
        });
    }

    /**
     * Replay translog operations in a convergence loop until externally signalled to stop.
     * Each round replays from {@code fromSeqNo} to the current GCP, then checks for new ops.
     *
     * @param convergenceRoundCallback callback fired after each round with remaining gap
     * @param signalledToComplete      external signal to stop after the current round
     * @param fromSeqNo                first sequence number to replay (inclusive)
     */
    public CompletableFuture<ReplayResult> replayUntilSignalledToComplete(
        ConvergenceRoundCallback convergenceRoundCallback,
        AtomicBoolean signalledToComplete,
        long fromSeqNo
    ) {
        if (cancelled.get() || !started.compareAndSet(false, true)) {
            throw new IllegalStateException("TranslogReplayEngine already started or cancelled");
        }

        lastProcessedSeqNo = fromSeqNo - 1;
        if (startCallback != null) {
            startCallback.onStart(fromSeqNo, shardHandle.getGlobalCheckpoint());
        }

        logger.info("Starting replay until signalled to complete");
        replayUntilSignalledToCompleteInternal(convergenceRoundCallback, signalledToComplete, fromSeqNo);
        return finishedFuture;
    }

    /**
     * Replay translog operations from {@code fromSeqNo} to {@code targetSeqNo} (single round).
     *
     * @param fromSeqNo   the starting sequence number (inclusive)
     * @param targetSeqNo the ending sequence number (inclusive), typically the GCP
     */
    public CompletableFuture<ReplayResult> replayRange(long fromSeqNo, long targetSeqNo) {
        if (cancelled.get() || !started.compareAndSet(false, true)) {
            throw new IllegalStateException("TranslogReplayEngine already started or cancelled");
        }

        lastProcessedSeqNo = fromSeqNo - 1;
        if (startCallback != null) {
            startCallback.onStart(fromSeqNo, targetSeqNo);
        }

        logger.info(
            "Replaying range",
            kv(LC.EVENT, "replay_range_start"),
            kv(LC.FROM_SEQ_NO, fromSeqNo),
            kv(LC.TARGET_SEQ_NO, targetSeqNo)
        );
        replayRangeInternal(fromSeqNo, targetSeqNo, finishedFuture);
        return finishedFuture;
    }

    /**
     * Internal convergence loop — replays one round then recurses until signalled to complete or cancelled.
     * Each round creates a local {@code roundFuture} that gates the next recursion.
     * On signal or cancellation, completes {@link #finishedFuture}.
     */
    private void replayUntilSignalledToCompleteInternal(
        ConvergenceRoundCallback convergenceRoundCallback,
        AtomicBoolean signalledToComplete,
        long fromSeqNo
    ) {
        if (finishedFuture.isDone()) return;

        if (cancelled.get()) {
            finishedFuture.completeExceptionally(new CancellationException("Replay cancelled"));
            return;
        }

        if (signalledToComplete.get()) {
            // Do not query the shard here — it may be closed if the index was deleted or the
            // shard relocated. We already know lastProcessedSeqNo = fromSeqNo - 1
            // from our own tracking; that is sufficient to complete the future correctly.
            logger.info(
                "Convergence signalled to complete",
                kv(LC.EVENT, "convergence_signalled"),
                kv(LC.FROM_SEQ_NO, fromSeqNo),
                kv(LC.LAST_PROCESSED_SEQ_NO, fromSeqNo - 1)
            );
            finishedFuture.complete(toResult(fromSeqNo - 1));
            return;
        }

        try {
            long targetSeqNoOfRound = shardHandle.getGlobalCheckpoint();
            long gap = Math.max(0, targetSeqNoOfRound - fromSeqNo + 1);

            if (gap <= 0) {
                // No new ops — report zero gap so the worker can detect convergence, then wait and retry
                convergenceRoundCallback.onRoundComplete(currentRound, 0);
                AsyncUtils.scheduleDelayed(
                    threadPool,
                    500,
                    () -> replayUntilSignalledToCompleteInternal(convergenceRoundCallback, signalledToComplete, fromSeqNo)
                );
                return;
            }

            CompletableFuture<ReplayResult> roundFuture = new CompletableFuture<>();
            replayRangeInternal(fromSeqNo, targetSeqNoOfRound, roundFuture);
            roundFuture.whenComplete((result, e) -> {
                if (e != null) {
                    finishedFuture.completeExceptionally(e);
                } else {
                    long remainingGap = Math.max(0, shardHandle.getGlobalCheckpoint() - targetSeqNoOfRound);
                    convergenceRoundCallback.onRoundComplete(currentRound, remainingGap);
                    replayUntilSignalledToCompleteInternal(convergenceRoundCallback, signalledToComplete, targetSeqNoOfRound + 1);
                }
            });
        } catch (Exception e) {
            finishedFuture.completeExceptionally(e);
        }
    }

    /**
     * Internal replay loop — opens a translog snapshot and processes operations via {@link BulkWriter}.
     * Completes {@code future} when done.
     *
     * @param future either {@link #finishedFuture} (for single-range replay) or a local round future
     *               (for convergence loops where each round gates the next)
     */
    private void replayRangeInternal(long fromSeqNo, long targetSeqNo, CompletableFuture<ReplayResult> future) {
        try {
            logger.info(
                "Starting replay range",
                kv(LC.EVENT, "replay_range_internal_start"),
                kv(LC.FROM_SEQ_NO, fromSeqNo),
                kv(LC.TARGET_SEQ_NO, targetSeqNo),
                kv(LC.RANGE, Math.max(0, targetSeqNo - fromSeqNo + 1)),
                kv(LC.ROUTING_MODE, routingMode.toString())
            );

            // Empty range — nothing to replay
            if (fromSeqNo > targetSeqNo || (fromSeqNo < 0 && targetSeqNo < 0)) {
                future.complete(toResult(targetSeqNo));
                return;
            }

            // Open translog snapshot — closed in future.whenComplete (single cleanup point)
            Translog.Snapshot snapshot = shardHandle.newChangesSnapshot("aosc-replay", fromSeqNo, targetSeqNo);
            future.whenComplete((r, e) -> closeSnapshotQuietly(snapshot));
            logger.debug("Translog snapshot opened: totalOperations={}", snapshot.totalOperations());

            currentRound++;

            Iterator<WriteOp<ReplayBatchMetrics>> iterator = new ReplayDocIterator(snapshot);
            ThreadSafeDocSource<ReplayBatchMetrics> source = new ThreadSafeDocSource<>(iterator);

            bulkWriter.consumeAsync(source, batch -> {
                long replayed = 0;
                long skipped = 0;
                long maxSeqNo = lastProcessedSeqNo;
                for (WriteOp<ReplayBatchMetrics> op : batch.ops()) {
                    ReplayBatchMetrics m = op.metrics();
                    replayed += m.opsReplayed();
                    skipped += m.opsSkipped();
                    if (m.lastSeqNo() > maxSeqNo) maxSeqNo = m.lastSeqNo();
                }
                totalOperationsReplayed.addAndGet(replayed);
                totalOperationsSkipped.addAndGet(skipped);
                lastProcessedSeqNo = maxSeqNo;
                if (progressCallback != null) {
                    progressCallback.onProgress(
                        totalOperationsReplayed.get(),
                        totalOperationsSkipped.get(),
                        lastProcessedSeqNo,
                        targetSeqNo,
                        currentRound
                    );
                }
            }).whenComplete((v, e) -> {
                if (e != null) {
                    future.completeExceptionally(e);
                } else {
                    logger.info(
                        "Replay range complete",
                        kv(LC.EVENT, "round_complete"),
                        kv(LC.OPS_REPLAYED, totalOperationsReplayed.get()),
                        kv(LC.OPS_SKIPPED, totalOperationsSkipped.get()),
                        kv(LC.CHECKPOINT, targetSeqNo)
                    );
                    future.complete(toResult(targetSeqNo));
                }
            });
        } catch (Exception e) {
            future.completeExceptionally(e);
        }
    }

    /**
     * Signals cancellation and returns a future that completes when the engine stops.
     * If the engine was never started, completes the future exceptionally immediately.
     */
    public CompletableFuture<ReplayResult> cancel() {
        cancelled.set(true);
        bulkWriter.cancel();
        if (!started.get()) {
            finishedFuture.completeExceptionally(new CancellationException("TranslogReplayEngine not started"));
        }
        return finishedFuture;
    }

    public boolean isCompleted() {
        return finishedFuture.isDone();
    }

    /** Closes the translog snapshot, swallowing any exception and logging a warning. */
    private void closeSnapshotQuietly(Translog.Snapshot snapshot) {
        if (snapshot != null) {
            try {
                snapshot.close();
            } catch (IOException e) {
                logger.warn("Failed to close translog snapshot", e);
            }
        }
    }

    /**
     * Result of a translog replay operation for a single shard.
     */
    @lombok.Value
    @lombok.experimental.Accessors(fluent = true)
    public static class ReplayResult {
        long lastProcessedSeqNo;
        long targetSeqNo;
        long operationsReplayed;
        long operationsSkipped;
    }

    /** Per-batch metrics for replay — tracks replayed ops, skipped ops, and last seqNo. */
    @lombok.Value
    @lombok.experimental.Accessors(fluent = true)
    public static class ReplayBatchMetrics {
        static final ReplayBatchMetrics ZERO = new ReplayBatchMetrics(0, 0, -1);

        long opsReplayed;
        long opsSkipped;
        long lastSeqNo;

        public ReplayBatchMetrics zero() {
            return ZERO;
        }

        public ReplayBatchMetrics accumulate(ReplayBatchMetrics prev) {
            return new ReplayBatchMetrics(
                prev.opsReplayed + this.opsReplayed,
                prev.opsSkipped + this.opsSkipped,
                Math.max(this.lastSeqNo, prev.lastSeqNo)
            );
        }
    }

    /** Builds a ReplayResult from the current cumulative counters. */
    private ReplayResult toResult(long targetSeqNo) {
        return new ReplayResult(lastProcessedSeqNo, targetSeqNo, totalOperationsReplayed.get(), totalOperationsSkipped.get());
    }

    /**
     * Iterator that reads translog operations one at a time, transforms them,
     * and yields {@link WriteOp} objects. NO_OP operations are yielded as
     * {@link WriteOp#skipped(long)} so the supplier can count them in batch metadata.
     * Handles DELETE fan-out for SPLIT_SHARD routing mode via a pending buffer.
     */
    private class ReplayDocIterator implements Iterator<WriteOp<ReplayBatchMetrics>> {

        private final Translog.Snapshot snapshot;
        /** Buffer for fan-out (SPLIT_SHARD DELETE produces multiple WriteOps per translog op). */
        private final ArrayDeque<WriteOp<ReplayBatchMetrics>> queue = new ArrayDeque<>();

        ReplayDocIterator(Translog.Snapshot snapshot) {
            this.snapshot = snapshot;
        }

        @Override
        public boolean hasNext() {
            if (cancelled.get()) throw new RuntimeException(new CancellationException("Replay cancelled"));
            if (!queue.isEmpty()) return true;
            try {
                Translog.Operation op;
                if ((op = snapshot.next()) != null) {
                    switch (op.opType()) {
                        case INDEX:
                        case CREATE:
                            buildIndexWriteOp((Translog.Index) op);
                            return true;
                        case DELETE:
                            buildDeleteWriteOps((Translog.Delete) op);
                            return true;
                        case NO_OP:
                            buildSkippedWriteOp(op);
                            return true;
                        default:
                            logger.warn("Unknown translog op type [{}], skipping", op.opType());
                            buildSkippedWriteOp(op);
                            return true;
                    }
                }
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
            return false;
        }

        @Override
        public WriteOp<ReplayBatchMetrics> next() {
            if (!hasNext()) throw new NoSuchElementException();
            return queue.poll();
        }

        private void buildIndexWriteOp(Translog.Index indexOp) {
            Map<String, Object> sourceMap = XContentHelper.convertToMap(indexOp.source(), true).v2();

            List<IndexDoc> outputs = transform.apply(new IndexDoc(indexOp.id(), indexOp.routing(), sourceMap));

            int emitted = 0;
            for (IndexDoc out : outputs) {
                int opsReplayed = (emitted++ == 0) ? 1 : 0;
                IndexRequest req = new IndexRequest(targetIndex).id(out.id()).source(out.source());
                if (out.routing() != null) req.routing(out.routing());
                queue.add(WriteOp.of(req, new ReplayBatchMetrics(opsReplayed, 0, indexOp.seqNo())));
            }
        }

        private void buildSkippedWriteOp(Translog.Operation op) {
            queue.add(WriteOp.skipped(new ReplayBatchMetrics(0, 1, op.seqNo())));
        }

        private void buildDeleteWriteOps(Translog.Delete deleteOp) {
            switch (routingMode) {
                case SAME_SHARD:
                    String sameShardRouting = syntheticRoutings != null ? syntheticRoutings[shardId] : null;
                    queue.add(
                        WriteOp.of(
                            new DeleteRequest(targetIndex, deleteOp.id()).routing(sameShardRouting),
                            new ReplayBatchMetrics(1, 0, deleteOp.seqNo())
                        )
                    );
                    break;
                case SPLIT_SHARD:
                    if (syntheticRoutings != null && sourceShardCount > 0) {
                        int k = syntheticRoutings.length / sourceShardCount;
                        for (int i = 0; i < k; i++) {
                            int candidateShard = shardId * k + i;
                            // Only the first fan-out request counts as 1 op replayed
                            int opsReplayed = (i == 0) ? 1 : 0;
                            queue.add(
                                WriteOp.of(
                                    new DeleteRequest(targetIndex, deleteOp.id()).routing(syntheticRoutings[candidateShard]),
                                    new ReplayBatchMetrics(opsReplayed, 0, deleteOp.seqNo())
                                )
                            );
                        }
                    } else {
                        queue.add(
                            WriteOp.of(new DeleteRequest(targetIndex, deleteOp.id()), new ReplayBatchMetrics(1, 0, deleteOp.seqNo()))
                        );
                    }
                    break;
                case BULK_API:
                default:
                    queue.add(WriteOp.of(new DeleteRequest(targetIndex, deleteOp.id()), new ReplayBatchMetrics(1, 0, deleteOp.seqNo())));
                    break;
            }
        }
    }
}
