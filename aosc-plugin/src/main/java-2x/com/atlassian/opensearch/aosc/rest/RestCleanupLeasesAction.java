/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */
package com.atlassian.opensearch.aosc.rest;

import com.atlassian.opensearch.aosc.action.cleanup.CleanupLeasesAction;
import com.atlassian.opensearch.aosc.action.cleanup.CleanupLeasesBody;
import com.atlassian.opensearch.aosc.action.cleanup.CleanupLeasesRequest;

import org.opensearch.client.node.NodeClient;
import org.opensearch.rest.BaseRestHandler;
import org.opensearch.rest.RestRequest;
import org.opensearch.rest.action.RestToXContentListener;

import java.util.List;

/**
 * REST handler for emergency AOSC retention-lease cleanup.
 *
 * <p>Route: {@code POST /_plugins/_aosc/_admin/_cleanup_leases}
 *
 * <p>Query parameters:
 * <ul>
 *   <li>{@code index} (optional) — restrict cleanup to the named index. Omit to scan all open indices.</li>
 *   <li>{@code dry_run=true|false} (default {@code true}) — when {@code true} the action
 *       lists the AOSC-owned retention leases without removing them. To actually
 *       remove leases, pass {@code dry_run=false} explicitly.</li>
 * </ul>
 *
 * <p>Only retention leases whose ID begins with {@code aosc-migration-} are ever
 * inspected or removed; non-AOSC leases (peer-recovery, CCR, user-defined) are
 * passed over silently.
 */
public class RestCleanupLeasesAction extends BaseRestHandler {

    @Override
    public String getName() {
        return "aosc_cleanup_leases";
    }

    @Override
    public List<Route> routes() {
        return List.of(new Route(RestRequest.Method.POST, "/_plugins/_aosc/_admin/_cleanup_leases"));
    }

    @Override
    protected RestChannelConsumer prepareRequest(RestRequest request, NodeClient client) {
        String[] indices;
        if (request.hasParam("index")) {
            String index = request.param("index");
            if (index == null || index.isBlank()) {
                throw new IllegalArgumentException("[index] query parameter must not be empty");
            }
            indices = new String[] { index };
        } else {
            indices = new String[0];
        }
        boolean dryRun = request.paramAsBoolean("dry_run", true);
        CleanupLeasesRequest cleanupRequest = new CleanupLeasesRequest(new CleanupLeasesBody(indices, dryRun));
        return channel -> client.execute(CleanupLeasesAction.INSTANCE, cleanupRequest, new RestToXContentListener<>(channel));
    }
}
