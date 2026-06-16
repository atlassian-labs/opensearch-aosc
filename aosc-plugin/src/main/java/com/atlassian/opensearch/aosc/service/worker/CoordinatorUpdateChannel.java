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
import com.atlassian.opensearch.aosc.model.phase.ShardPhase;
import com.atlassian.opensearch.aosc.utils.AoscLogger;
import com.atlassian.opensearch.aosc.utils.AsyncClientHelper;
import com.atlassian.opensearch.aosc.utils.AsyncUtils;

import org.opensearch.client.Client;
import org.opensearch.threadpool.ThreadPool;

import java.io.Closeable;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

/**
 * The single path a shard worker uses to send status updates to the coordinator. Owns both the
 * periodic heartbeat (tick) and the write-barrier (phase-transition) updates.
 *
 * <p><b>Design — completion-driven single-flight gate.</b> At most one transport request is in
 * flight; the next send is kicked off from the previous send's completion callback (so sends run
 * on transport-completion threads, not a dedicated executor). The {@link ShardProgressDocument} is
 * built <em>at send time</em> from the worker's current state, so each send carries the current,
 * only-advancing phase — updates therefore leave the worker in monotonic order with no merge,
 * coalescing slot, or per-worker thread.</p>
 *
 * <ul>
 *   <li><b>Blocking write barrier:</b> {@link #reportTransition} returns a future the state machine
 *       awaits; it completes when a send carrying the target phase is ack'd, and fails on give-up
 *       (→ the SM transitions to FAILING). The SM blocks on it, so {@code currentState} is pinned to
 *       the target for the whole send — hence the simple exact-match completion.</li>
 *   <li><b>Timely heartbeats:</b> each send is one attempt, bounded by the request's cluster-manager
 *       timeout. On failure the gate is freed and the send is retried with exponential backoff; the
 *       gate is never held during a backoff, so the periodic heartbeat keeps probing meanwhile and a
 *       stuck/backing-off send cannot starve liveness.</li>
 * </ul>
 *
 * <p>The coordinator's monotonic guard remains the restart-/failover-safe backstop; this channel only
 * removes intra-worker reorder.</p>
 */
final class CoordinatorUpdateChannel implements Closeable {

    /** A blocking write-barrier waiter and the phase whose ack should complete it. */
    private static final class Pending {
        final CompletableFuture<Void> future;
        final ShardPhase target;

        Pending(CompletableFuture<Void> future, ShardPhase target) {
            this.future = future;
            this.target = target;
        }
    }

    private final AoscLogger logger;
    private final Client client;
    private final ThreadPool threadPool;
    private final String migrationId;
    private final int shardId;
    private final Supplier<ShardProgressDocument> snapshotSupplier;
    private final long retryBaseDelayMs;
    private final int maxConsecutiveFailures;

    private final AtomicBoolean inFlight = new AtomicBoolean(false);
    private final AtomicBoolean resendRequested = new AtomicBoolean(false);
    private final AtomicReference<Pending> barrier = new AtomicReference<>();
    // Touched only inside onSendComplete, which is serialized by the single-flight gate.
    private int consecutiveFailures;
    private volatile boolean closed;

    CoordinatorUpdateChannel(
        AoscLogger logger,
        Client client,
        ThreadPool threadPool,
        String migrationId,
        int shardId,
        Supplier<ShardProgressDocument> snapshotSupplier,
        long retryBaseDelayMs,
        int maxConsecutiveFailures
    ) {
        this.logger = Objects.requireNonNull(logger, "logger").forClass(CoordinatorUpdateChannel.class);
        this.client = Objects.requireNonNull(client, "client");
        this.threadPool = Objects.requireNonNull(threadPool, "threadPool");
        this.migrationId = Objects.requireNonNull(migrationId, "migrationId");
        this.shardId = shardId;
        this.snapshotSupplier = Objects.requireNonNull(snapshotSupplier, "snapshotSupplier");
        this.retryBaseDelayMs = retryBaseDelayMs;
        this.maxConsecutiveFailures = maxConsecutiveFailures;
    }

