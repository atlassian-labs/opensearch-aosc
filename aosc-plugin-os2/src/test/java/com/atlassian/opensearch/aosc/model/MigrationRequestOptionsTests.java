/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */
package com.atlassian.opensearch.aosc.model;

import com.atlassian.opensearch.aosc.AoscSettings;
import com.atlassian.opensearch.aosc.AoscTestUtil;

import org.opensearch.action.ActionRequestValidationException;
import org.opensearch.common.io.stream.BytesStreamOutput;
import org.opensearch.common.settings.ClusterSettings;
import org.opensearch.common.settings.Settings;
import org.opensearch.common.xcontent.XContentFactory;
import org.opensearch.common.xcontent.json.JsonXContent;
import org.opensearch.core.xcontent.DeprecationHandler;
import org.opensearch.core.xcontent.ToXContent;
import org.opensearch.core.xcontent.XContentBuilder;
import org.opensearch.core.xcontent.XContentParser;
import org.opensearch.test.OpenSearchTestCase;

import java.io.IOException;
import java.util.HashSet;
import java.util.Map;

public class MigrationRequestOptionsTests extends OpenSearchTestCase {

    // ---- Writeable round-trip: all fields ----
    public void testWriteableRoundTrip() throws IOException {
        MigrationRequestOptions original = new MigrationRequestOptions().setConvergenceThresholdPerShard(10)
            .setMaxConvergenceRoundsPerShard(5)
            .setDocCountTolerance(0)
            .setAcceptDataLossIfCustomRoutingIsUsed(true);

        BytesStreamOutput out = new BytesStreamOutput();
        original.writeTo(out);
        MigrationRequestOptions rt = new MigrationRequestOptions(out.bytes().streamInput());

        assertEquals(Integer.valueOf(10), rt.getConvergenceThresholdPerShard());
        assertEquals(Integer.valueOf(5), rt.getMaxConvergenceRoundsPerShard());
        assertEquals(Integer.valueOf(0), rt.getDocCountTolerance());
        assertEquals(Boolean.TRUE, rt.getAcceptDataLossIfCustomRoutingIsUsed());
    }

    // ---- Writeable round-trip: all nulls ----
    public void testWriteableRoundTripNulls() throws IOException {
        MigrationRequestOptions original = new MigrationRequestOptions();

        BytesStreamOutput out = new BytesStreamOutput();
        original.writeTo(out);
        MigrationRequestOptions rt = new MigrationRequestOptions(out.bytes().streamInput());

        assertNull(rt.getConvergenceThresholdPerShard());
        assertNull(rt.getMaxConvergenceRoundsPerShard());
        assertNull(rt.getDocCountTolerance());
        assertNull(rt.getAcceptDataLossIfCustomRoutingIsUsed());
    }

    // ---- XContent round-trip ----
    public void testXContentRoundTrip() throws IOException {
        MigrationRequestOptions original = new MigrationRequestOptions()

            .setConvergenceThresholdPerShard(1000)
            .setMaxConvergenceRoundsPerShard(0)
            .setDocCountTolerance(0)
            .setAcceptDataLossIfCustomRoutingIsUsed(false);

        XContentBuilder builder = XContentFactory.jsonBuilder();
        original.toXContent(builder, ToXContent.EMPTY_PARAMS);
        String json = builder.toString();

        XContentParser parser = JsonXContent.jsonXContent.createParser(
            xContentRegistry(),
            DeprecationHandler.THROW_UNSUPPORTED_OPERATION,
            json
        );
        parser.nextToken();
        MigrationRequestOptions rt = MigrationRequestOptions.fromXContent(parser);

        assertEquals(original.getConvergenceThresholdPerShard(), rt.getConvergenceThresholdPerShard());
        assertEquals(original.getMaxConvergenceRoundsPerShard(), rt.getMaxConvergenceRoundsPerShard());
        assertEquals(original.getDocCountTolerance(), rt.getDocCountTolerance());
        assertEquals(original.getAcceptDataLossIfCustomRoutingIsUsed(), rt.getAcceptDataLossIfCustomRoutingIsUsed());
    }

    // ---- XContent omits nulls ----
    public void testXContentOmitsNulls() throws IOException {
        MigrationRequestOptions options = new MigrationRequestOptions();

        XContentBuilder builder = XContentFactory.jsonBuilder();
        options.toXContent(builder, ToXContent.EMPTY_PARAMS);
        String json = builder.toString();

        assertEquals("{}", json);
    }

