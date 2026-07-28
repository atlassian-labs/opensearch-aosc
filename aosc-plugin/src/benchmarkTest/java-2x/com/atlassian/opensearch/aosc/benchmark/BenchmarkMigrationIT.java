/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */
package com.atlassian.opensearch.aosc.benchmark;

import org.opensearch.client.Request;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

/**
 * AOSC Migration Benchmarks.
 *
 * <p>Profiles are selected via: {@code -Dbenchmark.profile=quick|medium|heavy|split-quick|routing-quick|...}
 *
 * <p>Usage:
 * <pre>
 *   ./gradlew benchmark2Nodes                                          # quick, same-shard
 *   ./gradlew benchmark2Nodes -Dbenchmark.profile=split-quick          # shard split 2→4
 *   ./gradlew benchmark2Nodes -Dbenchmark.profile=routing-quick        # custom routing
 *   ./gradlew benchmark2Nodes -Dbenchmark.profile=split-routing-quick  # split + routing
 *   ./gradlew benchmarkDedicatedCM -Dbenchmark.profile=heavy           # heavy on 3-node
 *   ./gradlew benchmarkDocker -Dbenchmark.profile=medium               # medium on Docker
 * </pre>
 */
public class BenchmarkMigrationIT extends AoscBenchmarkBase {

    private static final Logger LOG = Logger.getLogger(BenchmarkMigrationIT.class.getName());

    private final BenchmarkProfile profile = BenchmarkProfile.active();

