/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */
package com.atlassian.opensearch.aosc.model;

import com.atlassian.opensearch.aosc.AoscTestUtil;
import com.atlassian.opensearch.aosc.model.AoscMigrationsClusterState.Entry;
import com.atlassian.opensearch.aosc.model.AoscMigrationsClusterState.ShardMigrationClusterState;
import com.atlassian.opensearch.aosc.model.phase.CoordinatorPhase;
import com.atlassian.opensearch.aosc.model.phase.ShardPhase;
import com.atlassian.opensearch.aosc.model.transform.InlineTransformScript;

import org.opensearch.cluster.ClusterState;
import org.opensearch.cluster.Diff;
import org.opensearch.common.io.stream.BytesStreamOutput;
import org.opensearch.common.xcontent.XContentFactory;
import org.opensearch.core.common.io.stream.NamedWriteableAwareStreamInput;
import org.opensearch.core.common.io.stream.NamedWriteableRegistry;
import org.opensearch.core.common.io.stream.StreamInput;
import org.opensearch.core.xcontent.ToXContent;
import org.opensearch.core.xcontent.XContentBuilder;
import org.opensearch.test.OpenSearchTestCase;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Tests for {@link AoscMigrationsClusterState}, {@link Entry}, and {@link ShardMigrationClusterState}.
 */
public class AoscMigrationsClusterStateTests extends OpenSearchTestCase {

    // ---- Helpers ----

    private ShardMigrationClusterState sampleShard(ShardPhase phase) {
        return ShardMigrationClusterState.builder()
            .phase(phase)
            .lastReplayedSeqNo(-1)
            .backfillCutoffSeqNo(-1)
            .failure(null)
            .meta(MigrationMetadata.EMPTY)
            .build();
    }

    private ShardMigrationClusterState sampleShard(ShardPhase phase, long lastReplayed, long cutoff, String failure) {
        return ShardMigrationClusterState.builder()
            .phase(phase)
            .lastReplayedSeqNo(lastReplayed)
            .backfillCutoffSeqNo(cutoff)
            .failure(failure)
            .meta(MigrationMetadata.EMPTY)
            .build();
    }

    private Entry sampleEntry(String migrationId) {
        return Entry.builder()
            .migrationId(migrationId)
            .sourceIndex("source-index")
            .targetIndex("target-index")
            .alias("my-alias")
            .transformScript(new InlineTransformScript("ctx._source.migrated = true", null))
            .options(AoscTestUtil.defaultMigrationOptions())
            .phase(CoordinatorPhase.ACTIVE)
            .routingMode(ShardRoutingMode.SAME_SHARD)
            .startTimeMillis(System.currentTimeMillis())
            .shards(Map.of(0, sampleShard(ShardPhase.BACKFILLING), 1, sampleShard(ShardPhase.PENDING)))
            .failure(null)
            .meta(MigrationMetadata.EMPTY)
            .build();
    }

    private NamedWriteableRegistry registry() {
        return new NamedWriteableRegistry(
            List.of(
                new NamedWriteableRegistry.Entry(
                    ClusterState.Custom.class,
                    AoscMigrationsClusterState.TYPE,
                    AoscMigrationsClusterState::new
                )
            )
        );
    }

    private AoscMigrationsClusterState roundTrip(AoscMigrationsClusterState original) throws IOException {
        BytesStreamOutput out = new BytesStreamOutput();
        original.writeTo(out);
        StreamInput in = new NamedWriteableAwareStreamInput(out.bytes().streamInput(), registry());
        return new AoscMigrationsClusterState(in);
    }

    // ---- Writeable round-trip: full ----
    public void testWriteableRoundTrip() throws IOException {
        Entry entry = sampleEntry("mig-1");
        AoscMigrationsClusterState original = new AoscMigrationsClusterState(Map.of("mig-1", entry));

        AoscMigrationsClusterState deserialized = roundTrip(original);

        assertEquals(1, deserialized.entries().size());
        Entry rt = deserialized.getEntry("mig-1");
        assertNotNull(rt);
        assertEquals(entry.migrationId(), rt.migrationId());
        assertEquals(entry.sourceIndex(), rt.sourceIndex());
        assertEquals(entry.targetIndex(), rt.targetIndex());
        assertEquals(entry.alias(), rt.alias());
        assertEquals(entry.phase(), rt.phase());
        assertEquals(entry.routingMode(), rt.routingMode());
        assertEquals(entry.startTimeMillis(), rt.startTimeMillis());
        assertEquals(entry.shards().size(), rt.shards().size());
        assertNull(rt.failure());
    }

