/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */
package com.atlassian.opensearch.aosc.action.list;

import com.atlassian.opensearch.aosc.action.base.JacksonClusterManagerRequest;

import org.opensearch.core.common.io.stream.StreamInput;

import java.io.IOException;

/** Transport request to list active migrations. */
public class ListMigrationsRequest extends JacksonClusterManagerRequest<ListMigrationsRequest, ListMigrationsBody> {

    public ListMigrationsRequest(ListMigrationsBody body) {
        super(body);
    }

    public ListMigrationsRequest(StreamInput in) throws IOException {
        super(in, ListMigrationsBody.class);
    }
}
