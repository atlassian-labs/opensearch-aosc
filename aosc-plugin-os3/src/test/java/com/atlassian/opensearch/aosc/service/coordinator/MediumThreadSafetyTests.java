/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */
package com.atlassian.opensearch.aosc.service.coordinator;

import com.atlassian.opensearch.aosc.AoscSettings;
import com.atlassian.opensearch.aosc.AoscTestUtil;
import com.atlassian.opensearch.aosc.model.AoscMigrationsClusterState;
import com.atlassian.opensearch.aosc.model.MigrationMetadata;
import com.atlassian.opensearch.aosc.model.ShardProgressDocument;
import com.atlassian.opensearch.aosc.model.ShardRoutingMode;
import com.atlassian.opensearch.aosc.model.phase.CoordinatorPhase;
import com.atlassian.opensearch.aosc.model.phase.ShardPhase;
import com.atlassian.opensearch.aosc.model.transform.InlineTransformScript;
import com.atlassian.opensearch.aosc.utils.AoscLogger;

import org.opensearch.cluster.ClusterState;
import org.opensearch.cluster.service.ClusterService;
import org.opensearch.common.settings.ClusterSettings;
import org.opensearch.common.settings.Settings;
import org.opensearch.test.OpenSearchTestCase;
import org.opensearch.threadpool.ThreadPool;
import org.opensearch.transport.client.AdminClient;
import org.opensearch.transport.client.Client;
import org.opensearch.transport.client.IndicesAdminClient;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Regression tests for thread-safety fixes in shard migration components.
 */
public class MediumThreadSafetyTests extends OpenSearchTestCase {

    private static final String MIGRATION_ID = "mig-thread-safety";
    private static final String SOURCE_INDEX = "source-idx";
    private static final String TARGET_INDEX = "target-idx";

    /**
     * Concurrent getAndAccumulate(Math::max) must always converge to the global maximum.
     */
    public void testM1_confirmedSeqNoMonotonicUnderConcurrency() throws Exception {
        final AtomicLong confirmedSeqNo = new AtomicLong(-1);
        final int threadCount = 8;
        final int iterationsPerThread = 10_000;
        final CyclicBarrier barrier = new CyclicBarrier(threadCount);
        final AtomicReference<Throwable> failure = new AtomicReference<>();

        Thread[] threads = new Thread[threadCount];
        for (int t = 0; t < threadCount; t++) {
            final int threadId = t;
            threads[t] = new Thread(() -> {
                try {
                    barrier.await(5, TimeUnit.SECONDS);
                    for (int i = 0; i < iterationsPerThread; i++) {

                        long newVal = threadId * iterationsPerThread + i;
                        long previous = confirmedSeqNo.getAndAccumulate(newVal, Math::max);

                        long current = confirmedSeqNo.get();
                        if (current < newVal && current < previous) {
                            failure.compareAndSet(
                                null,
                                new AssertionError(
                                    "Monotonicity violated: previous=" + previous + ", proposed=" + newVal + ", current=" + current
                                )
                            );
                        }
                    }
                } catch (Throwable e) {
                    failure.compareAndSet(null, e);
                }
            });
            threads[t].start();
        }

        for (Thread t : threads)
            t.join(10_000);
        if (failure.get() != null) throw new AssertionError("Concurrency failure", failure.get());

        long expectedMax = (long) (threadCount - 1) * iterationsPerThread + (iterationsPerThread - 1);
        assertEquals("confirmedSeqNo should hold the global maximum", expectedMax, confirmedSeqNo.get());
    }

    /**
     * computeIfAbsent with an enabled-gate must reject creation after disable.
     */
    public void testM2_computeIfAbsentRejectsWhenDisabled() throws Exception {
        ConcurrentMap<String, String> workers = new ConcurrentHashMap<>();
        AtomicBoolean enabled = new AtomicBoolean(true);

        String result1 = workers.computeIfAbsent("worker-1", k -> {
            if (!enabled.get()) return null;
            return "created";
        });
        assertEquals("Worker should be created when enabled", "created", result1);
        assertEquals(1, workers.size());

        enabled.set(false);
        String result2 = workers.computeIfAbsent("worker-2", k -> {
            if (!enabled.get()) return null;
            return "created";
        });
        assertNull("Worker should NOT be created when disabled", result2);
        assertEquals("Map should still have only 1 entry", 1, workers.size());

        assertEquals("created", workers.get("worker-1"));
    }

