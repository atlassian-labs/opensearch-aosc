/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */
package com.atlassian.opensearch.aosc.service.adaptive;

import org.opensearch.action.NoShardAvailableActionException;
import org.opensearch.action.bulk.BulkItemResponse;
import org.opensearch.action.bulk.BulkResponse;
import org.opensearch.core.common.breaker.CircuitBreakingException;
import org.opensearch.core.concurrency.OpenSearchRejectedExecutionException;
import org.opensearch.core.index.shard.ShardId;
import org.opensearch.core.rest.RestStatus;
import org.opensearch.index.IndexNotFoundException;
import org.opensearch.index.shard.ShardNotFoundException;
import org.opensearch.test.OpenSearchTestCase;

import java.util.concurrent.TimeoutException;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Tests for {@link RejectionClassifier} — classifies bulk failures into rejection categories.
 */
public class RejectionClassifierTests extends OpenSearchTestCase {

    // ---- No failure ----

    public void testSuccessfulResponseReturnsNone() {
        BulkResponse response = mock(BulkResponse.class);
        when(response.hasFailures()).thenReturn(false);

        assertEquals(RejectionKind.NONE, RejectionClassifier.classify(response, null));
    }

    public void testNullResponseAndNullExceptionReturnsNone() {
        assertEquals(RejectionKind.NONE, RejectionClassifier.classify(null, null));
    }

    // ---- Exception-based classification ----

    public void testRejectedExecutionExceptionIsOverload() {
        OpenSearchRejectedExecutionException ex = new OpenSearchRejectedExecutionException("queue full");
        assertEquals(RejectionKind.OVERLOAD, RejectionClassifier.classify(null, ex));
    }

    public void testCircuitBreakingExceptionIsOverload() {
        CircuitBreakingException ex = new CircuitBreakingException("parent", 100, 50, null);
        assertEquals(RejectionKind.OVERLOAD, RejectionClassifier.classify(null, ex));
    }

    public void testWrappedRejectionExceptionIsOverload() {
        OpenSearchRejectedExecutionException inner = new OpenSearchRejectedExecutionException("queue full");
        RuntimeException wrapper = new RuntimeException("wrapped", inner);
        assertEquals(RejectionKind.OVERLOAD, RejectionClassifier.classify(null, wrapper));
    }

    public void testTimeoutExceptionIsTransient() {
        java.util.concurrent.TimeoutException ex = new TimeoutException("timed out");
        assertEquals(RejectionKind.TRANSIENT, RejectionClassifier.classify(null, ex));
    }

    public void testGenericExceptionIsTransient() {
        RuntimeException ex = new RuntimeException("something went wrong");
        assertEquals(RejectionKind.TRANSIENT, RejectionClassifier.classify(null, ex));
    }

    public void testMessageContainingRejectedIsOverload() {
        RuntimeException ex = new RuntimeException("es_rejected_execution");
        assertEquals(RejectionKind.OVERLOAD, RejectionClassifier.classify(null, ex));
    }

    public void testMessageContainingTooManyRequestsIsOverload() {
        RuntimeException ex = new RuntimeException("too many requests");
        assertEquals(RejectionKind.OVERLOAD, RejectionClassifier.classify(null, ex));
    }

    // ---- BulkResponse item-level classification ----

    public void test429ItemIsOverload() {
        BulkResponse response = mockResponseWithItem(RestStatus.TOO_MANY_REQUESTS);
        assertEquals(RejectionKind.OVERLOAD, RejectionClassifier.classify(response, null));
    }

    public void test400ItemIsFatal() {
        BulkResponse response = mockResponseWithItem(RestStatus.BAD_REQUEST);
        assertEquals(RejectionKind.FATAL, RejectionClassifier.classify(response, null));
    }

    /**
     * 409 {@code VersionConflictEngineException} is expected during backfill (target
     * already has a newer/equal version). Must be ignored so the migration continues.
     */
    public void test409ConflictItemIsIgnored() {
        BulkResponse response = mockResponseWithItem(RestStatus.CONFLICT);
        assertEquals(RejectionKind.NONE, RejectionClassifier.classify(response, null));
    }