    // ---- Writeable round-trip: empty ----
    public void testWriteableRoundTripEmpty() throws IOException {
        AoscMigrationsClusterState deserialized = roundTrip(AoscMigrationsClusterState.EMPTY);
        assertEquals(0, deserialized.entries().size());
    }

    // ---- Writeable round-trip: multiple entries ----
    public void testWriteableRoundTripMultipleEntries() throws IOException {
        Entry entry1 = sampleEntry("mig-1");
        Entry entry2 = Entry.builder()
            .migrationId("mig-2")
            .sourceIndex("source-2")
            .targetIndex("target-2")
            .alias("alias-2")
            .transformScript(new InlineTransformScript("ctx._source.version = 2", null))
            .options(AoscTestUtil.defaultMigrationOptions())
            .phase(CoordinatorPhase.CUTTING_OVER)
            .routingMode(ShardRoutingMode.SPLIT_SHARD)
            .startTimeMillis(12345L)
            .shards(Map.of(0, sampleShard(ShardPhase.CATCHING_UP, 500, 100, null), 1, sampleShard(ShardPhase.FAILED, -1, -1, "disk full")))
            .build();
        AoscMigrationsClusterState original = new AoscMigrationsClusterState(Map.of("mig-1", entry1, "mig-2", entry2));
        AoscMigrationsClusterState deserialized = roundTrip(original);

        assertEquals(2, deserialized.entries().size());
        assertNotNull(deserialized.getEntry("mig-1"));
        assertNotNull(deserialized.getEntry("mig-2"));
        assertEquals(ShardRoutingMode.SPLIT_SHARD, deserialized.getEntry("mig-2").routingMode());
        assertEquals("disk full", deserialized.getEntry("mig-2").shards().get(1).failure());
    }

    // ---- NamedDiff: entry-level diffing ----
    public void testNamedDiff() throws IOException {
        Entry entry1 = sampleEntry("mig-1");
        AoscMigrationsClusterState before = new AoscMigrationsClusterState(Map.of("mig-1", entry1));
        Entry entry1Updated = entry1.toBuilder().phase(CoordinatorPhase.CUTTING_OVER).build();
        AoscMigrationsClusterState after = new AoscMigrationsClusterState(Map.of("mig-1", entry1Updated));

        Diff<ClusterState.Custom> diff = after.diff(before);

        BytesStreamOutput out = new BytesStreamOutput();
        diff.writeTo(out);
        StreamInput in = new NamedWriteableAwareStreamInput(out.bytes().streamInput(), registry());

        Diff<ClusterState.Custom> readDiff = AoscMigrationsClusterState.readDiffFrom(in);
        ClusterState.Custom applied = readDiff.apply(before);

        assertNotNull(applied);
        AoscMigrationsClusterState result = (AoscMigrationsClusterState) applied;
        assertEquals(1, result.entries().size());
        assertEquals(CoordinatorPhase.CUTTING_OVER, result.getEntry("mig-1").phase());
    }

    // ---- getEntry ----
    public void testGetEntryByMigrationId() {
        Entry entry1 = sampleEntry("mig-1");
        Entry entry2 = sampleEntry("mig-2");
        AoscMigrationsClusterState mip = new AoscMigrationsClusterState(Map.of("mig-1", entry1, "mig-2", entry2));

        assertNotNull(mip.getEntry("mig-1"));
        assertEquals("mig-1", mip.getEntry("mig-1").migrationId());
        assertNotNull(mip.getEntry("mig-2"));
        assertNull(mip.getEntry("mig-unknown"));
    }

    // ---- withEntry: add new ----
    public void testWithEntryAddsNew() {
        Entry entry1 = sampleEntry("mig-1");
        AoscMigrationsClusterState mip = new AoscMigrationsClusterState(Map.of("mig-1", entry1));
        Entry entry2 = sampleEntry("mig-2");

        AoscMigrationsClusterState updated = mip.withEntry(entry2);
        assertEquals(2, updated.entries().size());
        assertNotNull(updated.getEntry("mig-2"));
    }

    // ---- withEntry: replace existing ----
    public void testWithEntryReplacesExisting() {
        Entry entry1 = sampleEntry("mig-1");
        AoscMigrationsClusterState mip = new AoscMigrationsClusterState(Map.of("mig-1", entry1));
        Entry entry1Updated = entry1.toBuilder().phase(CoordinatorPhase.COMPLETING).build();

        AoscMigrationsClusterState updated = mip.withEntry(entry1Updated);
        assertEquals(1, updated.entries().size());
        assertEquals(CoordinatorPhase.COMPLETING, updated.getEntry("mig-1").phase());
    }

