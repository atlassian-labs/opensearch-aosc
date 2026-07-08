/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */
package com.atlassian.opensearch.aosc.utils.jackson;

import com.fasterxml.jackson.annotation.JsonIgnore;

import org.opensearch.core.xcontent.ToXContentObject;
import org.opensearch.core.xcontent.XContentBuilder;

import java.io.IOException;

/**
 * A ToXContentObject that delegates serialization to Jackson.
 *
 * <p>Implementing classes just need Jackson annotations ({@code @JsonProperty},
 * {@code @JsonInclude(NON_NULL)}) on their fields. The {@code toXContent} method
 * is handled automatically via Jackson's ObjectMapper.</p>
 *
 * <p>For deserialization from XContent (REST input), use a static factory:</p>
 * <pre>
 * public static MyClass fromXContent(XContentParser parser) throws IOException {
 *     return JacksonHelper.fromXContent(parser, MyClass.class);
 * }
 * </pre>
 */
public interface JacksonToXContentObject extends ToXContentObject {

    @Override
    default XContentBuilder toXContent(XContentBuilder builder, Params params) throws IOException {
        JacksonHelper.toXContent(this, builder);
        return builder;
    }

    @JsonIgnore
    @Override
    default boolean isFragment() {
        return false;
    }
}
