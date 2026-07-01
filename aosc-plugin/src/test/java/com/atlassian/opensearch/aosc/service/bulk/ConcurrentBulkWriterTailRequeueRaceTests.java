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

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Regression test for the silent backfill data loss on shard 116 of the
 * {@code confluence-content-2.0.0-0000-2023.10.26 -> ...-v2} migration: 156 documents were missing
 * from the target even though the backfill reported {@code COMPLETED}.
 *
 * <p>The writer treated a transient {@code poll() == null} as exhaustion and exited while an
 * in-flight batch later requeued its ops (the async {@code PAUSE_AND_RETRY} path), stranding them.
 * The race needs concurrency &gt;= 2: the reader observes the empty source via the permit freed by a
 * successful batch while a different batch is still in flight and requeues afterwards.
 *
 * <p>This pins {@code concurrency = 2} and forces that interleaving deterministically (latch-gated,
 * no sleeps) by withholding the tail batch's completion until the reader has observed the source
 * empty. It fails on the unfixed writer and passes once completion is gated on true exhaustion.
 */
public class ConcurrentBulkWriterTailRequeueRaceTests extends OpenSearchTestCase {

    private final ExecutorService executor = Executors.newFixedThreadPool(4);

    @Override
    public void tearDown() throws Exception {
        executor.shutdownNow();
        super.tearDown();
    }

    public void testTailBatchRequeuedAfterReaderExitIsNotDropped() throws Exception {
        // doc-0 is written by the first (immediately successful) batch; doc-1 is the "tail" batch
        // whose completion we withhold so it requeues after the reader has left the loop.
        AtomicInteger bulkCalls = new AtomicInteger();
        AtomicReference<ActionListener<BulkResponse>> tailListener = new AtomicReference<>();
        CountDownLatch tailDispatched = new CountDownLatch(1);
        CountDownLatch sourceObservedEmpty = new CountDownLatch(1);

        // Flipped by the test thread immediately before it completes the withheld tail batch, so the
        // controller knows the next outcome it handles is the tail batch (the one to reject once).
        AtomicReference<Boolean> tailReleased = new AtomicReference<>(Boolean.FALSE);
        AtomicInteger rejections = new AtomicInteger();

        Client client = mock(Client.class);
        doAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            ActionListener<BulkResponse> listener = invocation.getArgument(1);
            if (bulkCalls.incrementAndGet() == 2) {
                // Tail batch: withhold completion and hand the listener to the test thread.
                tailListener.set(listener);
                tailDispatched.countDown();
            } else {
                // First batch (lets the reader poll the source empty) and the tail's eventual retry
                // on a fixed writer both succeed immediately.
                listener.onResponse(emptyBulkResponse());
            }
            return null;
        }).when(client).bulk(any(BulkRequest.class), any());

        WriteController controller = mock(WriteController.class);
        when(controller.concurrency()).thenReturn(2);
        when(controller.nextBatchSize()).thenReturn(1);
        when(controller.maxBatchBytes()).thenReturn(100_000_000L);
        when(controller.handleOutcome(any())).thenAnswer(inv -> {
            // Reject exactly once, and only the tail batch (released after the reader exited the
            // loop). Every other outcome — the first batch and the tail's eventual retry — succeeds.
            if (tailReleased.get() && rejections.compareAndSet(0, 1)) {
                return WriteDecision.pauseAndRetry(5);
            }
            return WriteDecision.success();
        });

        DocSource<TestMetrics> source = new ExhaustionSignallingDocSource(
            new ThreadSafeDocSource<>(List.of(op("doc-0"), op("doc-1")).iterator()),
            sourceObservedEmpty
        );

        List<String> writtenIds = Collections.synchronizedList(new ArrayList<>());
        ConcurrentBulkWriter writer = new ConcurrentBulkWriter(
            client,
            mockThreadPool(),
            controller,
            AoscLogger.create(ConcurrentBulkWriter.class)
        );

        CompletableFuture<Void> future = writer.consumeAsync(source, batch -> {
            for (WriteOp<TestMetrics> wo : batch.ops()) {
                if (wo.isWritable()) {
                    writtenIds.add(wo.request().id());
                }
            }
        });

        // Both gates are reached without any sleep: the tail batch is dispatched, and the reader has
        // polled the source empty (so it has decided "exhausted" while the tail is still in flight).
        assertTrue("Tail batch should have been dispatched", tailDispatched.await(10, TimeUnit.SECONDS));
        assertTrue("Reader should have observed the source empty", sourceObservedEmpty.await(10, TimeUnit.SECONDS));

        // Now complete the tail batch so its outcome handling requeues doc-1 after the reader exited.
        tailReleased.set(Boolean.TRUE);
        tailListener.get().onResponse(emptyBulkResponse());

        future.get(15, TimeUnit.SECONDS);

        assertTrue("Future should complete", future.isDone());
        assertFalse("Future should not fail", future.isCompletedExceptionally());

        // A requeued op must never be abandoned: the source is only truly exhausted once the retry
        // deque is also drained.
        assertTrue("Source must be truly exhausted at completion (retry deque drained)", source.isExhausted());

        List<String> sorted = new ArrayList<>(writtenIds);
        Collections.sort(sorted);
        assertEquals(
            "Both source docs must be written exactly once; the requeued tail doc must not be dropped",
            List.of("doc-0", "doc-1"),
            sorted
        );
    }

    /**
     * Decorates a {@link DocSource} and trips a latch the first time {@code poll()} returns
     * {@code null} — i.e. the moment the reader observes the source as empty and is about to treat it
     * as exhausted.
     */
    private static final class ExhaustionSignallingDocSource implements DocSource<TestMetrics> {
        private final DocSource<TestMetrics> delegate;
        private final CountDownLatch observedEmpty;

        ExhaustionSignallingDocSource(DocSource<TestMetrics> delegate, CountDownLatch observedEmpty) {
            this.delegate = delegate;
            this.observedEmpty = observedEmpty;
        }

        @Override
        public synchronized WriteOp<TestMetrics> poll() {
            WriteOp<TestMetrics> op = delegate.poll();
            if (op == null) {
                observedEmpty.countDown();
            }
            return op;
        }

        @Override
        public synchronized void returnForRetry(List<WriteOp<TestMetrics>> ops) {
            delegate.returnForRetry(ops);
        }

        @Override
        public synchronized boolean isExhausted() {
            return delegate.isExhausted();
        }
    }

    private static BulkResponse emptyBulkResponse() {
        return new BulkResponse(new BulkItemResponse[0], 1);
    }

    private ThreadPool mockThreadPool() {
        ThreadPool tp = mock(ThreadPool.class);
        when(tp.getThreadContext()).thenReturn(new ThreadContext(Settings.EMPTY));
        when(tp.generic()).thenReturn(executor);
        doAnswer(inv -> {
            Runnable task = inv.getArgument(0);
            TimeValue delay = inv.getArgument(1);
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

    private static WriteOp<TestMetrics> op(String id) {
        return WriteOp.of(new IndexRequest("idx").id(id).source(Collections.singletonMap("f", id)), TestMetrics.ONE);
    }
}
