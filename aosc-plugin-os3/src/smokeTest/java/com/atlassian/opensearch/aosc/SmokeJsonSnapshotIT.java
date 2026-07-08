/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */
package com.atlassian.opensearch.aosc;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Contract test for the REST API JSON wire format.
 *
 * <p>Runs a single-shard migration to completion and then asserts every
 * expected field name, type, and nested structure in the status response.
 * Catches field renames, missing fields, and type changes that would
 * break API consumers.</p>
 */
public class SmokeJsonSnapshotIT extends AoscSmokeTestBase {

    // ---- Expected top-level fields in a COMPLETED status response ----

    private static final Set<String> REQUIRED_TOP_LEVEL_FIELDS = Set.of(
        "migration_id",
        "source_index",
        "target_index",
        "alias",
        "phase",
        "options",
        "shard_routing_mode",
        "start_time_millis",
        "last_updated_millis",
        "shards",
        "transition_history",
        "meta"
    );

    private static final Set<String> OPTIONAL_TOP_LEVEL_FIELDS = Set.of(
        "transform_script",
        "synthetic_routings",
        "error_message",
        "cutover_context"
    );

    // ---- Expected shard-level fields ----

    private static final Set<String> REQUIRED_SHARD_FIELDS = Set.of(
        "phase",
        "last_replayed_seq_no",
        "target_seq_no",
        "backfill_cutoff_seq_no",
        "backfill",
        "transition_history",
        "meta"
    );

    private static final Set<String> OPTIONAL_SHARD_FIELDS = Set.of("error", "replay", "convergence", "catching_up");

    // ---- Expected PhaseMetrics fields ----

    private static final Set<String> PHASE_METRICS_FIELDS = Set.of(
        "status",
        "start_seq_no",
        "target_seq_no",
        "last_processed_seq_no",
        "documents_indexed",
        "documents_skipped",
        "operations_applied",
        "operations_skipped",
        "bulk_retries",
        "rounds",
        "current_gap",
        "start_time_millis",
        "end_time_millis"
    );

    // ---- Expected CutoverContext fields ----

    private static final Set<String> CUTOVER_CONTEXT_FIELDS = Set.of(
        "source_doc_count",
        "target_doc_count",
        "doc_count_tolerance",
        "doc_count_validation_passed",
        "alias_swap_succeeded",
        "cutover_start_millis",
        "cutover_end_millis"
    );

    // ---- Expected TransitionRecord fields ----

    private static final Set<String> TRANSITION_RECORD_FIELDS = Set.of("phase", "start_time_millis", "end_time_millis");

