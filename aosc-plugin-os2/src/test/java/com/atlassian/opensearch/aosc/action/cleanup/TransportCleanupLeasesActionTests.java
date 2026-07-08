/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */
package com.atlassian.opensearch.aosc.action.cleanup;

import com.atlassian.opensearch.aosc.utils.AoscLogger;
import com.atlassian.opensearch.aosc.utils.IndexOperationUtils;

import org.opensearch.action.ActionType;
import org.opensearch.action.admin.indices.stats.CommonStats;
import org.opensearch.action.admin.indices.stats.IndicesStatsAction;
import org.opensearch.action.admin.indices.stats.IndicesStatsResponse;
import org.opensearch.action.admin.indices.stats.IndicesStatsResponseTestFactory;
import org.opensearch.action.admin.indices.stats.ShardStats;
import org.opensearch.client.support.AbstractClient;
import org.opensearch.cluster.routing.ShardRouting;
import org.opensearch.cluster.routing.ShardRoutingState;
import org.opensearch.cluster.routing.TestShardRouting;
import org.opensearch.common.UUIDs;
import org.opensearch.common.io.PathUtils;
import org.opensearch.common.settings.Settings;
import org.opensearch.core.action.ActionListener;
import org.opensearch.core.action.ActionResponse;
import org.opensearch.core.index.Index;
import org.opensearch.core.index.shard.ShardId;
import org.opensearch.index.seqno.RetentionLease;
import org.opensearch.index.seqno.RetentionLeaseActions;
import org.opensearch.index.seqno.RetentionLeaseNotFoundException;
import org.opensearch.index.seqno.RetentionLeaseStats;
import org.opensearch.index.seqno.RetentionLeases;
import org.opensearch.index.shard.ShardPath;
import org.opensearch.test.OpenSearchTestCase;
import org.opensearch.threadpool.TestThreadPool;
import org.opensearch.threadpool.ThreadPool;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Unit tests for {@link TransportCleanupLeasesAction}.
 *
 * <p>The transport action delegates to {@link IndexOperationUtils} which in turn uses the
 * {@link org.opensearch.client.Client} to issue {@link IndicesStatsAction} (to enumerate
 * retention leases) and {@link RetentionLeaseActions.Remove} (to release matched leases).
 * These tests use a fake client that intercepts both calls and lets each test script the
 * per-call response, so the production action's filtering/dedup/dry-run/error-handling
 * logic is exercised end-to-end without standing up a real cluster.
 */
public class TransportCleanupLeasesActionTests extends OpenSearchTestCase {

    private TestThreadPool threadPool;

    @Override
    public void setUp() throws Exception {
        super.setUp();
        threadPool = new TestThreadPool(getTestName());
    }

    @Override
    public void tearDown() throws Exception {
        ThreadPool.terminate(threadPool, 5, TimeUnit.SECONDS);
        super.tearDown();
    }

    public void testNonAoscLeasesAreIgnored() {
        ShardId shardId = newShardId("idx", 0);
        IndicesStatsResponse stats = statsResponseFor(
            shardWithLeases(shardId, lease("aosc-migration-mig-0", 100), lease("peer-recovery/foo", 200), lease("ccr-follower-1", 300))
        );

        FakeClient client = new FakeClient(threadPool, stats, /* removeOk */ true, /* removeException */ null);
        IndexOperationUtils indexOps = new IndexOperationUtils(AoscLogger.create(IndexOperationUtils.class), client);

        CompletableFuture<CleanupLeasesResponse> future = execute(
            indexOps,
            new CleanupLeasesRequest(new CleanupLeasesBody(new String[0], false))
        );

        CleanupLeasesResponse response = future.join();
        assertNotNull(response);
        assertEquals("only the AOSC lease should be acted on", 1, response.body().leases().size());
        assertEquals("aosc-migration-mig-0", response.body().leases().get(0).leaseId());
        assertEquals(1, client.removeRequests.size());
    }

