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
import com.atlassian.opensearch.aosc.model.ShardRoutingMode;
import com.atlassian.opensearch.aosc.model.phase.ShardPhase;
import com.atlassian.opensearch.aosc.transform.IdentityTransformFunction;
import com.atlassian.opensearch.aosc.utils.AoscLogger;
import com.atlassian.opensearch.aosc.utils.ShardHandle;

import org.opensearch.common.settings.ClusterSettings;
import org.opensearch.common.settings.Settings;
import org.opensearch.common.util.concurrent.ThreadContext;
import org.opensearch.core.index.Index;
import org.opensearch.core.index.shard.ShardId;
import org.opensearch.index.seqno.SeqNoStats;
import org.opensearch.index.shard.IndexShard;
import org.opensearch.test.OpenSearchTestCase;
import org.opensearch.threadpool.ThreadPool;
import org.opensearch.transport.client.Client;

import java.util.HashSet;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Tests for {@link ShardMigrationWorker}.
 */
public class ShardMigrationWorkerTests extends OpenSearchTestCase {

    private static final String MIGRATION_ID = "mig-test-123";
    private static final String SOURCE_INDEX = "source-index";
    private static final String TARGET_INDEX = "target-index";
    private final ThreadPool threadPool = mockThreadPool();

    // ---- Constructor null validation ----

    public void testConstructorRejectsNullMigrationId() {
        expectThrows(
            NullPointerException.class,
            () -> new ShardMigrationWorker(
                AoscLogger.create(ShardMigrationWorker.class),
                null,
                new ShardHandle(AoscLogger.create(ShardHandle.class), mockShard(), threadPool),
                TARGET_INDEX,
                1,
                ShardRoutingMode.BULK_API,
                null,
                IdentityTransformFunction.INSTANCE,
                AoscTestUtil.defaultMigrationOptions(),
                mock(Client.class),
                mockThreadPool(),
                new BackfillPermitManager(AoscLogger.create(BackfillPermitManager.class), 100),
                null,
                testClusterSettings()
            )
        );
    }

    public void testConstructorRejectsNullIndexShard() {
        expectThrows(
            NullPointerException.class,
            () -> new ShardMigrationWorker(
                AoscLogger.create(ShardMigrationWorker.class),
                MIGRATION_ID,
                null,
                TARGET_INDEX,
                1,
                ShardRoutingMode.BULK_API,
                null,
                IdentityTransformFunction.INSTANCE,
                AoscTestUtil.defaultMigrationOptions(),
                mock(Client.class),
                mockThreadPool(),
                new BackfillPermitManager(AoscLogger.create(BackfillPermitManager.class), 100),
                null,
                testClusterSettings()
            )
        );
    }

    public void testConstructorRejectsNullTargetIndex() {
        expectThrows(
            NullPointerException.class,
            () -> new ShardMigrationWorker(
                AoscLogger.create(ShardMigrationWorker.class),
                MIGRATION_ID,
                new ShardHandle(AoscLogger.create(ShardHandle.class), mockShard(), threadPool),
                null,
                1,
                ShardRoutingMode.BULK_API,
                null,
                IdentityTransformFunction.INSTANCE,
                AoscTestUtil.defaultMigrationOptions(),
                mock(Client.class),
                mockThreadPool(),
                new BackfillPermitManager(AoscLogger.create(BackfillPermitManager.class), 100),
                null,
                testClusterSettings()
            )
        );
    }

    public void testConstructorRejectsNullOptions() {
        expectThrows(
            NullPointerException.class,
            () -> new ShardMigrationWorker(
                AoscLogger.create(ShardMigrationWorker.class),
                MIGRATION_ID,
                new ShardHandle(AoscLogger.create(ShardHandle.class), mockShard(), threadPool),
                TARGET_INDEX,
                1,
                ShardRoutingMode.BULK_API,
                null,
                IdentityTransformFunction.INSTANCE,
                null,
                mock(Client.class),
                mockThreadPool(),
                new BackfillPermitManager(AoscLogger.create(BackfillPermitManager.class), 100),
                null,
                testClusterSettings()
            )
        );
    }

    public void testConstructorRejectsNullClient() {
        expectThrows(
            NullPointerException.class,
            () -> new ShardMigrationWorker(
                AoscLogger.create(ShardMigrationWorker.class),
                MIGRATION_ID,
                new ShardHandle(AoscLogger.create(ShardHandle.class), mockShard(), threadPool),
                TARGET_INDEX,
                1,
                ShardRoutingMode.BULK_API,
                null,
                IdentityTransformFunction.INSTANCE,
                AoscTestUtil.defaultMigrationOptions(),
                null,
                mockThreadPool(),
                new BackfillPermitManager(AoscLogger.create(BackfillPermitManager.class), 100),
                null,
                testClusterSettings()
            )
        );
    }

