/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */
package com.atlassian.opensearch.aosc.model.transform;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;
import lombok.experimental.Accessors;

import org.opensearch.script.ScriptType;

import java.util.Map;

/** Inline Painless transform script — the {@code source} field holds the script body. */
@Getter
@Setter
@NoArgsConstructor
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
@Accessors(chain = true)
public class InlineTransformScript extends TransformScript {

    @JsonProperty("source")
    private String source;

    @JsonCreator
    public InlineTransformScript(@JsonProperty("source") String source, @JsonProperty("params") Map<String, Object> params) {
        super(params);
        this.source = source;
    }

    @Override
    public boolean hasBody() {
        return source != null && !source.isBlank() && !"identity".equals(source);
    }

    @Override
    public String getScriptSourceOrId() {
        return source;
    }

    @Override
    public ScriptType getScriptType() {
        return ScriptType.INLINE;
    }

    @Override
    public String getScriptLang() {
        return "painless";
    }
}
