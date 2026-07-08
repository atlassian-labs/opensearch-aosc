/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */
package com.atlassian.opensearch.aosc.rest;

import com.atlassian.opensearch.aosc.action.list.ListMigrationsAction;
import com.atlassian.opensearch.aosc.action.list.ListMigrationsBody;
import com.atlassian.opensearch.aosc.action.list.ListMigrationsRequest;
import com.atlassian.opensearch.aosc.model.phase.CoordinatorPhase;

import org.opensearch.rest.BaseRestHandler;
import org.opensearch.rest.RestRequest;
import org.opensearch.rest.action.RestToXContentListener;
import org.opensearch.transport.client.node.NodeClient;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * REST handler for {@code GET /_plugins/_aosc/_list}.
 *
 * <p>Query params:
 * <ul>
 *   <li>{@code status} — comma-separated phases, or {@code all} (default: {@code all}).
 *       The pseudo-value {@code ACTIVE} expands to all non-terminal phases.</li>
 *   <li>{@code size} — max results (default {@value #DEFAULT_SIZE},
 *       silently clamped to {@value #INTERNAL_MAX_LIST_SIZE}).</li>
 * </ul>
 *
 * <p>The response is a slim projection — see {@link com.atlassian.opensearch.aosc.action.list.ListMigrationsResult}
 * and {@link com.atlassian.opensearch.aosc.model.MigrationSummary}. Use the
 * {@code Get} API for full document details on a specific migration.</p>
 */
public class RestListMigrationsAction extends BaseRestHandler {

    /** Default page size when {@code size} is not supplied. */
    static final int DEFAULT_SIZE = 50;

    /**
     * Hard server-side cap on the {@code size} parameter. Requests asking for
     * more than this are silently clamped — pagination is intentionally not
     * modeled at this time. If the merged result actually exceeds this cap,
     * the response will set {@code truncated = true}.
     */
    static final int INTERNAL_MAX_LIST_SIZE = 500;

    @Override
    public String getName() {
        return "aosc_list_migrations";
    }

    @Override
    public List<Route> routes() {
        return List.of(new Route(RestRequest.Method.GET, "/_plugins/_aosc/_list"));
    }

    @Override
    protected RestChannelConsumer prepareRequest(RestRequest request, NodeClient client) {
        String statusParam = request.param("status", "all");
        int requestedSize = request.paramAsInt("size", DEFAULT_SIZE);
        int size = clampSize(requestedSize);

        List<CoordinatorPhase> statusFilter = parseStatusFilter(statusParam);

        ListMigrationsRequest listRequest = new ListMigrationsRequest(new ListMigrationsBody(statusFilter, size));
        return channel -> client.execute(ListMigrationsAction.INSTANCE, listRequest, new RestToXContentListener<>(channel));
    }

    /**
     * Clamp the requested size into {@code [1, INTERNAL_MAX_LIST_SIZE]}. Oversized values are silently truncated to
     * {@link #INTERNAL_MAX_LIST_SIZE} (pagination is intentionally not modeled at this time \u2014 the cap is a safety
     * net, not a contract). Non-positive values are rejected with {@link IllegalArgumentException}, which the
     * OpenSearch REST layer translates to HTTP 400.
     *
     * @throws IllegalArgumentException if {@code requestedSize <= 0}
     */
    static int clampSize(int requestedSize) {
        if (requestedSize <= 0) {
            throw new IllegalArgumentException("size must be > 0, got: " + requestedSize);
        }
        return Math.min(requestedSize, INTERNAL_MAX_LIST_SIZE);
    }

    /**
     * Parse the comma-separated {@code status} query parameter into a list of {@link CoordinatorPhase} filters.
     *
     * <ul>
     *   <li>{@code "all"} (case-insensitive) returns an empty filter (no phase restriction).</li>
     *   <li>The pseudo-token {@code "ACTIVE"} expands to all 7 non-terminal phases.</li>
     *   <li>Any other token is parsed as a {@link CoordinatorPhase} enum name (case-insensitive).</li>
     * </ul>
     *
     * @throws IllegalArgumentException if any token is empty or not a known phase
     *                                  (the OpenSearch REST layer translates this to HTTP 400)
     */
    static List<CoordinatorPhase> parseStatusFilter(String statusParam) {
        if (statusParam == null || statusParam.isBlank()) {
            throw new IllegalArgumentException("status must not be blank");
        }
        if ("all".equalsIgnoreCase(statusParam)) {
            return List.of();
        }
        List<CoordinatorPhase> phases = new ArrayList<>();
        for (String part : statusParam.split(",")) {
            String trimmed = part.trim().toUpperCase(Locale.ROOT);
            if (trimmed.isEmpty()) {
                throw new IllegalArgumentException("status contains an empty token: '" + statusParam + "'");
            }
            if ("ACTIVE".equals(trimmed)) {
                // Pseudo-token: expands to all 7 non-terminal phases (preserves the historical
                // behavior of the API). Note: this shadows the literal CoordinatorPhase.ACTIVE
                // enum value — there is no way for callers to filter to ONLY that single phase.
                phases.add(CoordinatorPhase.ACTIVE);
                phases.add(CoordinatorPhase.INITIALIZING);
                phases.add(CoordinatorPhase.CUTTING_OVER);
                phases.add(CoordinatorPhase.CATCHING_UP);
                phases.add(CoordinatorPhase.COMPLETING);
                phases.add(CoordinatorPhase.CANCELLING);
                phases.add(CoordinatorPhase.FAILING);
            } else {
                try {
                    phases.add(CoordinatorPhase.valueOf(trimmed));
                } catch (IllegalArgumentException e) {
                    throw new IllegalArgumentException(
                        "unknown status: '" + trimmed + "'. Expected one of: all, ACTIVE, " + Arrays.toString(CoordinatorPhase.values()),
                        e
                    );
                }
            }
        }
        return phases;
    }
}