    public void testDryRunDoesNotInvokeRemove() {
        ShardId shardId = newShardId("idx", 0);
        IndicesStatsResponse stats = statsResponseFor(
            shardWithLeases(shardId, lease("aosc-migration-mig-0", 100), lease("aosc-migration-mig-1", 200))
        );

        FakeClient client = new FakeClient(threadPool, stats, true, null);
        IndexOperationUtils indexOps = new IndexOperationUtils(AoscLogger.create(IndexOperationUtils.class), client);

        CompletableFuture<CleanupLeasesResponse> future = execute(
            indexOps,
            new CleanupLeasesRequest(new CleanupLeasesBody(new String[0], true))
        );

        CleanupLeasesResponse response = future.join();
        assertNotNull(response);
        assertTrue(response.body().dryRun());
        assertEquals(2, response.body().leases().size());
        assertEquals(0, response.body().releasedCount());
        assertEquals(0, response.body().failedCount());
        assertEquals("dry-run must not invoke RetentionLeaseActions.Remove", 0, client.removeRequests.size());
        for (CleanupLeasesResult.LeaseInfo info : response.body().leases()) {
            assertFalse("released flag must be false in dry-run", info.released());
        }
    }

    public void testRemoveAllSuccess() {
        ShardId shard0 = newShardId("idx", 0);
        ShardId shard1 = newShardId("idx", 1);
        IndicesStatsResponse stats = statsResponseFor(
            shardWithLeases(shard0, lease("aosc-migration-mig-0", 100)),
            shardWithLeases(shard1, lease("aosc-migration-mig-1", 200))
        );

        FakeClient client = new FakeClient(threadPool, stats, true, null);
        IndexOperationUtils indexOps = new IndexOperationUtils(AoscLogger.create(IndexOperationUtils.class), client);

        CompletableFuture<CleanupLeasesResponse> future = execute(
            indexOps,
            new CleanupLeasesRequest(new CleanupLeasesBody(new String[0], false))
        );

        CleanupLeasesResponse response = future.join();
        assertNotNull(response);
        assertFalse(response.body().dryRun());
        assertEquals(2, response.body().releasedCount());
        assertEquals(0, response.body().failedCount());
        assertEquals(2, client.removeRequests.size());
        for (CleanupLeasesResult.LeaseInfo info : response.body().leases()) {
            assertTrue(info.released());
            assertNull(info.error());
        }
    }

    public void testRetentionLeaseNotFoundOnRemoveCountsAsSuccess() {
        ShardId shardId = newShardId("idx", 0);
        IndicesStatsResponse stats = statsResponseFor(shardWithLeases(shardId, lease("aosc-migration-mig-0", 100)));

        FakeClient client = new FakeClient(
            threadPool,
            stats,
            /* removeOk */ false,
            /* removeException */ new RetentionLeaseNotFoundException("aosc-migration-mig-0")
        );
        IndexOperationUtils indexOps = new IndexOperationUtils(AoscLogger.create(IndexOperationUtils.class), client);

        CompletableFuture<CleanupLeasesResponse> future = execute(
            indexOps,
            new CleanupLeasesRequest(new CleanupLeasesBody(new String[0], false))
        );

        CleanupLeasesResponse response = future.join();
        assertNotNull(response);
        assertEquals("RetentionLeaseNotFoundException must be treated as success (idempotent)", 1, response.body().releasedCount());
        assertEquals(0, response.body().failedCount());
        assertTrue(response.body().leases().get(0).released());
        assertNull(response.body().leases().get(0).error());
    }

