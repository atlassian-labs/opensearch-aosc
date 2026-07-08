/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */
package com.atlassian.opensearch.aosc.utils.jackson;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import org.opensearch.common.xcontent.XContentFactory;
import org.opensearch.core.common.bytes.BytesReference;
import org.opensearch.core.common.io.stream.StreamInput;
import org.opensearch.core.common.io.stream.StreamOutput;
import org.opensearch.core.xcontent.XContentBuilder;

import java.io.IOException;
import java.security.AccessController;
import java.security.PrivilegedActionException;
import java.security.PrivilegedExceptionAction;
import java.util.Map;

/**
 * Bridges Jackson, Writeable, and XContent serialization.
 *
 * <p>All Jackson operations are wrapped in {@link AccessController#doPrivileged}
 * because OpenSearch's security manager restricts reflection access in plugin classloaders.</p>
 */
public final class JacksonHelper {

    static final ObjectMapper MAPPER;

    static {
        try {
            MAPPER = AccessController.doPrivileged(
                (PrivilegedExceptionAction<ObjectMapper>) () -> new ObjectMapper().setSerializationInclusion(JsonInclude.Include.NON_NULL)
                    .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
                    .configure(SerializationFeature.FAIL_ON_EMPTY_BEANS, false)
                    .addMixIn(org.opensearch.core.xcontent.ToXContentObject.class, IgnoreXContentMixin.class)
                    .addMixIn(org.opensearch.core.xcontent.ToXContent.class, IgnoreXContentMixin.class)
                    .addMixIn(JacksonToXContentObject.class, IgnoreXContentMixin.class)
                    .addMixIn(org.opensearch.core.common.io.stream.Writeable.class, IgnoreWriteableMixin.class)
                    .addMixIn(JacksonWriteable.class, IgnoreWriteableMixin.class)
            );
        } catch (PrivilegedActionException e) {
            throw new RuntimeException("Failed to initialize Jackson ObjectMapper", e);
        }
    }

    @JsonIgnoreProperties({ "fragment" })
    private abstract static class IgnoreXContentMixin {}

    @JsonIgnoreProperties({ "fragment" })
    private abstract static class IgnoreWriteableMixin {}

    private JacksonHelper() {}

    /** Serialize an object to JSON bytes and write to StreamOutput. */
    public static void writeTo(Object obj, StreamOutput out) throws IOException {
        out.writeByteArray(writeAsBytes(obj));
    }

    /** Deserialize from StreamInput JSON bytes into the given class. */
    public static <T> T readFrom(StreamInput in, Class<T> clazz) throws IOException {
        byte[] bytes = in.readByteArray();
        return doPrivileged(() -> MAPPER.readValue(bytes, clazz));
    }

    /** Write Jackson-annotated fields to an XContentBuilder. */
    @SuppressWarnings("unchecked")
    public static void toXContent(Object obj, XContentBuilder builder) throws IOException {
        Map<String, Object> map = doPrivileged(() -> MAPPER.convertValue(obj, Map.class));
        builder.startObject();
        for (Map.Entry<String, Object> entry : map.entrySet()) {
            builder.field(entry.getKey(), entry.getValue());
        }
        builder.endObject();
    }

    /** Convert an XContentParser's current structure to JSON bytes for Jackson parsing. */
    public static <T> T fromXContent(org.opensearch.core.xcontent.XContentParser parser, Class<T> clazz) throws IOException {
        XContentBuilder builder = XContentFactory.jsonBuilder();
        builder.copyCurrentStructure(parser);
        builder.close();
        org.opensearch.core.common.bytes.BytesReference bytesRef = BytesReference.bytes(builder);
        byte[] bytes = BytesReference.toBytes(bytesRef);
        return doPrivileged(() -> MAPPER.readValue(bytes, clazz));
    }

    /** Deserialize from StreamInput and copy all fields into the target object. */
    public static <T> void copyFields(StreamInput in, T target, Class<T> clazz) throws IOException {
        byte[] bytes = in.readByteArray();
        doPrivileged(() -> {
            MAPPER.readerForUpdating(target).readValue(bytes);
            return null;
        });
    }

    /** Serialize an object to JSON bytes. */
    public static byte[] writeAsBytes(Object obj) throws IOException {
        return doPrivileged(() -> MAPPER.writeValueAsBytes(obj));
    }

    /** Deserialize from JSON string into the given class. */
    public static <T> T readValue(String json, Class<T> clazz) throws IOException {
        return doPrivileged(() -> MAPPER.readValue(json, clazz));
    }

    /** Deserialize from an InputStream into the given class. */
    public static <T> T readValue(java.io.InputStream is, Class<T> clazz) throws IOException {
        return doPrivileged(() -> MAPPER.readValue(is, clazz));
    }

    /** Execute a Jackson operation with elevated privileges. */
    private static <T> T doPrivileged(PrivilegedExceptionAction<T> action) throws IOException {
        try {
            return AccessController.doPrivileged(action);
        } catch (PrivilegedActionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof IOException) throw (IOException) cause;
            if (cause instanceof RuntimeException) throw (RuntimeException) cause;
            throw new RuntimeException(cause);
        }
    }
}
