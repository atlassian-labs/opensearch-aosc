/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */
package com.atlassian.opensearch.aosc;

import com.atlassian.opensearch.aosc.action.status.GetMigrationStatusResponse;
import com.atlassian.opensearch.aosc.model.phase.CoordinatorPhase;

import org.opensearch.ResourceNotFoundException;
import org.opensearch.action.admin.cluster.node.info.NodesInfoRequest;
import org.opensearch.action.admin.cluster.node.info.NodesInfoResponse;
import org.opensearch.action.admin.cluster.node.info.PluginsAndModules;
import org.opensearch.action.admin.indices.settings.get.GetSettingsRequest;
import org.opensearch.action.delete.DeleteRequest;
import org.opensearch.action.get.GetRequest;
import org.opensearch.action.get.GetResponse;
import org.opensearch.action.index.IndexRequest;
import org.opensearch.action.support.WriteRequest;
import org.opensearch.common.settings.Settings;
import org.opensearch.common.xcontent.XContentType;
import org.opensearch.plugins.PluginInfo;
import org.opensearch.test.OpenSearchIntegTestCase.ClusterScope;
import org.opensearch.test.OpenSearchIntegTestCase.Scope;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * Core integration tests for the AOSC plugin. Tests plugin installation,
 * REST API basics, validation, full migration lifecycles, and transforms.
 */
@ClusterScope(scope = Scope.SUITE, numDataNodes = 2, numClientNodes = 0)
public class AoscPluginIT extends AoscIntegTestBase {

    // ---- Plugin installation ----

    public void testPluginInstalled() {
        NodesInfoResponse response = client().admin().cluster().nodesInfo(new NodesInfoRequest().clear().addMetric("plugins")).actionGet();

        List<PluginInfo> pluginInfos = response.getNodes()
            .stream()
            .flatMap(n -> n.getInfo(PluginsAndModules.class).getPluginInfos().stream())
            .collect(Collectors.toList());

        assertTrue(
            "Expected AoscPlugin in " + pluginInfos,
            pluginInfos.stream().anyMatch(info -> info.getClassname().contains("AoscPlugin") || info.getName().contains("aosc"))
        );
    }

    // ---- Start API ----

    public void testStartMigrationWithPreCreatedTarget() throws Exception {
        String source = indexName("start-src");
        String target = indexName("start-tgt");
        createSourceAndTarget(source, target, 1, 1);
        indexDocs(source, 10);

        String migrationId = startMigration(source, target, "my-alias", null);
        assertNotNull("Should return a migration ID", migrationId);
        assertMigrationCompleted(source, 60);
    }

    public void testStartMigrationRejectsIfActiveMigrationExists() throws Exception {
        String source = indexName("dup-src");
        String target = indexName("dup-tgt");
        createSourceAndTarget(source, target, 1, 1);

        startMigration(source, target, "dup-alias", null);

        // Second start should fail
        try {
            startMigration(source, target, "dup-alias", null);
            fail("Should reject duplicate migration");
        } catch (Exception e) {
            assertTrue(e.getMessage().contains("Active migration already exists"));
        }

        // Wait for the first migration to complete so it doesn't leak retention leases
        assertMigrationCompleted(source, 60);
    }

    public void testStartMigrationRejectsNonExistentTarget() {
        String source = indexName("noexist-src");
        createIndex(source, Settings.builder().put("index.number_of_shards", 1).put("index.number_of_replicas", 0).build());
        ensureGreen(source);

        try {
            startMigration(source, "nonexistent-target-" + randomAlphaOfLength(5), "alias", null);
            fail("Should reject non-existent target");
        } catch (Exception e) {
            // Expected
        }
    }

    // ---- Status API ----

    public void testGetStatusNotFound() {
        expectThrows(ResourceNotFoundException.class, () -> getStatus("nonexistent-index"));
    }

