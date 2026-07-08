/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */
package com.atlassian.opensearch.aosc.utils;

import com.carrotsearch.randomizedtesting.annotations.ThreadLeakScope;

import org.opensearch.common.unit.TimeValue;
import org.opensearch.test.OpenSearchTestCase;
import org.opensearch.threadpool.TestThreadPool;
import org.opensearch.threadpool.ThreadPool;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;
import java.util.function.Supplier;

/** Tests for {@link AsyncClientHelper#executeAsyncWithRetry}. */
@ThreadLeakScope(ThreadLeakScope.Scope.NONE)
public class AsyncClientHelperTests extends OpenSearchTestCase {

    private ThreadPool threadPool;

    @Override
    public void setUp() throws Exception {
        super.setUp();
        threadPool = new TestThreadPool("async-client-helper-test");
    }

    @Override
    public void tearDown() throws Exception {
        terminate(threadPool);
        super.tearDown();
    }

    private CompletableFuture<String> retry(Supplier<CompletableFuture<String>> attempt, Predicate<Exception> retryable) {
        return AsyncClientHelper.executeAsyncWithRetry(
            logger,
            threadPool,
            attempt,
            TimeValue.timeValueMillis(1),   // initial delay
            TimeValue.timeValueMillis(5),   // max delay (cap)
            TimeValue.timeValueSeconds(30), // give-up safety net
            ThreadPool.Names.GENERIC,
            retryable
        );
    }

    public void testCompletesOnFirstSuccess() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        CompletableFuture<String> result = retry(() -> {
            calls.incrementAndGet();
            return CompletableFuture.completedFuture("ok");
        }, e -> true);
        assertEquals("ok", result.get(5, TimeUnit.SECONDS));
        assertEquals("no retry needed", 1, calls.get());
    }

    public void testRetriesThenCompletes() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        CompletableFuture<String> result = retry(() -> {
            int n = calls.incrementAndGet();
            CompletableFuture<String> f = new CompletableFuture<>();
            if (n < 3) {
                f.completeExceptionally(new RuntimeException("transient"));
            } else {
                f.complete("ok");
            }
            return f;
        }, e -> true);
        assertEquals("ok", result.get(5, TimeUnit.SECONDS));
        // The attempt thunk is re-invoked on every retry — this is what gives callers "built-at-send" freshness.
        assertEquals("attempt re-invoked per try", 3, calls.get());
    }

    public void testFailsWhenPredicateStopsRetrying() {
        AtomicInteger calls = new AtomicInteger();
        CompletableFuture<String> result = retry(() -> {
            calls.incrementAndGet();
            CompletableFuture<String> f = new CompletableFuture<>();
            f.completeExceptionally(new RuntimeException("boom"));
            return f;
        }, e -> calls.get() < 3); // stop retrying after the 3rd attempt
        ExecutionException ee = expectThrows(ExecutionException.class, () -> result.get(5, TimeUnit.SECONDS));
        assertEquals("boom", ee.getCause().getMessage());
        assertEquals("gave up after the predicate returned false", 3, calls.get());
    }
}
