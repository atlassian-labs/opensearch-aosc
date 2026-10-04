/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */
package com.atlassian.opensearch.aosc.action.start.validation;

import com.atlassian.opensearch.aosc.model.DeleteRoutingStrategy;
import com.atlassian.opensearch.aosc.model.MigrationRequest;
import com.atlassian.opensearch.aosc.transform.TransformFactory;
import com.atlassian.opensearch.aosc.utils.AsyncClientHelper;

import org.opensearch.Version;
import org.opensearch.cluster.ClusterState;
import org.opensearch.cluster.metadata.IndexMetadata;
import org.opensearch.cluster.metadata.Metadata;
import org.opensearch.cluster.routing.IndexRoutingTable;
import org.opensearch.cluster.routing.IndexShardRoutingTable;
import org.opensearch.cluster.routing.RoutingTable;
import org.opensearch.cluster.routing.ShardRoutingState;
import org.opensearch.cluster.routing.TestShardRouting;
import org.opensearch.common.settings.ClusterSettings;
import org.opensearch.common.settings.Settings;
import org.opensearch.core.index.Index;
import org.opensearch.core.index.shard.ShardId;
import org.opensearch.test.OpenSearchTestCase;

import java.util.Collections;
import java.util.List;

import static org.mockito.Mockito.mock;

/**
 * Unit tests for {@link IndexPreconditionsValidator}: delegates to the
 * production {@code validatePreconditions} helper and wraps errors in an
 * {@link IllegalStateException}.
 */
public class IndexPreconditionsValidatorTests extends OpenSearchTestCase {

    private static IndexMetadata buildMeta(String name) {
        return buildMeta(name, 1, null);
    }

    private static IndexMetadata buildMeta(String name, int shards, Integer routingNumShards) {
        IndexMetadata.Builder builder = IndexMetadata.builder(name)
            .settings(
                Settings.builder()
                    .put(IndexMetadata.SETTING_VERSION_CREATED, Version.CURRENT)
                    .put(IndexMetadata.SETTING_NUMBER_OF_SHARDS, shards)
                    .put(IndexMetadata.SETTING_NUMBER_OF_REPLICAS, 0)
            );
        if (routingNumShards != null) {
            builder.setRoutingNumShards(routingNumShards);
        }
        return builder.build();
    }

    private static IndexRoutingTable activeRouting(IndexMetadata meta) {
        Index index = meta.getIndex();
        IndexRoutingTable.Builder b = IndexRoutingTable.builder(index);
        for (int i = 0; i < meta.getNumberOfShards(); i++) {
            ShardId shardId = new ShardId(index, i);
            b.addIndexShard(
                new IndexShardRoutingTable.Builder(shardId).addShard(
                    TestShardRouting.newShardRouting(shardId, "n1", true, ShardRoutingState.STARTED)
                ).build()
            );
        }
        return b.build();
    }

    private static IndexRoutingTable unassignedRouting(IndexMetadata meta) {
        Index index = meta.getIndex();
        IndexRoutingTable.Builder b = IndexRoutingTable.builder(index);
        for (int i = 0; i < meta.getNumberOfShards(); i++) {
            ShardId shardId = new ShardId(index, i);
            b.addIndexShard(
                new IndexShardRoutingTable.Builder(shardId).addShard(
                    TestShardRouting.newShardRouting(shardId, null, true, ShardRoutingState.UNASSIGNED)
                ).build()
            );
        }
        return b.build();
    }

    private static ValidationContext ctx(ClusterState state, IndexMetadata src, IndexMetadata tgt, String alias) {
        MigrationRequest req = new MigrationRequest().setSourceIndex(src.getIndex().getName())
            .setTargetIndex(tgt.getIndex().getName())
            .setAlias(alias);
        return ValidationContext.of(
            req,
            state,
            src,
            tgt,
            new TransformFactory(null),
            new ClusterSettings(Settings.EMPTY, Collections.emptySet()),
            mock(AsyncClientHelper.class)
        );
    }

    private static ClusterState healthyState(IndexMetadata src, IndexMetadata tgt) {
        return ClusterState.builder(ClusterState.EMPTY_STATE)
            .metadata(Metadata.builder().put(src, false).put(tgt, false).build())
            .routingTable(RoutingTable.builder().add(activeRouting(src)).add(activeRouting(tgt)).build())
            .build();
    }

