/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */
package com.atlassian.opensearch.aosc.action.start.validation;

import com.atlassian.opensearch.aosc.AoscSettings;
import com.atlassian.opensearch.aosc.model.MigrationRequest;
import com.atlassian.opensearch.aosc.model.MigrationRequestOptions;
import com.atlassian.opensearch.aosc.transform.TransformFactory;

import org.apache.lucene.search.TotalHits;

import org.opensearch.action.search.SearchRequest;
import org.opensearch.action.search.SearchResponse;
import org.opensearch.cluster.ClusterState;
import org.opensearch.common.settings.ClusterSettings;
import org.opensearch.common.settings.Settings;
import org.opensearch.core.action.ActionListener;
import org.opensearch.search.SearchHit;
import org.opensearch.search.SearchHits;
import org.opensearch.test.OpenSearchTestCase;
import org.opensearch.transport.client.Client;

import java.util.HashSet;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import org.mockito.Mockito;

import static org.apache.lucene.search.TotalHits.Relation.EQUAL_TO;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

public class ValidationQueryValidatorTests extends OpenSearchTestCase {

    private final ValidationQueryValidator validator = new ValidationQueryValidator();

    private static ClusterSettings clusterSettings() {
        return new ClusterSettings(Settings.EMPTY, new HashSet<>(AoscSettings.ALL));
    }

    private static ValidationContext ctx(MigrationRequestOptions options) {
        return ctx(options, mockClientSuccess());
    }

    private static ValidationContext ctx(MigrationRequestOptions options, Client client) {
        MigrationRequest req = new MigrationRequest().setSourceIndex("src").setTargetIndex("tgt").setOptions(options);
        return ValidationContext.of(req, ClusterState.EMPTY_STATE, null, null, new TransformFactory(null), clusterSettings(), client);
    }

    @SuppressWarnings("unchecked")
    private static Client mockClientSuccess() {
        Client client = mock(Client.class);
        SearchResponse response = mock(SearchResponse.class);
        SearchHits hits = new SearchHits(new SearchHit[0], new TotalHits(0, EQUAL_TO), 1.0f);
        Mockito.when(response.getHits()).thenReturn(hits);
        doAnswer(inv -> {
            ActionListener<SearchResponse> listener = (ActionListener<SearchResponse>) inv.getArguments()[1];
            listener.onResponse(response);
            return null;
        }).when(client).search(any(SearchRequest.class), any(ActionListener.class));
        return client;
    }

    @SuppressWarnings("unchecked")
    private static Client mockClientFailure(String errorMsg) {
        Client client = mock(Client.class);
        doAnswer(inv -> {
            ActionListener<SearchResponse> listener = (ActionListener<SearchResponse>) inv.getArguments()[1];
            listener.onFailure(new RuntimeException(errorMsg));
            return null;
        }).when(client).search(any(SearchRequest.class), any(ActionListener.class));
        return client;
    }

    public void testNoOpWhenNoOptions() throws Exception {
        Client client = mock(Client.class);
        validator.validate(ctx(null, client)).get(5, TimeUnit.SECONDS);
        verify(client, never()).search(any(), any());
    }

    public void testNoOpWhenNullValidationQuery() throws Exception {
        Client client = mock(Client.class);
        validator.validate(ctx(new MigrationRequestOptions(), client)).get(5, TimeUnit.SECONDS);
        verify(client, never()).search(any(), any());
    }

    public void testNoOpWhenEmptyValidationQuery() throws Exception {
        Client client = mock(Client.class);
        MigrationRequestOptions opts = new MigrationRequestOptions();
        opts.setValidationQuery(Map.of());
        validator.validate(ctx(opts, client)).get(5, TimeUnit.SECONDS);
        verify(client, never()).search(any(), any());
    }

    public void testMatchAllQueryPasses() throws Exception {
        MigrationRequestOptions opts = new MigrationRequestOptions();
        opts.setValidationQuery(Map.of("match_all", Map.of()));
        validator.validate(ctx(opts)).get(5, TimeUnit.SECONDS);
    }

    public void testTermQueryPasses() throws Exception {
        MigrationRequestOptions opts = new MigrationRequestOptions();
        opts.setValidationQuery(Map.of("term", Map.of("status", "active")));
        validator.validate(ctx(opts)).get(5, TimeUnit.SECONDS);
    }

    public void testDryRunFailurePropagates() throws Exception {
        Client client = mockClientFailure("field [status] not found");
        MigrationRequestOptions opts = new MigrationRequestOptions();
        opts.setValidationQuery(Map.of("term", Map.of("status", "active")));
        CompletableFuture<Void> f = validator.validate(ctx(opts, client));
        ExecutionException ee = expectThrows(ExecutionException.class, () -> f.get(5, TimeUnit.SECONDS));
        assertTrue(ee.getCause().getMessage(), ee.getCause().getMessage().contains("validation_query failed on index"));
    }
}
