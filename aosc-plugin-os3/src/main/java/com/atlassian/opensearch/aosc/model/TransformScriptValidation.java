/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */
package com.atlassian.opensearch.aosc.model;

import com.atlassian.opensearch.aosc.model.transform.InlineTransformScript;
import com.atlassian.opensearch.aosc.model.transform.StoredTransformScript;
import com.atlassian.opensearch.aosc.model.transform.TransformScript;

import org.opensearch.action.ActionRequestValidationException;

import static org.opensearch.action.ValidateActions.addValidationError;

/**
 * Validation rules for {@link TransformScript} subtypes used in {@link MigrationRequest}.
 */
final class TransformScriptValidation {

    private TransformScriptValidation() {}

    static ActionRequestValidationException validate(TransformScript script) {
        ActionRequestValidationException ex = null;
        if (script instanceof InlineTransformScript) {
            InlineTransformScript inline = (InlineTransformScript) script;
            if (inline.getSource() == null || inline.getSource().isBlank()) {
                ex = addValidationError("transform_script.source is required for inline scripts", ex);
            }
        } else if (script instanceof StoredTransformScript) {
            StoredTransformScript stored = (StoredTransformScript) script;
            if (stored.getId() == null || stored.getId().isBlank()) {
                ex = addValidationError("transform_script.id is required for stored scripts", ex);
            }
        } else {
            ex = addValidationError("unknown transform_script type: " + script.getClass().getSimpleName(), ex);
        }
        return ex;
    }
}
