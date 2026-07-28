/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */
package com.atlassian.opensearch.aosc;

import org.opensearch.action.get.GetRequest;
import org.opensearch.action.get.GetResponse;
import org.opensearch.action.search.SearchRequest;
import org.opensearch.action.search.SearchResponse;
import org.opensearch.common.settings.Settings;
import org.opensearch.index.query.QueryBuilders;
import org.opensearch.search.builder.SearchSourceBuilder;
import org.opensearch.test.OpenSearchIntegTestCase.ClusterScope;
import org.opensearch.test.OpenSearchIntegTestCase.Scope;

import java.util.Locale;

/**
 * Integration tests for multi-node migration scenarios. Validates that
 * shard workers run correctly across multiple data nodes and that
 * replicas, aliases, and transforms work in a distributed setup.
 */
@ClusterScope(scope = Scope.SUITE, numDataNodes = 2, numClientNodes = 0)
public class MultiNodeReplayIT extends AoscIntegTestBase {

    /**
     * 3-shard source across 2 nodes — all docs replayed to target.
     */
    public void testMultiNodeReplayAllDocsPresent() throws Exception {
        String source = indexName("mnr-src");
        String target = indexName("mnr-tgt");
        createSourceAndTarget(source, target, 3, 3);
        indexDocs(source, 1000);

        startMigration(source, target, "mnr-alias", null);
        assertMigrationCompleted(source, 90);
        assertDocCountsMatch(source, target);

        // Spot-check content
        for (int i = 0; i < 50; i++) {
            GetResponse resp = client().get(new GetRequest(target, String.valueOf(i))).actionGet();
            assertTrue("Doc " + i + " should exist", resp.isExists());
            assertEquals(i, resp.getSourceAsMap().get("value"));
        }
    }

    /**
     * Multi-node with alias swap — alias points to target after completion.
     */
    public void testFullLifecycleMultiNodeWithAlias() throws Exception {
        String source = indexName("mna-src");
        String target = indexName("mna-tgt");
        String alias = "mna-alias-" + randomAlphaOfLength(4).toLowerCase(Locale.ROOT);
        createSourceAndTarget(source, target, 3, 3);
        indexDocs(source, 500);

        // Pre-create alias pointing to source
        client().admin().indices().prepareAliases().addAlias(source, alias).get();

        startMigration(source, target, alias, null);
        assertMigrationCompleted(source, 90);
        assertDocCountsMatch(source, target);

        // Verify alias now points to target
        SearchResponse searchResp = client().search(
            new SearchRequest(alias).source(new SearchSourceBuilder().query(QueryBuilders.matchAllQuery()).size(0))
        ).actionGet();
        assertEquals("Alias should return target doc count", getDocCount(target), searchResp.getHits().getTotalHits().value());
    }

    /**
     * Multi-node with transform — all docs in target are transformed.
     */
    public void testMultiNodeReplayWithTransform() throws Exception {
        String source = indexName("mnt-src");
        String target = indexName("mnt-tgt");
        createSourceAndTarget(source, target, 3, 3);
        indexDocs(source, 500);

        startMigration(source, target, "mnt-alias", "ctx._source.version = 2");
        assertMigrationCompleted(source, 90);
        assertDocCountsMatch(source, target);

        // Verify all docs have version=2
        for (int i = 0; i < 500; i++) {
            GetResponse resp = client().get(new GetRequest(target, String.valueOf(i))).actionGet();
            assertTrue("Doc " + i + " should exist", resp.isExists());
            assertEquals("Doc " + i + " should have version=2", 2, resp.getSourceAsMap().get("version"));
        }
    }

    /**
     * Source with 1 replica — migration uses primaries only, replicas don't interfere.
     */
    public void testFullLifecycleWithReplicas() throws Exception {
        String source = indexName("mnrep-src");
        String target = indexName("mnrep-tgt");

        createIndex(source, Settings.builder().put("index.number_of_shards", 2).put("index.number_of_replicas", 1).build());
        createIndex(target, Settings.builder().put("index.number_of_shards", 2).put("index.number_of_replicas", 0).build());
        ensureGreen(source, target);

        indexDocs(source, 500);

        startMigration(source, target, "mnrep-alias", null);
        assertMigrationCompleted(source, 90);
        assertDocCountsMatch(source, target);
    }

    /**
     * Shard count change (2→4) with replicas on source — correct doc count in target.
     */
    public void testShardChangeWithReplicas() throws Exception {
        String source = indexName("mncr-src");
        String target = indexName("mncr-tgt");

        createIndex(source, Settings.builder().put("index.number_of_shards", 2).put("index.number_of_replicas", 1).build());
        createIndex(target, Settings.builder().put("index.number_of_shards", 4).put("index.number_of_replicas", 0).build());
        ensureGreen(source, target);

        indexDocs(source, 500);

        startMigration(source, target, "mncr-alias", null);
        assertMigrationCompleted(source, 90);
        assertDocCountsMatch(source, target);
    }
}
