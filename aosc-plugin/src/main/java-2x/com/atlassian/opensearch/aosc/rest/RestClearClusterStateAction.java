/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */
package com.atlassian.opensearch.aosc.rest;

import com.atlassian.opensearch.aosc.action.clear.ClearClusterStateAction;
import com.atlassian.opensearch.aosc.action.clear.ClearClusterStateBody;
import com.atlassian.opensearch.aosc.action.clear.ClearClusterStateRequest;

import org.opensearch.client.node.NodeClient;
import org.opensearch.rest.BaseRestHandler;
import org.opensearch.rest.RestRequest;
import org.opensearch.rest.action.RestToXContentListener;

import java.util.List;

import static java.util.Collections.singletonList;
import static org.opensearch.rest.RestRequest.Method.POST;

/**
 * REST handler for clearing AOSC cluster state.
 *
 * <p>Query parameters:
 * <ul>
 *   <li>{@code dry_run} (default true) — preview what would be cleared without modifying state. Pass {@code dry_run=false} explicitly to perform the actual clear.</li>
 *   <li>{@code try_close} (default true) — also close in-memory coordinators on the CM node</li>
 *   <li>{@code migration_id} (optional) — target a specific migration; omit to clear all</li>
 *   <li>{@code detailed} (default false) — include full cluster state Entry in response</li>
 * </ul>
 */
public class RestClearClusterStateAction extends BaseRestHandler {

    @Override
    public String getName() {
        return "aosc_clear_cluster_state";
    }

    @Override
    public List<Route> routes() {
        return singletonList(new Route(POST, "/_plugins/_aosc/_admin/_clear_state"));
    }

    @Override
    protected RestChannelConsumer prepareRequest(RestRequest request, NodeClient client) {
        boolean dryRun = request.paramAsBoolean("dry_run", true);
        boolean tryClose = request.paramAsBoolean("try_close", true);
        String migrationId = request.hasParam("migration_id") ? request.param("migration_id") : null;
        boolean detailed = request.paramAsBoolean("detailed", false);

        ClearClusterStateBody body = new ClearClusterStateBody(dryRun, tryClose, migrationId, detailed);
        ClearClusterStateRequest clearRequest = new ClearClusterStateRequest(body);
        return channel -> client.execute(ClearClusterStateAction.INSTANCE, clearRequest, new RestToXContentListener<>(channel));
    }
}
