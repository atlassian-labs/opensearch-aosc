/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */
package com.atlassian.opensearch.aosc.service.coordinator;

import com.atlassian.opensearch.aosc.action.update.UpdateShardMigrationStatusBody;
import com.atlassian.opensearch.aosc.action.update.UpdateShardMigrationStatusRequest;
import com.atlassian.opensearch.aosc.model.ShardProgressDocument;
import com.atlassian.opensearch.aosc.model.phase.ShardPhase;
import com.atlassian.opensearch.aosc.utils.AoscLogger;

import org.opensearch.cluster.service.ClusterService;
import org.opensearch.test.OpenSearchTestCase;
import org.opensearch.threadpool.ThreadPool;
import org.opensearch.transport.client.Client;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Tests for {@link AoscCoordinatorService}.
 */
public class AoscCoordinatorServiceTests extends OpenSearchTestCase {

    private Client mockClient;
    private ClusterService mockClusterService;
    private ThreadPool mockThreadPool;
    private MigrationDocumentService mockMetadataService;

    @Override
    public void setUp() throws Exception {
        super.setUp();
        mockClient = mock(Client.class);
        mockClusterService = mock(ClusterService.class);
        mockThreadPool = mock(ThreadPool.class);
        mockMetadataService = mock(MigrationDocumentService.class);

        // Mock ThreadPool.Cancellable for batch flush task
        ThreadPool.Cancellable mockCancellable = mock(ThreadPool.Cancellable.class);
        when(mockThreadPool.scheduleWithFixedDelay(any(), any(), any())).thenReturn(mockCancellable);

        // Mock executor using inline implementation
        when(mockThreadPool.executor(any())).thenAnswer(invocation -> new Executor() {
            @Override
            public void execute(Runnable command) {
                command.run();
            }
        });
    }

    // ---- Test: Accept shard update for unknown migration returns failed future ----
    public void testAcceptShardUpdateUnknownMigrationDoesNotThrowSynchronously() throws Exception {
        AoscCoordinatorService service = new AoscCoordinatorService(
            AoscLogger.create(AoscCoordinatorService.class),
            mockClient,
            mockClusterService,
            mockThreadPool,
            mockMetadataService
        );

        // Create an update for a migration that doesn't exist
        UpdateShardMigrationStatusRequest request = new UpdateShardMigrationStatusRequest(
            new UpdateShardMigrationStatusBody(
                "unknown-migration",
                0,
                ShardProgressDocument.builder().phase(ShardPhase.ACQUIRING_LEASE).lastReplayedSeqNo(-1L).backfillCutoffSeqNo(-1L).build()
            )
        );

        // Does not throw synchronously — returns a failed future instead
        CompletableFuture<?> result = service.acceptShardUpdate(request);
        assertTrue(result.isCompletedExceptionally());

        service.close();
    }

    // ---- Test: Service construction and close ----
    public void testServiceConstructionAndClose() throws Exception {
        AoscCoordinatorService service = new AoscCoordinatorService(
            AoscLogger.create(AoscCoordinatorService.class),
            mockClient,
            mockClusterService,
            mockThreadPool,
            mockMetadataService
        );

        // Service should be created without errors
        assertNotNull(service);

        // Close should not throw
        service.close();

        assertTrue(true);
    }

    // ---- Test: acceptShardUpdate for unknown migration fails exceptionally ----

    public void testAcceptShardUpdateForUnknownMigrationFails() throws Exception {
        AoscCoordinatorService service = new AoscCoordinatorService(
            AoscLogger.create(AoscCoordinatorService.class),
            mockClient,
            mockClusterService,
            mockThreadPool,
            mockMetadataService
        );

        UpdateShardMigrationStatusRequest request = new UpdateShardMigrationStatusRequest(
            new UpdateShardMigrationStatusBody(
                "nonexistent-migration-" + UUID.randomUUID(),
                0,
                ShardProgressDocument.builder().phase(ShardPhase.BACKFILLING).lastReplayedSeqNo(-1L).backfillCutoffSeqNo(-1L).build()
            )
        );

        CompletableFuture<?> result = service.acceptShardUpdate(request);

        assertTrue(result.isCompletedExceptionally());
        Exception thrown = expectThrows(ExecutionException.class, () -> result.get(5, TimeUnit.SECONDS));
        assertTrue(thrown.getCause() instanceof IllegalStateException);

        service.close();
    }

    // ---- Test: Multiple unknown shard updates all return failed futures ----
    public void testMultipleUnknownShardUpdates() throws Exception {
        AoscCoordinatorService service = new AoscCoordinatorService(
            AoscLogger.create(AoscCoordinatorService.class),
            mockClient,
            mockClusterService,
            mockThreadPool,
            mockMetadataService
        );

        // Create multiple updates for different unknown migrations — all return failed futures
        for (int i = 0; i < 3; i++) {
            UpdateShardMigrationStatusRequest request = new UpdateShardMigrationStatusRequest(
                new UpdateShardMigrationStatusBody(
                    "unknown-migration-" + i,
                    i,
                    ShardProgressDocument.builder()
                        .phase(ShardPhase.ACQUIRING_LEASE)
                        .lastReplayedSeqNo(-1L)
                        .backfillCutoffSeqNo(-1L)
                        .build()
                )
            );
            CompletableFuture<?> result = service.acceptShardUpdate(request);
            assertTrue("Expected failed future for unknown migration " + i, result.isCompletedExceptionally());
        }

        service.close();
    }
}