    public void testAllHealthyPasses() {
        IndexMetadata src = buildMeta("src");
        IndexMetadata tgt = buildMeta("tgt");
        ClusterState state = healthyState(src, tgt);
        new IndexPreconditionsValidator().validate(ctx(state, src, tgt, "my-alias"));
    }

    public void testUnreadyPrimaryFailsAsIllegalState() {
        IndexMetadata src = buildMeta("src");
        IndexMetadata tgt = buildMeta("tgt");
        ClusterState state = ClusterState.builder(ClusterState.EMPTY_STATE)
            .metadata(Metadata.builder().put(src, false).put(tgt, false).build())
            .routingTable(RoutingTable.builder().add(unassignedRouting(src)).add(activeRouting(tgt)).build())
            .build();
        IllegalStateException ex = expectThrows(
            IllegalStateException.class,
            () -> new IndexPreconditionsValidator().validate(ctx(state, src, tgt, "my-alias"))
        );
        assertTrue(ex.getMessage(), ex.getMessage().startsWith("Migration precondition check failed"));
        assertTrue(ex.getMessage(), ex.getMessage().contains("source index [src] has unready primaries"));
    }

    public void testRejectsSplitShardRoutingNumShardsMismatch() {
        IndexMetadata src = buildMeta("src", 3, 3);
        IndexMetadata tgt = buildMeta("tgt", 12, 12);
        List<String> errors = IndexPreconditionsValidator.validatePreconditions(
            healthyState(src, tgt),
            src,
            tgt,
            "my-alias",
            DeleteRoutingStrategy.SHARD_TOPOLOGY
        );
        assertEquals(1, errors.size());
        assertTrue(errors.get(0), errors.get(0).contains("different [index.number_of_routing_shards]"));
        assertTrue(errors.get(0), errors.get(0).contains("source=3, target=12"));
        assertTrue(errors.get(0), errors.get(0).contains("recreate the target index"));
    }

    public void testAllowsSplitShardRoutingNumShardsMatch() {
        IndexMetadata src = buildMeta("src", 3, 12);
        IndexMetadata tgt = buildMeta("tgt", 12, 12);
        List<String> errors = IndexPreconditionsValidator.validatePreconditions(
            healthyState(src, tgt),
            src,
            tgt,
            "my-alias",
            DeleteRoutingStrategy.SHARD_TOPOLOGY
        );
        assertTrue("Expected compatible split routing metadata to pass, got: " + errors, errors.isEmpty());
    }

    public void testTranslogRoutingSkipsSplitRoutingNumShardsCheck() {
        IndexMetadata src = buildMeta("src", 3, 3);
        IndexMetadata tgt = buildMeta("tgt", 12, 12);
        List<String> errors = IndexPreconditionsValidator.validatePreconditions(
            healthyState(src, tgt),
            src,
            tgt,
            "my-alias",
            DeleteRoutingStrategy.TRANSLOG_ROUTING
        );
        assertTrue("Translog routing should not require a shared routing hash space: " + errors, errors.isEmpty());
    }

    public void testRejectsPartitionedSourceUnderShardTopology() {
        IndexMetadata src = partitionedMeta("src", 4, 2);
        IndexMetadata tgt = buildMeta("tgt", 4, null);
        List<String> errors = validateTopology(src, tgt);
        assertTrue(errors.toString(), errors.stream().anyMatch(e -> e.contains("source index [src] is routing-partitioned")));
    }

    public void testRejectsPartitionedTargetUnderShardTopologyWithoutCrashing() {
        IndexMetadata src = buildMeta("src", 4, null);
        IndexMetadata tgt = partitionedMeta("tgt", 4, 2);
        List<String> errors = validateTopology(src, tgt);
        assertEquals(errors.toString(), 1, errors.size());
        assertTrue(errors.get(0), errors.get(0).contains("target index [tgt] is routing-partitioned"));
    }

    public void testPartitionedTargetIsReportedOnceInBulkApiTopology() {
        // Partitioned indices require routing; the routing-required BULK_API check must not repeat the rejection.
        List<String> errors = validateTopology(buildMeta("src", 2, null), partitionedMeta("tgt", 3, 2));
        assertEquals(errors.toString(), 1, errors.size());
        assertTrue(errors.get(0), errors.get(0).contains("target index [tgt] is routing-partitioned"));
    }

    public void testAllowsPartitionedIndicesUnderTranslogRouting() {
        IndexMetadata src = partitionedMeta("src", 4, 2);
        IndexMetadata tgt = partitionedMeta("tgt", 4, 2);
        List<String> errors = IndexPreconditionsValidator.validatePreconditions(
            healthyState(src, tgt),
            src,
            tgt,
            "my-alias",
            DeleteRoutingStrategy.TRANSLOG_ROUTING
        );
        assertTrue(errors.toString(), errors.isEmpty());
    }

