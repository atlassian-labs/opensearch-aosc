/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */
package com.atlassian.opensearch.aosc.service.worker;

import com.atlassian.opensearch.aosc.compat.MockClientFactory;
import com.atlassian.opensearch.aosc.model.ShardRoutingMode;
import com.atlassian.opensearch.aosc.service.adaptive.FixedBatchSizeController;
import com.atlassian.opensearch.aosc.service.bulk.ConcurrentBulkWriter;
import com.atlassian.opensearch.aosc.service.bulk.OverloadBackoff;
import com.atlassian.opensearch.aosc.service.bulk.SimpleWriteController;
import com.atlassian.opensearch.aosc.service.worker.TranslogReplayEngine.ReplayResult;
import com.atlassian.opensearch.aosc.transform.IdentityTransformFunction;
import com.atlassian.opensearch.aosc.utils.AoscLogger;
import com.atlassian.opensearch.aosc.utils.AsyncClientHelper;
import com.atlassian.opensearch.aosc.utils.ShardHandle;

import org.opensearch.action.bulk.BulkItemResponse;
import org.opensearch.action.bulk.BulkRequest;
import org.opensearch.action.bulk.BulkResponse;
import org.opensearch.core.action.ActionListener;
import org.opensearch.core.index.Index;
import org.opensearch.core.index.shard.ShardId;
import org.opensearch.index.shard.IndexShard;
import org.opensearch.index.translog.Translog;
import org.opensearch.test.OpenSearchTestCase;
import org.opensearch.threadpool.TestThreadPool;
import org.opensearch.threadpool.ThreadPool;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link TranslogReplayEngine} covering:
 * - Constructor validation
 * - startCallback contract (fires exactly once)
 * - progressCallback contract (fires per batch with correct values)
 * - replayRange: basic flow, empty range, INDEX/DELETE/NO_OP handling
 * - Cancel mid-replay
 * - Result contains authoritative toSeqNo
 * - Single-use guard (double start)
 */
public class TranslogReplayEngineTests extends OpenSearchTestCase {

    private TestThreadPool threadPool;

    @Override
    public void setUp() throws Exception {
        super.setUp();
        threadPool = new TestThreadPool(getTestName());
    }

    @Override
    public void tearDown() throws Exception {
        ThreadPool.terminate(threadPool, 10, TimeUnit.SECONDS);
        super.tearDown();
    }

    // ---- Constructor validation ----

    public void testConstructorRejectsNullWriter() {
        IndexShard shard = mockShard();
        ShardHandle shardHandle = new ShardHandle(AoscLogger.create(ShardHandle.class), shard, threadPool);
        expectThrows(
            NullPointerException.class,
            () -> new TranslogReplayEngine(
                AoscLogger.create(TranslogReplayEngine.class),
                null,
                shardHandle,
                "target",
                IdentityTransformFunction.INSTANCE,
                ShardRoutingMode.BULK_API,
                1,
                null,
                null,
                null,
                threadPool
            )
        );
    }

    public void testConstructorRejectsNullIndexShard() {
        expectThrows(
            NullPointerException.class,
            () -> new TranslogReplayEngine(
                AoscLogger.create(TranslogReplayEngine.class),
                createWriter(dummyHelper()),
                null,
                "target",
                IdentityTransformFunction.INSTANCE,
                ShardRoutingMode.BULK_API,
                1,
                null,
                null,
                null,
                threadPool
            )
        );
    }

    public void testConstructorRejectsNullTransform() {
        IndexShard shard = mockShard();
        ShardHandle shardHandle = new ShardHandle(AoscLogger.create(ShardHandle.class), shard, threadPool);
        expectThrows(
            NullPointerException.class,
            () -> new TranslogReplayEngine(
                AoscLogger.create(TranslogReplayEngine.class),
                createWriter(dummyHelper()),
                shardHandle,
                "target",
                null,
                ShardRoutingMode.BULK_API,
                1,
                null,
                null,
                null,
                threadPool
            )
        );
    }

    // ---- Single-use guard ----

    public void testDoubleStartReplayRangeThrowsIllegalStateException() throws Exception {
        IndexShard shard = mockShard();
        var helper = mockBulkHelper();
        when(shard.newChangesSnapshot(any(), anyLong(), anyLong(), anyBoolean(), anyBoolean())).thenReturn(
            new ListSnapshot(Collections.emptyList())
        );

        TranslogReplayEngine engine = createEngine(helper, shard);
        engine.replayRange(0, -1); // first call succeeds
        // Second call throws — engine is single-use
        expectThrows(IllegalStateException.class, () -> engine.replayRange(0, -1));
    }

