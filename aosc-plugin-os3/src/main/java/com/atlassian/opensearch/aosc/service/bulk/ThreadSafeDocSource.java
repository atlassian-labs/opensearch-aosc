/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */
package com.atlassian.opensearch.aosc.service.bulk;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;

/**
 * Thread-safe {@link DocSource} backed by an iterator and a retry deque.
 *
 * <p>All public methods are {@code synchronized} so a lock-free deque is unnecessary;
 * {@link ArrayDeque} avoids per-node allocation and CAS overhead.</p>
 *
 * @param <M> engine-specific metrics type
 */
public class ThreadSafeDocSource<M> implements DocSource<M> {

    private final Iterator<WriteOp<M>> source;
    private final Deque<WriteOp<M>> retryDeque = new ArrayDeque<>();

    public ThreadSafeDocSource(Iterator<WriteOp<M>> source) {
        this.source = Objects.requireNonNull(source, "source");
    }

    @Override
    public synchronized WriteOp<M> poll() {
        WriteOp<M> retry = retryDeque.pollFirst();
        if (retry != null) {
            return retry;
        }
        if (source.hasNext()) {
            return source.next();
        }
        return null;
    }

    @Override
    public synchronized void returnForRetry(List<WriteOp<M>> ops) {
        for (int i = ops.size() - 1; i >= 0; i--) {
            retryDeque.addFirst(ops.get(i));
        }
    }

    @Override
    public synchronized boolean isExhausted() {
        return retryDeque.isEmpty() && !source.hasNext();
    }
}
