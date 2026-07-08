/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */
package com.atlassian.opensearch.aosc;

import org.opensearch.client.Request;
import org.opensearch.client.Response;
import org.opensearch.common.io.Streams;
import org.opensearch.test.rest.OpenSearchRestTestCase;

import org.junit.After;

import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * Base class for AOSC REST-level smoke tests.
 *
 * <p>Extends {@link OpenSearchRestTestCase} to provide helpers for the AOSC REST API.
 * All operations go through HTTP — no transport client. The cluster is a real forked
 * OpenSearch process with the AOSC plugin installed (via {@code testClusters}).
 *
 * <p>Key design:
 * <ul>
 *   <li>Shared cluster across all test classes (started once by Gradle)</li>
 *   <li>Per-test index isolation via unique index names</li>
 *   <li>Automatic index cleanup after each test</li>
 * </ul>
 */
public abstract class AoscSmokeTestBase extends OpenSearchRestTestCase {

    // Terminal coordinator phases
    private static final Set<String> TERMINAL_PHASES = Set.of("COMPLETED", "CANCELLED", "FAILED");

    // Default poll interval for async operations
    private static final long POLL_INTERVAL_MS = 500;

    // ---- Index Name Isolation ----

    private final String testId = randomAlphaOfLength(6).toLowerCase(Locale.ROOT);

    /**
     * Generate a unique index name for this test to avoid collisions.
     */
    @Override
    protected boolean preserveIndicesUponCompletion() {
        // Each test uses unique index names via indexName(), so no cleanup needed.
        // This avoids race conditions on slower clusters (Docker) where index
        // deletion from the previous test hasn't propagated before the next starts.
        return true;
    }

    /** Drain in-flight {@code indices:*} tasks between tests to avoid cross-test pollution (B020). */
    @After
    public void waitForNoPendingIndicesTasks() throws Exception {
        try {
            assertBusy(this::assertNoPendingIndicesTasks, 30, TimeUnit.SECONDS);
        } catch (Throwable t) {
            logger.warn("waitForNoPendingIndicesTasks: did not drain within 30s — {}", t.getMessage());
        }
    }

    @SuppressWarnings("unchecked")
    private void assertNoPendingIndicesTasks() throws Exception {
        Request request = new Request("GET", "/_tasks");
        request.addParameter("actions", "indices:*");
        request.addParameter("detailed", "false");
        Response response = client().performRequest(request);
        Map<String, Object> parsed = entityAsMap(response);
        Map<String, Object> nodes = (Map<String, Object>) parsed.get("nodes");
        if (nodes == null || nodes.isEmpty()) {
            return;
        }
        List<String> pending = nodes.values()
            .stream()
            .map(n -> (Map<String, Object>) n)
            .map(n -> (Map<String, Object>) n.get("tasks"))
            .filter(t -> t != null)
            .flatMap(t -> t.values().stream())
            .map(t -> (Map<String, Object>) t)
            .map(t -> String.valueOf(t.get("action")))
            .filter(action -> action.startsWith("indices:"))
            .filter(action -> !action.equals("indices:data/read/search"))
            .collect(Collectors.toList());
        assertTrue("Pending indices tasks after test: " + pending, pending.isEmpty());
    }

    /** Drain in-flight {@code aosc-*} cluster-state tasks between tests to avoid cross-test pollution (B022). */
    @After
    public void waitForNoAoscClusterStateTasks() throws Exception {
        try {
            assertBusy(this::assertNoAoscClusterStateTasks, 30, TimeUnit.SECONDS);
        } catch (Throwable t) {
            logger.warn("waitForNoAoscClusterStateTasks: did not drain within 30s — {}", t.getMessage());
        }
    }

    @SuppressWarnings("unchecked")
    private void assertNoAoscClusterStateTasks() throws Exception {
        Request request = new Request("GET", "/_cluster/pending_tasks");
        Response response = client().performRequest(request);
        Map<String, Object> parsed = entityAsMap(response);
        List<Map<String, Object>> tasks = (List<Map<String, Object>>) parsed.get("tasks");
        if (tasks == null || tasks.isEmpty()) {
            return;
        }
        List<String> aoscTasks = tasks.stream()
            .map(t -> String.valueOf(t.get("source")))
            .filter(source -> source.startsWith("aosc-"))
            .collect(Collectors.toList());
        assertTrue("Pending AOSC cluster state tasks after test: " + aoscTasks, aoscTasks.isEmpty());
    }

