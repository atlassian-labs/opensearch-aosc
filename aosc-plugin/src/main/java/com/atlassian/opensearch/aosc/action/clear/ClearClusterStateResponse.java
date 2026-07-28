/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */
package com.atlassian.opensearch.aosc.action.clear;

import com.atlassian.opensearch.aosc.action.base.JacksonActionResponse;

import org.opensearch.core.common.io.stream.StreamInput;

import java.io.IOException;

/** Transport response for {@link ClearClusterStateAction}. */
public class ClearClusterStateResponse extends JacksonActionResponse<ClearClusterStateResult> {

    public ClearClusterStateResponse(ClearClusterStateResult body) {
        super(body);
    }

    public ClearClusterStateResponse(StreamInput in) throws IOException {
        super(in, ClearClusterStateResult.class);
    }
}
