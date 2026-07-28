/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */
package com.atlassian.opensearch.aosc;

import com.atlassian.opensearch.aosc.model.MigrationRequestOptions;

import org.opensearch.action.get.GetRequest;
import org.opensearch.action.get.GetResponse;
import org.opensearch.action.index.IndexRequest;
import org.opensearch.action.search.SearchRequest;
import org.opensearch.action.search.SearchResponse;
import org.opensearch.action.support.WriteRequest;
import org.opensearch.common.settings.Settings;
import org.opensearch.common.xcontent.XContentType;
import org.opensearch.index.query.QueryBuilders;
import org.opensearch.search.builder.SearchSourceBuilder;
import org.opensearch.test.OpenSearchIntegTestCase.ClusterScope;
import org.opensearch.test.OpenSearchIntegTestCase.Scope;

import java.util.Locale;
import java.util.Map;

/**
 * Integration tests for routing-aware migration across different shard topologies.
 * Validates SAME_SHARD, SPLIT_SHARD, and BULK_API routing modes with and without
 * custom routing.
 */
@ClusterScope(scope = Scope.SUITE, numDataNodes = 2, numClientNodes = 0)
public class RoutingAwareReplayIT extends AoscIntegTestBase {

    // ---- SAME_SHARD mode (N→N) ----

    /**
     * Same shard count, no custom routing — straightforward 1:1 mapping.
     */
    public void testSameShardNoRouting() throws Exception {
        String source = indexName("ssn-src");
        String target = indexName("ssn-tgt");
        createSourceAndTarget(source, target, 2, 2);
        indexDocs(source, 500);

        startMigration(source, target, "ssn-alias", null);
        assertMigrationCompleted(source, 90);
        assertDocCountsMatch(source, target);

        // Verify content correctness
        for (int i = 0; i < 10; i++) {
            GetResponse resp = client().get(new GetRequest(target, String.valueOf(i))).actionGet();
            assertTrue("Doc " + i + " should exist", resp.isExists());
            assertEquals(i, resp.getSourceAsMap().get("value"));
        }
    }

    /**
     * Same shard count with custom routing — docs should be retrievable
     * with the same routing value in the target.
     */
    public void testSameShardWithCustomRouting() throws Exception {
        String source = indexName("sscr-src");
        String target = indexName("sscr-tgt");

        // Create indices with routing required
        createIndex(source, Settings.builder().put("index.number_of_shards", 2).put("index.number_of_replicas", 0).build());
        createIndex(target, Settings.builder().put("index.number_of_shards", 2).put("index.number_of_replicas", 0).build());
        ensureGreen(source, target);

        // Index docs with explicit routing
        for (int i = 0; i < 200; i++) {
            String routing = "tenant-" + (i % 5);
            client().index(
                new IndexRequest(source).id(String.valueOf(i))
                    .source("{\"value\":" + i + ",\"tenant\":\"" + routing + "\"}", XContentType.JSON)
                    .routing(routing)
                    .setRefreshPolicy(WriteRequest.RefreshPolicy.NONE)
            ).actionGet();
        }
        client().admin().indices().prepareRefresh(source).get();

        startMigration(source, target, "sscr-alias", null);
        assertMigrationCompleted(source, 90);
        assertDocCountsMatch(source, target);

        // Verify docs accessible with correct routing in target
        for (int i = 0; i < 200; i++) {
            String routing = "tenant-" + (i % 5);
            GetResponse resp = client().get(new GetRequest(target, String.valueOf(i)).routing(routing)).actionGet();
            assertTrue("Doc " + i + " should exist with routing " + routing, resp.isExists());
            assertEquals(routing, resp.getSourceAsMap().get("tenant"));
        }
    }

    // ---- SPLIT_SHARD mode (N→kN, power of 2) ----

    /**
     * Split shard (2→4) without custom routing — all docs present in target.
     */
    public void testSplitShardNoRouting() throws Exception {
        String source = indexName("spn-src");
        String target = indexName("spn-tgt");
        createSourceAndTarget(source, target, 2, 4);
        indexDocs(source, 500);

        startMigration(source, target, "spn-alias", null);
        assertMigrationCompleted(source, 90);
        assertDocCountsMatch(source, target);

        // Verify all docs accessible by ID
        for (int i = 0; i < 500; i++) {
            GetResponse resp = client().get(new GetRequest(target, String.valueOf(i))).actionGet();
            assertTrue("Doc " + i + " should exist in target", resp.isExists());
        }
    }

    /**
     * Split shard (3→6) — standard 2x factor, power of 2.
     */
    public void testSplitShard3to6() throws Exception {
        String source = indexName("sp36-src");
        String target = indexName("sp36-tgt");
        createSourceAndTarget(source, target, 3, 6);
        indexDocs(source, 600);

        startMigration(source, target, "sp36-alias", null);
        assertMigrationCompleted(source, 90);
        assertDocCountsMatch(source, target);
    }

