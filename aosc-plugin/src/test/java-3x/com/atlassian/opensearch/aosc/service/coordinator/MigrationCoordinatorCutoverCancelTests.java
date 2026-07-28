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
import com.atlassian.opensearch.aosc.model.MigrationDocument;
import com.atlassian.opensearch.aosc.model.MigrationMetadata;
import com.atlassian.opensearch.aosc.model.ShardRoutingMode;
import com.atlassian.opensearch.aosc.model.phase.CoordinatorPhase;
import com.atlassian.opensearch.aosc.model.phase.ShardPhase;
import com.atlassian.opensearch.aosc.model.transform.InlineTransformScript;
import com.atlassian.opensearch.aosc.utils.AoscLogger;

import com.carrotsearch.randomizedtesting.annotations.ThreadLeakScope;

import org.opensearch.action.admin.indices.alias.IndicesAliasesRequest;
import org.opensearch.action.admin.indices.settings.put.UpdateSettingsRequest;
import org.opensearch.action.support.clustermanager.AcknowledgedResponse;
import org.opensearch.cluster.ClusterState;
import org.opensearch.cluster.service.ClusterService;
import org.opensearch.common.settings.ClusterSettings;
import org.opensearch.common.settings.Settings;
import org.opensearch.core.action.ActionListener;
import org.opensearch.test.OpenSearchTestCase;
import org.opensearch.threadpool.ThreadPool;
import org.opensearch.transport.client.AdminClient;
import org.opensearch.transport.client.Client;
import org.opensearch.transport.client.IndicesAdminClient;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Tests that cancellation during the COMPLETING phase is handled correctly.
 * Cancel is always allowed — even after cutover starts — because
 * {@code onCancelling} rolls back the alias swap (idempotent).
 */
@ThreadLeakScope(ThreadLeakScope.Scope.NONE)
public class MigrationCoordinatorCutoverCancelTests extends OpenSearchTestCase {

    private Client mockClient;
    private ClusterService mockClusterService;
    private ThreadPool mockThreadPool;
    private MigrationDocumentService mockMigrationDocumentService;

