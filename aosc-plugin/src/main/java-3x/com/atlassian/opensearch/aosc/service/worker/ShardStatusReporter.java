/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */
package com.atlassian.opensearch.aosc.service.worker;

import com.atlassian.opensearch.aosc.action.update.UpdateShardMigrationStatusAction;
import com.atlassian.opensearch.aosc.action.update.UpdateShardMigrationStatusBody;
import com.atlassian.opensearch.aosc.action.update.UpdateShardMigrationStatusRequest;
import com.atlassian.opensearch.aosc.model.ShardProgressDocument;
import com.atlassian.opensearch.aosc.utils.AoscLogger;
import com.atlassian.opensearch.aosc.utils.AsyncClientHelper;

import org.opensearch.common.unit.TimeValue;
import org.opensearch.threadpool.ThreadPool;
import org.opensearch.transport.client.Client;

import java.io.Closeable;
import java.util.Objects;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

/**
 * The single path a shard worker uses to send status updates to the coordinator. Owns both the periodic
 * heartbeat and the write-barrier (phase-transition) updates, on one dedicated thread, and builds the
 * status request itself from a point-in-time progress snapshot.
 *
 * <h2>Design — a single-threaded sequential loop</h2>
 *
 * <p>The dedicated thread runs a loop that {@code poll}s a one-slot mailbox: a queued barrier is
 * delivered; on {@code poll} timeout a heartbeat is sent. Because one thread sends one update at a time,
 * <b>updates leave the worker strictly ordered</b> with no lock, no in-flight flags, and no callback
 * coordination. Every send — heartbeat or barrier — is built from {@link #snapshot} <b>at send time</b>
 * (re-read on each attempt), so the wire sequence carries the current, only-advancing phase and is
 * monotonic; the coordinator can trust the last value it receives.</p>
 *
 * <ul>
 *   <li><b>Write barrier (blocking):</b> {@link #reportTransition} returns immediately with a future the
 *       state machine awaits (the SM never blocks a thread). The barrier is delivered with bounded
 *       exponential retries; the future completes on ack, treats a vanished coordinator as success, and
 *       fails after {@code maxRetries} → the SM transitions to FAILING.</li>
 *   <li><b>Heartbeat:</b> the loop's {@code poll} timeout is the tick — a single best-effort send with no
 *       retry (the next poll is its retry), so there is no separate timer thread.</li>
 *   <li><b>Supersede:</b> on an interrupt (cancel/fail) the SM enqueues a new barrier; the in-flight
 *       barrier sees a non-empty mailbox and stops retrying, and its now-moot completion is discarded by
 *       the SM's epoch check.</li>
 * </ul>
 */
final class ShardStatusReporter implements Closeable {

    /** A blocking write-barrier waiter. The phase to deliver is always read from {@link #snapshot}. */
    private static final class Barrier {
        final CompletableFuture<Void> future = new CompletableFuture<>();
    }

    private final AoscLogger logger;
    private final Client client;
    private final ThreadPool threadPool;
    private final String migrationId;
    private final int shardId;
    private final Supplier<ShardProgressDocument> snapshot;
    private final long heartbeatMs;
    private final long baseDelayMs;
    private final long maxDelayMs;
    private final long giveUpTimeoutMs;
    private final long perAttemptTimeoutMs;
    private final int maxRetries;

    private final BlockingQueue<Barrier> mailbox = new LinkedBlockingQueue<>();
    private final ExecutorService executor;
    private volatile boolean closed;

    ShardStatusReporter(
        AoscLogger logger,
        Client client,
        ThreadPool threadPool,
        ThreadFactory threadFactory,
        String migrationId,
        int shardId,
        Supplier<ShardProgressDocument> snapshot,
        long heartbeatMs,
        long baseDelayMs,
        long maxDelayMs,
        long giveUpTimeoutMs,
        long perAttemptTimeoutMs,
        int maxRetries
    ) {
        this.logger = Objects.requireNonNull(logger, "logger").forClass(ShardStatusReporter.class);
        this.client = Objects.requireNonNull(client, "client");
        this.threadPool = Objects.requireNonNull(threadPool, "threadPool");
        this.migrationId = Objects.requireNonNull(migrationId, "migrationId");
        this.shardId = shardId;
        this.snapshot = Objects.requireNonNull(snapshot, "snapshot");
        this.heartbeatMs = heartbeatMs;
        this.baseDelayMs = baseDelayMs;
        this.maxDelayMs = maxDelayMs;
        this.giveUpTimeoutMs = giveUpTimeoutMs;
        this.perAttemptTimeoutMs = perAttemptTimeoutMs;
        this.maxRetries = maxRetries;
        this.executor = Executors.newSingleThreadExecutor(Objects.requireNonNull(threadFactory, "threadFactory"));
    }

