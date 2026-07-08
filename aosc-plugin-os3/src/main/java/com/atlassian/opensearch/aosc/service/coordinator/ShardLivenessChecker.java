/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */
package com.atlassian.opensearch.aosc.service.coordinator;

import com.atlassian.opensearch.aosc.model.phase.ShardPhase;
import com.atlassian.opensearch.aosc.utils.AoscLogger;

import org.opensearch.common.unit.TimeValue;
import org.opensearch.core.index.shard.ShardId;
import org.opensearch.threadpool.ThreadPool;

import java.io.Closeable;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * Tracks per-shard heartbeat timestamps and periodically checks for stale shards.
 *
 * <p>Combines heartbeat tracking with scheduled liveness checks. A shard is considered
 * stale if its last heartbeat is older than the configured timeout. Stale shards are
 * reported as FAILED to the shard gate, triggering the coordinator's fail-fast path.</p>
 *
 * <p>After CM failover, a grace period prevents false positives while shards discover
 * the new coordinator.</p>
 *
 * <p>Thread-safe: heartbeatReceived() can be called from any thread.</p>
 */
class ShardLivenessChecker implements Closeable {

    private final AoscLogger logger;

    private final Map<ShardId, Instant> lastSeen = new ConcurrentHashMap<>();
    private final Clock clock;
    private final TimeValue timeout;
    private final ThreadPool.Cancellable checkTask;
    private final Supplier<ShardGate> gateSupplier;
    private volatile Instant graceExpiresAt;

    ShardLivenessChecker(
        AoscLogger logger,
        ThreadPool threadPool,
        Supplier<ShardGate> gateSupplier,
        TimeValue checkInterval,
        TimeValue timeout
    ) {
        this(logger, threadPool, gateSupplier, checkInterval, timeout, Clock.systemUTC());
    }

    /** Package-private constructor with injectable clock (for testing). */
    ShardLivenessChecker(
        AoscLogger logger,
        ThreadPool threadPool,
        Supplier<ShardGate> gateSupplier,
        TimeValue checkInterval,
        TimeValue timeout,
        Clock clock
    ) {
        this.logger = Objects.requireNonNull(logger, "logger").forClass(ShardLivenessChecker.class);
        this.gateSupplier = gateSupplier;
        this.timeout = timeout;
        this.clock = clock;
        this.graceExpiresAt = Instant.MIN;
        this.checkTask = threadPool.scheduleWithFixedDelay(this::check, checkInterval, ThreadPool.Names.GENERIC);
    }

    /** Record a heartbeat from a shard. Thread-safe. */
    void heartbeatReceived(ShardId shard) {
        lastSeen.put(shard, clock.instant());
    }

    /**
     * Set a grace period during which no shards are reported as stale.
     * Used after CM failover to give shards time to re-report.
     */
    void setGracePeriod(Duration grace) {
        this.graceExpiresAt = clock.instant().plus(grace);
    }

    /**
     * Returns shards whose last heartbeat is older than the given timeout.
     * Returns empty set during grace period.
     */
    Set<ShardId> getStaleShardsOlderThan(Duration staleness) {
        Instant now = clock.instant();
        if (now.isBefore(graceExpiresAt)) {
            return Set.of();
        }
        Instant cutoff = now.minus(staleness);
        return lastSeen.entrySet()
            .stream()
            .filter(e -> e.getValue().isBefore(cutoff))
            .map(Map.Entry::getKey)
            .collect(Collectors.toUnmodifiableSet());
    }

    /** Number of shards being tracked. */
    int trackedCount() {
        return lastSeen.size();
    }

    private void check() {
        try {
            Set<ShardId> stale = getStaleShardsOlderThan(Duration.ofMillis(timeout.millis()));

            ShardGate gate = gateSupplier.get();
            if (gate == null) {
                return;
            }

            if (!stale.isEmpty()) {
                logger.warn("Found {} stale shards (heartbeat timeout after {})", stale.size(), timeout);
                for (ShardId shardId : stale) {
                    gate.shardReported(shardId, ShardPhase.FAILED, "heartbeat timeout after " + timeout);
                }
            }
        } catch (Exception e) {
            logger.warn("Liveness check failed", e);
        }
    }

    @Override
    public void close() {
        checkTask.cancel();
    }
}
