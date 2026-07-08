/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */
package com.atlassian.opensearch.aosc.utils;

import org.opensearch.Version;
import org.opensearch.cluster.metadata.IndexMetadata;
import org.opensearch.common.settings.Settings;
import org.opensearch.test.OpenSearchTestCase;

import java.util.Map;
import java.util.Set;

public class IndexOperationUtilsCaptureSettingsTests extends OpenSearchTestCase {

    private IndexMetadata buildMeta(Settings settings) {
        return IndexMetadata.builder("test-index")
            .settings(
                Settings.builder()
                    .put(IndexMetadata.SETTING_VERSION_CREATED, Version.CURRENT)
                    .put(IndexMetadata.SETTING_NUMBER_OF_SHARDS, 1)
                    .put(IndexMetadata.SETTING_NUMBER_OF_REPLICAS, 1)
                    .put(settings)
            )
            .build();
    }

    public void testCaptureExplicitSettings() {
        IndexMetadata meta = buildMeta(
            Settings.builder().put("index.refresh_interval", "30s").put("index.translog.durability", "async").build()
        );

        Map<String, String> captured = IndexOperationUtils.captureSettings(
            meta,
            Set.of("index.refresh_interval", "index.translog.durability")
        );

        assertEquals(2, captured.size());
        assertEquals("30s", captured.get("index.refresh_interval"));
        assertEquals("async", captured.get("index.translog.durability"));
    }

    public void testCaptureDefaultSettings() {
        // index.refresh_interval not explicitly set — should capture null
        IndexMetadata meta = buildMeta(Settings.EMPTY);

        Map<String, String> captured = IndexOperationUtils.captureSettings(
            meta,
            Set.of("index.refresh_interval", "index.translog.durability")
        );

        assertEquals(2, captured.size());
        assertNull(captured.get("index.refresh_interval"));
        assertNull(captured.get("index.translog.durability"));
    }

    public void testCaptureMixedExplicitAndDefault() {
        IndexMetadata meta = buildMeta(Settings.builder().put("index.refresh_interval", "5s").build());

        Map<String, String> captured = IndexOperationUtils.captureSettings(
            meta,
            Set.of("index.refresh_interval", "index.translog.durability")
        );

        assertEquals("5s", captured.get("index.refresh_interval"));
        assertNull(captured.get("index.translog.durability"));
    }

    public void testCaptureEmptyKeySet() {
        IndexMetadata meta = buildMeta(Settings.EMPTY);
        Map<String, String> captured = IndexOperationUtils.captureSettings(meta, Set.of());
        assertTrue(captured.isEmpty());
    }
}