    protected String indexName(String suffix) {
        return "aosc-" + testId + "-" + suffix;
    }

    // ---- Index Management (REST) ----

    /**
     * Create an index with the given number of shards and 0 replicas.
     */
    protected void createTestIndex(String index, int numShards) throws IOException {
        createTestIndex(index, numShards, 0);
    }

    /**
     * Create an index with the given number of shards and replicas.
     */
    protected void createTestIndex(String index, int numShards, int numReplicas) throws IOException {
        String body = String.format(
            Locale.ROOT,
            "{\"settings\":{\"index\":{\"number_of_shards\":%d,\"number_of_replicas\":%d}}}",
            numShards,
            numReplicas
        );
        Request request = new Request("PUT", "/" + index);
        request.setJsonEntity(body);
        client().performRequest(request);
    }

    /**
     * Create source and target indices with the given shard counts.
     */
    protected void createSourceAndTarget(String source, String target, int sourceShards, int targetShards) throws IOException {
        createTestIndex(source, sourceShards);
        createTestIndex(target, targetShards);
        waitForGreen(source);
        waitForGreen(target);
    }

    /**
     * Bulk index documents with sequential IDs and a "value" field.
     */
    protected void bulkIndex(String index, int numDocs) throws IOException {
        bulkIndex(index, 0, numDocs);
    }

    /**
     * Bulk index documents with sequential IDs starting from {@code fromId}.
     */
    protected void bulkIndex(String index, int fromId, int toId) throws IOException {
        int batchSize = 500;
        for (int batch = fromId; batch < toId; batch += batchSize) {
            StringBuilder sb = new StringBuilder();
            int end = Math.min(batch + batchSize, toId);
            for (int i = batch; i < end; i++) {
                sb.append(String.format(Locale.ROOT, "{\"index\":{\"_id\":\"%d\"}}\n{\"value\":%d}\n", i, i));
            }
            Request request = new Request("POST", "/" + index + "/_bulk");
            request.setJsonEntity(sb.toString());
            request.addParameter("refresh", "false");
            Response response = client().performRequest(request);
            Map<String, Object> responseMap = entityAsMap(response);
            assertFalse("Bulk indexing should not have errors", (Boolean) responseMap.get("errors"));
        }
        // Refresh to make docs visible
        refreshIndex(index);
    }

    /**
     * Index a single document with custom fields.
     */
    protected void indexDoc(String index, String id, Map<String, Object> fields) throws IOException {
        Request request = new Request("PUT", "/" + index + "/_doc/" + id);
        request.setJsonEntity(toJson(fields));
        request.addParameter("refresh", "false");
        client().performRequest(request);
    }

    /**
     * Index a single document with custom routing.
     */
    protected void indexDocWithRouting(String index, String id, String routing, Map<String, Object> fields) throws IOException {
        Request request = new Request("PUT", "/" + index + "/_doc/" + id);
        request.addParameter("routing", routing);
        request.setJsonEntity(toJson(fields));
        request.addParameter("refresh", "false");
        client().performRequest(request);
    }

    /**
     * Refresh an index to make all docs searchable.
     */
    protected void refreshIndex(String index) throws IOException {
        client().performRequest(new Request("POST", "/" + index + "/_refresh"));
    }

    /**
     * Get the document count for an index.
     */
    protected long getDocCount(String index) throws IOException {
        refreshIndex(index);
        Request request = new Request("GET", "/" + index + "/_count");
        Map<String, Object> response = entityAsMap(client().performRequest(request));
        return ((Number) response.get("count")).longValue();
    }

    /**
     * Get a document by ID.
     */
    protected Map<String, Object> getDoc(String index, String id) throws IOException {
        Request request = new Request("GET", "/" + index + "/_doc/" + id);
        return entityAsMap(client().performRequest(request));
    }

    /**
     * Get a document by ID with routing.
     */
    protected Map<String, Object> getDocWithRouting(String index, String id, String routing) throws IOException {
        Request request = new Request("GET", "/" + index + "/_doc/" + id);
        request.addParameter("routing", routing);
        return entityAsMap(client().performRequest(request));
    }

    /**
     * Wait for index health to be green.
     */
    protected void waitForGreen(String index) throws IOException {
        Request request = new Request("GET", "/_cluster/health/" + index);
        request.addParameter("wait_for_status", "green");
        request.addParameter("timeout", "60s");
        client().performRequest(request);
    }

