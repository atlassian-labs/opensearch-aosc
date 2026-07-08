/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */
package com.atlassian.opensearch.aosc.action.clear;

import com.atlassian.opensearch.aosc.model.AoscMigrationsClusterState;
import com.atlassian.opensearch.aosc.service.coordinator.AoscCoordinatorService;
import com.atlassian.opensearch.aosc.utils.AoscLogger;

import org.opensearch.action.support.ActionFilters;
import org.opensearch.action.support.clustermanager.TransportClusterManagerNodeAction;
import org.opensearch.cluster.ClusterState;
import org.opensearch.cluster.ClusterStateUpdateTask;
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
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

public class TransportClearClusterStateAction extends TransportClusterManagerNodeAction<
    ClearClusterStateRequest,
    ClearClusterStateResponse> {

    private final AoscLogger logger;

    private final AoscCoordinatorService coordinatorService;

    @Inject
    public TransportClearClusterStateAction(
        TransportService transportService,
        ClusterService clusterService,
        ThreadPool threadPool,
        ActionFilters actionFilters,
        IndexNameExpressionResolver indexNameExpressionResolver,
        AoscCoordinatorService coordinatorService
    ) {
        super(
            ClearClusterStateAction.NAME,
            transportService,
            clusterService,
            threadPool,
            actionFilters,
            ClearClusterStateRequest::new,
            indexNameExpressionResolver
        );
        this.coordinatorService = Objects.requireNonNull(coordinatorService, "coordinatorService");
        this.logger = AoscLogger.create(TransportClearClusterStateAction.class);
    }

    @Override
    protected String executor() {
        return ThreadPool.Names.SAME;
    }

    @Override
    protected ClearClusterStateResponse read(StreamInput in) throws IOException {
        return new ClearClusterStateResponse(in);
    }

    @Override
    protected ClusterBlockException checkBlock(ClearClusterStateRequest request, ClusterState state) {
        return state.blocks().globalBlockedException(ClusterBlockLevel.METADATA_WRITE);
    }

    @Override
    protected void clusterManagerOperation(
        ClearClusterStateRequest request,
        ClusterState state,
        ActionListener<ClearClusterStateResponse> listener
    ) {
        ClearClusterStateBody body = request.body();
        AoscMigrationsClusterState existing = state.custom(AoscMigrationsClusterState.TYPE);
        Map<String, AoscMigrationsClusterState.Entry> csEntries = existing != null ? existing.entries() : Map.of();
        Set<String> activeMigrationIds = coordinatorService.getActiveMigrationIds();

        // Build the union of all known migration IDs
        Set<String> allIds = new LinkedHashSet<>();
        allIds.addAll(csEntries.keySet());
        allIds.addAll(activeMigrationIds);
        if (body.migrationId() != null) {
            allIds.retainAll(Set.of(body.migrationId()));
        }

        if (allIds.isEmpty()) {
            logger.info("No AOSC migration entries found — nothing to clear");
            listener.onResponse(
                new ClearClusterStateResponse(
                    ClearClusterStateResult.builder().dryRun(body.dryRun()).tryClose(body.tryClose()).migrations(Map.of()).build()
                )
            );
            return;
        }

        // Dry run — build result without modifying anything
        if (body.dryRun()) {
            listener.onResponse(buildDryRunResult(body, allIds, csEntries));
            return;
        }

        clusterService.submitStateUpdateTask("aosc-clear-cluster-state", new ClusterStateUpdateTask() {
            @Override
            public ClusterState execute(ClusterState clusterState) {
                AoscMigrationsClusterState aoscState = clusterState.custom(AoscMigrationsClusterState.TYPE);
                if (aoscState == null) {
                    return clusterState;
                }

                for (String id : allIds) {
                    aoscState = aoscState.withoutEntry(id);
                }

                return ClusterState.builder(clusterState).putCustom(AoscMigrationsClusterState.TYPE, aoscState).build();
            }

            @Override
            public void onFailure(String source, Exception e) {
                logger.error("Failed to clear AOSC cluster state", e);
                listener.onFailure(e);
            }

            @Override
            public void clusterStateProcessed(String source, ClusterState oldState, ClusterState newState) {
                logger.info("AOSC cluster state cleared successfully");
                ClearClusterStateResponse response = buildResponseAfterCsUpdate(body, allIds, csEntries);
                listener.onResponse(response);
            }
        });
    }

    private ClearClusterStateResponse buildResponseAfterCsUpdate(
        ClearClusterStateBody body,
        Set<String> allIds,
        Map<String, AoscMigrationsClusterState.Entry> csEntries
    ) {
        Set<String> closedMigrations;
        if (body.tryClose()) {
            closedMigrations = coordinatorService.removeAndClose(allIds);
        } else {
            closedMigrations = new HashSet<>();
        }

        var migrationsResponse = allIds.stream().collect(Collectors.toMap(migrationId -> migrationId, migrationId -> {
            AoscMigrationsClusterState.Entry entry = csEntries.get(migrationId);
            return ClearClusterStateResult.MigrationAction.builder()
                .sourceIndex(entry != null ? entry.sourceIndex() : null)
                .phase(entry != null ? entry.phase().name() : null)
                .clusterState(entry != null ? "cleared" : "not_found")
                .activeMigrations(closedMigrations.contains(migrationId) ? "cleared" : "not_found")
                .closed(closedMigrations.contains(migrationId))
                .entry(body.detailed() ? entry : null)
                .build();
        }));

        return new ClearClusterStateResponse(
            ClearClusterStateResult.builder().dryRun(false).tryClose(body.tryClose()).migrations(migrationsResponse).build()
        );
    }

    private ClearClusterStateResponse buildDryRunResult(
        ClearClusterStateBody body,
        Set<String> allIds,
        Map<String, AoscMigrationsClusterState.Entry> csEntries
    ) {
        var activeMigrationIds = coordinatorService.getActiveMigrationIds();
        var migrationsResponse = allIds.stream().collect(Collectors.toMap(migrationId -> migrationId, migrationId -> {
            AoscMigrationsClusterState.Entry entry = csEntries.get(migrationId);
            return ClearClusterStateResult.MigrationAction.builder()
                .sourceIndex(entry != null ? entry.sourceIndex() : null)
                .phase(entry != null ? entry.phase().name() : null)
                .clusterState(entry != null ? "would_clear" : "not_found")
                .activeMigrations(activeMigrationIds.contains(migrationId) ? "would_clear" : "not_found")
                .closed(false)
                .entry(body.detailed() ? entry : null)
                .build();
        }));

        return new ClearClusterStateResponse(
            ClearClusterStateResult.builder().dryRun(true).tryClose(body.tryClose()).migrations(migrationsResponse).build()
        );
    }
}
