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

import java.util.HashSet;
import java.util.Set;

/**
 * End-to-end integration tests for the F006 retention-lease cleanup endpoint.
 *
 * <p>These tests boot a real OpenSearch cluster (with the AOSC plugin loaded by
 * {@link AoscIntegTestBase}), plant a retention lease directly on a shard via
 * {@link IndexShard#addRetentionLease}, then invoke {@link CleanupLeasesAction}
 * and re-read the shard's leases to assert the planted lease is physically gone.
 *
 * <p>Why plant directly instead of running a real migration: a real migration
 * acquires the lease asynchronously inside {@code ShardMigrationWorker}, so the
 * window between {@code start} and lease-held is timing-dependent. Planting
 * directly is deterministic and exercises the exact same lease-discovery and
 * lease-removal codepaths the production action uses (via
 * {@code IndicesStatsAction} → filter → {@code RetentionLeaseActions.Remove}).
 */
@ClusterScope(scope = Scope.TEST, numDataNodes = 1, numClientNodes = 0)
public class CleanupLeasesIT extends AoscIntegTestBase {

    private static final String AOSC_PREFIX = RetentionLeaseManager.LEASE_ID_PREFIX;
    private static final String AOSC_SOURCE = RetentionLeaseManager.LEASE_SOURCE;
    private static final String NON_AOSC_PREFIX = "peer-recovery-";

    /**
     * Plants a single AOSC lease on a single shard, runs cleanup (live, no dry-run),
     * and verifies the lease is physically removed.
     */
    public void testCleanupRemovesPlantedAoscLease() throws Exception {
        String index = indexName("clp-removes");
        createIndex(index, 1);

        ShardId shardId = primaryShardId(index, 0);
        String leaseId = AOSC_PREFIX + "mig-removes-0";

        plantLease(shardId, leaseId, /* retainingSeqNo */ 0L);
        assertLeasePresent(shardId, leaseId);

        CleanupLeasesResponse response = invokeCleanup(new String[0], /* dryRun */ false);

        assertEquals("expected exactly one lease to be released", 1, response.body().releasedCount());
        assertEquals("no failures expected", 0, response.body().failedCount());
        assertEquals(1, response.body().leases().size());
        assertEquals(leaseId, response.body().leases().get(0).leaseId());
        assertTrue(response.body().leases().get(0).released());

        assertLeaseAbsent(shardId, leaseId);
    }

    /**
     * Mixes one AOSC lease with one non-AOSC lease on the same shard. Cleanup must
     * remove the AOSC one and leave the non-AOSC one untouched.
     */
    public void testCleanupLeavesNonAoscLeaseUntouched() throws Exception {
        String index = indexName("clp-leaves");
        createIndex(index, 1);

        ShardId shardId = primaryShardId(index, 0);
        String aoscLease = AOSC_PREFIX + "mig-leaves-0";
        String nonAoscLease = NON_AOSC_PREFIX + "follower-1";

        plantLease(shardId, aoscLease, 0L);
        plantLease(shardId, nonAoscLease, /* retainingSeqNo */ 0L, /* source */ "peer-recovery");
        assertLeasePresent(shardId, aoscLease);
        assertLeasePresent(shardId, nonAoscLease);

        CleanupLeasesResponse response = invokeCleanup(new String[0], false);

        assertEquals(1, response.body().releasedCount());
        assertEquals(0, response.body().failedCount());
        assertEquals(aoscLease, response.body().leases().get(0).leaseId());

        assertLeaseAbsent(shardId, aoscLease);
        assertLeasePresent("non-AOSC lease MUST be preserved", shardId, nonAoscLease);
    }

