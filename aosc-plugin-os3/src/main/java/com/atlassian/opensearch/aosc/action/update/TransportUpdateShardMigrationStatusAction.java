/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */
package com.atlassian.opensearch.aosc.action.update;

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

/**
 * Transport handler for {@link UpdateShardMigrationStatusAction}.
 * Extends {@link TransportClusterManagerNodeAction} — requests are automatically
 * routed to the current ClusterManager node where the coordinator lives.
 *
 * Delegates to {@link AoscCoordinatorService#acceptShardUpdate} which batches
 * updates via the coordinator's accumulator before flushing to cluster state.
 */
public class TransportUpdateShardMigrationStatusAction extends TransportClusterManagerNodeAction<
    UpdateShardMigrationStatusRequest,
    UpdateShardMigrationStatusResponse> {

    private final AoscCoordinatorService coordinatorService;

    @Inject
    public TransportUpdateShardMigrationStatusAction(
        TransportService transportService,
        ClusterService clusterService,
        ThreadPool threadPool,
        ActionFilters actionFilters,
        IndexNameExpressionResolver indexNameExpressionResolver,
        AoscCoordinatorService coordinatorService
    ) {
        super(
            UpdateShardMigrationStatusAction.NAME,
            transportService,
            clusterService,
            threadPool,
            actionFilters,
            UpdateShardMigrationStatusRequest::new,
            indexNameExpressionResolver
        );
        this.coordinatorService = coordinatorService;
    }

    @Override
    protected String executor() {
        return ThreadPool.Names.SAME;
    }

    @Override
    protected UpdateShardMigrationStatusResponse read(StreamInput in) throws IOException {
        return new UpdateShardMigrationStatusResponse(in);
    }

    @Override
    protected void clusterManagerOperation(
        UpdateShardMigrationStatusRequest request,
        ClusterState state,
        ActionListener<UpdateShardMigrationStatusResponse> listener
    ) {
        AsyncUtils.bridgeToListener(coordinatorService.acceptShardUpdate(request), listener);
    }

    @Override
    protected ClusterBlockException checkBlock(UpdateShardMigrationStatusRequest request, ClusterState state) {
        return state.blocks().globalBlockedException(ClusterBlockLevel.METADATA_WRITE);
    }
}
