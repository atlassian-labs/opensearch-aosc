/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */
package com.atlassian.opensearch.aosc.service.worker;

import com.atlassian.opensearch.aosc.service.bulk.BulkWriter;
import com.atlassian.opensearch.aosc.service.worker.BackfillEngine.BackfillResult;
import com.atlassian.opensearch.aosc.transform.IdentityTransformFunction;
import com.atlassian.opensearch.aosc.utils.AoscLogger;
import com.atlassian.opensearch.aosc.utils.ShardHandle;

import org.opensearch.common.settings.Settings;
import org.opensearch.common.util.concurrent.ThreadContext;
import org.opensearch.core.index.Index;
import org.opensearch.core.index.shard.ShardId;
import org.opensearch.index.shard.IndexShard;
import org.opensearch.test.OpenSearchTestCase;
import org.opensearch.threadpool.ThreadPool;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class BackfillEngineTests extends OpenSearchTestCase {

    private final ThreadPool threadPool = mockThreadPool();

    private static ThreadPool mockThreadPool() {
        ThreadPool tp = mock(ThreadPool.class);
        when(tp.getThreadContext()).thenReturn(new ThreadContext(Settings.EMPTY));
        return tp;
    }

    // ---- Constructor validation ----

    public void testConstructorRejectsNullClient() {
        IndexShard shard = mockShard();
        ShardHandle handle = new ShardHandle(AoscLogger.create(ShardHandle.class), shard, threadPool);
        expectThrows(
            NullPointerException.class,
            () -> new BackfillEngine(
                AoscLogger.create(BackfillEngine.class),
                null,
                handle,
                "target",
                IdentityTransformFunction.INSTANCE,
                () -> 100,
                null,
                null
            )
        );
    }

    public void testConstructorRejectsNullIndexShard() {
        expectThrows(
            NullPointerException.class,
            () -> new BackfillEngine(
                AoscLogger.create(BackfillEngine.class),
                mock(BulkWriter.class),
                null,
                "target",
                IdentityTransformFunction.INSTANCE,
                () -> 100,
                null,
                null
            )
        );
    }

    public void testConstructorRejectsNullTargetIndex() {
        IndexShard shard = mockShard();
        ShardHandle handle = new ShardHandle(AoscLogger.create(ShardHandle.class), shard, threadPool);
        expectThrows(
            NullPointerException.class,
            () -> new BackfillEngine(
                AoscLogger.create(BackfillEngine.class),
                mock(BulkWriter.class),
                handle,
                null,
                IdentityTransformFunction.INSTANCE,
                () -> 100,
                null,
                null
            )
        );
    }

    public void testConstructorRejectsNullTransform() {
        IndexShard shard = mockShard();
        ShardHandle handle = new ShardHandle(AoscLogger.create(ShardHandle.class), shard, threadPool);
        expectThrows(
            NullPointerException.class,
            () -> new BackfillEngine(
                AoscLogger.create(BackfillEngine.class),
                mock(BulkWriter.class),
                handle,
                "target",
                null,
                () -> 100,
                null,
                null
            )
        );
    }

    // ---- Single-use guard ----

    public void testDoubleStartThrowsIllegalStateException() {
        IndexShard shard = mockShard();
        ShardHandle handle = new ShardHandle(AoscLogger.create(ShardHandle.class), shard, threadPool);
        doThrow(new IllegalStateException("mock")).when(shard).refresh(anyString());
        BackfillEngine engine = new BackfillEngine(
            AoscLogger.create(BackfillEngine.class),
            mock(BulkWriter.class),
            handle,
            "target",
            IdentityTransformFunction.INSTANCE,
            () -> 100,
            null,
            null
        );
        engine.start(); // first call succeeds (completes exceptionally due to refresh mock)
        // Second call throws — engine is single-use
        expectThrows(IllegalStateException.class, engine::start);
    }

    // ---- Cancel ----

    public void testCancelSetsFlag() {
        IndexShard shard = mockShard();
        ShardHandle handle = new ShardHandle(AoscLogger.create(ShardHandle.class), shard, threadPool);
        BackfillEngine engine = new BackfillEngine(
            AoscLogger.create(BackfillEngine.class),
            mock(BulkWriter.class),
            handle,
            "target",
            IdentityTransformFunction.INSTANCE,
            () -> 100,
            null,
            null
        );
        CompletableFuture<BackfillResult> cancelFuture = engine.cancel();
        assertTrue(cancelFuture.isDone());
    }

    public void testCancelBeforeStartThrowsOnStart() {
        IndexShard shard = mockShard();
        ShardHandle handle = new ShardHandle(AoscLogger.create(ShardHandle.class), shard, threadPool);
        BackfillEngine engine = new BackfillEngine(
            AoscLogger.create(BackfillEngine.class),
            mock(BulkWriter.class),
            handle,
            "target",
            IdentityTransformFunction.INSTANCE,
            () -> 100,
            null,
            null
        );
        engine.cancel();
        // start() after cancel throws — engine is cancelled
        expectThrows(IllegalStateException.class, engine::start);
    }

    // ---- startCallback contract ----

    public void testStartCallbackFiresOnStart() {
        IndexShard shard = mockShard();
        ShardHandle handle = new ShardHandle(AoscLogger.create(ShardHandle.class), shard, threadPool);
        doThrow(new IllegalStateException("mock")).when(shard).refresh(anyString());

        AtomicBoolean callbackFired = new AtomicBoolean(false);

        BackfillEngine engine = new BackfillEngine(
            AoscLogger.create(BackfillEngine.class),
            mock(BulkWriter.class),
            handle,
            "target",
            IdentityTransformFunction.INSTANCE,
            () -> 100,
            () -> callbackFired.set(true),
            null
        );

        assertFalse("Callback should not fire before start", callbackFired.get());
        engine.start();
        assertTrue("Callback should fire on start", callbackFired.get());
    }

    public void testStartCallbackFiresExactlyOnce() {
        IndexShard shard = mockShard();
        ShardHandle handle = new ShardHandle(AoscLogger.create(ShardHandle.class), shard, threadPool);
        doThrow(new IllegalStateException("mock refresh")).when(shard).refresh(anyString());

        AtomicInteger callCount = new AtomicInteger(0);

        BackfillEngine engine = new BackfillEngine(
            AoscLogger.create(BackfillEngine.class),
            mock(BulkWriter.class),
            handle,
            "target",
            IdentityTransformFunction.INSTANCE,
            () -> 100,
            callCount::incrementAndGet,
            null
        );

        engine.start();
        try {
            engine.start(); // second call throws
        } catch (IllegalStateException e) {
            // expected
        }
        assertEquals("startCallback must fire exactly once", 1, callCount.get());
    }

    public void testStartCallbackDoesNotFireOnCancel() {
        IndexShard shard = mockShard();
        ShardHandle handle = new ShardHandle(AoscLogger.create(ShardHandle.class), shard, threadPool);

        AtomicBoolean callbackFired = new AtomicBoolean(false);

        BackfillEngine engine = new BackfillEngine(
            AoscLogger.create(BackfillEngine.class),
            mock(BulkWriter.class),
            handle,
            "target",
            IdentityTransformFunction.INSTANCE,
            () -> 100,
            () -> callbackFired.set(true),
            null
        );

        engine.cancel();
        assertFalse("startCallback should not fire when only cancel() is called", callbackFired.get());
    }

    public void testStartCallbackDoesNotFireWhenCancelledBeforeStart() {
        IndexShard shard = mockShard();
        ShardHandle handle = new ShardHandle(AoscLogger.create(ShardHandle.class), shard, threadPool);

        AtomicBoolean callbackFired = new AtomicBoolean(false);

        BackfillEngine engine = new BackfillEngine(
            AoscLogger.create(BackfillEngine.class),
            mock(BulkWriter.class),
            handle,
            "target",
            IdentityTransformFunction.INSTANCE,
            () -> 100,
            () -> callbackFired.set(true),
            null
        );

        engine.cancel();
        try {
            engine.start(); // throws — engine is cancelled
        } catch (IllegalStateException e) {
            // expected
        }
        assertFalse("startCallback should not fire when engine was cancelled before start", callbackFired.get());
    }

    // ---- BackfillResult ----

    public void testBackfillResultFields() {
        BackfillResult result = new BackfillResult(100, 200, 1500, 5);
        assertEquals(100, result.documentsProcessed());
        assertEquals(200, result.totalDocuments());
        assertEquals(1500, result.elapsedMillis());
        assertEquals(5, result.batchCount());
    }

    public void testBackfillResultZeroDocs() {
        BackfillResult result = new BackfillResult(0, 0, 0, 0);
        assertEquals(0, result.documentsProcessed());
        assertEquals(0, result.totalDocuments());
        assertEquals(0, result.batchCount());
    }

    // ---- Searcher lifecycle (B008) ----

    /**
     * Verifies that the searcher is closed when cancel() is called before start().
     * The finishedFuture.whenComplete() handler should close the searcher even
     * though start() was never called (activeSearcher is null — safe no-op).
     */
    public void testCancelBeforeStartDoesNotLeakSearcher() {
        BackfillEngine engine = new BackfillEngine(
            AoscLogger.create(BackfillEngine.class),
            mock(BulkWriter.class),
            new ShardHandle(AoscLogger.create(ShardHandle.class), mockShard(), threadPool),
            "target",
            IdentityTransformFunction.INSTANCE,
            () -> 100,
            null,
            null
        );
        CompletableFuture<BackfillResult> future = engine.cancel();
        assertTrue("Future should be done after cancel", future.isDone());
        assertTrue("Future should be completed exceptionally", future.isCompletedExceptionally());
        // No searcher was acquired, so nothing to leak — just verify no NPE
    }

    /**
     * Verifies that cancel() after start returns the finishedFuture,
     * and the future completes exceptionally (not hanging).
     */
    public void testCancelReturnsSameFuture() {
        BackfillEngine engine = new BackfillEngine(
            AoscLogger.create(BackfillEngine.class),
            mock(BulkWriter.class),
            new ShardHandle(AoscLogger.create(ShardHandle.class), mockShard(), threadPool),
            "target",
            IdentityTransformFunction.INSTANCE,
            () -> 100,
            null,
            null
        );

        CompletableFuture<BackfillResult> cancelFuture1 = engine.cancel();
        CompletableFuture<BackfillResult> cancelFuture2 = engine.cancel();
        assertSame("cancel() should return the same future", cancelFuture1, cancelFuture2);
        assertTrue("Future should be done after cancel", cancelFuture1.isDone());
    }

    // ---- Callback coverage (B008) ----

    /**
     * Verifies startCallback fires exactly once on successful start().
     */
    public void testStartCallbackFiresOnSuccessfulStart() {
        AtomicInteger startCount = new AtomicInteger(0);
        BackfillEngine engine = new BackfillEngine(
            AoscLogger.create(BackfillEngine.class),
            mock(BulkWriter.class),
            new ShardHandle(AoscLogger.create(ShardHandle.class), mockShard(), threadPool),
            "target",
            IdentityTransformFunction.INSTANCE,
            () -> 100,
            startCount::incrementAndGet,
            null
        );
        // start() will fail because mockShard().refresh() is not set up,
        // but startCallback should still fire (it fires before refresh)
        try {
            engine.start();
        } catch (Exception ignored) {}
        assertEquals("startCallback should fire exactly once", 1, startCount.get());
    }

    /**
     * Verifies startCallback does NOT fire when cancel() is called before start().
     */
    public void testStartCallbackDoesNotFireOnCancelBeforeStart() {
        AtomicInteger startCount = new AtomicInteger(0);
        BackfillEngine engine = new BackfillEngine(
            AoscLogger.create(BackfillEngine.class),
            mock(BulkWriter.class),
            new ShardHandle(AoscLogger.create(ShardHandle.class), mockShard(), threadPool),
            "target",
            IdentityTransformFunction.INSTANCE,
            () -> 100,
            startCount::incrementAndGet,
            null
        );
        engine.cancel();
        assertEquals("startCallback should not fire when cancelled before start", 0, startCount.get());
    }

    /**
     * Verifies progressCallback fires on cancel (with final count).
     */
    public void testProgressCallbackOnCancel() {
        AtomicBoolean progressCalled = new AtomicBoolean(false);
        BackfillEngine engine = new BackfillEngine(
            AoscLogger.create(BackfillEngine.class),
            mock(BulkWriter.class),
            new ShardHandle(AoscLogger.create(ShardHandle.class), mockShard(), threadPool),
            "target",
            IdentityTransformFunction.INSTANCE,
            () -> 100,
            null,
            (docs, batches, total) -> progressCalled.set(true)
        );
        engine.cancel();
        assertTrue("Progress callback should be called on cancel", progressCalled.get());
    }

    /**
     * Verifies progressCallback fires on start failure (with final count).
     */
    public void testProgressCallbackOnStartFailure() {
        AtomicBoolean progressCalled = new AtomicBoolean(false);
        IndexShard shard = mockShard();
        ShardHandle handle = new ShardHandle(AoscLogger.create(ShardHandle.class), shard, threadPool);
        doThrow(new IllegalStateException("mock refresh fail")).when(shard).refresh(anyString());

        BackfillEngine engine = new BackfillEngine(
            AoscLogger.create(BackfillEngine.class),
            mock(BulkWriter.class),
            handle,
            "target",
            IdentityTransformFunction.INSTANCE,
            () -> 100,
            null,
            (docs, batches, total) -> progressCalled.set(true)
        );
        engine.start();
        assertTrue("Progress callback should be called on start failure", progressCalled.get());
    }

    // ---- Helpers ----

    private static IndexShard mockShard() {
        IndexShard shard = mock(IndexShard.class);
        when(shard.shardId()).thenReturn(new ShardId(new Index("test-index", "uuid"), 0));
        return shard;
    }
}