    /**
     * Write-barrier path (BLOCKING). The SM has already set {@code currentState == targetPhase} and
     * awaits the returned future. Completes when the target phase is durably ack'd; fails on give-up.
     */
    CompletableFuture<Void> reportTransition(ShardPhase targetPhase) {
        CompletableFuture<Void> waiter = new CompletableFuture<>();
        if (closed) {
            waiter.completeExceptionally(new IllegalStateException("coordinator update channel closed"));
            return waiter;
        }
        // At most one barrier is pending (the SM blocks on it). A new one arriving while another is
        // pending only happens on an interrupt (cancel/fail) — supersede the stale one.
        Pending stale = barrier.getAndSet(new Pending(waiter, targetPhase));
        if (stale != null) {
            stale.future.complete(null);
        }
        requestSend();
        return waiter;
    }

    /** Tick path — fire-and-forget liveness heartbeat + fresh metrics. */
    void heartbeat() {
        requestSend();
    }

    private void requestSend() {
        resendRequested.set(true);
        tryStart();
    }

    private void tryStart() {
        if (closed) {
            return;
        }
        if (inFlight.compareAndSet(false, true)) {
            resendRequested.set(false);
            send();
        }
    }

    private void send() {
        ShardProgressDocument doc = snapshotSupplier.get(); // built at send time -> current phase
        UpdateShardMigrationStatusRequest req = new UpdateShardMigrationStatusRequest(
            new UpdateShardMigrationStatusBody(migrationId, shardId, doc)
        );
        // Each attempt is bounded by the request's own cluster-manager timeout (default ~30s); on
        // failure we retry with exponential backoff and, after maxConsecutiveFailures, give up -> FAILING.
        AsyncClientHelper.executeAsync(client, UpdateShardMigrationStatusAction.INSTANCE, req)
            .whenComplete((resp, ex) -> onSendComplete(doc.phase(), ex));
    }

    private void onSendComplete(ShardPhase sentPhase, Throwable ex) {
        inFlight.set(false);
        if (ex == null) {
            consecutiveFailures = 0;
            completeBarrierIfReached(sentPhase);
        } else if (isNonRetryable(ex)) { // coordinator gone for this migration — give up = success (as before)
            consecutiveFailures = 0;
            settleBarrier(null);
        } else if (++consecutiveFailures >= maxConsecutiveFailures || closed) {
            consecutiveFailures = 0;
            logger.warn("Shard status delivery exhausted retries — failing transition", ex);
            settleBarrier(ex); // -> barrier future fails -> SM transitions to FAILING (unchanged contract)
        } else {
            // Exponential backoff between explicit retries (e.g. 5s, 10s, 20s … capped at 64x). The gate
            // is freed during the delay, so the periodic heartbeat keeps liveness alive meanwhile.
            long delay = retryBaseDelayMs * (1L << Math.min(consecutiveFailures - 1, 6));
            AsyncUtils.scheduleDelayed(threadPool, delay, this::requestSend);
            return;
        }
        if (resendRequested.get()) {
            tryStart(); // drain a queued heartbeat/transition; runs on this transport-completion thread
        }
    }

    /** Complete the pending barrier iff the ack'd phase is the one it is waiting for. */
    private void completeBarrierIfReached(ShardPhase sentPhase) {
        Pending p = barrier.get();
        if (p != null && sentPhase == p.target && barrier.compareAndSet(p, null)) {
            p.future.complete(null);
        }
    }

    /** Settle (complete or fail) whatever barrier is pending — used for give-up and close. */
    private void settleBarrier(Throwable failureOrNull) {
        Pending p = barrier.getAndSet(null);
        if (p == null) {
            return;
        }
        if (failureOrNull == null) {
            p.future.complete(null);
        } else {
            p.future.completeExceptionally(failureOrNull);
        }
    }

    private static boolean isNonRetryable(Throwable ex) {
        Throwable cause = ex;
        while (cause != null) {
            String msg = cause.getMessage();
            if (msg != null && msg.contains("Batcher closed")) {
                return true;
            }
            cause = cause.getCause();
        }
        return false;
    }

    @Override
    public void close() {
        closed = true;
        settleBarrier(new IllegalStateException("coordinator update channel closed"));
    }
}
