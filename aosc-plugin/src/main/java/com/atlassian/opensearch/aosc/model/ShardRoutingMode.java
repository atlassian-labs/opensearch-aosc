/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */
package com.atlassian.opensearch.aosc.model;

/**
 * Source-to-target shard topology. With {@link DeleteRoutingStrategy#SHARD_TOPOLOGY} (before 3.9),
 * it decides where replayed deletes go:
 *
 * <ul>
 *   <li><b>SAME_SHARD</b> (N→N): the corresponding target shard.</li>
 *   <li><b>SPLIT_SHARD</b> (N→kN, k a power of 2): all k target shards of the source shard.</li>
 *   <li><b>SHRINK_SHARD</b> (kN→N, k a power of 2): the one target shard {@code s / k}.</li>
 *   <li><b>BULK_API</b> (anything else): unrouted; requires data-loss consent.</li>
 * </ul>
 */
public enum ShardRoutingMode {
    SAME_SHARD,
    SPLIT_SHARD,
    SHRINK_SHARD,
    BULK_API
}
