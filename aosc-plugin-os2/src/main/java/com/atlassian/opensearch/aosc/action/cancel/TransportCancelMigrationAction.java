/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */
package com.atlassian.opensearch.aosc.action.cancel;

import com.atlassian.opensearch.aosc.service.coordinator.AoscCoordinatorService;
import com.atlassian.opensearch.aosc.utils.AsyncUtils;

import org.opensearch.action.support.ActionFilters;
import org.opensearch.action.support.clustermanager.TransportClusterManagerNodeAction;
import org.opensearch.cluster.ClusterState;
import org.opensearch.cluster.block.ClusterBlockException;
import org.opensearch.cluster.block.ClusterBlockLevel;
import org.opensearch.cluster.metadata.IndexNameExpressionResolver;
import org.opensearch.cluster.service.ClusterService;
import org.opensearch.common.inject.Inject;
import org.opensearch.core.action.ActionListener;
import org.opensearch.core.common.io.stream.StreamInput;
import org.opensearch.threadpool.ThreadPool;
import org.opensearch.transport.TransportService;

import java.io.IOException;
import java.util.Objects;

/**
 * Transport handler for {@link CancelMigrationAction}.
 *
 * <p>Extends {@link TransportClusterManagerNodeAction} so that the request is automatically
 * forwarded to the active cluster manager node, where the coordinator transitions the
 * migration phase to {@code CANCELLING} via a cluster state update.
 *
 * <p>Cancellation is asynchronous — the response indicates whether the cancel was accepted
 * and what phase the migration is now in. The caller should poll {@code _status} to observe
 * the transition from {@code CANCELLING} to {@code CANCELLED}.
 */
public class TransportCancelMigrationAction extends TransportClusterManagerNodeAction<CancelMigrationRequest, CancelMigrationResponse> {

    private final AoscCoordinatorService coordinatorService;

    @Inject
    public TransportCancelMigrationAction(
        TransportService transportService,
        ClusterService clusterService,
        ThreadPool threadPool,
        ActionFilters actionFilters,
        IndexNameExpressionResolver indexNameExpressionResolver,
        AoscCoordinatorService coordinatorService
    ) {
        super(
            CancelMigrationAction.NAME,
            transportService,
            clusterService,
            threadPool,
            actionFilters,
            CancelMigrationRequest::new,
            indexNameExpressionResolver
        );
        this.coordinatorService = Objects.requireNonNull(coordinatorService, "coordinatorService");
    }

    @Override
    protected String executor() {
        return ThreadPool.Names.SAME;
    }

    @Override
    protected CancelMigrationResponse read(StreamInput in) throws IOException {
        return new CancelMigrationResponse(in);
    }

    @Override
    protected void clusterManagerOperation(
        CancelMigrationRequest request,
        ClusterState state,
        ActionListener<CancelMigrationResponse> listener
    ) {
        AsyncUtils.bridgeToListener(coordinatorService.cancelMigration(request.body().sourceIndex()), listener);
    }

    @Override
    protected ClusterBlockException checkBlock(CancelMigrationRequest request, ClusterState state) {
        return state.blocks().globalBlockedException(ClusterBlockLevel.METADATA_WRITE);
    }
}