    public void testGetMigrationStatus() throws Exception {
        String source = indexName("status-src");
        String target = indexName("status-tgt");
        createSourceAndTarget(source, target, 1, 1);
        indexDocs(source, 5);

        startMigration(source, target, "status-alias", null);

        // Poll until we see a non-null phase
        assertBusy(() -> {
            GetMigrationStatusResponse status = getStatus(source);
            assertNotNull("Should find migration", status.body());
            assertNotNull("Should have coordinator phase", status.body().phase());
        });

        // B012: wait for migration to finish before test cleanup runs,
        // otherwise workers still writing to .aosc-migrations can cause shard lock timeout
        assertMigrationCompleted(source, 60);
    }

    // ---- Full lifecycle ----

    public void testFullLifecycleEmptySource() throws Exception {
        String source = indexName("empty-src");
        String target = indexName("empty-tgt");
        createSourceAndTarget(source, target, 1, 1);

        startMigration(source, target, "empty-alias", null);
        assertMigrationCompleted(source, 60);

        assertEquals("Target should have 0 docs", 0, getDocCount(target));
    }

    public void testFullLifecycleWithSingleDoc() throws Exception {
        String source = indexName("single-src");
        String target = indexName("single-tgt");
        createSourceAndTarget(source, target, 1, 1);
        indexDocs(source, 1);

        startMigration(source, target, "single-alias", null);
        assertMigrationCompleted(source, 60);

        assertEquals("Target should have 1 doc", 1, getDocCount(target));
        GetResponse resp = client().get(new GetRequest(target, "0")).actionGet();
        assertTrue("Doc should exist in target", resp.isExists());
        assertEquals(0, resp.getSourceAsMap().get("value"));
    }

    public void testFullLifecycleWithManyDocs() throws Exception {
        String source = indexName("many-src");
        String target = indexName("many-tgt");
        createSourceAndTarget(source, target, 1, 1);
        indexDocs(source, 200);

        startMigration(source, target, "many-alias", null);
        assertMigrationCompleted(source, 60);

        assertDocCountsMatch(source, target);
    }

    public void testFullLifecycleWithDifferentShardCount() throws Exception {
        String source = indexName("shard-src");
        String target = indexName("shard-tgt");
        createSourceAndTarget(source, target, 1, 2);
        indexDocs(source, 100);

        startMigration(source, target, "shard-alias", null);
        assertMigrationCompleted(source, 60);

        assertDocCountsMatch(source, target);

        // Verify target has correct shard count
        String numShards = client().admin()
            .indices()
            .getSettings(new GetSettingsRequest().indices(target))
            .actionGet()
            .getSetting(target, "index.number_of_shards");
        assertEquals("2", numShards);
    }

    public void testFullLifecycleMultiShard() throws Exception {
        String source = indexName("multi-src");
        String target = indexName("multi-tgt");
        createSourceAndTarget(source, target, 3, 3);
        indexDocs(source, 300);

        startMigration(source, target, "multi-alias", null);
        assertMigrationCompleted(source, 90);

        assertDocCountsMatch(source, target);
    }

    // ---- Transforms ----

    public void testFullLifecycleWithTransformAddField() throws Exception {
        String source = indexName("txadd-src");
        String target = indexName("txadd-tgt");
        createSourceAndTarget(source, target, 1, 1);
        indexDocs(source, 50);

        startMigration(source, target, "txadd-alias", "ctx._source.version = 2");
        assertMigrationCompleted(source, 60);

        assertDocCountsMatch(source, target);

        // Verify transform was applied
        for (int i = 0; i < 50; i++) {
            GetResponse resp = client().get(new GetRequest(target, String.valueOf(i))).actionGet();
            assertTrue("Doc " + i + " should exist", resp.isExists());
            assertEquals("Transform should set version=2", 2, resp.getSourceAsMap().get("version"));
        }
    }

