/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */
package com.atlassian.opensearch.aosc.service.bulk;

import com.atlassian.opensearch.aosc.service.adaptive.BulkOutcome;
import com.atlassian.opensearch.aosc.utils.AoscLogger;
import com.atlassian.opensearch.aosc.utils.LC;

import org.opensearch.common.util.concurrent.FutureUtils;
import org.opensearch.threadpool.ThreadPool;
import org.opensearch.transport.client.Client;

import java.util.ArrayList;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static com.atlassian.opensearch.aosc.utils.AoscLogger.kv;

/**
 * Concurrent bulk writer with semaphore-gated dispatch and explicit in-flight tracking.
 *
 * <p>A single reader thread runs on {@code threadPool.generic()}, blocking on a
 * {@link Semaphore} to gate up to W concurrent async bulk requests. Each dispatched
 * request is tracked in an in-flight set so that {@link #cancel()} can cancel
 * outstanding futures immediately without waiting for drain.</p>
 *
 * <p><b>Blocking is intentional.</b> The generic pool is an unbounded {@code ScalingThreadPool}
 * that grows on demand. We hold at most {@code MAX_CONCURRENT_PER_NODE} (default 10) threads
 * for shard workers — well within capacity. The blocking reader model is dramatically
 * simpler than the non-blocking CAS + re-scheduling alternative.</p>
 *
 * <p>Concurrency changes flow through {@link WriteController#concurrency()}, not through
 * the controller. The controller owns W state internally;
 * {@code adjustPermits()} polls {@code controller.concurrency()} each iteration.</p>
 */
public class ConcurrentBulkWriter implements BulkWriter {

    private final AoscLogger logger;
    private final Client client;
    private final ThreadPool threadPool;
    private final WriteController controller;
    private final AtomicBoolean cancelled = new AtomicBoolean(false);
    final Set<CompletableFuture<BulkOutcome>> inflight = ConcurrentHashMap.newKeySet();
    private final AtomicInteger effectiveW;
    private volatile Semaphore permits = new Semaphore(0);
    private final Semaphore pauseGate = new Semaphore(0);

    public ConcurrentBulkWriter(Client client, ThreadPool threadPool, WriteController controller, AoscLogger logger) {
        this.logger = Objects.requireNonNull(logger, "logger").forClass(ConcurrentBulkWriter.class);
        this.client = Objects.requireNonNull(client, "client");
        this.threadPool = Objects.requireNonNull(threadPool, "threadPool");
        this.controller = Objects.requireNonNull(controller, "controller");
        int initialW = controller.concurrency();
        if (initialW < 1) throw new IllegalArgumentException("controller.concurrency() must be >= 1, got " + initialW);
        this.effectiveW = new AtomicInteger(initialW);
    }

    @Override
    public <M> CompletableFuture<Void> consumeAsync(DocSource<M> docSource, Consumer<PreparedBatch<M>> progressCallback) {
        CompletableFuture<Void> future = new CompletableFuture<>();
        threadPool.generic().execute(() -> {
            try {
                readerLoop(docSource, progressCallback, future);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                future.completeExceptionally(new CancellationException("Reader thread interrupted"));
            } catch (Exception e) {
                future.completeExceptionally(e);
            }
        });
        return future;
    }

    @Override
    public void cancel() {
        cancelled.set(true);
        cancelAllInflight();
        // Wake reader if blocked on permits.acquire() or pauseGate.tryAcquire()
        Semaphore s = permits;
        if (s != null) s.release();
        pauseGate.release();
    }

    public boolean isCancelled() {
        return cancelled.get();
    }

    private void cancelAllInflight() {
        var snapshot = new ArrayList<>(inflight);
        inflight.clear();
        for (CompletableFuture<BulkOutcome> f : snapshot) {
            FutureUtils.cancel(f);
        }
    }

    private <M> void readerLoop(DocSource<M> source, Consumer<PreparedBatch<M>> progressCallback, CompletableFuture<Void> future)
        throws InterruptedException {
        BatchBuilder<M> builder = new BatchBuilder<>(source);
        Semaphore localPermits = new Semaphore(effectiveW.get());
        this.permits = localPermits;
        AtomicReference<Throwable> fatalError = new AtomicReference<>();
        AtomicLong pauseUntil = new AtomicLong(0);

        // A momentarily empty source (poll() == null) is not exhaustion: an in-flight batch can still
        // requeue ops via the async PAUSE_AND_RETRY callback. Drain in flight, then finish only once
        // the source is truly exhausted (iterator drained AND retry deque empty); otherwise loop.
        do {
            dispatchUntilSourceMomentarilyEmpty(source, builder, progressCallback, localPermits, fatalError, pauseUntil);

            // Abort promptly on cancel/fatal — don't wait for the drain.
            if (cancelled.get()) {
                cancelAllInflight();
                future.completeExceptionally(new CancellationException("ConcurrentBulkWriter cancelled"));
                return;
            }
            if (fatalError.get() != null) {
                cancelAllInflight();
                future.completeExceptionally(fatalError.get());
                return;
            }

            // Barrier: returnForRetry runs inside the completion callback before the tracked future
            // resolves, so once allOf() returns nothing is in flight and any requeue is already
            // visible to the isExhausted() check below.
            if (!inflight.isEmpty()) {
                try {
                    CompletableFuture.allOf(inflight.toArray(new CompletableFuture[0])).join();
                } catch (Exception e) {
                    // Individual failures already captured in fatalError via whenComplete
                }
            }

            // The drain may have surfaced a fatal error, or a cancel may have raced in.
            if (cancelled.get()) {
                cancelAllInflight();
                future.completeExceptionally(new CancellationException("ConcurrentBulkWriter cancelled"));
                return;
            }
            if (fatalError.get() != null) {
                cancelAllInflight();
                future.completeExceptionally(fatalError.get());
                return;
            }
        } while (!source.isExhausted());

        future.complete(null);
    }

