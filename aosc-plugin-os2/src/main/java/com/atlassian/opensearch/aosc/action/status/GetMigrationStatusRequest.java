/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */
package com.atlassian.opensearch.aosc.action.status;

import com.atlassian.opensearch.aosc.action.base.JacksonClusterManagerRequest;

import org.opensearch.core.common.io.stream.StreamInput;

import java.io.IOException;

/** Transport request to get migration status. */
public class GetMigrationStatusRequest extends JacksonClusterManagerRequest<GetMigrationStatusRequest, GetMigrationStatusBody> {

    public GetMigrationStatusRequest(GetMigrationStatusBody body) {
        super(body);
    }

    public GetMigrationStatusRequest(StreamInput in) throws IOException {
        super(in, GetMigrationStatusBody.class);
    }
}
