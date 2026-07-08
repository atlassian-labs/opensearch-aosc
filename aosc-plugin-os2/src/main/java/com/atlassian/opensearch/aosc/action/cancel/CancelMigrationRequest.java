/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */
package com.atlassian.opensearch.aosc.action.cancel;

import com.atlassian.opensearch.aosc.action.base.JacksonClusterManagerRequest;

import org.opensearch.core.common.io.stream.StreamInput;

import java.io.IOException;

/** Transport request to cancel a running migration. */
public class CancelMigrationRequest extends JacksonClusterManagerRequest<CancelMigrationRequest, CancelMigrationBody> {

    public CancelMigrationRequest(CancelMigrationBody body) {
        super(body);
    }

    public CancelMigrationRequest(StreamInput in) throws IOException {
        super(in, CancelMigrationBody.class);
    }
}
