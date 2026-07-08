/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */
package com.atlassian.opensearch.aosc.benchmark;

import org.opensearch.common.io.PathUtils;
import org.opensearch.test.rest.OpenSearchRestTestCase;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;

/**
 * Base class for AOSC benchmark tests.
 * Delegates migration lifecycle and validation to {@link AoscTestUtils}.
 */
public abstract class AoscBenchmarkBase extends OpenSearchRestTestCase {

    protected static final int DEFAULT_POLL_INTERVAL_MS = 500;

    // --- REST Helpers ---

    protected Map<String, Object> startMigration(String source, String target) throws IOException {
        return AoscTestUtils.startMigration(client(), source, target);
    }

    // --- Polling ---

    protected String waitForTerminal(String source, long timeoutMs) throws Exception {
        return AoscTestUtils.waitForTerminal(client(), source, timeoutMs, DEFAULT_POLL_INTERVAL_MS);
    }

    // --- Doc Counts ---

    protected long getDocCount(String index) throws IOException {
        return AoscTestUtils.getDocCount(client(), index);
    }

    // --- Index Creation ---

    protected void createIndex(String name, int shards, int replicas) throws IOException {
        AoscTestUtils.createIndex(client(), name, shards, replicas);
        ensureGreen(name);
    }

    protected void createIndexWithRouting(String name, int shards, int replicas) throws IOException {
        AoscTestUtils.createIndexWithRouting(client(), name, shards, replicas);
        ensureGreen(name);
    }

    // --- Output ---

    protected Path benchmarkOutputDir(String profile) {
        return PathUtils.get("benchmarks", "results", profile + "-" + System.currentTimeMillis());
    }

}
