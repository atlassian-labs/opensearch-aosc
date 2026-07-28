/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */
package com.atlassian.opensearch.aosc;

import com.atlassian.opensearch.aosc.action.cancel.CancelMigrationAction;
import com.atlassian.opensearch.aosc.action.cancel.TransportCancelMigrationAction;
import com.atlassian.opensearch.aosc.action.cleanup.CleanupLeasesAction;
import com.atlassian.opensearch.aosc.action.cleanup.TransportCleanupLeasesAction;
import com.atlassian.opensearch.aosc.action.clear.ClearClusterStateAction;
import com.atlassian.opensearch.aosc.action.clear.TransportClearClusterStateAction;
import com.atlassian.opensearch.aosc.action.list.ListMigrationsAction;
import com.atlassian.opensearch.aosc.action.list.TransportListMigrationsAction;
import com.atlassian.opensearch.aosc.action.start.StartMigrationAction;
import com.atlassian.opensearch.aosc.action.start.TransportStartMigrationAction;
import com.atlassian.opensearch.aosc.action.status.GetMigrationStatusAction;
import com.atlassian.opensearch.aosc.action.status.TransportGetMigrationStatusAction;
import com.atlassian.opensearch.aosc.action.update.TransportUpdateShardMigrationStatusAction;
import com.atlassian.opensearch.aosc.action.update.UpdateShardMigrationStatusAction;
import com.atlassian.opensearch.aosc.model.AoscMigrationsClusterState;
import com.atlassian.opensearch.aosc.rest.RestCancelMigrationAction;
import com.atlassian.opensearch.aosc.rest.RestCleanupLeasesAction;
import com.atlassian.opensearch.aosc.rest.RestClearClusterStateAction;
import com.atlassian.opensearch.aosc.rest.RestGetMigrationStatusAction;
import com.atlassian.opensearch.aosc.rest.RestListMigrationsAction;
import com.atlassian.opensearch.aosc.rest.RestStartMigrationAction;
import com.atlassian.opensearch.aosc.service.coordinator.AoscCoordinatorService;
import com.atlassian.opensearch.aosc.service.coordinator.MigrationDocumentService;
import com.atlassian.opensearch.aosc.service.worker.AoscShardService;
import com.atlassian.opensearch.aosc.transform.TransformFactory;
import com.atlassian.opensearch.aosc.utils.AoscLogger;

import org.opensearch.action.ActionRequest;
import org.opensearch.cluster.ClusterState;
import org.opensearch.cluster.NamedDiff;
import org.opensearch.cluster.metadata.IndexNameExpressionResolver;
import org.opensearch.cluster.node.DiscoveryNodes;
import org.opensearch.cluster.service.ClusterService;
import org.opensearch.common.settings.ClusterSettings;
import org.opensearch.common.settings.IndexScopedSettings;
import org.opensearch.common.settings.Setting;
import org.opensearch.common.settings.Settings;
import org.opensearch.common.settings.SettingsFilter;
import org.opensearch.common.unit.TimeValue;
import org.opensearch.core.ParseField;
import org.opensearch.core.action.ActionResponse;
import org.opensearch.core.common.io.stream.NamedWriteableRegistry;
import org.opensearch.core.xcontent.NamedXContentRegistry;
import org.opensearch.env.Environment;
import org.opensearch.env.NodeEnvironment;
import org.opensearch.index.IndexModule;
import org.opensearch.indices.IndicesService;
import org.opensearch.indices.SystemIndexDescriptor;
import org.opensearch.plugins.ActionPlugin;
import org.opensearch.plugins.Plugin;
import org.opensearch.plugins.SystemIndexPlugin;
import org.opensearch.repositories.RepositoriesService;
import org.opensearch.rest.RestController;
import org.opensearch.rest.RestHandler;
import org.opensearch.script.ScriptService;
import org.opensearch.threadpool.ExecutorBuilder;
import org.opensearch.threadpool.ScalingExecutorBuilder;
import org.opensearch.threadpool.ThreadPool;
import org.opensearch.transport.client.Client;
import org.opensearch.watcher.ResourceWatcherService;

import java.io.Closeable;
import java.io.IOException;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.function.Supplier;