    /** A mix of 409 conflicts and a real 400 fatal must still surface as FATAL. */
    public void test409ConflictMixedWith400IsFatal() {
        BulkResponse response = mockResponseWithItems(RestStatus.CONFLICT, RestStatus.BAD_REQUEST);
        assertEquals(RejectionKind.FATAL, RejectionClassifier.classify(response, null));
    }

    /** A mix of 409 conflicts and 429 overload must still surface as OVERLOAD (priority). */
    public void test409ConflictMixedWith429IsOverload() {
        BulkResponse response = mockResponseWithItems(RestStatus.CONFLICT, RestStatus.TOO_MANY_REQUESTS);
        assertEquals(RejectionKind.OVERLOAD, RejectionClassifier.classify(response, null));
    }

    public void testOverloadTakesPriorityOverFatal() {
        // Mix of 429 and 400 items — OVERLOAD wins
        BulkItemResponse.Failure overloadFailure = mock(BulkItemResponse.Failure.class);
        when(overloadFailure.getStatus()).thenReturn(RestStatus.TOO_MANY_REQUESTS);
        when(overloadFailure.getCause()).thenReturn(null);
        BulkItemResponse overloadItem = mock(BulkItemResponse.class);
        when(overloadItem.getFailure()).thenReturn(overloadFailure);

        BulkItemResponse.Failure fatalFailure = mock(BulkItemResponse.Failure.class);
        when(fatalFailure.getStatus()).thenReturn(RestStatus.BAD_REQUEST);
        when(fatalFailure.getCause()).thenReturn(null);
        BulkItemResponse fatalItem = mock(BulkItemResponse.class);
        when(fatalItem.getFailure()).thenReturn(fatalFailure);

        BulkResponse response = mock(BulkResponse.class);
        when(response.hasFailures()).thenReturn(true);
        when(response.getItems()).thenReturn(new BulkItemResponse[] { fatalItem, overloadItem });

        assertEquals(RejectionKind.OVERLOAD, RejectionClassifier.classify(response, null));
    }

    public void testNullItemsArrayIsTransient() {
        BulkResponse response = mock(BulkResponse.class);
        when(response.hasFailures()).thenReturn(true);
        when(response.getItems()).thenReturn(null);

        assertEquals(RejectionKind.TRANSIENT, RejectionClassifier.classify(response, null));
    }

    // ---- Oversized-payload classification ----

    /** Per-item status 400 (IAE→BAD_REQUEST) + 2 GiB IAE cause must be OVERLOAD, not FATAL. */
    public void testReleasableBytesStreamOutputOverflowItemIsOverload() {
        BulkItemResponse.Failure failure = mock(BulkItemResponse.Failure.class);
        when(failure.getStatus()).thenReturn(RestStatus.BAD_REQUEST);
        when(failure.getCause()).thenReturn(new IllegalArgumentException("ReleasableBytesStreamOutput cannot hold more than 2GB of data"));
        BulkItemResponse item = mock(BulkItemResponse.class);
        when(item.getFailure()).thenReturn(failure);

        BulkResponse response = mock(BulkResponse.class);
        when(response.hasFailures()).thenReturn(true);
        when(response.getItems()).thenReturn(new BulkItemResponse[] { item });

        assertEquals(RejectionKind.OVERLOAD, RejectionClassifier.classify(response, null));
    }

    public void test5xxItemWithOversizedPayloadCauseIsOverload() {
        BulkItemResponse.Failure failure = mock(BulkItemResponse.Failure.class);
        when(failure.getStatus()).thenReturn(RestStatus.INTERNAL_SERVER_ERROR);
        when(failure.getCause()).thenReturn(new RuntimeException("BytesStreamOutput cannot hold more than 2GB of data"));
        BulkItemResponse item = mock(BulkItemResponse.class);
        when(item.getFailure()).thenReturn(failure);

        BulkResponse response = mock(BulkResponse.class);
        when(response.hasFailures()).thenReturn(true);
        when(response.getItems()).thenReturn(new BulkItemResponse[] { item });

        assertEquals(RejectionKind.OVERLOAD, RejectionClassifier.classify(response, null));
    }

