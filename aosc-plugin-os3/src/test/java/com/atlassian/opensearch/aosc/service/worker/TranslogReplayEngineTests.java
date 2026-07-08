/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */
package com.atlassian.opensearch.aosc.service.worker;

import com.atlassian.opensearch.aosc.model.ShardRoutingMode;
import com.atlassian.opensearch.aosc.service.adaptive.FixedBatchSizeController;
import com.atlassian.opensearch.aosc.service.bulk.ConcurrentBulkWriter;
import com.atlassian.opensearch.aosc.service.bulk.OverloadBackoff;
import com.atlassian.opensearch.aosc.service.bulk.SimpleWriteController;
import com.atlassian.opensearch.aosc.service.worker.TranslogReplayEngine.ReplayResult;
import com.atlassian.opensearch.aosc.transform.IdentityTransformFunction;
import com.atlassian.opensearch.aosc.utils.AoscLogger;
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
import org.opensearch.transport.client.Client;

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
                createWriter(mock(Client.class)),
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
                createWriter(mock(Client.class)),
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
        Client client = mockBulkClient();
        when(shard.newChangesSnapshot(any(), anyLong(), anyLong(), anyBoolean(), anyBoolean())).thenReturn(
            new ListSnapshot(Collections.emptyList())
        );

        TranslogReplayEngine engine = createEngine(client, shard);
        engine.replayRange(0, -1); // first call succeeds
        // Second call throws — engine is single-use
        expectThrows(IllegalStateException.class, () -> engine.replayRange(0, -1));
    }

    // ---- startCallback contract ----

    public void testStartCallbackFiresOnReplayRange() throws Exception {
        IndexShard shard = mockShard();
        Client client = mockBulkClient();
        mockSnapshotWithOps(shard); // 3 INDEX ops at seqNo 10,11,12

        AtomicBoolean callbackFired = new AtomicBoolean(false);

        TranslogReplayEngine engine = createEngine(client, shard, (from, to) -> callbackFired.set(true), null);

        assertFalse("Callback should not fire before start", callbackFired.get());
        CompletableFuture<ReplayResult> future = engine.replayRange(10, 12);
        future.get(5, TimeUnit.SECONDS);
        assertTrue("Callback should fire on replayRange", callbackFired.get());
    }

    public void testStartCallbackFiresExactlyOnceOnReplayRange() throws Exception {
        IndexShard shard = mockShard();
        Client client = mockBulkClient();
        mockSnapshotWithOps(shard);

        AtomicInteger callCount = new AtomicInteger(0);

        TranslogReplayEngine engine = createEngine(client, shard, (from, to) -> callCount.incrementAndGet(), null);

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

        TranslogReplayEngine engine = createEngine(mock(Client.class), shard, (from, to) -> callbackFired.set(true), null);

        engine.cancel();
        assertFalse("startCallback should not fire when only cancel() is called", callbackFired.get());
    }

    // ---- replayRange: empty range ----

    public void testReplayRangeEmptyWhenFromExceedsTo() throws Exception {
        IndexShard shard = mockShard();
        TranslogReplayEngine engine = createEngine(mock(Client.class), shard);

        CompletableFuture<ReplayResult> future = engine.replayRange(100, 50);
        ReplayResult result = future.get(5, TimeUnit.SECONDS);

        assertEquals("Empty range should replay 0 ops", 0, result.operationsReplayed());
        assertEquals("Empty range should skip 0 ops", 0, result.operationsSkipped());
        assertEquals("targetSeqNo should be the requested end", 50, result.targetSeqNo());
    }

    public void testReplayRangeEmptyWhenBothNegative() throws Exception {
        IndexShard shard = mockShard();
        TranslogReplayEngine engine = createEngine(mock(Client.class), shard);

        CompletableFuture<ReplayResult> future = engine.replayRange(-1, -1);
        ReplayResult result = future.get(5, TimeUnit.SECONDS);

        assertEquals(0, result.operationsReplayed());
    }

    // ---- replayRange: basic INDEX ops ----

    public void testReplayRangeWithIndexOps() throws Exception {
        IndexShard shard = mockShard();
        Client client = mockBulkClient();
        mockSnapshotWithOps(shard); // 3 INDEX ops at seqNo 10,11,12

        TranslogReplayEngine engine = createEngine(client, shard);

        CompletableFuture<ReplayResult> future = engine.replayRange(10, 12);
        ReplayResult result = future.get(5, TimeUnit.SECONDS);

        assertEquals("Should replay 3 INDEX ops", 3, result.operationsReplayed());
        assertEquals("Should skip 0 ops", 0, result.operationsSkipped());
        assertEquals("targetSeqNo should be authoritative", 12, result.targetSeqNo());
    }

    // ---- replayRange: mixed ops (INDEX + DELETE + NO_OP) ----

    public void testReplayRangeWithMixedOps() throws Exception {
        IndexShard shard = mockShard();
        Client client = mockBulkClient();

        List<Translog.Operation> ops = new ArrayList<>();
        ops.add(makeIndexOp("doc1", 10, "{\"field\":\"value1\"}"));
        ops.add(new Translog.NoOp(11, 1, "gap-fill"));
        ops.add(new Translog.Delete("doc2", 12, 1));
        ops.add(makeIndexOp("doc3", 13, "{\"field\":\"value3\"}"));

        when(shard.newChangesSnapshot(any(), anyLong(), anyLong(), anyBoolean(), anyBoolean())).thenReturn(new ListSnapshot(ops));

        TranslogReplayEngine engine = createEngine(client, shard);

        CompletableFuture<ReplayResult> future = engine.replayRange(10, 13);
        ReplayResult result = future.get(5, TimeUnit.SECONDS);

        assertEquals("Should replay 3 ops (2 INDEX + 1 DELETE)", 3, result.operationsReplayed());
        assertEquals("Should skip 1 NO_OP", 1, result.operationsSkipped());
        assertEquals("targetSeqNo should be authoritative end", 13, result.targetSeqNo());
    }

    // ---- replayRange: NO_OP only (no bulk request) ----

    public void testReplayRangeWithOnlyNoOps() throws Exception {
        IndexShard shard = mockShard();
        // No bulk client mock needed — should not submit bulk for NO_OPs only
        Client client = mock(Client.class);

        List<Translog.Operation> ops = new ArrayList<>();
        ops.add(new Translog.NoOp(10, 1, "gap-fill-1"));
        ops.add(new Translog.NoOp(11, 1, "gap-fill-2"));

        when(shard.newChangesSnapshot(any(), anyLong(), anyLong(), anyBoolean(), anyBoolean())).thenReturn(new ListSnapshot(ops));

        TranslogReplayEngine engine = createEngine(client, shard);

        CompletableFuture<ReplayResult> future = engine.replayRange(10, 11);
        ReplayResult result = future.get(5, TimeUnit.SECONDS);

        assertEquals("Should replay 0 data ops", 0, result.operationsReplayed());
        assertEquals("Should skip 2 NO_OPs", 2, result.operationsSkipped());
    }

    // ---- progressCallback contract ----

    public void testProgressCallbackFiresWithCorrectValues() throws Exception {
        IndexShard shard = mockShard();
        Client client = mockBulkClient();
        mockSnapshotWithOps(shard); // 3 INDEX ops

        List<long[]> progressUpdates = Collections.synchronizedList(new ArrayList<>());
        TranslogReplayEngine.ProgressCallback progressCallback = (opsReplayed, opsSkipped, lastSeqNo, targetSeqNo, round) -> {
            progressUpdates.add(new long[] { opsReplayed, opsSkipped, lastSeqNo });
        };

        TranslogReplayEngine engine = createEngine(client, shard, null, progressCallback);

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
        Client client = mockBulkClient();

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
            createWriter(client, 2),
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

        // Progress fires on each batch completion + final completion callback (from finishedFuture.whenComplete)
        // 3 batches → 3 notifyProgress calls + 1 final from whenComplete = 4
        // But the exact number depends on empty-batch termination check.
        // At minimum, there should be more than 1 call (proving it fires per batch, not just at end)
        assertTrue(
            "Progress should fire multiple times for multi-batch replay, got " + progressCallCount.get(),
            progressCallCount.get() >= 3
        );
    }

    // ---- Cancel mid-replay ----

    public void testCancelBeforeStartThrowsOnReplayRange() {
        IndexShard shard = mockShard();
        TranslogReplayEngine engine = createEngine(mock(Client.class), shard);

        engine.cancel();

        // replayRange() after cancel throws — engine is cancelled
        expectThrows(IllegalStateException.class, () -> engine.replayRange(0, 10));
    }

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
        Client client = mock(Client.class);
        TranslogReplayEngine[] engineHolder = new TranslogReplayEngine[1];

        doAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            ActionListener<BulkResponse> listener = invocation.getArgument(1);
            // Cancel after first bulk completes
            if (engineHolder[0] != null) {
                engineHolder[0].cancel();
            }
            listener.onResponse(new BulkResponse(new BulkItemResponse[0], 1));
            return null;
        }).when(client).bulk(any(BulkRequest.class), any());

        TranslogReplayEngine engine = new TranslogReplayEngine(
            AoscLogger.create(TranslogReplayEngine.class),
            createWriter(client, 10),
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
        Client client = mockBulkClient();

        // Only 2 ops but toSeqNo is 100 — result.toSeqNo must be 100, not the last op's seqNo
        List<Translog.Operation> ops = new ArrayList<>();
        ops.add(makeIndexOp("doc1", 10, "{\"f\":\"v1\"}"));
        ops.add(makeIndexOp("doc2", 20, "{\"f\":\"v2\"}"));

        when(shard.newChangesSnapshot(any(), anyLong(), anyLong(), anyBoolean(), anyBoolean())).thenReturn(new ListSnapshot(ops));

        TranslogReplayEngine engine = createEngine(client, shard);
        ReplayResult result = engine.replayRange(10, 100).get(5, TimeUnit.SECONDS);

        assertEquals("targetSeqNo should be the requested range end, not the last processed op", 100, result.targetSeqNo());
    }

    // ---- Snapshot error handling ----

    public void testSnapshotOpenFailureCompletesExceptionally() throws Exception {
        IndexShard shard = mockShard();
        when(shard.newChangesSnapshot(any(), anyLong(), anyLong(), anyBoolean(), anyBoolean())).thenThrow(
            new IOException("snapshot failed")
        );

        TranslogReplayEngine engine = createEngine(mock(Client.class), shard);

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

    /**
     * Verifies cancel before start doesn't crash and returns a completed future.
     */
    public void testCancelBeforeStartReturnsCompletedFuture() {
        TranslogReplayEngine engine = createEngine(mock(Client.class), mockShard());
        CompletableFuture<ReplayResult> future = engine.cancel();
        assertTrue("Future should be done after cancel", future.isDone());
        assertTrue("Future should be completed exceptionally", future.isCompletedExceptionally());
    }

    /**
     * Verifies cancel is idempotent — returns the same future on multiple calls.
     */
    public void testCancelReturnsSameFuture() {
        TranslogReplayEngine engine = createEngine(mock(Client.class), mockShard());
        CompletableFuture<ReplayResult> f1 = engine.cancel();
        CompletableFuture<ReplayResult> f2 = engine.cancel();
        assertSame("cancel() should return the same future", f1, f2);
    }

    /**
     * Verifies progressCallback fires on cancel (terminal reporting).
     */
    public void testProgressCallbackFiresOnCancel() {
        AtomicBoolean progressCalled = new AtomicBoolean(false);
        TranslogReplayEngine engine = createEngine(
            mock(Client.class),
            new ShardHandle(AoscLogger.create(ShardHandle.class), mockShard(), threadPool),
            null,
            (replayed, skipped, lastSeqNo, targetSeqNo, round) -> progressCalled.set(true)
        );
        engine.cancel();
        assertTrue("Progress callback should fire on cancel (terminal)", progressCalled.get());
    }

    /**
     * Verifies progressCallback fires on start failure (terminal reporting).
     */
    public void testProgressCallbackFiresOnFailure() throws Exception {
        AtomicBoolean progressCalled = new AtomicBoolean(false);
        IndexShard shard = mockShard();
        when(shard.newChangesSnapshot(any(), anyLong(), anyLong(), anyBoolean(), anyBoolean())).thenThrow(
            new IOException("mock snapshot failure")
        );

        TranslogReplayEngine engine = createEngine(
            mock(Client.class),
            shard,
            null,
            (replayed, skipped, lastSeqNo, targetSeqNo, round) -> progressCalled.set(true)
        );
        CompletableFuture<ReplayResult> future = engine.replayRange(0, 10);
        assertTrue("Future should complete exceptionally", future.isCompletedExceptionally());
        assertTrue("Progress callback should fire on failure (terminal)", progressCalled.get());
    }

    /**
     * Verifies snapshot is closed on successful replay completion.
     */
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

        TranslogReplayEngine engine = createEngine(mockBulkClient(), shard);
        CompletableFuture<ReplayResult> future = engine.replayRange(10, 10);
        ReplayResult result = future.get(5, TimeUnit.SECONDS);
        assertNotNull(result);
        // Snapshot is closed via future.whenComplete() which may execute asynchronously
        // after future.complete() — allow a brief window for the callback to fire.
        assertBusy(() -> assertTrue("Snapshot should be closed after successful replay", snapshotClosed.get()));
    }

    /**
     * Verifies snapshot is closed when snapshot.next() throws IOException.
     */
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

        TranslogReplayEngine engine = createEngine(mockBulkClient(), shard);
        CompletableFuture<ReplayResult> future = engine.replayRange(10, 10);
        try {
            future.get(5, TimeUnit.SECONDS);
            fail("Expected exceptional completion");
        } catch (ExecutionException e) {
            // expected
        }
        assertTrue("Future should complete exceptionally", future.isCompletedExceptionally());
        // Snapshot is closed via future.whenComplete() which may execute asynchronously
        // after future.completeExceptionally() — allow a brief window for the callback to fire.
        assertBusy(() -> assertTrue("Snapshot should be closed on read failure", snapshotClosed.get()));
    }

    // ======== Helpers ========

    private static IndexShard mockShard() {
        IndexShard shard = mock(IndexShard.class);
        when(shard.shardId()).thenReturn(new ShardId(new Index("test-index", "uuid"), 0));
        return shard;
    }

    /** Creates a standard engine with no start/progress callbacks and default batchSize. */
    private TranslogReplayEngine createEngine(Client client, IndexShard shard) {
        return createEngine(client, new ShardHandle(AoscLogger.create(ShardHandle.class), shard, threadPool), null, null);
    }

    private TranslogReplayEngine createEngine(Client client, ShardHandle shardHandle) {
        return createEngine(client, shardHandle, null, null);
    }

    private TranslogReplayEngine createEngine(
        Client client,
        IndexShard shard,
        TranslogReplayEngine.StartCallback startCallback,
        TranslogReplayEngine.ProgressCallback progressCallback
    ) {
        return createEngine(
            client,
            new ShardHandle(AoscLogger.create(ShardHandle.class), shard, threadPool),
            startCallback,
            progressCallback
        );
    }

    private TranslogReplayEngine createEngine(
        Client client,
        ShardHandle shardHandle,
        TranslogReplayEngine.StartCallback startCallback,
        TranslogReplayEngine.ProgressCallback progressCallback
    ) {
        return new TranslogReplayEngine(
            AoscLogger.create(TranslogReplayEngine.class),
            createWriter(client),
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

    /** Creates a ConcurrentBulkWriter with W=1 and the given batch size. */
    private ConcurrentBulkWriter createWriter(Client client, int batchSize) {
        AoscLogger logger = AoscLogger.create(TranslogReplayEngineTests.class);
        SimpleWriteController controller = new SimpleWriteController(
            logger,
            new FixedBatchSizeController(() -> batchSize),
            () -> 1,
            () -> 100_000_000L,
            new OverloadBackoff(() -> 2_000L, () -> 120_000L, () -> 50)
        );
        return new ConcurrentBulkWriter(client, threadPool, controller, logger);
    }

    /** Creates a ConcurrentBulkWriter with W=1 and default batch size of 500. */
    private ConcurrentBulkWriter createWriter(Client client) {
        return createWriter(client, 500);
    }

    /** Creates a mock Client that immediately succeeds all bulk requests. */
    private Client mockBulkClient() {
        Client client = mock(Client.class);
        doAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            ActionListener<BulkResponse> listener = invocation.getArgument(1);
            listener.onResponse(new BulkResponse(new BulkItemResponse[0], 1));
            return null;
        }).when(client).bulk(any(BulkRequest.class), any());
        return client;
    }

    /** Sets up the shard mock to return a snapshot with 3 INDEX ops at seqNo 10, 11, 12. */
    private void mockSnapshotWithOps(IndexShard shard) throws IOException {
        List<Translog.Operation> ops = new ArrayList<>();
        ops.add(makeIndexOp("doc1", 10, "{\"field\":\"value1\"}"));
        ops.add(makeIndexOp("doc2", 11, "{\"field\":\"value2\"}"));
        ops.add(makeIndexOp("doc3", 12, "{\"field\":\"value3\"}"));
        when(shard.newChangesSnapshot(any(), anyLong(), anyLong(), anyBoolean(), anyBoolean())).thenReturn(new ListSnapshot(ops));
    }

    /** Helper to create a Translog.Index operation with JSON source. */
    private static Translog.Index makeIndexOp(String id, long seqNo, String jsonSource) {
        return new Translog.Index(id, seqNo, 1, jsonSource.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Simple Translog.Snapshot backed by a list — returns ops in order then null.
     * Allows testing replay logic without a real translog.
     */
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
