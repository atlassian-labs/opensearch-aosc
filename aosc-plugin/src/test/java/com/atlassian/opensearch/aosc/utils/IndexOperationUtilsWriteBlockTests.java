/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */
package com.atlassian.opensearch.aosc.utils;

import com.atlassian.opensearch.aosc.compat.MockClientFactory;

import org.opensearch.action.admin.indices.settings.put.UpdateSettingsRequest;
import org.opensearch.core.action.ActionListener;
import org.opensearch.test.OpenSearchTestCase;

import java.util.Arrays;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.verify;

public class IndexOperationUtilsWriteBlockTests extends OpenSearchTestCase {

    private MockClientFactory.Handle mock;
    private IndexOperationUtils utils;

    @Override
    public void setUp() throws Exception {
        super.setUp();
        mock = MockClientFactory.createHandle();
        utils = new IndexOperationUtils(AoscLogger.create(IndexOperationUtils.class), mock.helper());
    }

    public void testRemoveWriteBlockIssuesIndexBlocksWriteFalse() throws Exception {
        mockUpdateSettingsAck();

        utils.removeWriteBlock("source-idx").get(5, TimeUnit.SECONDS);

        ArgumentCaptor<UpdateSettingsRequest> captor = ArgumentCaptor.forClass(UpdateSettingsRequest.class);
        verify(mock.indicesAdmin()).updateSettings(captor.capture(), any(ActionListener.class));

        UpdateSettingsRequest req = captor.getValue();
        assertTrue(
            "Request should target source-idx, was: " + Arrays.toString(req.indices()),
            Arrays.asList(req.indices()).contains("source-idx")
        );
        assertEquals("false", req.settings().get("index.blocks.write"));
    }

    // applyWriteBlock now uses addBlock API — covered by integration tests (IndexMetadata.APIBlock
    // requires full node init). See B048.

    /** Two consecutive removes must both succeed — guards the success-path remover against an already-cleared block. */
    public void testRemoveWriteBlockIsIdempotent() throws Exception {
        mockUpdateSettingsAck();

        CompletableFuture<Void> first = utils.removeWriteBlock("source-idx");
        CompletableFuture<Void> second = utils.removeWriteBlock("source-idx");

        first.get(5, TimeUnit.SECONDS);
        second.get(5, TimeUnit.SECONDS);

        verify(mock.indicesAdmin(), Mockito.times(2)).updateSettings(any(UpdateSettingsRequest.class), any(ActionListener.class));
    }

    @SuppressWarnings({ "unchecked", "rawtypes" })
    private void mockUpdateSettingsAck() {
        doAnswer(invocation -> {
            ActionListener listener = invocation.getArgument(1);
            listener.onResponse(MockClientFactory.acknowledgedResponse(true));
            return null;
        }).when(mock.indicesAdmin()).updateSettings(any(UpdateSettingsRequest.class), any(ActionListener.class));
    }

}
