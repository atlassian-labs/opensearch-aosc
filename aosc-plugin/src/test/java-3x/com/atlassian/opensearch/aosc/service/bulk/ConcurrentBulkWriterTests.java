/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */
package com.atlassian.opensearch.aosc.service.bulk;

import com.atlassian.opensearch.aosc.service.bulk.ThreadSafeDocSourceTests.TestMetrics;
import com.atlassian.opensearch.aosc.utils.AoscLogger;

import org.opensearch.action.bulk.BulkItemResponse;
import org.opensearch.action.bulk.BulkRequest;
import org.opensearch.action.bulk.BulkResponse;
import org.opensearch.action.index.IndexRequest;
import org.opensearch.common.settings.Settings;
import org.opensearch.common.unit.TimeValue;
import org.opensearch.common.util.concurrent.ThreadContext;
import org.opensearch.core.action.ActionListener;
import org.opensearch.test.OpenSearchTestCase;
import org.opensearch.threadpool.ThreadPool;
import org.opensearch.transport.client.Client;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class ConcurrentBulkWriterTests extends OpenSearchTestCase {

    private final ExecutorService executor = Executors.newFixedThreadPool(4);

    @Override
    public void tearDown() throws Exception {
        executor.shutdownNow();
        super.tearDown();
    }

    // ---- basic consume ----

    public void testConsumeAsyncCompletesOnEmptySource() throws Exception {
        ConcurrentBulkWriter writer = writer(1);
        DocSource<TestMetrics> source = emptySource();

        CompletableFuture<Void> future = writer.consumeAsync(source, ignored -> {});
        future.get(5, TimeUnit.SECONDS);
        assertTrue(future.isDone());
        assertFalse(future.isCompletedExceptionally());
    }

    // ---- cancel ----

    public void testCancelSetsCancelledFlag() {
        ConcurrentBulkWriter writer = writer(1);
        assertFalse(writer.isCancelled());
        writer.cancel();
        assertTrue(writer.isCancelled());
    }

    public void testCancelCausesExceptionalCompletion() throws Exception {
        ConcurrentBulkWriter writer = writer(1);
        writer.cancel();
        DocSource<TestMetrics> source = sourceOf(op("a"));

        CompletableFuture<Void> future = writer.consumeAsync(source, ignored -> {});
        try {
            future.get(5, TimeUnit.SECONDS);
            fail("Expected CancellationException");
        } catch (CancellationException e) {
            // CompletableFuture.get() throws CancellationException directly (not wrapped)
        }
    }

    // ---- constructor validation ----

    public void testConstructorRejectsZeroConcurrency() {
        WriteController badController = mock(WriteController.class);
        when(badController.concurrency()).thenReturn(0);
        expectThrows(
            IllegalArgumentException.class,
            () -> new ConcurrentBulkWriter(
                mock(Client.class),
                mockThreadPool(),
                badController,
                AoscLogger.create(ConcurrentBulkWriter.class)
            )
        );
    }

    public void testConstructorRejectsNullClient() {
        expectThrows(
            NullPointerException.class,
            () -> new ConcurrentBulkWriter(null, mockThreadPool(), mockController(), AoscLogger.create(ConcurrentBulkWriter.class))
        );
    }

    public void testConstructorRejectsNullController() {
        expectThrows(
            NullPointerException.class,
            () -> new ConcurrentBulkWriter(mock(Client.class), mockThreadPool(), null, AoscLogger.create(ConcurrentBulkWriter.class))
        );
    }

    // ---- dynamic W ----

    public void testEffectiveConcurrencyInitialValue() {
        ConcurrentBulkWriter writer = writer(4);
    }

    public void testEffectiveConcurrencyDefaultsToOne() {
        ConcurrentBulkWriter writer = writer(1);
    }

    // ---- new tests for write pipeline ----

    public void testSingleDocWriteCompletesSuccessfully() throws Exception {
        Client client = mockClientWithBulkResponse();
        ConcurrentBulkWriter writer = new ConcurrentBulkWriter(
            client,
            mockThreadPool(),
            mockController(),
            AoscLogger.create(ConcurrentBulkWriter.class)
        );
        DocSource<TestMetrics> source = sourceOf(op("doc1"));
        AtomicInteger progressCount = new AtomicInteger(0);

        CompletableFuture<Void> future = writer.consumeAsync(source, batch -> progressCount.incrementAndGet());
        future.get(5, TimeUnit.SECONDS);

        assertTrue(future.isDone());
        assertFalse(future.isCompletedExceptionally());
        assertEquals(1, progressCount.get());
    }

    public void testMultipleDocsConsumedSequentially() throws Exception {
        Client client = mockClientWithBulkResponse();
        ConcurrentBulkWriter writer = new ConcurrentBulkWriter(
            client,
            mockThreadPool(),
            mockController(),
            AoscLogger.create(ConcurrentBulkWriter.class)
        );
        DocSource<TestMetrics> source = sourceOf(op("a"), op("b"), op("c"), op("d"), op("e"));
        AtomicInteger progressCount = new AtomicInteger(0);

        CompletableFuture<Void> future = writer.consumeAsync(source, batch -> progressCount.incrementAndGet());
        future.get(5, TimeUnit.SECONDS);

        assertTrue(future.isDone());
        assertFalse(future.isCompletedExceptionally());
        assertTrue("Expected 1 progress callback, got " + progressCount.get(), progressCount.get() > 0);
    }

    public void testFatalDecisionCompletesExceptionally() throws Exception {
        Client client = mockClientWithBulkResponse();
        WriteController controller = mockController();

        when(controller.handleOutcome(any())).thenReturn(WriteDecision.fatal("Test fatal error"));

        ConcurrentBulkWriter writer = new ConcurrentBulkWriter(
            client,
            mockThreadPool(),
            controller,
            AoscLogger.create(ConcurrentBulkWriter.class)
        );
        DocSource<TestMetrics> source = sourceOf(op("doc"));

        CompletableFuture<Void> future = writer.consumeAsync(source, ignored -> {});
        try {
            future.get(5, TimeUnit.SECONDS);
            fail("Expected future to complete exceptionally");
        } catch (Exception e) {
            assertTrue("Expected RuntimeException, got " + e.getClass(), e.getCause() instanceof RuntimeException);
            assertTrue("Expected 'fatal error' in message", e.getCause().getMessage().contains("fatal error"));
        }
    }

    public void testConcurrencyDeltaAdjustsEffectiveW() throws Exception {
        Client client = mockClientWithBulkResponse();
        AtomicInteger currentW = new AtomicInteger(1);
        WriteController controller = mock(WriteController.class);
        when(controller.nextBatchSize()).thenReturn(1);
        when(controller.maxBatchBytes()).thenReturn(100_000_000L);
        when(controller.concurrency()).thenAnswer(inv -> currentW.get());
        when(controller.handleOutcome(any())).thenAnswer(invocation -> {
            currentW.updateAndGet(w -> Math.max(1, w + 1));
            return WriteDecision.success();
        });

        ConcurrentBulkWriter writer = new ConcurrentBulkWriter(
            client,
            mockThreadPool(),
            controller,
            AoscLogger.create(ConcurrentBulkWriter.class)
        );

        DocSource<TestMetrics> source = sourceOf(op("a"), op("b"));
        CompletableFuture<Void> future = writer.consumeAsync(source, batch -> {});
        future.get(5, TimeUnit.SECONDS);

    }

    public void testConcurrencyFloorAtOne() throws Exception {
        Client client = mockClientWithBulkResponse();
        AtomicInteger currentW = new AtomicInteger(2);
        WriteController controller = mock(WriteController.class);
        when(controller.nextBatchSize()).thenReturn(1);
        when(controller.maxBatchBytes()).thenReturn(100_000_000L);
        when(controller.concurrency()).thenAnswer(inv -> currentW.get());
        when(controller.handleOutcome(any())).thenAnswer(invocation -> {
            currentW.updateAndGet(w -> Math.max(1, w - 5));
            return WriteDecision.success();
        });

        ConcurrentBulkWriter writer = new ConcurrentBulkWriter(
            client,
            mockThreadPool(),
            controller,
            AoscLogger.create(ConcurrentBulkWriter.class)
        );

        DocSource<TestMetrics> source = sourceOf(op("doc"));
        CompletableFuture<Void> future = writer.consumeAsync(source, batch -> {});
        future.get(5, TimeUnit.SECONDS);

    }

    public void testConcurrentWritersWithHighW() throws Exception {
        Client client = mockClientWithBulkResponse();
        ConcurrentBulkWriter writer = new ConcurrentBulkWriter(
            client,
            mockThreadPool(),
            mockController(4),
            AoscLogger.create(ConcurrentBulkWriter.class)
        );
        DocSource<TestMetrics> source = sourceOf(op("1"), op("2"), op("3"), op("4"), op("5"), op("6"), op("7"), op("8"), op("9"), op("10"));
        AtomicInteger progressCount = new AtomicInteger(0);

        CompletableFuture<Void> future = writer.consumeAsync(source, batch -> progressCount.incrementAndGet());
        future.get(5, TimeUnit.SECONDS);

        assertTrue(future.isDone());
        assertFalse(future.isCompletedExceptionally());
        assertTrue("Expected progress callbacks", progressCount.get() > 0);
    }

    public void testProgressCallbackCalledForEachBatch() throws Exception {
        Client client = mockClientWithBulkResponse();
        WriteController controller = mockController();
        when(controller.nextBatchSize()).thenReturn(1);

        ConcurrentBulkWriter writer = new ConcurrentBulkWriter(
            client,
            mockThreadPool(),
            controller,
            AoscLogger.create(ConcurrentBulkWriter.class)
        );
        DocSource<TestMetrics> source = sourceOf(op("1"), op("2"), op("3"), op("4"), op("5"));
        AtomicInteger progressCount = new AtomicInteger(0);

        CompletableFuture<Void> future = writer.consumeAsync(source, batch -> progressCount.incrementAndGet());
        future.get(5, TimeUnit.SECONDS);

        assertTrue(future.isDone());
        assertEquals(5, progressCount.get());
    }

    // ---- PAUSE_AND_RETRY / drain-resume ----

    public void testPauseAndRetryRequeuesOpsAndEventuallyCompletes() throws Exception {
        Client client = mockClientWithBulkResponse();
        AtomicInteger handleCount = new AtomicInteger();
        AtomicInteger retryCount = new AtomicInteger();

        WriteController controller = mock(WriteController.class);
        when(controller.concurrency()).thenReturn(1);
        when(controller.nextBatchSize()).thenReturn(1);
        when(controller.maxBatchBytes()).thenReturn(100_000_000L);
        when(controller.handleOutcome(any())).thenAnswer(inv -> {
            int call = handleCount.incrementAndGet();
            if (call <= 2) {
                retryCount.incrementAndGet();
                return WriteDecision.pauseAndRetry(10); // 10ms pause
            }
            return WriteDecision.success();
        });

        ConcurrentBulkWriter writer = new ConcurrentBulkWriter(
            client,
            mockThreadPool(),
            controller,
            AoscLogger.create(ConcurrentBulkWriter.class)
        );
        DocSource<TestMetrics> source = sourceOf(op("a"), op("b"), op("c"));
        AtomicInteger progressCount = new AtomicInteger();

        CompletableFuture<Void> future = writer.consumeAsync(source, batch -> progressCount.incrementAndGet());
        future.get(10, TimeUnit.SECONDS);

        assertTrue("Future should complete", future.isDone());
        assertFalse("Future should not fail", future.isCompletedExceptionally());
        assertTrue("Controller should have been called more than 3 times due to retries", handleCount.get() > 3);
        assertEquals("Should have retried exactly 2 times", 2, retryCount.get());
        assertEquals("All 3 docs should produce progress callbacks", 3, progressCount.get());
    }

    public void testPauseAndRetryWithConcurrencyReductionDrainsInflight() throws Exception {
        Client client = mockClientWithBulkResponse();
        AtomicInteger handleCount = new AtomicInteger();
        AtomicInteger currentW = new AtomicInteger(3);

        WriteController controller = mock(WriteController.class);
        when(controller.concurrency()).thenAnswer(inv -> currentW.get());
        when(controller.nextBatchSize()).thenReturn(1);
        when(controller.maxBatchBytes()).thenReturn(100_000_000L);
        when(controller.handleOutcome(any())).thenAnswer(inv -> {
            int call = handleCount.incrementAndGet();
            if (call == 3) {
                currentW.updateAndGet(w -> Math.max(1, w - 1));
                return WriteDecision.pauseAndRetry(10);
            }
            return WriteDecision.success();
        });

        ConcurrentBulkWriter writer = new ConcurrentBulkWriter(
            client,
            mockThreadPool(),
            controller,
            AoscLogger.create(ConcurrentBulkWriter.class)
        );
        DocSource<TestMetrics> source = sourceOf(op("1"), op("2"), op("3"), op("4"), op("5"), op("6"));
        AtomicInteger progressCount = new AtomicInteger();

        CompletableFuture<Void> future = writer.consumeAsync(source, batch -> progressCount.incrementAndGet());
        future.get(10, TimeUnit.SECONDS);

        assertTrue(future.isDone());
        assertFalse(future.isCompletedExceptionally());
        assertEquals(6, progressCount.get());
    }

    public void testMultipleConsecutiveOverloadsStillComplete() throws Exception {
        Client client = mockClientWithBulkResponse();
        AtomicInteger handleCount = new AtomicInteger();
        AtomicInteger overloadCount = new AtomicInteger();

        WriteController controller = mock(WriteController.class);
        when(controller.concurrency()).thenReturn(2);
        when(controller.nextBatchSize()).thenReturn(1);
        when(controller.maxBatchBytes()).thenReturn(100_000_000L);
        when(controller.handleOutcome(any())).thenAnswer(inv -> {
            int call = handleCount.incrementAndGet();
            // First 5 calls all return PAUSE_AND_RETRY (simulating sustained pressure)
            if (call <= 5) {
                overloadCount.incrementAndGet();
                return WriteDecision.pauseAndRetry(5);
            }
            return WriteDecision.success();
        });

        ConcurrentBulkWriter writer = new ConcurrentBulkWriter(
            client,
            mockThreadPool(),
            controller,
            AoscLogger.create(ConcurrentBulkWriter.class)
        );
        DocSource<TestMetrics> source = sourceOf(op("a"), op("b"), op("c"), op("d"));
        AtomicInteger progressCount = new AtomicInteger();

        CompletableFuture<Void> future = writer.consumeAsync(source, batch -> progressCount.incrementAndGet());
        future.get(10, TimeUnit.SECONDS);

        assertTrue(future.isDone());
        assertFalse(future.isCompletedExceptionally());
        assertEquals("Should have hit overload 5 times", 5, overloadCount.get());
        assertEquals("All 4 docs should be consumed", 4, progressCount.get());
    }

    public void testConcurrencyRampsUpThenDownViaDeltas() throws Exception {
        Client client = mockClientWithBulkResponse();
        AtomicInteger handleCount = new AtomicInteger();
        AtomicInteger maxObservedW = new AtomicInteger(1);
        AtomicInteger currentW = new AtomicInteger(1);

        WriteController controller = mock(WriteController.class);
        when(controller.concurrency()).thenAnswer(inv -> currentW.get());
        when(controller.nextBatchSize()).thenReturn(1);
        when(controller.maxBatchBytes()).thenReturn(100_000_000L);
        when(controller.handleOutcome(any())).thenAnswer(inv -> {
            int call = handleCount.incrementAndGet();
            if (call <= 3) {
                currentW.updateAndGet(w -> w + 1);
            } else if (call == 4) {
                currentW.updateAndGet(w -> Math.max(1, w - 2));
            }
            return WriteDecision.success();
        });

        ConcurrentBulkWriter writer = new ConcurrentBulkWriter(
            client,
            mockThreadPool(),
            controller,
            AoscLogger.create(ConcurrentBulkWriter.class)
        );
        DocSource<TestMetrics> source = sourceOf(op("1"), op("2"), op("3"), op("4"), op("5"), op("6"), op("7"), op("8"));
        AtomicInteger progressCount = new AtomicInteger();

        CompletableFuture<Void> future = writer.consumeAsync(source, batch -> {
            progressCount.incrementAndGet();
            maxObservedW.updateAndGet(old -> Math.max(old, currentW.get()));
        });
        future.get(10, TimeUnit.SECONDS);

        assertTrue(future.isDone());
        assertTrue("Concurrency should have ramped above 1", maxObservedW.get() > 1);
        assertEquals(8, progressCount.get());
    }

    // ---- overload pause regression (B054) ----

    /**
     * Verifies that the overload backoff pause actually delays the next batch dispatch.
     * Before the fix, {@code Semaphore.tryAcquire(0, timeout)} returned instantly,
     * making the pause a no-op and exhausting max_consecutive_failures in seconds.
     */
    public void testOverloadPauseActuallyDelaysNextDispatch() throws Exception {
        Client client = mockClientWithBulkResponse();
        AtomicInteger handleCount = new AtomicInteger();
        long pauseMs = 500;

        WriteController controller = mock(WriteController.class);
        when(controller.concurrency()).thenReturn(1);
        when(controller.nextBatchSize()).thenReturn(1);
        when(controller.maxBatchBytes()).thenReturn(100_000_000L);
        when(controller.handleOutcome(any())).thenAnswer(inv -> {
            int call = handleCount.incrementAndGet();
            if (call == 1) {
                return WriteDecision.pauseAndRetry(pauseMs);
            }
            return WriteDecision.success();
        });

        ConcurrentBulkWriter writer = new ConcurrentBulkWriter(
            client,
            mockThreadPool(),
            controller,
            AoscLogger.create(ConcurrentBulkWriter.class)
        );
        DocSource<TestMetrics> source = sourceOf(op("a"), op("b"));

        long start = System.nanoTime();
        CompletableFuture<Void> future = writer.consumeAsync(source, batch -> {});
        future.get(10, TimeUnit.SECONDS);
        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);

        assertTrue(future.isDone());
        assertFalse(future.isCompletedExceptionally());
        assertTrue(
            "Overload pause of " + pauseMs + "ms should delay completion, but elapsed was only " + elapsedMs + "ms",
            elapsedMs >= pauseMs
        );
    }

    /**
     * With concurrency=4 and 8 ops, verifies that a single overload pause still
     * delays the entire pipeline by at least pauseMs, even with multiple batches
     * in flight when the overload occurs.
     */
    public void testOverloadPauseWithHighConcurrency() throws Exception {
        Client client = mockClientWithBulkResponse();
        AtomicInteger handleCount = new AtomicInteger();
        long pauseMs = 500;

        WriteController controller = mock(WriteController.class);
        when(controller.concurrency()).thenReturn(4);
        when(controller.nextBatchSize()).thenReturn(1);
        when(controller.maxBatchBytes()).thenReturn(100_000_000L);
        when(controller.handleOutcome(any())).thenAnswer(inv -> {
            int call = handleCount.incrementAndGet();
            if (call == 1) {
                return WriteDecision.pauseAndRetry(pauseMs);
            }
            return WriteDecision.success();
        });

        ConcurrentBulkWriter writer = new ConcurrentBulkWriter(
            client,
            mockThreadPool(),
            controller,
            AoscLogger.create(ConcurrentBulkWriter.class)
        );
        DocSource<TestMetrics> source = sourceOf(op("1"), op("2"), op("3"), op("4"), op("5"), op("6"), op("7"), op("8"));

        long start = System.nanoTime();
        CompletableFuture<Void> future = writer.consumeAsync(source, batch -> {});
        future.get(10, TimeUnit.SECONDS);
        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);

        assertTrue(future.isDone());
        assertFalse(future.isCompletedExceptionally());
        assertTrue(
            "Overload pause of " + pauseMs + "ms should delay even with concurrency=4, but elapsed was " + elapsedMs + "ms",
            elapsedMs >= pauseMs
        );
    }

    /**
     * With concurrency=1 and multiple consecutive overloads, verifies that each
     * pause is applied individually and total elapsed time is at least the sum
     * of all pause durations.
     */
    public void testMultipleConsecutiveOverloadPausesAreAllApplied() throws Exception {
        Client client = mockClientWithBulkResponse();
        AtomicInteger handleCount = new AtomicInteger();
        int overloadCount = 3;
        long pauseMs = 300;

        WriteController controller = mock(WriteController.class);
        when(controller.concurrency()).thenReturn(1);
        when(controller.nextBatchSize()).thenReturn(1);
        when(controller.maxBatchBytes()).thenReturn(100_000_000L);
        when(controller.handleOutcome(any())).thenAnswer(inv -> {
            int call = handleCount.incrementAndGet();
            if (call <= overloadCount) {
                return WriteDecision.pauseAndRetry(pauseMs);
            }
            return WriteDecision.success();
        });

        ConcurrentBulkWriter writer = new ConcurrentBulkWriter(
            client,
            mockThreadPool(),
            controller,
            AoscLogger.create(ConcurrentBulkWriter.class)
        );
        // Need enough ops: each overload re-queues the failed op, then we need one more for success
        DocSource<TestMetrics> source = sourceOf(op("a"), op("b"));

        long start = System.nanoTime();
        CompletableFuture<Void> future = writer.consumeAsync(source, batch -> {});
        future.get(15, TimeUnit.SECONDS);
        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);

        long expectedMinMs = overloadCount * pauseMs;
        assertTrue(future.isDone());
        assertFalse(future.isCompletedExceptionally());
        assertTrue(
            overloadCount
                + " consecutive pauses of "
                + pauseMs
                + "ms should total ≥"
                + expectedMinMs
                + "ms, but elapsed was "
                + elapsedMs
                + "ms",
            elapsedMs >= expectedMinMs
        );
    }

    /**
     * With concurrency=4 and ALL batches returning overload, verifies that
     * the writer respects the overload pauses and does not exhaust them
     * faster than expected. The total time should reflect at least some
     * pauses being applied, not near-zero as the buggy code would do.
     */
    public void testAllBatchesOverloadWithHighConcurrency() throws Exception {
        Client client = mockClientWithBulkResponse();
        AtomicInteger handleCount = new AtomicInteger();
        int maxOverloads = 4;
        long pauseMs = 300;

        WriteController controller = mock(WriteController.class);
        when(controller.concurrency()).thenReturn(4);
        when(controller.nextBatchSize()).thenReturn(1);
        when(controller.maxBatchBytes()).thenReturn(100_000_000L);
        when(controller.handleOutcome(any())).thenAnswer(inv -> {
            int call = handleCount.incrementAndGet();
            if (call <= maxOverloads) {
                return WriteDecision.pauseAndRetry(pauseMs);
            }
            return WriteDecision.success();
        });

        ConcurrentBulkWriter writer = new ConcurrentBulkWriter(
            client,
            mockThreadPool(),
            controller,
            AoscLogger.create(ConcurrentBulkWriter.class)
        );
        DocSource<TestMetrics> source = sourceOf(op("1"), op("2"), op("3"), op("4"), op("5"), op("6"), op("7"), op("8"));

        long start = System.nanoTime();
        CompletableFuture<Void> future = writer.consumeAsync(source, batch -> {});
        future.get(15, TimeUnit.SECONDS);
        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);

        assertTrue(future.isDone());
        assertFalse(future.isCompletedExceptionally());
        // At least one full pause cycle must have been applied
        assertTrue(
            "With "
                + maxOverloads
                + " overloads at "
                + pauseMs
                + "ms and concurrency=4, "
                + "should take ≥"
                + pauseMs
                + "ms, but was "
                + elapsedMs
                + "ms",
            elapsedMs >= pauseMs
        );
    }

    /**
     * With concurrency=2 and escalating pause durations, verifies that the
     * longest pause (from accumulateAndGet with Math::max) is the one applied,
     * not an earlier shorter one.
     */
    public void testEscalatingPauseDurationsApplyLongest() throws Exception {
        Client client = mockClientWithBulkResponse();
        AtomicInteger handleCount = new AtomicInteger();
        long shortPause = 200;
        long longPause = 600;

        WriteController controller = mock(WriteController.class);
        when(controller.concurrency()).thenReturn(2);
        when(controller.nextBatchSize()).thenReturn(1);
        when(controller.maxBatchBytes()).thenReturn(100_000_000L);
        when(controller.handleOutcome(any())).thenAnswer(inv -> {
            int call = handleCount.incrementAndGet();
            if (call == 1) return WriteDecision.pauseAndRetry(shortPause);
            if (call == 2) return WriteDecision.pauseAndRetry(longPause);
            return WriteDecision.success();
        });

        ConcurrentBulkWriter writer = new ConcurrentBulkWriter(
            client,
            mockThreadPool(),
            controller,
            AoscLogger.create(ConcurrentBulkWriter.class)
        );
        DocSource<TestMetrics> source = sourceOf(op("a"), op("b"), op("c"), op("d"));

        long start = System.nanoTime();
        CompletableFuture<Void> future = writer.consumeAsync(source, batch -> {});
        future.get(15, TimeUnit.SECONDS);
        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);

        assertTrue(future.isDone());
        assertFalse(future.isCompletedExceptionally());
        // The longer pause should be the one that's honoured (accumulateAndGet with max)
        assertTrue(
            "With concurrent pauses of "
                + shortPause
                + "ms and "
                + longPause
                + "ms, "
                + "should apply the longer one. Elapsed: "
                + elapsedMs
                + "ms",
            elapsedMs >= longPause
        );
    }

    /**
     * With concurrency=1 and many ops (16), verifies that interleaving overload
     * and success outcomes correctly applies pauses for each overload without
     * skipping any, and still completes all ops.
     */
    public void testInterleavedOverloadAndSuccessWithManyOps() throws Exception {
        Client client = mockClientWithBulkResponse();
        AtomicInteger handleCount = new AtomicInteger();
        AtomicInteger progressCount = new AtomicInteger();
        long pauseMs = 200;

        WriteController controller = mock(WriteController.class);
        when(controller.concurrency()).thenReturn(1);
        when(controller.nextBatchSize()).thenReturn(1);
        when(controller.maxBatchBytes()).thenReturn(100_000_000L);
        when(controller.handleOutcome(any())).thenAnswer(inv -> {
            int call = handleCount.incrementAndGet();
            // Overload every 3rd call: calls 3, 6, 9 are overloads
            if (call % 3 == 0) {
                return WriteDecision.pauseAndRetry(pauseMs);
            }
            return WriteDecision.success();
        });

        ConcurrentBulkWriter writer = new ConcurrentBulkWriter(
            client,
            mockThreadPool(),
            controller,
            AoscLogger.create(ConcurrentBulkWriter.class)
        );
        DocSource<TestMetrics> source = sourceOf(
            op("1"),
            op("2"),
            op("3"),
            op("4"),
            op("5"),
            op("6"),
            op("7"),
            op("8"),
            op("9"),
            op("10"),
            op("11"),
            op("12"),
            op("13"),
            op("14"),
            op("15"),
            op("16")
        );

        long start = System.nanoTime();
        CompletableFuture<Void> future = writer.consumeAsync(source, batch -> progressCount.incrementAndGet());
        future.get(15, TimeUnit.SECONDS);
        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);

        assertTrue(future.isDone());
        assertFalse(future.isCompletedExceptionally());
        assertEquals("All 16 ops should complete via progress callback", 16, progressCount.get());

        // Count how many overloads: every 3rd handleOutcome call.
        // Total handleOutcome calls = 16 successes + N overloads (each overload re-queues 1 op,
        // which needs another handleOutcome call). The exact count depends on interleaving,
        // but there should be at least 5 overloads (calls 3,6,9,12,15).
        int minOverloads = 5;
        long expectedMinMs = minOverloads * pauseMs;
        assertTrue(
            "Interleaved overloads should add at least " + expectedMinMs + "ms total pause, " + "but elapsed was " + elapsedMs + "ms",
            elapsedMs >= expectedMinMs
        );
    }

    /**
     * Verifies that cancel() interrupts a long overload pause promptly.
     * The writer should not block for the full pause duration after cancel.
     */
    public void testCancelInterruptsOverloadPause() throws Exception {
        Client client = mockClientWithBulkResponse();
        AtomicInteger handleCount = new AtomicInteger();
        long longPauseMs = 10_000; // 10 seconds — we should NOT wait this long

        WriteController controller = mock(WriteController.class);
        when(controller.concurrency()).thenReturn(1);
        when(controller.nextBatchSize()).thenReturn(1);
        when(controller.maxBatchBytes()).thenReturn(100_000_000L);
        when(controller.handleOutcome(any())).thenAnswer(inv -> {
            int call = handleCount.incrementAndGet();
            if (call == 1) {
                return WriteDecision.pauseAndRetry(longPauseMs);
            }
            return WriteDecision.success();
        });

        ConcurrentBulkWriter writer = new ConcurrentBulkWriter(
            client,
            mockThreadPool(),
            controller,
            AoscLogger.create(ConcurrentBulkWriter.class)
        );
        DocSource<TestMetrics> source = sourceOf(op("a"), op("b"), op("c"));

        long start = System.nanoTime();
        CompletableFuture<Void> future = writer.consumeAsync(source, batch -> {});

        // Wait until the writer has processed at least one outcome (entered the pause), then cancel
        assertBusy(() -> assertTrue("Writer should have processed at least one outcome", handleCount.get() >= 1), 5, TimeUnit.SECONDS);
        writer.cancel();

        try {
            future.get(5, TimeUnit.SECONDS);
        } catch (Exception e) {
            // CancellationException or ExecutionException expected
        }
        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);

        assertTrue("Cancel should interrupt the 10s pause promptly. Elapsed: " + elapsedMs + "ms", elapsedMs < 5000);
    }

    // ---- helpers ----

    private ConcurrentBulkWriter writer(int concurrency) {
        return new ConcurrentBulkWriter(
            mockClientWithBulkResponse(),
            mockThreadPool(),
            mockController(concurrency),
            AoscLogger.create(ConcurrentBulkWriter.class)
        );
    }

    private static WriteController mockController() {
        return mockController(1);
    }

    private static WriteController mockController(int concurrency) {
        WriteController controller = mock(WriteController.class);
        when(controller.nextBatchSize()).thenReturn(500);
        when(controller.handleOutcome(any())).thenReturn(WriteDecision.success());
        when(controller.maxBatchBytes()).thenReturn(100_000_000L);
        when(controller.concurrency()).thenReturn(concurrency);
        return controller;
    }

    private ThreadPool mockThreadPool() {
        ThreadPool tp = mock(ThreadPool.class);
        when(tp.getThreadContext()).thenReturn(new ThreadContext(Settings.EMPTY));
        when(tp.generic()).thenReturn(executor);
        // Support AsyncUtils.scheduleDelayed: execute the task after the delay on the executor
        doAnswer(inv -> {
            Runnable task = inv.getArgument(0);
            org.opensearch.common.unit.TimeValue delay = inv.getArgument(1);
            executor.submit(() -> {
                try {
                    Thread.sleep(delay.millis());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                task.run();
            });
            return null;
        }).when(tp).schedule(any(Runnable.class), any(TimeValue.class), any(String.class));
        return tp;
    }

    private static Client mockClientWithBulkResponse() {
        Client client = mock(Client.class);
        doAnswer(invocation -> {
            BulkRequest request = invocation.getArgument(0);
            @SuppressWarnings("unchecked")
            ActionListener<BulkResponse> listener = invocation.getArgument(1);
            BulkResponse response = new BulkResponse(new BulkItemResponse[0], 10);
            listener.onResponse(response);
            return null;
        }).when(client).bulk(any(BulkRequest.class), any());

        return client;
    }

    private static DocSource<TestMetrics> emptySource() {
        return new ThreadSafeDocSource<>(Collections.emptyIterator());
    }

    @SafeVarargs
    private static DocSource<TestMetrics> sourceOf(WriteOp<TestMetrics>... ops) {
        return new ThreadSafeDocSource<>(List.of(ops).iterator());
    }

    private static WriteOp<TestMetrics> op(String id) {
        return WriteOp.of(new IndexRequest("idx").id(id).source(Collections.singletonMap("f", id)), TestMetrics.ONE);
    }
}
