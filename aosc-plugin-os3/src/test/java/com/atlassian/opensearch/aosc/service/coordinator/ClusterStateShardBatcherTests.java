/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */
package com.atlassian.opensearch.aosc.service.coordinator;

import com.atlassian.opensearch.aosc.model.AoscMigrationsClusterState;
import com.atlassian.opensearch.aosc.model.ShardProgressDocument;
import com.atlassian.opensearch.aosc.model.phase.ShardPhase;
import com.atlassian.opensearch.aosc.utils.AoscLogger;

import org.opensearch.test.OpenSearchTestCase;
import org.opensearch.threadpool.TestThreadPool;
import org.opensearch.threadpool.ThreadPool;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class ClusterStateShardBatcherTests extends OpenSearchTestCase {

    private ThreadPool threadPool;
    private ClusterStateUpdateHelper mockHelper;

    @Override
    public void setUp() throws Exception {
        super.setUp();
        threadPool = new TestThreadPool("batcher-test");
        mockHelper = mock(ClusterStateUpdateHelper.class);
    }

    @Override
    public void tearDown() throws Exception {
        ThreadPool.terminate(threadPool, 10, TimeUnit.SECONDS);
        super.tearDown();
    }

    // 1. Single update flushes successfully
    @SuppressWarnings("unchecked")
    public void testSingleUpdateFlushes() throws Exception {
        CompletableFuture<AoscMigrationsClusterState.Entry> csResult = new CompletableFuture<>();
        when(mockHelper.flushPendingUpdates(anyString(), anyMap())).thenReturn(csResult);

        ClusterStateShardBatcher batcher = new ClusterStateShardBatcher(
            AoscLogger.create(ClusterStateShardBatcher.class),
            "mig-1",
            mockHelper,
            threadPool
        );
        CompletableFuture<Void> result = batcher.queueUpdate(0, progressWith(ShardPhase.CONVERGED));

        assertFalse(result.isDone());
        csResult.complete(null);
        result.get(5, TimeUnit.SECONDS);
        assertTrue(result.isDone());
        assertFalse(result.isCompletedExceptionally());
        batcher.close();
    }

    // 2. Multiple updates batched into one CS write
    @SuppressWarnings("unchecked")
    public void testMultipleUpdatesBatched() throws Exception {
        CompletableFuture<AoscMigrationsClusterState.Entry> csResult = new CompletableFuture<>();
        when(mockHelper.flushPendingUpdates(anyString(), anyMap())).thenReturn(csResult);

        ClusterStateShardBatcher batcher = new ClusterStateShardBatcher(
            AoscLogger.create(ClusterStateShardBatcher.class),
            "mig-1",
            mockHelper,
            threadPool,
            5000
        );
        CompletableFuture<Void> r1 = batcher.queueUpdate(0, progressWith(ShardPhase.CONVERGED));
        CompletableFuture<Void> r2 = batcher.queueUpdate(1, progressWith(ShardPhase.CONVERGED));
        CompletableFuture<Void> r3 = batcher.queueUpdate(2, progressWith(ShardPhase.BACKFILLING));

        // Manually flush (batch window is large, so auto-flush won't fire)
        batcher.flush();

        assertEquals(0, batcher.pendingCount());
        csResult.complete(null);
        r1.get(5, TimeUnit.SECONDS);
        r2.get(5, TimeUnit.SECONDS);
        r3.get(5, TimeUnit.SECONDS);
        batcher.close();
    }

    // 3. Same shard updated twice — latest wins
    @SuppressWarnings("unchecked")
    public void testSameShardLatestWins() throws Exception {
        CompletableFuture<AoscMigrationsClusterState.Entry> csResult = new CompletableFuture<>();
        when(mockHelper.flushPendingUpdates(anyString(), anyMap())).thenAnswer(invocation -> {
            Map<Integer, AoscMigrationsClusterState.ShardMigrationClusterState> updates = invocation.getArgument(1);
            // Only 1 entry for shard 0 — latest phase wins
            assertEquals(1, updates.size());
            assertEquals(ShardPhase.CONVERGED, updates.get(0).phase());
            return csResult;
        });

        ClusterStateShardBatcher batcher = new ClusterStateShardBatcher(
            AoscLogger.create(ClusterStateShardBatcher.class),
            "mig-1",
            mockHelper,
            threadPool,
            5000
        );
        batcher.queueUpdate(0, progressWith(ShardPhase.BACKFILLING));
        CompletableFuture<Void> r2 = batcher.queueUpdate(0, progressWith(ShardPhase.CONVERGED));

        batcher.flush();
        csResult.complete(null);
        r2.get(5, TimeUnit.SECONDS);
        batcher.close();
    }

    // 4. CS write failure propagates to all queued futures
    @SuppressWarnings("unchecked")
    public void testFlushFailurePropagates() throws Exception {
        CompletableFuture<AoscMigrationsClusterState.Entry> csResult = new CompletableFuture<>();
        when(mockHelper.flushPendingUpdates(anyString(), anyMap())).thenReturn(csResult);

        ClusterStateShardBatcher batcher = new ClusterStateShardBatcher(
            AoscLogger.create(ClusterStateShardBatcher.class),
            "mig-1",
            mockHelper,
            threadPool,
            5000
        );
        CompletableFuture<Void> r1 = batcher.queueUpdate(0, progressWith(ShardPhase.CONVERGED));
        CompletableFuture<Void> r2 = batcher.queueUpdate(1, progressWith(ShardPhase.CONVERGED));

        batcher.flush();
        csResult.completeExceptionally(new RuntimeException("CS write failed"));

        assertTrue(expectThrows(Exception.class, () -> r1.get(5, TimeUnit.SECONDS)).getMessage().contains("CS write failed"));
        assertTrue(expectThrows(Exception.class, () -> r2.get(5, TimeUnit.SECONDS)).getMessage().contains("CS write failed"));
        batcher.close();
    }

    // 5. Close fails pending futures
    @SuppressWarnings("unchecked")
    public void testCloseFlushesPending() throws Exception {
        CompletableFuture<Void> flushResult = new CompletableFuture<>();
        when(mockHelper.flushPendingUpdates(anyString(), anyMap())).thenAnswer(inv -> flushResult);

        ClusterStateShardBatcher batcher = new ClusterStateShardBatcher(
            AoscLogger.create(ClusterStateShardBatcher.class),
            "mig-1",
            mockHelper,
            threadPool,
            5000
        );
        CompletableFuture<Void> r1 = batcher.queueUpdate(0, progressWith(ShardPhase.CONVERGED));

        batcher.close();
        // close() triggers flush — verify the helper was called
        verify(mockHelper).flushPendingUpdates(eq("mig-1"), anyMap());
        // The queued future settles when the flush completes
        assertFalse(r1.isDone());
        flushResult.complete(null);
        assertTrue(r1.isDone());
        assertFalse(r1.isCompletedExceptionally());
    }

    // 6. Queue after close returns failed future
    public void testQueueAfterClose() {
        ClusterStateShardBatcher batcher = new ClusterStateShardBatcher(
            AoscLogger.create(ClusterStateShardBatcher.class),
            "mig-1",
            mockHelper,
            threadPool
        );
        batcher.close();

        CompletableFuture<Void> result = batcher.queueUpdate(0, progressWith(ShardPhase.CONVERGED));
        assertTrue(result.isCompletedExceptionally());
    }

    // 7. Empty flush is no-op
    public void testEmptyFlushIsNoOp() {
        ClusterStateShardBatcher batcher = new ClusterStateShardBatcher(
            AoscLogger.create(ClusterStateShardBatcher.class),
            "mig-1",
            mockHelper,
            threadPool,
            5000
        );
        batcher.flush(); // should not throw or call helper
        batcher.close();
    }

    private static ShardProgressDocument progressWith(ShardPhase phase) {
        return ShardProgressDocument.builder().phase(phase).build();
    }
}