    /**
     * Quad fan-out: 2→8 shards. Each source shard fans out to 4 target shards.
     */
    public void testSplitShardQuadFanOut() throws Exception {
        String source = indexName("spq-src");
        String target = indexName("spq-tgt");
        createSourceAndTarget(source, target, 2, 8);
        indexDocs(source, 500);

        startMigration(source, target, "spq-alias", null);
        assertMigrationCompleted(source, 90);
        assertDocCountsMatch(source, target);

        // Verify content correctness on a sample
        for (int i = 0; i < 50; i++) {
            GetResponse resp = client().get(new GetRequest(target, String.valueOf(i))).actionGet();
            assertTrue("Doc " + i + " should exist", resp.isExists());
            assertEquals(i, resp.getSourceAsMap().get("value"));
        }
    }

    /**
     * Split shard with custom routing — docs should land on correct target shard.
     */
    public void testSplitShardWithCustomRouting() throws Exception {
        String source = indexName("spcr-src");
        String target = indexName("spcr-tgt");

        createIndex(source, Settings.builder().put("index.number_of_shards", 2).put("index.number_of_replicas", 0).build());
        createIndex(target, Settings.builder().put("index.number_of_shards", 4).put("index.number_of_replicas", 0).build());
        ensureGreen(source, target);

        for (int i = 0; i < 200; i++) {
            String routing = "tenant-" + (i % 4);
            client().index(
                new IndexRequest(source).id(String.valueOf(i))
                    .source("{\"value\":" + i + ",\"tenant\":\"" + routing + "\"}", XContentType.JSON)
                    .routing(routing)
                    .setRefreshPolicy(WriteRequest.RefreshPolicy.NONE)
            ).actionGet();
        }
        client().admin().indices().prepareRefresh(source).get();

        startMigration(source, target, "spcr-alias", null);
        assertMigrationCompleted(source, 90);
        assertDocCountsMatch(source, target);

        // Verify docs accessible with correct routing
        for (int i = 0; i < 200; i++) {
            String routing = "tenant-" + (i % 4);
            GetResponse resp = client().get(new GetRequest(target, String.valueOf(i)).routing(routing)).actionGet();
            assertTrue("Doc " + i + " should exist with routing " + routing, resp.isExists());
        }
    }

    // ---- Concurrent writes during split-shard migration ----

    /**
     * Split shard (2→4) with concurrent inserts during migration — target reflects
     * the final state of the source at cutover time.
     */
    public void testSplitShardConcurrentInserts() throws Exception {
        String source = indexName("spci-src");
        String target = indexName("spci-tgt");
        createSourceAndTarget(source, target, 2, 4);
        indexDocs(source, 300);

        startMigration(source, target, "spci-alias", null);

        // Write more docs concurrently — some may be blocked during cutover
        int extraWritten = 0;
        for (int i = 300; i < 500; i++) {
            try {
                indexDoc(source, String.valueOf(i), Map.of("value", i));
                extraWritten++;
            } catch (Exception e) {
                break; // Write block during cutover
            }
        }

        assertMigrationCompleted(source, 90);

        long targetCount = getDocCount(target);
        // Target should have at least the original 300 docs
        assertTrue("Target should have at least 300 docs, got: " + targetCount, targetCount >= 300);
        // Target should not exceed total attempted writes
        assertTrue("Target should not exceed " + (300 + extraWritten) + " docs, got: " + targetCount, targetCount <= 300 + extraWritten);
    }

    /**
     * Split shard with concurrent deletes — target reflects the final state
     * of the source at cutover time.
     */
    public void testSplitShardConcurrentDeletes() throws Exception {
        String source = indexName("spcd-src");
        String target = indexName("spcd-tgt");
        createSourceAndTarget(source, target, 2, 4);
        indexDocs(source, 500);

        startMigration(source, target, "spcd-alias", null);

        // Delete some docs during migration — some may not take effect before cutover
        int deletesAttempted = 0;
        for (int i = 0; i < 100; i++) {
            try {
                client().prepareDelete(source, String.valueOf(i)).setRefreshPolicy(WriteRequest.RefreshPolicy.NONE).get();
                deletesAttempted++;
            } catch (Exception e) {
                break; // Write block during cutover
            }
        }

        assertMigrationCompleted(source, 90);

        long targetCount = getDocCount(target);
        // Target should have between (500-deletesAttempted) and 500 docs
        assertTrue(
            "Target should have at least " + (500 - deletesAttempted) + " docs, got: " + targetCount,
            targetCount >= 500 - deletesAttempted
        );
        assertTrue("Target should have at most 500 docs, got: " + targetCount, targetCount <= 500);
    }

    // ---- BULK_API mode (non-power-of-2 factor) ----

