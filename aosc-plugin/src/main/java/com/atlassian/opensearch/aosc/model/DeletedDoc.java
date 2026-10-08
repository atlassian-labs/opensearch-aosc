/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */
package com.atlassian.opensearch.aosc.model;

import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NonNull;
import lombok.ToString;
import lombok.experimental.Accessors;

import java.util.HashMap;
import java.util.Map;

/** A delete in the transform pipeline: {@link IndexDoc} without a source. */
@Getter
@Accessors(fluent = true)
@EqualsAndHashCode
@ToString
public final class DeletedDoc {
    private final String id;
    private final String routing;
    /** Source shard the delete was read from; scripts see it as {@code ctx.source_shard_id}. */
    private final int sourceShardId;

    public DeletedDoc(@NonNull String id, String routing, int sourceShardId) {
        if (id.isEmpty()) {
            throw new IllegalArgumentException("id must not be empty");
        }
        this.id = id;
        this.routing = routing;
        this.sourceShardId = sourceShardId;
    }

    public static DeletedDoc fromCtx(Map<String, Object> ctx, int sourceShardId) {
        return new DeletedDoc((String) ctx.get("_id"), (String) ctx.get("_routing"), sourceShardId);
    }

    /** Like {@link IndexDoc#toCtx()}, without {@code _source}. */
    public Map<String, Object> toCtx() {
        Map<String, Object> ctx = new HashMap<>(4);
        ctx.put("_id", id);
        ctx.put("_routing", routing);
        ctx.put("op_type", "delete");
        ctx.put("source_shard_id", sourceShardId);
        return ctx;
    }
}
