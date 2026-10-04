/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */
package com.atlassian.opensearch.aosc.service.worker.routing;

import com.atlassian.opensearch.aosc.compat.TranslogDeleteOp;
import com.atlassian.opensearch.aosc.model.DeleteRoutingStrategy;
import com.atlassian.opensearch.aosc.model.DeletedDoc;
import com.atlassian.opensearch.aosc.model.ShardRoutingMode;
import com.atlassian.opensearch.aosc.utils.SyntheticRoutingHelper;

import org.opensearch.Version;
import org.opensearch.cluster.ClusterState;
import org.opensearch.cluster.metadata.IndexMetadata;
import org.opensearch.cluster.metadata.MetadataCreateIndexService;
import org.opensearch.cluster.node.DiscoveryNodes;
import org.opensearch.cluster.routing.OperationRouting;
import org.opensearch.common.settings.Settings;
import org.opensearch.index.translog.Translog;
import org.opensearch.test.OpenSearchTestCase;

import java.util.List;
import java.util.stream.Collectors;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class DeleteOperationRouterTests extends OpenSearchTestCase {

    private static final TranslogDeleteOp DELETE = new TranslogDeleteOp(new Translog.Delete("doc-1", 7, 1));

    public void testSameShardUsesSyntheticRouting() {
        var router = new DeleteOperationRouter(
            DeleteRoutingStrategy.SHARD_TOPOLOGY,
            ShardRoutingMode.SAME_SHARD,
            2,
            new String[] { "r0", "r1" },
            1
        );

        List<DeletedDoc> requests = router.route(DELETE);

        assertEquals(1, requests.size());
        assertEquals("doc-1", requests.get(0).id());
        assertEquals("r1", requests.get(0).routing());
        assertEquals("candidates carry the source shard for ctx.source_shard_id", 1, requests.get(0).sourceShardId());
    }

    public void testForShardComputesSyntheticRoutingsOnlyWhenNeeded() {
        IndexMetadata source = index("src", 2, 0);
        IndexMetadata target = index("tgt", 2, 0);
        String expected = SyntheticRoutingHelper.computeSyntheticRoutings(target)[1];
        var sameShard = DeleteOperationRouter.forShard(
            DeleteRoutingStrategy.SHARD_TOPOLOGY,
            ShardRoutingMode.SAME_SHARD,
            source,
            target,
            1
        );
        assertEquals(expected, sameShard.route(DELETE).get(0).routing());

        // BULK_API never uses synthetic routings, so a routing-partitioned target must not fail here.
        IndexMetadata partitioned = index("tgt-p", 3, 2);
        var bulk = DeleteOperationRouter.forShard(DeleteRoutingStrategy.SHARD_TOPOLOGY, ShardRoutingMode.BULK_API, source, partitioned, 1);
        assertNull(bulk.route(DELETE).get(0).routing());
    }

    public void testSyntheticRoutingReachesTheShardHoldingTheDocument() {
        int[][] topologies = { { 2, 2 }, { 2, 4 }, { 3, 6 }, { 4, 2 }, { 6, 3 }, { 1, 4 }, { 4, 1 } };
        for (int[] shards : topologies) {
            IndexMetadata source = withDefaultRoutingShards("src", shards[0]);
            IndexMetadata target = withDefaultRoutingShards("tgt", shards[1]);
            ShardRoutingMode mode = SyntheticRoutingHelper.detectRoutingMode(source, target);
            assertNotEquals(ShardRoutingMode.BULK_API, mode);
            for (int i = 0; i < 100; i++) {
                String id = randomAlphaOfLength(8);
                String routing = randomBoolean() ? null : randomAlphaOfLength(6);
                int sourceShard = OperationRouting.generateShardId(source, id, routing);
                int targetShard = OperationRouting.generateShardId(target, id, routing);
                List<DeletedDoc> deletes = DeleteOperationRouter.forShard(
                    DeleteRoutingStrategy.SHARD_TOPOLOGY,
                    mode,
                    source,
                    target,
                    sourceShard
                ).route(new TranslogDeleteOp(new Translog.Delete(id, 1, 1)));
                assertTrue(
                    shards[0] + "->" + shards[1] + ": no delete reaches target shard " + targetShard,
                    deletes.stream().anyMatch(d -> OperationRouting.generateShardId(target, id, d.routing()) == targetShard)
                );
            }
        }
    }

    private static IndexMetadata withDefaultRoutingShards(String name, int shards) {
        int routingShards = MetadataCreateIndexService.calculateNumRoutingShards(shards, Version.CURRENT);
        return IndexMetadata.builder(index(name, shards, 0)).setRoutingNumShards(routingShards).build();
    }

    private static IndexMetadata index(String name, int shards, int partitionSize) {
        Settings.Builder settings = Settings.builder()
            .put(IndexMetadata.SETTING_VERSION_CREATED, Version.CURRENT)
            .put(IndexMetadata.SETTING_NUMBER_OF_SHARDS, shards)
            .put(IndexMetadata.SETTING_NUMBER_OF_REPLICAS, 0);
        if (partitionSize > 0) {
            settings.put(IndexMetadata.INDEX_ROUTING_PARTITION_SIZE_SETTING.getKey(), partitionSize);
        }
        return IndexMetadata.builder(name).settings(settings).build();
    }

    public void testShrinkRoutesToTheOneTargetShard() {
        // 8 → 2: target shard t owns source shards 4t..4t+3, so source shard 5 maps to target shard 1.
        var router = new DeleteOperationRouter(
            DeleteRoutingStrategy.SHARD_TOPOLOGY,
            ShardRoutingMode.SHRINK_SHARD,
            8,
            new String[] { "r0", "r1" },
            5
        );
        assertEquals(List.of(new DeletedDoc("doc-1", "r1", 5)), router.route(DELETE));
    }

    public void testSplitFansOutToCandidateShards() {
        var router = new DeleteOperationRouter(
            DeleteRoutingStrategy.SHARD_TOPOLOGY,
            ShardRoutingMode.SPLIT_SHARD,
            2,
            new String[] { "r0", "r1", "r2", "r3", "r4", "r5", "r6", "r7" },
            1
        );

        List<DeletedDoc> requests = router.route(DELETE);

        assertEquals(4, requests.size());
        assertEquals(List.of("r4", "r5", "r6", "r7"), requests.stream().map(DeletedDoc::routing).collect(Collectors.toList()));
    }

    public void testSplitWithoutSyntheticRoutingUsesUnroutedDelete() {
        var router = new DeleteOperationRouter(DeleteRoutingStrategy.SHARD_TOPOLOGY, ShardRoutingMode.SPLIT_SHARD, 2, null, 0);

        List<DeletedDoc> requests = router.route(DELETE);

        assertEquals(1, requests.size());
        assertNull(requests.get(0).routing());
    }

    public void testChooseStrategyUsesTranslogRoutingWhenEveryNodeIs39() {
        assertEquals(
            DeleteRoutingStrategy.TRANSLOG_ROUTING,
            DeleteOperationRouter.chooseStrategy(stateWithMinVersion(Version.fromString("3.9.0")))
        );
    }

    public void testChooseStrategyUsesShardTopologyWhenAnyNodeIsPre39() {
        assertEquals(
            DeleteRoutingStrategy.SHARD_TOPOLOGY,
            DeleteOperationRouter.chooseStrategy(stateWithMinVersion(Version.fromString("3.8.0")))
        );
    }

    public void testChooseStrategyUsesShardTopologyForEmptyCluster() {
        ClusterState state = mock(ClusterState.class);
        DiscoveryNodes nodes = mock(DiscoveryNodes.class);
        when(state.nodes()).thenReturn(nodes);
        assertEquals(DeleteRoutingStrategy.SHARD_TOPOLOGY, DeleteOperationRouter.chooseStrategy(state));
    }

    private static ClusterState stateWithMinVersion(Version version) {
        ClusterState state = mock(ClusterState.class);
        DiscoveryNodes nodes = mock(DiscoveryNodes.class);
        when(state.nodes()).thenReturn(nodes);
        when(nodes.getSize()).thenReturn(1);
        when(nodes.getMinNodeVersion()).thenReturn(version);
        return state;
    }

    public void testBulkApiUsesUnroutedDelete() {
        var router = new DeleteOperationRouter(
            DeleteRoutingStrategy.SHARD_TOPOLOGY,
            ShardRoutingMode.BULK_API,
            2,
            new String[] { "ignored" },
            0
        );

        List<DeletedDoc> requests = router.route(DELETE);

        assertEquals(1, requests.size());
        assertEquals("doc-1", requests.get(0).id());
        assertNull(requests.get(0).routing());
    }
}
