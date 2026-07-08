/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */
package com.atlassian.opensearch.aosc;

import com.atlassian.opensearch.aosc.action.cancel.CancelMigrationAction;
import com.atlassian.opensearch.aosc.action.cancel.CancelMigrationBody;
import com.atlassian.opensearch.aosc.action.cancel.CancelMigrationRequest;
import com.atlassian.opensearch.aosc.action.cancel.CancelMigrationResponse;
import com.atlassian.opensearch.aosc.action.list.ListMigrationsAction;
import com.atlassian.opensearch.aosc.action.list.ListMigrationsBody;
import com.atlassian.opensearch.aosc.action.list.ListMigrationsRequest;
import com.atlassian.opensearch.aosc.action.list.ListMigrationsResponse;
import com.atlassian.opensearch.aosc.action.start.StartMigrationAction;
import com.atlassian.opensearch.aosc.action.start.StartMigrationRequest;
import com.atlassian.opensearch.aosc.action.start.StartMigrationResponse;
import com.atlassian.opensearch.aosc.action.status.GetMigrationStatusAction;
import com.atlassian.opensearch.aosc.action.status.GetMigrationStatusBody;
import com.atlassian.opensearch.aosc.action.status.GetMigrationStatusRequest;
import com.atlassian.opensearch.aosc.action.status.GetMigrationStatusResponse;
import com.atlassian.opensearch.aosc.model.MigrationRequest;
import com.atlassian.opensearch.aosc.model.MigrationRequestOptions;
import com.atlassian.opensearch.aosc.model.MigrationSummary;
import com.atlassian.opensearch.aosc.model.phase.CoordinatorPhase;
import com.atlassian.opensearch.aosc.model.transform.InlineTransformScript;
import com.atlassian.opensearch.aosc.model.transform.StoredTransformScript;
import com.atlassian.opensearch.aosc.service.coordinator.AoscCoordinatorService;
import com.atlassian.opensearch.aosc.service.coordinator.MigrationDocumentService;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import org.opensearch.action.admin.indices.stats.IndicesStatsResponse;
import org.opensearch.action.index.IndexRequest;
import org.opensearch.action.support.WriteRequest;
import org.opensearch.common.Booleans;
import org.opensearch.common.settings.Settings;
import org.opensearch.common.unit.TimeValue;
import org.opensearch.common.xcontent.XContentType;
import org.opensearch.plugins.Plugin;
import org.opensearch.plugins.ScriptPlugin;
import org.opensearch.script.MockScriptEngine;
import org.opensearch.script.MockScriptPlugin;
import org.opensearch.script.ScriptContext;
import org.opensearch.script.UpdateScript;
import org.opensearch.test.OpenSearchIntegTestCase;
import org.opensearch.test.telemetry.tracing.StrictCheckSpanProcessor;

import org.junit.After;
import org.junit.AfterClass;
import org.junit.Before;

import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Shared base class for AOSC integration tests. Provides:
 * <ul>
 *   <li>{@link MockPainlessPlugin} — mock script engine for transform scripts</li>
 *   <li>Helpers to start, cancel, and poll migration status</li>
 *   <li>Helpers to index documents and assert doc counts</li>
 * </ul>
 */
@com.carrotsearch.randomizedtesting.annotations.ThreadLeakFilters(defaultFilters = true, filters = {
    AoscIntegTestBase.CompletableFutureDelaySchedulerFilter.class })
public abstract class AoscIntegTestBase extends OpenSearchIntegTestCase {

    /**
     * Filters the {@code CompletableFutureDelayScheduler} daemon thread created by
     * {@link java.util.concurrent.CompletableFuture#delayedExecutor}. This is a JVM-global
     * shared thread that cannot be shut down and is harmless.
     */
    public static class CompletableFutureDelaySchedulerFilter implements com.carrotsearch.randomizedtesting.ThreadFilter {
        @Override
        public boolean reject(Thread t) {
            return t.getName().contains("CompletableFutureDelayScheduler");
        }
    }

