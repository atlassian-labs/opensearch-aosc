/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */
package com.atlassian.opensearch.aosc.service.adaptive;

import org.opensearch.action.NoShardAvailableActionException;
import org.opensearch.action.UnavailableShardsException;
import org.opensearch.action.bulk.BulkItemResponse;
import org.opensearch.action.bulk.BulkResponse;
import org.opensearch.core.common.breaker.CircuitBreakingException;
import org.opensearch.core.concurrency.OpenSearchRejectedExecutionException;
import org.opensearch.core.rest.RestStatus;
import org.opensearch.index.IndexNotFoundException;
import org.opensearch.index.shard.IllegalIndexShardStateException;
import org.opensearch.index.shard.ShardNotFoundException;

import java.util.Iterator;
import java.util.Locale;
import java.util.concurrent.TimeoutException;

/**
 * Classifies bulk response failures and exceptions into {@link RejectionKind}
 * categories to guide adaptive batch sizing decisions.
 *
 * <p>Classification hierarchy:
 * <ul>
 *   <li><strong>OVERLOAD</strong>: 429, threadpool rejection, circuit breaker, queue full,
 *       or transport/HTTP payload-size overflow. Triggers multiplicative decrease
 *       in {@link AimdBatchSizeController}.</li>
 *   <li><strong>FATAL</strong>: 4xx (except 429 and 409) — mapping/parse errors, malformed requests</li>
 *   <li><strong>TRANSIENT</strong>: 5xx, timeouts, network errors — retry may succeed</li>
 *   <li><strong>NONE</strong>: no failures detected, or all per-item failures are ignorable
 *       (currently 409 {@code VersionConflictEngineException} — expected during backfill
 *       when an external write has already produced a newer/equal version of the doc;
 *       the migration must continue without aborting the batch)</li>
 * </ul>
 */
public final class RejectionClassifier {

    /** Hard cap on cause-chain depth to bound work and avoid pathological loops. */
    private static final int MAX_CAUSE_CHAIN_HOPS = 12;

    private RejectionClassifier() {}

    /**
     * Classify the rejection kind for a bulk request result.
     *
     * @param response BulkResponse, or null if exception occurred
     * @param t        Exception, or null if response received
     * @return RejectionKind classification
     */
    public static RejectionKind classify(BulkResponse response, Throwable t) {
        if (t != null) {
            return classifyException(t);
        }
        if (response != null && response.hasFailures()) {
            return classifyBulkResponse(response);
        }
        return RejectionKind.NONE;
    }

    // ---- Exception-path classification --------------------------------------

    /** Walks the cause chain (bounded) and returns the first specific signal found. */
    private static RejectionKind classifyException(Throwable t) {
        for (Throwable cur : causeChain(t)) {
            RejectionKind kind = classifySingleThrowable(cur);
            if (kind != null) return kind;
        }
        return RejectionKind.TRANSIENT;
    }

    /** Classifies a single throwable by type + message; returns {@code null} if no signal. */
    private static RejectionKind classifySingleThrowable(Throwable t) {
        if (t instanceof OpenSearchRejectedExecutionException || t instanceof CircuitBreakingException) {
            return RejectionKind.OVERLOAD;
        }
        if (t instanceof TimeoutException || isShardUnavailable(t)) {
            return RejectionKind.TRANSIENT;
        }
        String message = t.getMessage();
        if (message != null && isOverloadMessage(message.toLowerCase(Locale.ROOT))) {
            return RejectionKind.OVERLOAD;
        }
        return null;
    }

    /**
     * Mirrors {@code TransportActions.isShardNotAvailableException}: these mean the
     * target shard is temporarily missing/closed/relocating. Retrying after a backoff
     * may succeed once the cluster restabilises, so we classify as TRANSIENT instead of
     * FATAL (the synthesised RestStatus would otherwise be 404 and bucket as 4xx-FATAL).
     */
    private static boolean isShardUnavailable(Throwable t) {
        return t instanceof ShardNotFoundException
            || t instanceof IndexNotFoundException
            || t instanceof IllegalIndexShardStateException
            || t instanceof NoShardAvailableActionException
            || t instanceof UnavailableShardsException;
    }

    // ---- BulkResponse-path classification -----------------------------------

