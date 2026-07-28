/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */
package com.atlassian.opensearch.aosc.action.base;

import com.atlassian.opensearch.aosc.utils.jackson.JacksonHelper;
import com.atlassian.opensearch.aosc.utils.jackson.JacksonWriteable;

import org.opensearch.action.ActionRequest;
import org.opensearch.action.ActionRequestValidationException;
import org.opensearch.core.common.io.stream.StreamInput;
import org.opensearch.core.common.io.stream.StreamOutput;

import java.io.IOException;

/**
 * Base class for AOSC transport requests extending {@link ActionRequest}.
 * Wraps a Jackson-serializable body — subclasses are thin two-liners.
 */
public abstract class JacksonActionRequest<B extends JacksonWriteable> extends ActionRequest {

    private final B body;

    protected JacksonActionRequest(B body) {
        this.body = body;
    }

    protected JacksonActionRequest(StreamInput in, Class<B> bodyClass) throws IOException {
        super(in);
        this.body = JacksonHelper.readFrom(in, bodyClass);
    }

    public B body() {
        return body;
    }

    @Override
    public void writeTo(StreamOutput out) throws IOException {
        super.writeTo(out);
        JacksonHelper.writeTo(body, out);
    }

    @Override
    public ActionRequestValidationException validate() {
        return null;
    }
}
