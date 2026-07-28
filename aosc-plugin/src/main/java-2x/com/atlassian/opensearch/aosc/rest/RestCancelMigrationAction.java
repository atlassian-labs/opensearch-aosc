/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */
package com.atlassian.opensearch.aosc.rest;

import com.atlassian.opensearch.aosc.action.cancel.CancelMigrationAction;
import com.atlassian.opensearch.aosc.action.cancel.CancelMigrationBody;
import com.atlassian.opensearch.aosc.action.cancel.CancelMigrationRequest;

import org.opensearch.client.node.NodeClient;
import org.opensearch.rest.BaseRestHandler;
import org.opensearch.rest.RestRequest;
import org.opensearch.rest.action.RestToXContentListener;

import java.util.List;

/**
 * REST handler for {@code POST /_plugins/_aosc/{index}/_cancel}.
 *
 * <p>Cancels an active migration by source index name. Dispatches to {@link CancelMigrationAction}.
 */
public class RestCancelMigrationAction extends BaseRestHandler {

    @Override
    public String getName() {
        return "aosc_cancel_migration";
    }

    @Override
    public List<Route> routes() {
        return List.of(new Route(RestRequest.Method.POST, "/_plugins/_aosc/{index}/_cancel"));
    }

    @Override
    protected RestChannelConsumer prepareRequest(RestRequest request, NodeClient client) {
        String sourceIndex = request.param("index");
        CancelMigrationRequest cancelRequest = new CancelMigrationRequest(new CancelMigrationBody(sourceIndex));
        return channel -> client.execute(CancelMigrationAction.INSTANCE, cancelRequest, new RestToXContentListener<>(channel));
    }
}
