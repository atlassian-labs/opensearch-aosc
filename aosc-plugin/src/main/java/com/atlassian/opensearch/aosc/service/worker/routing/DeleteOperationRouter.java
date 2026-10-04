/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */
package com.atlassian.opensearch.aosc.service.worker.routing;

import com.atlassian.opensearch.aosc.compat.TranslogDeleteOp;
import com.atlassian.opensearch.aosc.model.DeleteRoutingStrategy;
import com.atlassian.opensearch.aosc.model.DeletedDoc;
import com.atlassian.opensearch.aosc.model.ShardRoutingMode;
import com.atlassian.opensearch.aosc.utils.SyntheticRoutingHelper;

import org.opensearch.Version;
import org.opensearch.cluster.ClusterState;
import org.opensearch.cluster.metadata.IndexMetadata;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Routes replayed deletes for the migration's {@link DeleteRoutingStrategy}. */
public final class DeleteOperationRouter {

    private static final Version VERSION_3_9_0 = Version.fromString("3.9.0");

    private final DeleteRoutingStrategy strategy;
    private final ShardRoutingMode routingMode;
    private final int sourceShardCount;
    private final String[] syntheticRoutings;
    private final int sourceShardId;

    public DeleteOperationRouter(
        DeleteRoutingStrategy strategy,
        ShardRoutingMode routingMode,
        int sourceShardCount,
        String[] syntheticRoutings,
        int sourceShardId
    ) {
        this.strategy = Objects.requireNonNull(strategy, "strategy");
        this.routingMode = Objects.requireNonNull(routingMode, "routingMode");
        this.sourceShardCount = sourceShardCount;
        this.syntheticRoutings = syntheticRoutings;
        this.sourceShardId = sourceShardId;
    }

    public DeleteRoutingStrategy strategy() {
        return strategy;
    }

    public ShardRoutingMode routingMode() {
        return routingMode;
    }

    /** Router for one source shard; computes synthetic routings only when shard-topology deletes need them. */
    public static DeleteOperationRouter forShard(
        DeleteRoutingStrategy strategy,
        ShardRoutingMode routingMode,
        IndexMetadata sourceMeta,
        IndexMetadata targetMeta,
        int sourceShardId
    ) {
        boolean synthetic = strategy == DeleteRoutingStrategy.SHARD_TOPOLOGY && routingMode != ShardRoutingMode.BULK_API;
        String[] syntheticRoutings = synthetic ? SyntheticRoutingHelper.computeSyntheticRoutings(targetMeta) : null;
        return new DeleteOperationRouter(strategy, routingMode, sourceMeta.getNumberOfShards(), syntheticRoutings, sourceShardId);
    }

    /** Chosen once at {@code _start} and stored on the migration entry. */
    public static DeleteRoutingStrategy chooseStrategy(ClusterState state) {
        Objects.requireNonNull(state, "state");
        // Every node on 3.9+ includes this one, so this build has the java-39plus TranslogDeleteOp too.
        boolean allNodesOn39 = state.nodes().getSize() > 0 && state.nodes().getMinNodeVersion().onOrAfter(VERSION_3_9_0);
        return allNodesOn39 ? DeleteRoutingStrategy.TRANSLOG_ROUTING : DeleteRoutingStrategy.SHARD_TOPOLOGY;
    }

    /**
     * {@code TRANSLOG_ROUTING}: the delete's own routing. {@code SHARD_TOPOLOGY}: one synthetic routing
     * per target shard that can hold the source shard's documents, or none for {@code BULK_API}.
     */
    public List<DeletedDoc> route(TranslogDeleteOp delete) {
        if (strategy == DeleteRoutingStrategy.TRANSLOG_ROUTING) {
            return List.of(new DeletedDoc(delete.id(), delete.routing(), sourceShardId));
        }

        switch (routingMode) {
            case SAME_SHARD:
                String routing = syntheticRoutings != null ? syntheticRoutings[sourceShardId] : null;
                return List.of(new DeletedDoc(delete.id(), routing, sourceShardId));
            case SPLIT_SHARD:
                return routeSplit(delete);
            case SHRINK_SHARD:
                return routeShrink(delete);
            case BULK_API:
            default:
                return List.of(new DeletedDoc(delete.id(), null, sourceShardId));
        }
    }

    /** Target shard {@code t} owns exactly source shards {@code t·k .. t·k+k-1}, so the delete goes to {@code s / k}. */
    private List<DeletedDoc> routeShrink(TranslogDeleteOp delete) {
        if (syntheticRoutings == null || syntheticRoutings.length == 0) {
            return List.of(new DeletedDoc(delete.id(), null, sourceShardId));
        }
        int sourceShardsPerTargetShard = sourceShardCount / syntheticRoutings.length;
        return List.of(new DeletedDoc(delete.id(), syntheticRoutings[sourceShardId / sourceShardsPerTargetShard], sourceShardId));
    }

    private List<DeletedDoc> routeSplit(TranslogDeleteOp delete) {
        if (syntheticRoutings == null || sourceShardCount <= 0) {
            return List.of(new DeletedDoc(delete.id(), null, sourceShardId));
        }
        int targetShardsPerSourceShard = syntheticRoutings.length / sourceShardCount;
        List<DeletedDoc> candidates = new ArrayList<>(targetShardsPerSourceShard);
        for (int i = 0; i < targetShardsPerSourceShard; i++) {
            int targetShard = sourceShardId * targetShardsPerSourceShard + i;
            candidates.add(new DeletedDoc(delete.id(), syntheticRoutings[targetShard], sourceShardId));
        }
        return candidates;
    }
}