    public void testGenericRemoveFailureReportedPerLease() {
        ShardId shard0 = newShardId("idx", 0);
        ShardId shard1 = newShardId("idx", 1);
        IndicesStatsResponse stats = statsResponseFor(
            shardWithLeases(shard0, lease("aosc-migration-mig-0", 100)),
            shardWithLeases(shard1, lease("aosc-migration-mig-1", 200))
        );

        FakeClient client = new FakeClient(threadPool, stats, false, new RuntimeException("network down"));
        IndexOperationUtils indexOps = new IndexOperationUtils(AoscLogger.create(IndexOperationUtils.class), client);

        CompletableFuture<CleanupLeasesResponse> future = execute(
            indexOps,
            new CleanupLeasesRequest(new CleanupLeasesBody(new String[0], false))
        );

        CleanupLeasesResponse response = future.join();
        assertNotNull(response);
        assertEquals("the whole cleanup must not abort on per-lease failure", 0, response.body().releasedCount());
        assertEquals(2, response.body().failedCount());
        for (CleanupLeasesResult.LeaseInfo info : response.body().leases()) {
            assertFalse(info.released());
            assertNotNull(info.error());
            assertTrue("error message should include exception detail", info.error().contains("network down"));
        }
    }

    public void testStatsFailurePropagatesToListener() {
        FakeClient client = new FakeClient(threadPool, /* statsResponse */ null, true, null);
        client.statsException = new RuntimeException("stats blew up");
        IndexOperationUtils indexOps = new IndexOperationUtils(AoscLogger.create(IndexOperationUtils.class), client);

        CompletableFuture<CleanupLeasesResponse> future = execute(
            indexOps,
            new CleanupLeasesRequest(new CleanupLeasesBody(new String[0], false))
        );

        assertTrue(future.isCompletedExceptionally());
        Exception ex = expectThrows(Exception.class, future::join);
        assertTrue(ex.getCause().getMessage().contains("stats blew up"));
    }

    public void testDuplicateLeaseAcrossShardCopiesIsRemovedOnce() {
        // Same shard reported twice (e.g. primary + replica copies in stats). The action
        // must dedup on (shardId, leaseId) and only issue one Remove per logical lease.
        ShardId shardId = newShardId("idx", 0);
        ShardStats primaryCopy = shardWithLeases(shardId, lease("aosc-migration-mig-0", 100));
        ShardStats replicaCopy = shardWithLeases(shardId, lease("aosc-migration-mig-0", 100));
        IndicesStatsResponse stats = statsResponseFor(primaryCopy, replicaCopy);

        FakeClient client = new FakeClient(threadPool, stats, true, null);
        IndexOperationUtils indexOps = new IndexOperationUtils(AoscLogger.create(IndexOperationUtils.class), client);

        CompletableFuture<CleanupLeasesResponse> future = execute(
            indexOps,
            new CleanupLeasesRequest(new CleanupLeasesBody(new String[0], false))
        );

        CleanupLeasesResponse response = future.join();
        assertEquals("dedup must collapse duplicate copies", 1, response.body().leases().size());
        assertEquals(1, response.body().releasedCount());
        assertEquals("Remove should be invoked exactly once per logical lease", 1, client.removeRequests.size());
    }

    public void testEmptyStatsProducesEmptyResponse() {
        IndicesStatsResponse stats = statsResponseFor(/* no shards */);
        FakeClient client = new FakeClient(threadPool, stats, true, null);
        IndexOperationUtils indexOps = new IndexOperationUtils(AoscLogger.create(IndexOperationUtils.class), client);

        CompletableFuture<CleanupLeasesResponse> future = execute(
            indexOps,
            new CleanupLeasesRequest(new CleanupLeasesBody(new String[0], false))
        );

        CleanupLeasesResponse response = future.join();
        assertNotNull(response);
        assertEquals(0, response.body().leases().size());
        assertEquals(0, response.body().releasedCount());
        assertEquals(0, response.body().failedCount());
        assertEquals(0, client.removeRequests.size());
    }

    // ---- Helpers ----

    private static ShardId newShardId(String indexName, int shard) {
        return new ShardId(new Index(indexName, UUIDs.randomBase64UUID()), shard);
    }