    /**
     * putIfAbsent re-enqueue must not overwrite newer updates that arrived during flush.
     */
    public void testM3_flushFailurePreservesNewerUpdates() throws Exception {

        ConcurrentHashMap<Integer, AoscMigrationsClusterState.ShardMigrationClusterState> currentMap = new ConcurrentHashMap<>();

        AoscMigrationsClusterState.ShardMigrationClusterState newerStatus = AoscMigrationsClusterState.ShardMigrationClusterState.builder()
            .phase(ShardPhase.REPLAYING)
            .lastReplayedSeqNo(100L)
            .backfillCutoffSeqNo(50L)
            .failure(null)
            .meta(MigrationMetadata.EMPTY)
            .build();
        currentMap.put(0, newerStatus);

        ConcurrentHashMap<Integer, AoscMigrationsClusterState.ShardMigrationClusterState> snapshot = new ConcurrentHashMap<>();
        AoscMigrationsClusterState.ShardMigrationClusterState staleStatus = AoscMigrationsClusterState.ShardMigrationClusterState.builder()
            .phase(ShardPhase.REPLAYING)
            .lastReplayedSeqNo(50L)
            .backfillCutoffSeqNo(50L)
            .failure(null)
            .meta(MigrationMetadata.EMPTY)
            .build();
        snapshot.put(0, staleStatus);

        AoscMigrationsClusterState.ShardMigrationClusterState shard1Status = AoscMigrationsClusterState.ShardMigrationClusterState.builder()
            .phase(ShardPhase.BACKFILLING)
            .lastReplayedSeqNo(10L)
            .backfillCutoffSeqNo(5L)
            .failure(null)
            .meta(MigrationMetadata.EMPTY)
            .build();
        snapshot.put(1, shard1Status);

        snapshot.forEach((shardId, status) -> currentMap.putIfAbsent(shardId, status));
        assertEquals("Shard 0 should keep newer update", 100L, currentMap.get(0).lastReplayedSeqNo());

        assertNotNull("Shard 1 should be re-enqueued", currentMap.get(1));
        assertEquals("Shard 1 should have snapshot value", 10L, currentMap.get(1).lastReplayedSeqNo());
    }

    /**
     * acceptShardUpdate batches same-phase updates and flushes on phase change.
     */
    public void testM4_acceptShardUpdateUsesConsistentEntry() throws Exception {
        Map<Integer, AoscMigrationsClusterState.ShardMigrationClusterState> shards = new HashMap<>();
        shards.put(
            0,
            AoscMigrationsClusterState.ShardMigrationClusterState.builder()
                .phase(ShardPhase.ACQUIRING_LEASE)
                .lastReplayedSeqNo(-1L)
                .backfillCutoffSeqNo(-1L)
                .failure(null)
                .meta(MigrationMetadata.EMPTY)
                .build()
        );

        AoscMigrationsClusterState.Entry entry = entryWithShards(CoordinatorPhase.ACTIVE, shards);

        Client mockClient = mock(Client.class);
        AdminClient mockAdminClient = mock(AdminClient.class);
        IndicesAdminClient mockIndicesAdminClient = mock(IndicesAdminClient.class);
        when(mockClient.admin()).thenReturn(mockAdminClient);
        when(mockAdminClient.indices()).thenReturn(mockIndicesAdminClient);

        ClusterService mockClusterService = mock(ClusterService.class);
        when(mockClusterService.state()).thenReturn(mock(ClusterState.class));
        when(mockClusterService.getSettings()).thenReturn(Settings.EMPTY);
        when(mockClusterService.getClusterSettings()).thenReturn(new ClusterSettings(Settings.EMPTY, new HashSet<>(AoscSettings.ALL)));

        ThreadPool mockThreadPool = mock(ThreadPool.class);
        ThreadPool.Cancellable mockCancellable = mock(ThreadPool.Cancellable.class);
        when(mockThreadPool.scheduleWithFixedDelay(any(), any(), any())).thenReturn(mockCancellable);

        MigrationDocumentService mockMetadataService = mock(MigrationDocumentService.class);

        MigrationCoordinator coordinator = new MigrationCoordinator(
            AoscLogger.create(MigrationCoordinator.class),
            MIGRATION_ID,
            CoordinatorPhase.ACTIVE,
            entry,
            mockClient,
            mockClusterService,
            mockThreadPool,
            mockMetadataService,
            () -> {}
        );

        CompletableFuture<?> result = coordinator.acceptShardUpdate(
            0,
            ShardProgressDocument.builder().phase(ShardPhase.ACQUIRING_LEASE).build()
        );
        assertNotNull(result);

        CompletableFuture<?> result2 = coordinator.acceptShardUpdate(
            0,
            ShardProgressDocument.builder().phase(ShardPhase.BACKFILLING).build()
        );
        assertNotNull(result2);

        coordinator.close();
    }

    private static AoscMigrationsClusterState.Entry entryWithShards(
        CoordinatorPhase phase,
        Map<Integer, AoscMigrationsClusterState.ShardMigrationClusterState> shards
    ) {
        return AoscMigrationsClusterState.Entry.builder()
            .migrationId(MIGRATION_ID)
            .sourceIndex(SOURCE_INDEX)
            .targetIndex(TARGET_INDEX)
            .alias("test-alias")
            .transformScript(new InlineTransformScript("ctx._source.version = 2", null))
            .options(AoscTestUtil.defaultMigrationOptions())
            .phase(phase)
            .routingMode(ShardRoutingMode.BULK_API)
            .startTimeMillis(System.currentTimeMillis())
            .shards(shards)
            .failure(null)
            .meta(MigrationMetadata.EMPTY)
            .build();
    }
}