    public void testConstructorRejectsNullThreadPool() {
        expectThrows(
            NullPointerException.class,
            () -> new ShardMigrationWorker(
                AoscLogger.create(ShardMigrationWorker.class),
                MIGRATION_ID,
                new ShardHandle(AoscLogger.create(ShardHandle.class), mockShard(), threadPool),
                TARGET_INDEX,
                1,
                ShardRoutingMode.BULK_API,
                null,
                IdentityTransformFunction.INSTANCE,
                AoscTestUtil.defaultMigrationOptions(),
                mock(Client.class),
                null,
                new BackfillPermitManager(AoscLogger.create(BackfillPermitManager.class), 100),
                null,
                testClusterSettings()
            )
        );
    }

    // ---- currentPhase() ----

    public void testCurrentPhaseReturnsSmState() {
        ShardMigrationWorker worker = createWorker();
        assertEquals(ShardPhase.PENDING, worker.currentPhase());
        worker.close();
    }

    // ---- close() ----

    public void testDoubleCloseIsIdempotent() {
        ShardMigrationWorker worker = createWorker();
        worker.close();
        worker.close(); // Should not throw
    }

    public void testCloseAfterStart() throws Exception {
        ShardMigrationWorker worker = createWorker();
        worker.start();
        Thread.sleep(50);
        worker.close(); // Should not throw
    }

    // ---- cancel() ----

    public void testCancelTransitionsTowardsCancelled() throws Exception {
        ShardMigrationWorker worker = createWorker();
        worker.cancel();
        Thread.sleep(100);
        ShardPhase phase = worker.currentPhase();
        assertTrue("Expected CANCELLING or CANCELLED but got " + phase, phase == ShardPhase.CANCELLING || phase == ShardPhase.CANCELLED);
        worker.close();
    }

    // ---- failWithReason() ----

    public void testFailWithReasonTransitionsTowardsFailed() throws Exception {
        ShardMigrationWorker worker = createWorker();
        worker.failWithReason("test failure");
        Thread.sleep(100);
        ShardPhase phase = worker.currentPhase();
        assertTrue("Expected FAILING or FAILED but got " + phase, phase == ShardPhase.FAILING || phase == ShardPhase.FAILED);
        worker.close();
    }

    // ---- signalCatchUp() ----

    public void testCompleteCatchUpSignalDoesNotThrow() {
        ShardMigrationWorker worker = createWorker();
        worker.signalCatchUp();
        worker.signalCatchUp(); // Idempotent — should not throw
        worker.close();
    }

    // ---- Helpers ----

    private ShardMigrationWorker createWorker() {
        return new ShardMigrationWorker(
            AoscLogger.create(ShardMigrationWorker.class),
            MIGRATION_ID,
            new ShardHandle(AoscLogger.create(ShardHandle.class), mockShard(), threadPool),
            TARGET_INDEX,
            1,
            ShardRoutingMode.BULK_API,
            null,
            IdentityTransformFunction.INSTANCE,
            AoscTestUtil.defaultMigrationOptions(),
            mock(Client.class),
            mockThreadPool(),
            new BackfillPermitManager(AoscLogger.create(BackfillPermitManager.class), 100),
            null,
            testClusterSettings()
        );
    }

    private static ClusterSettings testClusterSettings() {
        return new ClusterSettings(Settings.EMPTY, new HashSet<>(AoscSettings.ALL));
    }

    private static IndexShard mockShard() {
        IndexShard shard = mock(IndexShard.class);
        when(shard.shardId()).thenReturn(new ShardId(new Index(SOURCE_INDEX, "uuid"), 0));
        SeqNoStats seqNoStats = new SeqNoStats(100L, 100L, 100L);
        when(shard.seqNoStats()).thenReturn(seqNoStats);
        return shard;
    }

    private static ThreadPool mockThreadPool() {
        ThreadPool tp = mock(ThreadPool.class);
        ThreadPool.Cancellable mockCancellable = mock(ThreadPool.Cancellable.class);
        when(tp.scheduleWithFixedDelay(any(), any(), anyString())).thenReturn(mockCancellable);
        when(tp.getThreadContext()).thenReturn(new ThreadContext(Settings.EMPTY));
        return tp;
    }

}
