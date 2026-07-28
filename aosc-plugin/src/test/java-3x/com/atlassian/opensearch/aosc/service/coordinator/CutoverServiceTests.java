/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */
package com.atlassian.opensearch.aosc.service.coordinator;

import com.atlassian.opensearch.aosc.model.CutoverContext;
import com.atlassian.opensearch.aosc.utils.AoscLogger;
import com.atlassian.opensearch.aosc.utils.IndexOperationUtils;

import org.apache.lucene.search.TotalHits;

import org.opensearch.action.admin.indices.refresh.RefreshRequest;
import org.opensearch.action.admin.indices.refresh.RefreshResponse;
import org.opensearch.action.search.SearchRequest;
import org.opensearch.action.search.SearchResponse;
import org.opensearch.core.action.ActionListener;
import org.opensearch.index.query.QueryBuilders;
import org.opensearch.search.SearchHit;
import org.opensearch.search.SearchHits;
import org.opensearch.test.OpenSearchTestCase;
import org.opensearch.transport.client.AdminClient;
import org.opensearch.transport.client.Client;
import org.opensearch.transport.client.IndicesAdminClient;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tests for {@link CutoverService}.
 */
public class CutoverServiceTests extends OpenSearchTestCase {

    private Client mockClient;
    private IndicesAdminClient mockIndicesAdmin;
    private IndexOperationUtils mockIndexOps;
    private CutoverService cutoverService;

    @Override
    public void setUp() throws Exception {
        super.setUp();
        mockClient = mock(Client.class);
        AdminClient mockAdmin = mock(AdminClient.class);
        mockIndicesAdmin = mock(IndicesAdminClient.class);
        when(mockClient.admin()).thenReturn(mockAdmin);
        when(mockAdmin.indices()).thenReturn(mockIndicesAdmin);
        mockIndexOps = mock(IndexOperationUtils.class);
        cutoverService = new CutoverService(AoscLogger.create(CutoverService.class), mockClient, mockIndexOps, "test-migration");
    }

    // ---- Success cases ----

    public void testCutoverSucceedsWithMatchingDocCounts() throws Exception {
        mockRefreshSuccess();
        mockSearchDocCounts("source-idx", 1000L, "target-idx", 1000L);
        when(mockIndexOps.swapAlias("source-idx", "target-idx", "my-alias")).thenReturn(CompletableFuture.completedFuture(null));

        CutoverContext ctx = cutoverService.executeCutover("source-idx", "target-idx", "my-alias", 0, null).get(5, TimeUnit.SECONDS);

        assertEquals(1000L, ctx.sourceDocCount());
        assertEquals(1000L, ctx.targetDocCount());
        assertEquals(0, ctx.docCountTolerance());
        assertTrue(ctx.docCountValidationPassed());
        assertTrue(ctx.aliasSwapSucceeded());
        assertTrue(ctx.durationMillis() >= 0);
        assertNull(ctx.errorMessage());
    }

    public void testCutoverSucceedsWithinTolerance() throws Exception {
        mockRefreshSuccess();
        mockSearchDocCounts("source-idx", 1000L, "target-idx", 998L);
        when(mockIndexOps.swapAlias("source-idx", "target-idx", "my-alias")).thenReturn(CompletableFuture.completedFuture(null));

        CutoverContext ctx = cutoverService.executeCutover("source-idx", "target-idx", "my-alias", 5, null).get(5, TimeUnit.SECONDS);

        assertEquals(1000L, ctx.sourceDocCount());
        assertEquals(998L, ctx.targetDocCount());
        assertEquals(5, ctx.docCountTolerance());
        assertTrue(ctx.docCountValidationPassed());
        assertTrue(ctx.aliasSwapSucceeded());
    }

    public void testCutoverSucceedsWithZeroDocsOnBothSides() throws Exception {
        mockRefreshSuccess();
        mockSearchDocCounts("source-idx", 0L, "target-idx", 0L);
        when(mockIndexOps.swapAlias("source-idx", "target-idx", "my-alias")).thenReturn(CompletableFuture.completedFuture(null));

        CutoverContext ctx = cutoverService.executeCutover("source-idx", "target-idx", "my-alias", 0, null).get(5, TimeUnit.SECONDS);

        assertEquals(0L, ctx.sourceDocCount());
        assertEquals(0L, ctx.targetDocCount());
        assertTrue(ctx.aliasSwapSucceeded());
    }

    // ---- Doc count validation failure ----

    public void testCutoverFailsOnDocCountMismatch() throws Exception {
        mockRefreshSuccess();
        mockSearchDocCounts("source-idx", 1000L, "target-idx", 500L);

        CompletableFuture<CutoverContext> future = cutoverService.executeCutover("source-idx", "target-idx", "my-alias", 10, null);

        ExecutionException ex = expectThrows(ExecutionException.class, () -> future.get(5, TimeUnit.SECONDS));
        assertTrue(ex.getCause() instanceof CutoverService.DocCountValidationException);

        CutoverService.DocCountValidationException docEx = (CutoverService.DocCountValidationException) ex.getCause();
        assertEquals(1000L, docEx.sourceDocCount());
        assertEquals(500L, docEx.targetDocCount());
        assertEquals(10, docEx.tolerance());

        // Alias swap should NOT have been attempted
        verify(mockIndexOps, never()).swapAlias(any(), any(), any());
    }

