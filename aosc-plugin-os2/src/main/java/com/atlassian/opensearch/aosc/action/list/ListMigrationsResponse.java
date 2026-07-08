/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */
package com.atlassian.opensearch.aosc.action.list;

import com.atlassian.opensearch.aosc.action.base.JacksonActionResponse;

import org.opensearch.core.common.io.stream.StreamInput;

import java.io.IOException;

/** Transport response for {@link ListMigrationsAction}. */
public class ListMigrationsResponse extends JacksonActionResponse<ListMigrationsResult> {

    public ListMigrationsResponse(ListMigrationsResult body) {
        super(body);
    }

    public ListMigrationsResponse(StreamInput in) throws IOException {
        super(in, ListMigrationsResult.class);
    }
}
