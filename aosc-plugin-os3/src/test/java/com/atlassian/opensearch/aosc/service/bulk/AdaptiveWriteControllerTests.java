/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */
package com.atlassian.opensearch.aosc.service.bulk;

import com.atlassian.opensearch.aosc.service.adaptive.BulkOutcome;
import com.atlassian.opensearch.aosc.service.adaptive.LatencyGradient;
import com.atlassian.opensearch.aosc.service.adaptive.RejectionKind;
import com.atlassian.opensearch.aosc.utils.AoscLogger;

import org.opensearch.test.OpenSearchTestCase;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

public class AdaptiveWriteControllerTests extends OpenSearchTestCase {

    private static final int MIN_W = 1;
    private static final int MAX_W = 8;
    private static final double DECREASE_THRESHOLD = 1.2;
    private static final int PROBE_INTERVAL = 3;
    private static final long MIN_TARGET_BYTES = 2 * 1024 * 1024;
    private static final long MAX_TARGET_BYTES = 256 * 1024 * 1024;
    private static final int MAX_DOCS = 5000;
    private static final long START_BPD = 10 * 1024;

    // ---- construction ----

    public void testConstructorValidatesMaxWBelowOne() {
        expectThrows(IllegalArgumentException.class, () -> controller(0, 1));
    }

    public void testConstructorValidatesInitialWOutOfRange() {
        expectThrows(IllegalArgumentException.class, () -> controller(8, 0));
        expectThrows(IllegalArgumentException.class, () -> controller(8, 9));
    }

    // ---- warmup guard ----

    public void testDuringWarmupNoConcurrencyChange() {
        AdaptiveWriteController ctrl = defaultController();
        // Feed fewer than 5 outcomes — should not change W
        for (int i = 0; i < LatencyGradient.WARMUP_SAMPLES - 1; i++) {
            ctrl.handleOutcome(successOutcome(1.0));
        }
        assertEquals(MIN_W, ctrl.currentW());
    }

    // ---- probe / grow W ----

    public void testProbeGrowsWAfterStableInterval() {
        AdaptiveWriteController ctrl = defaultController();
        warmUp(ctrl);

        // Warmup already advanced stableCount, so the first post-warmup outcome
        // may immediately trigger a probe. Drain any accumulated probes first.
        int wAfterWarmup = ctrl.currentW();
        while (ctrl.stableCount() != 0) {
            ctrl.handleOutcome(successOutcome(1.0));
        }
        int wAfterDrain = ctrl.currentW();

        // Now stableCount is 0 — feed exactly PROBE_INTERVAL stable outcomes
        for (int i = 0; i < PROBE_INTERVAL; i++) {
            ctrl.handleOutcome(successOutcome(1.0));
        }
        assertEquals(wAfterDrain + 1, ctrl.currentW());
    }

    public void testWDoesNotExceedMaxW() {
        AdaptiveWriteController ctrl = controller(2, 1);
        warmUp(ctrl);

        // Grow W to maxW
        for (int i = 0; i < PROBE_INTERVAL; i++) {
            ctrl.handleOutcome(successOutcome(1.0));
        }
        assertEquals(2, ctrl.currentW());

        // Another probe should not grow W past maxW, should grow batch instead
        for (int i = 0; i < PROBE_INTERVAL; i++) {
            ctrl.handleOutcome(successOutcome(1.0));
        }
        assertEquals(2, ctrl.currentW());
    }

    // ---- decrease ----

    public void testHighGradientDecreases() {
        AdaptiveWriteController ctrl = controller(8, 4);
        warmUp(ctrl);

        // Inject rising latency to push gradient > DECREASE_THRESHOLD
        for (int i = 0; i < 10; i++) {
            ctrl.handleOutcome(successOutcome(20.0));
        }
        // Gradient should now be > 1.2 (short EWMA > long EWMA due to spike)
        // But with constant high values, gradient converges to 1.0.
        // Need a pattern: low then high
        ctrl = controller(8, 4);
        // Seed with low latency
        for (int i = 0; i < 10; i++) {
            ctrl.handleOutcome(successOutcome(1.0));
        }
        int wBefore = ctrl.currentW();
        // Spike latency — should trigger decrease
        for (int i = 0; i < 5; i++) {
            ctrl.handleOutcome(successOutcome(10.0));
            if (ctrl.currentW() < wBefore) {
                return;
            }
        }
        // If gradient was above threshold, W should have decreased
        assertTrue("W should have decreased from " + wBefore, ctrl.currentW() < wBefore);
    }

    // ---- overload handling ----

    public void testOverloadDecreasesConcurrencyAndPauses() {
        AdaptiveWriteController ctrl = controller(8, 4);
        WriteDecision d = ctrl.handleOutcome(overloadOutcome());
        assertEquals(WriteDecision.Action.PAUSE_AND_RETRY, d.action());
        assertTrue(d.pauseMillis() > 0);
    }

