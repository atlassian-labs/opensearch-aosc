/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */
package com.atlassian.opensearch.aosc.service.worker;

import com.atlassian.opensearch.aosc.utils.AoscLogger;

import org.opensearch.common.util.concurrent.FutureUtils;

import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.Objects;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

/**
 * Controls how many shard workers on this node may simultaneously be in
 * the heavy-work portion of the migration pipeline (ACQUIRING_LEASE through
 * CONVERGING). Workers beyond the limit park in PENDING until a permit is
 * released.
 *
 * <p>Each permit is tracked by a holder key ({@code migrationId::shardId}).
 * The set of holders is the single source of truth — there is no separate
 * counter. {@link #release} is idempotent: releasing a permit that is not
 * held is a no-op. Acquiring a permit that is already held throws.</p>
 *
 * <p>Implementation uses a simple set + FIFO queue guarded by {@code synchronized}.
 * Contention is negligible — methods are called only on phase transitions,
 * not per-document.</p>
 */
public class BackfillPermitManager {

    private final AoscLogger logger;

    private int maxPermits;
    private final Set<String> holders = new HashSet<>();
    private final Queue<Waiter> waitQueue = new LinkedList<>();
    /** Keys that were released before being granted — grantWaiters() skips these. */
    private final Set<String> dead = new HashSet<>();

    public BackfillPermitManager(AoscLogger logger, int maxPermits) {
        this.logger = Objects.requireNonNull(logger, "logger").forClass(BackfillPermitManager.class);
        if (maxPermits < 0) throw new IllegalArgumentException("maxPermits must be >= 0: " + maxPermits);
        this.maxPermits = maxPermits;
    }

    /**
     * Request a backfill permit. Returns a future that completes immediately if
     * a permit is available, or queues in FIFO order until one is released.
     *
     * @throws IllegalStateException if a permit is already held for this migration+shard
     */
    public synchronized CompletableFuture<Void> acquire(String migrationId, int shardId) {
        String key = key(migrationId, shardId);
        if (holders.contains(key)) {
            throw new IllegalStateException("Permit already held for [" + key + "]");
        }
        if (holders.size() < maxPermits) {
            holders.add(key);
            logger.info("Permit granted for [{}] ({}/{})", key, holders.size(), maxPermits);
            return CompletableFuture.completedFuture(null);
        }
        logger.info("Permit queued for [{}] ({}/{} in use, {} waiting)", key, holders.size(), maxPermits, waitQueue.size() + 1);
        CompletableFuture<Void> waitFuture = new CompletableFuture<>();
        waitQueue.add(new Waiter(key, waitFuture));
        return waitFuture;
    }

    /**
     * Release a permit. Idempotent — releasing a permit that is not held is a
     * no-op. If waiters are queued, the next non-cancelled one is granted.
     */
    public synchronized void release(String migrationId, int shardId) {
        String key = key(migrationId, shardId);
        if (!holders.remove(key)) {
            // Permit not held yet — the waiter may still be in the queue. Mark as
            // dead so grantWaiters() skips it instead of granting to a dead worker.
            dead.add(key);
            logger.debug("Permit pre-released for [{}] — marked dead", key);
            return;
        }
        logger.debug("Permit released for [{}] ({}/{})", key, holders.size(), maxPermits);
        grantWaiters();
    }

    /**
     * Release all permits whose key starts with the given migration ID prefix,
     * and cancel any queued waiters for that migration. Called when a migration
     * reaches a terminal phase as a safety net.
     */
    public synchronized void releaseAllForMigration(String migrationId) {
        String prefix = migrationId + "::";
        int removed = 0;
        Iterator<String> it = holders.iterator();
        while (it.hasNext()) {
            if (it.next().startsWith(prefix)) {
                it.remove();
                removed++;
            }
        }
        Iterator<Waiter> wit = waitQueue.iterator();
        int cancelled = 0;
        while (wit.hasNext()) {
            Waiter w = wit.next();
            if (w.key.startsWith(prefix)) {
                FutureUtils.cancel(w.future);
                wit.remove();
                cancelled++;
            }
        }
        if (removed > 0 || cancelled > 0) {
            logger.info(
                "Bulk cleanup for migration [{}]: released {} permits, cancelled {} waiters ({}/{} now held)",
                migrationId,
                removed,
                cancelled,
                holders.size(),
                maxPermits
            );
            grantWaiters();
        }
    }

    /**
     * Update the maximum number of permits dynamically (cluster setting change).
     * Increasing immediately grants permits to queued waiters. Decreasing does not
     * revoke permits — the count drains naturally as workers release.
     */
    public synchronized void updateMaxPermits(int newMax) {
        if (newMax < 0) throw new IllegalArgumentException("maxPermits must be >= 0: " + newMax);
        int oldMax = this.maxPermits;
        this.maxPermits = newMax;
        logger.info("Max permits updated: {} -> {} (held={}, queued={})", oldMax, newMax, holders.size(), waitQueue.size());
        grantWaiters();
    }

    /** Cancel all queued waiters (e.g. on node shutdown). */
    public synchronized void cancelWaiters() {
        int cancelled = 0;
        Waiter waiter;
        while ((waiter = waitQueue.poll()) != null) {
            FutureUtils.cancel(waiter.future);
            cancelled++;
        }
        if (cancelled > 0) {
            logger.info("Cancelled {} queued permit waiters", cancelled);
        }
    }

    /**
     * Emergency reset — clears all held permits and queued waiters.
     * Use only for operational recovery (e.g. after confirmed permit leak).
     */
    public synchronized void forceReset() {
        logger.warn("Force-resetting permit manager: held={}, queued={}, dead={}", holders.size(), waitQueue.size(), dead.size());
        cancelWaiters();
        holders.clear();
        dead.clear();
    }

    /**
     * Grant permits to queued waiters while headroom is available.
     * Skips cancelled waiters without consuming a permit.
     * Must be called while holding the monitor.
     */
    private void grantWaiters() {
        while (holders.size() < maxPermits && !waitQueue.isEmpty()) {
            Waiter next = waitQueue.poll();
            if (next.future.isCancelled()) continue;
            if (dead.remove(next.key)) {
                // Worker already released before being granted — skip
                logger.debug("Skipping dead waiter [{}]", next.key);
                FutureUtils.cancel(next.future);
                continue;
            }
            holders.add(next.key);
            next.future.complete(null);
        }
    }

    /** Number of permits currently held. */
    public synchronized int issuedCount() {
        return holders.size();
    }

    /** Number of waiters in the queue. */
    public synchronized int queueSize() {
        return waitQueue.size();
    }

    /** Current max permits setting. */
    public synchronized int maxPermits() {
        return maxPermits;
    }

    private static String key(String migrationId, int shardId) {
        return migrationId + "::" + shardId;
    }

    /** Internal record for queued permit requests. */
    private static class Waiter {
        final String key;
        final CompletableFuture<Void> future;

        Waiter(String key, CompletableFuture<Void> future) {
            this.key = key;
            this.future = future;
        }
    }
}
