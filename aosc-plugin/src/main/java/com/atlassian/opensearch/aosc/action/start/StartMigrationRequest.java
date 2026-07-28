/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */
package com.atlassian.opensearch.aosc.action.start;

import com.atlassian.opensearch.aosc.action.base.JacksonClusterManagerRequest;
import com.atlassian.opensearch.aosc.model.MigrationRequest;

import org.opensearch.action.ActionRequestValidationException;
import org.opensearch.core.common.io.stream.StreamInput;

import java.io.IOException;

/** Transport request to start a new migration. Body is {@link MigrationRequest}. */
public class StartMigrationRequest extends JacksonClusterManagerRequest<StartMigrationRequest, MigrationRequest> {

    public StartMigrationRequest(MigrationRequest body) {
        super(body);
    }

    public StartMigrationRequest(StreamInput in) throws IOException {
        super(in, MigrationRequest.class);
    }

    @Override
    public ActionRequestValidationException validate() {
        return body() != null ? body().validate() : null;
    }
}
