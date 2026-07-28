/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */
package com.atlassian.opensearch.aosc.action.start;

import com.atlassian.opensearch.aosc.action.start.validation.AsyncMigrationStartValidator;
import com.atlassian.opensearch.aosc.action.start.validation.DataLossConsentValidator;
import com.atlassian.opensearch.aosc.action.start.validation.IndexPreconditionsValidator;
import com.atlassian.opensearch.aosc.action.start.validation.IndicesExistValidator;
import com.atlassian.opensearch.aosc.action.start.validation.MigrationStartValidator;
import com.atlassian.opensearch.aosc.action.start.validation.PluginConsistencyValidator;
import com.atlassian.opensearch.aosc.action.start.validation.ResolveOptionsValidator;
import com.atlassian.opensearch.aosc.action.start.validation.TargetIndexEmptyValidator;
import com.atlassian.opensearch.aosc.action.start.validation.TransformFunctionValidator;
import com.atlassian.opensearch.aosc.action.start.validation.ValidationContext;
import com.atlassian.opensearch.aosc.action.start.validation.ValidationQueryValidator;
import com.atlassian.opensearch.aosc.model.MigrationRequest;
import com.atlassian.opensearch.aosc.service.coordinator.AoscCoordinatorService;
import com.atlassian.opensearch.aosc.transform.TransformFactory;
import com.atlassian.opensearch.aosc.utils.AsyncUtils;

import org.opensearch.action.support.ActionFilters;
import org.opensearch.action.support.clustermanager.TransportClusterManagerNodeAction;
import org.opensearch.client.Client;
import org.opensearch.cluster.ClusterState;
import org.opensearch.cluster.block.ClusterBlockException;
import org.opensearch.cluster.block.ClusterBlockLevel;
import org.opensearch.cluster.metadata.IndexMetadata;
import org.opensearch.cluster.metadata.IndexNameExpressionResolver;
import org.opensearch.cluster.service.ClusterService;
import org.opensearch.common.inject.Inject;
import org.opensearch.core.action.ActionListener;
import org.opensearch.core.common.io.stream.StreamInput;
import org.opensearch.threadpool.ThreadPool;
import org.opensearch.transport.TransportService;

import java.io.IOException;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;

/**
 * Transport handler for {@link StartMigrationAction}.
 *
 * <p>Extends {@link TransportClusterManagerNodeAction} so that the request is automatically
 * forwarded to the active cluster manager node, where the cluster state update task runs.
 */
public class TransportStartMigrationAction extends TransportClusterManagerNodeAction<StartMigrationRequest, StartMigrationResponse> {

    /**
     * Synchronous pre-flight validators, executed in order. Each fails fast by throwing
     * {@link IllegalArgumentException} / {@link IllegalStateException}; the original 90-line
     * inline validation block in {@link #clusterManagerOperation} has been decomposed into
     * these single-responsibility units (pure refactor — same checks, same order, same errors).
     */
    private static final List<MigrationStartValidator> SYNC_VALIDATORS = List.of(
        new IndicesExistValidator(),
        new ResolveOptionsValidator(),
        new DataLossConsentValidator(),
        new IndexPreconditionsValidator(),
        new TransformFunctionValidator()
    );

    /**
     * Asynchronous pre-flight validators, chained via {@link CompletableFuture#thenCompose}
     * after all sync validators pass. Order preserved from the original inline logic.
     */
    private static final List<AsyncMigrationStartValidator> ASYNC_VALIDATORS = List.of(
        new PluginConsistencyValidator(),
        new TargetIndexEmptyValidator(),
        new ValidationQueryValidator()
    );

    private final AoscCoordinatorService coordinatorService;
    private final ClusterService clusterService;
    private final Client client;
    private final TransformFactory transformFactory;

    @Inject
    public TransportStartMigrationAction(
        TransportService transportService,
        ClusterService clusterService,
        ThreadPool threadPool,
        ActionFilters actionFilters,
        IndexNameExpressionResolver indexNameExpressionResolver,
        AoscCoordinatorService coordinatorService,
        Client client,
        TransformFactory transformFactory
    ) {
        super(
            StartMigrationAction.NAME,
            transportService,
            clusterService,
            threadPool,
            actionFilters,
            StartMigrationRequest::new,
            indexNameExpressionResolver
        );
        this.coordinatorService = Objects.requireNonNull(coordinatorService, "coordinatorService");
        this.clusterService = Objects.requireNonNull(clusterService, "clusterService");
        this.client = Objects.requireNonNull(client, "client");
        this.transformFactory = Objects.requireNonNull(transformFactory, "transformFactory");
    }

    @Override
    protected String executor() {
        return ThreadPool.Names.SAME;
    }

    @Override
    protected StartMigrationResponse read(StreamInput in) throws IOException {
        return new StartMigrationResponse(in);
    }

    @Override
    protected void clusterManagerOperation(
        StartMigrationRequest request,
        ClusterState state,
        ActionListener<StartMigrationResponse> listener
    ) {
        MigrationRequest migReq = request.body();
        IndexMetadata sourceMeta = state.metadata().index(migReq.getSourceIndex());
        IndexMetadata targetMeta = state.metadata().index(migReq.getTargetIndex());

        ValidationContext ctx = ValidationContext.of(
            migReq,
            state,
            sourceMeta,
            targetMeta,
            transformFactory,
            clusterService.getClusterSettings(),
            client
        );

        // 1. Synchronous validators: fail-fast on the first error
        try {
            for (MigrationStartValidator v : SYNC_VALIDATORS) {
                v.validate(ctx);
            }
        } catch (Exception e) {
            listener.onFailure(e);
            return;
        }

        // 2. Asynchronous validators: chained via thenCompose
        CompletableFuture<Void> chain = CompletableFuture.completedFuture(null);
        for (AsyncMigrationStartValidator v : ASYNC_VALIDATORS) {
            chain = chain.thenCompose(ignored -> v.validate(ctx));
        }

        // 3. On all-pass, start the migration
        chain.whenComplete((ignored, ex) -> {
            if (ex != null) {
                listener.onFailure(toException(unwrap(ex)));
                return;
            }
            AsyncUtils.bridgeToListener(coordinatorService.startMigration(request), listener);
        });
    }

    /**
     * Unwrap {@link java.util.concurrent.CompletionException} so callers see the original
     * {@code IllegalArgumentException} / {@code IllegalStateException} thrown by a validator.
     */
    private static Throwable unwrap(Throwable t) {
        if (t instanceof java.util.concurrent.CompletionException && t.getCause() != null) {
            return t.getCause();
        }
        return t;
    }

    /**
     * {@link ActionListener#onFailure} requires an {@link Exception}; wrap any non-Exception
     * {@link Throwable} (e.g. {@link Error}) so the listener contract holds.
     */
    private static Exception toException(Throwable t) {
        return (t instanceof Exception) ? (Exception) t : new RuntimeException(t);
    }

    @Override
    protected ClusterBlockException checkBlock(StartMigrationRequest request, ClusterState state) {
        return state.blocks().globalBlockedException(ClusterBlockLevel.METADATA_WRITE);
    }
}
