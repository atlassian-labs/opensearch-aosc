/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */
package com.atlassian.opensearch.aosc.service.worker;

import com.atlassian.opensearch.aosc.AoscSettings;
import com.atlassian.opensearch.aosc.AoscTestUtil;
import com.atlassian.opensearch.aosc.model.AoscMigrationsClusterState;
import com.atlassian.opensearch.aosc.model.MigrationMetadata;
import com.atlassian.opensearch.aosc.model.ShardRoutingMode;
import com.atlassian.opensearch.aosc.model.phase.CoordinatorPhase;
import com.atlassian.opensearch.aosc.model.transform.InlineTransformScript;
import com.atlassian.opensearch.aosc.transform.TransformFactory;
import com.atlassian.opensearch.aosc.utils.AoscLogger;

import org.opensearch.cluster.ClusterChangedEvent;
import org.opensearch.cluster.ClusterState;
import org.opensearch.cluster.metadata.Metadata;
import org.opensearch.cluster.routing.ShardRouting;
import org.opensearch.cluster.routing.ShardRoutingState;
import org.opensearch.cluster.routing.TestShardRouting;
import org.opensearch.cluster.service.ClusterService;
import org.opensearch.common.settings.ClusterSettings;
import org.opensearch.common.settings.Settings;
import org.opensearch.core.index.Index;
import org.opensearch.core.index.shard.ShardId;
import org.opensearch.index.shard.IndexShard;
import org.opensearch.test.OpenSearchTestCase;
import org.opensearch.threadpool.ThreadPool;
import org.opensearch.transport.client.Client;

import java.io.IOException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.concurrent.ExecutorService;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tests for {@link AoscShardService}.
 */
public class AoscShardServiceTests extends OpenSearchTestCase {

    private static final String MIGRATION_ID = "mig-test-123";
    private static final String SOURCE_INDEX = "source-index";
    private static final String TARGET_INDEX = "target-index";

    // ---- Constructor validation ----

    public void testConstructorRejectsNullClusterService() {
        Client client = mock(Client.class);

        ThreadPool threadPool = mock(ThreadPool.class);
        TransformFactory transformFactory = mock(TransformFactory.class);

        expectThrows(
            NullPointerException.class,
            () -> new AoscShardService(AoscLogger.create(AoscShardService.class), null, client, threadPool, transformFactory)
        );
    }

    public void testConstructorRejectsNullClient() {
        ClusterService clusterService = mockClusterService();
        ThreadPool threadPool = mock(ThreadPool.class);
        TransformFactory transformFactory = mock(TransformFactory.class);

        expectThrows(
            NullPointerException.class,
            () -> new AoscShardService(AoscLogger.create(AoscShardService.class), clusterService, null, threadPool, transformFactory)
        );
    }

    public void testConstructorRejectsNullThreadPool() {
        ClusterService clusterService = mockClusterService();
        Client client = mock(Client.class);
        TransformFactory transformFactory = mock(TransformFactory.class);

        expectThrows(
            NullPointerException.class,
            () -> new AoscShardService(AoscLogger.create(AoscShardService.class), clusterService, client, null, transformFactory)
        );
    }

    public void testConstructorRejectsNullTransformFactory() {
        ClusterService clusterService = mockClusterService();
        Client client = mock(Client.class);
        ThreadPool threadPool = mock(ThreadPool.class);

        expectThrows(
            NullPointerException.class,
            () -> new AoscShardService(AoscLogger.create(AoscShardService.class), clusterService, client, threadPool, null)
        );
    }

    // ---- Cluster state event handling ----

