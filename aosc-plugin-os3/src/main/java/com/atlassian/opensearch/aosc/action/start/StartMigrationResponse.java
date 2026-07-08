/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */
package com.atlassian.opensearch.aosc.action.start;

import com.atlassian.opensearch.aosc.action.base.JacksonActionResponse;

import org.opensearch.core.common.io.stream.StreamInput;

import java.io.IOException;

/** Transport response for {@link StartMigrationAction}. */
public class StartMigrationResponse extends JacksonActionResponse<StartMigrationResult> {

    public StartMigrationResponse(StartMigrationResult body) {
        super(body);
    }

    public StartMigrationResponse(StreamInput in) throws IOException {
        super(in, StartMigrationResult.class);
    }
}
