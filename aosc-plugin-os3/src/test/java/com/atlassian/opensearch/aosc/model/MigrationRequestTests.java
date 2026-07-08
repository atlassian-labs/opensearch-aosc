/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */
package com.atlassian.opensearch.aosc.model;

import com.atlassian.opensearch.aosc.model.transform.InlineTransformScript;
import com.atlassian.opensearch.aosc.model.transform.StoredTransformScript;

import org.opensearch.action.ActionRequestValidationException;
import org.opensearch.common.io.stream.BytesStreamOutput;
import org.opensearch.common.xcontent.XContentFactory;
import org.opensearch.common.xcontent.json.JsonXContent;
import org.opensearch.core.xcontent.DeprecationHandler;
import org.opensearch.core.xcontent.ToXContent;
import org.opensearch.core.xcontent.XContentBuilder;
import org.opensearch.core.xcontent.XContentParser;
import org.opensearch.test.OpenSearchTestCase;

import java.io.IOException;
import java.util.Map;

public class MigrationRequestTests extends OpenSearchTestCase {

    // ---- Writeable round-trip: full ----
    public void testWriteableRoundTrip() throws IOException {
        MigrationRequest original = new MigrationRequest().setSourceIndex("src")
            .setTargetIndex("tgt")
            .setTransformScript(new InlineTransformScript("ctx.field = 'x'", null))
            .setAlias("my-alias")
            .setOptions(
                new MigrationRequestOptions()

                    .setConvergenceThresholdPerShard(5)
                    .setMaxConvergenceRoundsPerShard(0)
                    .setDocCountTolerance(0)
                    .setAcceptDataLossIfCustomRoutingIsUsed(false)
            );

        BytesStreamOutput out = new BytesStreamOutput();
        original.writeTo(out);
        MigrationRequest rt = new MigrationRequest(out.bytes().streamInput());

        assertEquals("src", rt.getSourceIndex());
        assertEquals("tgt", rt.getTargetIndex());
        assertTrue(rt.getTransformScript() instanceof InlineTransformScript);
        assertEquals("ctx.field = 'x'", ((InlineTransformScript) rt.getTransformScript()).getSource());
        assertEquals("my-alias", rt.getAlias());
        assertNotNull(rt.getOptions());
    }

    // ---- Writeable round-trip: null optionals ----
    public void testWriteableRoundTripNullOptionals() throws IOException {
        MigrationRequest original = new MigrationRequest().setSourceIndex("src").setTargetIndex("tgt");

        BytesStreamOutput out = new BytesStreamOutput();
        original.writeTo(out);
        MigrationRequest rt = new MigrationRequest(out.bytes().streamInput());

        assertEquals("src", rt.getSourceIndex());
        assertEquals("tgt", rt.getTargetIndex());
        assertNull(rt.getTransformScript());
        assertNull(rt.getAlias());
        assertNull(rt.getOptions());
    }

    // ---- XContent round-trip ----
    public void testXContentRoundTrip() throws IOException {
        MigrationRequest original = new MigrationRequest().setSourceIndex("src-idx")
            .setTargetIndex("tgt-idx")
            .setTransformScript(new InlineTransformScript("ctx.remove('field')", null))
            .setAlias("alias-1")
            .setOptions(new MigrationRequestOptions());

        XContentBuilder builder = XContentFactory.jsonBuilder();
        original.toXContent(builder, ToXContent.EMPTY_PARAMS);
        String json = builder.toString();

        XContentParser parser = JsonXContent.jsonXContent.createParser(
            xContentRegistry(),
            DeprecationHandler.THROW_UNSUPPORTED_OPERATION,
            json
        );
        MigrationRequest rt = MigrationRequest.fromXContent(parser);

        assertEquals("tgt-idx", rt.getTargetIndex());
        assertEquals("src-idx", rt.getSourceIndex());
        assertTrue(rt.getTransformScript() instanceof InlineTransformScript);
        assertEquals("ctx.remove('field')", ((InlineTransformScript) rt.getTransformScript()).getSource());
        assertEquals("alias-1", rt.getAlias());
        assertNotNull(rt.getOptions());
    }

    // ---- Validation: missing target ----
    public void testValidation() {
        MigrationRequest request = new MigrationRequest();
        ActionRequestValidationException ex = request.validate();
        assertNotNull(ex);
        assertTrue(ex.getMessage().contains("target.index is required"));

        // Same source and target
        MigrationRequest same = new MigrationRequest().setSourceIndex("idx").setTargetIndex("idx").setAlias("my-alias");
        ActionRequestValidationException ex2 = same.validate();
        assertNotNull(ex2);
        assertTrue(ex2.getMessage().contains("must be different"));
    }