    public void testCutoverFailsWhenDiffExactlyExceedsTolerance() throws Exception {
        mockRefreshSuccess();
        mockSearchDocCounts("source-idx", 100L, "target-idx", 89L); // diff = 11, tolerance = 10

        CompletableFuture<CutoverContext> future = cutoverService.executeCutover("source-idx", "target-idx", "my-alias", 10, null);

        ExecutionException ex = expectThrows(ExecutionException.class, () -> future.get(5, TimeUnit.SECONDS));
        assertTrue(ex.getCause() instanceof CutoverService.DocCountValidationException);
        verify(mockIndexOps, never()).swapAlias(any(), any(), any());
    }

    public void testCutoverPassesWhenDiffExactlyEqualsTolerance() throws Exception {
        mockRefreshSuccess();
        mockSearchDocCounts("source-idx", 100L, "target-idx", 90L); // diff = 10, tolerance = 10
        when(mockIndexOps.swapAlias("source-idx", "target-idx", "my-alias")).thenReturn(CompletableFuture.completedFuture(null));

        CutoverContext ctx = cutoverService.executeCutover("source-idx", "target-idx", "my-alias", 10, null).get(5, TimeUnit.SECONDS);

        assertTrue(ctx.docCountValidationPassed());
        assertTrue(ctx.aliasSwapSucceeded());
    }

    // ---- Alias swap failure ----

    public void testCutoverThrowsOnAliasSwapFailure() throws Exception {
        mockRefreshSuccess();
        mockSearchDocCounts("source-idx", 100L, "target-idx", 100L);

        // Alias swap fails
        when(mockIndexOps.swapAlias("source-idx", "target-idx", "my-alias")).thenReturn(
            CompletableFuture.failedFuture(new RuntimeException("alias swap failed"))
        );

        CompletableFuture<CutoverContext> future = cutoverService.executeCutover("source-idx", "target-idx", "my-alias", 0, null);

        ExecutionException ex = expectThrows(ExecutionException.class, () -> future.get(5, TimeUnit.SECONDS));
        assertTrue(ex.getCause() instanceof CutoverService.CutoverFailedException);

        CutoverService.CutoverFailedException cutoverEx = (CutoverService.CutoverFailedException) ex.getCause();
        CutoverContext ctx = cutoverEx.cutoverContext();
        assertEquals(100L, ctx.sourceDocCount());
        assertEquals(100L, ctx.targetDocCount());
        assertFalse(ctx.aliasSwapSucceeded());
        assertNotNull(ctx.errorMessage());

        // Rollback is NOT done by CutoverService — it's the coordinator's responsibility
        verify(mockIndexOps, never()).swapAlias("target-idx", "source-idx", "my-alias");
    }

    // ---- Null checks ----

    public void testCutoverSucceedsWithValidationQuery() throws Exception {
        mockRefreshSuccess();
        mockSearchDocCounts("source-idx", 50L, "target-idx", 50L);
        when(mockIndexOps.swapAlias("source-idx", "target-idx", "my-alias")).thenReturn(CompletableFuture.completedFuture(null));

        org.opensearch.index.query.QueryBuilder termQuery = QueryBuilders.termQuery("status", "active");
        CutoverContext ctx = cutoverService.executeCutover("source-idx", "target-idx", "my-alias", 0, termQuery).get(5, TimeUnit.SECONDS);

        assertEquals(50L, ctx.sourceDocCount());
        assertEquals(50L, ctx.targetDocCount());
        assertTrue(ctx.docCountValidationPassed());
        assertTrue(ctx.aliasSwapSucceeded());
    }

    public void testNullSourceIndexThrows() {
        expectThrows(NullPointerException.class, () -> cutoverService.executeCutover(null, "target", "alias", 0, null));
    }

    public void testNullTargetIndexThrows() {
        expectThrows(NullPointerException.class, () -> cutoverService.executeCutover("source", null, "alias", 0, null));
    }

    public void testNullAliasThrows() {
        expectThrows(NullPointerException.class, () -> cutoverService.executeCutover("source", "target", null, 0, null));
    }

    // ---- Helpers ----

    @SuppressWarnings("unchecked")
    private void mockRefreshSuccess() {
        doAnswer(invocation -> {
            ActionListener<RefreshResponse> listener = (ActionListener<RefreshResponse>) invocation.getArguments()[1];
            listener.onResponse(mock(RefreshResponse.class));
            return null;
        }).when(mockIndicesAdmin).refresh(any(RefreshRequest.class), any(ActionListener.class));
    }

    @SuppressWarnings("unchecked")
    private void mockSearchDocCounts(String sourceIndex, long sourceCount, String targetIndex, long targetCount) {
        doAnswer(invocation -> {
            SearchRequest req = (SearchRequest) invocation.getArguments()[0];
            ActionListener<SearchResponse> listener = (ActionListener<SearchResponse>) invocation.getArguments()[1];
            String requestedIndex = req.indices()[0];
            long count;
            if (requestedIndex.equals(sourceIndex)) {
                count = sourceCount;
            } else if (requestedIndex.equals(targetIndex)) {
                count = targetCount;
            } else {
                listener.onFailure(new IllegalArgumentException("Unexpected index: " + requestedIndex));
                return null;
            }
            SearchHits hits = new SearchHits(new SearchHit[0], new TotalHits(count, TotalHits.Relation.EQUAL_TO), 1.0f);
            SearchResponse response = mock(SearchResponse.class);
            when(response.getHits()).thenReturn(hits);
            listener.onResponse(response);
            return null;
        }).when(mockClient).search(any(SearchRequest.class), any(ActionListener.class));
    }
}
