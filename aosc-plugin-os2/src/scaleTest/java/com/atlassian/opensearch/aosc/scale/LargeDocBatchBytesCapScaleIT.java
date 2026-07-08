/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */
package com.atlassian.opensearch.aosc.scale;

import com.atlassian.opensearch.aosc.benchmark.AoscTestUtils;

import org.apache.http.entity.ContentType;
import org.apache.http.entity.StringEntity;

import org.opensearch.client.Request;
import org.opensearch.client.Response;

import java.io.IOException;
import java.util.Map;

/**
 * Scale validation for B039: backfill bulk request must not exceed the 2GB transport buffer.
 *
 * <p>Scenario: 5000 docs × ~500KB each ≈ 2.5GB total on a single shard. Without the
 * {@code BatchBuilder} byte cap, a fixed batch of 5000 docs would produce a single
 * {@code BulkRequest} exceeding the 2GB {@code ReleasableBytesStreamOutput} limit.
 *
 * <p>With {@code aosc.backfill.controller.batch.max_bytes = 100MB},
 * {@code BatchBuilder.nextBatch()} splits the batch when the byte budget is reached,
 * so each bulk request stays well under the transport limit and migration completes.
 *
 * <p>Moved out of {@code itTest} because the 2.5 GB data volume and 5-node embedded
 * cluster were too heavy for the standard IT runners and the test was timing out
 * (not a correctness failure). Stays in {@code scaleTest} where it runs against a
 * real cluster with a 4 GB heap and a longer wall-clock budget.
 */
public class LargeDocBatchBytesCapScaleIT extends ScaleTestBase {

    private static final int DOC_COUNT = 5000;
    private static final int PADDING_BYTES = 450 * 1024;            // ~500 KB per doc with JSON overhead
    private static final int INDEX_CHUNK_SIZE = 20;                 // 20 × 500 KB = 10 MB per client bulk
    private static final long MIGRATION_TIMEOUT_MS = 600_000;       // 10 min
    private static final int POLL_INTERVAL_MS = 2000;

    public void testFixedBatchWithLargeDocsCompletesViaByteCap() throws Exception {
        runLargeDocScenario("b039-fixed", "fixed");
    }

    public void testAdaptiveBatchWithLargeDocsCompletesViaByteCap() throws Exception {
        runLargeDocScenario("b039-adapt", "adaptive");
    }

    // ---------------------------------------------------------------------

    private void runLargeDocScenario(String prefix, String controllerType) throws Exception {
        String source = prefix + "-src-" + System.currentTimeMillis();
        String target = prefix + "-tgt-" + System.currentTimeMillis();

        setTransientControllerSettings(controllerType);
        try {
            AoscTestUtils.createIndex(client(), source, 1, 1);
            AoscTestUtils.createIndex(client(), target, 1, 1);

            seedLargeDocs(source);
            refresh(source);
            long indexed = AoscTestUtils.getDocCount(client(), source);
            assertEquals("Source should have all docs", DOC_COUNT, indexed);

            AoscTestUtils.startMigration(client(), source, target, source + "-alias");
            String terminal = AoscTestUtils.waitForTerminal(client(), source, MIGRATION_TIMEOUT_MS, POLL_INTERVAL_MS);
            assertEquals("Migration should complete via byte cap (controller=" + controllerType + ")", "COMPLETED", terminal);

            refresh(target);
            AoscTestUtils.assertDocCountMatch(client(), source, target);
        } finally {
            clearTransientControllerSettings();
            safeDelete(source);
            safeDelete(target);
        }
    }

    private void seedLargeDocs(String index) throws IOException {
        String padding = randomPadding(PADDING_BYTES);
        StringBuilder bulkBody = new StringBuilder(INDEX_CHUNK_SIZE * (PADDING_BYTES + 256));
        for (int start = 0; start < DOC_COUNT; start += INDEX_CHUNK_SIZE) {
            int end = Math.min(start + INDEX_CHUNK_SIZE, DOC_COUNT);
            bulkBody.setLength(0);
            for (int i = start; i < end; i++) {
                bulkBody.append("{\"index\":{\"_index\":\"").append(index).append("\",\"_id\":\"").append(i).append("\"}}\n");
                bulkBody.append("{\"title\":\"doc-").append(i).append("\",\"body\":\"").append(padding).append("\"}\n");
            }
            Request bulk = new Request("POST", "/_bulk");
            bulk.setEntity(new StringEntity(bulkBody.toString(), ContentType.APPLICATION_JSON));
            bulk.addParameter("refresh", "false");
            Response resp = client().performRequest(bulk);
            int status = resp.getStatusLine().getStatusCode();
            if (status < 200 || status >= 300) {
                throw new IOException("Bulk seed failed with status " + status);
            }
        }
    }

    private void setTransientControllerSettings(String controllerType) throws IOException {
        Request req = new Request("PUT", "/_cluster/settings");
        String body = "{\"transient\":{"
            + "\"aosc.backfill.controller.type\":\""
            + controllerType
            + "\","
            + "\"aosc.backfill.controller.batch.size\":"
            + DOC_COUNT
            + ","
            + "\"aosc.backfill.controller.batch.max_bytes\":\"100mb\""
            + "}}";
        req.setEntity(new StringEntity(body, ContentType.APPLICATION_JSON));
        client().performRequest(req);
    }

    private void clearTransientControllerSettings() {
        try {
            Request req = new Request("PUT", "/_cluster/settings");
            String body = "{\"transient\":{"
                + "\"aosc.backfill.controller.type\":null,"
                + "\"aosc.backfill.controller.batch.size\":null,"
                + "\"aosc.backfill.controller.batch.max_bytes\":null"
                + "}}";
            req.setEntity(new StringEntity(body, ContentType.APPLICATION_JSON));
            client().performRequest(req);
        } catch (IOException e) {
            LOG.warning("Failed to clear transient controller settings: " + e.getMessage());
        }
    }

    private void refresh(String index) throws IOException {
        client().performRequest(new Request("POST", "/" + index + "/_refresh"));
    }

    private void safeDelete(String index) {
        try {
            client().performRequest(new Request("DELETE", "/" + index));
        } catch (IOException ignored) {
            // best-effort cleanup
        }
    }

    private static String randomPadding(int bytes) {
        StringBuilder sb = new StringBuilder(bytes);
        String chars = "abcdefghijklmnopqrstuvwxyz0123456789 ";
        for (int i = 0; i < bytes; i++) {
            sb.append(chars.charAt(i % chars.length()));
        }
        return sb.toString();
    }

    @Override
    public Map<String, Object> startMigration(String source, String target) throws IOException {
        // Not used here — see runLargeDocScenario which calls AoscTestUtils.startMigration directly with an alias.
        return AoscTestUtils.startMigration(client(), source, target);
    }
}
