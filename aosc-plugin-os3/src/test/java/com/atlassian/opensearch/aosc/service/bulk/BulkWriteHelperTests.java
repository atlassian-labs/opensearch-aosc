/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */
package com.atlassian.opensearch.aosc.service.bulk;

import com.atlassian.opensearch.aosc.service.adaptive.BulkOutcome;
import com.atlassian.opensearch.aosc.service.adaptive.RejectionKind;
import com.atlassian.opensearch.aosc.utils.AoscLogger;

import org.opensearch.action.bulk.BulkItemResponse;
import org.opensearch.action.bulk.BulkRequest;
import org.opensearch.action.bulk.BulkResponse;
import org.opensearch.core.action.ActionListener;
import org.opensearch.core.rest.RestStatus;
import org.opensearch.test.OpenSearchTestCase;
import org.opensearch.threadpool.TestThreadPool;
import org.opensearch.threadpool.ThreadPool;
import org.opensearch.transport.client.Client;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class BulkWriteHelperTests extends OpenSearchTestCase {

    private ThreadPool threadPool;
    private long originalRetryDelay;

    @Override
    public void setUp() throws Exception {
        super.setUp();
        threadPool = new TestThreadPool(getTestName());
        originalRetryDelay = BulkWriteHelper.baseRetryDelayMs;
        BulkWriteHelper.baseRetryDelayMs = 100L;
    }

    @Override
    public void tearDown() throws Exception {
        BulkWriteHelper.baseRetryDelayMs = originalRetryDelay;
        ThreadPool.terminate(threadPool, 10, TimeUnit.SECONDS);
        super.tearDown();
    }

    public void testSuccessReturnsSuccessOutcome() throws Exception {
        BulkResponse okResponse = mock(BulkResponse.class);
        when(okResponse.hasFailures()).thenReturn(false);

        BulkOutcome outcome = BulkWriteHelper.submitBulkAsyncWithOutcome(
            createRespondingClient(okResponse),
            new BulkRequest(),
            AoscLogger.create(BulkWriteHelper.class),
            threadPool
        ).get(5, TimeUnit.SECONDS);

        assertTrue(outcome.isSuccess());
        assertEquals(RejectionKind.NONE, outcome.getRejectionKind());
        assertEquals(1, outcome.getAttemptCount());
    }

    public void testTransientRetriesAndExhausts() throws Exception {
        IOException cause = new IOException("connection refused");

        BulkOutcome outcome = BulkWriteHelper.submitBulkAsyncWithOutcome(
            createFailingClient(cause),
            new BulkRequest(),
            AoscLogger.create(BulkWriteHelper.class),
            threadPool
        ).get(30, TimeUnit.SECONDS);

        assertFalse(outcome.isSuccess());
        assertEquals(RejectionKind.TRANSIENT, outcome.getRejectionKind());
        assertEquals(BulkWriteHelper.MAX_BULK_RETRIES + 1, outcome.getAttemptCount());
        assertNotNull(outcome.getCause());
    }

    public void testOverloadReturnsImmediately() throws Exception {
        BulkResponse overloadResponse = mockResponseWithItem(RestStatus.TOO_MANY_REQUESTS);

        BulkOutcome outcome = BulkWriteHelper.submitBulkAsyncWithOutcome(
            createRespondingClient(overloadResponse),
            new BulkRequest(),
            AoscLogger.create(BulkWriteHelper.class),
            threadPool
        ).get(5, TimeUnit.SECONDS);

        assertFalse(outcome.isSuccess());
        assertEquals(RejectionKind.OVERLOAD, outcome.getRejectionKind());
        assertEquals(1, outcome.getAttemptCount()); // no retry — consumer handles via unack
    }

    public void testFatalReturnsImmediately() throws Exception {
        BulkResponse fatalResponse = mockResponseWithItem(RestStatus.BAD_REQUEST);

        BulkOutcome outcome = BulkWriteHelper.submitBulkAsyncWithOutcome(
            createRespondingClient(fatalResponse),
            new BulkRequest(),
            AoscLogger.create(BulkWriteHelper.class),
            threadPool
        ).get(5, TimeUnit.SECONDS);

        assertFalse(outcome.isSuccess());
        assertEquals(RejectionKind.FATAL, outcome.getRejectionKind());
        assertEquals(1, outcome.getAttemptCount()); // no retry
        assertNull(outcome.getCause()); // response-level, not exception
    }

    /** 2 GiB transport overflow surfaces as per-item 400 + IAE cause; must classify as OVERLOAD. */
    public void testTransportBufferOverflowIsOverloadNotFatal() throws Exception {
        IllegalArgumentException rootCause = new IllegalArgumentException("ReleasableBytesStreamOutput cannot hold more than 2GB of data");
        RuntimeException sendRequestEx = new RuntimeException(
            "SendRequestTransportException[[node-name][host:port][indices:data/write/bulk[s]]]",
            rootCause
        );

        BulkResponse overflowResponse = mockResponseWithItemAndCause(RestStatus.BAD_REQUEST, (Exception) sendRequestEx);

        BulkOutcome outcome = BulkWriteHelper.submitBulkAsyncWithOutcome(
            createRespondingClient(overflowResponse),
            new BulkRequest(),
            AoscLogger.create(BulkWriteHelper.class),
            threadPool
        ).get(5, TimeUnit.SECONDS);

        assertFalse(outcome.isSuccess());
        assertEquals(RejectionKind.OVERLOAD, outcome.getRejectionKind());
        assertEquals(1, outcome.getAttemptCount()); // OVERLOAD returns immediately; consumer retries
    }

    /**
     * Per-item 409 version conflicts must be treated as a no-op success so the migration
     * continues through docs that were already written at a higher version.
     */
    public void testVersionConflictIsSuccess() throws Exception {
        BulkResponse conflictResponse = mockResponseWithItem(RestStatus.CONFLICT);

        BulkOutcome outcome = BulkWriteHelper.submitBulkAsyncWithOutcome(
            createRespondingClient(conflictResponse),
            new BulkRequest(),
            AoscLogger.create(BulkWriteHelper.class),
            threadPool
        ).get(5, TimeUnit.SECONDS);

        assertTrue(outcome.isSuccess());
        assertEquals(RejectionKind.NONE, outcome.getRejectionKind());
        assertEquals(1, outcome.getAttemptCount());
    }

    // ---- Helpers ----

    private BulkResponse mockResponseWithItem(RestStatus status) {
        return mockResponseWithItemAndCause(status, null);
    }

    private BulkResponse mockResponseWithItemAndCause(RestStatus status, Exception cause) {
        BulkItemResponse.Failure failure = mock(BulkItemResponse.Failure.class);
        when(failure.getStatus()).thenReturn(status);
        when(failure.getCause()).thenReturn(cause);

        BulkItemResponse item = mock(BulkItemResponse.class);
        when(item.getFailure()).thenReturn(failure);

        BulkResponse response = mock(BulkResponse.class);
        when(response.hasFailures()).thenReturn(true);
        when(response.buildFailureMessage()).thenReturn("item failure: " + status);
        when(response.getItems()).thenReturn(new BulkItemResponse[] { item });
        return response;
    }

    @SuppressWarnings("unchecked")
    private Client createFailingClient(Exception failWith) {
        Client client = mock(Client.class);
        doAnswer(inv -> {
            ActionListener<BulkResponse> listener = inv.getArgument(1);
            listener.onFailure(failWith);
            return null;
        }).when(client).bulk(any(BulkRequest.class), any(ActionListener.class));
        return client;
    }

    @SuppressWarnings("unchecked")
    private Client createRespondingClient(BulkResponse response) {
        Client client = mock(Client.class);
        doAnswer(inv -> {
            ActionListener<BulkResponse> listener = inv.getArgument(1);
            listener.onResponse(response);
            return null;
        }).when(client).bulk(any(BulkRequest.class), any(ActionListener.class));
        return client;
    }
}
