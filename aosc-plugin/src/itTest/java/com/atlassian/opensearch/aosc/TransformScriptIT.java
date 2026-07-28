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
import org.opensearch.common.xcontent.XContentType;
import org.opensearch.core.common.bytes.BytesArray;
import org.opensearch.test.OpenSearchIntegTestCase.ClusterScope;
import org.opensearch.test.OpenSearchIntegTestCase.Scope;

import java.util.Map;

/**
 * Integration tests for document transformation during migration — inline scripts,
 * stored scripts (with and without params), and eager failure on invalid scripts.
 */
@ClusterScope(scope = Scope.SUITE, numDataNodes = 2, numClientNodes = 0)
public class TransformScriptIT extends AoscIntegTestBase {

    public void testInlineTransformAddsFieldToTargetDocs() throws Exception {
        String source = indexName("itx-inline-src");
        String target = indexName("itx-inline-tgt");
        createSourceAndTarget(source, target, 1, 1);

        for (int i = 0; i < 30; i++) {
            indexDoc(source, String.valueOf(i), Map.of("value", i));
        }

        startMigration(source, target, "itx-inline-alias", "ctx._source.migrated = true");
        assertMigrationCompleted(source, 60);
        assertDocCountsMatch(source, target);

        for (int i = 0; i < 30; i++) {
            GetResponse resp = client().get(new GetRequest(target, String.valueOf(i))).actionGet();
            assertTrue("Doc " + i + " should exist in target", resp.isExists());
            assertEquals("Inline transform should set migrated=true", true, resp.getSourceAsMap().get("migrated"));
            assertEquals("Original field should be preserved", i, resp.getSourceAsMap().get("value"));
        }
    }

    public void testStoredScriptTransformWithoutParams() throws Exception {
        registerStoredScript("add-version-field", "ctx._source.version = 2");

        String source = indexName("itx-stored-src");
        String target = indexName("itx-stored-tgt");
        createSourceAndTarget(source, target, 1, 1);

        for (int i = 0; i < 25; i++) {
            indexDoc(source, String.valueOf(i), Map.of("value", i));
        }

        startMigrationWithStoredScript(source, target, "itx-stored-alias", "add-version-field", null);
        assertMigrationCompleted(source, 60);
        assertDocCountsMatch(source, target);

        for (int i = 0; i < 25; i++) {
            GetResponse resp = client().get(new GetRequest(target, String.valueOf(i))).actionGet();
            assertTrue("Doc " + i + " should exist in target", resp.isExists());
            assertEquals("Stored script should set version=2", 2, resp.getSourceAsMap().get("version"));
            assertEquals("Original field should be preserved", i, resp.getSourceAsMap().get("value"));
        }
    }

    public void testStoredScriptTransformWithParams() throws Exception {
        registerStoredScript("add-tag-from-params", "ctx._source.tag = params.tag_value");

        String source = indexName("itx-params-src");
        String target = indexName("itx-params-tgt");
        createSourceAndTarget(source, target, 1, 1);

        for (int i = 0; i < 20; i++) {
            indexDoc(source, String.valueOf(i), Map.of("value", i));
        }

        startMigrationWithStoredScript(source, target, "itx-params-alias", "add-tag-from-params", Map.of("tag_value", "migration-v3"));
        assertMigrationCompleted(source, 60);
        assertDocCountsMatch(source, target);

        for (int i = 0; i < 20; i++) {
            GetResponse resp = client().get(new GetRequest(target, String.valueOf(i))).actionGet();
            assertTrue("Doc " + i + " should exist in target", resp.isExists());
            assertEquals("Stored script should set tag from params", "migration-v3", resp.getSourceAsMap().get("tag"));
            assertEquals("Original field should be preserved", i, resp.getSourceAsMap().get("value"));
        }
    }

    public void testInlineDryRunRejectsMigrationWhenRequiredParamMissing() {
        String source = indexName("itx-dryrun-inline-src");
        String target = indexName("itx-dryrun-inline-tgt");
        createSourceAndTarget(source, target, 1, 1);
        indexDoc(source, "1", Map.of("value", 1));

        IllegalArgumentException e = expectThrows(
            IllegalArgumentException.class,
            () -> startMigration(source, target, "itx-dryrun-inline-alias", "ctx._source.x = params.required_key")
        );
        assertTrue(
            "Error must mention dry-run, got: " + e.getMessage(),
            e.getMessage().contains("dry-run") || e.getMessage().contains("required_key")
        );
    }

    public void testStoredScriptDryRunRejectsMigrationWhenRequiredParamMissing() {
        registerStoredScript("requires-required-key", "ctx._source.x = params.required_key");

        String source = indexName("itx-dryrun-stored-src");
        String target = indexName("itx-dryrun-stored-tgt");
        createSourceAndTarget(source, target, 1, 1);
        indexDoc(source, "1", Map.of("value", 1));

        IllegalArgumentException e = expectThrows(
            IllegalArgumentException.class,
            () -> startMigrationWithStoredScript(source, target, "itx-dryrun-stored-alias", "requires-required-key", null)
        );
        assertTrue(
            "Error must mention dry-run or missing param, got: " + e.getMessage(),
            e.getMessage().contains("dry-run") || e.getMessage().contains("required_key")
        );
    }

    public void testDryRunPassesWhenAllRequiredParamsProvided() throws Exception {
        String source = indexName("itx-dryrun-ok-src");
        String target = indexName("itx-dryrun-ok-tgt");
        createSourceAndTarget(source, target, 1, 1);
        for (int i = 0; i < 5; i++) {
            indexDoc(source, String.valueOf(i), Map.of("value", i));
        }

        registerStoredScript("requires-key-ok", "ctx._source.x = params.required_key");
        startMigrationWithStoredScript(source, target, "itx-dryrun-ok-alias", "requires-key-ok", Map.of("required_key", "ok"));

        assertMigrationCompleted(source, 60);
        assertDocCountsMatch(source, target);
        for (int i = 0; i < 5; i++) {
            GetResponse resp = client().get(new GetRequest(target, String.valueOf(i))).actionGet();
            assertTrue(resp.isExists());
            assertEquals("ok", resp.getSourceAsMap().get("x"));
        }
    }

    private void registerStoredScript(String scriptId, String scriptSource) {
        String body = "{\"script\":{\"lang\":\"painless\",\"source\":\"" + scriptSource + "\"}}";
        client().admin().cluster().preparePutStoredScript().setId(scriptId).setContent(new BytesArray(body), XContentType.JSON).get();
    }
}
