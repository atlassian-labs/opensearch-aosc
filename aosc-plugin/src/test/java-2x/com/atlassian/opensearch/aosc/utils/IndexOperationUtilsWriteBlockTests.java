/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */
package com.atlassian.opensearch.aosc.utils;

import org.opensearch.action.admin.indices.settings.put.UpdateSettingsRequest;
import org.opensearch.action.support.master.AcknowledgedResponse;
import org.opensearch.client.AdminClient;
import org.opensearch.client.Client;
import org.opensearch.client.IndicesAdminClient;
import org.opensearch.core.action.ActionListener;
import org.opensearch.test.OpenSearchTestCase;

import java.util.Arrays;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Unit tests for {@link IndexOperationUtils#applyWriteBlock(String)} and {@link IndexOperationUtils#removeWriteBlock(String)}. */
public class IndexOperationUtilsWriteBlockTests extends OpenSearchTestCase {

    private Client mockClient;
    private IndicesAdminClient mockIndicesAdmin;
    private IndexOperationUtils utils;

    @Override
    public void setUp() throws Exception {
        super.setUp();
        mockClient = mock(Client.class);
        AdminClient mockAdmin = mock(AdminClient.class);
        mockIndicesAdmin = mock(IndicesAdminClient.class);
        when(mockClient.admin()).thenReturn(mockAdmin);
        when(mockAdmin.indices()).thenReturn(mockIndicesAdmin);
        utils = new IndexOperationUtils(AoscLogger.create(IndexOperationUtils.class), mockClient);
    }

    public void testRemoveWriteBlockIssuesIndexBlocksWriteFalse() throws Exception {
        mockUpdateSettingsAck();

        utils.removeWriteBlock("source-idx").get(5, TimeUnit.SECONDS);

        ArgumentCaptor<UpdateSettingsRequest> captor = ArgumentCaptor.forClass(UpdateSettingsRequest.class);
        verify(mockIndicesAdmin).updateSettings(captor.capture(), any(ActionListener.class));

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

        verify(mockIndicesAdmin, Mockito.times(2)).updateSettings(any(UpdateSettingsRequest.class), any(ActionListener.class));
    }

    @SuppressWarnings("unchecked")
    private void mockUpdateSettingsAck() {
        doAnswer(invocation -> {
            ActionListener<AcknowledgedResponse> listener = invocation.getArgument(1);
            listener.onResponse(new AcknowledgedResponse(true) {
            });
            return null;
        }).when(mockIndicesAdmin).updateSettings(any(UpdateSettingsRequest.class), any(ActionListener.class));
    }

}
