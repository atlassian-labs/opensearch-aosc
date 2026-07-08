/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */
package com.atlassian.opensearch.aosc.model;

/**
 * Determines how replayed operations are routed to target shards during migration.
 *
 * <ul>
 *   <li><b>SAME_SHARD</b> (N→N): Source and target have the same shard count.
 *       Each source shard's ops are sent directly to the corresponding target shard
 *       via transport action. Full fidelity — no routing ambiguity.</li>
 *   <li><b>SPLIT_SHARD</b> (N→kN, k is a power of 2): INDEX ops routed to the
 *       correct target shard via {@code OperationRouting.generateShardId()}.
 *       DELETE ops fan out to all k candidate target shards. All via transport.
 *       Only power-of-2 split factors are safe because OpenSearch computes
 *       {@code routingNumShards = numShards * 2^numSplits} — doubling the shard
 *       count preserves {@code routingNumShards}, ensuring the contiguous shard
 *       mapping holds across source and target.</li>
 *   <li><b>BULK_API</b> (N→M, non-power-of-2 multiple or non-multiple): All ops
 *       go via Bulk API. INDEX preserves routing. DELETE has no routing (best
 *       effort, documented data loss for custom-routed documents). Requires
 *       explicit opt-in via {@code accept_data_loss_if_custom_routing_is_used}.</li>
 * </ul>
 */
public enum ShardRoutingMode {
    SAME_SHARD,
    SPLIT_SHARD,
    BULK_API
}
