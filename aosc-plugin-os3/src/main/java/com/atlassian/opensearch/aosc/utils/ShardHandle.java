/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */
package com.atlassian.opensearch.aosc.utils;

import org.apache.lucene.store.AlreadyClosedException;

import org.opensearch.action.admin.indices.flush.FlushRequest;
import org.opensearch.common.util.concurrent.ThreadContext;
import org.opensearch.core.index.shard.ShardId;
import org.opensearch.index.engine.Engine;
import org.opensearch.index.shard.IndexShard;
import org.opensearch.index.translog.Translog;
import org.opensearch.threadpool.ThreadPool;

import java.io.IOException;
import java.util.Objects;

/**
 * Safe wrapper over {@link IndexShard} that caches immutable identity and
 * provides guarded access to mutable shard state.
 *
 * <p>Immutable properties ({@link #shardId()}, {@link #indexName()}, {@link #shardNum()})
 * are cached at construction and remain available even after the shard closes.
 * Methods that access live shard state let {@link AlreadyClosedException} propagate
 * to the caller (and ultimately the state-machine failure handler), except for
 * best-effort helpers like {@link #flushBestEffort()} which swallow closure errors.</p>
 *
 * <p>Thread-safe: all methods may be called from any thread.</p>
 */
public class ShardHandle {

    private final AoscLogger logger;

    /**
     * Transient key the OpenSearch Security plugin uses to read the current action.
     * DlsFlsFilterLeafReader.applyDlsHere() calls action.startsWith(...) on this value;
     * when absent the call site NPEs. We inject a read action before acquiring a searcher
     * so that the security plugin's searcher wrapper does not throw.
     */
    static final String SECURITY_ACTION_KEY = "_opendistro_security_action_name";
    static final String INTERNAL_READ_ACTION = "indices:data/read/search";

    private final IndexShard indexShard;
    private final ThreadContext threadContext;
    private final ShardId shardId;
    private final String indexName;
    private final int shardNum;

    /** Last successfully observed global checkpoint, updated on every read. */
    private volatile long lastKnownGcp = -1;

    public ShardHandle(AoscLogger logger, IndexShard indexShard, ThreadPool threadPool) {
        this.logger = Objects.requireNonNull(logger, "logger").forClass(ShardHandle.class);
        Objects.requireNonNull(indexShard, "indexShard");
        Objects.requireNonNull(threadPool, "threadPool");
        this.indexShard = indexShard;
        this.threadContext = threadPool.getThreadContext();
        this.shardId = indexShard.shardId();
        this.indexName = shardId.getIndex().getName();
        this.shardNum = shardId.id();
    }

    // --- Immutable identity (always safe) ---

    public ShardId shardId() {
        return shardId;
    }

    public String indexName() {
        return indexName;
    }

    public int shardNum() {
        return shardNum;
    }

    // --- Live shard access: AlreadyClosedException propagates to caller ---

    /**
     * Returns the current global checkpoint. Caches the value for {@link #getGlobalCheckpointSafe()}.
     *
     * @throws AlreadyClosedException if the shard engine is closed
     */
    public long getGlobalCheckpoint() {
        long gcp = indexShard.seqNoStats().getGlobalCheckpoint();
        lastKnownGcp = gcp;
        return gcp;
    }

    /**
     * Opens a translog changes snapshot for the given sequence number range.
     * The caller is responsible for closing the returned snapshot.
     *
     * @throws AlreadyClosedException if the shard engine is closed
     */
    public Translog.Snapshot newChangesSnapshot(String source, long fromSeqNo, long toSeqNo) {
        try {
            return indexShard.newChangesSnapshot(source, fromSeqNo, toSeqNo, true, true);
        } catch (IOException e) {
            throw new RuntimeException("Failed to open translog snapshot on [" + shardId + "]", e);
        }
    }

    /**
     * Acquires a searcher for reading documents from the shard.
     * Stashes the thread context and injects a read action so that the
     * OpenSearch Security plugin's DLS/FLS searcher wrapper does not NPE
     * when called from a background thread with no transport action context.
     *
     * <p>The caller is responsible for closing the returned searcher.</p>
     *
     * @throws AlreadyClosedException if the shard engine is closed
     */
    public Engine.Searcher acquireSearcher(String source) {
        try (ThreadContext.StoredContext ignored = threadContext.stashContext()) {
            threadContext.putTransient(SECURITY_ACTION_KEY, INTERNAL_READ_ACTION);
            return indexShard.acquireSearcher(source);
        }
    }

    /**
     * Refreshes the shard. Rethrows all exceptions — callers that need
     * best-effort refresh should catch failures themselves.
     */
    public void refresh(String source) {
        indexShard.refresh(source);
    }

    // --- Safe methods: return cached/default value if shard is closed ---

    /**
     * Returns the current global checkpoint, or the last successfully observed
     * value if the shard is closed. Useful for non-critical metrics like gap
     * calculations in progress callbacks.
     */
    public long getGlobalCheckpointSafe() {
        try {
            return getGlobalCheckpoint();
        } catch (AlreadyClosedException e) {
            return lastKnownGcp;
        }
    }

    // --- Best-effort methods: swallow AlreadyClosedException ---

    /**
     * Flushes the shard. Silently swallows failures if the shard is closed.
     */
    public void flushBestEffort() {
        try {
            indexShard.flush(new FlushRequest().force(true));
        } catch (AlreadyClosedException e) {
            logger.debug("Flush skipped — shard [{}] already closed", shardId);
        } catch (Exception e) {
            logger.warn("Flush failed on shard [{}]", shardId, e);
        }
    }
}
