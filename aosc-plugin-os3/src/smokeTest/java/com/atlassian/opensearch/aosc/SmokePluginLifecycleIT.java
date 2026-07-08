/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */
package com.atlassian.opensearch.aosc;

import org.apache.hc.core5.http.io.entity.EntityUtils;

import org.opensearch.client.Request;
import org.opensearch.client.Response;
import org.opensearch.client.ResponseException;

import java.util.Map;

/**
 * Smoke tests for plugin lifecycle — validates the AOSC plugin is loaded,
 * REST endpoints are registered, and basic API contract is honoured.
 */
public class SmokePluginLifecycleIT extends AoscSmokeTestBase {

    /**
     * Plugin appears in _cat/plugins output.
     */
    public void testPluginIsLoaded() throws Exception {
        Request request = new Request("GET", "/_cat/plugins");
        request.addParameter("format", "json");
        request.addParameter("h", "component");
        Response response = client().performRequest(request);
        String body = EntityUtils.toString(response.getEntity());
        assertTrue("AOSC plugin should be listed in _cat/plugins", body.contains("opensearch-aosc"));
    }

    /**
     * Start endpoint exists and returns proper error for missing body.
     */
    public void testStartEndpointRejectsEmptyBody() throws Exception {
        String source = indexName("ep-src");
        createTestIndex(source, 1);
        waitForGreen(source);

        try {
            Request request = new Request("POST", "/_plugins/_aosc/" + source + "/_start");
            request.setJsonEntity("{}");
            client().performRequest(request);
            fail("Should reject empty body (no target)");
        } catch (ResponseException e) {
            assertTrue(
                "Should return 400 or 500, got: " + e.getResponse().getStatusLine().getStatusCode(),
                e.getResponse().getStatusLine().getStatusCode() >= 400
            );
        }
    }

    /**
     * Status endpoint returns 404 for a non-migrating index.
     */
    public void testStatusEndpointExists() throws Exception {
        try {
            getStatus("nonexistent-" + randomAlphaOfLength(6));
            fail("Expected 404 for nonexistent index");
        } catch (org.opensearch.client.ResponseException e) {
            assertEquals(404, e.getResponse().getStatusLine().getStatusCode());
        }
    }

    /**
     * List endpoint returns the new slim envelope with {@code migrations}, {@code truncated}, and {@code active_count}
     * fields. The legacy {@code total} field has been removed (it was misleadingly equal to {@code migrations.size()}).
     */
    @SuppressWarnings("unchecked")
    public void testListEndpointExists() throws Exception {
        Map<String, Object> list = listMigrations();
        assertNotNull("Should have migrations field", list.get("migrations"));
        assertNotNull("Should have truncated field", list.get("truncated"));
        assertNotNull("Should have active_count field", list.get("active_count"));
        assertFalse("Legacy total field should no longer be present", list.containsKey("total"));
    }

    /**
     * Reject duplicate migration — starting a second migration on the same source
     * while one is active should fail.
     */
    public void testRejectDuplicateMigration() throws Exception {
        String source = indexName("dup-src");
        String target1 = indexName("dup-tgt1");
        String target2 = indexName("dup-tgt2");

        createSourceAndTarget(source, target1, 1, 1);
        createTestIndex(target2, 1);
        waitForGreen(target2);
        bulkIndex(source, 2000);

        // Start first migration
        startMigration(source, target1, indexName("dup-alias1"));

        // Attempt second migration on same source — should fail
        try {
            Request request = new Request("POST", "/_plugins/_aosc/" + source + "/_start");
            request.setJsonEntity("{\"target\":{\"index\":\"" + target2 + "\"},\"alias\":\"" + indexName("dup-alias2") + "\"}");
            client().performRequest(request);
            fail("Second migration on same source should be rejected");
        } catch (ResponseException e) {
            int statusCode = e.getResponse().getStatusLine().getStatusCode();
            assertTrue("Should return 4xx or 5xx, got: " + statusCode, statusCode >= 400);
        }

        // Clean up — wait for first migration to complete
        waitForTerminalPhase(source, 90);
    }

    /**
     * Cancel endpoint returns proper response for non-migrating index.
     */
    public void testCancelNonMigratingIndex() throws Exception {
        try {
            Request request = new Request("POST", "/_plugins/_aosc/nonexistent-" + randomAlphaOfLength(6) + "/_cancel");
            client().performRequest(request);
            fail("Cancel on non-migrating index should fail");
        } catch (ResponseException e) {
            int statusCode = e.getResponse().getStatusLine().getStatusCode();
            assertTrue("Should return 4xx or 5xx, got: " + statusCode, statusCode >= 400);
        }
    }
}
