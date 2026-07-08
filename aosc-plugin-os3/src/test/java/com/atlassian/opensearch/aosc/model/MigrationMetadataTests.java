/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */
package com.atlassian.opensearch.aosc.model;

import org.opensearch.common.io.stream.BytesStreamOutput;
import org.opensearch.common.xcontent.XContentFactory;
import org.opensearch.common.xcontent.json.JsonXContent;
import org.opensearch.core.common.io.stream.StreamInput;
import org.opensearch.core.xcontent.ToXContent;
import org.opensearch.core.xcontent.XContentParser;
import org.opensearch.test.OpenSearchTestCase;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

public class MigrationMetadataTests extends OpenSearchTestCase {

    public void testEmptyRoundTripStream() throws IOException {
        MigrationMetadata original = MigrationMetadata.EMPTY;
        MigrationMetadata deserialized = streamRoundTrip(original);
        assertEquals(original, deserialized);
        assertTrue(deserialized.isEmpty());
    }

    public void testRoundTripStream() throws IOException {
        MigrationMetadata original = MigrationMetadata.builder()
            .put(MigrationMetadata.ACTIVE_LEASE, "aosc-migration-test-0")
            .put(MigrationMetadata.WRITE_BLOCK_APPLIED, true)
            .put(MigrationMetadata.REBALANCE_DISABLED, false)
            .build();

        MigrationMetadata deserialized = streamRoundTrip(original);
        assertEquals(original, deserialized);
        assertEquals("aosc-migration-test-0", deserialized.get(MigrationMetadata.ACTIVE_LEASE));
        assertTrue(deserialized.getBoolean(MigrationMetadata.WRITE_BLOCK_APPLIED));
        assertFalse(deserialized.getBoolean(MigrationMetadata.REBALANCE_DISABLED));
    }

    public void testRoundTripXContent() throws IOException {
        MigrationMetadata original = MigrationMetadata.builder()
            .put(MigrationMetadata.ACTIVE_LEASE, "test-lease")
            .put(MigrationMetadata.WRITE_BLOCK_APPLIED, true)
            .build();

        String json = original.toXContent(XContentFactory.jsonBuilder(), ToXContent.EMPTY_PARAMS).toString();
        try (XContentParser parser = JsonXContent.jsonXContent.createParser(xContentRegistry(), null, json)) {
            MigrationMetadata deserialized = MigrationMetadata.fromXContent(parser);
            assertEquals(original, deserialized);
        }
    }

    public void testEmptyXContentRoundTrip() throws IOException {
        MigrationMetadata original = MigrationMetadata.EMPTY;
        String json = original.toXContent(XContentFactory.jsonBuilder(), ToXContent.EMPTY_PARAMS).toString();
        try (XContentParser parser = JsonXContent.jsonXContent.createParser(xContentRegistry(), null, json)) {
            MigrationMetadata deserialized = MigrationMetadata.fromXContent(parser);
            assertTrue(deserialized.isEmpty());
        }
    }

    public void testBuilderMerge() {
        MigrationMetadata base = MigrationMetadata.builder()
            .put(MigrationMetadata.ACTIVE_LEASE, "lease-1")
            .put(MigrationMetadata.WRITE_BLOCK_APPLIED, true)
            .build();

        MigrationMetadata updated = base.toBuilder()
            .put(MigrationMetadata.WRITE_BLOCK_APPLIED, false)
            .put(MigrationMetadata.REBALANCE_DISABLED, true)
            .build();

        assertEquals("lease-1", updated.get(MigrationMetadata.ACTIVE_LEASE));
        assertFalse(updated.getBoolean(MigrationMetadata.WRITE_BLOCK_APPLIED));
        assertTrue(updated.getBoolean(MigrationMetadata.REBALANCE_DISABLED));
    }

    public void testBuilderRemove() {
        MigrationMetadata base = MigrationMetadata.builder().put("key1", "val1").put("key2", "val2").build();

        MigrationMetadata updated = base.toBuilder().remove("key1").build();
        assertNull(updated.get("key1"));
        assertEquals("val2", updated.get("key2"));
    }

    public void testGetBooleanDefaults() {
        MigrationMetadata empty = MigrationMetadata.EMPTY;
        assertFalse(empty.getBoolean("nonexistent"));
    }

    public void testGetLongDefaults() {
        MigrationMetadata empty = MigrationMetadata.EMPTY;
        assertEquals(-1L, empty.getLong("nonexistent", -1));

        MigrationMetadata bad = MigrationMetadata.builder().put("bad", "notanumber").build();
        assertEquals(42L, bad.getLong("bad", 42));
    }

    private static MigrationMetadata streamRoundTrip(MigrationMetadata original) throws IOException {
        BytesStreamOutput out = new BytesStreamOutput();
        original.writeTo(out);
        StreamInput in = out.bytes().streamInput();
        return new MigrationMetadata(in);
    }

    public void testPutAndGetOriginalTargetSettings() {
        Map<String, String> originals = new HashMap<>();
        originals.put("index.number_of_replicas", "1");
        originals.put("index.refresh_interval", null); // null = was at default

        MigrationMetadata meta = MigrationMetadata.builder().putOriginalTargetSettings(originals).build();

        Map<String, String> retrieved = meta.originalTargetSettings();
        assertEquals(2, retrieved.size());
        assertEquals("1", retrieved.get("index.number_of_replicas"));
        assertNull(retrieved.get("index.refresh_interval"));
    }

    public void testOriginalTargetSettingsEmpty() {
        MigrationMetadata meta = MigrationMetadata.builder().put(MigrationMetadata.WRITE_BLOCK_APPLIED, true).build();
        assertTrue(meta.originalTargetSettings().isEmpty());
    }

    public void testOriginalTargetSettingsPrefixIsolation() {
        // Keys without prefix should NOT appear in originalTargetSettings()
        MigrationMetadata meta = MigrationMetadata.builder()
            .put("some.other.key", "value")
            .putOriginalTargetSettings(Map.of("index.refresh_interval", "30s"))
            .build();

        Map<String, String> retrieved = meta.originalTargetSettings();
        assertEquals(1, retrieved.size());
        assertEquals("30s", retrieved.get("index.refresh_interval"));
        assertFalse(retrieved.containsKey("some.other.key"));
    }

    public void testOriginalTargetSettingsWriteableRoundtrip() throws IOException {
        MigrationMetadata original = MigrationMetadata.builder()
            .putOriginalTargetSettings(Map.of("index.number_of_replicas", "1", "index.translog.durability", "request"))
            .build();

        BytesStreamOutput out = new BytesStreamOutput();
        original.writeTo(out);
        MigrationMetadata rt = new MigrationMetadata(out.bytes().streamInput());

        assertEquals(original, rt);
        Map<String, String> settings = rt.originalTargetSettings();
        assertEquals("1", settings.get("index.number_of_replicas"));
        assertEquals("request", settings.get("index.translog.durability"));
    }
}
