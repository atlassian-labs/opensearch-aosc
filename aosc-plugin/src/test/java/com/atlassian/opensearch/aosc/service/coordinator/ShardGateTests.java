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
import org.opensearch.test.OpenSearchTestCase;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;

public class ShardGateTests extends OpenSearchTestCase {

    private static final ShardId SHARD_0 = new ShardId("source", "_na_", 0);
    private static final ShardId SHARD_1 = new ShardId("source", "_na_", 1);
    private static final ShardId SHARD_2 = new ShardId("source", "_na_", 2);

    // 1. All shards report target → completes with ALL_REACHED_TARGET
    public void testAllReachedTarget() throws Exception {
        ShardGate gate = new ShardGate(AoscLogger.create(ShardGate.class), Set.of(SHARD_0, SHARD_1, SHARD_2), ShardPhase.CONVERGED);
        assertFalse(gate.isDone());
        assertEquals(0, gate.convergedCount());

        gate.shardReported(SHARD_0, ShardPhase.CONVERGED, null);
        assertFalse(gate.isDone());
        assertEquals(1, gate.convergedCount());

        gate.shardReported(SHARD_1, ShardPhase.CONVERGED, null);
        assertFalse(gate.isDone());
        assertEquals(2, gate.convergedCount());

        gate.shardReported(SHARD_2, ShardPhase.CONVERGED, null);
        assertTrue(gate.isDone());
        assertEquals(3, gate.convergedCount());

        ShardGate.GateResult result = gate.awaitable().get(1, TimeUnit.SECONDS);
        assertEquals(ShardGate.Outcome.ALL_REACHED_TARGET, result.outcome());
        assertNull(result.failedShard());
        assertNull(result.failureReason());
        assertFalse(result.hasAnyFailed());
    }

    // 2. One shard fails → completes immediately with SHARD_FAILED
    public void testShardFailedFailFast() throws Exception {
        ShardGate gate = new ShardGate(AoscLogger.create(ShardGate.class), Set.of(SHARD_0, SHARD_1, SHARD_2), ShardPhase.CONVERGED);

        gate.shardReported(SHARD_0, ShardPhase.CONVERGED, null);
        assertFalse(gate.isDone());

        gate.shardReported(SHARD_1, ShardPhase.FAILED, "IOException on shard 1");
        assertTrue(gate.isDone());

        ShardGate.GateResult result = gate.awaitable().get(1, TimeUnit.SECONDS);
        assertEquals(ShardGate.Outcome.SHARD_FAILED, result.outcome());
        assertEquals(SHARD_1, result.failedShard());
        assertEquals("IOException on shard 1", result.failureReason());
        assertTrue(result.hasAnyFailed());
    }

    // 3. cancel() → completes with CANCELLED
    public void testCancel() throws Exception {
        ShardGate gate = new ShardGate(AoscLogger.create(ShardGate.class), Set.of(SHARD_0, SHARD_1), ShardPhase.CONVERGED);
        gate.shardReported(SHARD_0, ShardPhase.CONVERGED, null);
        assertFalse(gate.isDone());

        gate.cancel();
        assertTrue(gate.isDone());

        ShardGate.GateResult result = gate.awaitable().get(1, TimeUnit.SECONDS);
        assertEquals(ShardGate.Outcome.CANCELLED, result.outcome());
    }

    // 4. preFill with all shards already converged → completes immediately
    public void testPreFillCompletesImmediately() throws Exception {
        ShardGate gate = new ShardGate(AoscLogger.create(ShardGate.class), Set.of(SHARD_0, SHARD_1), ShardPhase.CONVERGED);

        gate.preFill(Map.of(SHARD_0, ShardPhase.CONVERGED, SHARD_1, ShardPhase.CONVERGED));
        assertTrue(gate.isDone());

        ShardGate.GateResult result = gate.awaitable().get(1, TimeUnit.SECONDS);
        assertEquals(ShardGate.Outcome.ALL_REACHED_TARGET, result.outcome());
    }

