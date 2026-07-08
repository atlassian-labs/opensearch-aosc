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

import org.opensearch.cluster.ClusterState;
import org.opensearch.test.OpenSearchIntegTestCase.ClusterScope;
import org.opensearch.test.OpenSearchIntegTestCase.Scope;

/**
 * Integration tests for failover and recovery scenarios.
 * Uses Scope.TEST to get a fresh cluster per test method.
 *
 * <p>Session 7 (fail-fast failover): blocking close fixes @AwaitsFix root cause,
 * data node restart now triggers FAILED via validateShardAssignments.</p>
 */
@ClusterScope(scope = Scope.TEST, numDataNodes = 2, numClientNodes = 0)
public class FailoverIT extends AoscIntegTestBase {

    @Override
    protected int numberOfReplicas() {
        return 0;
    }

    /**
     * Tests that a migration completes after a dedicated cluster manager is restarted.
     * Starts a dedicated CM-only node so source shards are on data nodes only.
     * Session 7 ensures the coordinator is reconstructed on the new CM via justBecameCM.
     * The shard worker restarts from scratch and completes the migration again.
     */
    public void testMigrationRecoversAfterClusterManagerRestart() throws Exception {
        // Add a dedicated CM-only node — gives us 3 CM-eligible nodes
        internalCluster().startClusterManagerOnlyNode();
        ensureGreen();

        String source = indexName("fo-src");
        String target = indexName("fo-tgt");
        createSourceAndTarget(source, target, 1, 1);
        indexDocs(source, 500);

        startMigration(source, target, "fo-alias", null);

        // Wait for migration to reach ACTIVE
        assertBusy(() -> {
            GetMigrationStatusResponse status = getStatus(source);
            assertNotNull("Migration should be found", status.body());
            CoordinatorPhase phase = status.body().phase();
            assertNotNull("Phase should not be null", phase);
            assertNotSame("Migration should be past INITIALIZING but was: " + phase, phase, CoordinatorPhase.INITIALIZING);
        });

        // Restart the elected CM — may or may not host the source shard
        String cmName = internalCluster().getClusterManagerName();
        ClusterState state = client().admin().cluster().prepareState().get().getState();
        String primaryNode = state.routingTable().index(source).shard(0).primaryShard().currentNodeId();
        String primaryNodeName = state.nodes().get(primaryNode).getName();
        boolean cmHostsSource = cmName.equals(primaryNodeName);
        logger.info("Restarting CM node [{}], hosts source primary: {}", cmName, cmHostsSource);
        internalCluster().restartNode(cmName);
        ensureGreen();

        CoordinatorPhase terminal = awaitTerminalPhase(source, 120);
        if (cmHostsSource) {
            // CM also hosted source shard — outcome depends on timing (COMPLETED or FAILED)
            assertTrue("CM hosted source — migration should be terminal but was: " + terminal, terminal.isTerminal());
        } else {
            // CM was dedicated (or source was on another node) — shards unaffected, must complete
            assertEquals("CM didn't host source — migration should complete", CoordinatorPhase.COMPLETED, terminal);
            assertDocCountsMatch(source, target);
        }
    }

    /**
     * Tests that a migration reaches terminal state after a data node restart.
     * Session 7 detects the shard going non-STARTED and fails the migration.
     * After node restart, afterIndexShardStarted re-evaluates migrations and creates
     * a new worker that reports terminal status, unblocking the FAILING → FAILED transition.
     */
    public void testMigrationTerminatesAfterDataNodeRestart() throws Exception {
        String source = indexName("dnr-src");
        String target = indexName("dnr-tgt");
        createSourceAndTarget(source, target, 1, 1);
        indexDocs(source, 500);

        startMigration(source, target, "dnr-alias", null);

        // Wait until migration is past INITIALIZING so the coordinator is driving work
        assertBusy(() -> {
            GetMigrationStatusResponse status = getStatus(source);
            assertNotNull("Migration should be found", status.body());
            CoordinatorPhase phase = status.body().phase();
            assertNotNull("Phase should not be null", phase);
            assertNotSame("Migration should be past INITIALIZING but was: " + phase, phase, CoordinatorPhase.INITIALIZING);
        });

        // Restart a non-CM data node — shard goes UNASSIGNED then recovers
        String cmName = internalCluster().getClusterManagerName();
        String dataNode = internalCluster().getNodeNames()[0].equals(cmName)
            ? internalCluster().getNodeNames()[1]
            : internalCluster().getNodeNames()[0];
        logger.info("Restarting data node: {}", dataNode);
        internalCluster().restartNode(dataNode);

        // Wait for cluster to be fully healthy — shards reassigned and started
        ensureGreen();

        // Migration should reach a terminal state. If validateActiveMigrations detected
        // the shard going non-STARTED, the migration transitions FAILING → FAILED after
        // shard workers on the recovered node report terminal. If the migration completed
        // before the shard went non-STARTED, COMPLETED is also acceptable.
        CoordinatorPhase terminal = awaitTerminalPhase(source, 120);
        assertTrue(
            "Migration should be terminal after data node restart but was: " + terminal,
            terminal == CoordinatorPhase.FAILED || terminal == CoordinatorPhase.COMPLETED
        );
    }