    @Override
    public void setUp() throws Exception {
        super.setUp();
        mockClient = mock(Client.class);
        mockClusterService = mock(ClusterService.class);
        mockThreadPool = mock(ThreadPool.class);
        mockMigrationDocumentService = mock(MigrationDocumentService.class);

        AdminClient mockAdminClient = mock(AdminClient.class);
        IndicesAdminClient mockIndicesAdmin = mock(IndicesAdminClient.class);
        when(mockClient.admin()).thenReturn(mockAdminClient);
        when(mockAdminClient.indices()).thenReturn(mockIndicesAdmin);

        // updateSettings → succeed immediately
        doAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            ActionListener<AcknowledgedResponse> listener = invocation.getArgument(1);
            listener.onResponse(new AcknowledgedResponse(true) {
            });
            return null;
        }).when(mockIndicesAdmin).updateSettings(any(UpdateSettingsRequest.class), any());

        // aliases → succeed immediately
        doAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            ActionListener<AcknowledgedResponse> listener = invocation.getArgument(1);
            listener.onResponse(new AcknowledgedResponse(true) {
            });
            return null;
        }).when(mockIndicesAdmin).aliases(any(IndicesAliasesRequest.class), any());

        ThreadPool.Cancellable mockCancellable = mock(ThreadPool.Cancellable.class);
        when(mockThreadPool.scheduleWithFixedDelay(any(), any(), any())).thenReturn(mockCancellable);

        ClusterState mockState = mock(ClusterState.class);
        when(mockClusterService.state()).thenReturn(mockState);
        when(mockClusterService.getSettings()).thenReturn(Settings.EMPTY);
        when(mockClusterService.getClusterSettings()).thenReturn(new ClusterSettings(Settings.EMPTY, new HashSet<>(AoscSettings.ALL)));

        // Mock metadata service
        when(mockMigrationDocumentService.createMigrationDocument(any(MigrationDocument.class))).thenReturn(
            CompletableFuture.completedFuture(null)
        );
        when(mockMigrationDocumentService.persistFinalState(any(), any())).thenReturn(CompletableFuture.completedFuture(null));
    }

    /**
     * Cancel signal set BEFORE the SM runs the COMPLETING handler.
     * Expected: coordinator transitions to CANCELLING.
     */
    public void testCancelBeforeCutoverStartsTransitionsToCancelling() throws Exception {
        AoscMigrationsClusterState.Entry entry = completingEntry("cancel-before");

        MigrationCoordinator coordinator = new MigrationCoordinator(
            AoscLogger.create(MigrationCoordinator.class),
            "cancel-before",
            CoordinatorPhase.COMPLETING,
            entry,
            mockClient,
            mockClusterService,
            mockThreadPool,
            mockMigrationDocumentService,
            () -> {}
        );

        // Cancel FIRST — before starting the SM
        coordinator.cancel();

        assertBusy(() -> {
            CoordinatorPhase phase = coordinator.phase();
            assertTrue(
                "Expected CANCELLING or CANCELLED but got: " + phase,
                phase == CoordinatorPhase.CANCELLING || phase == CoordinatorPhase.CANCELLED
            );
        }, 5, TimeUnit.SECONDS);

        coordinator.close();
    }

    /**
     * Cancel signal arrives AFTER cutover has started via start().
     * Expected: cancel is accepted — coordinator transitions to CANCELLING/CANCELLED.
     */
    public void testCancelAfterCutoverStartsTransitionsToCancelling() throws Exception {
        AoscMigrationsClusterState.Entry entry = completingEntry("cancel-after");

        MigrationCoordinator coordinator = new MigrationCoordinator(
            AoscLogger.create(MigrationCoordinator.class),
            "cancel-after",
            CoordinatorPhase.COMPLETING,
            entry,
            mockClient,
            mockClusterService,
            mockThreadPool,
            mockMigrationDocumentService,
            () -> {}
        );

        // Start the SM — triggers onCompleting which starts async cutover
        coordinator.start();

        // Give it a moment to start
        Thread.sleep(50);

        // Now cancel — should be accepted even though cutover may be in-flight
        coordinator.cancel();

        assertBusy(() -> {
            CoordinatorPhase phase = coordinator.phase();
            assertTrue(
                "Expected CANCELLING or CANCELLED but got: " + phase,
                phase == CoordinatorPhase.CANCELLING || phase == CoordinatorPhase.CANCELLED
            );
        }, 5, TimeUnit.SECONDS);

        coordinator.close();
    }

    private static AoscMigrationsClusterState.Entry completingEntry(String migrationId) {
        Map<Integer, AoscMigrationsClusterState.ShardMigrationClusterState> shards = new HashMap<>();
        shards.put(
            0,
            AoscMigrationsClusterState.ShardMigrationClusterState.builder()
                .phase(ShardPhase.COMPLETED)
                .lastReplayedSeqNo(100L)
                .backfillCutoffSeqNo(100L)
                .failure(null)
                .meta(MigrationMetadata.EMPTY)
                .build()
        );
        return AoscMigrationsClusterState.Entry.builder()
            .migrationId(migrationId)
            .sourceIndex("source-idx")
            .targetIndex("target-idx")
            .alias("my-alias")
            .transformScript(new InlineTransformScript("ctx._source.version = 2", null))
            .options(AoscTestUtil.defaultMigrationOptions())
            .phase(CoordinatorPhase.COMPLETING)
            .routingMode(ShardRoutingMode.BULK_API)
            .startTimeMillis(System.currentTimeMillis())
            .shards(shards)
            .failure(null)
            .meta(MigrationMetadata.EMPTY)
            .build();
    }
}
