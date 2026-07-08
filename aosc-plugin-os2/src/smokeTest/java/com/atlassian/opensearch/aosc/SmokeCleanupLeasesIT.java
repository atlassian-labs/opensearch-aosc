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
import org.opensearch.client.ResponseException;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Smoke tests for the cleanup_leases REST endpoint.
 * Validates endpoint registration, basic contract, and dry-run behaviour on a live cluster.
 */
public class SmokeCleanupLeasesIT extends AoscSmokeTestBase {

    /** Removes all AOSC retention leases so assertions start from a clean slate. */
    private void purgeAllAoscLeases() throws Exception {
        Request cleanup = new Request("POST", "/_plugins/_aosc/_admin/_cleanup_leases");
        cleanup.addParameter("dry_run", "false");
        client().performRequest(cleanup);
    }

    /**
     * Cluster-wide cleanup removes all pre-existing AOSC leases.
     * Snapshot-before/after approach tolerates concurrent tests creating new leases.
     */
    @SuppressWarnings("unchecked")
    public void testCleanupLeasesOnCleanCluster() throws Exception {
        // Snapshot leases before purge.
        Request dryBefore = new Request("POST", "/_plugins/_aosc/_admin/_cleanup_leases");
        dryBefore.addParameter("dry_run", "true");
        Map<String, Object> beforeResult = entityAsMap(client().performRequest(dryBefore));
        assertTrue("should be dry_run", (Boolean) beforeResult.get("dry_run"));
        List<Map<String, Object>> leasesBefore = (List<Map<String, Object>>) beforeResult.get("leases");
        assertNotNull("leases array must be present", leasesBefore);

        // Purge all AOSC leases.
        purgeAllAoscLeases();

        // Verify every snapshotted lease was removed.
        Request dryAfter = new Request("POST", "/_plugins/_aosc/_admin/_cleanup_leases");
        dryAfter.addParameter("dry_run", "true");
        Map<String, Object> afterResult = entityAsMap(client().performRequest(dryAfter));
        List<Map<String, Object>> leasesAfter = (List<Map<String, Object>>) afterResult.get("leases");

        Set<String> survivingIds = leasesAfter.stream().map(l -> (String) l.get("lease_id")).collect(Collectors.toSet());

        List<String> notCleaned = leasesBefore.stream()
            .map(l -> (String) l.get("lease_id"))
            .filter(survivingIds::contains)
            .collect(Collectors.toList());

        assertTrue("Purge should have removed all pre-existing AOSC leases, but these survived: " + notCleaned, notCleaned.isEmpty());
    }

    /** Per-index cleanup on a specific index with no leases. */
    @SuppressWarnings("unchecked")
    public void testCleanupLeasesPerIndex() throws Exception {
        String index = indexName("cl-idx");
        createTestIndex(index, 1);
        waitForGreen(index);

        Request request = new Request("POST", "/_plugins/_aosc/_admin/_cleanup_leases");
        request.addParameter("index", index);
        request.addParameter("dry_run", "true");
        Response response = client().performRequest(request);
        Map<String, Object> result = entityAsMap(response);

        assertTrue("should be dry_run", (Boolean) result.get("dry_run"));
        assertEquals(0, ((Number) result.get("released")).intValue());
        assertTrue("leases should be empty", ((List<?>) result.get("leases")).isEmpty());
    }

