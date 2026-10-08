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
    /** Source shard the document was read from; scripts see it as {@code ctx.source_shard_id}. */
    private final int sourceShardId;

    public IndexDoc(@NonNull String id, String routing, @NonNull Map<String, Object> source, int sourceShardId) {
        if (id.isEmpty()) {
            throw new IllegalArgumentException("id must not be empty");
        }
        this.id = id;
        this.routing = routing;
        this.source = source;
        this.sourceShardId = sourceShardId;
    }

    @SuppressWarnings("unchecked")
    public static IndexDoc fromCtx(Map<String, Object> ctx, int sourceShardId) {
        return new IndexDoc((String) ctx.get("_id"), (String) ctx.get("_routing"), (Map<String, Object>) ctx.get("_source"), sourceShardId);
    }

    /** Script ctx, including {@code op_type} and {@code source_shard_id}. */
    public Map<String, Object> toCtx() {
        Map<String, Object> ctx = new HashMap<>(5);
        ctx.put("_id", id);
        ctx.put("_routing", routing);
        ctx.put("_source", source);
        ctx.put("op_type", "index");
        ctx.put("source_shard_id", sourceShardId);
        return ctx;
    }
}