    /**
     * Full JSON wire format contract test.
     *
     * <p>Starts a single-shard migration, waits for COMPLETED, then validates
     * every field name and type in the status response.</p>
     */
    @SuppressWarnings("unchecked")
    public void testCompletedStatusJsonStructure() throws Exception {
        String source = indexName("json-src");
        String target = indexName("json-tgt");
        String alias = indexName("json-alias");
        createSourceAndTarget(source, target, 1, 1);
        bulkIndex(source, 200);

        startMigration(source, target, alias);
        waitForCompletion(source, 90);

        // Fetch the raw status response
        Map<String, Object> status = getStatus(source);

        // ---- Top-level field presence ----
        for (String field : REQUIRED_TOP_LEVEL_FIELDS) {
            assertNotNull("Missing required top-level field: " + field, status.get(field));
        }
        // Verify no unexpected fields
        for (String key : status.keySet()) {
            assertTrue(
                "Unexpected top-level field: " + key,
                REQUIRED_TOP_LEVEL_FIELDS.contains(key) || OPTIONAL_TOP_LEVEL_FIELDS.contains(key)
            );
        }

        // ---- Top-level field types ----
        assertIsString(status, "migration_id");
        assertIsString(status, "source_index");
        assertIsString(status, "target_index");
        assertIsString(status, "alias");
        assertIsString(status, "phase");
        assertIsString(status, "shard_routing_mode");
        assertIsNumber(status, "start_time_millis");
        assertIsNumber(status, "last_updated_millis");
        assertIsMap(status, "options");
        assertIsMap(status, "shards");
        assertIsList(status, "transition_history");
        assertIsMap(status, "meta");

        // ---- Phase value ----
        assertEquals("COMPLETED", status.get("phase"));
        assertEquals(source, status.get("source_index"));
        assertEquals(target, status.get("target_index"));
        assertEquals(alias, status.get("alias"));

        // ---- Shards map structure ----
        Map<String, Object> shards = (Map<String, Object>) status.get("shards");
        assertEquals("Should have exactly 1 shard", 1, shards.size());
        assertTrue("Shard key should be '0'", shards.containsKey("0"));

        Map<String, Object> shard0 = (Map<String, Object>) shards.get("0");
        for (String field : REQUIRED_SHARD_FIELDS) {
            assertNotNull("Missing required shard field: " + field, shard0.get(field));
        }
        for (String key : shard0.keySet()) {
            assertTrue("Unexpected shard field: " + key, REQUIRED_SHARD_FIELDS.contains(key) || OPTIONAL_SHARD_FIELDS.contains(key));
        }

        // Shard phase should be terminal
        String shardPhase = (String) shard0.get("phase");
        assertTrue("Shard phase should be terminal, got: " + shardPhase, Set.of("COMPLETED", "CANCELLED", "FAILED").contains(shardPhase));

        // ---- Backfill PhaseMetrics structure ----
        Map<String, Object> backfill = (Map<String, Object>) shard0.get("backfill");
        assertNotNull("Backfill metrics should be present", backfill);
        for (String field : PHASE_METRICS_FIELDS) {
            assertNotNull("Missing backfill metrics field: " + field, backfill.get(field));
        }
        assertEquals("COMPLETED", backfill.get("status"));
        assertIsNumber(backfill, "documents_indexed");
        assertTrue("Should have indexed some docs", ((Number) backfill.get("documents_indexed")).longValue() > 0);

        // ---- Transition history structure ----
        List<Map<String, Object>> transitionHistory = (List<Map<String, Object>>) status.get("transition_history");
        assertFalse("Transition history should not be empty", transitionHistory.isEmpty());
        Map<String, Object> firstTransition = transitionHistory.get(0);
        for (String field : TRANSITION_RECORD_FIELDS) {
            assertNotNull("Missing transition record field: " + field, firstTransition.get(field));
        }
        assertIsString(firstTransition, "phase");
        assertIsNumber(firstTransition, "start_time_millis");
        assertIsNumber(firstTransition, "end_time_millis");

        // ---- Shard transition history ----
        List<Map<String, Object>> shardHistory = (List<Map<String, Object>>) shard0.get("transition_history");
        assertFalse("Shard transition history should not be empty", shardHistory.isEmpty());
        for (String field : TRANSITION_RECORD_FIELDS) {
            assertNotNull("Missing shard transition record field: " + field, shardHistory.get(0).get(field));
        }

        // ---- CutoverContext (present on COMPLETED migrations) ----
        Map<String, Object> cutover = (Map<String, Object>) status.get("cutover_context");
        if (cutover != null) {
            for (String field : CUTOVER_CONTEXT_FIELDS) {
                assertNotNull("Missing cutover_context field: " + field, cutover.get(field));
            }
            assertIsNumber(cutover, "source_doc_count");
            assertIsNumber(cutover, "target_doc_count");
            assertTrue("Doc count validation should pass", (Boolean) cutover.get("doc_count_validation_passed"));
            assertTrue("Alias swap should succeed", (Boolean) cutover.get("alias_swap_succeeded"));
        }

        // ---- Options sub-object round-trips ----
        Map<String, Object> options = (Map<String, Object>) status.get("options");
        assertNotNull("Options should be present", options);
        // Options may be empty (all defaults) but should be a valid map
        assertTrue("Options should be a Map", options instanceof Map);
    }

    // ---- Type assertion helpers ----

    private static void assertIsString(Map<String, Object> map, String key) {
        Object val = map.get(key);
        assertNotNull("Field " + key + " should not be null", val);
        assertTrue("Field " + key + " should be String, got " + val.getClass().getSimpleName(), val instanceof String);
    }

    private static void assertIsNumber(Map<String, Object> map, String key) {
        Object val = map.get(key);
        assertNotNull("Field " + key + " should not be null", val);
        assertTrue("Field " + key + " should be Number, got " + val.getClass().getSimpleName(), val instanceof Number);
    }

    private static void assertIsMap(Map<String, Object> map, String key) {
        Object val = map.get(key);
        assertNotNull("Field " + key + " should not be null", val);
        assertTrue("Field " + key + " should be Map, got " + val.getClass().getSimpleName(), val instanceof Map);
    }

    private static void assertIsList(Map<String, Object> map, String key) {
        Object val = map.get(key);
        assertNotNull("Field " + key + " should not be null", val);
        assertTrue("Field " + key + " should be List, got " + val.getClass().getSimpleName(), val instanceof List);
    }
}