    // ---- startCallback contract ----

    public void testStartCallbackFiresOnReplayRange() throws Exception {
        IndexShard shard = mockShard();
        var helper = mockBulkHelper();
        mockSnapshotWithOps(shard); // 3 INDEX ops at seqNo 10,11,12

        AtomicBoolean callbackFired = new AtomicBoolean(false);

        TranslogReplayEngine engine = createEngine(helper, shard, (from, to) -> callbackFired.set(true), null);

        assertFalse("Callback should not fire before start", callbackFired.get());
        CompletableFuture<ReplayResult> future = engine.replayRange(10, 12);
        future.get(5, TimeUnit.SECONDS);
        assertTrue("Callback should fire on replayRange", callbackFired.get());
    }

    public void testStartCallbackFiresExactlyOnceOnReplayRange() throws Exception {
        IndexShard shard = mockShard();
        var helper = mockBulkHelper();
        mockSnapshotWithOps(shard);

        AtomicInteger callCount = new AtomicInteger(0);

        TranslogReplayEngine engine = createEngine(helper, shard, (from, to) -> callCount.incrementAndGet(), null);

        engine.replayRange(10, 12).get(5, TimeUnit.SECONDS);
        try {
            engine.replayRange(10, 12); // second call throws
        } catch (IllegalStateException e) {
            // expected
        }
        assertEquals("startCallback must fire exactly once", 1, callCount.get());
    }

    public void testStartCallbackDoesNotFireOnCancel() {
        IndexShard shard = mockShard();
        AtomicBoolean callbackFired = new AtomicBoolean(false);

        TranslogReplayEngine engine = createEngine(dummyHelper(), shard, (from, to) -> callbackFired.set(true), null);

        engine.cancel();
        assertFalse("startCallback should not fire when only cancel() is called", callbackFired.get());
    }

    // ---- replayRange: empty range ----

    public void testReplayRangeEmptyWhenFromExceedsTo() throws Exception {
        IndexShard shard = mockShard();
        TranslogReplayEngine engine = createEngine(dummyHelper(), shard);

        CompletableFuture<ReplayResult> future = engine.replayRange(100, 50);
        ReplayResult result = future.get(5, TimeUnit.SECONDS);

        assertEquals("Empty range should replay 0 ops", 0, result.operationsReplayed());
        assertEquals("Empty range should skip 0 ops", 0, result.operationsSkipped());
        assertEquals("targetSeqNo should be the requested end", 50, result.targetSeqNo());
    }

    public void testReplayRangeEmptyWhenBothNegative() throws Exception {
        IndexShard shard = mockShard();
        TranslogReplayEngine engine = createEngine(dummyHelper(), shard);

        CompletableFuture<ReplayResult> future = engine.replayRange(-1, -1);
        ReplayResult result = future.get(5, TimeUnit.SECONDS);

        assertEquals(0, result.operationsReplayed());
    }

    // ---- replayRange: basic INDEX ops ----

    public void testReplayRangeWithIndexOps() throws Exception {
        IndexShard shard = mockShard();
        var helper = mockBulkHelper();
        mockSnapshotWithOps(shard); // 3 INDEX ops at seqNo 10,11,12

        TranslogReplayEngine engine = createEngine(helper, shard);

        CompletableFuture<ReplayResult> future = engine.replayRange(10, 12);
        ReplayResult result = future.get(5, TimeUnit.SECONDS);

        assertEquals("Should replay 3 INDEX ops", 3, result.operationsReplayed());
        assertEquals("Should skip 0 ops", 0, result.operationsSkipped());
        assertEquals("targetSeqNo should be authoritative", 12, result.targetSeqNo());
    }

    // ---- replayRange: mixed ops (INDEX + DELETE + NO_OP) ----

