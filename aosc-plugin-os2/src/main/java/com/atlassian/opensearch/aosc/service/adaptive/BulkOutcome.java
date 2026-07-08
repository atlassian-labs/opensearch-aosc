/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */
package com.atlassian.opensearch.aosc.service.adaptive;

import lombok.Builder;
import lombok.Value;

/**
 * Immutable result of a single bulk indexing attempt, capturing timing,
 * size, retry count, and rejection classification.
 *
 * <p>Used by {@link BatchSizeController#observe(BulkOutcome)} to feed
 * write outcomes back into the adaptive batch sizing algorithm.</p>
 */
@Value
@Builder
public class BulkOutcome {
    /**
     * Wall-clock duration in milliseconds for this attempt (client-measured).
     */
    long tookMillis;

    /**
     * Server-side processing time in milliseconds from {@code BulkResponse.getTookInMillis()}.
     * Excludes transport/serialization overhead. -1 if unavailable (e.g. transport exception).
     */
    long serverTookMillis;

    /**
     * Number of bulk items submitted in this request.
     */
    int itemCount;

    /**
     * Size in bytes of the BulkRequest at submission time.
     */
    long batchBytes;

    /**
     * Attempt count: 1 = no retries, >1 = retried.
     */
    int attemptCount;

    /**
     * Classification of rejection, if any.
     */
    RejectionKind rejectionKind;

    /**
     * True if the request succeeded (all items indexed without error).
     */
    boolean success;

    /**
     * Populated when success=false; null or empty when success=true.
     */
    String failureMessage;

    /**
     * Underlying throwable when the failure originated from a transport-level exception
     * (vs. an item-level failure embedded in {@code BulkResponse}). May be null.
     * Preserved so callers can chain it as the cause of any wrapper exception they
     * throw, keeping the original stack trace intact in operator logs.
     */
    Throwable cause;

    /**
     * Per-document server-side processing time in milliseconds.
     * Returns -1 if server time is unavailable or itemCount is 0.
     */
    public double perDocServerMillis() {
        if (serverTookMillis < 0 || itemCount == 0) return -1;
        if (serverTookMillis == 0) return 0.0;
        return (double) serverTookMillis / itemCount;
    }

    /**
     * Construct a successful BulkOutcome.
     */
    public static BulkOutcome success(long tookMillis, long serverTookMillis, int itemCount, long batchBytes, int attemptCount) {
        return BulkOutcome.builder()
            .tookMillis(tookMillis)
            .serverTookMillis(serverTookMillis)
            .itemCount(itemCount)
            .batchBytes(batchBytes)
            .attemptCount(attemptCount)
            .rejectionKind(RejectionKind.NONE)
            .success(true)
            .failureMessage(null)
            .cause(null)
            .build();
    }

    /**
     * Construct a failed BulkOutcome, preserving the underlying throwable cause.
     */
    public static BulkOutcome failure(
        long tookMillis,
        long serverTookMillis,
        int itemCount,
        long batchBytes,
        int attemptCount,
        RejectionKind rejectionKind,
        String failureMessage,
        Throwable cause
    ) {
        return BulkOutcome.builder()
            .tookMillis(tookMillis)
            .serverTookMillis(serverTookMillis)
            .itemCount(itemCount)
            .batchBytes(batchBytes)
            .attemptCount(attemptCount)
            .rejectionKind(rejectionKind)
            .success(false)
            .failureMessage(failureMessage)
            .cause(cause)
            .build();
    }

    /**
     * Construct a failed BulkOutcome (no underlying throwable).
     */
    public static BulkOutcome failure(
        long tookMillis,
        long serverTookMillis,
        int itemCount,
        long batchBytes,
        int attemptCount,
        RejectionKind rejectionKind,
        String failureMessage
    ) {
        return failure(tookMillis, serverTookMillis, itemCount, batchBytes, attemptCount, rejectionKind, failureMessage, null);
    }
}