    /**
     * Classifies a {@link BulkResponse} that contains failures.
     *
     * <p>For each failed item we walk the cause chain <em>before</em> bucketing by
     * {@link RestStatus}: {@code BulkItemResponse.Failure} derives its status via
     * {@code ExceptionsHelper.status(cause)}, which maps {@code IllegalArgumentException}
     * to {@link RestStatus#BAD_REQUEST}. The 2 GiB {@code ReleasableBytesStreamOutput}
     * overflow surfaces exactly that way, so a status-only check would misclassify it as
     * {@link RejectionKind#FATAL} and the controller would never shrink.
     */
    private static RejectionKind classifyBulkResponse(BulkResponse response) {
        var items = response.getItems();
        if (items == null) {
            return RejectionKind.TRANSIENT;
        }

        boolean anyOverload = false;
        boolean anyFatal = false;
        boolean anyTransient = false;
        for (var item : items) {
            RejectionKind kind = classifyFailedItem(item);
            if (kind == RejectionKind.OVERLOAD) {
                anyOverload = true;
            } else if (kind == RejectionKind.FATAL) {
                anyFatal = true;
            } else if (kind == RejectionKind.TRANSIENT) {
                anyTransient = true;
            }
            // null = no failure or ignorable (e.g. 409 conflict) — does not contribute.
        }

        if (anyOverload) return RejectionKind.OVERLOAD;
        if (anyFatal) return RejectionKind.FATAL;
        if (anyTransient) return RejectionKind.TRANSIENT;
        // Either no failures at all, or every failure was ignorable (409 conflicts).
        // Surface as NONE so the bulk is treated as a successful no-op and the
        // migration continues without retry / abort.
        return RejectionKind.NONE;
    }

    /**
     * Classifies a single bulk item. Walks the cause chain first to catch signals
     * that the synthesised {@link RestStatus} would otherwise mask: shard-unavailable
     * (404 → TRANSIENT), {@link TimeoutException} (400/5xx → TRANSIENT), and payload
     * overflow (IAE → 400 → OVERLOAD). Falls back to status-code bucketing.
     * Returns {@code null} for items without failures and ignorable 409 conflicts.
     */
    private static RejectionKind classifyFailedItem(BulkItemResponse item) {
        var failure = item.getFailure();
        if (failure == null) return null;

        Throwable cause = failure.getCause();
        if (cause != null) {
            for (Throwable cur : causeChain(cause)) {
                if (isShardUnavailable(cur) || cur instanceof TimeoutException) return RejectionKind.TRANSIENT;
                if (classifySingleThrowable(cur) == RejectionKind.OVERLOAD) return RejectionKind.OVERLOAD;
            }
        }

        int statusCode = failure.getStatus().getStatus();
        if (isIgnorableConflict(statusCode)) return null;
        return classifyByStatusCode(statusCode);
    }

    /** 409 conflicts are expected during backfill (target already has newer/equal version). */
    private static boolean isIgnorableConflict(int statusCode) {
        return statusCode == 409;
    }

    /** Maps an HTTP status code to a {@link RejectionKind} (status-only fallback). */
    private static RejectionKind classifyByStatusCode(int statusCode) {
        if (statusCode == 429) return RejectionKind.OVERLOAD;
        if (statusCode >= 400 && statusCode < 500) return RejectionKind.FATAL;
        return RejectionKind.TRANSIENT;
    }

    // ---- Cause-chain iterator ------------------------------------------------

    /** Bounded, self-loop-safe iterator over a throwable's cause chain. */
    private static Iterable<Throwable> causeChain(Throwable t) {
        return () -> new Iterator<>() {
            private Throwable cur = t;
            private int hops = 0;

            @Override
            public boolean hasNext() {
                return cur != null && hops < MAX_CAUSE_CHAIN_HOPS;
            }

            @Override
            public Throwable next() {
                Throwable out = cur;
                Throwable nx = cur.getCause();
                cur = (nx == cur) ? null : nx;
                hops++;
                return out;
            }
        };
    }

    // ---- Message heuristics --------------------------------------------------

    /**
     * Caller must pass an already-lower-cased message. Keeping the lower-casing at the
     * boundary avoids redundant {@link String#toLowerCase(Locale)} on the hot
     * {@link #isOversizedPayloadMessage} delegation.
     */
    private static boolean isOverloadMessage(String lower) {
        return lower.contains("rejected")
            || lower.contains("circuit_breaking")
            || lower.contains("too_many")
            || lower.contains("too many requests")
            || lower.contains("queue full")
            || isOversizedPayloadMessage(lower);
    }

    /**
     * Detects transport- or HTTP-layer payload-size overflow (e.g. the 2&nbsp;GiB
     * {@code BytesStreamOutput}/{@code ReleasableBytesStreamOutput} ceiling,
     * {@code http.max_content_length}, in-flight-bytes breaker). Treated as OVERLOAD
     * because the corrective action — shrink the next batch — is identical.
     */
    private static boolean isOversizedPayloadMessage(String lower) {
        return lower.contains("releasablebytesstreamoutput")
            || lower.contains("cannot hold more than")
            || lower.contains("content-length too long")
            || lower.contains("content length too long")
            || lower.contains("transport content length received")
            || lower.contains("request entity too large")
            || lower.contains("in_flight_requests");
    }
}
