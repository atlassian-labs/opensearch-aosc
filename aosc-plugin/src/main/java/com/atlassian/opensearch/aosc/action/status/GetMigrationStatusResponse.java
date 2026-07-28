/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */
package com.atlassian.opensearch.aosc.action.status;

import com.atlassian.opensearch.aosc.action.base.JacksonActionResponse;
import com.atlassian.opensearch.aosc.model.MigrationDocument;

import org.opensearch.core.common.io.stream.StreamInput;

import java.io.IOException;

/** Transport response returning the migration status document. */
public class GetMigrationStatusResponse extends JacksonActionResponse<MigrationDocument> {

    public GetMigrationStatusResponse(MigrationDocument body) {
        super(body);
    }

    public GetMigrationStatusResponse(StreamInput in) throws IOException {
        super(in, MigrationDocument.class);
    }
}
