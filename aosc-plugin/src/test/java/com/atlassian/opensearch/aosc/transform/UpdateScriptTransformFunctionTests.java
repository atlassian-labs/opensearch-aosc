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

import org.opensearch.script.UpdateScript;
import org.opensearch.test.OpenSearchTestCase;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/** Exercises the ctx contract with a hand-written script in place of compiled Painless. */
public class UpdateScriptTransformFunctionTests extends OpenSearchTestCase {

    private static final int SHARD = 3;

    private final List<Map<String, Object>> seenCtx = new ArrayList<>();

    private UpdateScript.Factory script(Consumer<Map<String, Object>> body) {
        return (params, ctx) -> new UpdateScript(params, ctx) {
            @Override
            public void execute() {
                seenCtx.add(new HashMap<>(ctx));
                body.accept(ctx);
            }
        };
    }

    private UpdateScriptTransformFunction fn(Consumer<Map<String, Object>> body) {
        return new UpdateScriptTransformFunction(script(body), Map.of());
    }

    private static IndexDoc doc(String id, String routing) {
        Map<String, Object> source = new HashMap<>();
        source.put("tenant", "acme");
        return new IndexDoc(id, routing, source, SHARD);
    }

    // ---- index ops ----

    public void testIndexCtxCarriesOpShardAndRealRouting() {
        UpdateScriptTransformFunction fn = fn(ctx -> {});
        fn.apply(doc("1", "r1"));
        Map<String, Object> ctx = seenCtx.get(0);
        assertEquals("index", ctx.get("op_type"));
        assertEquals(SHARD, ctx.get("source_shard_id"));
        assertEquals("r1", ctx.get("_routing"));
        assertTrue(ctx.containsKey("_source"));
    }

    public void testIndexOutputIsUsedAsIs() {
        UpdateScriptTransformFunction fn = fn(ctx -> {
            ctx.put("_id", "t-" + ctx.get("_id"));
            ctx.put("_routing", "p" + ctx.get("source_shard_id"));
            ctx.put("source_shard_id", 99);
        });
        IndexDoc out = fn.apply(doc("1", null)).get(0);
        assertEquals("t-1", out.id());
        assertEquals("p3", out.routing());
        assertEquals("acme", out.source().get("tenant"));
        assertEquals("sourceShardId comes from the input, not ctx", SHARD, out.sourceShardId());
    }

    // ---- delete ops ----

    public void testDeleteCtxHasNoSource() {
        UpdateScriptTransformFunction fn = fn(ctx -> {});
        fn.applyDelete(new DeletedDoc("1", "syn-0", SHARD));
        Map<String, Object> ctx = seenCtx.get(0);
        assertEquals("delete", ctx.get("op_type"));
        assertEquals(SHARD, ctx.get("source_shard_id"));
        assertEquals("syn-0", ctx.get("_routing"));
        assertFalse(ctx.containsKey("_source"));
    }

    public void testDeleteOutputIsUsedAsIs() {
        UpdateScriptTransformFunction fn = fn(ctx -> {
            ctx.put("_id", "t-" + ctx.get("_id"));
            ctx.put("_routing", "fixed");
            ctx.put("source_shard_id", 99);
        });
        DeletedDoc out = fn.applyDelete(new DeletedDoc("1", "syn-0", SHARD)).get(0);
        assertEquals("t-1", out.id());
        assertEquals("fixed", out.routing());
        assertEquals("sourceShardId comes from the input, not ctx", SHARD, out.sourceShardId());
    }

    public void testReadingSourceOnDeleteFailsLoudly() {
        UpdateScriptTransformFunction fn = fn(ctx -> {
            @SuppressWarnings("unchecked")
            Map<String, Object> source = (Map<String, Object>) ctx.get("_source");
            source.get("tenant"); // NPE on deletes, like an unguarded Painless script
        });
        expectThrows(RuntimeException.class, () -> fn.applyDelete(new DeletedDoc("1", null, SHARD)));
    }

    // ---- default pass-through for other implementations ----

    public void testIdentityTransformPassesDeletesThrough() {
        DeletedDoc deletedDoc = new DeletedDoc("1", "r1", SHARD);
        assertEquals(List.of(deletedDoc), IdentityTransformFunction.INSTANCE.applyDelete(deletedDoc));
    }
}
