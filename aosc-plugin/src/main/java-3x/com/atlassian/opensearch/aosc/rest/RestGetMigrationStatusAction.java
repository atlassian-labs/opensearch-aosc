/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */
package com.atlassian.opensearch.aosc.rest;

import com.atlassian.opensearch.aosc.action.status.GetMigrationStatusAction;
import com.atlassian.opensearch.aosc.action.status.GetMigrationStatusBody;
import com.atlassian.opensearch.aosc.action.status.GetMigrationStatusRequest;

import org.opensearch.rest.BaseRestHandler;
import org.opensearch.rest.RestRequest;
import org.opensearch.rest.action.RestToXContentListener;
import org.opensearch.transport.client.node.NodeClient;

import java.util.List;

/**
 * REST handler for {@code GET /_plugins/_aosc/{index}/_status}.
 *
 * <p>Returns the migration status from the coordinator cache (active) or Tier-1 (terminal).
 * Always returns HTTP 200; the response body contains {@code found: true/false}
 * to indicate whether a migration exists for the given source index.</p>
 */
public class RestGetMigrationStatusAction extends BaseRestHandler {

    @Override
    public String getName() {
        return "aosc_get_migration_status";
    }

    @Override
    public List<Route> routes() {
        return List.of(new Route(RestRequest.Method.GET, "/_plugins/_aosc/{index}/_status"));
    }

    @Override
    protected RestChannelConsumer prepareRequest(RestRequest request, NodeClient client) {
        String sourceIndex = request.param("index");
        GetMigrationStatusRequest statusRequest = new GetMigrationStatusRequest(new GetMigrationStatusBody(sourceIndex));
        return channel -> client.execute(GetMigrationStatusAction.INSTANCE, statusRequest, new RestToXContentListener<>(channel));
    }
}
