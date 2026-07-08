/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */
package com.atlassian.opensearch.aosc.action.base;

import com.atlassian.opensearch.aosc.utils.jackson.JacksonHelper;
import com.atlassian.opensearch.aosc.utils.jackson.JacksonToXContentObject;

import org.opensearch.core.action.ActionResponse;
import org.opensearch.core.common.io.stream.StreamInput;
import org.opensearch.core.common.io.stream.StreamOutput;
import org.opensearch.core.xcontent.XContentBuilder;

import java.io.IOException;

/**
 * Base class for transport responses whose body is a Jackson-serializable object.
 *
 * <p>Subclasses only need two constructors:</p>
 * <pre>
 * public MyResponse(MyResult body) { super(body); }
 * public MyResponse(StreamInput in) throws IOException { super(in, MyResult.class); }
 * </pre>
 *
 * @param <T> the body type, must implement both JacksonWriteable and JacksonToXContentObject
 */
public abstract class JacksonActionResponse<T> extends ActionResponse implements JacksonToXContentObject {

    private final T body;

    protected JacksonActionResponse(T body) {
        this.body = body;
    }

    protected JacksonActionResponse(StreamInput in, Class<T> bodyClass) throws IOException {
        super(in);
        this.body = JacksonHelper.readFrom(in, bodyClass);
    }

    /** The deserialized response body. */
    public T body() {
        return body;
    }

    @Override
    public void writeTo(StreamOutput out) throws IOException {
        JacksonHelper.writeTo(body, out);
    }

    @Override
    public XContentBuilder toXContent(XContentBuilder builder, Params params) throws IOException {
        JacksonHelper.toXContent(body, builder);
        return builder;
    }
}