    /**
     * Dispatches batches until {@link DocSource#poll()} momentarily returns {@code null}, or the
     * writer is cancelled, or a fatal error is recorded. {@link #readerLoop} then drains in-flight
     * requests and re-checks true exhaustion.
     */
    private <M> void dispatchUntilSourceMomentarilyEmpty(
        DocSource<M> source,
        BatchBuilder<M> builder,
        Consumer<PreparedBatch<M>> progressCallback,
        Semaphore localPermits,
        AtomicReference<Throwable> fatalError,
        AtomicLong pauseUntil
    ) throws InterruptedException {
        while (!cancelled.get() && fatalError.get() == null) {
            // Honour overload pause — block on zero-permit semaphore as interruptible sleep
            long remaining = pauseUntil.getAndSet(0) - System.currentTimeMillis();
            if (remaining > 0) {
                logger.debug("pausing {}ms for overload backoff", remaining);
                pauseGate.tryAcquire(1, remaining, TimeUnit.MILLISECONDS);
            }

            adjustPermits(localPermits);
            localPermits.acquire();

            // Re-check after waking
            if (cancelled.get() || fatalError.get() != null) {
                localPermits.release();
                break;
            }

            // Re-check overload pause after acquire — a callback may have set
            // pauseUntil while we were blocked, and the permit was released
            // after the pause value was written.
            remaining = pauseUntil.getAndSet(0) - System.currentTimeMillis();
            if (remaining > 0) {
                localPermits.release();
                logger.debug("pausing {}ms for overload backoff (post-acquire)", remaining);
                pauseGate.tryAcquire(1, remaining, TimeUnit.MILLISECONDS);
                localPermits.acquire();
                if (cancelled.get() || fatalError.get() != null) {
                    localPermits.release();
                    break;
                }
            }

            PreparedBatch<M> batch = builder.nextBatch(controller.nextBatchSize(), controller.maxBatchBytes());
            if (batch == null) {
                localPermits.release();
                break;
            }
            if (batch.docCount() == 0) {
                localPermits.release();
                if (progressCallback != null) progressCallback.accept(batch);
                continue;
            }

            // Dispatch async — release permit on completion.
            // Track the whenComplete-derived future (not the raw bulkFuture) so
            // allOf().join() waits for the progress callback to finish too.
            // handleOutcome (which may set pauseUntil) MUST run before release(),
            // otherwise the reader wakes and dispatches the next batch before
            // the overload pause is visible.
            CompletableFuture<BulkOutcome> bulkFuture = submitBulk(batch);
            CompletableFuture<BulkOutcome> tracked = bulkFuture.whenComplete((outcome, ex) -> {

                if (bulkFuture.isCancelled()) {
                    localPermits.release();
                    return;
                }

                if (ex != null) {
                    fatalError.compareAndSet(null, ex);
                    localPermits.release();
                    return;
                }

                handleOutcome(outcome, batch, source, progressCallback, fatalError, pauseUntil);
                localPermits.release();
            });
            inflight.add(tracked);
            tracked.whenComplete((v, e) -> inflight.remove(tracked));
        }
    }

    private <M> CompletableFuture<BulkOutcome> submitBulk(PreparedBatch<M> batch) {
        return BulkWriteHelper.submitBulkAsyncWithOutcome(client, batch.request(), logger, threadPool);
    }

    private <M> void handleOutcome(
        BulkOutcome outcome,
        PreparedBatch<M> batch,
        DocSource<M> source,
        Consumer<PreparedBatch<M>> progressCallback,
        AtomicReference<Throwable> fatalError,
        AtomicLong pauseUntil
    ) {
        WriteDecision decision = controller.handleOutcome(outcome);
        switch (decision.action()) {
            case SUCCESS:
                if (progressCallback != null) progressCallback.accept(batch);
                break;
            case PAUSE_AND_RETRY:
                source.returnForRetry(batch.ops());
                long newDeadline = System.currentTimeMillis() + decision.pauseMillis();
                pauseUntil.accumulateAndGet(newDeadline, Math::max);
                logger.info(
                    "Overload requeue",
                    kv(LC.EVENT, "overload_requeue"),
                    kv(LC.REQUEUED_OPS, batch.ops().size()),
                    kv(LC.PAUSE_MS, decision.pauseMillis())
                );
                break;
            case FATAL:
                fatalError.compareAndSet(null, new RuntimeException(decision.reason()));
                break;
            default:
                break;
        }
    }

    private void adjustPermits(Semaphore permits) {
        int desired = Math.max(1, controller.concurrency());
        int current = effectiveW.get();
        if (desired != current) {
            int diff = desired - current;
            if (diff > 0) {
                permits.release(diff);
            } else {
                for (int i = 0; i < -diff; i++) {
                    permits.tryAcquire();
                }
            }
            effectiveW.set(desired);
            logger.debug("adjusted concurrency {} -> {}", current, desired);
        }
    }

}