    // ---- withoutEntry ----
    public void testWithoutEntry() {
        Entry entry1 = sampleEntry("mig-1");
        Entry entry2 = sampleEntry("mig-2");
        AoscMigrationsClusterState mip = new AoscMigrationsClusterState(Map.of("mig-1", entry1, "mig-2", entry2));

        AoscMigrationsClusterState updated = mip.withoutEntry("mig-1");
        assertEquals(1, updated.entries().size());
        assertNull(updated.getEntry("mig-1"));
        assertNotNull(updated.getEntry("mig-2"));

        // No-op if not found
        AoscMigrationsClusterState same = mip.withoutEntry("mig-unknown");
        assertEquals(2, same.entries().size());
    }

    // ---- Entry Writeable round-trip ----
    public void testEntryWriteableRoundTrip() throws IOException {
        Entry entry = Entry.builder()
            .migrationId("mig-rt")
            .sourceIndex("src")
            .targetIndex("tgt")
            .alias("my-alias")
            .transformScript(new InlineTransformScript("ctx._source.migrated = true", null))
            .options(AoscTestUtil.defaultMigrationOptions())
            .phase(CoordinatorPhase.INITIALIZING)
            .routingMode(ShardRoutingMode.BULK_API)
            .startTimeMillis(99999L)
            .shards(Map.of(0, sampleShard(ShardPhase.PENDING)))
            .failure("some-failure")
            .build();

        BytesStreamOutput out = new BytesStreamOutput();
        entry.writeTo(out);
        Entry rt = new Entry(out.bytes().streamInput());

        assertEquals(entry.migrationId(), rt.migrationId());
        assertEquals(entry.sourceIndex(), rt.sourceIndex());
        assertEquals(entry.targetIndex(), rt.targetIndex());
        assertEquals(entry.alias(), rt.alias());
        assertEquals(entry.phase(), rt.phase());
        assertEquals(entry.routingMode(), rt.routingMode());
        assertEquals(entry.startTimeMillis(), rt.startTimeMillis());
        assertEquals(entry.shards().size(), rt.shards().size());
        assertEquals("some-failure", rt.failure());
        assertTrue(rt.meta().isEmpty());
    }

    public void testEntryWriteableRoundTripWithMeta() throws IOException {
        MigrationMetadata meta = MigrationMetadata.builder()
            .put(MigrationMetadata.WRITE_BLOCK_APPLIED, true)
            .put(MigrationMetadata.REBALANCE_DISABLED, true)
            .build();
        Entry entry = Entry.builder()
            .migrationId("mig-meta")
            .sourceIndex("src")
            .targetIndex("tgt")
            .alias("my-alias")
            .options(AoscTestUtil.defaultMigrationOptions())
            .phase(CoordinatorPhase.CUTTING_OVER)
            .routingMode(ShardRoutingMode.SAME_SHARD)
            .startTimeMillis(12345L)
            .shards(Map.of(0, sampleShard(ShardPhase.BACKFILLING)))
            .meta(meta)
            .build();

        BytesStreamOutput out = new BytesStreamOutput();
        entry.writeTo(out);
        Entry rt = new Entry(out.bytes().streamInput());

        assertEquals(entry.meta(), rt.meta());
        assertTrue(rt.meta().getBoolean(MigrationMetadata.WRITE_BLOCK_APPLIED));
        assertTrue(rt.meta().getBoolean(MigrationMetadata.REBALANCE_DISABLED));
    }

    // ---- ShardMigrationClusterState Writeable round-trip ----
    public void testShardMigrationClusterStateWriteableRoundTrip() throws IOException {
        ShardMigrationClusterState sms = ShardMigrationClusterState.builder()
            .phase(ShardPhase.CONVERGING)
            .lastReplayedSeqNo(42000)
            .backfillCutoffSeqNo(10000)
            .failure("oops")
            .build();

        BytesStreamOutput out = new BytesStreamOutput();
        sms.writeTo(out);
        ShardMigrationClusterState rt = new ShardMigrationClusterState(out.bytes().streamInput());

        assertEquals(ShardPhase.CONVERGING, rt.phase());
        assertEquals(42000, rt.lastReplayedSeqNo());
        assertEquals(10000, rt.backfillCutoffSeqNo());
        assertEquals("oops", rt.failure());
    }

    // ---- Entry.withPhase ----
    public void testEntryWithPhase() {
        Entry entry = sampleEntry("mig-1");
        Entry updated = entry.toBuilder().phase(CoordinatorPhase.FAILING).build();

        assertEquals(CoordinatorPhase.FAILING, updated.phase());
        assertEquals(entry.migrationId(), updated.migrationId());
        assertEquals(entry.sourceIndex(), updated.sourceIndex());
        assertEquals(entry.shards(), updated.shards());
    }