    // ---- mergeWith: user options override defaults ----
    public void testMergeWithUserOptionsOverrideDefaults() {
        MigrationRequestOptions defaults = AoscTestUtil.defaultMigrationOptions();
        MigrationRequestOptions userOptions = new MigrationRequestOptions().setDocCountTolerance(999);

        MigrationRequestOptions merged = defaults.mergeWith(userOptions);

        // User-specified fields should win
        assertEquals("User docCountTolerance should override default", Integer.valueOf(999), merged.getDocCountTolerance());

        // Unset user fields should fall back to defaults
        assertEquals(
            "Default convergenceThresholdPerShard should be preserved",
            defaults.getConvergenceThresholdPerShard(),
            merged.getConvergenceThresholdPerShard()
        );
        assertEquals(
            "Default maxConvergenceRoundsPerShard should be preserved",
            defaults.getMaxConvergenceRoundsPerShard(),
            merged.getMaxConvergenceRoundsPerShard()
        );
        assertEquals(
            "Default acceptDataLoss should be preserved",
            defaults.getAcceptDataLossIfCustomRoutingIsUsed(),
            merged.getAcceptDataLossIfCustomRoutingIsUsed()
        );
    }

    // ---- mergeWith: null user options returns copy of defaults ----
    public void testMergeWithNullReturnsDefaults() {
        MigrationRequestOptions defaults = AoscTestUtil.defaultMigrationOptions();

        MigrationRequestOptions merged = defaults.mergeWith(null);

        assertEquals(defaults.getConvergenceThresholdPerShard(), merged.getConvergenceThresholdPerShard());
        assertEquals(defaults.getMaxConvergenceRoundsPerShard(), merged.getMaxConvergenceRoundsPerShard());
        assertEquals(defaults.getDocCountTolerance(), merged.getDocCountTolerance());
        assertEquals(defaults.getAcceptDataLossIfCustomRoutingIsUsed(), merged.getAcceptDataLossIfCustomRoutingIsUsed());
    }

    // ---- mergeWith: all user fields set replaces all defaults ----
    public void testMergeWithAllFieldsSetReplacesDefaults() {
        MigrationRequestOptions defaults = AoscTestUtil.defaultMigrationOptions();
        MigrationRequestOptions userOptions = new MigrationRequestOptions().setConvergenceThresholdPerShard(300)
            .setMaxConvergenceRoundsPerShard(400)
            .setDocCountTolerance(5)
            .setAcceptDataLossIfCustomRoutingIsUsed(true);

        MigrationRequestOptions merged = defaults.mergeWith(userOptions);

        assertEquals(Integer.valueOf(300), merged.getConvergenceThresholdPerShard());
        assertEquals(Integer.valueOf(400), merged.getMaxConvergenceRoundsPerShard());
        assertEquals(Integer.valueOf(5), merged.getDocCountTolerance());
        assertEquals(Boolean.TRUE, merged.getAcceptDataLossIfCustomRoutingIsUsed());
    }

    public void testValidateAcceptsValidOptions() {
        MigrationRequestOptions opts = new MigrationRequestOptions()

            .setConvergenceThresholdPerShard(100)
            .setMaxConvergenceRoundsPerShard(50)
            .setDocCountTolerance(0)
            .setAcceptDataLossIfCustomRoutingIsUsed(false);
        assertNull("Valid options should pass validation", opts.validate());
    }

    public void testValidateRejectsNegativeDocCountTolerance() {
        MigrationRequestOptions opts = new MigrationRequestOptions().setDocCountTolerance(-1);
        ActionRequestValidationException ex = opts.validate();
        assertNotNull(ex);
        assertTrue(ex.getMessage().contains("doc_count_tolerance"));
    }

    public void testValidateRejectsZeroMaxConvergenceRounds() {
        MigrationRequestOptions opts = new MigrationRequestOptions().setMaxConvergenceRoundsPerShard(0);
        ActionRequestValidationException ex = opts.validate();
        assertNotNull(ex);
        assertTrue(ex.getMessage().contains("max_convergence_rounds_per_shard"));
    }

    public void testValidateRejectsTargetReadyTimeoutBelowClusterMinimum() {
        MigrationRequestOptions opts = new MigrationRequestOptions().setTargetReadyTimeoutSeconds(59);
        ActionRequestValidationException ex = opts.validate();
        assertNotNull(ex);
        assertTrue(ex.getMessage().contains("target_ready_timeout_seconds"));
    }

    public void testValidateAcceptsMinimumTargetReadyTimeout() {
        MigrationRequestOptions opts = new MigrationRequestOptions().setTargetReadyTimeoutSeconds(60);
        assertNull(opts.validate());
    }