    /**
     * Plants multiple AOSC leases across multiple shards and indices. Cleanup must
     * remove all of them in a single call, with stable counts.
     */
    public void testCleanupRemovesMultipleAoscLeasesAcrossShards() throws Exception {
        String indexA = indexName("clp-multi-a");
        String indexB = indexName("clp-multi-b");
        createIndex(indexA, 2);
        createIndex(indexB, 1);

        ShardId a0 = primaryShardId(indexA, 0);
        ShardId a1 = primaryShardId(indexA, 1);
        ShardId b0 = primaryShardId(indexB, 0);

        plantLease(a0, AOSC_PREFIX + "mig-a-0", 0L);
        plantLease(a1, AOSC_PREFIX + "mig-a-1", 0L);
        plantLease(b0, AOSC_PREFIX + "mig-b-0", 0L);

        CleanupLeasesResponse response = invokeCleanup(new String[0], false);

        assertEquals(3, response.body().releasedCount());
        assertEquals(0, response.body().failedCount());

        assertLeaseAbsent(a0, AOSC_PREFIX + "mig-a-0");
        assertLeaseAbsent(a1, AOSC_PREFIX + "mig-a-1");
        assertLeaseAbsent(b0, AOSC_PREFIX + "mig-b-0");
    }

    /**
     * Per-index scoping: a planted lease on index A must be removed when cleanup is
     * called with {@code indices=[A]}, and the planted lease on index B must remain.
     */
    public void testPerIndexScopingOnlyRemovesLeasesOnTargetedIndex() throws Exception {
        String indexA = indexName("clp-scope-a");
        String indexB = indexName("clp-scope-b");
        createIndex(indexA, 1);
        createIndex(indexB, 1);

        ShardId a0 = primaryShardId(indexA, 0);
        ShardId b0 = primaryShardId(indexB, 0);

        plantLease(a0, AOSC_PREFIX + "mig-scope-a-0", 0L);
        plantLease(b0, AOSC_PREFIX + "mig-scope-b-0", 0L);

        CleanupLeasesResponse response = invokeCleanup(new String[] { indexA }, false);

        assertEquals(1, response.body().releasedCount());
        assertEquals(0, response.body().failedCount());
        assertEquals(indexA, response.body().leases().get(0).index());

        assertLeaseAbsent(a0, AOSC_PREFIX + "mig-scope-a-0");
        assertLeasePresent("lease on index B MUST remain when cleanup is scoped to A only", b0, AOSC_PREFIX + "mig-scope-b-0");
    }

    /**
     * Dry-run on a planted lease must list it but NOT remove it. A subsequent
     * non-dry-run call then performs the actual removal — proving the discovery
     * path and the removal path are independent.
     */
    public void testDryRunListsButDoesNotRemoveThenLiveRunRemoves() throws Exception {
        String index = indexName("clp-dryrun");
        createIndex(index, 1);

        ShardId shardId = primaryShardId(index, 0);
        String leaseId = AOSC_PREFIX + "mig-dryrun-0";
        plantLease(shardId, leaseId, 0L);

        // Dry-run: lease must appear in response but remain present on the shard.
        CleanupLeasesResponse dry = invokeCleanup(new String[0], true);
        assertEquals(0, dry.body().releasedCount());
        assertEquals(0, dry.body().failedCount());
        assertEquals(1, dry.body().leases().size());
        assertEquals(leaseId, dry.body().leases().get(0).leaseId());
        assertFalse("dry-run must not mark released=true", dry.body().leases().get(0).released());
        assertLeasePresent("dry-run MUST NOT remove the lease", shardId, leaseId);

        // Live run: now the lease must be physically removed.
        CleanupLeasesResponse live = invokeCleanup(new String[0], false);
        assertEquals(1, live.body().releasedCount());
        assertEquals(0, live.body().failedCount());
        assertLeaseAbsent(shardId, leaseId);
    }

