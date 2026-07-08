/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */
package com.atlassian.opensearch.aosc.scale;

import com.atlassian.opensearch.aosc.benchmark.BulkDocSeeder;
import com.atlassian.opensearch.aosc.benchmark.WriteLoadGenerator;

import org.opensearch.client.Request;
import org.opensearch.client.Response;
import org.opensearch.common.io.Streams;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * Scale validation tests — correctness at realistic data volumes.
 *
 * <p>These tests verify that AOSC migrations complete correctly with various
 * topologies (shard splits, custom routing, high shard counts) under write load.
 * Unlike benchmarks, the only gates are: migration completed + doc count match.
 *
 * <p>Usage:
 * <pre>
 *   ./gradlew scaleTest2Nodes                                         # split-quick (default)
 *   ./gradlew scaleTest2Nodes -Dscale.profile=routing-quick           # custom routing
 *   ./gradlew scaleTest2Nodes -Dscale.profile=split-routing-medium    # split + routing
 *   ./gradlew scaleTestDedicatedCM -Dscale.profile=high-shard         # 20-shard on 3-node
 *   ./gradlew scaleTestDocker -Dscale.profile=soak                    # long-running on Docker
 * </pre>
 */
public class ScaleMigrationIT extends ScaleTestBase {

    private final ScaleTestProfile profile = ScaleTestProfile.active();

    /**
     * Core scale test: seed → start migration with write load → wait → validate correctness.
     */
    public void testScaleMigration() throws Exception {
        String profileName = System.getProperty("scale.profile", "split-quick");
        int sourceShards = profile.paramInt("sourceShards");
        int targetShards = profile.paramInt("targetShards");
        int docs = profile.paramInt("docs");
        int writeRate = profile.paramInt("writeRate");
        int batchSize = profile.paramInt("batchSize");
        long timeoutMs = profile.paramLong("timeoutMs");
        boolean routing = profile.isUseCustomRouting();

        String suffix = profileName.replace("-", "_");
        String source = "scale-source-" + suffix;
        String target = "scale-target-" + suffix;

        LOG.info(
            "Scale test: profile="
                + profileName
                + " ["
                + profile.getDescription()
                + "]"
                + " docs="
                + docs
                + " shards="
                + sourceShards
                + "→"
                + targetShards
                + " routing="
                + routing
        );

        // Phase 1: Create indices and seed
        BulkDocSeeder seeder = new BulkDocSeeder(client(), 4);
        if (routing) {
            createIndexWithRouting(source, sourceShards, 0);
            createIndex(target, targetShards, 0);
            long seedMs = seeder.seedWithRouting(source, docs, batchSize);
            LOG.info("Seeded " + docs + " docs with routing in " + seedMs + "ms");
        } else {
            createIndex(source, sourceShards, 0);
            createIndex(target, targetShards, 0);
            long seedMs = seeder.seed(source, docs, batchSize);
            LOG.info("Seeded " + docs + " docs in " + seedMs + "ms");
        }

        // Phase 2: Start migration — keep write block so post-migration doc count assertion is valid
        startMigration(source, target, Map.of("remove_source_write_block_on_success", false));
        LOG.info("Migration started");

        // Phase 3: Write load during migration
        int writeBatchSize = profile.paramInt("writeBatchSize");
        int writerThreads = profile.paramInt("writerThreads");
        WriteLoadGenerator writeLoad = new WriteLoadGenerator(client(), source, writeRate, writeBatchSize, docs, writerThreads, routing);
        writeLoad.start();
        LOG.info("Write load started at " + writeRate + " ops/sec, " + writerThreads + " threads, batch=" + writeBatchSize);

        // Phase 4: Wait for completion
        String terminalPhase;
        try {
            terminalPhase = waitForTerminal(source, timeoutMs);
        } finally {
            writeLoad.close();
        }
        LOG.info(
            "Migration reached "
                + terminalPhase
                + " (write ops="
                + writeLoad.getTotalOps()
                + ", errors="
                + writeLoad.getErrors()
                + ", writeBlocks="
                + writeLoad.getWriteBlockErrors()
                + ")"
        );

        // Phase 5: Validate correctness — the only gates that matter
        assertEquals("Migration should complete successfully", "COMPLETED", terminalPhase);

        // B005 diagnostics: dump full status before asserting doc counts
        Request statusReq = new Request("GET", "/_plugins/_aosc/" + source + "/_status");
        Map<String, Object> fullStatus = entityAsMap(client().performRequest(statusReq));
        LOG.info("[B005-diag] Full migration status: " + fullStatus);

        // Check if source index is still write-blocked
        Request settingsReq = new Request("GET", "/" + source + "/_settings");
        Map<String, Object> settings = entityAsMap(client().performRequest(settingsReq));
        LOG.info("[B005-diag] Source index settings: " + settings);

        // Get source and target doc counts with refresh first
        client().performRequest(new Request("POST", "/" + source + "/_refresh"));
        client().performRequest(new Request("POST", "/" + target + "/_refresh"));
        long sourceCount = getDocCount(source);
        long targetCount = getDocCount(target);
        LOG.info(
            "[B005-diag] Doc counts after refresh: source="
                + sourceCount
                + " target="
                + targetCount
                + " delta="
                + (sourceCount - targetCount)
        );

        // Per-shard doc count comparison via _cat/shards (plain text)
        Request catShardsReq = new Request("GET", "/_cat/shards/" + source + "," + target + "?h=index,shard,docs&s=index,shard");
        Response catShardsResp = client().performRequest(catShardsReq);
        String catShardsText = Streams.copyToString(new InputStreamReader(catShardsResp.getEntity().getContent(), StandardCharsets.UTF_8));
        LOG.info("[B005-diag] Per-shard doc counts:\n" + catShardsText);

        assertMigrationCorrect(source, target);
        LOG.info("✅ Scale test passed: " + profileName);
    }
}