    public void testReplayRangeWithMixedOps() throws Exception {
        IndexShard shard = mockShard();
        var helper = mockBulkHelper();

        List<Translog.Operation> ops = new ArrayList<>();
        ops.add(makeIndexOp("doc1", 10, "{\"field\":\"value1\"}"));
        ops.add(new Translog.NoOp(11, 1, "gap-fill"));
        ops.add(new Translog.Delete("doc2", 12, 1));
        ops.add(makeIndexOp("doc3", 13, "{\"field\":\"value3\"}"));

        when(shard.newChangesSnapshot(any(), anyLong(), anyLong(), anyBoolean(), anyBoolean())).thenReturn(new ListSnapshot(ops));

        TranslogReplayEngine engine = createEngine(helper, shard);

        CompletableFuture<ReplayResult> future = engine.replayRange(10, 13);
        ReplayResult result = future.get(5, TimeUnit.SECONDS);

        assertEquals("Should replay 3 ops (2 INDEX + 1 DELETE)", 3, result.operationsReplayed());
        assertEquals("Should skip 1 NO_OP", 1, result.operationsSkipped());
        assertEquals("targetSeqNo should be authoritative end", 13, result.targetSeqNo());
    }

    // ---- replayRange: NO_OP only (no bulk request) ----

    public void testReplayRangeWithOnlyNoOps() throws Exception {
        IndexShard shard = mockShard();

        List<Translog.Operation> ops = new ArrayList<>();
        ops.add(new Translog.NoOp(10, 1, "gap-fill-1"));
        ops.add(new Translog.NoOp(11, 1, "gap-fill-2"));

        when(shard.newChangesSnapshot(any(), anyLong(), anyLong(), anyBoolean(), anyBoolean())).thenReturn(new ListSnapshot(ops));

        TranslogReplayEngine engine = createEngine(dummyHelper(), shard);

        CompletableFuture<ReplayResult> future = engine.replayRange(10, 11);
        ReplayResult result = future.get(5, TimeUnit.SECONDS);

        assertEquals("Should replay 0 data ops", 0, result.operationsReplayed());
        assertEquals("Should skip 2 NO_OPs", 2, result.operationsSkipped());
    }

    // ---- progressCallback contract ----

    public void testProgressCallbackFiresWithCorrectValues() throws Exception {
        IndexShard shard = mockShard();
        var helper = mockBulkHelper();
        mockSnapshotWithOps(shard); // 3 INDEX ops

        List<long[]> progressUpdates = Collections.synchronizedList(new ArrayList<>());
        TranslogReplayEngine.ProgressCallback progressCallback = (opsReplayed, opsSkipped, lastSeqNo, targetSeqNo, round) -> {
            progressUpdates.add(new long[] { opsReplayed, opsSkipped, lastSeqNo });
        };

        TranslogReplayEngine engine = createEngine(helper, shard, null, progressCallback);

        engine.replayRange(10, 12).get(5, TimeUnit.SECONDS);

        assertFalse("Progress callback should have been called", progressUpdates.isEmpty());

        // Last progress update should have final counts
        long[] lastUpdate = progressUpdates.get(progressUpdates.size() - 1);
        assertEquals("Final opsReplayed should be 3", 3, lastUpdate[0]);
        assertEquals("Final opsSkipped should be 0", 0, lastUpdate[1]);
        assertTrue("Final lastSeqNo should be >= 10", lastUpdate[2] >= 10);
    }

    public void testProgressCallbackFiresPerBatch() throws Exception {
        IndexShard shard = mockShard();
        ShardHandle shardHandle = new ShardHandle(AoscLogger.create(ShardHandle.class), shard, threadPool);
        var helper = mockBulkHelper();

        // Create 5 ops but with batchSize=2 → should need 3 batches (2+2+1)
        List<Translog.Operation> ops = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            ops.add(makeIndexOp("doc" + i, 10 + i, "{\"f\":\"v" + i + "\"}"));
        }
        when(shard.newChangesSnapshot(any(), anyLong(), anyLong(), anyBoolean(), anyBoolean())).thenReturn(new ListSnapshot(ops));

        AtomicInteger progressCallCount = new AtomicInteger(0);
        TranslogReplayEngine.ProgressCallback progressCallback = (r, s, l, t, round) -> progressCallCount.incrementAndGet();

        TranslogReplayEngine engine = new TranslogReplayEngine(
            AoscLogger.create(TranslogReplayEngine.class),
            createWriter(helper, 2),
            shardHandle,
            "target",
            IdentityTransformFunction.INSTANCE,
            ShardRoutingMode.BULK_API,
            1,
            null,
            null,
            progressCallback,
            threadPool
        );

        engine.replayRange(10, 14).get(5, TimeUnit.SECONDS);

