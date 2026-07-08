/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */
package com.atlassian.opensearch.aosc.benchmark;

import lombok.Builder;
import lombok.Getter;

import org.opensearch.common.Booleans;

import java.util.Map;

/**
 * Benchmark profile presets.
 *
 * <p>Select with {@code -Dbenchmark.profile=quick|medium|heavy|stress|endurance}.
 * Override individual params with {@code -Dbenchmark.docs=50000}.
 */
@Getter
@Builder
public class BenchmarkProfile {

    @Builder.Default
    private final long docs = 10_000;
    @Builder.Default
    private final long writeRate = 1_000;
    @Builder.Default
    private final long shards = 2;
    @Builder.Default
    private final long targetShards = -1; // -1 means same as source shards
    @Builder.Default
    private final long batchSize = 500;
    @Builder.Default
    private final long writeBatchSize = 500;
    @Builder.Default
    private final long writerThreads = 1;
    @Builder.Default
    private final long seedThreads = 4;
    @Builder.Default
    private final long timeoutMs = 120_000;
    @Builder.Default
    private final long maxDurationMs = 30_000;
    @Builder.Default
    private final long maxWriteBlockMs = 5_000;
    @Builder.Default
    private final long minBackfillDocsPerSec = 500;
    @Builder.Default
    private final boolean useCustomRouting = false;

    /** Effective target shards — falls back to source shards if not set. */
    public long effectiveTargetShards() {
        long ts = param("targetShards");
        return ts < 0 ? param("shards") : ts;
    }

    // ---- Profile presets ----
    // Thresholds include ~3× headroom over local measurements to accommodate CI runners.
    // Local baseline (M-series Mac): quick ~8s, medium ~10s, heavy ~40s, stress ~90s.

    private static final BenchmarkProfile QUICK = BenchmarkProfile.builder().seedThreads(1).build();

    private static final BenchmarkProfile MEDIUM = BenchmarkProfile.builder()
        .docs(100_000)
        .writeRate(5_000)
        .shards(5)
        .batchSize(1_000)
        .writeBatchSize(1_000)
        .writerThreads(2)
        .seedThreads(2)
        .timeoutMs(300_000)
        .maxDurationMs(60_000)
        .maxWriteBlockMs(5_000)
        .minBackfillDocsPerSec(2_000)
        .build();

    private static final BenchmarkProfile HEAVY = BenchmarkProfile.builder()
        .docs(500_000)
        .writeRate(0)
        .shards(10)
        .batchSize(2_000)
        .writeBatchSize(2_000)
        .writerThreads(4)
        .seedThreads(4)
        .timeoutMs(600_000)
        .maxDurationMs(120_000)
        .maxWriteBlockMs(10_000)
        .minBackfillDocsPerSec(2_000)
        .build();

    private static final BenchmarkProfile STRESS = BenchmarkProfile.builder()
        .docs(1_000_000)
        .writeRate(0)
        .shards(20)
        .batchSize(5_000)
        .writeBatchSize(2_000)
        .writerThreads(4)
        .seedThreads(8)
        .timeoutMs(1_800_000)
        .maxDurationMs(300_000)
        .maxWriteBlockMs(15_000)
        .minBackfillDocsPerSec(2_000)
        .build();

    private static final BenchmarkProfile ENDURANCE = BenchmarkProfile.builder()
        .docs(5_000_000)
        .writeRate(0)
        .shards(10)
        .batchSize(5_000)
        .writeBatchSize(2_000)
        .writerThreads(4)
        .seedThreads(8)
        .timeoutMs(600_000)
        .maxDurationMs(300_000)
        .maxWriteBlockMs(30_000)
        .minBackfillDocsPerSec(500)
        .build();

    // T029 profiles: adaptive batching + concurrent writers
    private static final BenchmarkProfile ADAPTIVE_MEDIUM = BenchmarkProfile.builder()
        .docs(100_000)
        .writeRate(5_000)
        .shards(5)
        .batchSize(1_000)
        .writeBatchSize(1_000)
        .writerThreads(2)
        .seedThreads(2)
        .timeoutMs(300_000)
        .maxDurationMs(90_000)
        .maxWriteBlockMs(5_000)
        .minBackfillDocsPerSec(1_500)
        .build();