    // ---- AOSC REST API Helpers ----

    /**
     * Start a migration via POST /_plugins/_aosc/{index}/_start
     *
     * @return response map with "accepted", "migration_id"
     */
    protected Map<String, Object> startMigration(String sourceIndex, String targetIndex, String alias) throws IOException {
        return startMigration(sourceIndex, targetIndex, alias, null, null);
    }

    /**
     * Start a migration with optional transform script and options.
     */
    protected Map<String, Object> startMigration(
        String sourceIndex,
        String targetIndex,
        String alias,
        String transformScript,
        Map<String, Object> options
    ) throws IOException {
        // Build JSON body matching MigrationRequest Jackson schema:
        // {"target_index":"...","alias":"...","transform_script":{"type":"inline","source":"..."},"options":{...}}
        StringBuilder json = new StringBuilder("{\"target_index\":\"").append(targetIndex).append("\"");
        if (alias != null) json.append(",\"alias\":\"").append(alias).append("\"");
        if (transformScript != null) {
            json.append(",\"transform_script\":{\"type\":\"inline\",\"source\":\"").append(transformScript).append("\"}");
        }
        if (options != null) json.append(",\"options\":").append(toJson(options));
        json.append("}");

        Request request = new Request("POST", "/_plugins/_aosc/" + sourceIndex + "/_start");
        request.setJsonEntity(json.toString());
        Response response = client().performRequest(request);
        Map<String, Object> result = entityAsMap(response);
        assertTrue("Migration should be accepted", (Boolean) result.get("accepted"));
        assertNotNull("Migration ID should be present", result.get("migration_id"));
        return result;
    }

    /**
     * Get migration status via GET /_plugins/_aosc/{index}/_status
     *
     * @return response map with "found", "phase", "shards", etc.
     */
    protected Map<String, Object> getStatus(String sourceIndex) throws IOException {
        Request request = new Request("GET", "/_plugins/_aosc/" + sourceIndex + "/_status");
        return entityAsMap(client().performRequest(request));
    }

    /**
     * List active migrations via GET /_plugins/_aosc/_list
     */
    protected Map<String, Object> listMigrations() throws IOException {
        Request request = new Request("GET", "/_plugins/_aosc/_list");
        return entityAsMap(client().performRequest(request));
    }

    /**
     * Cancel a migration via POST /_plugins/_aosc/{index}/_cancel
     */
    protected Map<String, Object> cancelMigration(String sourceIndex) throws IOException {
        Request request = new Request("POST", "/_plugins/_aosc/" + sourceIndex + "/_cancel");
        return entityAsMap(client().performRequest(request));
    }

    // ---- Cluster Settings ----

    /**
     * Update a transient cluster setting via REST.
     */
    protected void updateClusterSetting(String key, Object value) throws IOException {
        String valueStr = value instanceof String ? "\"" + value + "\"" : String.valueOf(value);
        String json = "{\"transient\":{\"" + key + "\":" + valueStr + "}}";
        Request request = new Request("PUT", "/_cluster/settings");
        request.setJsonEntity(json);
        client().performRequest(request);
    }

    /**
     * Clear a transient cluster setting via REST.
     */
    protected void clearClusterSetting(String key) throws IOException {
        String json = "{\"transient\":{\"" + key + "\":null}}";
        Request request = new Request("PUT", "/_cluster/settings");
        request.setJsonEntity(json);
        client().performRequest(request);
    }

    // ---- Polling Helpers ----

    /**
     * Poll status until the coordinator phase reaches a terminal state (COMPLETED, CANCELLED, FAILED).
     *
     * @return the terminal status response map
     * @throws AssertionError if timeout is reached
     */
    protected Map<String, Object> waitForTerminalPhase(String sourceIndex, int timeoutSeconds) throws Exception {
        long deadline = System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(timeoutSeconds);
        Map<String, Object> lastStatus = null;

        while (System.currentTimeMillis() < deadline) {
            try {
                lastStatus = getStatus(sourceIndex);
            } catch (org.opensearch.client.ResponseException e) {
                if (e.getResponse().getStatusLine().getStatusCode() == 404) {
                    Thread.sleep(POLL_INTERVAL_MS);
                    continue;
                }
                throw e;
            }
            String phase = (String) lastStatus.get("phase");
            if (phase != null && TERMINAL_PHASES.contains(phase)) {
                return lastStatus;
            }
            Thread.sleep(POLL_INTERVAL_MS);
        }

        String lastPhase = lastStatus != null ? String.valueOf(lastStatus.get("phase")) : "NO_STATUS";
        fail("Migration did not reach terminal phase within " + timeoutSeconds + "s. Last phase: " + lastPhase);
        return null; // unreachable
    }

