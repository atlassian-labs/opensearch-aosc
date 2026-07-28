/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */
package com.atlassian.opensearch.aosc.utils;

import com.atlassian.opensearch.aosc.action.cleanup.CleanupLeasesResult;

import org.opensearch.action.admin.indices.stats.CommonStats;
import org.opensearch.action.admin.indices.stats.IndicesStatsResponse;
import org.opensearch.action.admin.indices.stats.IndicesStatsResponseTestFactory;
import org.opensearch.action.admin.indices.stats.ShardStats;
import org.opensearch.cluster.routing.ShardRouting;
import org.opensearch.cluster.routing.ShardRoutingState;
import org.opensearch.cluster.routing.TestShardRouting;
import org.opensearch.common.UUIDs;
import org.opensearch.common.io.PathUtils;
import org.opensearch.core.index.Index;
import org.opensearch.core.index.shard.ShardId;
import org.opensearch.index.seqno.RetentionLease;
import org.opensearch.index.seqno.RetentionLeaseStats;
import org.opensearch.index.seqno.RetentionLeases;
import org.opensearch.index.shard.ShardPath;
import org.opensearch.test.OpenSearchTestCase;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

/**
 * Unit tests for the lease-related static helpers in {@link IndexOperationUtils}.
 */
public class IndexOperationUtilsLeaseTests extends OpenSearchTestCase {

    // ---- collectAoscLeases ----

    public void testCollectFiltersNonAoscLeases() {
        ShardId shardId = newShardId("idx", 0);
        IndicesStatsResponse stats = statsResponseFor(
            shardWithLeases(shardId, lease("aosc-migration-m1", 10), lease("peer_recovery/0", 20), lease("ccr-follower", 30))
        );

        List<IndexOperationUtils.MatchedLease> result = IndexOperationUtils.collectAoscLeases(stats);

        assertEquals(1, result.size());
        assertEquals("aosc-migration-m1", result.get(0).lease().id());
    }

    public void testCollectDedupsPrimaryAndReplicaCopies() {
        ShardId shardId = newShardId("idx", 0);
        IndicesStatsResponse stats = statsResponseFor(
            shardWithLeases(shardId, lease("aosc-migration-m1", 10)),
            shardWithLeases(shardId, lease("aosc-migration-m1", 10))
        );

        List<IndexOperationUtils.MatchedLease> result = IndexOperationUtils.collectAoscLeases(stats);

        assertEquals("duplicate shard copies should be deduped", 1, result.size());
    }

    public void testCollectKeepsDistinctLeasesOnSameShard() {
        ShardId shardId = newShardId("idx", 0);
        IndicesStatsResponse stats = statsResponseFor(
            shardWithLeases(shardId, lease("aosc-migration-m1", 10), lease("aosc-migration-m2", 20))
        );

        List<IndexOperationUtils.MatchedLease> result = IndexOperationUtils.collectAoscLeases(stats);

        assertEquals(2, result.size());
    }

    public void testCollectKeepsSameLeaseIdOnDifferentShards() {
        ShardId shard0 = newShardId("idx", 0);
        ShardId shard1 = newShardId("idx", 1);
        IndicesStatsResponse stats = statsResponseFor(
            shardWithLeases(shard0, lease("aosc-migration-m1", 10)),
            shardWithLeases(shard1, lease("aosc-migration-m1", 10))
        );

        List<IndexOperationUtils.MatchedLease> result = IndexOperationUtils.collectAoscLeases(stats);

        assertEquals("same lease ID on different shards are distinct", 2, result.size());
    }

    public void testCollectReturnsEmptyForNoShards() {
        IndicesStatsResponse stats = statsResponseFor();

        List<IndexOperationUtils.MatchedLease> result = IndexOperationUtils.collectAoscLeases(stats);

        assertTrue(result.isEmpty());
    }

    public void testCollectSkipsNullRetentionLeaseStats() {
        ShardId shardId = newShardId("idx", 0);
        ShardStats withNullStats = shardWithNullLeaseStats(shardId);
        IndicesStatsResponse stats = statsResponseFor(withNullStats);

        List<IndexOperationUtils.MatchedLease> result = IndexOperationUtils.collectAoscLeases(stats);

        assertTrue(result.isEmpty());
    }