    /**
     * Idempotency: running cleanup twice in a row must succeed both times. The
     * second run reports zero leases (everything was already removed by the first).
     */
    public void testCleanupIsIdempotent() throws Exception {
        String index = indexName("clp-idemp");
        createIndex(index, 1);

        ShardId shardId = primaryShardId(index, 0);
        String leaseId = AOSC_PREFIX + "mig-idemp-0";
        plantLease(shardId, leaseId, 0L);

        CleanupLeasesResponse first = invokeCleanup(new String[0], false);
        assertEquals(1, first.body().releasedCount());

        CleanupLeasesResponse second = invokeCleanup(new String[0], false);
        assertEquals("second run must find nothing to remove", 0, second.body().releasedCount());
        assertEquals(0, second.body().failedCount());
        assertEquals(0, second.body().leases().size());
    }

    // ---- helpers ----

    private void createIndex(String index, int numShards) {
        client().admin()
            .indices()
            .prepareCreate(index)
            .setSettings(Settings.builder().put("index.number_of_shards", numShards).put("index.number_of_replicas", 0))
            .get();
        ensureGreen(index);
    }

    /**
     * Resolve the {@link ShardId} for a primary copy of the given index/shard. Walks
     * every data node's {@link IndicesService} to locate whichever node actually hosts
     * the primary (we don't assume single-node here so the helper survives if a future
     * test bumps {@code numDataNodes}).
     */
    private ShardId primaryShardId(String index, int shardNum) {
        return locatePrimary(index, shardNum).shardId();
    }

    private IndexShard locatePrimary(ShardId shardId) {
        return locatePrimary(shardId.getIndexName(), shardId.id());
    }

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

    private void plantLease(ShardId shardId, String leaseId, long retainingSeqNo) throws Exception {
        plantLease(shardId, leaseId, retainingSeqNo, AOSC_SOURCE);
    }

    private void plantLease(ShardId shardId, String leaseId, long retainingSeqNo, String source) throws Exception {
        IndexShard shard = locatePrimary(shardId);
        PlainActionFuture<ReplicationResponse> future = PlainActionFuture.newFuture();
        shard.addRetentionLease(leaseId, retainingSeqNo, source, future);
        future.actionGet();
        // Allow the lease sync to propagate so the next IndicesStatsAction call observes it.
        assertBusy(() -> {
            Set<String> ids = currentLeaseIds(shardId);
            assertTrue("planted lease [" + leaseId + "] not yet visible on shard " + shardId + " (saw " + ids + ")", ids.contains(leaseId));
        });
    }

    private CleanupLeasesResponse invokeCleanup(String[] indices, boolean dryRun) {
        PlainActionFuture<CleanupLeasesResponse> future = PlainActionFuture.newFuture();
        client().execute(
            CleanupLeasesAction.INSTANCE,
            new CleanupLeasesRequest(new CleanupLeasesBody(indices, dryRun)),
            ActionListener.wrap(future::onResponse, future::onFailure)
        );
        return future.actionGet();
    }

    private Set<String> currentLeaseIds(ShardId shardId) {
        IndexShard shard = locatePrimary(shardId);
        Set<String> ids = new HashSet<>();
        for (RetentionLease lease : shard.getRetentionLeases().leases()) {
            ids.add(lease.id());
        }
        return ids;
    }

    private void assertLeasePresent(ShardId shardId, String leaseId) throws Exception {
        assertLeasePresent("expected lease [" + leaseId + "] to be present on " + shardId, shardId, leaseId);
    }

    private void assertLeasePresent(String message, ShardId shardId, String leaseId) throws Exception {
        // Use assertBusy so we don't race against the periodic lease sync.
        assertBusy(() -> {
            Set<String> ids = currentLeaseIds(shardId);
            assertTrue(message + " (current leases: " + ids + ")", ids.contains(leaseId));
        });
    }

    private void assertLeaseAbsent(ShardId shardId, String leaseId) throws Exception {
        assertBusy(() -> {
            Set<String> ids = currentLeaseIds(shardId);
            assertFalse(
                "expected lease [" + leaseId + "] to be removed from " + shardId + " (current leases: " + ids + ")",
                ids.contains(leaseId)
            );
        });
    }
}
