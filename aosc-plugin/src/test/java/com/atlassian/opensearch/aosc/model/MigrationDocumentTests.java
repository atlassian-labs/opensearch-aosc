/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */
package com.atlassian.opensearch.aosc.model;

import com.atlassian.opensearch.aosc.model.phase.CoordinatorPhase;
import com.atlassian.opensearch.aosc.model.phase.ShardPhase;
import com.atlassian.opensearch.aosc.model.transform.InlineTransformScript;
import com.atlassian.opensearch.aosc.statemachine.TransitionRecord;

import org.opensearch.common.io.stream.BytesStreamOutput;
import org.opensearch.common.xcontent.XContentFactory;
import org.opensearch.common.xcontent.json.JsonXContent;
import org.opensearch.core.xcontent.DeprecationHandler;
import org.opensearch.core.xcontent.ToXContent;
import org.opensearch.core.xcontent.XContentBuilder;
import org.opensearch.core.xcontent.XContentParser;
import org.opensearch.test.OpenSearchTestCase;

import java.io.IOException;
import java.util.List;
import java.util.Map;

public class MigrationDocumentTests extends OpenSearchTestCase {

    private MigrationDocument sampleMetadata() {
        return MigrationDocument.builder()
            .migrationId("mig-1")
            .sourceIndex("source")
            .targetIndex("target")
            .phase(CoordinatorPhase.ACTIVE)
            .alias("my-alias")
            .transformScript(new InlineTransformScript("ctx.remove('field')", null))
            .options(
                new MigrationRequestOptions()

                    .setConvergenceThresholdPerShard(1000)
                    .setMaxConvergenceRoundsPerShard(0)
                    .setDocCountTolerance(0)
                    .setAcceptDataLossIfCustomRoutingIsUsed(false)
            )
            .shardRoutingMode(ShardRoutingMode.SAME_SHARD)
            .startTimeMillis(1679500000000L)
            .lastUpdatedMillis(1679500060000L)
            .build();
    }

    // ---- Writeable round-trip: full ----
    public void testWriteableRoundTrip() throws IOException {
        MigrationDocument original = sampleMetadata();

        BytesStreamOutput out = new BytesStreamOutput();
        original.writeTo(out);
        MigrationDocument rt = new MigrationDocument(out.bytes().streamInput());

        assertEquals(original.migrationId(), rt.migrationId());
        assertEquals(original.sourceIndex(), rt.sourceIndex());
        assertEquals(original.targetIndex(), rt.targetIndex());
        assertEquals(original.alias(), rt.alias());
        assertEquals(original.phase(), rt.phase());
        assertEquals(original.transformScript(), rt.transformScript());
        assertNotNull(rt.options());
        assertEquals(original.shardRoutingMode(), rt.shardRoutingMode());
        assertEquals(original.startTimeMillis(), rt.startTimeMillis());
        assertEquals(original.lastUpdatedMillis(), rt.lastUpdatedMillis());
        assertNull(rt.errorMessage());
    }

    // ---- Writeable round-trip: minimal ----
    public void testWriteableRoundTripMinimal() throws IOException {
        MigrationDocument original = MigrationDocument.builder()
            .migrationId("mig-min")
            .sourceIndex("src")
            .targetIndex("tgt")
            .phase(CoordinatorPhase.INITIALIZING)
            .build();

        BytesStreamOutput out = new BytesStreamOutput();
        original.writeTo(out);
        MigrationDocument rt = new MigrationDocument(out.bytes().streamInput());

        assertEquals("mig-min", rt.migrationId());
        assertEquals(CoordinatorPhase.INITIALIZING, rt.phase());
        assertNull(rt.transformScript());
        assertNull(rt.alias());
        assertNotNull(rt.options());
        assertNull(rt.errorMessage());
        assertNull(rt.shardRoutingMode());
    }

    // ---- XContent round-trip ----
    public void testXContentRoundTrip() throws IOException {
        MigrationDocument original = sampleMetadata();

        XContentBuilder builder = XContentFactory.jsonBuilder();
        original.toXContent(builder, ToXContent.EMPTY_PARAMS);
        String json = builder.toString();

        XContentParser parser = JsonXContent.jsonXContent.createParser(
            xContentRegistry(),
            DeprecationHandler.THROW_UNSUPPORTED_OPERATION,
            json
        );
        MigrationDocument rt = MigrationDocument.fromXContent(parser);

        assertEquals(original.migrationId(), rt.migrationId());
        assertEquals(original.sourceIndex(), rt.sourceIndex());
        assertEquals(original.targetIndex(), rt.targetIndex());
        assertEquals(original.phase(), rt.phase());
        assertEquals(original.shardRoutingMode(), rt.shardRoutingMode());
    }

