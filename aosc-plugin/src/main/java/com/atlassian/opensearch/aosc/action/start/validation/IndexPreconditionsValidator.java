/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */
package com.atlassian.opensearch.aosc.action.start.validation;

import com.atlassian.opensearch.aosc.model.DeleteRoutingStrategy;
import com.atlassian.opensearch.aosc.model.ShardRoutingMode;
import com.atlassian.opensearch.aosc.utils.SyntheticRoutingHelper;

import org.opensearch.cluster.ClusterState;
import org.opensearch.cluster.metadata.IndexAbstraction;
import org.opensearch.cluster.metadata.IndexMetadata;
import org.opensearch.cluster.metadata.MappingMetadata;
import org.opensearch.cluster.routing.IndexRoutingTable;

import java.util.ArrayList;
import java.util.List;

/** Checks index-level preconditions: primary health, alias conflicts, synthetic routing. */
public final class IndexPreconditionsValidator implements MigrationStartValidator {

    @Override
    public void validate(ValidationContext ctx) {
        List<String> errors = validatePreconditions(
            ctx.clusterState(),
            ctx.sourceMeta(),
            ctx.targetMeta(),
            ctx.request().getAlias(),
            ctx.deleteRoutingStrategy()
        );
        if (!errors.isEmpty()) {
            throw new IllegalStateException("Migration precondition check failed: " + String.join("; ", errors));
        }
    }

    public static List<String> validatePreconditions(
        ClusterState state,
        IndexMetadata sourceMeta,
        IndexMetadata targetMeta,
        String alias,
        DeleteRoutingStrategy deleteRoutingStrategy
    ) {
        List<String> errors = new ArrayList<>();

        IndexRoutingTable sourceRouting = state.routingTable().index(sourceMeta.getIndex());
        if (sourceRouting == null || !sourceRouting.allPrimaryShardsActive()) {
            int active = sourceRouting == null ? 0 : sourceRouting.primaryShardsActive();
            int total = sourceMeta.getNumberOfShards();
            errors.add(
                "source index ["
                    + sourceMeta.getIndex().getName()
                    + "] has unready primaries ("
                    + active
                    + "/"
                    + total
                    + " active); wait for GREEN/YELLOW health"
            );
        }

        IndexRoutingTable targetRouting = state.routingTable().index(targetMeta.getIndex());
        if (targetRouting == null || !targetRouting.allPrimaryShardsActive()) {
            int active = targetRouting == null ? 0 : targetRouting.primaryShardsActive();
            int total = targetMeta.getNumberOfShards();
            errors.add(
                "target index ["
                    + targetMeta.getIndex().getName()
                    + "] has unready primaries ("
                    + active
                    + "/"
                    + total
                    + " active); wait for all primaries to start"
            );
        }

        IndexAbstraction existing = state.metadata().getIndicesLookup().get(alias);
        if (existing != null && existing.getType() == IndexAbstraction.Type.ALIAS) {
            boolean pointsToTarget = existing.getIndices().stream().anyMatch(im -> im.getIndex().equals(targetMeta.getIndex()));
            if (pointsToTarget) {
                errors.add(
                    "alias ["
                        + alias
                        + "] already points to target index ["
                        + targetMeta.getIndex().getName()
                        + "]; remove it before starting a new migration"
                );
            } else {
                boolean pointsToSource = existing.getIndices().stream().anyMatch(im -> im.getIndex().equals(sourceMeta.getIndex()));
                if (!pointsToSource) {
                    errors.add("alias [" + alias + "] already exists on unrelated index(es); remove it first or choose a different alias");
                }
            }
        } else if (existing != null && existing.getType() == IndexAbstraction.Type.CONCRETE_INDEX) {
            errors.add("alias [" + alias + "] conflicts with an existing concrete index of the same name");
        }

        if (deleteRoutingStrategy == DeleteRoutingStrategy.SHARD_TOPOLOGY) {
            validateShardTopologyPreconditions(sourceMeta, targetMeta, errors);
        }

        return errors;
    }

    /** Before 3.9, deletes are routed by shard topology: reject settings where that can't reach the document. */
    private static void validateShardTopologyPreconditions(IndexMetadata sourceMeta, IndexMetadata targetMeta, List<String> errors) {
        if (sourceMeta.isRoutingPartitionedIndex() || targetMeta.isRoutingPartitionedIndex()) {
            addPartitionedError("source", sourceMeta, errors);
            addPartitionedError("target", targetMeta, errors);
            return; // the checks below assume routing alone picks the shard
        }
        if (SyntheticRoutingHelper.detectRoutingMode(sourceMeta, targetMeta) == ShardRoutingMode.BULK_API) {
            validateUnroutedDeletes(sourceMeta, targetMeta, errors);
        } else {
            validateSyntheticRouting(sourceMeta, targetMeta, errors);
        }
    }

    private static void addPartitionedError(String role, IndexMetadata indexMeta, List<String> errors) {
        if (indexMeta.isRoutingPartitionedIndex()) {
            errors.add(
                role
                    + " index ["
                    + indexMeta.getIndex().getName()
                    + "] is routing-partitioned (index.routing_partition_size="
                    + indexMeta.getRoutingPartitionSize()
                    + "); before OpenSearch 3.9 replayed deletes can't be routed to its documents. "
                    + "Run the migration with every node on OpenSearch 3.9 or later"
            );
        }
    }

    /** BULK_API sends deletes without routing, which a routing-required target rejects. */
    private static void validateUnroutedDeletes(IndexMetadata sourceMeta, IndexMetadata targetMeta, List<String> errors) {
        MappingMetadata mapping = targetMeta.mapping();
        if (mapping != null && mapping.routingRequired()) {
            errors.add(
                "target index ["
                    + targetMeta.getIndex().getName()
                    + "] requires routing (_routing.required=true), but before OpenSearch 3.9 a "
                    + sourceMeta.getNumberOfShards()
                    + "->"
                    + targetMeta.getNumberOfShards()
                    + " shard migration replays deletes without routing, which this index rejects. "
                    + "Use a shard count that is the source count multiplied or divided by a power of two, or run on OpenSearch 3.9 or later"
            );
        }
    }

    /** SAME/SPLIT/SHRINK address target shards by synthetic routing, which needs a shared hash space. */
    private static void validateSyntheticRouting(IndexMetadata sourceMeta, IndexMetadata targetMeta, List<String> errors) {
        int sourceShards = sourceMeta.getNumberOfShards();
        int targetShards = targetMeta.getNumberOfShards();
        int sourceRoutingShards = sourceMeta.getRoutingNumShards();
        int targetRoutingShards = targetMeta.getRoutingNumShards();
        // With one shard on either side, every delete goes to every candidate shard, so the hash space doesn't matter.
        if (Math.min(sourceShards, targetShards) > 1 && sourceRoutingShards != targetRoutingShards) {
            errors.add(
                "source index ["
                    + sourceMeta.getIndex().getName()
                    + "] and target index ["
                    + targetMeta.getIndex().getName()
                    + "] have different [index.number_of_routing_shards] (source="
                    + sourceRoutingShards
                    + ", target="
                    + targetRoutingShards
                    + ", shards="
                    + sourceShards
                    + "->"
                    + targetShards
                    + "); recreate the target index with index.number_of_routing_shards="
                    + sourceRoutingShards
                    + ", or run the migration with every node on OpenSearch 3.9 or later"
            );
        }
        try {
            SyntheticRoutingHelper.computeSyntheticRoutings(targetMeta);
        } catch (IllegalStateException e) {
            errors.add("synthetic routing computation failed for target index: " + e.getMessage());
        }
    }
}
