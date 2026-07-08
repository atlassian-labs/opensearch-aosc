/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */
package com.atlassian.opensearch.aosc.service.bulk;

import com.atlassian.opensearch.aosc.service.adaptive.BulkOutcome;
import com.atlassian.opensearch.aosc.service.adaptive.FixedBatchSizeController;
import com.atlassian.opensearch.aosc.service.adaptive.RejectionKind;
import com.atlassian.opensearch.aosc.utils.AoscLogger;

import org.opensearch.test.OpenSearchTestCase;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

public class SimpleWriteControllerTests extends OpenSearchTestCase {

    // ---- nextBatchSize delegates ----

    public void testNextBatchSizeDelegatesToSizeController() {
        SimpleWriteController controller = new SimpleWriteController(
            AoscLogger.create(SimpleWriteController.class),
            new FixedBatchSizeController(() -> 42),
            () -> 1,
            () -> 100_000_000L,
            new OverloadBackoff(() -> 2_000L, () -> 120_000L, () -> 50)
        );
        assertEquals(42, controller.nextBatchSize());
    }

    // ---- SUCCESS handling ----

    public void testSuccessReturnsSuccessDecision() {
        SimpleWriteController controller = controller(500);
        WriteDecision decision = controller.handleOutcome(successOutcome());
        assertEquals(WriteDecision.Action.SUCCESS, decision.action());
    }

    public void testSuccessDecaysOverloadLevel() {
        SimpleWriteController controller = controller(500);
        controller.handleOutcome(overloadOutcome());
        controller.handleOutcome(overloadOutcome());
        controller.handleOutcome(overloadOutcome());
        assertEquals(3, controller.overloadLevel());

        controller.handleOutcome(successOutcome());
        assertEquals(2, controller.overloadLevel());

        controller.handleOutcome(successOutcome());
        assertEquals(1, controller.overloadLevel());

        controller.handleOutcome(successOutcome());
        assertEquals(0, controller.overloadLevel());

        // Stays at zero
        controller.handleOutcome(successOutcome());
        assertEquals(0, controller.overloadLevel());
    }

    // ---- OVERLOAD handling ----

    public void testOverloadReturnsPauseAndRetry() {
        SimpleWriteController controller = controller(500);
        WriteDecision decision = controller.handleOutcome(overloadOutcome());
        assertEquals(WriteDecision.Action.PAUSE_AND_RETRY, decision.action());
        assertTrue(decision.pauseMillis() > 0);
    }

    public void testOverloadReturnsFatalAfterConsecutiveFailures() {
        SimpleWriteController controller = controller(500);
        for (int i = 0; i < 49; i++) {
            WriteDecision d = controller.handleOutcome(overloadOutcome());
            assertEquals(
                "OVERLOAD should be PAUSE_AND_RETRY before limit (iteration " + i + ")",
                WriteDecision.Action.PAUSE_AND_RETRY,
                d.action()
            );
        }
        WriteDecision fatal = controller.handleOutcome(overloadOutcome());
        assertEquals("OVERLOAD should go FATAL at limit", WriteDecision.Action.FATAL, fatal.action());
    }

    public void testSuccessResetsConsecutiveFailureCounter() {
        SimpleWriteController controller = controller(500);
        // Send 100 overloads, but with a success every 40 — should never hit the limit of 50
        for (int round = 0; round < 3; round++) {
            for (int i = 0; i < 40; i++) {
                WriteDecision d = controller.handleOutcome(overloadOutcome());
                assertEquals("Should be PAUSE_AND_RETRY", WriteDecision.Action.PAUSE_AND_RETRY, d.action());
            }
            WriteDecision success = controller.handleOutcome(successOutcome());
            assertEquals(WriteDecision.Action.SUCCESS, success.action());
        }
    }

    public void testOverloadPauseEscalatesExponentially() {
        SimpleWriteController controller = controllerWithBackoff(500, 2_000L, 120_000L);
        long prevPause = 0;
        for (int i = 0; i < 6; i++) {
            WriteDecision d = controller.handleOutcome(overloadOutcome());
            assertTrue("Pause should increase or plateau (iteration " + i + ")", d.pauseMillis() >= prevPause / 2);
            prevPause = d.pauseMillis();
        }
        // At level 6+, should be near or at max (120s + jitter)
        WriteDecision d = controller.handleOutcome(overloadOutcome());
        assertTrue("Pause should be capped near maxPause", d.pauseMillis() <= 120_000L + 30_000L);
    }

    public void testOverloadPauseCappedAtMax() {
        SimpleWriteController controller = controllerWithBackoff(500, 1_000L, 5_000L);
        for (int i = 0; i < 20; i++) {
            WriteDecision d = controller.handleOutcome(overloadOutcome());
            assertTrue("Pause should never exceed max + 25% jitter", d.pauseMillis() <= 5_000L + 1_250L + 1);
        }
    }

    public void testGradualDecayPreventsOscillation() {
        SimpleWriteController controller = controller(500);
        // Push to level 5
        for (int i = 0; i < 5; i++) {
            controller.handleOutcome(overloadOutcome());
        }
        assertEquals(5, controller.overloadLevel());

        // One success decays to 4, not 0
        controller.handleOutcome(successOutcome());
        assertEquals(4, controller.overloadLevel());

        // Next overload goes to 5 (not 1)
        controller.handleOutcome(overloadOutcome());
        assertEquals(5, controller.overloadLevel());
    }

    // ---- TRANSIENT handling (treated like OVERLOAD) ----

    public void testTransientReturnsPauseAndRetry() {
        SimpleWriteController controller = controller(500);
        WriteDecision decision = controller.handleOutcome(transientOutcome());
        assertEquals(WriteDecision.Action.PAUSE_AND_RETRY, decision.action());
        assertTrue(decision.pauseMillis() > 0);
    }