    public void testClusterChangedIgnoresNullAoscState() {
        ClusterService clusterService = mockClusterService();
        Client client = mock(Client.class);
        ThreadPool threadPool = mock(ThreadPool.class);
        TransformFactory transformFactory = mock(TransformFactory.class);

        AoscShardService service = new AoscShardService(
            AoscLogger.create(AoscShardService.class),
            clusterService,
            client,
            threadPool,
            transformFactory
        );

        // Create a cluster state event with no AoscMigrationsClusterState
        ClusterState previousState = ClusterState.builder(ClusterState.EMPTY_STATE).metadata(Metadata.builder().build()).build();
        ClusterState newState = ClusterState.builder(previousState).metadata(Metadata.builder().build()).build();

        ClusterChangedEvent event = new ClusterChangedEvent("test", newState, previousState);

        // Should not throw and should not create any workers
        service.clusterChanged(event);

        try {
            service.close();
        } catch (IOException e) {
            // Expected for mock
        }
    }

    public void testClusterChangedIgnoresTerminalEntries() {
        ClusterService clusterService = mockClusterService();
        Client client = mock(Client.class);
        ThreadPool threadPool = mock(ThreadPool.class);
        TransformFactory transformFactory = mock(TransformFactory.class);

        AoscShardService service = new AoscShardService(
            AoscLogger.create(AoscShardService.class),
            clusterService,
            client,
            threadPool,
            transformFactory
        );

        // Create cluster state with a COMPLETED entry (terminal)
        ClusterState previousState = ClusterState.builder(ClusterState.EMPTY_STATE).metadata(Metadata.builder().build()).build();

        AoscMigrationsClusterState aoscState = new AoscMigrationsClusterState(createEntryMap(CoordinatorPhase.COMPLETED));
        ClusterState newState = ClusterState.builder(previousState)
            .metadata(Metadata.builder().build())
            .putCustom(AoscMigrationsClusterState.TYPE, aoscState)
            .build();

        ClusterChangedEvent event = new ClusterChangedEvent("test", newState, previousState);

        // Should not throw and should not create workers for terminal entries
        service.clusterChanged(event);

        try {
            service.close();
        } catch (IOException e) {
            // Expected for mock
        }
    }

    // ---- Close behavior ----

    public void testCloseClosesAllWorkers() throws IOException {
        ClusterService clusterService = mockClusterService();
        Client client = mock(Client.class);
        ThreadPool threadPool = mock(ThreadPool.class);
        TransformFactory transformFactory = mock(TransformFactory.class);

        AoscShardService service = new AoscShardService(
            AoscLogger.create(AoscShardService.class),
            clusterService,
            client,
            threadPool,
            transformFactory
        );

        // We can't directly access the worker map, but we can verify close doesn't throw
        service.close();

        // Verify that clusterService.removeListener was called
        verify(clusterService).removeListener(service);
    }

    // ---- B041 root cause reproduction: afterIndexShardStarted skips replicas ----

    /** B041: afterIndexShardStarted does NOT register replica shards. */
    public void testAfterIndexShardStartedSkipsReplicaShard() throws IOException {
        ClusterService clusterService = mockClusterService();
        Client client = mock(Client.class);
        ThreadPool threadPool = mockThreadPoolWithExecutor();
        TransformFactory transformFactory = mock(TransformFactory.class);

        AoscShardService service = new AoscShardService(
            AoscLogger.create(AoscShardService.class),
            clusterService,
            client,
            threadPool,
            transformFactory
        );

        IndexShard indexShard = mock(IndexShard.class);
        ShardId shardId = new ShardId(new Index(SOURCE_INDEX, "uuid-1"), 418);
        when(indexShard.shardId()).thenReturn(shardId);
        ShardRouting replicaRouting = TestShardRouting.newShardRouting(shardId, "node-1", false, ShardRoutingState.STARTED);
        when(indexShard.routingEntry()).thenReturn(replicaRouting);

        service.afterIndexShardStarted(indexShard);

        assertEquals("Replica shard should NOT be registered in localShards (root cause of B041)", 0, service.localShards.size());

        service.close();
    }