        assertTrue(
            "Progress should fire multiple times for multi-batch replay, got " + progressCallCount.get(),
            progressCallCount.get() >= 3
        );
    }

    // ---- Cancel mid-replay ----

    public void testCancelBeforeStartThrowsOnReplayRange() {
        IndexShard shard = mockShard();
        TranslogReplayEngine engine = createEngine(dummyHelper(), shard);

        engine.cancel();

        // replayRange() after cancel throws — engine is cancelled
        expectThrows(IllegalStateException.class, () -> engine.replayRange(0, 10));
    }

    @SuppressWarnings("unchecked")
    public void testCancelMidReplayStopsProcessing() throws Exception {
        IndexShard shard = mockShard();
        ShardHandle shardHandle = new ShardHandle(AoscLogger.create(ShardHandle.class), shard, threadPool);

        // Create many ops so cancel can interrupt mid-replay
        List<Translog.Operation> ops = new ArrayList<>();
        for (int i = 0; i < 100; i++) {
            ops.add(makeIndexOp("doc" + i, i, "{\"f\":\"v\"}"));
        }
        when(shard.newChangesSnapshot(any(), anyLong(), anyLong(), anyBoolean(), anyBoolean())).thenReturn(new ListSnapshot(ops));

        AtomicLong opsReported = new AtomicLong(0);

        // Mock client that cancels the engine after first bulk
        var client = MockClientFactory.mockClient();
        TranslogReplayEngine[] engineHolder = new TranslogReplayEngine[1];

        doAnswer(invocation -> {
            ActionListener<BulkResponse> listener = invocation.getArgument(1);
            // Cancel after first bulk completes
            if (engineHolder[0] != null) {
                engineHolder[0].cancel();
            }
            listener.onResponse(new BulkResponse(new BulkItemResponse[0], 1));
            return null;
        }).when(client).bulk(any(BulkRequest.class), any());

        var helper = AsyncClientHelper.wrap(client);

        TranslogReplayEngine engine = new TranslogReplayEngine(
            AoscLogger.create(TranslogReplayEngine.class),
            createWriter(helper, 10),
            shardHandle,
            "target",
            IdentityTransformFunction.INSTANCE,
            ShardRoutingMode.BULK_API,
            1,
            null,
            null,
            (r, s, l, t, round) -> opsReported.set(r),
            threadPool
        );
        engineHolder[0] = engine;

        CompletableFuture<ReplayResult> future = engine.replayRange(0, 99);

        // Should complete exceptionally with CancellationException
        try {
            future.get(5, TimeUnit.SECONDS);
            fail("Expected CancellationException");
        } catch (CancellationException e) {
            // expected — CompletableFuture.get() throws CancellationException directly
        } catch (ExecutionException e) {
            assertTrue("Expected CancellationException but got " + e.getCause().getClass(), e.getCause() instanceof CancellationException);
        }

        // Should have stopped before processing all 100 ops
        assertTrue("Should have stopped early, but processed " + opsReported.get(), opsReported.get() < 100);
    }

    // ---- Result: toSeqNo is authoritative ----

    public void testResultToSeqNoIsAuthoritativeNotLastProcessed() throws Exception {
        IndexShard shard = mockShard();
        var helper = mockBulkHelper();

        // Only 2 ops but toSeqNo is 100 — result.toSeqNo must be 100, not the last op's seqNo
        List<Translog.Operation> ops = new ArrayList<>();
        ops.add(makeIndexOp("doc1", 10, "{\"f\":\"v1\"}"));
        ops.add(makeIndexOp("doc2", 20, "{\"f\":\"v2\"}"));

        when(shard.newChangesSnapshot(any(), anyLong(), anyLong(), anyBoolean(), anyBoolean())).thenReturn(new ListSnapshot(ops));

        TranslogReplayEngine engine = createEngine(helper, shard);
        ReplayResult result = engine.replayRange(10, 100).get(5, TimeUnit.SECONDS);

        assertEquals("targetSeqNo should be the requested range end, not the last processed op", 100, result.targetSeqNo());
    }

    // ---- Snapshot error handling ----

    public void testSnapshotOpenFailureCompletesExceptionally() throws Exception {
        IndexShard shard = mockShard();
        when(shard.newChangesSnapshot(any(), anyLong(), anyLong(), anyBoolean(), anyBoolean())).thenThrow(
            new IOException("snapshot failed")
        );

        TranslogReplayEngine engine = createEngine(dummyHelper(), shard);

        CompletableFuture<ReplayResult> future = engine.replayRange(0, 10);
        assertTrue(future.isCompletedExceptionally());
    }

    // ---- ReplayResult ----

    public void testReplayResultFields() {
        ReplayResult result = new ReplayResult(95L, 100L, 50, 3);
        assertEquals(95, result.lastProcessedSeqNo());
        assertEquals(100, result.targetSeqNo());
        assertEquals(50, result.operationsReplayed());
        assertEquals(3, result.operationsSkipped());
    }

    // ---- Lifecycle and cleanup (B008) ----

    public void testCancelBeforeStartReturnsCompletedFuture() {
        TranslogReplayEngine engine = createEngine(dummyHelper(), mockShard());
        CompletableFuture<ReplayResult> future = engine.cancel();
        assertTrue("Future should be done after cancel", future.isDone());
        assertTrue("Future should be completed exceptionally", future.isCompletedExceptionally());
    }

    public void testCancelReturnsSameFuture() {
        TranslogReplayEngine engine = createEngine(dummyHelper(), mockShard());
        CompletableFuture<ReplayResult> f1 = engine.cancel();
        CompletableFuture<ReplayResult> f2 = engine.cancel();
        assertSame("cancel() should return the same future", f1, f2);
    }

    public void testProgressCallbackFiresOnCancel() {
        AtomicBoolean progressCalled = new AtomicBoolean(false);
        TranslogReplayEngine engine = createEngine(
            dummyHelper(),
            new ShardHandle(AoscLogger.create(ShardHandle.class), mockShard(), threadPool),
            null,
            (replayed, skipped, lastSeqNo, targetSeqNo, round) -> progressCalled.set(true)
        );
        engine.cancel();
        assertTrue("Progress callback should fire on cancel (terminal)", progressCalled.get());
    }

    public void testProgressCallbackFiresOnFailure() throws Exception {
        AtomicBoolean progressCalled = new AtomicBoolean(false);
        IndexShard shard = mockShard();
        when(shard.newChangesSnapshot(any(), anyLong(), anyLong(), anyBoolean(), anyBoolean())).thenThrow(
            new IOException("mock snapshot failure")
        );

        TranslogReplayEngine engine = createEngine(
            dummyHelper(),
            shard,
            null,
            (replayed, skipped, lastSeqNo, targetSeqNo, round) -> progressCalled.set(true)
        );
        CompletableFuture<ReplayResult> future = engine.replayRange(0, 10);
        assertTrue("Future should complete exceptionally", future.isCompletedExceptionally());
        assertTrue("Progress callback should fire on failure (terminal)", progressCalled.get());
    }

    public void testSnapshotClosedOnSuccess() throws Exception {
        AtomicBoolean snapshotClosed = new AtomicBoolean(false);
        IndexShard shard = mockShard();
        List<Translog.Operation> ops = new ArrayList<>();
        ops.add(makeIndexOp("doc1", 10, "{\"field\":\"value\"}"));

        Translog.Snapshot trackingSnapshot = new ListSnapshot(ops) {
            @Override
            public void close() {
                snapshotClosed.set(true);
            }
        };
        when(shard.newChangesSnapshot(any(), anyLong(), anyLong(), anyBoolean(), anyBoolean())).thenReturn(trackingSnapshot);

        TranslogReplayEngine engine = createEngine(mockBulkHelper(), shard);
        CompletableFuture<ReplayResult> future = engine.replayRange(10, 10);
        ReplayResult result = future.get(5, TimeUnit.SECONDS);
        assertNotNull(result);
        assertBusy(() -> assertTrue("Snapshot should be closed after successful replay", snapshotClosed.get()));
    }

    public void testSnapshotClosedOnReadFailure() throws Exception {
        AtomicBoolean snapshotClosed = new AtomicBoolean(false);
        IndexShard shard = mockShard();

        Translog.Snapshot failingSnapshot = new Translog.Snapshot() {
            @Override
            public int totalOperations() {
                return 1;
            }

            @Override
            public Translog.Operation next() throws IOException {
                throw new IOException("mock read failure");
            }

            @Override
            public void close() {
                snapshotClosed.set(true);
            }
        };
        when(shard.newChangesSnapshot(any(), anyLong(), anyLong(), anyBoolean(), anyBoolean())).thenReturn(failingSnapshot);

        TranslogReplayEngine engine = createEngine(mockBulkHelper(), shard);
        CompletableFuture<ReplayResult> future = engine.replayRange(10, 10);
        try {
            future.get(5, TimeUnit.SECONDS);
            fail("Expected exceptional completion");
        } catch (ExecutionException e) {
            // expected
        }
        assertTrue("Future should complete exceptionally", future.isCompletedExceptionally());
        assertBusy(() -> assertTrue("Snapshot should be closed on read failure", snapshotClosed.get()));
    }

    // ======== Helpers ========

    private static IndexShard mockShard() {
        IndexShard shard = mock(IndexShard.class);
        when(shard.shardId()).thenReturn(new ShardId(new Index("test-index", "uuid"), 0));
        return shard;
    }

    private static AsyncClientHelper dummyHelper() {
        return AsyncClientHelper.wrap(MockClientFactory.mockClient());
    }

    private TranslogReplayEngine createEngine(AsyncClientHelper helper, IndexShard shard) {
        return createEngine(helper, new ShardHandle(AoscLogger.create(ShardHandle.class), shard, threadPool), null, null);
    }

    private TranslogReplayEngine createEngine(AsyncClientHelper helper, ShardHandle shardHandle) {
        return createEngine(helper, shardHandle, null, null);
    }

    private TranslogReplayEngine createEngine(
        AsyncClientHelper helper,
        IndexShard shard,
        TranslogReplayEngine.StartCallback startCallback,
        TranslogReplayEngine.ProgressCallback progressCallback
    ) {
        return createEngine(
            helper,
            new ShardHandle(AoscLogger.create(ShardHandle.class), shard, threadPool),
            startCallback,
            progressCallback
        );
    }

    private TranslogReplayEngine createEngine(
        AsyncClientHelper helper,
        ShardHandle shardHandle,
        TranslogReplayEngine.StartCallback startCallback,
        TranslogReplayEngine.ProgressCallback progressCallback
    ) {
        return new TranslogReplayEngine(
            AoscLogger.create(TranslogReplayEngine.class),
            createWriter(helper),
            shardHandle,
            "target",
            IdentityTransformFunction.INSTANCE,
            ShardRoutingMode.BULK_API,
            1,
            null,
            startCallback,
            progressCallback,
            threadPool
        );
    }

    private ConcurrentBulkWriter createWriter(AsyncClientHelper helper, int batchSize) {
        AoscLogger logger = AoscLogger.create(TranslogReplayEngineTests.class);
        SimpleWriteController controller = new SimpleWriteController(
            logger,
            new FixedBatchSizeController(() -> batchSize),
            () -> 1,
            () -> 100_000_000L,
            new OverloadBackoff(() -> 2_000L, () -> 120_000L, () -> 50)
        );
        return new ConcurrentBulkWriter(helper, threadPool, controller, logger);
    }

    private ConcurrentBulkWriter createWriter(AsyncClientHelper helper) {
        return createWriter(helper, 500);
    }

    @SuppressWarnings("unchecked")
    private static AsyncClientHelper mockBulkHelper() {
        var client = MockClientFactory.mockClient();
        doAnswer(invocation -> {
            ActionListener<BulkResponse> listener = invocation.getArgument(1);
            listener.onResponse(new BulkResponse(new BulkItemResponse[0], 1));
            return null;
        }).when(client).bulk(any(BulkRequest.class), any());
        return AsyncClientHelper.wrap(client);
    }

    private void mockSnapshotWithOps(IndexShard shard) throws IOException {
        List<Translog.Operation> ops = new ArrayList<>();
        ops.add(makeIndexOp("doc1", 10, "{\"field\":\"value1\"}"));
        ops.add(makeIndexOp("doc2", 11, "{\"field\":\"value2\"}"));
        ops.add(makeIndexOp("doc3", 12, "{\"field\":\"value3\"}"));
        when(shard.newChangesSnapshot(any(), anyLong(), anyLong(), anyBoolean(), anyBoolean())).thenReturn(new ListSnapshot(ops));
    }

    private static Translog.Index makeIndexOp(String id, long seqNo, String jsonSource) {
        return new Translog.Index(id, seqNo, 1, jsonSource.getBytes(StandardCharsets.UTF_8));
    }

    private static class ListSnapshot implements Translog.Snapshot {
        private final List<Translog.Operation> ops;
        private int index = 0;

        ListSnapshot(List<Translog.Operation> ops) {
            this.ops = new ArrayList<>(ops);
        }

        @Override
        public int totalOperations() {
            return ops.size();
        }

        @Override
        public Translog.Operation next() {
            if (index >= ops.size()) return null;
            return ops.get(index++);
        }

        @Override
        public void close() {
            // no-op
        }
    }
}
