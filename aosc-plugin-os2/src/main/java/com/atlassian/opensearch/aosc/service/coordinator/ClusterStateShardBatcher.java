/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */
package com.atlassian.opensearch.aosc.service.coordinator;

import com.atlassian.opensearch.aosc.model.AoscMigrationsClusterState;
import com.atlassian.opensearch.aosc.model.ShardProgressDocument;
import com.atlassian.opensearch.aosc.utils.AoscLogger;

import org.opensearch.common.unit.TimeValue;
import org.opensearch.threadpool.ThreadPool;

import java.io.Closeable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Micro-batches shard phase updates into single cluster state writes.
 *
 * <p>Instead of writing one CS update per shard report, queues updates and flushes
 * them periodically (default 100ms). Callers get a future that completes when their
 * update is durably written to cluster state.</p>
 *
 * <p>Uses {@code scheduleWithFixedDelay} for predictable batching. Empty flushes are
 * a single {@code isEmpty()} check — negligible cost vs the 50-100ms CS write.</p>
 *
 * <h2>Thread safety</h2>
 *
 * <p>{@link #queueUpdate} can be called from any thread. {@link #flush} drains via
 * per-key {@link ConcurrentHashMap#remove}, which is atomic per entry. A concurrent
 * {@code queueUpdate} during flush either lands in the current batch (if its key is
 * visited before the iterator passes it) or stays for the next flush — no lost updates.</p>
 */
public class ClusterStateShardBatcher implements Closeable {

    private final AoscLogger logger;
    static final long DEFAULT_FLUSH_INTERVAL_MS = 100;

    private final String migrationId;
    private final ClusterStateUpdateHelper csHelper;
    private final ThreadPool.Cancellable flushTask;

    private final ConcurrentHashMap<Integer, PendingUpdate> pending = new ConcurrentHashMap<>();
    private volatile boolean closed;

    private static class PendingUpdate {
        final int shardId;
        final ShardProgressDocument progress;
        final CompletableFuture<Void> future;

        PendingUpdate(int shardId, ShardProgressDocument progress, CompletableFuture<Void> future) {
            this.shardId = shardId;
            this.progress = progress;
            this.future = future;
        }
    }

    public ClusterStateShardBatcher(AoscLogger logger, String migrationId, ClusterStateUpdateHelper csHelper, ThreadPool threadPool) {
        this(logger, migrationId, csHelper, threadPool, DEFAULT_FLUSH_INTERVAL_MS);
    }

    public ClusterStateShardBatcher(
        AoscLogger logger,
        String migrationId,
        ClusterStateUpdateHelper csHelper,
        ThreadPool threadPool,
        long flushIntervalMs
    ) {
        this.logger = Objects.requireNonNull(logger, "logger").forClass(ClusterStateShardBatcher.class);
        this.migrationId = migrationId;
        this.csHelper = csHelper;
        this.flushTask = threadPool.scheduleWithFixedDelay(
            this::flush,
            TimeValue.timeValueMillis(flushIntervalMs),
            ThreadPool.Names.GENERIC
        );
    }

    /**
     * Queue a shard progress update. Returns a future that completes when the update
     * is durably written to cluster state (possibly batched with others).
     *
     * <p>If a previous update for the same shard is still pending, the new update
     * replaces it (latest wins). The old future is chained to the new one so both
     * settle when the batch commits.</p>
     */
    public CompletableFuture<Void> queueUpdate(int shardId, ShardProgressDocument progress) {
        if (closed) {
            return CompletableFuture.failedFuture(new IllegalStateException("Batcher closed"));
        }

        CompletableFuture<Void> future = new CompletableFuture<>();
        PendingUpdate old = pending.put(shardId, new PendingUpdate(shardId, progress, future));
        if (old != null) {
            // Chain old future to new — both settle when the batch writes
            future.whenComplete((v, ex) -> {
                if (ex != null) {
                    old.future.completeExceptionally(ex);
                } else {
                    old.future.complete(null);
                }
            });
        }

        return future;
    }

    /**
     * Drain pending updates via per-key remove and write to cluster state in one batch.
     * Runs on the fixed-interval schedule. No-op if nothing is pending.
     * Visible for testing.
     */
    void flush() {
        if (pending.isEmpty()) {
            return;
        }

        // Drain via per-key remove — atomic per entry, no lost updates.
        Map<Integer, PendingUpdate> batch = new HashMap<>();
        for (Integer key : pending.keySet()) {
            PendingUpdate removed = pending.remove(key);
            if (removed != null) {
                batch.put(key, removed);
            }
        }

        if (batch.isEmpty()) {
            return;
        }

        // Build the shard status map for the helper
        Map<Integer, AoscMigrationsClusterState.ShardMigrationClusterState> shardUpdates = new HashMap<>();
        List<CompletableFuture<Void>> futures = new ArrayList<>();
        for (var entry : batch.entrySet()) {
            PendingUpdate update = entry.getValue();
            ShardProgressDocument p = update.progress;
            shardUpdates.put(
                update.shardId,
                AoscMigrationsClusterState.ShardMigrationClusterState.builder()
                    .phase(p.phase())
                    .lastReplayedSeqNo(p.lastReplayedSeqNo())
                    .backfillCutoffSeqNo(p.backfillCutoffSeqNo())
                    .failure(p.error())
                    .meta(p.meta())
                    .build()
            );
            futures.add(update.future);
        }

        logger.debug("Flushing batch of {} shard updates", shardUpdates.size());

        csHelper.flushPendingUpdates(migrationId, shardUpdates).whenComplete((result, ex) -> {
            if (ex != null) {
                logger.warn("Batch shard update failed: {}", ex.getMessage());
                for (CompletableFuture<Void> f : futures) {
                    f.completeExceptionally(ex);
                }
            } else {
                logger.debug("Batch of {} shard updates committed", shardUpdates.size());
                for (CompletableFuture<Void> f : futures) {
                    f.complete(null);
                }
            }
        });
    }

    /** Number of pending (unflushed) updates. Visible for testing. */
    int pendingCount() {
        return pending.size();
    }

    @Override
    public void close() {
        closed = true;
        flushTask.cancel();
        // Flush any remaining pending updates before closing
        flush();
    }
}
