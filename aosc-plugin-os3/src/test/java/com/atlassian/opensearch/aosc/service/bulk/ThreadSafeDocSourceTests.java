/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */
package com.atlassian.opensearch.aosc.service.bulk;

import org.opensearch.action.index.IndexRequest;
import org.opensearch.test.OpenSearchTestCase;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

public class ThreadSafeDocSourceTests extends OpenSearchTestCase {

    // ---- poll basics ----

    public void testPollReturnsDocsInOrder() {
        List<WriteOp<TestMetrics>> ops = List.of(op("a"), op("b"), op("c"));
        ThreadSafeDocSource<TestMetrics> source = new ThreadSafeDocSource<>(ops.iterator());

        assertEquals("a", id(source.poll()));
        assertEquals("b", id(source.poll()));
        assertEquals("c", id(source.poll()));
        assertNull(source.poll());
    }

    public void testPollReturnsNullWhenEmpty() {
        ThreadSafeDocSource<TestMetrics> source = new ThreadSafeDocSource<>(Collections.emptyIterator());
        assertNull(source.poll());
        assertNull(source.poll());
    }

    // ---- isExhausted ----

    public void testIsExhaustedFalseWhenDocsRemain() {
        ThreadSafeDocSource<TestMetrics> source = new ThreadSafeDocSource<>(List.of(op("a")).iterator());
        assertFalse(source.isExhausted());
    }

    public void testIsExhaustedTrueAfterAllConsumed() {
        ThreadSafeDocSource<TestMetrics> source = new ThreadSafeDocSource<>(List.of(op("a")).iterator());
        source.poll();
        source.poll(); // triggers exhaustion
        assertTrue(source.isExhausted());
    }

    public void testIsExhaustedFalseWhenRetryPending() {
        ThreadSafeDocSource<TestMetrics> source = new ThreadSafeDocSource<>(Collections.emptyIterator());
        assertNull(source.poll()); // exhaust source
        source.returnForRetry(List.of(op("retry")));
        assertFalse(source.isExhausted());
    }

    // ---- returnForRetry ----

    public void testRetryOpsServedBeforeFreshDocs() {
        ThreadSafeDocSource<TestMetrics> source = new ThreadSafeDocSource<>(List.of(op("fresh")).iterator());
        source.returnForRetry(List.of(op("retry1"), op("retry2")));

        assertEquals("retry1", id(source.poll()));
        assertEquals("retry2", id(source.poll()));
        assertEquals("fresh", id(source.poll()));
        assertNull(source.poll());
    }

    public void testRetryPreservesOrder() {
        ThreadSafeDocSource<TestMetrics> source = new ThreadSafeDocSource<>(Collections.emptyIterator());
        source.poll(); // exhaust
        source.returnForRetry(List.of(op("a"), op("b"), op("c")));

        assertEquals("a", id(source.poll()));
        assertEquals("b", id(source.poll()));
        assertEquals("c", id(source.poll()));
        assertNull(source.poll());
    }

    public void testMultipleRetryBatchesServedInFifoOrder() {
        ThreadSafeDocSource<TestMetrics> source = new ThreadSafeDocSource<>(Collections.emptyIterator());
        source.poll(); // exhaust
        source.returnForRetry(List.of(op("batch1-a"), op("batch1-b")));
        source.returnForRetry(List.of(op("batch2-a")));

        // First batch was addFirst in reverse, so batch1 items are at front.
        // Second batch was addFirst after, so batch2 items are now at front.
        // ConcurrentLinkedDeque.addFirst puts batch2 before batch1.
        assertEquals("batch2-a", id(source.poll()));
        assertEquals("batch1-a", id(source.poll()));
        assertEquals("batch1-b", id(source.poll()));
    }

    // ---- concurrent returnForRetry safety ----

    public void testConcurrentReturnForRetryDoesNotLoseOps() throws Exception {
        ThreadSafeDocSource<TestMetrics> source = new ThreadSafeDocSource<>(Collections.emptyIterator());
        source.poll(); // exhaust

        int threadCount = 8;
        int opsPerThread = 100;
        CountDownLatch latch = new CountDownLatch(threadCount);
        Thread[] threads = new Thread[threadCount];

        for (int t = 0; t < threadCount; t++) {
            final int threadId = t;
            threads[t] = new Thread(() -> {
                for (int i = 0; i < opsPerThread; i++) {
                    source.returnForRetry(List.of(op("t" + threadId + "-" + i)));
                }
                latch.countDown();
            });
        }

        for (Thread t : threads)
            t.start();
        assertTrue(latch.await(10, TimeUnit.SECONDS));

        int polled = 0;
        while (source.poll() != null)
            polled++;
        assertEquals(threadCount * opsPerThread, polled);
    }

    // ---- null rejection ----

    public void testConstructorRejectsNull() {
        expectThrows(NullPointerException.class, () -> new ThreadSafeDocSource<TestMetrics>(null));
    }

    // ---- helpers ----

    private static WriteOp<TestMetrics> op(String id) {
        return WriteOp.of(new IndexRequest("idx").id(id).source(Collections.singletonMap("f", id)), TestMetrics.ONE);
    }

    private static String id(WriteOp<TestMetrics> op) {
        return op.request().id();
    }

    static final class TestMetrics {
        static final TestMetrics ZERO = new TestMetrics(0);
        static final TestMetrics ONE = new TestMetrics(1);
        final int count;

        TestMetrics(int count) {
            this.count = count;
        }
    }
}