    private static final BenchmarkProfile CONCURRENT_MEDIUM = BenchmarkProfile.builder()
        .docs(100_000)
        .writeRate(0)
        .shards(5)
        .batchSize(1_000)
        .writeBatchSize(1_000)
        .writerThreads(0)
        .seedThreads(2)
        .timeoutMs(300_000)
        .maxDurationMs(60_000)
        .maxWriteBlockMs(5_000)
        .minBackfillDocsPerSec(3_000)
        .build();

    private static final BenchmarkProfile LARGE_DOCS_ADAPTIVE = BenchmarkProfile.builder()
        .docs(2_000)
        .writeRate(0)
        .shards(2)
        .batchSize(500)
        .writeBatchSize(100)
        .writerThreads(1)
        .seedThreads(1)
        .timeoutMs(600_000)
        .maxDurationMs(300_000)
        .maxWriteBlockMs(30_000)
        .minBackfillDocsPerSec(10)
        .build();

    private static final Map<String, BenchmarkProfile> PROFILES = Map.ofEntries(
        Map.entry("quick", QUICK),
        Map.entry("medium", MEDIUM),
        Map.entry("heavy", HEAVY),
        Map.entry("stress", STRESS),
        Map.entry("endurance", ENDURANCE),
        Map.entry("adaptive-medium", ADAPTIVE_MEDIUM),
        Map.entry("concurrent-medium", CONCURRENT_MEDIUM),
        Map.entry("large-docs-adaptive", LARGE_DOCS_ADAPTIVE)
    );

    /**
     * CI multiplier for threshold relaxation.
     * Set {@code -Dbenchmark.ci=true} in CI environments
     * where machines are slower. Relaxes duration/write-block thresholds by 3× and
     * reduces throughput minimums by 3×.
     */
    private static final boolean CI_MODE = Booleans.parseBoolean(System.getProperty("benchmark.ci"), false);
    private static final long CI_DURATION_MULTIPLIER = 3;
    private static final long CI_THROUGHPUT_DIVISOR = 3;

    /** Resolve the active profile from {@code -Dbenchmark.profile}. Defaults to QUICK. */
    public static BenchmarkProfile active() {
        String name = System.getProperty("benchmark.profile", "quick");
        BenchmarkProfile profile = PROFILES.get(name);
        if (profile == null) {
            throw new IllegalArgumentException("Unknown benchmark profile: " + name + ". Valid: " + PROFILES.keySet());
        }
        return profile;
    }

    /** Get a param value, allowing {@code -Dbenchmark.X} overrides. */
    public long param(String key) {
        String override = System.getProperty("benchmark." + key);
        if (override != null) {
            return Long.parseLong(override);
        }
        long value;
        switch (key) {
            case "docs":
                value = docs;
                break;
            case "writeRate":
                value = writeRate;
                break;
            case "shards":
                value = shards;
                break;
            case "targetShards":
                value = targetShards;
                break;
            case "batchSize":
                value = batchSize;
                break;
            case "writeBatchSize":
                value = writeBatchSize;
                break;
            case "writerThreads":
                value = writerThreads;
                break;
            case "seedThreads":
                value = seedThreads;
                break;
            case "timeoutMs":
                value = timeoutMs;
                break;
            case "maxDurationMs":
                value = maxDurationMs;
                break;
            case "maxWriteBlockMs":
                value = maxWriteBlockMs;
                break;
            case "minBackfillDocsPerSec":
                value = minBackfillDocsPerSec;
                break;
            default:
                throw new IllegalArgumentException("Unknown param: " + key);
        }
        if (CI_MODE) {
            switch (key) {
                case "maxDurationMs":
                case "maxWriteBlockMs":
                case "timeoutMs":
                    value *= CI_DURATION_MULTIPLIER;
                    break;
                case "minBackfillDocsPerSec":
                    value /= CI_THROUGHPUT_DIVISOR;
                    break;
                default:
                    break;
            }
        }
        return value;
    }
}
