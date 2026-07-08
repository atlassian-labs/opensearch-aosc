/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */
package com.atlassian.opensearch.aosc.scale;

import com.atlassian.opensearch.aosc.benchmark.AoscTestUtils;

import org.opensearch.test.rest.OpenSearchRestTestCase;

import java.io.IOException;
import java.util.Map;
import java.util.logging.Logger;

/**
 * Base class for scale validation tests.
 * Delegates migration lifecycle and validation to {@link AoscTestUtils}.
 */
public abstract class ScaleTestBase extends OpenSearchRestTestCase {

    protected static final Logger LOG = Logger.getLogger(ScaleTestBase.class.getName());

    @Override
    protected boolean preserveIndicesUponCompletion() {
        return true;
    }

    // --- Migration REST helpers ---

    protected Map<String, Object> startMigration(String source, String target) throws IOException {
        return AoscTestUtils.startMigration(client(), source, target);
    }

    protected Map<String, Object> startMigration(String source, String target, Map<String, Object> options) throws IOException {
        return AoscTestUtils.startMigration(client(), source, target, source + "-alias", options);
    }

    protected String getCoordinatorPhase(String source) throws IOException {
        return AoscTestUtils.getCoordinatorPhase(client(), source);
    }

    protected String waitForTerminal(String source, long timeoutMs) throws Exception {
        return AoscTestUtils.waitForTerminal(client(), source, timeoutMs, 500);
    }

    // --- Index helpers ---

    protected void createIndex(String name, int shards, int replicas) throws IOException {
        AoscTestUtils.createIndex(client(), name, shards, replicas);
        ensureGreen(name);
    }

    protected void createIndexWithRouting(String name, int shards, int replicas) throws IOException {
        AoscTestUtils.createIndexWithRouting(client(), name, shards, replicas);
        ensureGreen(name);
    }

    // --- Validation ---

    protected long getDocCount(String index) throws IOException {
        return AoscTestUtils.getDocCount(client(), index);
    }

    protected void assertMigrationCorrect(String source, String target) throws IOException {
        AoscTestUtils.assertDocCountMatch(client(), source, target);
    }
}
