/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */
package com.atlassian.opensearch.aosc.action.update;

import com.atlassian.opensearch.aosc.action.base.JacksonClusterManagerRequest;

import org.opensearch.core.common.io.stream.StreamInput;

import java.io.IOException;

/** Transport request to update shard-level migration status. */
public class UpdateShardMigrationStatusRequest extends JacksonClusterManagerRequest<
    UpdateShardMigrationStatusRequest,
    UpdateShardMigrationStatusBody> {

    public UpdateShardMigrationStatusRequest(UpdateShardMigrationStatusBody body) {
        super(body);
    }

    public UpdateShardMigrationStatusRequest(StreamInput in) throws IOException {
        super(in, UpdateShardMigrationStatusBody.class);
    }
}
