/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */
package com.atlassian.opensearch.aosc.rest;

import com.atlassian.opensearch.aosc.action.start.StartMigrationAction;
import com.atlassian.opensearch.aosc.action.start.StartMigrationRequest;
import com.atlassian.opensearch.aosc.model.MigrationRequest;

import org.opensearch.rest.BaseRestHandler;
import org.opensearch.rest.RestRequest;
import org.opensearch.rest.action.RestToXContentListener;
import org.opensearch.transport.client.node.NodeClient;

import java.io.IOException;
import java.util.List;

/**
 * REST handler for {@code POST /_plugins/_aosc/{index}/_start}.
 *
 * <p>Parses the request body as a {@link MigrationRequest}, injects the source index
 * from the path parameter, and dispatches to {@link StartMigrationAction}.
 */
public class RestStartMigrationAction extends BaseRestHandler {

    @Override
    public String getName() {
        return "aosc_start_migration";
    }

    @Override
    public List<Route> routes() {
        return List.of(new Route(RestRequest.Method.POST, "/_plugins/_aosc/{index}/_start"));
    }

    @Override
    protected RestChannelConsumer prepareRequest(RestRequest request, NodeClient client) throws IOException {
        String sourceIndex = request.param("index");
        MigrationRequest migrationRequest = request.hasContent()
            ? MigrationRequest.fromXContent(request.contentParser())
            : new MigrationRequest();
        migrationRequest.setSourceIndex(sourceIndex);

        StartMigrationRequest startRequest = new StartMigrationRequest(migrationRequest);
        return channel -> client.execute(StartMigrationAction.INSTANCE, startRequest, new RestToXContentListener<>(channel));
    }
}