    public void testEntryWithPhasePreservesMeta() {
        MigrationMetadata meta = MigrationMetadata.builder().put(MigrationMetadata.REBALANCE_DISABLED, true).build();
        Entry entry = sampleEntry("mig-1").toBuilder().meta(meta).build();

        Entry updated = entry.toBuilder().phase(CoordinatorPhase.COMPLETING).build();
        assertEquals(meta, updated.meta());
    }

    public void testEntryWithShardsPreservesMeta() {
        MigrationMetadata meta = MigrationMetadata.builder().put(MigrationMetadata.WRITE_BLOCK_APPLIED, true).build();
        Entry entry = sampleEntry("mig-1").toBuilder().meta(meta).build();

        Entry updated = entry.toBuilder().shards(Map.of(0, sampleShard(ShardPhase.COMPLETED))).build();
        assertEquals(meta, updated.meta());
    }

    public void testEntryWithFailurePreservesMeta() {
        MigrationMetadata meta = MigrationMetadata.builder().put(MigrationMetadata.ALIAS_SWAPPED, true).build();
        Entry entry = sampleEntry("mig-1").toBuilder().meta(meta).build();

        Entry updated = entry.toBuilder().failure("boom").build();
        assertEquals(meta, updated.meta());
        assertEquals("boom", updated.failure());
    }

    public void testEntryWithMeta() {
        Entry entry = sampleEntry("mig-1");
        assertTrue(entry.meta().isEmpty());

        MigrationMetadata meta = MigrationMetadata.builder()
            .put(MigrationMetadata.WRITE_BLOCK_APPLIED, true)
            .put(MigrationMetadata.REBALANCE_DISABLED, true)
            .build();
        Entry updated = entry.toBuilder().meta(meta).build();

        assertEquals(meta, updated.meta());
        assertEquals(entry.phase(), updated.phase());
        assertEquals(entry.migrationId(), updated.migrationId());
    }

    // ---- Entry.withShard ----
    public void testEntryWithShard() {
        Entry entry = sampleEntry("mig-1");
        ShardMigrationClusterState newStatus = sampleShard(ShardPhase.COMPLETED, 99999, 50000, null);

        Map<Integer, AoscMigrationsClusterState.ShardMigrationClusterState> newShards1 = new HashMap<>(entry.shards());
        newShards1.put(2, newStatus);
        Entry updated = entry.toBuilder().shards(newShards1).build();
        assertEquals(3, updated.shards().size());
        assertEquals(ShardPhase.COMPLETED, updated.shards().get(2).phase());

        // Replace existing shard
        Map<Integer, AoscMigrationsClusterState.ShardMigrationClusterState> newShards2 = new HashMap<>(entry.shards());
        newShards2.put(0, newStatus);
        Entry replaced = entry.toBuilder().shards(newShards2).build();
        assertEquals(2, replaced.shards().size());
        assertEquals(ShardPhase.COMPLETED, replaced.shards().get(0).phase());
    }

    // ---- XContent output ----
    public void testXContentRoundTrip() throws IOException {
        Entry entry = sampleEntry("mig-xc");
        AoscMigrationsClusterState mip = new AoscMigrationsClusterState(Map.of("mig-xc", entry));

        XContentBuilder builder = XContentFactory.jsonBuilder();
        builder.startObject();
        mip.toXContent(builder, ToXContent.EMPTY_PARAMS);
        builder.endObject();

        String json = builder.toString();
        assertTrue(json.contains("\"migration_id\":\"mig-xc\""));
        assertTrue(json.contains("\"source_index\":\"source-index\""));
        assertTrue(json.contains("\"target_index\":\"target-index\""));
        assertTrue(json.contains("\"alias\":\"my-alias\""));
        assertTrue(json.contains("\"phase\":\"ACTIVE\""));
        assertTrue(json.contains("\"routing_mode\":\"SAME_SHARD\""));
        assertTrue(json.contains("\"shards\""));
    }

    // ---- getWriteableName ----
    public void testGetWriteableName() {
        assertEquals("aosc_migrations", AoscMigrationsClusterState.EMPTY.getWriteableName());
    }

    // ---- equals and hashCode ----
    public void testEqualsAndHashCode() {
        Entry entry = sampleEntry("mig-eq");
        AoscMigrationsClusterState a = new AoscMigrationsClusterState(Map.of("mig-eq", entry));
        AoscMigrationsClusterState b = new AoscMigrationsClusterState(Map.of("mig-eq", entry));
        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());

        AoscMigrationsClusterState c = new AoscMigrationsClusterState(Map.of("mig-different", sampleEntry("mig-different")));
        assertNotEquals(a, c);
    }
}
