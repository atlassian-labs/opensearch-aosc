/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */
package com.atlassian.opensearch.aosc.benchmark;

import org.apache.hc.core5.http.ContentType;
import org.apache.hc.core5.http.io.entity.StringEntity;

import org.opensearch.client.Request;
import org.opensearch.client.Response;

import java.io.IOException;
import java.util.Locale;
import java.util.logging.Logger;

/**
 * Shard-skew benchmark: measures migration time with and without adaptive
 * concurrency on a heavily skewed index (80% of docs on one shard).
 *
 * <p>Run locally with:
 * <pre>
 *   ./gradlew --no-daemon benchmark2Nodes \
 *       -Dtests.filter="*ShardSkewBenchmarkIT*" \
 *       -Dbenchmark.profile=quick
 * </pre>
 *
 * <p>This test is intentionally long-running (~5-10 min total). It seeds
 * 50K docs with routing-based skew, then runs two full migration cycles.
 */
public class ShardSkewBenchmarkIT extends AoscBenchmarkBase {

    private static final Logger LOG = Logger.getLogger(ShardSkewBenchmarkIT.class.getName());

    private static final int TOTAL_DOCS = Integer.getInteger("skew.docs", 200_000);
    private static final int SOURCE_SHARDS = Integer.getInteger("skew.shards", 5);
    private static final int SKEW_SHARD_PERCENT = Integer.getInteger("skew.pct", 80);
    private static final int DOC_SIZE_BYTES = Integer.getInteger("skew.docSize", 2_048);
    private static final long TIMEOUT_MS = Long.getLong("skew.timeoutMs", 60_000);
    private static final String PADDING = "x".repeat(Math.max(DOC_SIZE_BYTES, 1));

    public void testShardSkewAdaptiveVsSerial() throws Exception {
        LOG.info("=== Shard Skew Benchmark: " + TOTAL_DOCS + " docs, " + SOURCE_SHARDS + " shards ===");
        LOG.info("Skew: " + SKEW_SHARD_PERCENT + "% of docs routed to shard 0");

        // ---- Run 1: Serial (adaptive=false) ----
        long serialMs = runMigration("serial", false);

        // ---- Run 2: Adaptive (W=4) ----
        long adaptiveMs = runMigration("adaptive", true);

        // ---- Report ----
        double speedup = (double) serialMs / adaptiveMs;
        LOG.info("╔════════════════════════════════════════════╗");
        LOG.info("║      SHARD SKEW BENCHMARK RESULTS          ║");
        LOG.info("╠════════════════════════════════════════════╣");
        LOG.info(String.format(Locale.ROOT, "║  Serial (W=1):    %,8d ms             ║", serialMs));
        LOG.info(String.format(Locale.ROOT, "║  Adaptive (W=4):  %,8d ms             ║", adaptiveMs));
        LOG.info(String.format(Locale.ROOT, "║  Speedup:         %8.2fx              ║", speedup));
        LOG.info(String.format(Locale.ROOT, "║  Docs:            %,8d                ║", TOTAL_DOCS));
        LOG.info(String.format(Locale.ROOT, "║  Shards:          %8d                ║", SOURCE_SHARDS));
        LOG.info(String.format(Locale.ROOT, "║  Skew:            %7d%%               ║", SKEW_SHARD_PERCENT));
        LOG.info("╚════════════════════════════════════════════╝");

        // Sanity: both should have completed
        assertTrue("Serial migration should complete in time", serialMs < TIMEOUT_MS);
        assertTrue("Adaptive migration should complete in time", adaptiveMs < TIMEOUT_MS);
    }