    public void testCollectAcrossMultipleIndices() {
        Index idx1 = new Index("index-a", UUIDs.randomBase64UUID());
        Index idx2 = new Index("index-b", UUIDs.randomBase64UUID());
        ShardId s1 = new ShardId(idx1, 0);
        ShardId s2 = new ShardId(idx2, 0);
        IndicesStatsResponse stats = statsResponseFor(
            shardWithLeases(s1, lease("aosc-migration-m1", 10)),
            shardWithLeases(s2, lease("aosc-migration-m2", 20))
        );

        List<IndexOperationUtils.MatchedLease> result = IndexOperationUtils.collectAoscLeases(stats);

        assertEquals(2, result.size());
    }

    // ---- toLeaseInfo ----

    public void testToLeaseInfoMapsAllFields() {
        ShardId shardId = new ShardId(new Index("my-index", "uuid"), 3);
        RetentionLease lease = new RetentionLease("aosc-migration-m1", 42L, 0L, "aosc-migration");
        IndexOperationUtils.MatchedLease matched = new IndexOperationUtils.MatchedLease(shardId, lease);

        CleanupLeasesResult.LeaseInfo info = IndexOperationUtils.toLeaseInfo(matched, true, null);

        assertEquals("my-index", info.index());
        assertEquals(3, info.shard());
        assertEquals("aosc-migration-m1", info.leaseId());
        assertEquals("aosc-migration", info.source());
        assertEquals(42L, info.retainingSeqNo());
        assertTrue(info.released());
        assertNull(info.error());
    }

    public void testToLeaseInfoWithError() {
        ShardId shardId = newShardId("idx", 0);
        RetentionLease lease = new RetentionLease("aosc-migration-m1", 10L, 0L, "aosc-migration");
        IndexOperationUtils.MatchedLease matched = new IndexOperationUtils.MatchedLease(shardId, lease);

        CleanupLeasesResult.LeaseInfo info = IndexOperationUtils.toLeaseInfo(matched, false, "something broke");

        assertFalse(info.released());
        assertEquals("something broke", info.error());
    }

    // ---- formatErrorMessage ----

    public void testFormatErrorMessageIncludesTypeAndMessage() {
        String result = IndexOperationUtils.formatErrorMessage(new IllegalStateException("bad state"));
        assertEquals("IllegalStateException: bad state", result);
    }

    public void testFormatErrorMessageWithNullMessage() {
        String result = IndexOperationUtils.formatErrorMessage(new NullPointerException());
        assertEquals("NullPointerException", result);
    }

    public void testFormatErrorMessageWithWrappedException() {
        Exception root = new IllegalArgumentException("root cause");
        Exception wrapper = new RuntimeException("wrapper", root);
        String result = IndexOperationUtils.formatErrorMessage(wrapper);
        // Result should include either the wrapper or root message — just verify it's non-empty and well-formed
        assertNotNull(result);
        assertFalse(result.isEmpty());
        assertTrue("Should contain a recognizable type", result.contains("Exception"));
    }

    // ---- Helpers ----

    private static ShardId newShardId(String indexName, int shard) {
        return new ShardId(new Index(indexName, UUIDs.randomBase64UUID()), shard);
    }

    private static RetentionLease lease(String id, long seqNo) {
        return new RetentionLease(id, seqNo, 0L, "aosc-migration");
    }

    private static ShardStats shardWithLeases(ShardId shardId, RetentionLease... leases) {
        ShardRouting routing = TestShardRouting.newShardRouting(shardId, "node-1", true, ShardRoutingState.STARTED);
        Path tmp = PathUtils.get(
            System.getProperty("java.io.tmpdir"),
            "aosc-test",
            shardId.getIndex().getUUID(),
            String.valueOf(shardId.id())
        );
        ShardPath shardPath = new ShardPath(false, tmp, tmp, shardId);
        RetentionLeases retentionLeases = new RetentionLeases(1L, 1L, Arrays.asList(leases));
        return new ShardStats(routing, shardPath, new CommonStats(), null, null, new RetentionLeaseStats(retentionLeases));
    }

    private static ShardStats shardWithNullLeaseStats(ShardId shardId) {
        ShardRouting routing = TestShardRouting.newShardRouting(shardId, "node-1", true, ShardRoutingState.STARTED);
        Path tmp = PathUtils.get(
            System.getProperty("java.io.tmpdir"),
            "aosc-test",
            shardId.getIndex().getUUID(),
            String.valueOf(shardId.id())
        );
        ShardPath shardPath = new ShardPath(false, tmp, tmp, shardId);
        return new ShardStats(routing, shardPath, new CommonStats(), null, null, null);
    }

    private static IndicesStatsResponse statsResponseFor(ShardStats... shards) {
        return IndicesStatsResponseTestFactory.forShards(shards);
    }
}