    public void testTransientReturnsFatalAfterConsecutiveFailures() {
        SimpleWriteController controller = controller(500);
        for (int i = 0; i < 49; i++) {
            WriteDecision d = controller.handleOutcome(transientOutcome());
            assertEquals(
                "TRANSIENT should be PAUSE_AND_RETRY before limit (iteration " + i + ")",
                WriteDecision.Action.PAUSE_AND_RETRY,
                d.action()
            );
        }
        WriteDecision fatal = controller.handleOutcome(transientOutcome());
        assertEquals("TRANSIENT should go FATAL at limit", WriteDecision.Action.FATAL, fatal.action());
    }

    public void testTransientIncrementsOverloadLevel() {
        SimpleWriteController controller = controller(500);
        assertEquals(0, controller.overloadLevel());
        controller.handleOutcome(transientOutcome());
        assertEquals(1, controller.overloadLevel());
        controller.handleOutcome(transientOutcome());
        assertEquals(2, controller.overloadLevel());
    }

    // ---- FATAL handling ----

    public void testFatalRejectionReturnsFatal() {
        SimpleWriteController controller = controller(500);
        WriteDecision decision = controller.handleOutcome(fatalOutcome());
        assertEquals(WriteDecision.Action.FATAL, decision.action());
    }

    // ---- thread safety (basic smoke test) ----

    public void testConcurrentAccessDoesNotThrow() throws Exception {
        SimpleWriteController controller = controller(500);
        int threadCount = 8;
        Thread[] threads = new Thread[threadCount];
        CountDownLatch latch = new CountDownLatch(threadCount);

        for (int t = 0; t < threadCount; t++) {
            threads[t] = new Thread(() -> {
                for (int i = 0; i < 100; i++) {
                    controller.nextBatchSize();
                    controller.handleOutcome(successOutcome());
                }
                latch.countDown();
            });
        }

        for (Thread t : threads)
            t.start();
        assertTrue(latch.await(10, TimeUnit.SECONDS));
    }

    // ---- constructor validation ----

    public void testConstructorRejectsNull() {
        expectThrows(
            NullPointerException.class,
            () -> new SimpleWriteController(
                AoscLogger.create(SimpleWriteController.class),
                null,
                () -> 1,
                () -> 100_000_000L,
                new OverloadBackoff(() -> 2_000L, () -> 120_000L, () -> 50)
            )
        );
    }

    public void testConcurrencyAndMaxBatchBytesFromConstructor() {
        SimpleWriteController controller = new SimpleWriteController(
            AoscLogger.create(SimpleWriteController.class),
            new FixedBatchSizeController(() -> 100),
            () -> 4,
            () -> 50_000_000L,
            new OverloadBackoff(() -> 2_000L, () -> 120_000L, () -> 50)
        );
        assertEquals(4, controller.concurrency());
        assertEquals(50_000_000L, controller.maxBatchBytes());
    }

    // ---- dynamic pause values ----

    public void testDynamicPauseValuesAreUsed() {
        AtomicLong dynamicBase = new AtomicLong(1_000L);
        AtomicLong dynamicMax = new AtomicLong(120_000L);
        SimpleWriteController controller = new SimpleWriteController(
            AoscLogger.create(SimpleWriteController.class),
            new FixedBatchSizeController(() -> 500),
            () -> 1,
            () -> 100_000_000L,
            new OverloadBackoff(dynamicBase::get, dynamicMax::get, () -> 50)
        );

        WriteDecision d1 = controller.handleOutcome(overloadOutcome());
        assertEquals(WriteDecision.Action.PAUSE_AND_RETRY, d1.action());
        assertTrue(d1.pauseMillis() >= 1_000 && d1.pauseMillis() <= 1_250);

        // Update dynamic base pause
        dynamicBase.set(5_000L);
        // Decay to reset level, then escalate fresh
        controller.handleOutcome(successOutcome());

        WriteDecision d2 = controller.handleOutcome(overloadOutcome());
        assertTrue("After updating base to 5000, pause should be >= 5000, got " + d2.pauseMillis(), d2.pauseMillis() >= 5_000);
    }

    // ---- helpers ----

    private static SimpleWriteController controller(int batchSize) {
        return new SimpleWriteController(
            AoscLogger.create(SimpleWriteController.class),
            new FixedBatchSizeController(() -> batchSize),
            () -> 1,
            () -> 100_000_000L,
            new OverloadBackoff(() -> 2_000L, () -> 120_000L, () -> 50)
        );
    }

    private static SimpleWriteController controllerWithBackoff(int batchSize, long basePause, long maxPause) {
        return new SimpleWriteController(
            AoscLogger.create(SimpleWriteController.class),
            new FixedBatchSizeController(() -> batchSize),
            () -> 1,
            () -> 100_000_000L,
            new OverloadBackoff(() -> basePause, () -> maxPause, () -> 50)
        );
    }

    private static BulkOutcome successOutcome() {
        return BulkOutcome.success(100, 80, 500, 1024, 1);
    }

    private static BulkOutcome overloadOutcome() {
        return BulkOutcome.failure(100, -1, 500, 1024, 1, RejectionKind.OVERLOAD, "429 Too Many Requests");
    }

    private static BulkOutcome transientOutcome() {
        return BulkOutcome.failure(100, -1, 500, 1024, 3, RejectionKind.TRANSIENT, "Connection reset");
    }

    private static BulkOutcome fatalOutcome() {
        return BulkOutcome.failure(100, -1, 500, 1024, 1, RejectionKind.FATAL, "Mapping conflict");
    }
}