    public void testFullLifecycleWithTransformRemoveField() throws Exception {
        String source = indexName("txrm-src");
        String target = indexName("txrm-tgt");
        createSourceAndTarget(source, target, 1, 1);

        for (int i = 0; i < 20; i++) {
            indexDoc(source, String.valueOf(i), Map.of("value", i, "internal_id", "secret-" + i));
        }

        startMigration(source, target, "txrm-alias", "ctx._source.remove('internal_id')");
        assertMigrationCompleted(source, 60);

        assertDocCountsMatch(source, target);

        // Verify transform removed the field
        for (int i = 0; i < 20; i++) {
            GetResponse resp = client().get(new GetRequest(target, String.valueOf(i))).actionGet();
            assertTrue(resp.isExists());
            assertNull("internal_id should be removed", resp.getSourceAsMap().get("internal_id"));
            assertEquals(i, resp.getSourceAsMap().get("value"));
        }
    }

    public void testFullLifecycleWithTransformRenameField() throws Exception {
        String source = indexName("txrn-src");
        String target = indexName("txrn-tgt");
        createSourceAndTarget(source, target, 1, 1);

        for (int i = 0; i < 20; i++) {
            indexDoc(source, String.valueOf(i), Map.of("old_name", "data-" + i));
        }

        startMigration(source, target, "txrn-alias", "ctx._source.put('new_name', ctx._source.remove('old_name'))");
        assertMigrationCompleted(source, 60);

        assertDocCountsMatch(source, target);

        // Verify rename
        for (int i = 0; i < 20; i++) {
            GetResponse resp = client().get(new GetRequest(target, String.valueOf(i))).actionGet();
            assertTrue(resp.isExists());
            assertNull("old_name should be removed", resp.getSourceAsMap().get("old_name"));
            assertEquals("data-" + i, resp.getSourceAsMap().get("new_name"));
        }
    }

    public void testFullLifecycleWithIdentityTransform() throws Exception {
        String source = indexName("txid-src");
        String target = indexName("txid-tgt");
        createSourceAndTarget(source, target, 1, 1);
        indexDocs(source, 30);

        // No transform script
        startMigration(source, target, "txid-alias", null);
        assertMigrationCompleted(source, 60);

        assertDocCountsMatch(source, target);

        // Verify content preserved
        for (int i = 0; i < 30; i++) {
            GetResponse resp = client().get(new GetRequest(target, String.valueOf(i))).actionGet();
            assertTrue(resp.isExists());
            assertEquals(i, resp.getSourceAsMap().get("value"));
        }
    }

    // ---- Concurrent writes ----

    public void testConcurrentInsertsDuringMigration() throws Exception {
        String source = indexName("conc-src");
        String target = indexName("conc-tgt");
        createSourceAndTarget(source, target, 1, 1);
        indexDocs(source, 500);

        startMigration(source, target, "conc-alias", null);

        // Write more docs while migration is active — may hit write block during cutover
        for (int i = 500; i < 700; i++) {
            try {
                client().index(new IndexRequest(source).id(String.valueOf(i)).source("{\"value\":" + i + "}", XContentType.JSON))
                    .actionGet();
            } catch (Exception e) {
                break;
            }
        }

        assertMigrationCompleted(source, 90);

        client().admin().indices().prepareRefresh(source, target).get();
        // Target captures source state at cutover — some inserts may not have been
        // applied before cutover, so target may have fewer docs than post-insert source.
        long targetCount = getDocCount(target);
        assertTrue("Target should have at least 500 docs, got: " + targetCount, targetCount >= 500);
    }

    // ---- High shard count ----

    public void testFullLifecycleHighShardCount() throws Exception {
        String source = indexName("hsc-src");
        String target = indexName("hsc-tgt");
        createSourceAndTarget(source, target, 10, 10);
        indexDocs(source, 200);

        startMigration(source, target, "hsc-alias", null);
        assertMigrationCompleted(source, 120);

        assertDocCountsMatch(source, target);
    }