    /**
     * Non-multiple shard counts (3→5) require accept_data_loss option.
     * Without the flag, the migration should still be accepted (BULK_API mode is auto-detected)
     * but we need the flag for custom routing safety.
     */
    public void testNonMultipleShardsWithAcceptDataLossFlag() throws Exception {
        String source = indexName("nm-src");
        String target = indexName("nm-tgt");
        createSourceAndTarget(source, target, 3, 5);
        indexDocs(source, 300);

        MigrationRequestOptions options = new MigrationRequestOptions();
        options.setAcceptDataLossIfCustomRoutingIsUsed(true);

        startMigration(source, target, "nm-alias", null, options);
        assertMigrationCompleted(source, 90);
        assertDocCountsMatch(source, target);
    }

    /**
     * Non-power-of-2 multiple (3→9, factor=3) uses BULK_API mode.
     * Without custom routing, all docs should migrate correctly.
     */
    public void testNonPowerOf2Multiple() throws Exception {
        String source = indexName("np2-src");
        String target = indexName("np2-tgt");
        createSourceAndTarget(source, target, 3, 9);
        indexDocs(source, 300);

        MigrationRequestOptions options = new MigrationRequestOptions();
        options.setAcceptDataLossIfCustomRoutingIsUsed(true);

        startMigration(source, target, "np2-alias", null, options);
        assertMigrationCompleted(source, 90);
        assertDocCountsMatch(source, target);
    }

    // ---- End-to-end with alias swap ----

    /**
     * Full lifecycle with split shards and alias swap. After completion,
     * the alias should point to the target index.
     */
    public void testFullLifecycleSplitShardWithAliasSwap() throws Exception {
        String source = indexName("fla-src");
        String target = indexName("fla-tgt");
        String alias = "fla-alias-" + randomAlphaOfLength(4).toLowerCase(Locale.ROOT);
        createSourceAndTarget(source, target, 2, 4);
        indexDocs(source, 300);

        // Pre-create alias pointing to source
        client().admin().indices().prepareAliases().addAlias(source, alias).get();

        startMigration(source, target, alias, null);
        assertMigrationCompleted(source, 90);
        assertDocCountsMatch(source, target);

        // Verify alias now points to target
        SearchResponse searchResp = client().search(
            new SearchRequest(alias).source(new SearchSourceBuilder().query(QueryBuilders.matchAllQuery()).size(0))
        ).actionGet();
        assertEquals("Alias search should return docs from target", getDocCount(target), searchResp.getHits().getTotalHits().value());
    }

    /**
     * Full lifecycle: same shard count with custom routing, concurrent writes,
     * and alias swap — the complete happy path.
     */
    public void testFullLifecycleSameShardCustomRoutingConcurrentWrites() throws Exception {
        String source = indexName("flcr-src");
        String target = indexName("flcr-tgt");

        createIndex(source, Settings.builder().put("index.number_of_shards", 2).put("index.number_of_replicas", 0).build());
        createIndex(target, Settings.builder().put("index.number_of_shards", 2).put("index.number_of_replicas", 0).build());
        ensureGreen(source, target);

        // Index initial docs with routing
        for (int i = 0; i < 200; i++) {
            String routing = "org-" + (i % 3);
            client().index(
                new IndexRequest(source).id(String.valueOf(i))
                    .source("{\"value\":" + i + ",\"org\":\"" + routing + "\"}", XContentType.JSON)
                    .routing(routing)
                    .setRefreshPolicy(WriteRequest.RefreshPolicy.NONE)
            ).actionGet();
        }
        client().admin().indices().prepareRefresh(source).get();

        startMigration(source, target, "flcr-alias", null);

        // Write more docs with routing concurrently — some may be blocked during cutover
        for (int i = 200; i < 300; i++) {
            String routing = "org-" + (i % 3);
            try {
                client().index(
                    new IndexRequest(source).id(String.valueOf(i))
                        .source("{\"value\":" + i + ",\"org\":\"" + routing + "\"}", XContentType.JSON)
                        .routing(routing)
                        .setRefreshPolicy(WriteRequest.RefreshPolicy.NONE)
                ).actionGet();
            } catch (Exception e) {
                break; // Write block during cutover
            }
        }

        assertMigrationCompleted(source, 90);

        long targetCount = getDocCount(target);
        // Target should have at least the initial 200 docs
        assertTrue("Target should have at least 200 docs, got: " + targetCount, targetCount >= 200);

        // Verify routing correctness on target for the initial docs
        for (int i = 0; i < 200; i++) {
            String routing = "org-" + (i % 3);
            GetResponse resp = client().get(new GetRequest(target, String.valueOf(i)).routing(routing)).actionGet();
            assertTrue("Doc " + i + " should exist with routing " + routing, resp.isExists());
            assertEquals(routing, resp.getSourceAsMap().get("org"));
        }
    }
}
