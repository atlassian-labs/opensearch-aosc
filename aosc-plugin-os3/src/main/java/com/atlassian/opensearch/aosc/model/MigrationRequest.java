/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */
package com.atlassian.opensearch.aosc.model;

import com.atlassian.opensearch.aosc.model.transform.TransformScript;
import com.atlassian.opensearch.aosc.utils.jackson.JacksonHelper;
import com.atlassian.opensearch.aosc.utils.jackson.JacksonToXContentObject;
import com.atlassian.opensearch.aosc.utils.jackson.JacksonWriteable;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.Accessors;

import org.opensearch.action.ActionRequestValidationException;
import org.opensearch.core.common.io.stream.StreamInput;

import java.io.IOException;

import static org.opensearch.action.ValidateActions.addValidationError;

/**
 * Migration start request payload. Provided by the user via REST API.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Accessors(chain = true)
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class MigrationRequest implements JacksonWriteable, JacksonToXContentObject {

    @JsonProperty("source_index")
    private String sourceIndex;

    @JsonProperty("target_index")
    private String targetIndex;

    @JsonProperty("transform_script")
    private TransformScript transformScript;

    @JsonProperty("alias")
    private String alias;

    @JsonProperty("options")
    private MigrationRequestOptions options;

    public MigrationRequest(StreamInput in) throws IOException {
        JacksonHelper.copyFields(in, this, MigrationRequest.class);
    }

    /** Deserialize from XContent parser (REST API input). */
    public static MigrationRequest fromXContent(org.opensearch.core.xcontent.XContentParser parser) throws IOException {
        return JacksonHelper.fromXContent(parser, MigrationRequest.class);
    }

    public ActionRequestValidationException validate() {
        ActionRequestValidationException validationException = null;
        if (sourceIndex == null || sourceIndex.isBlank()) {
            validationException = addValidationError("source index is required", validationException);
        }
        if (targetIndex == null || targetIndex.isBlank()) {
            validationException = addValidationError("target.index is required", validationException);
        }
        if (alias == null || alias.isBlank()) {
            validationException = addValidationError("alias is required", validationException);
        }
        if (sourceIndex != null && sourceIndex.equals(targetIndex)) {
            validationException = addValidationError("source index and target.index must be different", validationException);
        }
        if (transformScript != null) {
            ActionRequestValidationException txEx = TransformScriptValidation.validate(transformScript);
            if (txEx != null) {
                for (String error : txEx.validationErrors()) {
                    validationException = addValidationError(error, validationException);
                }
            }
        }
        if (options != null) {
            ActionRequestValidationException optionsEx = options.validate();
            if (optionsEx != null) {
                for (String error : optionsEx.validationErrors()) {
                    validationException = addValidationError(error, validationException);
                }
            }
        }
        return validationException;
    }
}