    // ---- Validation: success ----
    public void testValidationSuccess() {
        MigrationRequest request = new MigrationRequest().setSourceIndex("src").setTargetIndex("tgt").setAlias("my-alias");
        assertNull(request.validate());
    }

    public void testValidationSourceRequired() {
        MigrationRequest request = new MigrationRequest().setTargetIndex("tgt").setAlias("my-alias");
        ActionRequestValidationException ex = request.validate();
        assertNotNull(ex);
        assertTrue(ex.getMessage().contains("source index is required"));
    }

    public void testValidationBlankSourceRejected() {
        MigrationRequest request = new MigrationRequest().setSourceIndex("  ").setTargetIndex("tgt").setAlias("my-alias");
        ActionRequestValidationException ex = request.validate();
        assertNotNull(ex);
        assertTrue(ex.getMessage().contains("source index is required"));
    }

    public void testValidationAliasRequired() {
        MigrationRequest request = new MigrationRequest().setSourceIndex("src").setTargetIndex("tgt");
        ActionRequestValidationException ex = request.validate();
        assertNotNull(ex);
        assertTrue(ex.getMessage().contains("alias is required"));
    }

    public void testValidationBlankAliasRejected() {
        MigrationRequest request = new MigrationRequest().setSourceIndex("src").setTargetIndex("tgt").setAlias("  ");
        ActionRequestValidationException ex = request.validate();
        assertNotNull(ex);
        assertTrue(ex.getMessage().contains("alias is required"));
    }

    public void testValidationEmptyAliasRejected() {
        MigrationRequest request = new MigrationRequest().setSourceIndex("src").setTargetIndex("tgt").setAlias("");
        ActionRequestValidationException ex = request.validate();
        assertNotNull(ex);
        assertTrue(ex.getMessage().contains("alias is required"));
    }

    public void testValidationAliasAccepted() {
        MigrationRequest request = new MigrationRequest().setSourceIndex("src").setTargetIndex("tgt").setAlias("my-alias");
        assertNull(request.validate());
    }

    public void testValidationMultipleErrorsIncludeAlias() {
        MigrationRequest request = new MigrationRequest();
        ActionRequestValidationException ex = request.validate();
        assertNotNull(ex);
        assertTrue(ex.getMessage().contains("source index is required"));
        assertTrue(ex.getMessage().contains("target.index is required"));
        assertTrue(ex.getMessage().contains("alias is required"));
    }

    public void testValidationDelegatesOptionsErrors() {
        MigrationRequestOptions badOpts = new MigrationRequestOptions().setDocCountTolerance(-1);
        MigrationRequest request = new MigrationRequest().setSourceIndex("src")
            .setTargetIndex("tgt")
            .setAlias("my-alias")
            .setOptions(badOpts);
        ActionRequestValidationException ex = request.validate();
        assertNotNull(ex);
        assertTrue(ex.getMessage().contains("doc_count_tolerance"));
    }

    public void testValidationAcceptsStoredScriptAlone() {
        MigrationRequest request = new MigrationRequest().setSourceIndex("src")
            .setTargetIndex("tgt")
            .setAlias("my-alias")
            .setTransformScript(new StoredTransformScript("my-stored-script", null));
        assertNull(request.validate());
    }

    public void testValidationAcceptsInlineScriptAlone() {
        MigrationRequest request = new MigrationRequest().setSourceIndex("src")
            .setTargetIndex("tgt")
            .setAlias("my-alias")
            .setTransformScript(new InlineTransformScript("ctx._source.x = 1", null));
        assertNull(request.validate());
    }

    public void testWriteableRoundTripStoredScript() throws IOException {
        MigrationRequest original = new MigrationRequest().setSourceIndex("src")
            .setTargetIndex("tgt")
            .setTransformScript(new StoredTransformScript("my-stored-script", Map.of("version", 2, "flag", true)))
            .setAlias("my-alias");

        BytesStreamOutput out = new BytesStreamOutput();
        original.writeTo(out);
        MigrationRequest rt = new MigrationRequest(out.bytes().streamInput());

        assertEquals("src", rt.getSourceIndex());
        assertEquals("tgt", rt.getTargetIndex());
        assertTrue(rt.getTransformScript() instanceof StoredTransformScript);
        StoredTransformScript storedScript = (StoredTransformScript) rt.getTransformScript();
        assertEquals("my-stored-script", storedScript.getId());
        assertNotNull(storedScript.getParams());
        assertEquals(2, storedScript.getParams().get("version"));
        assertEquals(true, storedScript.getParams().get("flag"));
        assertEquals("my-alias", rt.getAlias());
    }
}
