/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */
package com.atlassian.opensearch.aosc.transform;

import com.atlassian.opensearch.aosc.model.IndexDoc;

import org.opensearch.script.UpdateScript;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** 1:1 in-place transform driven by a Painless {@link UpdateScript}. */
public final class UpdateScriptTransformFunction implements TransformFunction {
    private final UpdateScript.Factory scriptFactory;
    private final Map<String, Object> params;

    public UpdateScriptTransformFunction(UpdateScript.Factory scriptFactory, Map<String, Object> params) {
        this.scriptFactory = scriptFactory;
        this.params = Collections.unmodifiableMap(new HashMap<>(params));
    }

    @Override
    public List<IndexDoc> apply(IndexDoc sourceDoc) {
        Map<String, Object> ctx = sourceDoc.toCtx();
        try {
            UpdateScript script = scriptFactory.newInstance(params, ctx);
            script.execute();
        } catch (Exception e) {
            throw new RuntimeException("Transform script execution failed: " + e.getMessage(), e);
        }
        return Collections.singletonList(IndexDoc.fromCtx(ctx));
    }
}