    public void testOverloadShrinksBatchSize() {
        AdaptiveWriteController ctrl = defaultController();
        long bytesBefore = ctrl.currentTargetBytes();
        ctrl.handleOutcome(overloadOutcome());
        assertTrue(ctrl.currentTargetBytes() <= bytesBefore / 2 || ctrl.currentTargetBytes() == MIN_TARGET_BYTES);
    }

    public void testOverloadReturnsFatalAfterConsecutiveFailures() {
        AdaptiveWriteController ctrl = defaultController();
        for (int i = 0; i < 49; i++) {
            WriteDecision d = ctrl.handleOutcome(overloadOutcome());
            assertEquals(
                "OVERLOAD should be PAUSE_AND_RETRY before limit (iteration " + i + ")",
                WriteDecision.Action.PAUSE_AND_RETRY,
                d.action()
            );
        }
        WriteDecision fatal = ctrl.handleOutcome(overloadOutcome());
        assertEquals("OVERLOAD should go FATAL at limit", WriteDecision.Action.FATAL, fatal.action());
    }

    public void testOverloadPauseEscalates() {
        AdaptiveWriteController ctrl = defaultController();
        long prevPause = 0;
        for (int i = 0; i < 6; i++) {
            WriteDecision d = ctrl.handleOutcome(overloadOutcome());
            assertTrue("Pause should generally increase (iteration " + i + ")", d.pauseMillis() >= prevPause / 2);
            prevPause = d.pauseMillis();
        }
    }

    public void testSuccessDecaysOverloadLevel() {
        AdaptiveWriteController ctrl = defaultController();
        ctrl.handleOutcome(overloadOutcome());
        ctrl.handleOutcome(overloadOutcome());
        ctrl.handleOutcome(overloadOutcome());
        assertEquals(3, ctrl.overloadLevel());

        ctrl.handleOutcome(successOutcome(1.0));
        assertEquals(2, ctrl.overloadLevel());

        ctrl.handleOutcome(successOutcome(1.0));
        assertEquals(1, ctrl.overloadLevel());
    }

    // ---- transient handling (treated like OVERLOAD) ----

    public void testTransientReturnsPauseAndRetry() {
        AdaptiveWriteController ctrl = defaultController();
        WriteDecision d = ctrl.handleOutcome(transientOutcome());
        assertEquals(WriteDecision.Action.PAUSE_AND_RETRY, d.action());
        assertTrue(d.pauseMillis() > 0);
    }

    public void testTransientReturnsFatalAfterConsecutiveFailures() {
        AdaptiveWriteController ctrl = defaultController();
        for (int i = 0; i < 49; i++) {
            WriteDecision d = ctrl.handleOutcome(transientOutcome());
            assertEquals(
                "TRANSIENT should be PAUSE_AND_RETRY before limit (iteration " + i + ")",
                WriteDecision.Action.PAUSE_AND_RETRY,
                d.action()
            );
        }
        WriteDecision fatal = ctrl.handleOutcome(transientOutcome());
        assertEquals("TRANSIENT should go FATAL at limit", WriteDecision.Action.FATAL, fatal.action());
    }

    public void testTransientIncrementsOverloadLevel() {
        AdaptiveWriteController ctrl = defaultController();
        assertEquals(0, ctrl.overloadLevel());
        ctrl.handleOutcome(transientOutcome());
        assertEquals(1, ctrl.overloadLevel());
        ctrl.handleOutcome(transientOutcome());
        assertEquals(2, ctrl.overloadLevel());
    }

    public void testTransientShrinksBatchSize() {
        AdaptiveWriteController ctrl = defaultController();
        long bytesBefore = ctrl.currentTargetBytes();
        ctrl.handleOutcome(transientOutcome());
        assertTrue(ctrl.currentTargetBytes() <= bytesBefore / 2 || ctrl.currentTargetBytes() == MIN_TARGET_BYTES);
    }

    // ---- fatal rejection ----

    public void testFatalRejectionReturnsFatal() {
        AdaptiveWriteController ctrl = defaultController();
        WriteDecision d = ctrl.handleOutcome(fatalOutcome());
        assertEquals(WriteDecision.Action.FATAL, d.action());
    }

    // ---- concurrency() and maxBatchBytes() ----

    public void testConcurrencyReturnsCurrentW() {
        AdaptiveWriteController ctrl = controller(8, 4);
        assertEquals(4, ctrl.concurrency());
    }

    public void testMaxBatchBytesIsDerived() {
        AdaptiveWriteController ctrl = defaultController();
        long max = ctrl.maxBatchBytes();
        assertTrue(max > 0);
        assertTrue(max <= MAX_TARGET_BYTES);
    }

