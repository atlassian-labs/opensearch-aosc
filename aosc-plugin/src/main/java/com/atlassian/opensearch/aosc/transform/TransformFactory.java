/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */
package com.atlassian.opensearch.aosc.transform;

import com.atlassian.opensearch.aosc.model.DeletedDoc;
import com.atlassian.opensearch.aosc.model.IndexDoc;
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
            TransformFunction function = new UpdateScriptTransformFunction(factory, transformScript.getEffectiveParams());
            // Without apply_to_deletes, deletes pass through unchanged via the default applyDelete.
            return transformScript.isDeleteAware() ? function : function::apply;
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
        dryRun(factory, params, transformScript.isDeleteAware());
        return factory;
    }

    private static final String DRY_RUN_ID = "aosc-dry-run";

    /** Dry-run on a sample doc (and, with {@code apply_to_deletes}, a sample delete) to fail bad scripts at start. */
    private static void dryRun(UpdateScript.Factory factory, Map<String, Object> params, boolean deleteAware) {
        try {
            factory.newInstance(params, new IndexDoc(DRY_RUN_ID, null, new HashMap<>(), 0).toCtx()).execute();
            if (deleteAware) {
                factory.newInstance(params, new DeletedDoc(DRY_RUN_ID, null, 0).toCtx()).execute();
            }
        } catch (Exception e) {
            throw new IllegalArgumentException(
                "Transform script dry-run failed (check that transform_script.params includes all params referenced by the script): "
                    + e.getMessage(),
                e
            );
        }
    }
}