    public void testValidateAcceptsNulls() {
        MigrationRequestOptions opts = new MigrationRequestOptions();
        assertNull("All-null options should pass validation", opts.validate());
    }

    public void testValidateAcceptsBoundaryValues() {
        MigrationRequestOptions opts = new MigrationRequestOptions().setConvergenceThresholdPerShard(0)
            .setMaxConvergenceRoundsPerShard(1)
            .setDocCountTolerance(0);
        assertNull("Boundary values should pass validation", opts.validate());
    }

    public void testTransientTargetSettingsRoundtripWriteable() throws IOException {
        MigrationRequestOptions original = new MigrationRequestOptions().setTransientTargetSettings(
            Map.of("index.number_of_replicas", "0", "index.refresh_interval", "-1")
        );

        BytesStreamOutput out = new BytesStreamOutput();
        original.writeTo(out);
        MigrationRequestOptions rt = new MigrationRequestOptions(out.bytes().streamInput());

        assertEquals(Map.of("index.number_of_replicas", "0", "index.refresh_interval", "-1"), rt.getTransientTargetSettings());
    }

    public void testTargetReadyTimeoutSecondsRoundtripWriteable() throws IOException {
        MigrationRequestOptions original = new MigrationRequestOptions().setTargetReadyTimeoutSeconds(3600);
        BytesStreamOutput out = new BytesStreamOutput();
        original.writeTo(out);
        MigrationRequestOptions rt = new MigrationRequestOptions(out.bytes().streamInput());
        assertEquals(Integer.valueOf(3600), rt.getTargetReadyTimeoutSeconds());
    }

    public void testTransientTargetSettingsXContentRoundtrip() throws IOException {
        MigrationRequestOptions original = new MigrationRequestOptions().setTransientTargetSettings(
            Map.of("index.number_of_replicas", "0")
        );

        XContentBuilder builder = XContentFactory.jsonBuilder();
        original.toXContent(builder, ToXContent.EMPTY_PARAMS);
        String json = builder.toString();
        assertTrue(json.contains("transient_target_settings"));

        XContentParser parser = JsonXContent.jsonXContent.createParser(
            xContentRegistry(),
            DeprecationHandler.THROW_UNSUPPORTED_OPERATION,
            json
        );
        parser.nextToken();
        MigrationRequestOptions rt = MigrationRequestOptions.fromXContent(parser);
        assertEquals(Map.of("index.number_of_replicas", "0"), rt.getTransientTargetSettings());
    }

    public void testValidationTransientTargetSettingsInvalidKey() {
        MigrationRequestOptions opts = new MigrationRequestOptions().setTransientTargetSettings(Map.of("cluster.setting", "value"));
        ActionRequestValidationException ex = opts.validate();
        assertNotNull(ex);
        assertTrue(ex.getMessage().contains("must start with 'index.'"));
    }

    public void testMergeWithTransientSettings() {
        MigrationRequestOptions base = new MigrationRequestOptions().setTransientTargetSettings(Map.of("index.refresh_interval", "-1"));
        MigrationRequestOptions override = new MigrationRequestOptions().setTransientTargetSettings(
            Map.of("index.number_of_replicas", "0")
        );

        MigrationRequestOptions merged = base.mergeWith(override);
        assertEquals(Map.of("index.number_of_replicas", "0"), merged.getTransientTargetSettings());
    }

    public void testNullTransientTargetSettingsWriteable() throws IOException {
        MigrationRequestOptions original = new MigrationRequestOptions();
        BytesStreamOutput out = new BytesStreamOutput();
        original.writeTo(out);
        MigrationRequestOptions rt = new MigrationRequestOptions(out.bytes().streamInput());
        assertNull(rt.getTransientTargetSettings());
    }

    // ---- remove_source_write_block_on_success ----

    public void testRemoveSourceWriteBlockOnSuccessRoundtripWriteable() throws IOException {
        for (Boolean value : new Boolean[] { Boolean.TRUE, Boolean.FALSE, null }) {
            MigrationRequestOptions original = new MigrationRequestOptions().setRemoveSourceWriteBlockOnSuccess(value);
            BytesStreamOutput out = new BytesStreamOutput();
            original.writeTo(out);
            MigrationRequestOptions rt = new MigrationRequestOptions(out.bytes().streamInput());
            assertEquals("value=" + value, value, rt.getRemoveSourceWriteBlockOnSuccess());
        }
    }