    // ---- nextBatchSize ----

    public void testNextBatchSizePositive() {
        AdaptiveWriteController ctrl = defaultController();
        assertTrue(ctrl.nextBatchSize() > 0);
    }

    public void testNextBatchSizeCappedByMaxDocs() {
        AdaptiveWriteController ctrl = defaultController();
        assertTrue(ctrl.nextBatchSize() <= MAX_DOCS);
    }

    // ---- W bounds ----

    public void testShrinkWFloorAtMinW() {
        AdaptiveWriteController ctrl = controller(8, 1);
        // At minW, overload should not decrease W further
        WriteDecision d = ctrl.handleOutcome(overloadOutcome());
        assertEquals(1, ctrl.currentW());
    }

    // ---- thread safety smoke ----

    public void testConcurrentAccessDoesNotThrow() throws Exception {
        AdaptiveWriteController ctrl = defaultController();
        int threadCount = 8;
        Thread[] threads = new Thread[threadCount];
        CountDownLatch latch = new CountDownLatch(threadCount);

        for (int t = 0; t < threadCount; t++) {
            threads[t] = new Thread(() -> {
                for (int i = 0; i < 100; i++) {
                    ctrl.nextBatchSize();
                    ctrl.handleOutcome(successOutcome(1.0));
                    ctrl.concurrency();
                    ctrl.maxBatchBytes();
                }
                latch.countDown();
            });
        }
        for (Thread t : threads)
            t.start();
        assertTrue(latch.await(10, TimeUnit.SECONDS));
    }

    // ---- dynamic pause values ----

    public void testDynamicPauseValuesAreUsed() {
        AtomicLong dynamicBase = new AtomicLong(1_000L);
        AtomicLong dynamicMax = new AtomicLong(120_000L);
        AdaptiveWriteController ctrl = new AdaptiveWriteController(
            AoscLogger.create(AdaptiveWriteController.class),
            () -> MAX_W,
            MIN_W,
            DECREASE_THRESHOLD,
            PROBE_INTERVAL,
            () -> MIN_TARGET_BYTES,
            () -> MAX_TARGET_BYTES,
            () -> 256 * 1024L,
            () -> MAX_DOCS,
            START_BPD,
            new OverloadBackoff(dynamicBase::get, dynamicMax::get, () -> 50)
        );

        WriteDecision d1 = ctrl.handleOutcome(overloadOutcome());
        assertEquals(WriteDecision.Action.PAUSE_AND_RETRY, d1.action());
        assertTrue(d1.pauseMillis() >= 1_000 && d1.pauseMillis() <= 1_250);

        // Update dynamic base pause
        dynamicBase.set(5_000L);
        ctrl.handleOutcome(successOutcome(1.0));

        WriteDecision d2 = ctrl.handleOutcome(overloadOutcome());
        assertTrue("After updating base to 5000, pause should be >= 5000, got " + d2.pauseMillis(), d2.pauseMillis() >= 5_000);
    }

    // ---- helpers ----

    private static AdaptiveWriteController defaultController() {
        return controller(MAX_W, MIN_W);
    }

    private static AdaptiveWriteController controller(int maxW, int initialW) {
        return new AdaptiveWriteController(
            AoscLogger.create(AdaptiveWriteController.class),
            () -> maxW,
            initialW,
            DECREASE_THRESHOLD,
            PROBE_INTERVAL,
            () -> MIN_TARGET_BYTES,
            () -> MAX_TARGET_BYTES,
            () -> 256 * 1024L,
            () -> MAX_DOCS,
            START_BPD,
            new OverloadBackoff(() -> 2_000L, () -> 120_000L, () -> 50)
        );
    }

    private static void warmUp(AdaptiveWriteController ctrl) {
        for (int i = 0; i < LatencyGradient.WARMUP_SAMPLES; i++) {
            ctrl.handleOutcome(successOutcome(1.0));
        }
    }

    private static BulkOutcome successOutcome(double perDocMs) {
        int items = 100;
        long serverMs = (long) (perDocMs * items);
        return BulkOutcome.success(serverMs + 10, serverMs, items, 1024 * items, 1);
    }

    private static BulkOutcome overloadOutcome() {
        return BulkOutcome.failure(100, -1, 500, 1024 * 500, 1, RejectionKind.OVERLOAD, "429 Too Many Requests");
    }

    private static BulkOutcome transientOutcome() {
        return BulkOutcome.failure(100, -1, 500, 1024 * 500, 3, RejectionKind.TRANSIENT, "Connection reset");
    }

    private static BulkOutcome fatalOutcome() {
        return BulkOutcome.failure(100, -1, 500, 1024 * 500, 1, RejectionKind.FATAL, "Mapping conflict");
    }
}
