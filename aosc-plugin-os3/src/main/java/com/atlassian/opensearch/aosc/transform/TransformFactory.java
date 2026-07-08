/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */
package com.atlassian.opensearch.aosc.transform;

import com.atlassian.opensearch.aosc.model.transform.TransformScript;

import org.opensearch.ResourceNotFoundException;
import org.opensearch.cluster.metadata.IndexMetadata;
import org.opensearch.script.Script;
import org.opensearch.script.ScriptService;
import org.opensearch.script.UpdateScript;

import java.util.HashMap;
import java.util.Map;

/**
 * Builds {@link TransformFunction}s from a {@link TransformScript}. Extension point —
 * subclasses override {@link #create} to handle custom {@code script_context} values.
 */
public class TransformFactory {

    private final ScriptService scriptService;

    public TransformFactory(ScriptService scriptService) {
        this.scriptService = scriptService;
    }

    protected ScriptService scriptService() {
        return scriptService;
    }

    /**
     * Build the transform function. Also acts as start-time validation — throws
     * {@link IllegalArgumentException} on unknown {@code script_context}, script compile errors,
     * unbound params, or subclass-specific checks.
     */
    public TransformFunction create(TransformScript transformScript, IndexMetadata sourceIndex, IndexMetadata targetIndex) {
        if (transformScript == null || !transformScript.hasBody()) {
            return IdentityTransformFunction.INSTANCE;
        }
        if (transformScript.isUpdateContext()) {
            UpdateScript.Factory factory = compileAndDryRun(transformScript);
            return new UpdateScriptTransformFunction(factory, transformScript.getEffectiveParams());
        }
        throw new IllegalArgumentException(
            "Unknown transform script_context ["
                + transformScript.getScriptContext()
                + "]. Supported: ["
                + TransformScript.SCRIPT_CONTEXT_UPDATE
                + "]"
        );
    }

    /** Compiles the Painless update script and dry-runs it. */
    private UpdateScript.Factory compileAndDryRun(TransformScript transformScript) {
        Map<String, Object> params = transformScript.getEffectiveParams();
        Script script = new Script(
            transformScript.getScriptType(),
            transformScript.getScriptLang(),
            transformScript.getScriptSourceOrId(),
            params
        );
        UpdateScript.Factory factory;
        try {
            factory = scriptService.compile(script, UpdateScript.CONTEXT);
        } catch (ResourceNotFoundException e) {
            throw new IllegalArgumentException(
                "Stored script [" + transformScript.getScriptSourceOrId() + "] not found: " + e.getMessage(),
                e
            );
        }
        dryRun(factory, params);
        return factory;
    }

    /** Dry-run the script against an empty ctx to surface unbound-param errors at start time. */
    private static void dryRun(UpdateScript.Factory factory, Map<String, Object> params) {
        Map<String, Object> ctx = new HashMap<>();
        ctx.put("_source", new HashMap<String, Object>());
        try {
            factory.newInstance(params, ctx).execute();
        } catch (Exception e) {
            throw new IllegalArgumentException(
                "Transform script dry-run failed (check that transform_script.params includes all params referenced by the script): "
                    + e.getMessage(),
                e
            );
        }
    }
}
