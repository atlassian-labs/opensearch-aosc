/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */
package com.atlassian.opensearch.aosc.model;

import lombok.Getter;
import lombok.NonNull;
import lombok.experimental.Accessors;

import java.util.HashMap;
import java.util.Map;

/** A single document flowing through the transform pipeline. */
@Getter
@Accessors(fluent = true)
public final class IndexDoc {
    private final String id;
    private final String routing;
    private final Map<String, Object> source;

    public IndexDoc(@NonNull String id, String routing, @NonNull Map<String, Object> source) {
        if (id.isEmpty()) {
            throw new IllegalArgumentException("id must not be empty");
        }
        this.id = id;
        this.routing = routing;
        this.source = source;
    }

    @SuppressWarnings("unchecked")
    public static IndexDoc fromCtx(Map<String, Object> ctx) {
        return new IndexDoc((String) ctx.get("_id"), (String) ctx.get("_routing"), (Map<String, Object>) ctx.get("_source"));
    }

    public Map<String, Object> toCtx() {
        Map<String, Object> ctx = new HashMap<>(3);
        ctx.put("_id", id);
        ctx.put("_routing", routing);
        ctx.put("_source", source);
        return ctx;
    }
}