    // ---- Split shards ----

    public void testFullLifecycleSplitShards3to6() throws Exception {
        String source = indexName("split-src");
        String target = indexName("split-tgt");
        createSourceAndTarget(source, target, 3, 6);
        indexDocs(source, 100);

        startMigration(source, target, "split-alias", null);
        assertMigrationCompleted(source, 90);

        assertDocCountsMatch(source, target);

        // Verify content
        for (int i = 0; i < 100; i++) {
            GetResponse resp = client().get(new GetRequest(target, String.valueOf(i))).actionGet();
            assertTrue("doc " + i + " should exist in target", resp.isExists());
            assertEquals(i, resp.getSourceAsMap().get("value"));
        }
    }

    // ---- With replicas ----

    public void testFullLifecycleWithReplicas() throws Exception {
        String source = indexName("rep-src");
        String target = indexName("rep-tgt");
        createIndex(source, Settings.builder().put("index.number_of_shards", 2).put("index.number_of_replicas", 1).build());
        createIndex(target, Settings.builder().put("index.number_of_shards", 2).put("index.number_of_replicas", 1).build());
        ensureGreen(source, target);
        indexDocs(source, 50);

        startMigration(source, target, "rep-alias", null);
        assertMigrationCompleted(source, 90);

        assertDocCountsMatch(source, target);
    }

    // ---- Phase stepping ----

    public void testPhaseProgression() throws Exception {
        String source = indexName("phase-src");
        String target = indexName("phase-tgt");
        createSourceAndTarget(source, target, 1, 1);
        indexDocs(source, 50);

        startMigration(source, target, "phase-alias", null);

        // Collect phases seen during migration
        Set<CoordinatorPhase> phasesSeen = ConcurrentHashMap.newKeySet();
        assertBusy(() -> {
            GetMigrationStatusResponse status = getStatus(source);
            if (status.body() != null && status.body().phase() != null) {
                phasesSeen.add(status.body().phase());
            }
            assertTrue("Should reach terminal phase", status.body() != null && status.body().phase().isTerminal());
        }, 90, TimeUnit.SECONDS);

        // Must have passed through INITIALIZING
        assertTrue("Should have seen INITIALIZING", phasesSeen.contains(CoordinatorPhase.INITIALIZING));
        // Must end at COMPLETED
        assertTrue("Should end at COMPLETED", phasesSeen.contains(CoordinatorPhase.COMPLETED));
    }

    // ---- B007 validation tests ----

    /**
     * B007 deterministic reproduction: forces ASYNC durability on the source index
     * to guarantee that GCP lags, then runs a migration with concurrent updates.
     *
     * BEFORE FIX: this test ALWAYS fails because the replay range is empty.
     * AFTER FIX:  this test ALWAYS passes because the coordinator flushes via
     *             client API before signalling shards to catch up.
     */
    public void testConcurrentUpdatesWithAsyncDurability() throws Exception {
        String source = indexName("b007-async-src");
        String target = indexName("b007-async-tgt");

        // Force ASYNC durability on source to deterministically reproduce GCP lag
        createIndex(
            source,
            Settings.builder()
                .put("index.number_of_shards", 1)
                .put("index.number_of_replicas", 0)
                .put("index.translog.durability", "ASYNC")
                .put("index.translog.sync_interval", "999s")
                .build()
        );
        createIndex(target, Settings.builder().put("index.number_of_shards", 1).put("index.number_of_replicas", 0).build());
        ensureGreen(source, target);

        indexDocs(source, 500);

        startMigration(source, target, "b007-alias", null);

        // Update docs while migration is active
        int updatesApplied = 0;
        for (int i = 0; i < 200; i++) {
            try {
                client().index(new IndexRequest(source).id(String.valueOf(i)).source("{\"value\":" + (i + 1000) + "}", XContentType.JSON))
                    .actionGet();
                updatesApplied++;
            } catch (Exception e) {
                break; // write block applied
            }
        }
        logger.info("B007 async test: {} updates applied", updatesApplied);

        assertMigrationCompleted(source, 90);
        client().admin().indices().prepareRefresh(source, target).get();
        assertDocCountsMatch(source, target);

        // Content correctness: every updated doc must have the updated value in target
        int mismatches = 0;
        for (int i = 0; i < Math.min(updatesApplied, 50); i++) {
            GetResponse sourceDoc = client().prepareGet(source, String.valueOf(i)).get();
            GetResponse targetDoc = client().prepareGet(target, String.valueOf(i)).get();
            assertTrue("Doc " + i + " should exist in source", sourceDoc.isExists());
            assertTrue("Doc " + i + " should exist in target", targetDoc.isExists());
            Object srcVal = sourceDoc.getSourceAsMap().get("value");
            Object tgtVal = targetDoc.getSourceAsMap().get("value");
            if (!srcVal.equals(tgtVal)) {
                mismatches++;
                logger.error("B007 MISMATCH doc={} srcVal={} tgtVal={}", i, srcVal, tgtVal);
            }
        }
        assertEquals("Updated docs should have correct values in target", 0, mismatches);
    }

