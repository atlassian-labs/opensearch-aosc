/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */
package com.atlassian.opensearch.aosc.service.coordinator;

import com.atlassian.opensearch.aosc.model.AoscMigrationsClusterState;
import com.atlassian.opensearch.aosc.model.MigrationMetadata;
import com.atlassian.opensearch.aosc.model.phase.CoordinatorPhase;
import com.atlassian.opensearch.aosc.utils.AoscLogger;

import org.opensearch.cluster.ClusterState;
import org.opensearch.cluster.ClusterStateUpdateTask;
import org.opensearch.cluster.service.ClusterService;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

/**
 * Submits {@link ClusterStateUpdateTask}s that patch {@link AoscMigrationsClusterState} for a single migration.
 *
 * <p>Each method returns a {@link CompletableFuture} that completes when the cluster state update is applied
 * (see {@link ClusterStateUpdateTask#clusterStateProcessed}) or fails via {@link ClusterStateUpdateTask#onFailure}.
 * The result may be {@code null} if the {@link AoscMigrationsClusterState} custom metadata is missing or there is no
 * entry for {@code migrationId} when the update is applied — callers must handle that case.</p>
 */
public class ClusterStateUpdateHelper {

    private final ClusterService clusterService;
    private final AoscLogger logger;

    public ClusterStateUpdateHelper(ClusterService clusterService, AoscLogger logger) {
        this.clusterService = clusterService;
        this.logger = Objects.requireNonNull(logger, "logger").forClass(ClusterStateUpdateHelper.class);
    }

    /**
     * Submit a phase update for the migration entry in cluster state.
     *
     * @return a future that completes with the entry after the update is applied, or {@code null} if there was
     *     nothing to update; completes exceptionally if the update task fails
     */
    public CompletableFuture<AoscMigrationsClusterState.Entry> submitPhaseUpdate(String migrationId, CoordinatorPhase newPhase) {
        return runGenericClusterStateUpdateTask(
            "aosc-phase-" + migrationId,
            new GenericAoscClusterStateUpdateTask(
                migrationId,
                entry -> entry.toBuilder().phase(newPhase).build(),
                "Failed to update coordinator phase to " + newPhase + " — cluster state and SM may be out of sync"
            )
        );
    }

    /** Submit a combined phase + migration-level metadata update in a single cluster state write. */
    public CompletableFuture<AoscMigrationsClusterState.Entry> submitPhaseAndMetaUpdate(
        String migrationId,
        CoordinatorPhase newPhase,
        MigrationMetadata meta
    ) {
        return runGenericClusterStateUpdateTask(
            "aosc-phase-" + migrationId,
            new GenericAoscClusterStateUpdateTask(
                migrationId,
                entry -> entry.toBuilder().phase(newPhase).meta(meta).build(),
                "Failed to update coordinator phase to " + newPhase + " — cluster state and SM may be out of sync"
            )
        );
    }

    /**
     * Merge {@code pendingUpdates} into the migration entry's shard map in a single cluster state update.
     * Does not clear {@code pendingUpdates}; the caller owns retry and clearing policy.
     *
     * @return a future that completes with the entry after the update is applied, or {@code null} if there was
     *     nothing to update; completes exceptionally if the update task fails
     */
    public CompletableFuture<AoscMigrationsClusterState.Entry> flushPendingUpdates(
        String migrationId,
        Map<Integer, AoscMigrationsClusterState.ShardMigrationClusterState> pendingUpdates
    ) {
        return runGenericClusterStateUpdateTask(
            "aosc-shard-updates-" + migrationId,
            new GenericAoscClusterStateUpdateTask(migrationId, entry -> {
                Map<Integer, AoscMigrationsClusterState.ShardMigrationClusterState> newShards = new HashMap<>(entry.shards());
                newShards.putAll(pendingUpdates);
                return entry.toBuilder().shards(newShards).build();
            }, "Failed to flush shard updates, will try again later")
        );
    }

    /** Submits a cluster state update task and wires its result to a new {@link CompletableFuture}. */
    private CompletableFuture<AoscMigrationsClusterState.Entry> runGenericClusterStateUpdateTask(
        String name,
        GenericAoscClusterStateUpdateTask task
    ) {
        CompletableFuture<AoscMigrationsClusterState.Entry> future = new CompletableFuture<>();
        clusterService.submitStateUpdateTask(name, task.withFuture(future));
        return future;
    }

    /**
     * Generic cluster state update task that applies a transformation function to a migration entry.
     * Completes a {@link CompletableFuture} via {@link #clusterStateProcessed} or {@link #onFailure}.
     */
    private class GenericAoscClusterStateUpdateTask extends ClusterStateUpdateTask {
        private final String migrationId;
        private final Function<AoscMigrationsClusterState.Entry, AoscMigrationsClusterState.Entry> transformer;
        private final String failureMessage;
        private CompletableFuture<AoscMigrationsClusterState.Entry> future;

        public GenericAoscClusterStateUpdateTask(
            String migrationId,
            Function<AoscMigrationsClusterState.Entry, AoscMigrationsClusterState.Entry> transformer,
            String failureMessage
        ) {
            this.migrationId = migrationId;
            this.transformer = transformer;
            this.failureMessage = failureMessage;
        }

        public GenericAoscClusterStateUpdateTask withFuture(CompletableFuture<AoscMigrationsClusterState.Entry> future) {
            this.future = future;
            return this;
        }

        @Override
        public ClusterState execute(ClusterState currentState) throws Exception {
            AoscMigrationsClusterState aoscState = currentState.custom(AoscMigrationsClusterState.TYPE);
            AoscMigrationsClusterState.Entry entry = aoscState != null ? aoscState.getEntry(migrationId) : null;
            if (entry == null) {
                return currentState;
            }

            entry = transformer.apply(entry);
            aoscState = aoscState.withEntry(entry);
            return ClusterState.builder(currentState).putCustom(AoscMigrationsClusterState.TYPE, aoscState).build();
        }

        @Override
        public void clusterStateProcessed(String source, ClusterState oldState, ClusterState newState) {
            AoscMigrationsClusterState aoscState = newState.custom(AoscMigrationsClusterState.TYPE);
            AoscMigrationsClusterState.Entry entry = aoscState != null ? aoscState.getEntry(migrationId) : null;
            future.complete(entry);
        }

        @Override
        public void onFailure(String source, Exception e) {
            logger.warn(failureMessage, e);
            future.completeExceptionally(e);
        }
    }

}
