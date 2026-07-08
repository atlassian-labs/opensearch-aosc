/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */
package com.atlassian.opensearch.aosc.action.update;

import org.opensearch.core.action.ActionResponse;
import org.opensearch.core.common.io.stream.StreamInput;
import org.opensearch.core.common.io.stream.StreamOutput;

import java.io.IOException;

/**
 * Response for {@link UpdateShardMigrationStatusAction}. Empty payload — acknowledgement only.
 */
public class UpdateShardMigrationStatusResponse extends ActionResponse {

    public UpdateShardMigrationStatusResponse() {}

    public UpdateShardMigrationStatusResponse(StreamInput in) throws IOException {
        super(in);
    }

    @Override
    public void writeTo(StreamOutput out) throws IOException {}
}