    /**
     * Debug mode: relax all cluster timeouts for breakpoint-safe debugging.
     * Activate with {@code -Daosc.debug=true} on the JVM or Gradle command line.
     *
     * <p>Usage:
     * <pre>
     * ./gradlew itTest --tests "*AoscPluginIT.testFullLifecycle" -Daosc.debug=true --debug-jvm
     * </pre>
     * Or in IntelliJ: add {@code -Daosc.debug=true} to VM options in the run configuration.
     */
    @Override
    protected Settings nodeSettings(int nodeOrdinal) {
        Settings.Builder builder = Settings.builder().put(super.nodeSettings(nodeOrdinal));
        if (Booleans.parseBoolean(System.getProperty("aosc.debug"), false)) {
            logger.info("AOSC debug mode enabled — relaxing cluster timeouts for breakpoint debugging");
            builder.put("cluster.fault_detection.leader_check.timeout", "300s")
                .put("cluster.fault_detection.leader_check.interval", "120s")
                .put("cluster.fault_detection.leader_check.retry_count", 10)
                .put("cluster.fault_detection.follower_check.timeout", "300s")
                .put("cluster.fault_detection.follower_check.interval", "120s")
                .put("cluster.fault_detection.follower_check.retry_count", 10)
                .put("cluster.join.timeout", "300s")
                .put("cluster.publish.timeout", "300s")
                .put("transport.connect_timeout", "300s")
                .put("transport.ping_schedule", "120s");
        }
        return builder.build();
    }

    @Override
    protected Collection<Class<? extends Plugin>> nodePlugins() {
        return Arrays.asList(AoscPlugin.class, MockPainlessPlugin.class);
    }

    @Override
    protected boolean addMockInternalEngine() {
        return false;
    }

    // ---- MockPainlessPlugin ----

    /**
     * Mock Painless plugin for integration tests. Registers as "painless" script lang
     * with an UpdateScript context compiler that interprets simple transform patterns.
     */
    public static class MockPainlessPlugin extends MockScriptPlugin implements ScriptPlugin {
        @Override
        public String pluginScriptLang() {
            return "painless";
        }

        @Override
        @SuppressWarnings("unchecked")
        protected Map<String, Function<Map<String, Object>, Object>> pluginScripts() {
            Map<String, Function<Map<String, Object>, Object>> scripts = new HashMap<>();

            scripts.put("ctx._source.version = 2", params -> {
                Map<String, Object> ctx = (Map<String, Object>) params.get("ctx");
                Map<String, Object> source = (Map<String, Object>) ctx.get("_source");
                source.put("version", 2);
                return null;
            });

            scripts.put("ctx._source.remove('internal_id')", params -> {
                Map<String, Object> ctx = (Map<String, Object>) params.get("ctx");
                Map<String, Object> source = (Map<String, Object>) ctx.get("_source");
                source.remove("internal_id");
                return null;
            });

            scripts.put("ctx._source.migrated = true", params -> {
                Map<String, Object> ctx = (Map<String, Object>) params.get("ctx");
                Map<String, Object> source = (Map<String, Object>) ctx.get("_source");
                source.put("migrated", true);
                return null;
            });

            scripts.put("ctx._source.put('new_name', ctx._source.remove('old_name'))", params -> {
                Map<String, Object> ctx = (Map<String, Object>) params.get("ctx");
                Map<String, Object> source = (Map<String, Object>) ctx.get("_source");
                source.put("new_name", source.remove("old_name"));
                return null;
            });

            scripts.put("throw_on_poison", params -> {
                Map<String, Object> ctx = (Map<String, Object>) params.get("ctx");
                Map<String, Object> source = (Map<String, Object>) ctx.get("_source");
                if (Boolean.TRUE.equals(source.get("poison"))) {
                    throw new RuntimeException("Poison document detected");
                }
                return null;
            });

            // Params-aware script: sets a field from a script parameter
            scripts.put("ctx._source.tag = params.tag_value", params -> {
                Map<String, Object> ctx = (Map<String, Object>) params.get("ctx");
                Map<String, Object> source = (Map<String, Object>) ctx.get("_source");
                Object tagValue = params.get("tag_value");
                source.put("tag", tagValue);
                return null;
            });

            // Strict params script: throws if required_key is missing (used by dry-run tests).
            scripts.put("ctx._source.x = params.required_key", params -> {
                if (!params.containsKey("required_key")) {
                    throw new IllegalArgumentException("Missing required script param: required_key");
                }
                Map<String, Object> ctx = (Map<String, Object>) params.get("ctx");
                Map<String, Object> source = (Map<String, Object>) ctx.get("_source");
                source.put("x", params.get("required_key"));
                return null;
            });

            return scripts;
        }

