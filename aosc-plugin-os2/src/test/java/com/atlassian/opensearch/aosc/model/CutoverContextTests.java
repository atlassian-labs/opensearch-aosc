/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */
package com.atlassian.opensearch.aosc.model;

import com.atlassian.opensearch.aosc.utils.jackson.JacksonHelper;

import org.opensearch.common.io.stream.BytesStreamOutput;
import org.opensearch.common.xcontent.XContentFactory;
import org.opensearch.common.xcontent.json.JsonXContent;
import org.opensearch.core.common.io.stream.StreamInput;
import org.opensearch.core.xcontent.DeprecationHandler;
import org.opensearch.core.xcontent.NamedXContentRegistry;
import org.opensearch.core.xcontent.ToXContent;
import org.opensearch.core.xcontent.XContentBuilder;
import org.opensearch.core.xcontent.XContentParser;
import org.opensearch.test.OpenSearchTestCase;

import java.io.IOException;

/**
 * Tests for {@link CutoverContext} serialization and deserialization.
 */
public class CutoverContextTests extends OpenSearchTestCase {

    public void testStreamRoundTrip() throws IOException {
        CutoverContext original = CutoverContext.builder()
            .sourceDocCount(1000)
            .targetDocCount(999)
            .docCountTolerance(5)
            .docCountValidationPassed(true)
            .aliasSwapSucceeded(true)
            .cutoverStartMillis(1000000L)
            .cutoverEndMillis(1000500L)
            .errorMessage(null)
            .build();

        BytesStreamOutput out = new BytesStreamOutput();
        original.writeTo(out);
        StreamInput in = out.bytes().streamInput();
        CutoverContext deserialized = JacksonHelper.readFrom(in, CutoverContext.class);

        assertEquals(original, deserialized);
        assertEquals(1000, deserialized.sourceDocCount());
        assertEquals(999, deserialized.targetDocCount());
        assertEquals(5, deserialized.docCountTolerance());
        assertTrue(deserialized.docCountValidationPassed());
        assertTrue(deserialized.aliasSwapSucceeded());
        assertEquals(1000000L, deserialized.cutoverStartMillis());
        assertEquals(1000500L, deserialized.cutoverEndMillis());
        assertNull(deserialized.errorMessage());
    }

    public void testStreamRoundTripWithError() throws IOException {
        CutoverContext original = CutoverContext.builder()
            .sourceDocCount(100)
            .targetDocCount(50)
            .docCountTolerance(0)
            .docCountValidationPassed(false)
            .aliasSwapSucceeded(false)
            .cutoverStartMillis(2000000L)
            .cutoverEndMillis(2000100L)
            .errorMessage("doc count mismatch")
            .build();

        BytesStreamOutput out = new BytesStreamOutput();
        original.writeTo(out);
        StreamInput in = out.bytes().streamInput();
        CutoverContext deserialized = JacksonHelper.readFrom(in, CutoverContext.class);

        assertEquals(original, deserialized);
        assertEquals("doc count mismatch", deserialized.errorMessage());
    }

    public void testXContentRoundTrip() throws IOException {
        CutoverContext original = CutoverContext.builder()
            .sourceDocCount(5000)
            .targetDocCount(4998)
            .docCountTolerance(10)
            .docCountValidationPassed(true)
            .aliasSwapSucceeded(true)
            .cutoverStartMillis(3000000L)
            .cutoverEndMillis(3000200L)
            .build();

        XContentBuilder builder = XContentFactory.jsonBuilder();
        original.toXContent(builder, ToXContent.EMPTY_PARAMS);
        String json = builder.toString();

        try (
            XContentParser parser = JsonXContent.jsonXContent.createParser(
                NamedXContentRegistry.EMPTY,
                DeprecationHandler.THROW_UNSUPPORTED_OPERATION,
                json
            )
        ) {
            CutoverContext deserialized = CutoverContext.fromXContent(parser);
            assertEquals(original, deserialized);
        }
    }

    public void testXContentRoundTripWithError() throws IOException {
        CutoverContext original = CutoverContext.builder()
            .sourceDocCount(100)
            .targetDocCount(100)
            .docCountTolerance(0)
            .docCountValidationPassed(true)
            .aliasSwapSucceeded(false)
            .cutoverStartMillis(4000000L)
            .cutoverEndMillis(4000050L)
            .errorMessage("alias swap timeout")
            .build();

        XContentBuilder builder = XContentFactory.jsonBuilder();
        original.toXContent(builder, ToXContent.EMPTY_PARAMS);
        String json = builder.toString();

        try (
            XContentParser parser = JsonXContent.jsonXContent.createParser(
                NamedXContentRegistry.EMPTY,
                DeprecationHandler.THROW_UNSUPPORTED_OPERATION,
                json
            )
        ) {
            CutoverContext deserialized = CutoverContext.fromXContent(parser);
            assertEquals(original, deserialized);
            assertEquals("alias swap timeout", deserialized.errorMessage());
        }
    }

    public void testDurationMillis() {
        CutoverContext ctx = CutoverContext.builder().cutoverStartMillis(1000L).cutoverEndMillis(1500L).build();
        assertEquals(500L, ctx.durationMillis());
    }
}