    // ---- withPhase ----
    public void testWithPhase() {
        MigrationDocument original = sampleMetadata();
        MigrationDocument updated = original.withPhase(CoordinatorPhase.CUTTING_OVER);

        assertEquals(CoordinatorPhase.CUTTING_OVER, updated.phase());
        assertEquals(original.migrationId(), updated.migrationId());
        assertEquals(original.sourceIndex(), updated.sourceIndex());
        assertTrue(updated.lastUpdatedMillis() >= original.lastUpdatedMillis());
    }

    // ---- toBuilder copy ----
    public void testToBuilder() {
        MigrationDocument original = sampleMetadata();
        MigrationDocument copy = original.toBuilder().build();

        assertEquals(original, copy);
    }

    // ---- Shards round-trip: Writeable ----
    public void testWriteableRoundTripWithShards() throws IOException {
        ShardProgressDocument shard0 = ShardProgressDocument.builder()
            .phase(ShardPhase.COMPLETED)
            .lastReplayedSeqNo(100L)
            .backfillCutoffSeqNo(50L)
            .backfill(
                ShardProgressDocument.PhaseMetrics.builder()
                    .status(ShardProgressDocument.PhaseStatus.COMPLETED)
                    .startSeqNo(0)
                    .targetSeqNo(50)
                    .documentsIndexed(50)
                    .startTimeMillis(1000L)
                    .endTimeMillis(2000L)
                    .build()
            )
            .build();
        ShardProgressDocument shard1 = ShardProgressDocument.builder()
            .phase(ShardPhase.FAILED)
            .lastReplayedSeqNo(42L)
            .backfillCutoffSeqNo(30L)
            .error("test error")
            .build();

        MigrationDocument original = sampleMetadata().toBuilder().shards(Map.of(0, shard0, 1, shard1)).build();

        BytesStreamOutput out = new BytesStreamOutput();
        original.writeTo(out);
        MigrationDocument rt = new MigrationDocument(out.bytes().streamInput());

        assertEquals(2, rt.shards().size());
        assertEquals(ShardPhase.COMPLETED, rt.shards().get(0).phase());
        assertEquals(100L, rt.shards().get(0).lastReplayedSeqNo());
        assertNotNull(rt.shards().get(0).backfill());
        assertEquals(50L, rt.shards().get(0).backfill().documentsIndexed());
        assertEquals(ShardPhase.FAILED, rt.shards().get(1).phase());
        assertEquals("test error", rt.shards().get(1).error());
    }

    // ---- Shards round-trip: XContent ----
    public void testXContentRoundTripWithShards() throws IOException {
        ShardProgressDocument shard0 = ShardProgressDocument.builder()
            .phase(ShardPhase.BACKFILLING)
            .lastReplayedSeqNo(42L)
            .backfillCutoffSeqNo(100L)
            .build();

        MigrationDocument original = sampleMetadata().toBuilder().shards(Map.of(0, shard0)).build();

        XContentBuilder builder = XContentFactory.jsonBuilder();
        original.toXContent(builder, ToXContent.EMPTY_PARAMS);
        String json = builder.toString();

        XContentParser parser = JsonXContent.jsonXContent.createParser(
            xContentRegistry(),
            DeprecationHandler.THROW_UNSUPPORTED_OPERATION,
            json
        );
        MigrationDocument rt = MigrationDocument.fromXContent(parser);

        assertEquals(1, rt.shards().size());
        assertEquals(ShardPhase.BACKFILLING, rt.shards().get(0).phase());
        assertEquals(42L, rt.shards().get(0).lastReplayedSeqNo());
    }

    // ---- Empty shards map ----
    public void testEmptyShardsDefault() {
        MigrationDocument doc = sampleMetadata();
        assertNotNull(doc.shards());
        assertTrue(doc.shards().isEmpty());
    }