    public void testRemoveSourceWriteBlockOnSuccessXContentRoundtrip() throws IOException {
        MigrationRequestOptions original = new MigrationRequestOptions().setRemoveSourceWriteBlockOnSuccess(false);

        XContentBuilder builder = XContentFactory.jsonBuilder();
        original.toXContent(builder, ToXContent.EMPTY_PARAMS);
        String json = builder.toString();
        assertTrue("JSON should contain new field, got: " + json, json.contains("remove_source_write_block_on_success"));

        XContentParser parser = JsonXContent.jsonXContent.createParser(
            xContentRegistry(),
            DeprecationHandler.THROW_UNSUPPORTED_OPERATION,
            json
        );
        parser.nextToken();
        MigrationRequestOptions rt = MigrationRequestOptions.fromXContent(parser);
        assertEquals(Boolean.FALSE, rt.getRemoveSourceWriteBlockOnSuccess());
    }

    public void testRemoveSourceWriteBlockOnSuccessXContentOmitsWhenNull() throws IOException {
        MigrationRequestOptions options = new MigrationRequestOptions();
        XContentBuilder builder = XContentFactory.jsonBuilder();
        options.toXContent(builder, ToXContent.EMPTY_PARAMS);
        assertFalse(
            "Null value should be omitted from JSON, got: " + builder,
            builder.toString().contains("remove_source_write_block_on_success")
        );
    }

    public void testMergeWithRemoveSourceWriteBlockOnSuccess_requestOverridesBase() {
        MigrationRequestOptions base = AoscTestUtil.defaultMigrationOptions().setRemoveSourceWriteBlockOnSuccess(true);
        MigrationRequestOptions override = new MigrationRequestOptions().setRemoveSourceWriteBlockOnSuccess(false);

        MigrationRequestOptions merged = base.mergeWith(override);
        assertEquals(Boolean.FALSE, merged.getRemoveSourceWriteBlockOnSuccess());
    }

    public void testMergeWithRemoveSourceWriteBlockOnSuccess_baseUsedWhenRequestUnset() {
        MigrationRequestOptions base = AoscTestUtil.defaultMigrationOptions().setRemoveSourceWriteBlockOnSuccess(true);
        MigrationRequestOptions override = new MigrationRequestOptions();

        MigrationRequestOptions merged = base.mergeWith(override);
        assertEquals(Boolean.TRUE, merged.getRemoveSourceWriteBlockOnSuccess());
    }

    public void testShouldRemoveSourceWriteBlockOnSuccess_defaultsToFalseWhenUnset() {
        assertFalse(new MigrationRequestOptions().shouldRemoveSourceWriteBlockOnSuccess());
        assertTrue(new MigrationRequestOptions().setRemoveSourceWriteBlockOnSuccess(true).shouldRemoveSourceWriteBlockOnSuccess());
        assertFalse(new MigrationRequestOptions().setRemoveSourceWriteBlockOnSuccess(false).shouldRemoveSourceWriteBlockOnSuccess());
    }

    public void testMergeWithRemoveSourceWriteBlockOnSuccess_nullRequestReturnsBaseValue() {
        MigrationRequestOptions base = AoscTestUtil.defaultMigrationOptions().setRemoveSourceWriteBlockOnSuccess(false);

        MigrationRequestOptions merged = base.mergeWith(null);
        assertEquals(Boolean.FALSE, merged.getRemoveSourceWriteBlockOnSuccess());
    }

    public void testDefaultOptionsRemoveSourceWriteBlockDefaultsToFalse() {
        ClusterSettings clusterSettings = new ClusterSettings(Settings.EMPTY, new HashSet<>(AoscSettings.ALL));
        MigrationRequestOptions defaults = MigrationRequestOptions.defaultOptions(clusterSettings);
        assertEquals(Boolean.FALSE, defaults.getRemoveSourceWriteBlockOnSuccess());
        assertFalse(defaults.shouldRemoveSourceWriteBlockOnSuccess());
    }

    public void testDefaultOptionsRemoveSourceWriteBlockHonoursClusterSetting() {
        Settings settings = Settings.builder().put("aosc.defaults.remove_source_write_block_on_success", true).build();
        ClusterSettings clusterSettings = new ClusterSettings(settings, new HashSet<>(AoscSettings.ALL));
        MigrationRequestOptions defaults = MigrationRequestOptions.defaultOptions(clusterSettings);
        assertEquals(Boolean.TRUE, defaults.getRemoveSourceWriteBlockOnSuccess());
        assertTrue(defaults.shouldRemoveSourceWriteBlockOnSuccess());
    }
}
