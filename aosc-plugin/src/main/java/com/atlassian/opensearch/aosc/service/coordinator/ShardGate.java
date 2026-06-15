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

import org.opensearch.core.index.shard.ShardId;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;

/**
 * Tracks shard convergence toward a target phase.
 *
 * <p>A ShardGate completes when ALL expected shards report the target phase (or beyond),
 * or immediately when ANY shard reports FAILED (fail-fast). The coordinator creates one
 * gate per waiting-phase (e.g., wait for all shards CONVERGED before cutting over).</p>
 *
 * <h2>Thread safety</h2>
 *
 * <p>Thread-safe via {@link ConcurrentHashMap} + {@link CompletableFuture#complete} atomicity.
 * Can be called from transport threads, executor threads, or cluster state applier threads.
 * Only the first call to complete the gate wins — subsequent calls are no-ops.</p>
 *
 * <h2>CM failover</h2>
 *
 * <p>Use {@link #preFill} to seed the gate from durable cluster state after failover.
 * If all shards were already at the target phase, the gate completes immediately.</p>
 */
public class ShardGate {

    private final AoscLogger logger;

    public enum Outcome {
        ALL_REACHED_TARGET,
        SHARD_FAILED,
        CANCELLED
    }

    /** Result of gate completion. */
    public static class GateResult {
        private final Outcome outcome;
        private final Map<ShardId, ShardPhase> shardStates;
        private final ShardId failedShard;
        private final String failureReason;

        public GateResult(Outcome outcome, Map<ShardId, ShardPhase> shardStates, ShardId failedShard, String failureReason) {
            this.outcome = outcome;
            this.shardStates = shardStates;
            this.failedShard = failedShard;
            this.failureReason = failureReason;
        }

        public Outcome outcome() {
            return outcome;
        }

        public Map<ShardId, ShardPhase> shardStates() {
            return shardStates;
        }

        public ShardId failedShard() {
            return failedShard;
        }

        public String failureReason() {
            return failureReason;
        }

        /** True if any shard reported FAILED, regardless of gate outcome. */
        public boolean hasAnyFailed() {
            return shardStates.values().stream().anyMatch(p -> p == ShardPhase.FAILED);
        }
    }

    /** Happy-path phases in progression order. Terminal/interrupt phases excluded. */
    static final List<ShardPhase> HAPPY_PATH = List.of(
        ShardPhase.PENDING,
        ShardPhase.ACQUIRING_LEASE,
        ShardPhase.BACKFILLING,
        ShardPhase.REPLAYING,
        ShardPhase.CONVERGING,
        ShardPhase.CONVERGED,
        ShardPhase.CATCHING_UP,
        ShardPhase.COMPLETING,
        ShardPhase.COMPLETED
    );

    /** Pre-built checker: accepts any phase at or beyond the given happy-path phase. */
    static Predicate<ShardPhase> atOrBeyond(ShardPhase target) {
        int targetIdx = HAPPY_PATH.indexOf(target);
        if (targetIdx < 0) throw new IllegalArgumentException("Not a happy-path phase: " + target);
        return phase -> {
            int idx = HAPPY_PATH.indexOf(phase);
            return idx >= 0 && idx >= targetIdx;
        };
    }

    /** Pre-built checker: accepts any terminal shard phase (COMPLETED, CANCELLED, FAILED). */
    static Predicate<ShardPhase> anyTerminal() {
        return ShardPhase::isTerminal;
    }

    private final Set<ShardId> expectedShards;
    private final Predicate<ShardPhase> targetCheck;
    // Whether FAILED is a fast-fail (unexpected) or an acceptable terminal outcome
    private final boolean failedIsFastFail;
    private final CompletableFuture<GateResult> future = new CompletableFuture<>();
    private final ConcurrentHashMap<ShardId, ShardPhase> reported = new ConcurrentHashMap<>();

    /**
     * @param expectedShards  shards this gate is waiting for
     * @param targetCheck     predicate that returns true when a shard phase satisfies the gate
     * @param failedIsFastFail  if true, a FAILED shard immediately completes the gate with SHARD_FAILED;
     *                          if false, FAILED is treated as just another phase (accepted by anyTerminal())
     */
    public ShardGate(AoscLogger logger, Set<ShardId> expectedShards, Predicate<ShardPhase> targetCheck, boolean failedIsFastFail) {
        this.logger = Objects.requireNonNull(logger, "logger").forClass(ShardGate.class);
        this.expectedShards = Set.copyOf(expectedShards);
        this.targetCheck = targetCheck;
        this.failedIsFastFail = failedIsFastFail;
        if (expectedShards.isEmpty()) {
            future.complete(new GateResult(Outcome.ALL_REACHED_TARGET, Map.of(), null, null));
        }
    }

    /** Convenience constructor for happy-path gates (FAILED is a fast-fail). */
    public ShardGate(AoscLogger logger, Set<ShardId> expectedShards, ShardPhase targetPhase) {
        this(logger, expectedShards, atOrBeyond(targetPhase), true);
    }

    /**
     * Report a shard's current phase.
     *
     * <p>On happy-path gates, a FAILED shard completes the gate immediately with SHARD_FAILED.
     * On terminal gates, FAILED is accepted as a valid terminal outcome.
     * If all shards satisfy the checker, the gate completes with ALL_REACHED_TARGET.</p>
     */
    public void shardReported(ShardId shard, ShardPhase phase, String failureReason) {
        if (future.isDone()) return;

        reported.put(shard, phase);

        if (phase == ShardPhase.FAILED && failedIsFastFail) {
            logger.debug("Gate: shard {} reported FAILED — completing with SHARD_FAILED", shard);
            future.complete(new GateResult(Outcome.SHARD_FAILED, Map.copyOf(reported), shard, failureReason));
            return;
        }

        if (allReachedTarget()) {
            logger.debug("Gate: all {} shards satisfied target — completing", expectedShards.size());
            future.complete(new GateResult(Outcome.ALL_REACHED_TARGET, Map.copyOf(reported), null, null));
        }
    }

    /** Cancel the gate (e.g., migration cancelled or SM overridden). */
    public void cancel() {
        future.complete(new GateResult(Outcome.CANCELLED, Map.copyOf(reported), null, null));
    }

    /**
     * Seed the gate from durable state (CM failover reconstruction).
     * If all shards already satisfy the target, the gate completes immediately.
     */
    public void preFill(Map<ShardId, ShardPhase> existingStates) {
        for (var entry : existingStates.entrySet()) {
            shardReported(entry.getKey(), entry.getValue(), null);
        }
    }

    /** The future to await. Completes when the gate condition is met. */
    public CompletableFuture<GateResult> awaitable() {
        return future;
    }

    /** True if any shard reported FAILED. */
    public boolean hasAnyFailed() {
        return reported.values().stream().anyMatch(p -> p == ShardPhase.FAILED);
    }

    /** Number of shards that have satisfied the target check. */
    public int convergedCount() {
        return (int) expectedShards.stream().filter(s -> {
            ShardPhase p = reported.get(s);
            return p != null && targetCheck.test(p);
        }).count();
    }

    /** Total expected shards. */
    public int expectedCount() {
        return expectedShards.size();
    }

    /** True if the gate's future has already completed. */
    public boolean isDone() {
        return future.isDone();
    }

    private boolean allReachedTarget() {
        return expectedShards.stream().allMatch(s -> {
            ShardPhase p = reported.get(s);
            return p != null && targetCheck.test(p);
        });
    }
}