    // ---- transitionHistory Writeable round-trip ----
    public void testPhaseTimingsWriteableRoundTrip() throws IOException {
        List<TransitionRecord<CoordinatorPhase>> timings = List.of(
            new TransitionRecord<>(CoordinatorPhase.INITIALIZING, 1000L, 1050L),
            new TransitionRecord<>(CoordinatorPhase.ACTIVE, 1050L, 5000L),
            new TransitionRecord<>(CoordinatorPhase.COMPLETED, 5000L, 5010L)
        );
        MigrationDocument original = sampleMetadata().toBuilder().transitionHistory(timings).build();

        BytesStreamOutput out = new BytesStreamOutput();
        original.writeTo(out);
        MigrationDocument rt = new MigrationDocument(out.bytes().streamInput());

        assertEquals(3, rt.transitionHistory().size());
        assertEquals(CoordinatorPhase.INITIALIZING, rt.transitionHistory().get(0).phase());
        assertEquals(1000L, rt.transitionHistory().get(0).startTimeMillis());
        assertEquals(1050L, rt.transitionHistory().get(0).endTimeMillis());
        assertEquals(50L, rt.transitionHistory().get(0).durationMillis());
        assertEquals(CoordinatorPhase.ACTIVE, rt.transitionHistory().get(1).phase());
        assertEquals(CoordinatorPhase.COMPLETED, rt.transitionHistory().get(2).phase());
    }

    // ---- transitionHistory XContent round-trip ----
    public void testPhaseTimingsXContentRoundTrip() throws IOException {
        List<TransitionRecord<CoordinatorPhase>> timings = List.of(
            new TransitionRecord<>(CoordinatorPhase.INITIALIZING, 1000L, 1050L),
            new TransitionRecord<>(CoordinatorPhase.ACTIVE, 1050L, 5000L)
        );
        MigrationDocument original = sampleMetadata().toBuilder().transitionHistory(timings).build();

        XContentBuilder builder = XContentFactory.jsonBuilder();
        original.toXContent(builder, ToXContent.EMPTY_PARAMS);
        String json = builder.toString();

        XContentParser parser = JsonXContent.jsonXContent.createParser(
            xContentRegistry(),
            DeprecationHandler.THROW_UNSUPPORTED_OPERATION,
            json
        );
        MigrationDocument rt = MigrationDocument.fromXContent(parser);

        assertEquals(2, rt.transitionHistory().size());
        assertEquals(CoordinatorPhase.INITIALIZING, rt.transitionHistory().get(0).phase());
        assertEquals(1000L, rt.transitionHistory().get(0).startTimeMillis());
        assertEquals(1050L, rt.transitionHistory().get(0).endTimeMillis());
        assertEquals(CoordinatorPhase.ACTIVE, rt.transitionHistory().get(1).phase());
    }

    // ---- transitionHistory empty by default ----
    public void testPhaseTimingsEmptyByDefault() {
        MigrationDocument doc = sampleMetadata();
        assertNotNull(doc.transitionHistory());
        assertTrue(doc.transitionHistory().isEmpty());
    }

    // ---- Full round-trip: shards + transitionHistory + cutoverContext + error ----
    public void testWriteableRoundTripFull() throws IOException {
        ShardProgressDocument shard0 = ShardProgressDocument.builder()
            .phase(ShardPhase.COMPLETED)
            .lastReplayedSeqNo(200L)
            .backfillCutoffSeqNo(100L)
            .backfill(
                ShardProgressDocument.PhaseMetrics.builder()
                    .status(ShardProgressDocument.PhaseStatus.COMPLETED)
                    .startSeqNo(0)
                    .targetSeqNo(100)
                    .documentsIndexed(100)
                    .startTimeMillis(1000L)
                    .endTimeMillis(2000L)
                    .build()
            )
            .transitionHistory(
                List.of(
                    new TransitionRecord<>(ShardPhase.PENDING, 900L, 910L),
                    new TransitionRecord<>(ShardPhase.BACKFILLING, 910L, 2000L),
                    new TransitionRecord<>(ShardPhase.COMPLETED, 2000L, 2005L)
                )
            )
            .build();

        List<TransitionRecord<CoordinatorPhase>> coordTimings = List.of(
            new TransitionRecord<>(CoordinatorPhase.INITIALIZING, 800L, 900L),
            new TransitionRecord<>(CoordinatorPhase.ACTIVE, 900L, 3000L),
            new TransitionRecord<>(CoordinatorPhase.CUTTING_OVER, 3000L, 3200L),
            new TransitionRecord<>(CoordinatorPhase.COMPLETED, 3200L, 3210L)
        );

        CutoverContext cutover = CutoverContext.builder()
            .sourceDocCount(100)
            .targetDocCount(100)
            .docCountValidationPassed(true)
            .aliasSwapSucceeded(true)
            .cutoverStartMillis(3000L)
            .cutoverEndMillis(3200L)
            .build();

        MigrationDocument original = sampleMetadata().toBuilder()
            .phase(CoordinatorPhase.COMPLETED)
            .shards(Map.of(0, shard0))
            .transitionHistory(coordTimings)
            .cutoverContext(cutover)
            .errorMessage("some error")
            .build();

        BytesStreamOutput out = new BytesStreamOutput();
        original.writeTo(out);
        MigrationDocument rt = new MigrationDocument(out.bytes().streamInput());

        assertEquals(original, rt);
        assertEquals(4, rt.transitionHistory().size());
        assertEquals(1, rt.shards().size());
        assertEquals(3, rt.shards().get(0).transitionHistory().size());
        assertNotNull(rt.cutoverContext());
        assertTrue(rt.cutoverContext().docCountValidationPassed());
        assertTrue(rt.cutoverContext().aliasSwapSucceeded());
        assertEquals("some error", rt.errorMessage());
    }

