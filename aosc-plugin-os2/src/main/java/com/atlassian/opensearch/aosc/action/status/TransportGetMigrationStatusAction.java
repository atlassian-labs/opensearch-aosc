/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */
package com.atlassian.opensearch.aosc.action.status;

import com.atlassian.opensearch.aosc.model.MigrationDocument;
import com.atlassian.opensearch.aosc.service.coordinator.AoscCoordinatorService;
import com.atlassian.opensearch.aosc.service.coordinator.MigrationDocumentService;
import com.atlassian.opensearch.aosc.utils.AoscLogger;

import org.opensearch.ResourceNotFoundException;
import org.opensearch.action.support.ActionFilters;
import org.opensearch.action.support.clustermanager.TransportClusterManagerNodeAction;
import org.opensearch.cluster.ClusterState;
import org.opensearch.cluster.block.ClusterBlockException;
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
 * Transport handler for {@link GetMigrationStatusAction}.
 *
 * <p>Extends {@link TransportClusterManagerNodeAction} — requests are routed to the
 * cluster manager node where the coordinator cache lives.</p>
 *
 * <p>Read path:</p>
 * <ol>
 *   <li><b>Active migration</b>: reads from coordinator's in-memory cache
 *       ({@code MigrationCoordinator.shardProgressCache()}) — full PhaseMetrics available.</li>
 *   <li><b>Terminal/old migration</b>: reads from Tier-1 ({@code .aosc-migrations} index)
 *       — full PhaseMetrics written at migration terminal by {@code persistAllShardProgress()}.</li>
 *   <li><b>Not found</b>: returns {@code found: false}.</li>
 * </ol>
 *
 * <p>No cluster state overlay — coordinator cache is the single source of truth for active
 * migrations, Tier-1 is the single source of truth for terminal migrations.</p>
 */
public class TransportGetMigrationStatusAction extends TransportClusterManagerNodeAction<
    GetMigrationStatusRequest,
    GetMigrationStatusResponse> {

    private final AoscLogger logger;

    private final AoscCoordinatorService coordinatorService;
    private final MigrationDocumentService migrationDocumentService;

    @Inject
    public TransportGetMigrationStatusAction(
        TransportService transportService,
        ClusterService clusterService,
        ThreadPool threadPool,
        ActionFilters actionFilters,
        IndexNameExpressionResolver indexNameExpressionResolver,
        AoscCoordinatorService coordinatorService,
        MigrationDocumentService migrationDocumentService
    ) {
        super(
            GetMigrationStatusAction.NAME,
            transportService,
            clusterService,
            threadPool,
            actionFilters,
            GetMigrationStatusRequest::new,
            indexNameExpressionResolver
        );
        this.coordinatorService = Objects.requireNonNull(coordinatorService, "coordinatorService");
        this.migrationDocumentService = Objects.requireNonNull(migrationDocumentService, "migrationDocumentService");
        this.logger = AoscLogger.create(TransportGetMigrationStatusAction.class);
    }

    @Override
    protected String executor() {
        return ThreadPool.Names.SAME;
    }

    @Override
    protected GetMigrationStatusResponse read(StreamInput in) throws IOException {
        return new GetMigrationStatusResponse(in);
    }

    @Override
    protected void clusterManagerOperation(
        GetMigrationStatusRequest request,
        ClusterState state,
        ActionListener<GetMigrationStatusResponse> listener
    ) {
        String sourceIndex = request.body().sourceIndex();

        // 1. Try coordinator cache (active migration)
        GetMigrationStatusResponse cachedResponse = coordinatorService.getActiveStatusFromCache(sourceIndex);
        if (cachedResponse != null) {
            logger.debug("Status for [{}]: served from coordinator cache (phase={})", sourceIndex, cachedResponse.body().phase());
            listener.onResponse(cachedResponse);
            return;
        }

        // 2. Fall back to Tier-1 (terminal/old migration), then cluster state as last resort
        logger.debug("Status for [{}]: cache miss, falling back to Tier-1", sourceIndex);
        respondFromTier1(sourceIndex, state, listener);
    }

    @Override
    protected ClusterBlockException checkBlock(GetMigrationStatusRequest request, ClusterState state) {
        // Status is a read-only operation — don't block on metadata writes.
        return null;
    }

    /**
     * Build response from Tier-1 (.aosc-migrations index).
     * Used for terminal/old migrations where the coordinator is no longer active.
     */
    private void respondFromTier1(String sourceIndex, ClusterState state, ActionListener<GetMigrationStatusResponse> listener) {
        migrationDocumentService.getMigrationBySourceIndex(sourceIndex).whenComplete((doc, ex) -> {
            if (ex != null) {
                logger.debug("Tier-1 lookup failed for source index [{}]: {}", sourceIndex, ex.getMessage());
                listener.onFailure(new ResourceNotFoundException("No migration found for index [" + sourceIndex + "]"));
                return;
            }
            if (doc == null) {
                listener.onFailure(new ResourceNotFoundException("No migration found for index [" + sourceIndex + "]"));
                return;
            }
            respondWithDoc(sourceIndex, doc, listener);
        });
    }

    /**
     * Build response from Tier-1 migration document with embedded shard progress.
     */
    private void respondWithDoc(String sourceIndex, MigrationDocument doc, ActionListener<GetMigrationStatusResponse> listener) {
        listener.onResponse(new GetMigrationStatusResponse(doc));
    }
}