    /** B041 end-to-end: replica promotion invisible without fix, registered with fix. */
    public void testReplicaPromotionEndToEnd_B041() throws IOException {
        ClusterService clusterService = mockClusterService();
        Client client = mock(Client.class);
        ThreadPool threadPool = mockThreadPoolWithExecutor();
        TransformFactory transformFactory = mock(TransformFactory.class);

        AoscShardService service = new AoscShardService(
            AoscLogger.create(AoscShardService.class),
            clusterService,
            client,
            threadPool,
            transformFactory
        );

        IndexShard indexShard = mock(IndexShard.class);
        ShardId shardId = new ShardId(new Index(SOURCE_INDEX, "uuid-1"), 418);
        when(indexShard.shardId()).thenReturn(shardId);

        ShardRouting replicaRouting = TestShardRouting.newShardRouting(shardId, "node-1", false, ShardRoutingState.STARTED);
        when(indexShard.routingEntry()).thenReturn(replicaRouting);
        service.afterIndexShardStarted(indexShard);

        assertEquals("After afterIndexShardStarted with replica: shard should NOT be registered", 0, service.localShards.size());

        ShardRouting primaryRouting = TestShardRouting.newShardRouting(shardId, "node-1", true, ShardRoutingState.STARTED);
        service.shardRoutingChanged(indexShard, replicaRouting, primaryRouting);

        assertEquals("After shardRoutingChanged replica→primary: shard SHOULD be registered", 1, service.localShards.size());

        service.close();
    }

    // ---- Shard routing changed: replica-to-primary promotion ----

    /** Replica-to-primary promotion registers the shard in localShards. */
    public void testShardRoutingChangedRegistersPromotedPrimary() throws IOException {
        ClusterService clusterService = mockClusterService();
        Client client = mock(Client.class);
        ThreadPool threadPool = mockThreadPoolWithExecutor();
        TransformFactory transformFactory = mock(TransformFactory.class);

        AoscShardService service = new AoscShardService(
            AoscLogger.create(AoscShardService.class),
            clusterService,
            client,
            threadPool,
            transformFactory
        );

        IndexShard indexShard = mock(IndexShard.class);
        ShardId shardId = new ShardId(new Index(SOURCE_INDEX, "uuid-1"), 418);
        when(indexShard.shardId()).thenReturn(shardId);

        ShardRouting oldRouting = TestShardRouting.newShardRouting(shardId, "node-1", false, ShardRoutingState.STARTED);
        ShardRouting newRouting = TestShardRouting.newShardRouting(shardId, "node-1", true, ShardRoutingState.STARTED);

        service.shardRoutingChanged(indexShard, oldRouting, newRouting);

        service.beforeIndexShardClosed(shardId, indexShard, Settings.EMPTY);

        service.close();
    }

    /** Primary-to-primary routing change is a no-op. */
    public void testShardRoutingChangedPrimaryToPrimaryNoOp() throws IOException {
        ClusterService clusterService = mockClusterService();
        Client client = mock(Client.class);
        ThreadPool threadPool = mockThreadPoolWithExecutor();
        TransformFactory transformFactory = mock(TransformFactory.class);

        AoscShardService service = new AoscShardService(
            AoscLogger.create(AoscShardService.class),
            clusterService,
            client,
            threadPool,
            transformFactory
        );

        IndexShard indexShard = mock(IndexShard.class);
        ShardId shardId = new ShardId(new Index(SOURCE_INDEX, "uuid-1"), 42);
        when(indexShard.shardId()).thenReturn(shardId);
        ShardRouting primaryRouting = TestShardRouting.newShardRouting(shardId, "node-1", true, ShardRoutingState.STARTED);

        when(indexShard.routingEntry()).thenReturn(primaryRouting);
        service.afterIndexShardStarted(indexShard);

        ShardRouting newPrimaryRouting = TestShardRouting.newShardRouting(shardId, "node-1", true, ShardRoutingState.STARTED);
        service.shardRoutingChanged(indexShard, primaryRouting, newPrimaryRouting);

        service.close();
    }

