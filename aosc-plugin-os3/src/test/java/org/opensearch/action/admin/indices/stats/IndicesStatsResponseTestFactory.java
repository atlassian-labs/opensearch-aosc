/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */
package org.opensearch.action.admin.indices.stats;

import java.util.Collections;

/**
 * Test-only factory placed in {@link org.opensearch.action.admin.indices.stats} so it
 * can call the package-private {@link IndicesStatsResponse} constructor without
 * reflection (which is forbidden by the project's forbidden-apis configuration).
 *
 * <p>Used by AOSC unit tests to fabricate stats responses with a controlled set of
 * shard stats for assertions about retention-lease cleanup behavior.
 */
public final class IndicesStatsResponseTestFactory {

    private IndicesStatsResponseTestFactory() {
        // utility — no instances
    }

    /**
     * Build an {@link IndicesStatsResponse} with the given per-shard stats. Successful
     * shard counts are set to {@code shards.length}; no failures are recorded.
     */
    public static IndicesStatsResponse forShards(ShardStats... shards) {
        return new IndicesStatsResponse(shards, shards.length, shards.length, 0, Collections.emptyList());
    }
}