    /**
     * Live (non-dry-run) cleanup removes all pre-existing AOSC leases.
     * Snapshot-before/after approach tolerates concurrent tests creating new leases.
     */
    @SuppressWarnings("unchecked")
    public void testCleanupLeasesLiveRunNoLeases() throws Exception {
        // Snapshot leases before live cleanup.
        Request dryBefore = new Request("POST", "/_plugins/_aosc/_admin/_cleanup_leases");
        dryBefore.addParameter("dry_run", "true");
        Map<String, Object> beforeResult = entityAsMap(client().performRequest(dryBefore));
        List<Map<String, Object>> leasesBefore = (List<Map<String, Object>>) beforeResult.get("leases");
        int preExistingCount = leasesBefore.size();

        // Live-run: remove all AOSC leases.
        Request request = new Request("POST", "/_plugins/_aosc/_admin/_cleanup_leases");
        request.addParameter("dry_run", "false");
        Response response = client().performRequest(request);
        Map<String, Object> result = entityAsMap(response);

        assertFalse("should not be dry_run", (Boolean) result.get("dry_run"));
        assertEquals("no failures expected", 0, ((Number) result.get("failed")).intValue());
        // Concurrent tests may add leases that also get released, so >= is correct.
        int released = ((Number) result.get("released")).intValue();
        assertTrue("released (" + released + ") should be >= pre-existing (" + preExistingCount + ")", released >= preExistingCount);

        // Verify every snapshotted lease was removed.
        Request dryAfter = new Request("POST", "/_plugins/_aosc/_admin/_cleanup_leases");
        dryAfter.addParameter("dry_run", "true");
        Map<String, Object> afterResult = entityAsMap(client().performRequest(dryAfter));
        List<Map<String, Object>> leasesAfter = (List<Map<String, Object>>) afterResult.get("leases");

        Set<String> survivingIds = leasesAfter.stream().map(l -> (String) l.get("lease_id")).collect(Collectors.toSet());

        List<String> notCleaned = leasesBefore.stream()
            .map(l -> (String) l.get("lease_id"))
            .filter(survivingIds::contains)
            .collect(Collectors.toList());

        assertTrue(
            "Live cleanup should have removed all pre-existing AOSC leases, but these survived: " + notCleaned,
            notCleaned.isEmpty()
        );
    }

    /** Explicit empty index parameter should be rejected with 400. */
    public void testCleanupLeasesRejectsEmptyIndex() throws Exception {
        try {
            // Construct URL with explicit empty index segment — the RestClient may reject
            // this client-side (IllegalArgumentException for illegal URL characters) or the
            // server may reject it with a 4xx response. Either outcome is acceptable.
            Request request = new Request("POST", "/_plugins/_aosc/_admin/_cleanup_leases");
            request.addParameter("index", " ");
            client().performRequest(request);
            fail("Empty index should be rejected");
        } catch (ResponseException e) {
            int statusCode = e.getResponse().getStatusLine().getStatusCode();
            assertTrue("Should return 4xx, got: " + statusCode, statusCode >= 400 && statusCode < 500);
        } catch (IllegalArgumentException e) {
            // Client-side URL validation rejected the space — this is valid rejection
            assertTrue("Should mention illegal character", e.getMessage().contains("Illegal character"));
        }
    }

    /** Dry-run followed by live-run is idempotent — both return same empty result. */
    @SuppressWarnings("unchecked")
    public void testDryRunThenLiveRunIdempotent() throws Exception {
        String index = indexName("cl-idem");
        createTestIndex(index, 2);
        waitForGreen(index);

        // Dry-run
        Request dryReq = new Request("POST", "/_plugins/_aosc/_admin/_cleanup_leases");
        dryReq.addParameter("index", index);
        dryReq.addParameter("dry_run", "true");
        Map<String, Object> dryResult = entityAsMap(client().performRequest(dryReq));
        assertTrue((Boolean) dryResult.get("dry_run"));
        int dryCount = ((List<?>) dryResult.get("leases")).size();

        // Live-run
        Request liveReq = new Request("POST", "/_plugins/_aosc/_admin/_cleanup_leases");
        liveReq.addParameter("index", index);
        liveReq.addParameter("dry_run", "false");
        Map<String, Object> liveResult = entityAsMap(client().performRequest(liveReq));
        assertFalse((Boolean) liveResult.get("dry_run"));

        // Both should see same lease count (0 on a fresh index)
        assertEquals(dryCount, ((List<?>) liveResult.get("leases")).size());
    }
}