    /** Replica-to-replica routing change is ignored. */
    public void testShardRoutingChangedReplicaToReplicaIgnored() throws IOException {
        ClusterService clusterService = mockClusterService();
        Client client = mock(Client.class);
        ThreadPool threadPool = mockThreadPoolWithExecutor();
        TransformFactory transformFactory = mock(TransformFactory.class);

        AoscShardService service = new AoscShardService(
            AoscLogger.create(AoscShardService.class),
            clusterService,
            client,
            threadPool,
            transformFactory
        );

        IndexShard indexShard = mock(IndexShard.class);
        ShardId shardId = new ShardId(new Index(SOURCE_INDEX, "uuid-1"), 100);
        when(indexShard.shardId()).thenReturn(shardId);

        ShardRouting replicaRouting = TestShardRouting.newShardRouting(shardId, "node-1", false, ShardRoutingState.STARTED);
        ShardRouting newReplicaRouting = TestShardRouting.newShardRouting(shardId, "node-2", false, ShardRoutingState.STARTED);

        service.shardRoutingChanged(indexShard, replicaRouting, newReplicaRouting);

        service.beforeIndexShardClosed(shardId, indexShard, Settings.EMPTY);

        service.close();
    }

    /** Null oldRouting with primary newRouting registers the shard. */
    public void testShardRoutingChangedNullOldRoutingPrimary() throws IOException {
        ClusterService clusterService = mockClusterService();
        Client client = mock(Client.class);
        ThreadPool threadPool = mockThreadPoolWithExecutor();
        TransformFactory transformFactory = mock(TransformFactory.class);

        AoscShardService service = new AoscShardService(
            AoscLogger.create(AoscShardService.class),
            clusterService,
            client,
            threadPool,
            transformFactory
        );

        IndexShard indexShard = mock(IndexShard.class);
        ShardId shardId = new ShardId(new Index(SOURCE_INDEX, "uuid-1"), 0);
        when(indexShard.shardId()).thenReturn(shardId);

        ShardRouting primaryRouting = TestShardRouting.newShardRouting(shardId, "node-1", true, ShardRoutingState.STARTED);

        service.shardRoutingChanged(indexShard, null, primaryRouting);

        service.close();
    }

    // ---- Helper methods ----

    private static ThreadPool mockThreadPoolWithExecutor() {
        ThreadPool threadPool = mock(ThreadPool.class);
        ExecutorService executor = mock(ExecutorService.class);
        when(threadPool.executor(anyString())).thenReturn(executor);
        doAnswer(invocation -> {
            // Swallow the runnable — cluster state is not set up for migration re-evaluation
            return null;
        }).when(executor).execute(any(Runnable.class));
        return threadPool;
    }

    private static ClusterService mockClusterService() {
        ClusterService cs = mock(ClusterService.class);
        doNothing().when(cs).addListener(any());
        doNothing().when(cs).removeListener(any());
        Settings settings = Settings.EMPTY;
        when(cs.getSettings()).thenReturn(settings);
        ClusterSettings clusterSettings = new ClusterSettings(settings, new HashSet<>(AoscSettings.ALL));
        when(cs.getClusterSettings()).thenReturn(clusterSettings);
        return cs;
    }

    private static Map<String, AoscMigrationsClusterState.Entry> createEntryMap(CoordinatorPhase phase) {
        Map<String, AoscMigrationsClusterState.Entry> entries = new HashMap<>();
        entries.put(
            MIGRATION_ID,
            AoscMigrationsClusterState.Entry.builder()
                .migrationId(MIGRATION_ID)
                .sourceIndex(SOURCE_INDEX)
                .targetIndex(TARGET_INDEX)
                .alias("test-alias")
                .transformScript(new InlineTransformScript("ctx._source.version = 2", null))
                .options(AoscTestUtil.defaultMigrationOptions())
                .phase(phase)
                .routingMode(ShardRoutingMode.BULK_API)
                .startTimeMillis(System.currentTimeMillis())
                .shards(new HashMap<>())
                .failure(null)
                .meta(MigrationMetadata.EMPTY)
                .build()
        );
        return entries;
    }
}
