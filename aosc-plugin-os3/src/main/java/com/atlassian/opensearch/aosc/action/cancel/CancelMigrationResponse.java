/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */
package com.atlassian.opensearch.aosc.action.cancel;

import com.atlassian.opensearch.aosc.action.base.JacksonActionResponse;

import org.opensearch.core.common.io.stream.StreamInput;

import java.io.IOException;

/** Transport response for {@link CancelMigrationAction}. */
public class CancelMigrationResponse extends JacksonActionResponse<CancelMigrationResult> {

    public CancelMigrationResponse(CancelMigrationResult body) {
        super(body);
    }

    public CancelMigrationResponse(StreamInput in) throws IOException {
        super(in, CancelMigrationResult.class);
    }
}