    private long runMigration(String label, boolean adaptive) throws Exception {
        String source = "skew-" + label + "-src";
        String target = "skew-" + label + "-tgt";

        LOG.info("[" + label + "] Creating indices...");
        AoscTestUtils.createIndex(client(), source, SOURCE_SHARDS, 0);
        AoscTestUtils.createIndex(client(), target, SOURCE_SHARDS, 0);

        LOG.info("[" + label + "] Seeding " + TOTAL_DOCS + " docs with " + SKEW_SHARD_PERCENT + "% skew...");
        seedSkewedDocs(source);

        // Verify seed counts
        client().performRequest(new Request("POST", "/" + source + "/_refresh"));
        long seededCount = AoscTestUtils.getDocCount(client(), source);
        LOG.info("[" + label + "] Seeded " + seededCount + " docs");
        assertEquals("Should have seeded all docs", TOTAL_DOCS, seededCount);

        // Apply cluster settings
        if (adaptive) {
            setClusterSetting("aosc.backfill.controller.type", "adaptive");
            setClusterSetting("aosc.backfill.controller.concurrency.max", "4");
        } else {
            setClusterSetting("aosc.backfill.controller.type", "fixed");
        }

        // Run migration
        LOG.info("[" + label + "] Starting migration (adaptive=" + adaptive + ")...");
        long startMs = System.currentTimeMillis();
        startMigration(source, target);
        String phase = AoscTestUtils.waitForTerminal(client(), source, TIMEOUT_MS, 500);
        long durationMs = System.currentTimeMillis() - startMs;

        LOG.info("[" + label + "] Migration " + phase + " in " + durationMs + "ms");
        assertEquals("Migration should complete", "COMPLETED", phase);

        // Verify doc counts
        client().performRequest(new Request("POST", "/" + target + "/_refresh"));
        long targetCount = AoscTestUtils.getDocCount(client(), target);
        LOG.info("[" + label + "] Source=" + seededCount + " Target=" + targetCount);
        assertEquals("All docs should be migrated", seededCount, targetCount);

        // Cleanup
        clearClusterSetting("aosc.backfill.controller.type");
        clearClusterSetting("aosc.backfill.controller.concurrency.max");
        cleanupIndex(source);
        cleanupIndex(target);

        return durationMs;
    }

    /**
     * Seed docs with routing skew: SKEW_SHARD_PERCENT% of docs use routing
     * value "hot" (all land on same shard), rest use random routing values.
     */
    private void seedSkewedDocs(String index) throws Exception {
        int hotDocs = TOTAL_DOCS * SKEW_SHARD_PERCENT / 100;
        int coldDocs = TOTAL_DOCS - hotDocs;

        // Hot shard: all docs routed to "hot"
        seedBulk(index, 0, hotDocs, "hot");
        // Cold shards: docs spread with unique routing
        seedBulk(index, hotDocs, coldDocs, null);
    }

    private void seedBulk(String index, int startId, int count, String routing) throws IOException {
        int batchSize = 500;
        for (int offset = 0; offset < count; offset += batchSize) {
            int end = Math.min(offset + batchSize, count);
            StringBuilder bulk = new StringBuilder();
            for (int i = offset; i < end; i++) {
                int docId = startId + i;
                String routingValue = routing != null ? routing : "cold-" + docId;
                bulk.append("{\"index\":{\"_id\":\"doc-").append(docId).append("\",\"routing\":\"").append(routingValue).append("\"}}\n");
                bulk.append("{\"data\":\"").append(PADDING).append("\",\"id\":").append(docId).append("}\n");
            }
            Request req = new Request("POST", "/" + index + "/_bulk?refresh=false");
            req.setEntity(new StringEntity(bulk.toString(), ContentType.APPLICATION_JSON));
            Response resp = client().performRequest(req);
            if (AoscTestUtils.responseAsMap(resp).containsKey("errors")
                && Boolean.TRUE.equals(AoscTestUtils.responseAsMap(resp).get("errors"))) {
                throw new IOException("Bulk indexing errors in batch starting at " + (startId + offset));
            }
        }
    }

    private void setClusterSetting(String key, String value) throws IOException {
        Request req = new Request("PUT", "/_cluster/settings");
        req.setJsonEntity("{\"transient\":{\"" + key + "\":" + jsonValue(value) + "}}");
        client().performRequest(req);
    }

    private void clearClusterSetting(String key) throws IOException {
        Request req = new Request("PUT", "/_cluster/settings");
        req.setJsonEntity("{\"transient\":{\"" + key + "\":null}}");
        client().performRequest(req);
    }

    private void cleanupIndex(String index) throws IOException {
        try {
            client().performRequest(new Request("DELETE", "/" + index));
        } catch (Exception e) {
            LOG.warning("Failed to delete " + index + ": " + e.getMessage());
        }
    }

    private static String jsonValue(String val) {
        if ("true".equals(val) || "false".equals(val)) return val;
        try {
            Integer.parseInt(val);
            return val;
        } catch (NumberFormatException e) {
            return "\"" + val + "\"";
        }
    }
}
