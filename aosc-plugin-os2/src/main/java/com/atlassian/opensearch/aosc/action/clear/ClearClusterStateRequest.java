/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */
package com.atlassian.opensearch.aosc.action.clear;

import com.atlassian.opensearch.aosc.action.base.JacksonClusterManagerRequest;

import org.opensearch.action.ActionRequestValidationException;
import org.opensearch.core.common.io.stream.StreamInput;

import java.io.IOException;

import static org.opensearch.action.ValidateActions.addValidationError;

/** Request to clear AOSC state from cluster state (debug/recovery). */
public class ClearClusterStateRequest extends JacksonClusterManagerRequest<ClearClusterStateRequest, ClearClusterStateBody> {

    public ClearClusterStateRequest(ClearClusterStateBody body) {
        super(body);
    }

    public ClearClusterStateRequest(StreamInput in) throws IOException {
        super(in, ClearClusterStateBody.class);
    }

    @Override
    public ActionRequestValidationException validate() {
        ActionRequestValidationException err = null;
        String migrationId = body().migrationId();
        if (migrationId != null && migrationId.isEmpty()) {
            err = addValidationError("migration_id must not be empty", err);
        }
        return err;
    }
}