    /** Start the reporter loop. Must be called once before any updates are expected to flow. */
    void start() {
        executor.execute(this::runLoop);
    }

    /**
     * Write-barrier path. Returns immediately with the future the state machine awaits; the current state
     * is delivered (with retries) on the reporter thread. Never blocks the caller.
     */
    CompletableFuture<Void> reportTransition() {
        Barrier b = new Barrier();
        if (closed) {
            b.future.completeExceptionally(new IllegalStateException("shard status reporter closed"));
        } else {
            mailbox.offer(b);
        }
        return b.future;
    }

    // ---- The one thread ----

    private void runLoop() {
        while (!closed) {
            Barrier b;
            try {
                b = mailbox.poll(heartbeatMs, TimeUnit.MILLISECONDS); // a barrier, or null on timeout -> heartbeat
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
            if (closed) {
                break;
            }
            // A single delivery must never kill the loop — otherwise this shard would go silent.
            try {
                if (b != null) {
                    deliverBarrier(b);
                } else {
                    sendHeartbeat();
                }
            } catch (Exception e) {
                if (b != null) {
                    b.future.completeExceptionally(e);
                }
                logger.warn("Shard status delivery failed unexpectedly", e);
            }
        }
        failPending();
    }

    private void deliverBarrier(Barrier b) {
        AtomicInteger attempts = new AtomicInteger();
        CompletableFuture<Void> done = AsyncClientHelper.executeAsyncWithRetry(
            logger.unwrap(),
            threadPool,
            this::sendOnce, // re-invoked per attempt -> built at send time from current state
            TimeValue.timeValueMillis(baseDelayMs),
            TimeValue.timeValueMillis(maxDelayMs),
            TimeValue.timeValueMillis(giveUpTimeoutMs),
            ThreadPool.Names.GENERIC, // retries are scheduled here; this thread is parked on get()
            // Stop retrying when: closed, the failure is non-retryable, a newer barrier superseded us, or
            // retries are exhausted. (!closed winds the RetryableAction down promptly on shutdown.)
            e -> !closed && !isCoordinatorClosed(e) && mailbox.isEmpty() && attempts.incrementAndGet() < maxRetries
        );
        try {
            done.get(giveUpTimeoutMs + 1_000, TimeUnit.MILLISECONDS);
            b.future.complete(null); // delivered (current phase is pinned to the target while the SM blocks)
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            b.future.completeExceptionally(e); // shutting down — SM is closing and ignores this
        } catch (Exception e) {
            Throwable cause = (e instanceof ExecutionException && e.getCause() != null) ? e.getCause() : e;
            if (isCoordinatorClosed(cause) || !mailbox.isEmpty()) {
                b.future.complete(null); // coordinator vanished (give up = success) or superseded by a newer barrier
            } else {
                logger.warn("Coordinator status update exhausted retries — failing shard", cause);
                b.future.completeExceptionally(cause); // -> SM transitions to FAILING (unchanged contract)
            }
        }
    }

    private void sendHeartbeat() {
        try {
            sendOnce().get(perAttemptTimeoutMs, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (ExecutionException | TimeoutException ignored) {
            // Best-effort liveness; the next poll is the retry.
        }
    }

    /** Perform one status send, built at call time from the current progress snapshot. */
    private CompletableFuture<Void> sendOnce() {
        UpdateShardMigrationStatusRequest request = new UpdateShardMigrationStatusRequest(
            new UpdateShardMigrationStatusBody(migrationId, shardId, snapshot.get())
        );
        return AsyncClientHelper.executeAsync(client, UpdateShardMigrationStatusAction.INSTANCE, request).thenApply(ignored -> null);
    }

    private void failPending() {
        for (Barrier b; (b = mailbox.poll()) != null;) {
            b.future.completeExceptionally(new IllegalStateException("shard status reporter closed"));
        }
    }

    /** A "Batcher closed" failure means the coordinator stopped tracking this migration — not retryable. */
    private static boolean isCoordinatorClosed(Throwable ex) {
        for (Throwable cause = ex; cause != null; cause = cause.getCause()) {
            String msg = cause.getMessage();
            if (msg != null && msg.contains("Batcher closed")) {
                return true;
            }
        }
        return false;
    }

    @Override
    public void close() {
        closed = true;
        executor.shutdownNow(); // interrupts the poll / blocking get; failPending() unblocks the SM
    }
}