    // ---- XContent round-trip: shards + transitionHistory + error ----
    public void testXContentRoundTripFull() throws IOException {
        ShardProgressDocument shard0 = ShardProgressDocument.builder()
            .phase(ShardPhase.BACKFILLING)
            .lastReplayedSeqNo(50L)
            .backfillCutoffSeqNo(200L)
            .transitionHistory(
                List.of(
                    new TransitionRecord<>(ShardPhase.PENDING, 100L, 110L),
                    new TransitionRecord<>(ShardPhase.ACQUIRING_LEASE, 110L, 200L)
                )
            )
            .build();

        List<TransitionRecord<CoordinatorPhase>> coordTimings = List.of(
            new TransitionRecord<>(CoordinatorPhase.INITIALIZING, 50L, 100L),
            new TransitionRecord<>(CoordinatorPhase.ACTIVE, 100L, 5000L)
        );

        MigrationDocument original = sampleMetadata().toBuilder()
            .shards(Map.of(0, shard0))
            .transitionHistory(coordTimings)
            .errorMessage("test error msg")
            .build();

        XContentBuilder builder = XContentFactory.jsonBuilder();
        original.toXContent(builder, ToXContent.EMPTY_PARAMS);
        String json = builder.toString();

        XContentParser parser = JsonXContent.jsonXContent.createParser(
            xContentRegistry(),
            DeprecationHandler.THROW_UNSUPPORTED_OPERATION,
            json
        );
        MigrationDocument rt = MigrationDocument.fromXContent(parser);

        assertEquals(2, rt.transitionHistory().size());
        assertEquals(CoordinatorPhase.INITIALIZING, rt.transitionHistory().get(0).phase());
        assertEquals(1, rt.shards().size());
        assertEquals(2, rt.shards().get(0).transitionHistory().size());
        assertEquals(ShardPhase.PENDING, rt.shards().get(0).transitionHistory().get(0).phase());
        assertEquals("test error msg", rt.errorMessage());
    }

    // ---- withPhase preserves transitionHistory ----
    public void testWithPhasePreservesPhaseTimings() {
        List<TransitionRecord<CoordinatorPhase>> timings = List.of(new TransitionRecord<>(CoordinatorPhase.INITIALIZING, 100L, 200L));
        MigrationDocument original = sampleMetadata().toBuilder().transitionHistory(timings).build();
        MigrationDocument updated = original.withPhase(CoordinatorPhase.CUTTING_OVER);

        assertEquals(CoordinatorPhase.CUTTING_OVER, updated.phase());
        assertEquals(1, updated.transitionHistory().size());
        assertEquals(CoordinatorPhase.INITIALIZING, updated.transitionHistory().get(0).phase());
    }

    // ---- Minimal doc Writeable round-trip has empty transitionHistory ----
    public void testMinimalWriteableRoundTripHasEmptyPhaseTimings() throws IOException {
        MigrationDocument original = MigrationDocument.builder()
            .migrationId("mig-min")
            .sourceIndex("src")
            .targetIndex("tgt")
            .phase(CoordinatorPhase.INITIALIZING)
            .build();

        BytesStreamOutput out = new BytesStreamOutput();
        original.writeTo(out);
        MigrationDocument rt = new MigrationDocument(out.bytes().streamInput());

        assertNotNull(rt.transitionHistory());
        assertTrue(rt.transitionHistory().isEmpty());
        assertNotNull(rt.shards());
        assertTrue(rt.shards().isEmpty());
    }
}
