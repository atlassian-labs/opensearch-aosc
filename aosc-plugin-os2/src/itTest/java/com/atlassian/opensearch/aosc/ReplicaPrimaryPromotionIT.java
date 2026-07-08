/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */
package com.atlassian.opensearch.aosc;

import com.atlassian.opensearch.aosc.model.phase.CoordinatorPhase;

import org.opensearch.cluster.ClusterState;
import org.opensearch.common.settings.Settings;
import org.opensearch.node.Node;
import org.opensearch.test.OpenSearchIntegTestCase.ClusterScope;
import org.opensearch.test.OpenSearchIntegTestCase.Scope;

/** Migration completes after replica-to-primary promotion. */
@ClusterScope(scope = Scope.TEST, numDataNodes = 3, numClientNodes = 0)
public class ReplicaPrimaryPromotionIT extends AoscIntegTestBase {

    @Override
    protected int numberOfReplicas() {
        return 1;
    }

    /** Stops primary node to force replica promotion, then verifies migration completes. */
    public void testMigrationCompletesAfterReplicaPromotedToPrimary() throws Exception {
        String source = indexName("rpp-src");
        String target = indexName("rpp-tgt");

        createIndex(source, Settings.builder().put("index.number_of_shards", 1).put("index.number_of_replicas", 1).build());
        indexDocs(source, 500);
        ensureGreen(source);

        ClusterState state = client().admin().cluster().prepareState().get().getState();
        String primaryNodeId = state.routingTable().index(source).shard(0).primaryShard().currentNodeId();
        String primaryNodeName = state.nodes().get(primaryNodeId).getName();
        String replicaNodeName = state.nodes()
            .get(state.routingTable().index(source).shard(0).replicaShards().get(0).currentNodeId())
            .getName();

        logger.info("Stopping primary node [{}] to force replica promotion on [{}]", primaryNodeName, replicaNodeName);
        internalCluster().stopRandomNode(settings -> Node.NODE_NAME_SETTING.get(settings).equals(primaryNodeName));
        ensureYellow(source);

        ClusterState stateAfterStop = client().admin().cluster().prepareState().get().getState();
        String newPrimaryNodeName = stateAfterStop.nodes()
            .get(stateAfterStop.routingTable().index(source).shard(0).primaryShard().currentNodeId())
            .getName();
        assertEquals("Replica should have been promoted to primary", replicaNodeName, newPrimaryNodeName);

        createIndex(target, Settings.builder().put("index.number_of_shards", 1).put("index.number_of_replicas", 0).build());
        ensureGreen(target);
        startMigration(source, target, "rpp-alias", null);

        CoordinatorPhase terminal = awaitTerminalPhase(source, 120);
        assertEquals(CoordinatorPhase.COMPLETED, terminal);
        assertDocCountsMatch(source, target);
    }
}
