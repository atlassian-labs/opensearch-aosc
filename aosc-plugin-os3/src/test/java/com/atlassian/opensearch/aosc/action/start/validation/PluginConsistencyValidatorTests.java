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

import org.opensearch.action.admin.cluster.node.info.NodesInfoRequest;
import org.opensearch.action.admin.cluster.node.info.NodesInfoResponse;
import org.opensearch.cluster.ClusterState;
import org.opensearch.common.settings.ClusterSettings;
import org.opensearch.common.settings.Settings;
import org.opensearch.core.action.ActionListener;
import org.opensearch.test.OpenSearchTestCase;
import org.opensearch.transport.client.AdminClient;
import org.opensearch.transport.client.Client;
import org.opensearch.transport.client.ClusterAdminClient;

import java.util.Collections;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link PluginConsistencyValidator}: verifies it bridges the
 * {@code TransportStartMigrationAction.validatePluginOnAllNodes} callback into
 * the returned {@link CompletableFuture}.
 */
public class PluginConsistencyValidatorTests extends OpenSearchTestCase {

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

    public void testEmptyNodesPasses() throws Exception {
        // Empty node list -> no missing plugin & no version mismatch -> success.
        Client client = mock(Client.class);
        AdminClient admin = mock(AdminClient.class);
        ClusterAdminClient cluster = mock(ClusterAdminClient.class);
        when(client.admin()).thenReturn(admin);
        when(admin.cluster()).thenReturn(cluster);

        NodesInfoResponse response = mock(NodesInfoResponse.class);
        when(response.getNodes()).thenReturn(Collections.emptyList());

        doAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            ActionListener<NodesInfoResponse> listener = (ActionListener<NodesInfoResponse>) invocation.getArguments()[1];
            listener.onResponse(response);
            return null;
        }).when(cluster).nodesInfo(any(NodesInfoRequest.class), any());

        CompletableFuture<Void> f = new PluginConsistencyValidator().validate(ctx(client));
        f.get(5, TimeUnit.SECONDS); // does not throw
    }

    public void testNodesInfoFailurePropagates() {
        Client client = mock(Client.class);
        AdminClient admin = mock(AdminClient.class);
        ClusterAdminClient cluster = mock(ClusterAdminClient.class);
        when(client.admin()).thenReturn(admin);
        when(admin.cluster()).thenReturn(cluster);

        doAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            ActionListener<NodesInfoResponse> listener = (ActionListener<NodesInfoResponse>) invocation.getArguments()[1];
            listener.onFailure(new RuntimeException("boom"));
            return null;
        }).when(cluster).nodesInfo(any(NodesInfoRequest.class), any());

        CompletableFuture<Void> f = new PluginConsistencyValidator().validate(ctx(client));
        ExecutionException ee = expectThrows(ExecutionException.class, () -> f.get(5, TimeUnit.SECONDS));
        assertEquals("boom", ee.getCause().getMessage());
    }
}
