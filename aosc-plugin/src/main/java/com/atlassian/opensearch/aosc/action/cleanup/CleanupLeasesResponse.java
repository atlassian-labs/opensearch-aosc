/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */
package com.atlassian.opensearch.aosc.action.cleanup;

import com.atlassian.opensearch.aosc.action.base.JacksonActionResponse;

import org.opensearch.core.common.io.stream.StreamInput;

import java.io.IOException;

/** Transport response for {@link CleanupLeasesAction}. */
public class CleanupLeasesResponse extends JacksonActionResponse<CleanupLeasesResult> {

    public CleanupLeasesResponse(CleanupLeasesResult body) {
        super(body);
    }

    public CleanupLeasesResponse(StreamInput in) throws IOException {
        super(in, CleanupLeasesResult.class);
    }

}
