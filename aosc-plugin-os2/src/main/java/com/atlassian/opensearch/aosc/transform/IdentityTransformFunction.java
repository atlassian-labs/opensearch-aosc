/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */
package com.atlassian.opensearch.aosc.transform;

import com.atlassian.opensearch.aosc.model.IndexDoc;

import java.util.Collections;
import java.util.List;

/** No-op transform: emits the source doc verbatim at its original routing. */
public final class IdentityTransformFunction implements TransformFunction {
    public static final IdentityTransformFunction INSTANCE = new IdentityTransformFunction();

    @Override
    public List<IndexDoc> apply(IndexDoc sourceDoc) {
        return Collections.singletonList(sourceDoc);
    }
}