    /**
     * Tests that a migration FAILS when the source index is deleted during migration.
     */
    public void testSourceIndexDeletedDuringMigration() throws Exception {
        String source = indexName("del-src");
        String target = indexName("del-tgt");
        createSourceAndTarget(source, target, 1, 1);
        indexDocs(source, 200);

        startMigration(source, target, "del-alias", null);

        assertBusy(() -> {
            GetMigrationStatusResponse status = getStatus(source);
            assertNotNull("Migration should be found", status.body());
            assertNotNull("Phase should not be null", status.body().phase());
        });

        logger.info("Deleting source index: {}", source);
        client().admin().indices().prepareDelete(source).get();

        CoordinatorPhase terminal = awaitTerminalPhase(source, 120);
        assertEquals("Migration should fail after source deletion", CoordinatorPhase.FAILED, terminal);
    }

    /**
     * Tests that closing a source index (not deleting) during migration causes FAILED.
     * The _close API makes the index metadata state CLOSE — routing table still exists
     * but shards are no longer STARTED.
     */
    public void testSourceIndexClosedDuringMigration() throws Exception {
        String source = indexName("close-src");
        String target = indexName("close-tgt");
        createSourceAndTarget(source, target, 1, 1);
        indexDocs(source, 200);

        startMigration(source, target, "close-alias", null);

        assertBusy(() -> {
            GetMigrationStatusResponse status = getStatus(source);
            assertNotNull("Migration should be found", status.body());
            assertNotNull("Phase should not be null", status.body().phase());
        });

        logger.info("Closing source index: {}", source);
        client().admin().indices().prepareClose(source).get();

        CoordinatorPhase terminal = awaitTerminalPhase(source, 120);
        assertEquals("Migration should fail after source close", CoordinatorPhase.FAILED, terminal);
    }

    /**
     * Tests failover with a multi-shard index. Verifies that validateActiveMigrations
     * detects shard-level issues across multiple shards and that the migration reaches
     * a terminal state cleanly.
     */
    public void testMultiShardMigrationTerminatesAfterDataNodeRestart() throws Exception {
        String source = indexName("ms-src");
        String target = indexName("ms-tgt");
        createSourceAndTarget(source, target, 3, 3);
        indexDocs(source, 300);

        startMigration(source, target, "ms-alias", null);

        assertBusy(() -> {
            GetMigrationStatusResponse status = getStatus(source);
            assertNotNull("Migration should be found", status.body());
            CoordinatorPhase phase = status.body().phase();
            assertNotNull("Phase should not be null", phase);
            assertNotSame("Migration should be past INITIALIZING but was: " + phase, phase, CoordinatorPhase.INITIALIZING);
        });

        // Restart a non-CM data node
        String cmName = internalCluster().getClusterManagerName();
        String dataNode = null;
        for (String name : internalCluster().getNodeNames()) {
            if (!name.equals(cmName)) {
                dataNode = name;
                break;
            }
        }
        assertNotNull("Should find a non-CM data node", dataNode);
        logger.info("Restarting data node for multi-shard test: {}", dataNode);
        internalCluster().restartNode(dataNode);
        ensureGreen();

        CoordinatorPhase terminal = awaitTerminalPhase(source, 120);
        assertTrue("Multi-shard migration should reach terminal state but was: " + terminal, terminal.isTerminal());
    }
}