    /**
     * Core benchmark: seed → start migration → write load → collect metrics → validate.
     * Profile determines doc count, shard topology, routing, and thresholds.
     */
    public void testMigrationBenchmark() throws Exception {
        String profileName = System.getProperty("benchmark.profile", "quick");
        int sourceShards = (int) profile.param("shards");
        int targetShards = (int) profile.effectiveTargetShards();
        boolean isSplit = targetShards != sourceShards;
        String suffix = profileName.replace("-", "_");
        String source = "bench-source-" + suffix;
        String target = "bench-target-" + suffix;
        int docs = (int) profile.param("docs");
        int writeRate = (int) profile.param("writeRate");
        int batchSize = (int) profile.param("batchSize");
        long timeoutMs = profile.param("timeoutMs");
        boolean routing = profile.isUseCustomRouting();

        Path outputDir = benchmarkOutputDir(profileName);
        LOG.info(
            "Starting benchmark: profile="
                + profileName
                + " docs="
                + docs
                + " writeRate="
                + writeRate
                + " shards="
                + sourceShards
                + "→"
                + targetShards
                + " routing="
                + routing
        );

        // Phase 1: Create indices and seed data
        int seedThreads = (int) profile.param("seedThreads");
        LOG.info("Phase 1: Creating indices and seeding " + docs + " docs (" + seedThreads + " threads)...");
        BulkDocSeeder seeder = new BulkDocSeeder(client(), seedThreads);
        long seedDurationMs;
        if (routing) {
            createIndexWithRouting(source, sourceShards, 0);
            createIndex(target, targetShards, 0);
            seedDurationMs = seeder.seedWithRouting(source, docs, batchSize);
        } else {
            createIndex(source, sourceShards, 0);
            createIndex(target, targetShards, 0);
            seedDurationMs = seeder.seed(source, docs, batchSize);
        }
        LOG.info("Seeded " + docs + " docs in " + seedDurationMs + "ms" + (routing ? " (with custom routing)" : ""));

        // Phase 2: Start migration (with optional AOSC options from profile)
        long migrationStartMs = System.currentTimeMillis();
        Map<String, Object> aoscOptions = new HashMap<>();
        aoscOptions.put("remove_source_write_block_on_success", false);

        // Apply adaptive/concurrent settings for T029 profiles
        if (profileName.contains("adaptive") || profileName.contains("concurrent")) {
            StringBuilder body = new StringBuilder("{\"transient\":{\"aosc.backfill.controller.type\":\"adaptive\"");
            if (profileName.contains("concurrent")) {
                body.append(",\"aosc.backfill.controller.concurrency.max\":4");
            }
            body.append("}}");
            Request settingsReq = new Request("PUT", "/_cluster/settings");
            settingsReq.setJsonEntity(body.toString());
            client().performRequest(settingsReq);
            LOG.info("Applied adaptive settings for profile: " + profileName);
        }

        AoscTestUtils.startMigration(client(), source, target, source + "-alias", aoscOptions.isEmpty() ? null : aoscOptions);
        LOG.info(
            "Phase 2: Migration started"
                + (isSplit ? " (SPLIT " + sourceShards + "→" + targetShards + ")" : "")
                + (aoscOptions.isEmpty() ? "" : " options=" + aoscOptions)
        );

        // Phase 3: Start write load + metrics collector
        int writeBatchSize = (int) profile.param("writeBatchSize");
        int writerThreads = (int) profile.param("writerThreads");
        WriteLoadGenerator writeLoad = new WriteLoadGenerator(client(), source, writeRate, writeBatchSize, docs, writerThreads, routing);
        MetricsCollector metrics = new MetricsCollector(client(), source, outputDir, DEFAULT_POLL_INTERVAL_MS, writeLoad::getTotalOps);
        writeLoad.start();
        metrics.start();
        LOG.info(
            "Phase 3: Write load started at "
                + writeRate
                + " ops/sec, "
                + writerThreads
                + " threads, batch="
                + writeBatchSize
                + (routing ? " (with routing)" : "")
        );

        // Phase 4: Wait for terminal state
        String terminalPhase;
        try {
            terminalPhase = waitForTerminal(source, timeoutMs);
        } finally {
            writeLoad.close();
            metrics.close();
            if (profileName.contains("adaptive") || profileName.contains("concurrent")) {
                Request clearReq = new Request("PUT", "/_cluster/settings");
                clearReq.setJsonEntity(
                    "{\"transient\":{\"aosc.backfill.controller.type\":null,\"aosc.backfill.controller.concurrency.max\":null}}"
                );
                client().performRequest(clearReq);
            }
        }
        long migrationDurationMs = System.currentTimeMillis() - migrationStartMs;
        LOG.info("Phase 4: Migration reached " + terminalPhase + " in " + migrationDurationMs + "ms");

        // Phase 5: Validate
        client().performRequest(new Request("POST", "/" + source + "/_refresh"));
        client().performRequest(new Request("POST", "/" + target + "/_refresh"));
        long sourceCount = getDocCount(source);
        long targetCount = getDocCount(target);

        // Build result
        BenchmarkResult result = new BenchmarkResult();
        result.profile = profileName;
        result.terminalPhase = terminalPhase;
        result.totalDurationMs = migrationDurationMs;
        result.seedDurationMs = seedDurationMs;
        result.seedDocCount = docs;
        result.sourceDocCount = sourceCount;
        result.targetDocCount = targetCount;
        result.docCountMatch = (sourceCount == targetCount);
        result.writeOpsTotal = writeLoad.getTotalOps();
        result.writeErrors = writeLoad.getErrors();
        result.writeBlockErrors = writeLoad.getWriteBlockErrors();
        result.writeRateTarget = writeRate;
        result.transitionHistory = metrics.getPhaseRecords();

        // Phase 6: Report
        LOG.info(result.summary());
        try {
            result.writeJson(outputDir);
            metrics.writeResults();
        } catch (java.security.AccessControlException e) {
            LOG.warning("Cannot write results to disk (security manager): " + e.getMessage());
        }

        // Phase 7: Threshold check
        ThresholdChecker checker = new ThresholdChecker(
            profile.param("maxDurationMs"),
            profile.param("maxWriteBlockMs"),
            profile.param("minBackfillDocsPerSec"),
            true,
            true
        );
        List<ThresholdChecker.CheckResult> checks = checker.check(result);
        LOG.info(checker.formatReport(checks));

        // Assert all thresholds pass
        for (ThresholdChecker.CheckResult check : checks) {
            assertTrue(check.name + ": expected " + check.threshold + ", got " + check.actual, check.passed);
        }
    }
}