    /**
     * Wait for migration to complete (phase = COMPLETED).
     *
     * @return the completed status response map
     * @throws AssertionError if timeout is reached or migration fails/cancels
     */
    protected Map<String, Object> waitForCompletion(String sourceIndex, int timeoutSeconds) throws Exception {
        Map<String, Object> status = waitForTerminalPhase(sourceIndex, timeoutSeconds);
        String phase = (String) status.get("phase");
        assertEquals("Migration should complete, got: " + phase, "COMPLETED", phase);
        return status;
    }

    // ---- Assertions ----

    /**
     * Assert that source and target doc counts match.
     */
    protected void assertDocCountsMatch(String source, String target) throws IOException {
        long sourceCount = getDocCount(source);
        long targetCount = getDocCount(target);
        assertEquals("Source (" + sourceCount + ") and target (" + targetCount + ") doc counts should match", sourceCount, targetCount);
    }

    /**
     * Assert that an alias points to the expected index.
     */
    @SuppressWarnings("unchecked")
    protected void assertAliasPointsTo(String alias, String expectedIndex) throws IOException {
        Request request = new Request("GET", "/_alias/" + alias);
        Map<String, Object> response = entityAsMap(client().performRequest(request));
        assertTrue("Alias should resolve to " + expectedIndex, response.containsKey(expectedIndex));
    }

    /**
     * Assert that a specific coordinator phase is active.
     */
    protected void assertCoordinatorPhase(Map<String, Object> status, String expectedPhase) {
        assertEquals("Coordinator phase should be " + expectedPhase, expectedPhase, status.get("phase"));
    }

    /**
     * Assert that all shards in the status are in a terminal phase.
     * Re-fetches status by polling because shard progress writes are async
     * and may lag behind the coordinator phase transition.
     *
     * @param sourceIndex the source index to poll status for
     */
    @SuppressWarnings("unchecked")
    protected void assertAllShardsTerminal(String sourceIndex) throws Exception {
        long deadline = System.currentTimeMillis() + 30_000;
        Map<String, Object> shards = null;
        while (System.currentTimeMillis() < deadline) {
            Map<String, Object> status = getStatus(sourceIndex);
            shards = (Map<String, Object>) status.get("shards");
            if (shards != null && !shards.isEmpty()) {
                for (Map.Entry<String, Object> entry : shards.entrySet()) {
                    Map<String, Object> shard = (Map<String, Object>) entry.getValue();
                    String phase = (String) shard.get("phase");
                    assertTrue(
                        "Shard " + entry.getKey() + " should be terminal, got: " + phase,
                        "COMPLETED".equals(phase) || "CANCELLED".equals(phase) || "FAILED".equals(phase)
                    );
                }
                return; // All shards terminal
            }
            Thread.sleep(POLL_INTERVAL_MS);
        }
        fail("Shard progress not available within 30s. Last shards: " + shards);
    }

    // ---- Utility ----

    /**
     * Convert a map to JSON string.
     */
    private static String toJson(Map<String, Object> map) {
        StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<String, Object> entry : map.entrySet()) {
            if (!first) sb.append(",");
            first = false;
            sb.append("\"").append(entry.getKey()).append("\":");
            Object value = entry.getValue();
            if (value instanceof String) {
                sb.append("\"").append(value).append("\"");
            } else if (value instanceof Map) {
                @SuppressWarnings("unchecked")
                Map<String, Object> nested = (Map<String, Object>) value;
                sb.append(toJson(nested));
            } else if (value instanceof Boolean || value instanceof Number) {
                sb.append(value);
            } else if (value == null) {
                sb.append("null");
            } else {
                sb.append("\"").append(value).append("\"");
            }
        }
        sb.append("}");
        return sb.toString();
    }

    /** Returns true if the Painless scripting module is installed on the cluster. */
    protected boolean isPainlessAvailable() throws Exception {
        Response resp = client().performRequest(new Request("GET", "/_nodes/plugins"));
        String body = Streams.copyToString(new InputStreamReader(resp.getEntity().getContent(), StandardCharsets.UTF_8));
        return body.contains("lang-painless");
    }
}
