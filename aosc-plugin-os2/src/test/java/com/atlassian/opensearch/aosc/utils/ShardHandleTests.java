/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */
package com.atlassian.opensearch.aosc.utils;

import org.apache.lucene.store.AlreadyClosedException;

import org.opensearch.common.settings.Settings;
import org.opensearch.common.util.concurrent.ThreadContext;
import org.opensearch.core.index.Index;
import org.opensearch.core.index.shard.ShardId;
import org.opensearch.index.seqno.SeqNoStats;
import org.opensearch.index.shard.IndexShard;
import org.opensearch.test.OpenSearchTestCase;
import org.opensearch.threadpool.ThreadPool;

import org.mockito.ArgumentMatchers;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class ShardHandleTests extends OpenSearchTestCase {

    private final ThreadPool threadPool = mockThreadPool();

    private static ThreadPool mockThreadPool() {
        ThreadPool tp = mock(ThreadPool.class);
        when(tp.getThreadContext()).thenReturn(new ThreadContext(Settings.EMPTY));
        return tp;
    }

    private static IndexShard mockShard() {
        IndexShard shard = mock(IndexShard.class);
        when(shard.shardId()).thenReturn(new ShardId(new Index("test-index", "uuid"), 0));
        return shard;
    }

    public void testImmutableIdentity() {
        IndexShard shard = mockShard();
        ShardHandle shardHandle = new ShardHandle(AoscLogger.create(ShardHandle.class), shard, threadPool);

        assertEquals("test-index", shardHandle.indexName());
        assertEquals(0, shardHandle.shardNum());
        assertEquals(shard.shardId(), shardHandle.shardId());
    }

    public void testGetGlobalCheckpoint() {
        IndexShard shard = mockShard();
        when(shard.seqNoStats()).thenReturn(new SeqNoStats(100, 100, 100));
        ShardHandle shardHandle = new ShardHandle(AoscLogger.create(ShardHandle.class), shard, threadPool);

        assertEquals(100, shardHandle.getGlobalCheckpoint());
    }

    public void testGetGlobalCheckpointPropagatesAlreadyClosed() {
        IndexShard shard = mockShard();
        when(shard.seqNoStats()).thenThrow(new AlreadyClosedException("shard closed"));
        ShardHandle shardHandle = new ShardHandle(AoscLogger.create(ShardHandle.class), shard, threadPool);

        expectThrows(AlreadyClosedException.class, shardHandle::getGlobalCheckpoint);
    }

    public void testGetGlobalCheckpointSafeReturnsCachedOnClose() {
        IndexShard shard = mockShard();
        when(shard.seqNoStats()).thenReturn(new SeqNoStats(42, 42, 42)).thenThrow(new AlreadyClosedException("shard closed"));
        ShardHandle shardHandle = new ShardHandle(AoscLogger.create(ShardHandle.class), shard, threadPool);

        // First call succeeds and caches
        assertEquals(42, shardHandle.getGlobalCheckpoint());

        // Second call hits AlreadyClosedException, safe version returns cached
        assertEquals(42, shardHandle.getGlobalCheckpointSafe());
    }

    public void testGetGlobalCheckpointSafeReturnsMinusOneWhenNeverCached() {
        IndexShard shard = mockShard();
        when(shard.seqNoStats()).thenThrow(new AlreadyClosedException("shard closed"));
        ShardHandle shardHandle = new ShardHandle(AoscLogger.create(ShardHandle.class), shard, threadPool);

        assertEquals(-1, shardHandle.getGlobalCheckpointSafe());
    }

    public void testAcquireSearcherPropagatesAlreadyClosed() {
        IndexShard shard = mockShard();
        when(shard.acquireSearcher(anyString())).thenThrow(new AlreadyClosedException("engine closed"));
        ShardHandle shardHandle = new ShardHandle(AoscLogger.create(ShardHandle.class), shard, threadPool);

        expectThrows(AlreadyClosedException.class, () -> shardHandle.acquireSearcher("test"));
    }

    public void testNewChangesSnapshotPropagatesAlreadyClosed() {
        IndexShard shard = mockShard();
        when(shard.shardId()).thenReturn(new ShardId(new Index("test-index", "uuid"), 0));
        try {
            when(
                shard.newChangesSnapshot(
                    anyString(),
                    ArgumentMatchers.anyLong(),
                    ArgumentMatchers.anyLong(),
                    ArgumentMatchers.anyBoolean(),
                    ArgumentMatchers.anyBoolean()
                )
            ).thenThrow(new AlreadyClosedException("engine closed"));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        ShardHandle shardHandle = new ShardHandle(AoscLogger.create(ShardHandle.class), shard, threadPool);

        expectThrows(AlreadyClosedException.class, () -> shardHandle.newChangesSnapshot("test", 0, 10));
    }

    public void testFlushBestEffortSwallowsClosedException() {
        IndexShard shard = mockShard();
        doThrow(new AlreadyClosedException("shard closed")).when(shard).flush(ArgumentMatchers.any());
        ShardHandle shardHandle = new ShardHandle(AoscLogger.create(ShardHandle.class), shard, threadPool);

        // Should not throw
        shardHandle.flushBestEffort();
    }

    public void testRefreshPropagatesAlreadyClosed() {
        IndexShard shard = mockShard();
        doThrow(new AlreadyClosedException("shard closed")).when(shard).refresh(anyString());
        ShardHandle shardHandle = new ShardHandle(AoscLogger.create(ShardHandle.class), shard, threadPool);

        expectThrows(AlreadyClosedException.class, () -> shardHandle.refresh("test"));
    }

    public void testConstructorRejectsNull() {
        expectThrows(NullPointerException.class, () -> new ShardHandle(AoscLogger.create(ShardHandle.class), null, threadPool));
    }

    public void testAcquireSearcherInjectsSecurityAction() {
        IndexShard shard = mockShard();
        ThreadContext threadContext = threadPool.getThreadContext();
        // Verify no action set before
        assertNull(threadContext.getTransient(ShardHandle.SECURITY_ACTION_KEY));

        // Capture the transient value set during acquireSearcher
        final String[] capturedAction = new String[1];
        when(shard.acquireSearcher(anyString())).thenAnswer(inv -> {
            capturedAction[0] = threadContext.getTransient(ShardHandle.SECURITY_ACTION_KEY);
            return null;
        });

        ShardHandle handle = new ShardHandle(AoscLogger.create(ShardHandle.class), shard, threadPool);
        handle.acquireSearcher("test");

        // During the call, the security action should have been set
        assertEquals(ShardHandle.INTERNAL_READ_ACTION, capturedAction[0]);
        // After the call, context should be restored (stashed)
        assertNull(threadContext.getTransient(ShardHandle.SECURITY_ACTION_KEY));
    }
}