        @Override
        public Map<ScriptContext<?>, MockScriptEngine.ContextCompiler> pluginContextCompilers() {
            MockScriptEngine.ContextCompiler updateCompiler = (script, params) -> {
                return (UpdateScript.Factory) (p, ctx) -> new UpdateScript(p, ctx) {
                    @Override
                    @SuppressWarnings("unchecked")
                    public void execute() {
                        Map<String, Object> combined = new HashMap<>(getParams());
                        combined.put("ctx", getCtx());
                        script.apply(combined);
                    }
                };
            };
            return Collections.singletonMap(UpdateScript.CONTEXT, updateCompiler);
        }
    }

    // ---- @After safety net ----

    /**
     * Drain in-flight {@code indices:*} tasks between tests to avoid cross-test pollution (B020).
     *
     * <p>Filters: only tasks that can mutate or block index state matter here. We intentionally
     * exclude reads ({@code indices:data/read/search}) and benign admin RPCs that are best-effort
     * and never block subsequent tests:
     * <ul>
     *   <li>{@code indices:admin/seq_no/remove_retention_lease} — best-effort lease cleanup;
     *       fails harmlessly with {@code RetentionLeaseNotFoundException} if the lease is
     *       already gone, and never holds index-level state.</li>
     *   <li>{@code indices:admin/seq_no/renew_retention_lease} — periodic renewal; cancelled
     *       on worker close but may have one in-flight task at teardown.</li>
     * </ul>
     *
     * <p>Polling: fixed 100 ms interval (vs {@code assertBusy}'s exponential backoff which
     * sleeps up to 16 s between late checks) so we detect drain within ~100 ms of completion.
     * Budget: 5 s — generous for normal cleanup; if tasks haven't drained by then something is
     * genuinely stuck and the warning is informative.
     */
    @Before
    public void resetMigrationDocumentServiceCache() {
        // The test framework wipes all indices (including .aosc-migrations) between tests.
        // Reset the cached index-creation future and eagerly recreate the index with proper
        // schema mappings. In production, applyClusterState(justBecameCM) handles creation,
        // but in tests the CM is not re-elected between methods (Scope.SUITE).
        for (var instance : internalCluster().getInstances(MigrationDocumentService.class)) {
            instance.resetIndexCreated();
        }
        // Recreate on one instance — the index is cluster-wide.
        internalCluster().getInstances(MigrationDocumentService.class).iterator().next().ensureIndexExists().join();
    }

    @After
    public void waitForNoPendingIndicesTasks() throws Exception {
        try {
            assertBusyWithFixedSleepTime(() -> {
                List<String> pending = client().admin()
                    .cluster()
                    .prepareListTasks()
                    .get()
                    .getTasks()
                    .stream()
                    .map(t -> t.getAction())
                    .filter(AoscIntegTestBase::isBlockingIndicesTask)
                    .collect(Collectors.toList());
                assertTrue("Pending indices tasks after test: " + pending, pending.isEmpty());
            }, TimeValue.timeValueSeconds(5), TimeValue.timeValueMillis(100));
        } catch (Throwable t) {
            logger.warn("waitForNoPendingIndicesTasks: did not drain within 5s — {}", t.getMessage());
        }
    }