    /** Buried OpenSearchRejectedExecutionException in cause must still be OVERLOAD. */
    public void test5xxItemWithRejectedExecutionCauseIsOverload() {
        BulkItemResponse.Failure failure = mock(BulkItemResponse.Failure.class);
        when(failure.getStatus()).thenReturn(RestStatus.INTERNAL_SERVER_ERROR);
        when(failure.getCause()).thenReturn(
            new RuntimeException(
                "remote",
                new OpenSearchRejectedExecutionException("rejected execution of primary operation [coordinating_and_primary_bytes=...]")
            )
        );
        BulkItemResponse item = mock(BulkItemResponse.class);
        when(item.getFailure()).thenReturn(failure);

        BulkResponse response = mock(BulkResponse.class);
        when(response.hasFailures()).thenReturn(true);
        when(response.getItems()).thenReturn(new BulkItemResponse[] { item });

        assertEquals(RejectionKind.OVERLOAD, RejectionClassifier.classify(response, null));
    }

    /** Regression: legit 400 mapping/parse errors stay FATAL — cause-walk must not false-positive. */
    public void test400MapperParsingItemIsStillFatal() {
        BulkItemResponse.Failure failure = mock(BulkItemResponse.Failure.class);
        when(failure.getStatus()).thenReturn(RestStatus.BAD_REQUEST);
        when(failure.getCause()).thenReturn(new IllegalArgumentException("failed to parse field [foo] of type [keyword]"));
        BulkItemResponse item = mock(BulkItemResponse.class);
        when(item.getFailure()).thenReturn(failure);

        BulkResponse response = mock(BulkResponse.class);
        when(response.hasFailures()).thenReturn(true);
        when(response.getItems()).thenReturn(new BulkItemResponse[] { item });

        assertEquals(RejectionKind.FATAL, RejectionClassifier.classify(response, null));
    }

    /** Same overflow as a top-level wrapped exception (cause-chain walk). */
    public void testReleasableBytesStreamOutputOverflowExceptionIsOverload() {
        IllegalArgumentException root = new IllegalArgumentException("ReleasableBytesStreamOutput cannot hold more than 2GB of data");
        RuntimeException sendRequestEx = new RuntimeException(
            "SendRequestTransportException[[node-name][host:port][indices:data/write/bulk[s]]]",
            root
        );
        assertEquals(RejectionKind.OVERLOAD, RejectionClassifier.classify(null, sendRequestEx));
    }

    public void testHttpContentLengthTooLongIsOverload() {
        RuntimeException ex = new RuntimeException("Content-Length too long: 314572800 (max=104857600)");
        assertEquals(RejectionKind.OVERLOAD, RejectionClassifier.classify(null, ex));
    }

    public void testRequestEntityTooLargeIsOverload() {
        RuntimeException ex = new RuntimeException("Request entity too large");
        assertEquals(RejectionKind.OVERLOAD, RejectionClassifier.classify(null, ex));
    }

    public void testInFlightRequestsBreakerIsOverload() {
        RuntimeException ex = new RuntimeException("[parent] Data too large, data for [in_flight_requests] would be larger than limit");
        assertEquals(RejectionKind.OVERLOAD, RejectionClassifier.classify(null, ex));
    }

    /** Defensive: benign "content" message must not false-positive. */
    public void testGenericContentMessageIsNotOverload() {
        RuntimeException ex = new RuntimeException("failed to parse content");
        assertEquals(RejectionKind.TRANSIENT, RejectionClassifier.classify(null, ex));
    }