    // 5. preFill partially, then remaining shards report
    public void testPreFillPartialThenComplete() throws Exception {
        ShardGate gate = new ShardGate(AoscLogger.create(ShardGate.class), Set.of(SHARD_0, SHARD_1, SHARD_2), ShardPhase.CONVERGED);

        gate.preFill(Map.of(SHARD_0, ShardPhase.CONVERGED, SHARD_1, ShardPhase.BACKFILLING));
        assertFalse(gate.isDone());
        assertEquals(1, gate.convergedCount());

        gate.shardReported(SHARD_1, ShardPhase.CONVERGED, null);
        assertFalse(gate.isDone());

        gate.shardReported(SHARD_2, ShardPhase.CONVERGED, null);
        assertTrue(gate.isDone());
    }

    // 6. shardReported after gate completed → no-op
    public void testReportAfterDoneIsNoOp() throws Exception {
        ShardGate gate = new ShardGate(AoscLogger.create(ShardGate.class), Set.of(SHARD_0), ShardPhase.CONVERGED);
        gate.shardReported(SHARD_0, ShardPhase.CONVERGED, null);
        assertTrue(gate.isDone());

        ShardGate.GateResult first = gate.awaitable().get(1, TimeUnit.SECONDS);
        assertEquals(ShardGate.Outcome.ALL_REACHED_TARGET, first.outcome());

        // Late report — should not change outcome
        gate.shardReported(SHARD_0, ShardPhase.FAILED, "late failure");
        ShardGate.GateResult second = gate.awaitable().get(1, TimeUnit.SECONDS);
        assertSame(first, second);
    }

    // 7. Phase beyond target satisfies gate (e.g., COMPLETED satisfies CONVERGED gate)
    public void testPhaseBeyondTargetSatisfies() throws Exception {
        ShardGate gate = new ShardGate(AoscLogger.create(ShardGate.class), Set.of(SHARD_0, SHARD_1), ShardPhase.CONVERGED);

        gate.shardReported(SHARD_0, ShardPhase.CONVERGED, null);
        gate.shardReported(SHARD_1, ShardPhase.COMPLETED, null); // beyond CONVERGED
        assertTrue(gate.isDone());

        ShardGate.GateResult result = gate.awaitable().get(1, TimeUnit.SECONDS);
        assertEquals(ShardGate.Outcome.ALL_REACHED_TARGET, result.outcome());
    }

    // 8. CANCELLING/FAILING do NOT satisfy gate (they're error paths, not progress)
    public void testCancellingDoesNotSatisfy() {
        ShardGate gate = new ShardGate(AoscLogger.create(ShardGate.class), Set.of(SHARD_0), ShardPhase.CONVERGED);
        gate.shardReported(SHARD_0, ShardPhase.CANCELLING, null);
        assertFalse(gate.isDone());
    }