    // ---- Concurrent updates and deletes ----
    public void testConcurrentUpdatesDuringMigration() throws Exception {
        String source = indexName("cupd-src");
        String target = indexName("cupd-tgt");
        createSourceAndTarget(source, target, 1, 1);
        indexDocs(source, 500);

        startMigration(source, target, "cupd-alias", null);

        // Update existing docs while migration is active — may hit write block during cutover
        int updatesApplied = 0;
        for (int i = 0; i < 200; i++) {
            try {
                client().index(new IndexRequest(source).id(String.valueOf(i)).source("{\"value\":" + (i + 1000) + "}", XContentType.JSON))
                    .actionGet();
                updatesApplied++;
            } catch (Exception e) {
                break;
            }
        }

        assertMigrationCompleted(source, 90);

        client().admin().indices().prepareRefresh(source, target).get();
        assertDocCountsMatch(source, target);

        // B007: Verify content correctness — updated docs should have the updated values
        logger.info("B007 diag: updatesApplied={}", updatesApplied);
        int mismatches = 0;
        for (int i = 0; i < Math.min(updatesApplied, 50); i++) {
            GetResponse sourceDoc = client().prepareGet(source, String.valueOf(i)).get();
            GetResponse targetDoc = client().prepareGet(target, String.valueOf(i)).get();
            assertTrue("Doc " + i + " should exist in source", sourceDoc.isExists());
            assertTrue("Doc " + i + " should exist in target", targetDoc.isExists());
            Object srcVal = sourceDoc.getSourceAsMap().get("value");
            Object tgtVal = targetDoc.getSourceAsMap().get("value");
            if (!srcVal.equals(tgtVal)) {
                mismatches++;
                logger.error(
                    "B007 MISMATCH doc={} srcVal={} tgtVal={} srcSeqNo={} tgtSeqNo={}",
                    i,
                    srcVal,
                    tgtVal,
                    sourceDoc.getSeqNo(),
                    targetDoc.getSeqNo()
                );
            }
        }
        assertEquals("Docs with mismatched values after migration", 0, mismatches);
    }

    public void testConcurrentDeletesDuringMigration() throws Exception {
        String source = indexName("cdel-src");
        String target = indexName("cdel-tgt");
        createSourceAndTarget(source, target, 1, 1);
        indexDocs(source, 500);

        startMigration(source, target, "cdel-alias", null);

        // Delete some docs while migration is active — may hit write block during cutover
        for (int i = 0; i < 100; i++) {
            try {
                client().delete(new DeleteRequest(source, String.valueOf(i))).actionGet();
            } catch (Exception e) {
                break;
            }
        }

        assertMigrationCompleted(source, 90);

        client().admin().indices().prepareRefresh(source, target).get();
        // Target captures source state at cutover — some deletes may not have been
        // applied before cutover, so target may have more docs than source.
        long targetCount = getDocCount(target);
        assertTrue("Target should have at least 400 docs, got: " + targetCount, targetCount >= 400);
        assertTrue("Target should have at most 500 docs, got: " + targetCount, targetCount <= 500);
    }

