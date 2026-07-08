/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */
package com.atlassian.opensearch.aosc.service.bulk;

import com.atlassian.opensearch.aosc.service.adaptive.BulkOutcome;
import com.atlassian.opensearch.aosc.service.adaptive.RejectionClassifier;
import com.atlassian.opensearch.aosc.service.adaptive.RejectionKind;
import com.atlassian.opensearch.aosc.utils.AoscLogger;
import com.atlassian.opensearch.aosc.utils.AsyncClientHelper;
import com.atlassian.opensearch.aosc.utils.AsyncUtils;
import com.atlassian.opensearch.aosc.utils.LC;

import org.opensearch.action.bulk.BulkRequest;
import org.opensearch.action.bulk.BulkResponse;
import org.opensearch.common.unit.TimeValue;
import org.opensearch.threadpool.ThreadPool;
import org.opensearch.transport.client.Client;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;

import static com.atlassian.opensearch.aosc.utils.AoscLogger.kv;

/**
 * Submits bulk requests with bounded retry and exponential backoff.
 *
 * <p>Retry policy:
 * <ul>
 *   <li><b>NONE</b> (success) — return immediately</li>
 *   <li><b>FATAL</b> — return immediately (unrecoverable)</li>
 *   <li><b>OVERLOAD / TRANSIENT</b> — retry up to {@value #MAX_BULK_RETRIES} times,
 *       then return outcome for consumer to handle (e.g. split on OVERLOAD)</li>
 * </ul>
 */
public final class BulkWriteHelper {

    public static final TimeValue BULK_TIMEOUT = TimeValue.timeValueMinutes(1);
    public static final int MAX_BULK_RETRIES = 2;

    /** Base delay for exponential backoff. Package-private for tests. */
    // Visible for testing — allows IT tests to speed up retries.
    public static long baseRetryDelayMs = 10_000L;

    private BulkWriteHelper() {}

    public static CompletableFuture<BulkOutcome> submitBulkAsyncWithOutcome(
        Client client,
        BulkRequest request,
        AoscLogger logger,
        ThreadPool threadPool
    ) {
        request.timeout(BULK_TIMEOUT);
        return attempt(client, request, logger, 0, threadPool);
    }

    private static CompletableFuture<BulkOutcome> attempt(
        Client client,
        BulkRequest request,
        AoscLogger logger,
        int attemptNum,
        ThreadPool threadPool
    ) {
        long startNanos = System.nanoTime();
        int attemptCount = attemptNum + 1;

        return AsyncClientHelper.executeBulkAsync(client, request).<CompletableFuture<BulkOutcome>>handle((response, ex) -> {
            long tookMillis = (System.nanoTime() - startNanos) / 1_000_000;
            RejectionKind kind = RejectionClassifier.classify(response, ex);
            BulkOutcome outcome = toOutcome(request, tookMillis, attemptCount, kind, response, ex);

            if (outcome.isSuccess() || kind != RejectionKind.TRANSIENT || attemptNum >= MAX_BULK_RETRIES) {
                if (!outcome.isSuccess()) {
                    logger.warn(
                        "Bulk request failed: {}",
                        Objects.toString(outcome.getFailureMessage(), "unknown"),
                        kv(LC.EVENT, "bulk_failure"),
                        kv(LC.REJECTION_KIND, kind),
                        kv(LC.ATTEMPT_COUNT, attemptCount)
                    );
                }
                return CompletableFuture.completedFuture(outcome);
            }

            // Allow retry transient errors
            long delayMs = (long) Math.pow(2, attemptNum) * baseRetryDelayMs;
            logger.warn(
                "Bulk request retrying",
                kv(LC.EVENT, "bulk_retry"),
                kv(LC.REJECTION_KIND, kind),
                kv(LC.ATTEMPT_COUNT, attemptCount),
                kv(LC.PAUSE_MS, delayMs)
            );

            return scheduleRetry(client, request, logger, attemptCount, threadPool, delayMs);
        }).thenCompose(f -> f);
    }

    private static CompletableFuture<BulkOutcome> scheduleRetry(
        Client client,
        BulkRequest request,
        AoscLogger logger,
        int nextAttemptNum,
        ThreadPool threadPool,
        long delayMs
    ) {
        CompletableFuture<BulkOutcome> future = new CompletableFuture<>();
        AsyncUtils.scheduleDelayed(
            threadPool,
            delayMs,
            () -> attempt(client, request, logger, nextAttemptNum, threadPool).whenComplete((o, e) -> {
                if (e != null) future.completeExceptionally(e);
                else future.complete(o);
            })
        );
        return future;
    }

    private static BulkOutcome toOutcome(
        BulkRequest request,
        long tookMillis,
        int attemptCount,
        RejectionKind kind,
        BulkResponse response,
        Throwable ex
    ) {
        long serverTookMillis = (response != null && response.getTook() != null) ? response.getTook().millis() : -1;
        if (kind == RejectionKind.NONE) {
            return BulkOutcome.success(
                tookMillis,
                serverTookMillis,
                request.numberOfActions(),
                request.estimatedSizeInBytes(),
                attemptCount
            );
        }
        String errorMsg = (ex != null) ? ex.getMessage()
            : (response != null && response.hasFailures()) ? response.buildFailureMessage()
            : "Unknown error";
        return BulkOutcome.failure(
            tookMillis,
            serverTookMillis,
            request.numberOfActions(),
            request.estimatedSizeInBytes(),
            attemptCount,
            kind,
            errorMsg,
            ex
        );
    }
}