    // 9. Concurrent shardReported calls — gate completes exactly once
    public void testConcurrentReports() throws Exception {
        int shardCount = 100;
        Set<ShardId> shards = new HashSet<>();
        for (int i = 0; i < shardCount; i++) {
            shards.add(new ShardId("source", "_na_", i));
        }
        ShardGate gate = new ShardGate(AoscLogger.create(ShardGate.class), shards, ShardPhase.CONVERGED);

        CyclicBarrier barrier = new CyclicBarrier(shardCount);
        Thread[] threads = new Thread[shardCount];
        for (int i = 0; i < shardCount; i++) {
            final ShardId sid = new ShardId("source", "_na_", i);
            threads[i] = new Thread(() -> {
                try {
                    barrier.await();
                    gate.shardReported(sid, ShardPhase.CONVERGED, null);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            });
            threads[i].start();
        }
        for (Thread t : threads)
            t.join(5000);

        assertTrue(gate.isDone());
        ShardGate.GateResult result = gate.awaitable().get(1, TimeUnit.SECONDS);
        assertEquals(ShardGate.Outcome.ALL_REACHED_TARGET, result.outcome());
        assertEquals(shardCount, gate.convergedCount());
    }

    // 10. hasAnyFailed reflects reported failures even after gate completed normally
    public void testHasAnyFailedOnGateInstance() {
        ShardGate gate = new ShardGate(AoscLogger.create(ShardGate.class), Set.of(SHARD_0, SHARD_1), ShardPhase.CONVERGED);
        assertFalse(gate.hasAnyFailed());
        gate.shardReported(SHARD_0, ShardPhase.FAILED, "error");
        assertTrue(gate.hasAnyFailed());
    }

    // 11. Empty shard set → gate completes immediately
    public void testEmptyShardSet() throws Exception {
        ShardGate gate = new ShardGate(AoscLogger.create(ShardGate.class), Set.of(), ShardPhase.CONVERGED);
        // With no expected shards, allReachedTarget is vacuously true on first check...
        // but no shardReported will ever be called. The gate should complete via preFill(empty).
        gate.preFill(Map.of());
        assertTrue(gate.isDone());
    }

    // 12. Single shard gate — simplest case
    public void testSingleShard() throws Exception {
        ShardGate gate = new ShardGate(AoscLogger.create(ShardGate.class), Set.of(SHARD_0), ShardPhase.COMPLETED);
        gate.shardReported(SHARD_0, ShardPhase.COMPLETED, null);
        assertTrue(gate.isDone());
        assertEquals(ShardGate.Outcome.ALL_REACHED_TARGET, gate.awaitable().get(1, TimeUnit.SECONDS).outcome());
    }

    // 13. preFill with failed shard → gate completes with SHARD_FAILED
    public void testPreFillWithFailedShard() throws Exception {
        ShardGate gate = new ShardGate(AoscLogger.create(ShardGate.class), Set.of(SHARD_0, SHARD_1), ShardPhase.CONVERGED);
        gate.preFill(Map.of(SHARD_0, ShardPhase.CONVERGED, SHARD_1, ShardPhase.FAILED));
        assertTrue(gate.isDone());
        assertEquals(ShardGate.Outcome.SHARD_FAILED, gate.awaitable().get(1, TimeUnit.SECONDS).outcome());
    }

    // 14. Gate with anyTerminal() checker — accepts CANCELLED and FAILED
    public void testTerminalGateAcceptsAllTerminalPhases() throws Exception {
        ShardGate gate = new ShardGate(AoscLogger.create(ShardGate.class), Set.of(SHARD_0, SHARD_1), ShardGate.anyTerminal(), false);

        gate.shardReported(SHARD_0, ShardPhase.CANCELLED, null);
        assertFalse(gate.isDone());

        gate.shardReported(SHARD_1, ShardPhase.FAILED, "test error");
        assertTrue(gate.isDone());
        assertEquals(ShardGate.Outcome.ALL_REACHED_TARGET, gate.awaitable().get(1, TimeUnit.SECONDS).outcome());
    }

    // 15. Terminal gate (failedIsFastFail=false) — FAILED is NOT fast-fail, just another terminal phase
    public void testTerminalGateFailedIsNotFastFail() throws Exception {
        ShardGate gate = new ShardGate(AoscLogger.create(ShardGate.class), Set.of(SHARD_0, SHARD_1), ShardGate.anyTerminal(), false);

        gate.shardReported(SHARD_0, ShardPhase.FAILED, "test error");
        assertFalse("One shard terminal is not enough — gate waits for all", gate.isDone());

        gate.shardReported(SHARD_1, ShardPhase.CANCELLED, null);
        assertTrue("Both shards terminal should complete gate", gate.isDone());
        assertEquals(ShardGate.Outcome.ALL_REACHED_TARGET, gate.awaitable().get(1, TimeUnit.SECONDS).outcome());
    }
}