    // ---- Multiple concurrent migrations ----

    public void testMultipleConcurrentMigrations() throws Exception {
        String source1 = indexName("mcm1-src");
        String target1 = indexName("mcm1-tgt");
        String source2 = indexName("mcm2-src");
        String target2 = indexName("mcm2-tgt");

        createSourceAndTarget(source1, target1, 1, 1);
        createSourceAndTarget(source2, target2, 1, 1);
        indexDocs(source1, 30);
        indexDocs(source2, 30);

        startMigration(source1, target1, "mcm1-alias", null);
        startMigration(source2, target2, "mcm2-alias", null);

        assertMigrationCompleted(source1, 90);
        assertMigrationCompleted(source2, 90);

        assertDocCountsMatch(source1, target1);
        assertDocCountsMatch(source2, target2);
    }

    // ---- Custom routing ----

    public void testFullLifecycleWithCustomRouting() throws Exception {
        String source = indexName("route-src");
        String target = indexName("route-tgt");

        // Create indices with routing required
        createIndex(source, Settings.builder().put("index.number_of_shards", 2).put("index.number_of_replicas", 0).build());
        createIndex(target, Settings.builder().put("index.number_of_shards", 2).put("index.number_of_replicas", 0).build());
        ensureGreen(source, target);

        // Index docs with explicit routing
        for (int i = 0; i < 30; i++) {
            String routing = "tenant-" + (i % 3); // 3 routing values across 2 shards
            client().index(
                new IndexRequest(source).id(String.valueOf(i))
                    .source("{\"value\":" + i + ",\"tenant\":\"" + routing + "\"}", XContentType.JSON)
                    .routing(routing)
                    .setRefreshPolicy(WriteRequest.RefreshPolicy.NONE)
            ).actionGet();
        }
        client().admin().indices().prepareRefresh(source).get();

        startMigration(source, target, "route-alias", null);
        assertMigrationCompleted(source, 90);

        client().admin().indices().prepareRefresh(target).get();
        assertDocCountsMatch(source, target);

        // Verify docs are accessible with correct routing in target
        for (int i = 0; i < 30; i++) {
            String routing = "tenant-" + (i % 3);
            GetResponse resp = client().get(new GetRequest(target, String.valueOf(i)).routing(routing)).actionGet();
            assertTrue("doc " + i + " should exist with routing " + routing, resp.isExists());
            assertEquals(i, resp.getSourceAsMap().get("value"));
            assertEquals(routing, resp.getSourceAsMap().get("tenant"));
        }
    }

    // ---- Delete + recreate ----

    public void testCreateDeleteRecreateCorrectness() throws Exception {
        String source = indexName("cdr-src");
        String target = indexName("cdr-tgt");
        createSourceAndTarget(source, target, 1, 1);

        // Create, delete, recreate
        indexDoc(source, "doc1", Map.of("value", 1));
        client().delete(new DeleteRequest(source, "doc1")).actionGet();
        indexDoc(source, "doc1", Map.of("value", 999));
        client().admin().indices().prepareRefresh(source).get();

        startMigration(source, target, "cdr-alias", null);
        assertMigrationCompleted(source, 60);

        // Target should have the final state
        GetResponse resp = client().get(new GetRequest(target, "doc1")).actionGet();
        assertTrue("doc1 should exist", resp.isExists());
        assertEquals("Should have final value", 999, resp.getSourceAsMap().get("value"));
    }
}
