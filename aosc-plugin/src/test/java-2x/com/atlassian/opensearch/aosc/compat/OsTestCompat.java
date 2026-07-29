/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */
package com.atlassian.opensearch.aosc.compat;

import org.opensearch.action.admin.indices.stats.CommonStats;
import org.opensearch.action.admin.indices.stats.ShardStats;
import org.opensearch.cluster.routing.ShardRouting;
import org.opensearch.index.engine.CommitStats;
import org.opensearch.index.seqno.RetentionLeaseStats;
import org.opensearch.index.seqno.SeqNoStats;
import org.opensearch.index.shard.ShardPath;

/** OpenSearch 2.x test compat: ShardStats 6-param constructor. */
public final class OsTestCompat {

    private OsTestCompat() {}

    public static ShardStats createShardStats(
        ShardRouting routing,
        ShardPath path,
        CommonStats stats,
        CommitStats commit,
        SeqNoStats seqNo,
        RetentionLeaseStats leaseStats
    ) {
        return new ShardStats(routing, path, stats, commit, seqNo, leaseStats);
    }

    public static ShardStats createShardStatsNoLeases(ShardRouting routing, ShardPath path, CommonStats stats) {
        return new ShardStats(routing, path, stats, null, null, null);
    }
}
