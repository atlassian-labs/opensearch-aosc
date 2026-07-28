/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */
package com.atlassian.opensearch.aosc.utils.jackson;

import org.opensearch.core.common.io.stream.StreamOutput;
import org.opensearch.core.common.io.stream.Writeable;

import java.io.IOException;

/**
 * A Writeable that delegates serialization to Jackson.
 *
 * <p>Implementing classes just need Jackson annotations ({@code @JsonProperty},
 * {@code @JsonIgnoreProperties(ignoreUnknown = true)}) on their fields.
 * The {@code writeTo} method is handled automatically via Jackson's ObjectMapper.</p>
 *
 * <p>For deserialization from StreamInput, use:</p>
 * <pre>
 * public MyClass(StreamInput in) throws IOException {
 *     JacksonHelper.copyFields(in, this, MyClass.class);
 * }
 * </pre>
 */
public interface JacksonWriteable extends Writeable {

    @Override
    default void writeTo(StreamOutput out) throws IOException {
        JacksonHelper.writeTo(this, out);
    }
}