    /** Defensive: "content length" without "too long" must not false-positive. */
    public void testInvalidContentLengthIsNotOverload() {
        RuntimeException ex = new RuntimeException("invalid content length header value");
        assertEquals(RejectionKind.TRANSIENT, RejectionClassifier.classify(null, ex));
    }

    /** TcpTransport's actual oversized-message phrasing must be OVERLOAD. */
    public void testTransportContentLengthExceededIsOverload() {
        RuntimeException ex = new RuntimeException("transport content length received [3gb] exceeded [30%]");
        assertEquals(RejectionKind.OVERLOAD, RejectionClassifier.classify(null, ex));
    }

    // ---- Shard-not-available family (must be TRANSIENT, not FATAL) ----

    public void testShardNotFoundExceptionIsTransient() {
        Throwable ex = new ShardNotFoundException(new ShardId("idx", "uuid", 0));
        assertEquals(RejectionKind.TRANSIENT, RejectionClassifier.classify(null, ex));
    }

    public void testIndexNotFoundExceptionIsTransient() {
        Throwable ex = new IndexNotFoundException("missing-index");
        assertEquals(RejectionKind.TRANSIENT, RejectionClassifier.classify(null, ex));
    }

    public void testNoShardAvailableExceptionIsTransient() {
        Throwable ex = new NoShardAvailableActionException(null, "no shards");
        assertEquals(RejectionKind.TRANSIENT, RejectionClassifier.classify(null, ex));
    }

    /** A per-item 4xx whose cause is TimeoutException must be TRANSIENT, not FATAL. */
    public void testItemWithTimeoutCauseIsTransient() {
        BulkItemResponse.Failure failure = mock(BulkItemResponse.Failure.class);
        when(failure.getStatus()).thenReturn(RestStatus.BAD_REQUEST);
        when(failure.getCause()).thenReturn(new TimeoutException("request timed out"));
        BulkItemResponse item = mock(BulkItemResponse.class);
        when(item.getFailure()).thenReturn(failure);

        BulkResponse response = mock(BulkResponse.class);
        when(response.hasFailures()).thenReturn(true);
        when(response.getItems()).thenReturn(new BulkItemResponse[] { item });

        assertEquals(RejectionKind.TRANSIENT, RejectionClassifier.classify(response, null));
    }

    /** A per-item 404 whose cause is shard-unavailable must be TRANSIENT, not FATAL. */
    public void testItemWithShardNotFoundCauseIsTransient() {
        BulkItemResponse.Failure failure = mock(BulkItemResponse.Failure.class);
        when(failure.getStatus()).thenReturn(RestStatus.NOT_FOUND);
        when(failure.getCause()).thenReturn(new ShardNotFoundException(new ShardId("idx", "uuid", 0)));
        BulkItemResponse item = mock(BulkItemResponse.class);
        when(item.getFailure()).thenReturn(failure);

        BulkResponse response = mock(BulkResponse.class);
        when(response.hasFailures()).thenReturn(true);
        when(response.getItems()).thenReturn(new BulkItemResponse[] { item });

        assertEquals(RejectionKind.TRANSIENT, RejectionClassifier.classify(response, null));
    }

    // ---- Helper ----

    private BulkResponse mockResponseWithItem(RestStatus status) {
        return mockResponseWithItems(status);
    }

    private BulkResponse mockResponseWithItems(RestStatus... statuses) {
        BulkItemResponse[] items = new BulkItemResponse[statuses.length];
        for (int i = 0; i < statuses.length; i++) {
            BulkItemResponse.Failure failure = mock(BulkItemResponse.Failure.class);
            when(failure.getStatus()).thenReturn(statuses[i]);
            when(failure.getCause()).thenReturn(null);

            BulkItemResponse item = mock(BulkItemResponse.class);
            when(item.getFailure()).thenReturn(failure);
            items[i] = item;
        }

        BulkResponse response = mock(BulkResponse.class);
        when(response.hasFailures()).thenReturn(true);
        when(response.getItems()).thenReturn(items);

        return response;
    }
}
