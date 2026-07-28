/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */
package com.atlassian.opensearch.aosc.utils;

import com.atlassian.opensearch.aosc.model.ShardRoutingMode;

import org.opensearch.cluster.metadata.IndexMetadata;
import org.opensearch.cluster.routing.OperationRouting;

/**
 * Computes synthetic routing values that deterministically hash to
 * specific target shards. Used for routing-aware DELETE operations
 * in SAME_SHARD and SPLIT_SHARD modes.
 *
 * <p>Each synthetic routing value, when passed through OpenSearch's routing
 * formula ({@link OperationRouting#generateShardId}), deterministically
 * maps to the corresponding shard number.</p>
 */
public final class SyntheticRoutingHelper {

    private SyntheticRoutingHelper() {} // utility class

    /**
     * Detect the routing mode based on source and target index metadata.
     *
     * <ul>
     *   <li>SAME_SHARD: same number of shards</li>
     *   <li>SPLIT_SHARD: target has more shards and the factor is a power of 2
     *       (OpenSearch's split constraint)</li>
     *   <li>BULK_API: all other cases (different shard counts, non-power-of-2 factor)</li>
     * </ul>
     */
    public static ShardRoutingMode detectRoutingMode(IndexMetadata sourceMetadata, IndexMetadata targetMetadata) {
        int sourceShards = sourceMetadata.getNumberOfShards();
        int targetShards = targetMetadata.getNumberOfShards();

        if (sourceShards == targetShards) {
            return ShardRoutingMode.SAME_SHARD;
        }

        if (targetShards > sourceShards && targetShards % sourceShards == 0) {
            int factor = targetShards / sourceShards;
            // OpenSearch requires split factor to be a power of 2
            if ((factor & (factor - 1)) == 0) {
                return ShardRoutingMode.SPLIT_SHARD;
            }
        }

        return ShardRoutingMode.BULK_API;
    }

    private static final int MAX_ROUNDS = 10;
    private static final int CANDIDATES_PER_ROUND_MULTIPLIER = 20;

    /**
     * Compute one synthetic routing value per target shard.
     * Delegates to {@link OperationRouting#generateShardId} to ensure
     * routing logic stays in sync with OpenSearch internals.
     *
     * <p>Uses multiple rounds with distinct prefixes to avoid coupon-collector
     * exhaustion at high shard counts (e.g. 1024 shards).</p>
     *
     * @param targetIndexMetadata the target index metadata (provides numShards, routingNumShards)
     * @return array of routing strings, one per shard
     */
    public static String[] computeSyntheticRoutings(IndexMetadata targetIndexMetadata) {
        int numShards = targetIndexMetadata.getNumberOfShards();
        String[] result = new String[numShards];
        int shardsFound = 0;
        int candidatesPerRound = Math.max(numShards * CANDIDATES_PER_ROUND_MULTIPLIER, (int) (numShards * Math.log(numShards) * 4));

        for (int round = 0; round < MAX_ROUNDS; round++) {
            for (int i = 0; i < candidatesPerRound; i++) {
                String candidate = "aosc-route-" + round + "-" + i;
                int computedShard = OperationRouting.generateShardId(targetIndexMetadata, null, candidate);
                if (result[computedShard] == null) {
                    result[computedShard] = candidate;
                    shardsFound++;
                }
                if (shardsFound == numShards) {
                    return result;
                }
            }
        }

        throw new IllegalStateException(
            "Failed to compute synthetic routings for all " + numShards + " shards (found " + shardsFound + ")"
        );
    }
}
