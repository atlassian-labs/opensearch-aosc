/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */
package com.atlassian.opensearch.aosc.action.start.validation;

import com.atlassian.opensearch.aosc.utils.SyntheticRoutingHelper;

import org.opensearch.cluster.ClusterState;
import org.opensearch.cluster.metadata.IndexAbstraction;
import org.opensearch.cluster.metadata.IndexMetadata;
import org.opensearch.cluster.routing.IndexRoutingTable;

import java.util.ArrayList;
import java.util.List;

/** Checks index-level preconditions: primary health, alias conflicts, synthetic routing. */
public final class IndexPreconditionsValidator implements MigrationStartValidator {

    @Override
    public void validate(ValidationContext ctx) {
        List<String> errors = validatePreconditions(ctx.clusterState(), ctx.sourceMeta(), ctx.targetMeta(), ctx.request().getAlias());
        if (!errors.isEmpty()) {
            throw new IllegalStateException("Migration precondition check failed: " + String.join("; ", errors));
        }
    }

    public static List<String> validatePreconditions(ClusterState state, IndexMetadata sourceMeta, IndexMetadata targetMeta, String alias) {
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

        validateSplitShardRoutingPreconditions(sourceMeta, targetMeta, errors);

        try {
            SyntheticRoutingHelper.computeSyntheticRoutings(targetMeta);
        } catch (IllegalStateException e) {
            errors.add("synthetic routing computation failed for target index: " + e.getMessage());
        }

        return errors;
    }

    private static void validateSplitShardRoutingPreconditions(IndexMetadata sourceMeta, IndexMetadata targetMeta, List<String> errors) {
        int sourceShards = sourceMeta.getNumberOfShards();
        int targetShards = targetMeta.getNumberOfShards();
        if (sourceShards == 1 || targetShards <= sourceShards || targetShards % sourceShards != 0) {
            return;
        }

        int factor = targetShards / sourceShards;
        if (!isPowerOfTwo(factor)) {
            return;
        }

        int sourceRoutingShards = sourceMeta.getRoutingNumShards();
        int targetRoutingShards = targetMeta.getRoutingNumShards();
        if (sourceRoutingShards != targetRoutingShards) {
            errors.add(
                "source index ["
                    + sourceMeta.getIndex().getName()
                    + "] and target index ["
                    + targetMeta.getIndex().getName()
                    + "] have incompatible [index.number_of_routing_shards] for split-shard replay (source="
                    + sourceRoutingShards
                    + ", target="
                    + targetRoutingShards
                    + ", shards="
                    + sourceShards
                    + "->"
                    + targetShards
                    + "); recreate the target index with index.number_of_routing_shards="
                    + sourceRoutingShards
            );
        }
    }

    private static boolean isPowerOfTwo(int value) {
        return value > 0 && (value & (value - 1)) == 0;
    }
}