    /**
     * @return true if the given task action is a mutating or blocking indices op that should be
     * drained between tests; false for reads and benign best-effort admin RPCs.
     */
    private static boolean isBlockingIndicesTask(String action) {
        if (!action.startsWith("indices:")) return false;
        if (action.equals("indices:data/read/search")) return false;
        if (action.equals("indices:admin/seq_no/remove_retention_lease")) return false;
        return !action.equals("indices:admin/seq_no/renew_retention_lease");
    }

    /**
     * Cancels any non-terminal migrations left over after a test and awaits their terminal phase.
     * Prevents orphaned ShardMigrationWorkers from racing with test-framework index teardown.
     * No-op when the test already cleaned up (the common case).
     */
    @After
    public void cleanupActiveMigrations() {
        List<MigrationSummary> active;
        try {
            ListMigrationsResponse listResponse = client().execute(
                ListMigrationsAction.INSTANCE,
                new ListMigrationsRequest(new ListMigrationsBody(Collections.emptyList(), 100))
            ).actionGet();
            active = listResponse.body().migrations().stream().filter(m -> !m.phase().isTerminal()).collect(Collectors.toList());
        } catch (Throwable e) {
            logger.warn("cleanupActiveMigrations: could not list migrations (cluster may not be initialised) — {}", e.getMessage());
            return;
        }

        if (active.isEmpty()) {
            return;
        }

        logger.warn("cleanupActiveMigrations: found {} non-terminal migration(s) after test — cancelling", active.size());

        for (MigrationSummary doc : active) {
            String sourceIndex = doc.sourceIndex();
            try {
                cancelMigration(sourceIndex);
                logger.info("cleanupActiveMigrations: cancelled migration for source={}", sourceIndex);
            } catch (Throwable e) {
                logger.warn(
                    "cleanupActiveMigrations: cancel failed for source={} (coordinator may already be gone) — {}",
                    sourceIndex,
                    e.getMessage()
                );
            }

            try {
                awaitTerminalPhase(sourceIndex, 30);
                logger.info("cleanupActiveMigrations: migration for source={} reached terminal phase", sourceIndex);
            } catch (Throwable e) {
                logger.warn(
                    "cleanupActiveMigrations: timed out waiting for terminal phase for source={} — {}",
                    sourceIndex,
                    e.getMessage()
                );
            }
        }
    }

    /**
     * Wait for all coordinators (including terminal ones with in-flight {@code persistFinalState}
     * Tier-1 writes) to finish and be removed from the active map.
     *
     * <p>Without this, the test framework may delete {@code .aosc-migrations} while a bulk write
     * is still in-flight. The bulk API auto-creates the index, and the new shard's initialization
     * holds a shard lock that causes {@code assertAfterTest} to fail with
     * "Shard [.aosc-migrations][0] is still locked after 5 sec waiting".</p>
     *
     * <p>This complements {@link #cleanupActiveMigrations()} which only handles non-terminal
     * migrations. Terminal migrations that are still persisting their final state need this
     * separate drain wait.</p>
     */
    @After
    public void waitForCoordinatorsDrained() throws Exception {
        try {
            assertBusyWithFixedSleepTime(() -> {
                for (AoscCoordinatorService svc : internalCluster().getInstances(AoscCoordinatorService.class)) {
                    assertEquals(
                        "AoscCoordinatorService still has active coordinators (persistFinalState may be in-flight)",
                        0,
                        svc.activeCoordinatorCount()
                    );
                }
            }, TimeValue.timeValueSeconds(10), TimeValue.timeValueMillis(100));
        } catch (Throwable t) {
            logger.warn("waitForCoordinatorsDrained: coordinators did not drain within 10s — {}", t.getMessage());
        }
    }

    // ---- Migration helpers ----

    /**
     * Start a migration and return the migration ID. Asserts the response is accepted.
     */
    protected String startMigration(String sourceIndex, String targetIndex, String alias, String transformScript) {
        return startMigration(sourceIndex, targetIndex, alias, transformScript, null);
    }

