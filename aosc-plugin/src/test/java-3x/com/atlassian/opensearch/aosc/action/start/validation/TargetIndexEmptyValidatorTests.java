/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */
package com.atlassian.opensearch.aosc.action.start.validation;

import com.atlassian.opensearch.aosc.model.MigrationRequest;
import com.atlassian.opensearch.aosc.transform.TransformFactory;

import org.opensearch.action.admin.indices.stats.CommonStats;
import org.opensearch.action.admin.indices.stats.IndicesStatsRequest;
import org.opensearch.action.admin.indices.stats.IndicesStatsResponse;
import org.opensearch.cluster.ClusterState;
import org.opensearch.common.settings.ClusterSettings;
import org.opensearch.common.settings.Settings;
import org.opensearch.core.action.ActionListener;
import org.opensearch.index.shard.DocsStats;
import org.opensearch.test.OpenSearchTestCase;
import org.opensearch.transport.client.AdminClient;
import org.opensearch.transport.client.Client;
import org.opensearch.transport.client.IndicesAdminClient;

import java.util.Collections;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link TargetIndexEmptyValidator}: verifies it issues an
 * IndicesStatsRequest and rejects when the target has any documents.
 */
public class TargetIndexEmptyValidatorTests extends OpenSearchTestCase {

    private static ValidationContext ctx(Client client) {
        MigrationRequest req = new MigrationRequest().setSourceIndex("src").setTargetIndex("tgt");
        return ValidationContext.of(
            req,
            ClusterState.EMPTY_STATE,
            null,
            null,
            new TransformFactory(null),
            new ClusterSettings(Settings.EMPTY, Collections.emptySet()),
            client
        );
    }

    /** Build a mock client whose indices().stats(...) immediately invokes the listener with the supplied docCount. */
    private static Client mockClientWithDocCount(long docCount) {
        Client client = mock(Client.class);
        AdminClient adminClient = mock(AdminClient.class);
        IndicesAdminClient indicesAdminClient = mock(IndicesAdminClient.class);
        when(client.admin()).thenReturn(adminClient);
        when(adminClient.indices()).thenReturn(indicesAdminClient);

        IndicesStatsResponse response = mock(IndicesStatsResponse.class);
        CommonStats total = mock(CommonStats.class);
        when(response.getTotal()).thenReturn(total);
        if (docCount >= 0) {
            DocsStats docs = new DocsStats(docCount, 0, 0);
            when(total.getDocs()).thenReturn(docs);
        } else {
            when(total.getDocs()).thenReturn(null);
        }

        doAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            ActionListener<IndicesStatsResponse> listener = (ActionListener<IndicesStatsResponse>) invocation.getArguments()[1];
            listener.onResponse(response);
            return null;
        }).when(indicesAdminClient).stats(any(IndicesStatsRequest.class), any());

        return client;
    }

    public void testEmptyTargetPasses() throws Exception {
        Client client = mockClientWithDocCount(0);
        CompletableFuture<Void> f = new TargetIndexEmptyValidator().validate(ctx(client));
        f.get(5, TimeUnit.SECONDS);
    }

    public void testNullDocsTreatedAsEmpty() throws Exception {
        Client client = mockClientWithDocCount(-1); // -1 sentinel => total.getDocs() returns null
        CompletableFuture<Void> f = new TargetIndexEmptyValidator().validate(ctx(client));
        f.get(5, TimeUnit.SECONDS);
    }

    public void testNonEmptyTargetRejects() throws Exception {
        Client client = mockClientWithDocCount(42);
        CompletableFuture<Void> f = new TargetIndexEmptyValidator().validate(ctx(client));
        ExecutionException ee = expectThrows(ExecutionException.class, () -> f.get(5, TimeUnit.SECONDS));
        assertTrue(ee.getCause() instanceof IllegalStateException);
        assertTrue(ee.getCause().getMessage(), ee.getCause().getMessage().contains("target index [tgt] is not empty (42 docs)"));
    }

    public void testClientFailurePropagates() throws Exception {
        Client client = mock(Client.class);
        AdminClient adminClient = mock(AdminClient.class);
        IndicesAdminClient indicesAdminClient = mock(IndicesAdminClient.class);
        when(client.admin()).thenReturn(adminClient);
        when(adminClient.indices()).thenReturn(indicesAdminClient);

        doAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            ActionListener<IndicesStatsResponse> listener = (ActionListener<IndicesStatsResponse>) invocation.getArguments()[1];
            listener.onFailure(new RuntimeException("kapow"));
            return null;
        }).when(indicesAdminClient).stats(any(IndicesStatsRequest.class), any());

        CompletableFuture<Void> f = new TargetIndexEmptyValidator().validate(ctx(client));
        ExecutionException ee = expectThrows(ExecutionException.class, () -> f.get(5, TimeUnit.SECONDS));
        assertEquals("kapow", ee.getCause().getMessage());
    }

}
