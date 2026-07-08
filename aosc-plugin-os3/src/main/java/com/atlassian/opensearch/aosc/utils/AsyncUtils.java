/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */
package com.atlassian.opensearch.aosc.utils;

import org.opensearch.common.unit.TimeValue;
import org.opensearch.core.action.ActionListener;
import org.opensearch.threadpool.ThreadPool;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * Utilities for async/SM threading patterns used across coordinator and worker.
 */
public final class AsyncUtils {

    private AsyncUtils() {}

    /**
     * Bridges a {@link CompletableFuture} to an {@link ActionListener}. When the future completes
     * normally, {@code listener.onResponse} is called; when it completes exceptionally,
     * {@code listener.onFailure} is called.
     *
     * <p>This allows service methods to return {@code CompletableFuture<T>} while transport actions
     * continue to use the OpenSearch {@code ActionListener<T>} callback pattern.
     */
    public static <T> void bridgeToListener(CompletableFuture<T> future, ActionListener<T> listener) {
        future.whenComplete((result, throwable) -> {
            if (throwable != null) {
                listener.onFailure(throwable instanceof Exception ? (Exception) throwable : new RuntimeException(throwable));
            } else {
                listener.onResponse(result);
            }
        });
    }

    /**
     * Schedules a task to run after a delay using OpenSearch's {@link ThreadPool#schedule} instead of
     * {@link CompletableFuture#delayedExecutor}. The latter uses {@code ForkJoinPool.commonPool()},
     * which is blocked by OpenSearch's test security manager.
     *
     * @param threadPool the thread pool to schedule on
     * @param delayMs    delay in milliseconds
     * @param task       the task to run
     */
    public static void scheduleDelayed(ThreadPool threadPool, long delayMs, Runnable task) {
        threadPool.schedule(task, TimeValue.timeValueMillis(delayMs), ThreadPool.Names.GENERIC);
    }

    /**
     * Maximum depth to traverse when searching exception cause chains.
     * Guards against circular cause chains or pathologically deep nesting.
     */
    private static final int MAX_CAUSE_DEPTH = 10;

    /**
     * Checks whether an exception's cause chain contains an instance of the given type.
     * Traverses at most {@value #MAX_CAUSE_DEPTH} levels to guard against circular or
     * excessively deep cause chains.
     *
     * @param throwable the exception to inspect (may be {@code null})
     * @param type      the exception type to search for
     * @return {@code true} if any exception in the cause chain (including {@code throwable}
     *         itself) is an instance of {@code type}
     */
    public static boolean hasCauseOfType(Throwable throwable, Class<? extends Throwable> type) {
        return unwrapIfCausedBy(throwable, type).isPresent();
    }

    /**
     * Unwraps a {@link RuntimeException} if its cause is an instance of {@code targetType}.
     * Returns the unwrapped cause if matched, otherwise returns the original exception unchanged.
     */
    public static <T extends Throwable> Optional<Throwable> unwrapIfCausedBy(Throwable throwable, Class<T> targetType) {
        Throwable current = throwable;
        for (int depth = 0; current != null && depth < MAX_CAUSE_DEPTH; depth++) {
            if (targetType.isInstance(current)) {
                return Optional.of(current);
            }
            current = current.getCause();
        }
        return Optional.empty();
    }
}