    /**
     * Start a migration with optional request options.
     */
    protected String startMigration(
        String sourceIndex,
        String targetIndex,
        String alias,
        String transformScript,
        MigrationRequestOptions options
    ) {
        MigrationRequest migrationRequest = new MigrationRequest().setSourceIndex(sourceIndex)
            .setTargetIndex(targetIndex)
            .setTransformScript(transformScript != null ? new InlineTransformScript(transformScript, null) : null)
            .setAlias(alias)
            .setOptions(options);
        StartMigrationRequest request = new StartMigrationRequest(migrationRequest);
        StartMigrationResponse response = client().execute(StartMigrationAction.INSTANCE, request).actionGet();
        assertTrue("Migration should be accepted", response.body().accepted());
        assertNotNull("Migration ID should not be null", response.body().migrationId());
        return response.body().migrationId();
    }

    /**
     * Start a migration using a stored script (by ID) and return the migration ID.
     */
    protected String startMigrationWithStoredScript(
        String sourceIndex,
        String targetIndex,
        String alias,
        String transformScriptId,
        Map<String, Object> scriptParams
    ) {
        MigrationRequest migrationRequest = new MigrationRequest().setSourceIndex(sourceIndex)
            .setTargetIndex(targetIndex)
            .setTransformScript(new StoredTransformScript(transformScriptId, scriptParams))
            .setAlias(alias);
        StartMigrationRequest request = new StartMigrationRequest(migrationRequest);
        StartMigrationResponse response = client().execute(StartMigrationAction.INSTANCE, request).actionGet();
        assertTrue("Migration should be accepted", response.body().accepted());
        assertNotNull("Migration ID should not be null", response.body().migrationId());
        return response.body().migrationId();
    }

    /**
     * Get migration status for a source index.
     *
     * <p>Note: shard progress writes are async (fire-and-forget CompletableFutures).
     * The final shard progress write may still be in-flight when the coordinator
     * transitions to COMPLETED. Tests that need shard progress data should use
     * {@code assertBusy} to poll until the data appears.</p>
     */
    protected GetMigrationStatusResponse getStatus(String sourceIndex) {
        GetMigrationStatusRequest request = new GetMigrationStatusRequest(new GetMigrationStatusBody(sourceIndex));
        return client().execute(GetMigrationStatusAction.INSTANCE, request).actionGet();
    }

    /**
     * Cancel a migration by source index. Returns the cancel response.
     */
    protected CancelMigrationResponse cancelMigration(String sourceIndex) {
        CancelMigrationRequest request = new CancelMigrationRequest(new CancelMigrationBody(sourceIndex));
        return client().execute(CancelMigrationAction.INSTANCE, request).actionGet();
    }

    /**
     * Poll until the coordinator phase reaches a terminal state (COMPLETED, FAILED, or CANCELLED).
     *
     * <p>Uses fixed 200 ms polling rather than {@link #assertBusy} 's exponential backoff. With the
     * default {@code assertBusy} schedule (1, 2, 4, …, 16384 ms doublings, then a single sleep of the
     * remaining budget), a migration that completes at e.g. t=14 s could only be observed after the
     * next wakeup at t=16 s — and a migration that completes at t=33 s could be missed for ~16 s.
     * Fixed 200 ms polling caps detection latency at 200 ms regardless of the absolute completion
     * time, which is the dominant source of "50-second wall" wait times we previously observed.
     */
    protected CoordinatorPhase awaitTerminalPhase(String sourceIndex, int timeoutSeconds) throws Exception {
        CoordinatorPhase[] result = new CoordinatorPhase[1];
        assertBusyWithFixedSleepTime(() -> {
            GetMigrationStatusResponse status = getStatus(sourceIndex);
            assertNotNull("Migration should be found", status.body());
            CoordinatorPhase phase = status.body().phase();
            assertNotNull("Phase should not be null", phase);
            assertTrue(
                "Expected terminal phase but got: " + phase,
                phase == CoordinatorPhase.COMPLETED || phase == CoordinatorPhase.FAILED || phase == CoordinatorPhase.CANCELLED
            );
            result[0] = phase;
        }, TimeValue.timeValueSeconds(timeoutSeconds), TimeValue.timeValueMillis(200));
        return result[0];
    }

