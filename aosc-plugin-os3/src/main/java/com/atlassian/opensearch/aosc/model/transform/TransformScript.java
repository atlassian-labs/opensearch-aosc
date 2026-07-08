/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */
package com.atlassian.opensearch.aosc.model.transform;

import com.atlassian.opensearch.aosc.utils.jackson.JacksonToXContentObject;
import com.atlassian.opensearch.aosc.utils.jackson.JacksonWriteable;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;
import lombok.experimental.Accessors;

import org.opensearch.script.ScriptType;

import java.util.Collections;
import java.util.Map;

/**
 * Polymorphic transform script payload. Two concrete subtypes:
 * <ul>
 *   <li>{@link InlineTransformScript} (type=inline) — source code embedded in the request</li>
 *   <li>{@link StoredTransformScript} (type=stored) — reference to a stored script by id</li>
 * </ul>
 *
 * <p>JSON shape (inline):
 * <pre>{@code
 * { "type": "inline", "source": "ctx._source.tag = params.t", "params": { "t": "v" } }
 * }</pre>
 *
 * <p>JSON shape (stored):
 * <pre>{@code
 * { "type": "stored", "id": "my-stored-script", "params": { "t": "v" } }
 * }</pre>
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type", visible = true)
@JsonSubTypes({
    @JsonSubTypes.Type(value = InlineTransformScript.class, name = "inline"),
    @JsonSubTypes.Type(value = StoredTransformScript.class, name = "stored") })
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
@Getter
@Setter
@NoArgsConstructor
@EqualsAndHashCode
@ToString
@Accessors(chain = true)
public abstract class TransformScript implements JacksonWriteable, JacksonToXContentObject {

    /** Default OpenSearch script context (today's behaviour: 1:1 update-style transform). */
    public static final String SCRIPT_CONTEXT_UPDATE = "update";

    @JsonProperty("params")
    protected Map<String, Object> params;

    /** Script-context discriminator (default: {@link #SCRIPT_CONTEXT_UPDATE}). */
    @JsonProperty("script_context")
    protected String scriptContext = SCRIPT_CONTEXT_UPDATE;

    protected TransformScript(Map<String, Object> params) {
        this.params = params;
    }

    /** Returns parameters or an empty map if none were provided. */
    @JsonIgnore
    public Map<String, Object> getEffectiveParams() {
        return params == null ? Collections.emptyMap() : params;
    }

    /** True iff this request targets the base's built-in {@code update} transform. */
    @JsonIgnore
    public boolean isUpdateContext() {
        return scriptContext == null || scriptContext.isBlank() || SCRIPT_CONTEXT_UPDATE.equals(scriptContext);
    }

    /** True iff this script has a non-empty body to execute (else: identity pass-through). */
    @JsonIgnore
    public abstract boolean hasBody();

    /** Inline source code (for {@link InlineTransformScript}) or stored-script id (for {@link StoredTransformScript}). */
    @JsonIgnore
    public abstract String getScriptSourceOrId();

    /** OpenSearch script type: {@link ScriptType#INLINE} or {@link ScriptType#STORED}. */
    @JsonIgnore
    public abstract ScriptType getScriptType();

    /** Script language, e.g. {@code "painless"} for inline; {@code null} for stored. */
    @JsonIgnore
    public abstract String getScriptLang();
}