/**
 * AOSC (Automatic Online Schema Change) — OpenSearch plugin for live index
 * schema migrations without downtime using distributed per-shard workers.
 *
 * <p>Architecture: ClusterManager-based coordinator + per-shard workers on data nodes,
 * modeled after OpenSearch's SnapshotsService + SnapshotShardsService pattern.</p>
 *
 * <p><b>Thread model:</b></p>
 * <ul>
 *   <li>{@link AoscCoordinatorService} runs as a {@code ClusterStateApplier} on ClusterManager nodes only</li>
 *   <li>{@link AoscShardService} runs as a {@code ClusterStateListener} + {@code IndexEventListener} on all data nodes</li>
 *   <li>Each {@code MigrationCoordinator} and {@code ShardMigrationWorker} has a dedicated single-thread
 *       executor ({@code smExecutor}) serializing all SM transitions</li>
 *   <li>Engine callbacks (backfill, replay) run on the AOSC thread pool</li>
 * </ul>
 */
public class AoscPlugin extends Plugin implements ActionPlugin, SystemIndexPlugin, Closeable {

    /** Name of the dedicated AOSC thread pool used by engines and async callbacks. */
    public static final String AOSC_THREAD_POOL = "aosc";

    /** Plugin descriptor name. Extension plugins may reuse this value when they replace the base plugin. */
    public static final String PLUGIN_NAME = "opensearch-aosc";

    /** Held for close() — coordinator service manages per-migration coordinators on CM nodes. */
    private volatile AoscCoordinatorService coordinatorService;

    /** Held for close() — shard service manages per-shard workers on data nodes. */
    private volatile AoscShardService shardService;

    @Override
    public Collection<Object> createComponents(
        Client client,
        ClusterService clusterService,
        ThreadPool threadPool,
        ResourceWatcherService resourceWatcherService,
        ScriptService scriptService,
        NamedXContentRegistry xContentRegistry,
        Environment environment,
        NodeEnvironment nodeEnvironment,
        NamedWriteableRegistry namedWriteableRegistry,
        IndexNameExpressionResolver indexNameExpressionResolver,
        Supplier<RepositoriesService> repositoriesServiceSupplier
    ) {
        // Root logger — all migration-scoped components receive this and compose child loggers
        AoscLogger rootLogger = AoscLogger.create(AoscPlugin.class);

        // 1. Leaf services — no dependencies on each other
        MigrationDocumentService migrationDocumentService = new MigrationDocumentService(rootLogger, client);
        TransformFactory transformFactory = createTransformFactory(scriptService);

        // 2. Coordinator service (ClusterManager-only, ClusterStateApplier)
        this.coordinatorService = new AoscCoordinatorService(rootLogger, client, clusterService, threadPool, migrationDocumentService);

        // 3. Shard service (all data nodes, ClusterStateListener + IndexEventListener)
        // IndicesService is not available here — passed as a Supplier resolved lazily
        // via onIndexModule(). The shardService will use it in clusterChanged() only
        // after the first IndexModule has been processed (i.e., after node startup).
        this.shardService = new AoscShardService(rootLogger, clusterService, client, threadPool, transformFactory);

        return Arrays.asList(migrationDocumentService, transformFactory, coordinatorService, shardService);
    }

    /**
     * Registers all AOSC transport actions with the OpenSearch action registry.
     *
     * <p>Migration lifecycle actions are routed to the ClusterManager node.
     * The shard update action ({@link UpdateShardMigrationStatusAction}) is also routed
     * to the ClusterManager so that {@link AoscCoordinatorService} can accept the update.</p>
     */
    @Override
    public List<RestHandler> getRestHandlers(
        Settings settings,
        RestController restController,
        ClusterSettings clusterSettings,
        IndexScopedSettings indexScopedSettings,
        SettingsFilter settingsFilter,
        IndexNameExpressionResolver indexNameExpressionResolver,
        Supplier<DiscoveryNodes> nodesInCluster
    ) {
        return Arrays.asList(
            new RestStartMigrationAction(),
            new RestCancelMigrationAction(),
            new RestGetMigrationStatusAction(),
            new RestListMigrationsAction(),
            new RestCleanupLeasesAction(),
            new RestClearClusterStateAction()
        );
    }