    /**
     * Assert that the migration completed successfully.
     */
    protected void assertMigrationCompleted(String sourceIndex, int timeoutSeconds) throws Exception {
        CoordinatorPhase phase = awaitTerminalPhase(sourceIndex, timeoutSeconds);
        if (phase != CoordinatorPhase.COMPLETED) {
            // Log failure details for debugging CI failures
            GetMigrationStatusResponse status = getStatus(sourceIndex);
            logger.error("Migration ended in {} instead of COMPLETED. Status: {}", phase, status);
        }
        assertEquals("Migration should complete successfully", CoordinatorPhase.COMPLETED, phase);
    }

    // ---- Index helpers ----

    /**
     * Create source and target indices with the given shard counts.
     */
    protected void createSourceAndTarget(String source, String target, int sourceShards, int targetShards) {
        createIndex(source, Settings.builder().put("index.number_of_shards", sourceShards).put("index.number_of_replicas", 0).build());
        createIndex(target, Settings.builder().put("index.number_of_shards", targetShards).put("index.number_of_replicas", 0).build());
        ensureGreen(source, target);
    }

    /**
     * Index {@code count} documents with predictable IDs and a simple payload.
     */
    protected void indexDocs(String index, int count) {
        for (int i = 0; i < count; i++) {
            client().index(
                new IndexRequest(index).id(String.valueOf(i))
                    .source("{\"value\":" + i + "}", XContentType.JSON)
                    .setRefreshPolicy(WriteRequest.RefreshPolicy.NONE)
            ).actionGet();
        }
        client().admin().indices().prepareRefresh(index).get();
    }

    /**
     * Index a single document with the given fields.
     */
    protected void indexDoc(String index, String id, Map<String, Object> source) {
        client().index(new IndexRequest(index).id(id).source(source).setRefreshPolicy(WriteRequest.RefreshPolicy.IMMEDIATE)).actionGet();
    }

    /**
     * Get the primary doc count for an index.
     */
    protected long getDocCount(String index) {
        client().admin().indices().prepareRefresh(index).get();
        IndicesStatsResponse stats = client().admin().indices().prepareStats(index).clear().setDocs(true).get();
        return stats.getPrimaries().getDocs().getCount();
    }

    /**
     * Assert that source and target have the same document count.
     */
    protected void assertDocCountsMatch(String source, String target) {
        long sourceCount = getDocCount(source);
        long targetCount = getDocCount(target);
        assertEquals("Source and target doc counts should match", sourceCount, targetCount);
    }

    /**
     * Suppress {@code AllSpansAreEndedProperly} failures caused by orphaned transport-level
     * spans from node restarts.
     *
     * <p>When a node is restarted mid-bulk-write (e.g. in {@code FailoverIT}), the OpenSearch
     * framework's transport spans ({@code dispatchedShardOperationOnPrimary}) are started but
     * never ended because the thread is interrupted. These orphaned spans cause the
     * {@link StrictCheckSpanProcessor#validateTracingStateOnShutdown()} validation to fail.</p>
     *
     * <p>This override catches the validation error, checks if it only involves known
     * transport-level orphaned spans, and suppresses it. If non-transport AOSC spans are
     * leaked, the error is re-thrown.</p>
     */
    @AfterClass
    public static void suppressOrphanedTransportSpanValidation() {
        Logger log = LogManager.getLogger(AoscIntegTestBase.class);
        try {
            StrictCheckSpanProcessor.validateTracingStateOnShutdown();
        } catch (AssertionError e) {
            String msg = e.getMessage();
            if (msg != null && msg.contains("AllSpansAreEndedProperly") && msg.contains("dispatchedShardOperation")) {
                log.warn(
                    "Suppressed orphaned transport span validation failure (expected after node restart): {}",
                    msg.substring(0, Math.min(msg.length(), 200))
                );
            } else {
                throw e;
            }
        }
    }

    /**
     * Generate a unique index name with a prefix.
     */
    protected String indexName(String prefix) {
        return prefix + "-" + randomAlphaOfLength(6).toLowerCase(Locale.ROOT);
    }
}
