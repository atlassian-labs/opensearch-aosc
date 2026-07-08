/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */
package com.atlassian.opensearch.aosc;

import org.opensearch.action.admin.indices.create.CreateIndexRequest;
import org.opensearch.action.index.IndexRequest;
import org.opensearch.action.support.WriteRequest;
import org.opensearch.common.settings.Settings;
import org.opensearch.common.xcontent.XContentType;
import org.opensearch.test.OpenSearchIntegTestCase.ClusterScope;
import org.opensearch.test.OpenSearchIntegTestCase.Scope;

/**
 * Regression test for B037: backfill must handle indices with nested field mappings.
 * Nested objects are stored as separate Lucene documents without _id or _source.
 */
@ClusterScope(scope = Scope.TEST, numDataNodes = 1)
public class NestedDocsBackfillIT extends AoscIntegTestBase {

    public void testMigrationWithNestedFields() throws Exception {
        String source = indexName("nested-src");
        String target = indexName("nested-tgt");
        String alias = "nested-alias-" + source;

        // Create source index with nested field mapping
        String mapping = "{"
            + "\"properties\": {"
            + "  \"title\": {\"type\": \"text\"},"
            + "  \"tags\": {"
            + "    \"type\": \"nested\","
            + "    \"properties\": {"
            + "      \"name\": {\"type\": \"keyword\"},"
            + "      \"score\": {\"type\": \"integer\"}"
            + "    }"
            + "  }"
            + "}"
            + "}";

        client().admin()
            .indices()
            .create(
                new CreateIndexRequest(source).settings(
                    Settings.builder().put("index.number_of_shards", 1).put("index.number_of_replicas", 0)
                ).mapping(mapping, XContentType.JSON)
            )
            .actionGet();

        // Create target with same mapping
        client().admin()
            .indices()
            .create(
                new CreateIndexRequest(target).settings(
                    Settings.builder().put("index.number_of_shards", 1).put("index.number_of_replicas", 0)
                ).mapping(mapping, XContentType.JSON)
            )
            .actionGet();
        ensureGreen(source, target);

        // Index documents with nested objects
        int docCount = 20;
        for (int i = 0; i < docCount; i++) {
            String doc = "{"
                + "\"title\": \"doc-"
                + i
                + "\","
                + "\"tags\": ["
                + "  {\"name\": \"tag-a-"
                + i
                + "\", \"score\": "
                + (i * 10)
                + "},"
                + "  {\"name\": \"tag-b-"
                + i
                + "\", \"score\": "
                + (i * 20)
                + "}"
                + "]"
                + "}";
            client().index(
                new IndexRequest(source).id(String.valueOf(i))
                    .source(doc, XContentType.JSON)
                    .setRefreshPolicy(WriteRequest.RefreshPolicy.NONE)
            ).actionGet();
        }
        client().admin().indices().prepareRefresh(source).get();

        // getDocCount returns total Lucene docs including nested child docs.
        // 20 parent docs × (1 parent + 2 nested children) = 60 total Lucene docs.
        long sourceCount = getDocCount(source);
        assertEquals("Source should have parent + nested docs", docCount * 3, sourceCount);

        // Start migration — this should succeed (B037 fix)
        startMigration(source, target, alias, null);
        assertMigrationCompleted(source, 30);

        // Target should have the same total Lucene doc count (parents + nested)
        assertDocCountsMatch(source, target);
    }

    public void testMigrationWithDeeplyNestedFields() throws Exception {
        String source = indexName("deep-nested-src");
        String target = indexName("deep-nested-tgt");
        String alias = "deep-nested-alias-" + source;

        // Create source with multiple levels of nesting
        String mapping = "{"
            + "\"properties\": {"
            + "  \"name\": {\"type\": \"text\"},"
            + "  \"comments\": {"
            + "    \"type\": \"nested\","
            + "    \"properties\": {"
            + "      \"author\": {\"type\": \"keyword\"},"
            + "      \"text\": {\"type\": \"text\"},"
            + "      \"replies\": {"
            + "        \"type\": \"nested\","
            + "        \"properties\": {"
            + "          \"author\": {\"type\": \"keyword\"},"
            + "          \"text\": {\"type\": \"text\"}"
            + "        }"
            + "      }"
            + "    }"
            + "  }"
            + "}"
            + "}";

        client().admin()
            .indices()
            .create(
                new CreateIndexRequest(source).settings(
                    Settings.builder().put("index.number_of_shards", 1).put("index.number_of_replicas", 0)
                ).mapping(mapping, XContentType.JSON)
            )
            .actionGet();
        client().admin()
            .indices()
            .create(
                new CreateIndexRequest(target).settings(
                    Settings.builder().put("index.number_of_shards", 1).put("index.number_of_replicas", 0)
                ).mapping(mapping, XContentType.JSON)
            )
            .actionGet();
        ensureGreen(source, target);

        // Index docs with deeply nested objects
        for (int i = 0; i < 10; i++) {
            String doc = "{"
                + "\"name\": \"post-"
                + i
                + "\","
                + "\"comments\": ["
                + "  {"
                + "    \"author\": \"alice\","
                + "    \"text\": \"comment-"
                + i
                + "\","
                + "    \"replies\": ["
                + "      {\"author\": \"bob\", \"text\": \"reply-"
                + i
                + "-1\"},"
                + "      {\"author\": \"carol\", \"text\": \"reply-"
                + i
                + "-2\"}"
                + "    ]"
                + "  }"
                + "]"
                + "}";
            client().index(
                new IndexRequest(source).id(String.valueOf(i))
                    .source(doc, XContentType.JSON)
                    .setRefreshPolicy(WriteRequest.RefreshPolicy.NONE)
            ).actionGet();
        }
        client().admin().indices().prepareRefresh(source).get();

        // 10 parents × (1 parent + 1 comment + 2 replies) = 40 Lucene docs
        assertEquals(40, getDocCount(source));

        startMigration(source, target, alias, null);
        assertMigrationCompleted(source, 30);
        assertDocCountsMatch(source, target);
    }
}
