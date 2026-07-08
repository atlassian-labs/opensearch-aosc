/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */
package com.atlassian.opensearch.aosc.scale;

import lombok.Builder;
import lombok.Getter;

import java.util.Map;

/**
 * Scale test profiles — correctness validation at realistic data volumes.
 *
 * <p>Unlike benchmarks, scale tests only gate on: migration completed + doc count match.
 * Performance thresholds are generous (we don't care about speed, just correctness).
 *
 * <p>Select with {@code -Dscale.profile=split-quick|routing-quick|soak|...}
 */
@Getter
@Builder
public class ScaleTestProfile {

    @Builder.Default
    private final int docs = 10_000;
    @Builder.Default
    private final int sourceShards = 2;
    @Builder.Default
    private final int targetShards = 2;
    @Builder.Default
    private final int writeRate = 100;
    @Builder.Default
    private final int batchSize = 500;
    @Builder.Default
    private final int writerThreads = 1;
    @Builder.Default
    private final int writeBatchSize = 500;
    @Builder.Default
    private final long timeoutMs = 300_000;
    @Builder.Default
    private final boolean useCustomRouting = false;
    @Builder.Default
    private final String description = "";

    public boolean isSplit() {
        return sourceShards != targetShards;
    }

    // ---- Shard-split profiles ----

    private static final ScaleTestProfile SPLIT_QUICK = ScaleTestProfile.builder()
        .docs(10_000)
        .sourceShards(2)
        .targetShards(4)
        .writeRate(100)
        .description("Split 2→4, 10K docs")
        .build();

    private static final ScaleTestProfile SPLIT_MEDIUM = ScaleTestProfile.builder()
        .docs(50_000)
        .sourceShards(3)
        .targetShards(6)
        .writeRate(500)
        .batchSize(1_000)
        .timeoutMs(600_000)
        .description("Split 3→6, 50K docs")
        .build();

    private static final ScaleTestProfile SPLIT_HEAVY = ScaleTestProfile.builder()
        .docs(200_000)
        .sourceShards(5)
        .targetShards(10)
        .writeRate(2_000)
        .batchSize(2_000)
        .writerThreads(2)
        .writeBatchSize(1_000)
        .timeoutMs(1_200_000)
        .description("Split 5→10, 200K docs")
        .build();

    // ---- Routing-aware profiles ----

    private static final ScaleTestProfile ROUTING_QUICK = ScaleTestProfile.builder()
        .docs(10_000)
        .sourceShards(2)
        .targetShards(2)
        .writeRate(100)
        .useCustomRouting(true)
        .description("Same-shard with custom routing, 10K docs")
        .build();

    private static final ScaleTestProfile ROUTING_MEDIUM = ScaleTestProfile.builder()
        .docs(50_000)
        .sourceShards(5)
        .targetShards(5)
        .writeRate(500)
        .batchSize(1_000)
        .timeoutMs(600_000)
        .useCustomRouting(true)
        .description("Same-shard with custom routing, 50K docs")
        .build();

    // ---- Split + routing ----

    private static final ScaleTestProfile SPLIT_ROUTING_QUICK = ScaleTestProfile.builder()
        .docs(10_000)
        .sourceShards(2)
        .targetShards(4)
        .writeRate(100)
        .useCustomRouting(true)
        .description("Split 2→4 with custom routing, 10K docs")
        .build();

    private static final ScaleTestProfile SPLIT_ROUTING_MEDIUM = ScaleTestProfile.builder()
        .docs(50_000)
        .sourceShards(3)
        .targetShards(6)
        .writeRate(500)
        .batchSize(1_000)
        .timeoutMs(600_000)
        .useCustomRouting(true)
        .description("Split 3→6 with custom routing, 50K docs")
        .build();

    // ---- Soak profile (long-running stability) ----

    private static final ScaleTestProfile SOAK = ScaleTestProfile.builder()
        .docs(100_000)
        .sourceShards(5)
        .targetShards(5)
        .writeRate(1_000)
        .batchSize(1_000)
        .writerThreads(2)
        .writeBatchSize(500)
        .timeoutMs(3_600_000)
        .description("Long-running stability, 100K docs, 1K ops/sec")
        .build();

    // ---- High-shard profiles ----

    private static final ScaleTestProfile HIGH_SHARD = ScaleTestProfile.builder()
        .docs(50_000)
        .sourceShards(20)
        .targetShards(20)
        .writeRate(500)
        .batchSize(1_000)
        .timeoutMs(600_000)
        .description("20-shard same-shard migration, 50K docs")
        .build();

    private static final ScaleTestProfile HIGH_SHARD_SPLIT = ScaleTestProfile.builder()
        .docs(50_000)
        .sourceShards(10)
        .targetShards(20)
        .writeRate(500)
        .batchSize(1_000)
        .timeoutMs(600_000)
        .description("Split 10→20, 50K docs")
        .build();

    private static final Map<String, ScaleTestProfile> PROFILES = Map.ofEntries(
        Map.entry("split-quick", SPLIT_QUICK),
        Map.entry("split-medium", SPLIT_MEDIUM),
        Map.entry("split-heavy", SPLIT_HEAVY),
        Map.entry("routing-quick", ROUTING_QUICK),
        Map.entry("routing-medium", ROUTING_MEDIUM),
        Map.entry("split-routing-quick", SPLIT_ROUTING_QUICK),
        Map.entry("split-routing-medium", SPLIT_ROUTING_MEDIUM),
        Map.entry("soak", SOAK),
        Map.entry("high-shard", HIGH_SHARD),
        Map.entry("high-shard-split", HIGH_SHARD_SPLIT)
    );

    /** Resolve profile from {@code -Dscale.profile}. Defaults to split-quick. */
    public static ScaleTestProfile active() {
        String name = System.getProperty("scale.profile", "split-quick");
        ScaleTestProfile profile = PROFILES.get(name);
        if (profile == null) {
            throw new IllegalArgumentException("Unknown scale profile: " + name + ". Valid: " + PROFILES.keySet());
        }
        return profile;
    }

    /** Allow per-param overrides via {@code -Dscale.X}. */
    public int paramInt(String key) {
        String override = System.getProperty("scale." + key);
        if (override != null) return Integer.parseInt(override);
        switch (key) {
            case "docs":
                return docs;
            case "sourceShards":
                return sourceShards;
            case "targetShards":
                return targetShards;
            case "writeRate":
                return writeRate;
            case "batchSize":
                return batchSize;
            case "writerThreads":
                return writerThreads;
            case "writeBatchSize":
                return writeBatchSize;
            default:
                throw new IllegalArgumentException("Unknown param: " + key);
        }
    }

    public long paramLong(String key) {
        String override = System.getProperty("scale." + key);
        if (override != null) return Long.parseLong(override);
        if ("timeoutMs".equals(key)) return timeoutMs;
        throw new IllegalArgumentException("Unknown param: " + key);
    }
}