    private static RetentionLease lease(String id, long seqNo) {
        return new RetentionLease(id, seqNo, /* timestamp */ 0L, "aosc-migration");
    }

    private static ShardStats shardWithLeases(ShardId shardId, RetentionLease... leases) {
        ShardRouting routing = TestShardRouting.newShardRouting(shardId, "node-1", true, ShardRoutingState.STARTED);
        // ShardStats requires a non-null ShardPath. Its constructor asserts:
        // 1. dataPath.getFileName().toString().equals(Integer.toString(shardId.id()))
        // 2. dataPath.getParent().getFileName().toString().equals(shardId.getIndex().getUUID())
        // We build a synthetic path under java.io.tmpdir matching that shape; nothing
        // in the cleanup action reads from disk.
        Path tmp = PathUtils.get(
            System.getProperty("java.io.tmpdir"),
            "aosc-test",
            shardId.getIndex().getUUID(),
            String.valueOf(shardId.id())
        );
        ShardPath shardPath = new ShardPath(/* customDataPath */ false, tmp, tmp, shardId);
        RetentionLeases retentionLeases = new RetentionLeases(/* primaryTerm */ 1L, /* version */ 1L, Arrays.asList(leases));
        return new ShardStats(
            routing,
            shardPath,
            new CommonStats(),
            /* commit */ null,
            /* seqNo */ null,
            new RetentionLeaseStats(retentionLeases)
        );
    }

    private static IndicesStatsResponse statsResponseFor(ShardStats... shards) {
        // Built via {@link IndicesStatsResponseTestFactory} which lives in the same package
        // as IndicesStatsResponse so it can use the package-private constructor without
        // reflection (forbidden in this project).
        return IndicesStatsResponseTestFactory.forShards(shards);
    }

    /**
     * Minimal {@link AbstractClient} that intercepts the two action types this transport
     * action invokes. Stats responses are pre-canned per test; remove responses are
     * shared across all invocations (success or a single canned exception).
     */
    private static final class FakeClient extends AbstractClient {
        private final IndicesStatsResponse statsResponse;
        private final boolean removeOk;
        private final Exception removeException;
        Exception statsException; // optional override
        final List<RetentionLeaseActions.RemoveRequest> removeRequests = Collections.synchronizedList(new ArrayList<>());

        FakeClient(ThreadPool threadPool, IndicesStatsResponse statsResponse, boolean removeOk, Exception removeException) {
            super(Settings.EMPTY, threadPool);
            this.statsResponse = statsResponse;
            this.removeOk = removeOk;
            this.removeException = removeException;
        }

        @Override
        @SuppressWarnings({ "unchecked", "rawtypes" })
        protected <Request extends org.opensearch.action.ActionRequest, Response extends ActionResponse> void doExecute(
            ActionType<Response> action,
            Request request,
            ActionListener<Response> listener
        ) {
            if (action == IndicesStatsAction.INSTANCE) {
                if (statsException != null) {
                    listener.onFailure(statsException);
                } else {
                    listener.onResponse((Response) statsResponse);
                }
                return;
            }
            if (action == RetentionLeaseActions.Remove.INSTANCE) {
                removeRequests.add((RetentionLeaseActions.RemoveRequest) request);
                if (removeOk) {
                    listener.onResponse((Response) new RetentionLeaseActions.Response());
                } else {
                    listener.onFailure(removeException);
                }
                return;
            }
            throw new AssertionError("Unexpected action invoked in test: " + action);
        }

        @Override
        public void close() {
            // no-op
        }
    }

    private static CompletableFuture<CleanupLeasesResponse> execute(IndexOperationUtils indexOps, CleanupLeasesRequest request) {
        return TransportCleanupLeasesAction.execute(indexOps, request, AoscLogger.create(TransportCleanupLeasesActionTests.class));
    }

}