    @Override
    public List<ActionHandler<? extends ActionRequest, ? extends ActionResponse>> getActions() {
        return Arrays.asList(
            // Migration lifecycle
            new ActionHandler<>(StartMigrationAction.INSTANCE, TransportStartMigrationAction.class),
            new ActionHandler<>(CancelMigrationAction.INSTANCE, TransportCancelMigrationAction.class),
            new ActionHandler<>(GetMigrationStatusAction.INSTANCE, TransportGetMigrationStatusAction.class),

            // Worker → coordinator shard status updates — routed to ClusterManager
            new ActionHandler<>(UpdateShardMigrationStatusAction.INSTANCE, TransportUpdateShardMigrationStatusAction.class),
            new ActionHandler<>(ListMigrationsAction.INSTANCE, TransportListMigrationsAction.class),

            // Operator-facing emergency lease cleanup (F006)
            new ActionHandler<>(CleanupLeasesAction.INSTANCE, TransportCleanupLeasesAction.class),

            // Operator-facing cluster state cleanup
            new ActionHandler<>(ClearClusterStateAction.INSTANCE, TransportClearClusterStateAction.class)
        );
    }

    /**
     * Registers the AOSC thread pool for use by engine callbacks and async operations.
     *
     * <p>Uses a scaling executor (1–32 threads) with a 5-minute keep-alive. Engines and
     * async callbacks that need to escape the cluster-applier or transport threads use this pool.</p>
     */
    @Override
    public List<ExecutorBuilder<?>> getExecutorBuilders(Settings settings) {
        return Collections.singletonList(new ScalingExecutorBuilder(AOSC_THREAD_POOL, 1, 32, TimeValue.timeValueMinutes(5)));
    }

    @Override
    public List<Setting<?>> getSettings() {
        return AoscSettings.ALL;
    }

    /**
     * Registers the {@code .aosc-migrations} system index descriptor.
     *
     * <p>This index stores the parent migration document and per-shard progress documents
     * (Tier 1 persistence). It is managed exclusively by the AOSC plugin.</p>
     */
    @Override
    public Collection<SystemIndexDescriptor> getSystemIndexDescriptors(Settings settings) {
        return Collections.singletonList(new SystemIndexDescriptor(".aosc-migrations", "AOSC migration state and per-shard progress"));
    }

    /**
     * Registers {@link AoscShardService} as an {@link org.opensearch.index.shard.IndexEventListener}
     * on every index module, so it receives shard lifecycle events (start, close).
     *
     * <p>Also captures the {@link IndicesService} reference from the index module context
     * for use in {@code clusterChanged()} to look up local primary shards.</p>
     */
    @Override
    public void onIndexModule(IndexModule indexModule) {
        if (shardService != null) {
            indexModule.addIndexEventListener(shardService);
        }
    }

    @Override
    public List<NamedWriteableRegistry.Entry> getNamedWriteables() {
        return List.of(
            new NamedWriteableRegistry.Entry(ClusterState.Custom.class, AoscMigrationsClusterState.TYPE, AoscMigrationsClusterState::new),
            new NamedWriteableRegistry.Entry(NamedDiff.class, AoscMigrationsClusterState.TYPE, AoscMigrationsClusterState::readDiffFrom)
        );
    }

    @Override
    public List<NamedXContentRegistry.Entry> getNamedXContent() {
        return List.of(new NamedXContentRegistry.Entry(ClusterState.Custom.class, new ParseField(AoscMigrationsClusterState.TYPE), p -> {
            throw new UnsupportedOperationException("AoscMigrationsClusterState fromXContent not yet implemented");
        }));
    }

    /**
     * Closes all AOSC services gracefully on node shutdown.
     *
     * <p>Shard service is closed first (stops workers), then coordinator (stops coordinators).
     * Both are null-checked in case {@link #createComponents} was never called.</p>
     */
    @Override
    public void close() throws IOException {
        if (shardService != null) {
            shardService.close();
        }
        if (coordinatorService != null) {
            coordinatorService.close();
        }
    }

    /** Override to plug in a {@link TransformFactory} subclass. */
    protected TransformFactory createTransformFactory(ScriptService scriptService) {
        return new TransformFactory(scriptService);
    }

    @Override
    public String toString() {
        return "opensearch-aosc";
    }

}
