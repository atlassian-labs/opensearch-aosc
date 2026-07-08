/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */
package com.atlassian.opensearch.aosc.action.cleanup;

import com.atlassian.opensearch.aosc.action.base.JacksonActionRequest;

import org.opensearch.action.ActionRequestValidationException;
import org.opensearch.core.common.io.stream.StreamInput;

import java.io.IOException;

import static org.opensearch.action.ValidateActions.addValidationError;

/** Transport request to clean up orphaned AOSC retention leases. */
public class CleanupLeasesRequest extends JacksonActionRequest<CleanupLeasesBody> {

    public CleanupLeasesRequest(CleanupLeasesBody body) {
        super(body);
    }

    public CleanupLeasesRequest(StreamInput in) throws IOException {
        super(in, CleanupLeasesBody.class);
    }

    @Override
    public ActionRequestValidationException validate() {
        ActionRequestValidationException validationException = null;
        if (body().indices() == null) {
            validationException = addValidationError("indices must not be null (use empty array for all)", validationException);
        }
        return validationException;
    }
}