    public void testRejectsBulkApiWithRoutingRequiredTarget() {
        IndexMetadata src = buildMeta("src", 2, null);
        IndexMetadata tgt = routingRequiredMeta("tgt", 3);
        List<String> errors = validateTopology(src, tgt);
        assertEquals(errors.toString(), 1, errors.size());
        assertTrue(errors.get(0), errors.get(0).contains("requires routing (_routing.required=true)"));
    }

    public void testAllowsSameShardWithRoutingRequiredTarget() {
        IndexMetadata src = buildMeta("src", 3, null);
        IndexMetadata tgt = routingRequiredMeta("tgt", 3);
        assertTrue(validateTopology(src, tgt).isEmpty());
    }

    public void testRejectsSameShardRoutingNumShardsMismatch() {
        List<String> errors = validateTopology(buildMeta("src", 2, 768), buildMeta("tgt", 2, 1024));
        assertEquals(1, errors.size());
        assertTrue(errors.get(0), errors.get(0).contains("source=768, target=1024"));
    }

    public void testRejectsShrinkShardRoutingNumShardsMismatch() {
        List<String> errors = validateTopology(buildMeta("src", 4, 1024), buildMeta("tgt", 2, 2));
        assertEquals(1, errors.size());
        assertTrue(errors.get(0), errors.get(0).contains("different [index.number_of_routing_shards]"));
    }

    public void testAllowsShrinkShardRoutingNumShardsMatch() {
        assertTrue(validateTopology(buildMeta("src", 4, 1024), buildMeta("tgt", 2, 1024)).isEmpty());
    }

    public void testAllowsShrinkToOneShardWithDifferentRoutingNumShards() {
        // Every source shard maps to the one target shard, so the hash space doesn't matter.
        assertTrue(validateTopology(buildMeta("src", 4, 1024), buildMeta("tgt", 1, 1)).isEmpty());
    }

    public void testAllowsBulkApiWithoutSyntheticRoutingComputation() {
        // 2 -> 3 is BULK_API: synthetic routings are not used, so they must not be computed.
        IndexMetadata src = buildMeta("src", 2, null);
        IndexMetadata tgt = buildMeta("tgt", 3, null);
        assertTrue(validateTopology(src, tgt).isEmpty());
    }

    private static List<String> validateTopology(IndexMetadata src, IndexMetadata tgt) {
        return IndexPreconditionsValidator.validatePreconditions(
            healthyState(src, tgt),
            src,
            tgt,
            "my-alias",
            DeleteRoutingStrategy.SHARD_TOPOLOGY
        );
    }

    private static IndexMetadata partitionedMeta(String name, int shards, int partitionSize) {
        try {
            return IndexMetadata.builder(name)
                .settings(
                    Settings.builder()
                        .put(IndexMetadata.SETTING_VERSION_CREATED, Version.CURRENT)
                        .put(IndexMetadata.SETTING_NUMBER_OF_SHARDS, shards)
                        .put(IndexMetadata.SETTING_NUMBER_OF_REPLICAS, 0)
                        .put(IndexMetadata.INDEX_ROUTING_PARTITION_SIZE_SETTING.getKey(), partitionSize)
                )
                .putMapping("{\"_routing\":{\"required\":true}}")
                .build();
        } catch (java.io.IOException e) {
            throw new AssertionError(e);
        }
    }

    private static IndexMetadata routingRequiredMeta(String name, int shards) {
        try {
            return IndexMetadata.builder(buildMeta(name, shards, null)).putMapping("{\"_routing\":{\"required\":true}}").build();
        } catch (java.io.IOException e) {
            throw new AssertionError(e);
        }
    }

    public void testAllowsSingleSourceShardSplitWithDifferentRoutingNumShards() {
        IndexMetadata src = buildMeta("src", 1, 1);
        IndexMetadata tgt = buildMeta("tgt", 4, 4);
        List<String> errors = IndexPreconditionsValidator.validatePreconditions(
            healthyState(src, tgt),
            src,
            tgt,
            "my-alias",
            DeleteRoutingStrategy.SHARD_TOPOLOGY
        );
        assertTrue("Single source shard fan-out should not require matching routing shard space, got: " + errors, errors.isEmpty());
    }
}
