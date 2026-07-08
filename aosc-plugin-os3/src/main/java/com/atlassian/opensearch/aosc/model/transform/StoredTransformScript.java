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

/**
 * Stored Painless transform script reference. The {@code id} field names a script
 * previously registered via {@code PUT _scripts/<id>}.
 */
@Getter
@Setter
@NoArgsConstructor
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
@Accessors(chain = true)
public class StoredTransformScript extends TransformScript {

    @JsonProperty("id")
    private String id;

    @JsonCreator
    public StoredTransformScript(@JsonProperty("id") String id, @JsonProperty("params") Map<String, Object> params) {
        super(params);
        this.id = id;
    }

    @Override
    public boolean hasBody() {
        return id != null && !id.isBlank();
    }

    @Override
    public String getScriptSourceOrId() {
        return id;
    }

    @Override
    public ScriptType getScriptType() {
        return ScriptType.STORED;
    }

    @Override
    public String getScriptLang() {
        return null;
    }
}
