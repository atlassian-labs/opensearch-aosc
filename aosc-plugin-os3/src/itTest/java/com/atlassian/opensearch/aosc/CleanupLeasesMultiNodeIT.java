/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */
package com.atlassian.opensearch.aosc;

import com.atlassian.opensearch.aosc.action.cleanup.CleanupLeasesAction;
import com.atlassian.opensearch.aosc.action.cleanup.CleanupLeasesBody;
import com.atlassian.opensearch.aosc.action.cleanup.CleanupLeasesRequest;
import com.atlassian.opensearch.aosc.action.cleanup.CleanupLeasesResponse;
import com.atlassian.opensearch.aosc.service.worker.RetentionLeaseManager;

import org.opensearch.action.support.PlainActionFuture;
import org.opensearch.action.support.replication.ReplicationResponse;
import org.opensearch.common.settings.Settings;
import org.opensearch.core.action.ActionListener;
import org.opensearch.core.index.shard.ShardId;
import org.opensearch.index.IndexService;
import org.opensearch.index.seqno.RetentionLease;
import org.opensearch.index.shard.IndexShard;
import org.opensearch.indices.IndicesService;
import org.opensearch.test.OpenSearchIntegTestCase.ClusterScope;
import org.opensearch.test.OpenSearchIntegTestCase.Scope;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Multi-node integration test that proves the {@code DedupKey} pipeline correctly
 * collapses the same retention lease when reported by both primary and replica
 * shard copies via {@link org.opensearch.action.admin.indices.stats.IndicesStatsAction}.
 *
 * <p>This is a separate class because {@link ClusterScope#numDataNodes()} is a
 * class-level annotation — we can't have one method run on 1 node and another on 2.
 * Single-node coverage lives in {@link CleanupLeasesIT}; multi-node lives here.
 *
 * <p>The lease is planted on the primary; OpenSearch syncs the lease to the replica
 * automatically via {@code RetentionLeaseSyncer}. We then poll until the replica
 * also reports the lease (so {@code IndicesStatsAction} sees both copies), and
 * finally invoke cleanup. The action must:
 * <ol>
 *   <li>Report the lease exactly once in the response (not twice — that's what
 *       the dedup guarantees), with {@code released=true} and no failures.</li>
 *   <li>Physically remove the lease so the next stats call returns zero AOSC leases.</li>
 * </ol>
 */
@ClusterScope(scope = Scope.TEST, numDataNodes = 2, numClientNodes = 0)
public class CleanupLeasesMultiNodeIT extends AoscIntegTestBase {

    private static final String AOSC_PREFIX = RetentionLeaseManager.LEASE_ID_PREFIX;
    private static final String AOSC_SOURCE = RetentionLeaseManager.LEASE_SOURCE;

    public void testReplicaCopiesAreDedupedAndRemovedOnce() throws Exception {
        String index = indexName("clp-replica-dedup");
        client().admin()
            .indices()
            .prepareCreate(index)
            .setSettings(Settings.builder().put("index.number_of_shards", 1).put("index.number_of_replicas", 1))
            .get();
        ensureGreen(index);

        IndexShard primary = locatePrimary(index, 0);
        ShardId shardId = primary.shardId();
        String leaseId = AOSC_PREFIX + "mig-replica-dedup-0";

        // Plant the lease on the primary; the lease will be synced to the replica
        // by RetentionLeaseSyncer as part of the addRetentionLease pipeline.
        PlainActionFuture<ReplicationResponse> plant = PlainActionFuture.newFuture();
        primary.addRetentionLease(leaseId, /* retainingSeqNo */ 0L, AOSC_SOURCE, plant);
        plant.actionGet();

        // Wait until BOTH shard copies (primary + replica) report the lease in
        // their RetentionLeases collection. This is the situation the action's
        // DedupKey logic was designed for: stats responses will list the same
        // (shardId, leaseId) twice, once per copy.
        assertBusy(() -> {
            int copiesReportingLease = 0;
            for (IndexShard copy : allLocalCopies(shardId)) {
                if (containsLease(copy, leaseId)) {
                    copiesReportingLease++;
                }
            }
            assertEquals("expected lease to be visible on both primary and replica copies", 2, copiesReportingLease);
        });

        // Cleanup the lease cluster-wide. The response MUST contain exactly one
        // entry (dedup) even though stats reports the lease from two copies.
        PlainActionFuture<CleanupLeasesResponse> future = PlainActionFuture.newFuture();
        client().execute(
            CleanupLeasesAction.INSTANCE,
            new CleanupLeasesRequest(new CleanupLeasesBody(new String[0], /* dryRun */ false)),
            ActionListener.wrap(future::onResponse, future::onFailure)
        );
        CleanupLeasesResponse response = future.actionGet();

        assertEquals("dedup MUST collapse primary+replica reports of the same lease", 1, response.body().leases().size());
        assertEquals(1, response.body().releasedCount());
        assertEquals(0, response.body().failedCount());
        assertEquals(leaseId, response.body().leases().get(0).leaseId());
        assertTrue(response.body().leases().get(0).released());

        // Physical-removal check: the lease must be gone from every copy that
        // hosts the shard, after the lease sync propagates the removal.
        assertBusy(() -> {
            for (IndexShard copy : allLocalCopies(shardId)) {
                assertFalse(
                    "lease ["
                        + leaseId
                        + "] still present on a shard copy after cleanup; copies still holding it: "
                        + currentLeaseIds(copy),
                    containsLease(copy, leaseId)
                );
            }
        });
    }

    // ---- helpers (mirror CleanupLeasesIT but operate across all data nodes) ----

    private IndexShard locatePrimary(String index, int shardNum) {
        for (String nodeName : internalCluster().getNodeNames()) {
            IndicesService indicesService = internalCluster().getInstance(IndicesService.class, nodeName);
            for (IndexService indexService : indicesService) {
                if (!indexService.index().getName().equals(index)) {
                    continue;
                }
                IndexShard shard = indexService.getShard(shardNum);
                if (shard != null && shard.routingEntry().primary()) {
                    return shard;
                }
            }
        }
        throw new AssertionError("primary copy of [" + index + "][" + shardNum + "] not located on any data node");
    }

    /**
     * Return every {@link IndexShard} copy of the given {@link ShardId} on every
     * data node — typically one primary + one replica for a 1-shard, 1-replica index.
     */
    private List<IndexShard> allLocalCopies(ShardId shardId) {
        List<IndexShard> copies = new ArrayList<>();
        for (String nodeName : internalCluster().getNodeNames()) {
            IndicesService indicesService = internalCluster().getInstance(IndicesService.class, nodeName);
            for (IndexService indexService : indicesService) {
                if (!indexService.index().getName().equals(shardId.getIndexName())) {
                    continue;
                }
                IndexShard copy = indexService.getShard(shardId.id());
                if (copy != null) {
                    copies.add(copy);
                }
            }
        }
        return copies;
    }

    private static boolean containsLease(IndexShard shard, String leaseId) {
        for (RetentionLease lease : shard.getRetentionLeases().leases()) {
            if (lease.id().equals(leaseId)) {
                return true;
            }
        }
        return false;
    }

    private static Set<String> currentLeaseIds(IndexShard shard) {
        Set<String> ids = new HashSet<>();
        for (RetentionLease lease : shard.getRetentionLeases().leases()) {
            ids.add(lease.id());
        }
        return ids;
    }
}
