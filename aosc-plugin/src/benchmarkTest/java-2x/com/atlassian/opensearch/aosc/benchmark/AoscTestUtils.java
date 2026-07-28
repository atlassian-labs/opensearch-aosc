/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */
package com.atlassian.opensearch.aosc.benchmark;

import org.apache.http.entity.ContentType;
import org.apache.http.entity.StringEntity;
import org.apache.http.util.EntityUtils;

import org.opensearch.client.Request;
import org.opensearch.client.RequestOptions;
import org.opensearch.client.Response;
import org.opensearch.client.ResponseException;
import org.opensearch.client.RestClient;
import org.opensearch.common.xcontent.XContentHelper;
import org.opensearch.common.xcontent.XContentType;

import org.junit.Assert;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * Static utility methods shared by benchmark and scale tests.
 * All methods take an explicit {@link RestClient} — no inheritance required.
 */
public final class AoscTestUtils {

    private AoscTestUtils() {}

    /**
     * Ensure the {@code .aosc-migrations} index exists with the correct schema.
     * The test framework wipes indices between test classes but the in-JVM
     * MigrationDocumentService cache still thinks the index exists. Without this,
     * the first persistFinalState call auto-creates the index with dynamic mappings
     * (text instead of keyword), breaking term queries in the status API.
     */
    static void ensureMigrationsIndex(RestClient client) throws IOException {
        // Delete any stale index first — between test classes the framework wipes
        // indices but the in-JVM MigrationDocumentService cache still thinks the
        // index exists. Auto-creation on next write would produce dynamic mappings
        // (text instead of keyword), breaking term queries in the status API.
        try {
            Request delReq = new Request("DELETE", "/.aosc-migrations");
            RequestOptions.Builder opts = RequestOptions.DEFAULT.toBuilder();
            opts.setWarningsHandler(w -> false);
            delReq.setOptions(opts);
            client.performRequest(delReq);
        } catch (ResponseException e) {
            if (e.getResponse().getStatusLine().getStatusCode() != 404) {
                throw e;
            }
        }
        try (InputStream is = AoscTestUtils.class.getResourceAsStream("/aosc-migrations-schema.json")) {
            String schema = new String(is.readAllBytes(), StandardCharsets.UTF_8);
            Request req = new Request("PUT", "/.aosc-migrations");
            RequestOptions.Builder putOpts = RequestOptions.DEFAULT.toBuilder();
            putOpts.setWarningsHandler(w -> false);
            req.setOptions(putOpts);
            req.setEntity(new StringEntity(schema, ContentType.APPLICATION_JSON));
            client.performRequest(req);
        }
    }

    // --- Index creation ---

    /** Create a plain index with the given shard/replica counts, then wait for green. */
    public static void createIndex(RestClient client, String name, int shards, int replicas) throws IOException {
        Request request = new Request("PUT", "/" + name);
        String body = "{\"settings\":{\"number_of_shards\":" + shards + ",\"number_of_replicas\":" + replicas + "}}";
        request.setEntity(new StringEntity(body, ContentType.APPLICATION_JSON));
        client.performRequest(request);
    }

    /** Create an index with custom routing required. */
    public static void createIndexWithRouting(RestClient client, String name, int shards, int replicas) throws IOException {
        Request request = new Request("PUT", "/" + name);
        String body = "{\"settings\":{\"number_of_shards\":"
            + shards
            + ",\"number_of_replicas\":"
            + replicas
            + "},\"mappings\":{\"_routing\":{\"required\":true}}}";
        request.setEntity(new StringEntity(body, ContentType.APPLICATION_JSON));
        client.performRequest(request);
    }

    // --- Doc count ---

    public static long getDocCount(RestClient client, String index) throws IOException {
        Request request = new Request("GET", "/" + index + "/_count");
        Response response = client.performRequest(request);
        Map<String, Object> map = responseAsMap(response);
        return ((Number) map.get("count")).longValue();
    }

    // --- Migration lifecycle ---

    public static Map<String, Object> startMigration(RestClient client, String source, String target) throws IOException {
        return startMigration(client, source, target, source + "-alias");
    }

    public static Map<String, Object> startMigration(RestClient client, String source, String target, String alias) throws IOException {
        return startMigration(client, source, target, alias, null);
    }

    /** Start a migration with optional AOSC options. */
    public static Map<String, Object> startMigration(
        RestClient client,
        String source,
        String target,
        String alias,
        Map<String, Object> options
    ) throws IOException {
        ensureMigrationsIndex(client);
        StringBuilder body = new StringBuilder();
        body.append("{\"target_index\":\"").append(target).append("\",\"alias\":\"").append(alias).append("\"");
        if (options != null && !options.isEmpty()) {
            body.append(",\"options\":{");
            int i = 0;
            for (Map.Entry<String, Object> e : options.entrySet()) {
                if (i++ > 0) body.append(",");
                body.append("\"").append(e.getKey()).append("\":").append(e.getValue());
            }
            body.append("}");
        }
        body.append("}");
        Request request = new Request("POST", "/_plugins/_aosc/" + source + "/_start");
        request.setJsonEntity(body.toString());
        Response response = client.performRequest(request);
        return responseAsMap(response);
    }

    public static Map<String, Object> getStatus(RestClient client, String source) throws IOException {
        Request request = new Request("GET", "/_plugins/_aosc/" + source + "/_status");
        Response response = client.performRequest(request);
        return responseAsMap(response);
    }

    public static String getCoordinatorPhase(RestClient client, String source) throws IOException {
        Map<String, Object> status = getStatus(client, source);
        return (String) status.get("phase");
    }

    /** Wait for migration to reach a terminal state. Returns the terminal phase. */
    public static String waitForTerminal(RestClient client, String source, long timeoutMs, int pollIntervalMs) throws Exception {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            String phase = getCoordinatorPhase(client, source);
            if ("COMPLETED".equals(phase) || "FAILED".equals(phase) || "CANCELLED".equals(phase)) {
                return phase;
            }
            Thread.sleep(pollIntervalMs);
        }
        throw new AssertionError(
            "Migration did not reach terminal state within " + timeoutMs + "ms. Last phase: " + getCoordinatorPhase(client, source)
        );
    }

    // --- Correctness validation ---

    /** Refresh both indices and assert their doc counts match. */
    public static void assertDocCountMatch(RestClient client, String source, String target) throws IOException {
        client.performRequest(new Request("POST", "/" + source + "/_refresh"));
        client.performRequest(new Request("POST", "/" + target + "/_refresh"));
        long sourceCount = getDocCount(client, source);
        long targetCount = getDocCount(client, target);
        Assert.assertEquals("Doc count mismatch: source=" + sourceCount + " target=" + targetCount, sourceCount, targetCount);
    }

    // --- Internal helpers ---

    static Map<String, Object> responseAsMap(Response response) throws IOException {
        String body = EntityUtils.toString(response.getEntity());
        return XContentHelper.convertToMap(XContentType.JSON.xContent(), body, false);
    }
}
