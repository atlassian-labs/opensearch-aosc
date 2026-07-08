/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */
package com.atlassian.opensearch.aosc.action.cleanup;

import com.atlassian.opensearch.aosc.utils.AoscLogger;
import com.atlassian.opensearch.aosc.utils.AsyncUtils;
import com.atlassian.opensearch.aosc.utils.IndexOperationUtils;
import com.atlassian.opensearch.aosc.utils.IndexOperationUtils.MatchedLease;

import org.opensearch.action.support.ActionFilters;
import org.opensearch.action.support.HandledTransportAction;
import org.opensearch.client.Client;
import org.opensearch.common.inject.Inject;
import org.opensearch.core.action.ActionListener;
import org.opensearch.tasks.Task;
import org.opensearch.transport.TransportService;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

/**
 * Transport handler for {@link CleanupLeasesAction}.
 * Delegates to {@link IndexOperationUtils} for lease discovery and removal.
 */
public class TransportCleanupLeasesAction extends HandledTransportAction<CleanupLeasesRequest, CleanupLeasesResponse> {

    private final AoscLogger logger;
    private final IndexOperationUtils indexOps;

    @Inject
    public TransportCleanupLeasesAction(TransportService transportService, ActionFilters actionFilters, Client client) {
        super(CleanupLeasesAction.NAME, transportService, actionFilters, CleanupLeasesRequest::new);
        this.logger = AoscLogger.create(TransportCleanupLeasesAction.class);
        this.indexOps = new IndexOperationUtils(this.logger, Objects.requireNonNull(client, "client"));
    }

    /** Test constructor. */
    TransportCleanupLeasesAction(TransportService transportService, ActionFilters actionFilters, IndexOperationUtils indexOps) {
        super(CleanupLeasesAction.NAME, transportService, actionFilters, CleanupLeasesRequest::new);
        this.logger = AoscLogger.create(TransportCleanupLeasesAction.class);
        this.indexOps = Objects.requireNonNull(indexOps, "indexOps");
    }

    @Override
    protected void doExecute(Task task, CleanupLeasesRequest request, ActionListener<CleanupLeasesResponse> listener) {
        AsyncUtils.bridgeToListener(execute(indexOps, request, logger), listener);
    }

    static CompletableFuture<CleanupLeasesResponse> execute(IndexOperationUtils indexOps, CleanupLeasesRequest request, AoscLogger log) {
        return indexOps.findAoscLeases(request.body().indices()).thenCompose(matched -> {
            String indicesDesc = request.body().indices().length == 0
                ? "all indices"
                : "[" + request.body().indices().length + "] index pattern(s)";
            log.info("Found [{}] AOSC retention lease(s) across {}; dryRun=[{}]", matched.size(), indicesDesc, request.body().dryRun());

            if (matched.isEmpty()) {
                return CompletableFuture.completedFuture(
                    new CleanupLeasesResponse(CleanupLeasesResult.builder().dryRun(request.body().dryRun()).leases(List.of()).build())
                );
            }

            if (request.body().dryRun()) {
                return CompletableFuture.completedFuture(buildDryRunResponse(matched));
            }

            return indexOps.removeAllAoscLeases(matched).thenApply(results -> {
                return new CleanupLeasesResponse(CleanupLeasesResult.builder().dryRun(false).leases(List.copyOf(results)).build());
            });
        });
    }

    private static CleanupLeasesResponse buildDryRunResponse(List<MatchedLease> matched) {
        List<CleanupLeasesResult.LeaseInfo> leases = matched.stream()
            .map(m -> IndexOperationUtils.toLeaseInfo(m, false, null))
            .collect(Collectors.toList());
        return new CleanupLeasesResponse(CleanupLeasesResult.builder().dryRun(true).leases(leases).build());
    }
}
