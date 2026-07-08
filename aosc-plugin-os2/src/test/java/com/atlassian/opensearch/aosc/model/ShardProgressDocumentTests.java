/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */
package com.atlassian.opensearch.aosc.model;

import com.atlassian.opensearch.aosc.model.ShardProgressDocument.PhaseMetrics;
import com.atlassian.opensearch.aosc.model.ShardProgressDocument.PhaseStatus;
import com.atlassian.opensearch.aosc.model.phase.ShardPhase;
import com.atlassian.opensearch.aosc.statemachine.TransitionRecord;

import org.opensearch.common.io.stream.BytesStreamOutput;
import org.opensearch.common.xcontent.XContentFactory;
import org.opensearch.common.xcontent.json.JsonXContent;
import org.opensearch.core.common.io.stream.StreamInput;
import org.opensearch.core.xcontent.DeprecationHandler;
import org.opensearch.core.xcontent.ToXContent;
import org.opensearch.core.xcontent.XContentBuilder;
import org.opensearch.core.xcontent.XContentParser;
import org.opensearch.test.OpenSearchTestCase;

import java.io.IOException;
import java.util.List;

public class ShardProgressDocumentTests extends OpenSearchTestCase {

    private PhaseMetrics sampleMetrics(PhaseStatus status) {
        return PhaseMetrics.builder()
            .status(status)
            .startSeqNo(100)
            .targetSeqNo(5000)
            .documentsIndexed(4500)
            .documentsSkipped(50)
            .operationsApplied(3000)
            .operationsSkipped(10)
            .bulkRetries(2)
            .rounds(3)
            .startTimeMillis(1679500000000L)
            .endTimeMillis(1679500060000L)
            .build();
    }

    private ShardProgressDocument sampleProgress() {
        return ShardProgressDocument.builder()
            .phase(ShardPhase.CONVERGING)
            .lastReplayedSeqNo(4999)
            .backfillCutoffSeqNo(3000)
            .backfill(sampleMetrics(PhaseStatus.COMPLETED))
            .replay(sampleMetrics(PhaseStatus.COMPLETED))
            .convergence(sampleMetrics(PhaseStatus.IN_PROGRESS))
            .build();
    }

    private ShardProgressDocument roundTrip(ShardProgressDocument original) throws IOException {
        XContentBuilder builder = XContentFactory.jsonBuilder();
        original.toXContent(builder, ToXContent.EMPTY_PARAMS);
        String json = builder.toString();
        XContentParser parser = JsonXContent.jsonXContent.createParser(
            xContentRegistry(),
            DeprecationHandler.THROW_UNSUPPORTED_OPERATION,
            json
        );
        return ShardProgressDocument.fromXContent(parser);
    }

    // ---- XContent round-trip: full ----
    public void testXContentRoundTrip() throws IOException {
        ShardProgressDocument original = sampleProgress();
        ShardProgressDocument rt = roundTrip(original);

        assertEquals(original.phase(), rt.phase());
        assertEquals(original.lastReplayedSeqNo(), rt.lastReplayedSeqNo());
        assertEquals(original.backfillCutoffSeqNo(), rt.backfillCutoffSeqNo());
        assertNull(rt.error());
        assertNotNull(rt.backfill());
        assertNotNull(rt.replay());
        assertNotNull(rt.convergence());
        assertNull(rt.catchingUp());
        assertEquals(original.backfill(), rt.backfill());
        assertEquals(original.replay(), rt.replay());
        assertEquals(original.convergence(), rt.convergence());
    }

    // ---- Null phase metrics omitted ----
    public void testXContentWithNullPhaseMetrics() throws IOException {
        ShardProgressDocument original = ShardProgressDocument.builder()
            .phase(ShardPhase.PENDING)
            .lastReplayedSeqNo(-1)
            .backfillCutoffSeqNo(-1)
            .build();

        ShardProgressDocument rt = roundTrip(original);

        assertEquals(ShardPhase.PENDING, rt.phase());
        assertNull(rt.backfill());
        assertNull(rt.replay());
        assertNull(rt.convergence());
        assertNull(rt.catchingUp());

        // Verify XContent doesn't contain phase metrics fields (as objects)
        XContentBuilder builder = XContentFactory.jsonBuilder();
        original.toXContent(builder, ToXContent.EMPTY_PARAMS);
        String json = builder.toString();
        assertFalse(json.contains("\"backfill\":{"));
        assertFalse(json.contains("\"replay\":{"));
        assertFalse(json.contains("\"convergence\":{"));
        assertFalse(json.contains("\"catching_up\":{"));
    }

    // ---- PhaseMetrics XContent round-trip ----
    public void testPhaseMetricsXContentRoundTrip() throws IOException {
        PhaseMetrics original = sampleMetrics(PhaseStatus.IN_PROGRESS);

        XContentBuilder builder = XContentFactory.jsonBuilder();
        original.toXContent(builder, ToXContent.EMPTY_PARAMS);
        String json = builder.toString();

        XContentParser parser = JsonXContent.jsonXContent.createParser(
            xContentRegistry(),
            DeprecationHandler.THROW_UNSUPPORTED_OPERATION,
            json
        );
        PhaseMetrics rt = PhaseMetrics.fromXContent(parser);

        assertEquals(original, rt);
    }

    // ---- Builder defaults ----
    public void testBuilderDefaults() {
        ShardProgressDocument sp = ShardProgressDocument.builder().phase(ShardPhase.ACQUIRING_LEASE).build();

        assertEquals(ShardPhase.ACQUIRING_LEASE, sp.phase());
        assertEquals(0L, sp.lastReplayedSeqNo());
        assertEquals(0L, sp.backfillCutoffSeqNo());
        assertNull(sp.error());
        assertNull(sp.backfill());
        assertNull(sp.replay());
        assertNull(sp.convergence());
        assertNull(sp.catchingUp());
    }

    // ---- Equals and hashCode ----
    public void testEqualsAndHashCode() {
        ShardProgressDocument a = sampleProgress();
        ShardProgressDocument b = sampleProgress();
        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());

        ShardProgressDocument c = a.toBuilder().phase(ShardPhase.FAILED).build();
        assertNotEquals(a, c);
    }

    // ---- Writeable round-trip: PhaseMetrics ----
    public void testPhaseMetricsWriteableRoundTrip() throws IOException {
        ShardProgressDocument.PhaseMetrics original = ShardProgressDocument.PhaseMetrics.builder()
            .status(ShardProgressDocument.PhaseStatus.IN_PROGRESS)
            .startSeqNo(100)
            .targetSeqNo(500)
            .documentsIndexed(200)
            .documentsSkipped(10)
            .operationsApplied(150)
            .operationsSkipped(5)
            .bulkRetries(2)
            .rounds(3)
            .currentGap(42000)
            .startTimeMillis(System.currentTimeMillis())
            .endTimeMillis(0)
            .error(null)
            .build();

        // Write
        BytesStreamOutput out = new BytesStreamOutput();
        original.writeTo(out);

        // Read
        StreamInput in = out.bytes().streamInput();
        ShardProgressDocument.PhaseMetrics deserialized = new ShardProgressDocument.PhaseMetrics(in);

        assertEquals(original.status(), deserialized.status());
        assertEquals(original.startSeqNo(), deserialized.startSeqNo());
        assertEquals(original.targetSeqNo(), deserialized.targetSeqNo());
        assertEquals(original.documentsIndexed(), deserialized.documentsIndexed());
        assertEquals(original.documentsSkipped(), deserialized.documentsSkipped());
        assertEquals(original.operationsApplied(), deserialized.operationsApplied());
        assertEquals(original.operationsSkipped(), deserialized.operationsSkipped());
        assertEquals(original.bulkRetries(), deserialized.bulkRetries());
        assertEquals(original.rounds(), deserialized.rounds());
        assertEquals(original.currentGap(), deserialized.currentGap());
        assertEquals(original.startTimeMillis(), deserialized.startTimeMillis());
        assertEquals(original.endTimeMillis(), deserialized.endTimeMillis());
        assertNull(deserialized.error());
    }

    public void testPhaseMetricsWriteableRoundTripWithError() throws IOException {
        ShardProgressDocument.PhaseMetrics original = ShardProgressDocument.PhaseMetrics.builder()
            .status(ShardProgressDocument.PhaseStatus.FAILED)
            .startSeqNo(0)
            .targetSeqNo(100)
            .documentsIndexed(50)
            .documentsSkipped(0)
            .operationsApplied(0)
            .operationsSkipped(0)
            .bulkRetries(0)
            .rounds(0)
            .startTimeMillis(1000L)
            .endTimeMillis(2000L)
            .error("something went wrong")
            .build();

        BytesStreamOutput out = new BytesStreamOutput();
        original.writeTo(out);
        StreamInput in = out.bytes().streamInput();
        ShardProgressDocument.PhaseMetrics deserialized = new ShardProgressDocument.PhaseMetrics(in);

        assertEquals(original, deserialized);
    }

    public void testShardProgressDocumentWriteableRoundTrip() throws IOException {
        ShardProgressDocument original = ShardProgressDocument.builder()
            .phase(ShardPhase.REPLAYING)
            .lastReplayedSeqNo(42)
            .backfillCutoffSeqNo(100)
            .error(null)
            .backfill(
                ShardProgressDocument.PhaseMetrics.builder()
                    .status(ShardProgressDocument.PhaseStatus.COMPLETED)
                    .startSeqNo(0)
                    .targetSeqNo(100)
                    .documentsIndexed(100)
                    .documentsSkipped(0)
                    .operationsApplied(0)
                    .operationsSkipped(0)
                    .bulkRetries(0)
                    .rounds(0)
                    .startTimeMillis(1000)
                    .endTimeMillis(2000)
                    .error(null)
                    .build()
            )
            .replay(
                ShardProgressDocument.PhaseMetrics.builder()
                    .status(ShardProgressDocument.PhaseStatus.IN_PROGRESS)
                    .startSeqNo(100)
                    .targetSeqNo(500)
                    .documentsIndexed(0)
                    .documentsSkipped(0)
                    .operationsApplied(42)
                    .operationsSkipped(3)
                    .bulkRetries(1)
                    .rounds(0)
                    .startTimeMillis(2000)
                    .endTimeMillis(0)
                    .error(null)
                    .build()
            )
            .convergence(null)
            .catchingUp(null)
            .build();

        BytesStreamOutput out = new BytesStreamOutput();
        original.writeTo(out);
        StreamInput in = out.bytes().streamInput();
        ShardProgressDocument deserialized = new ShardProgressDocument(in);

        assertEquals(original, deserialized);
    }

    public void testShardProgressDocumentWriteableRoundTripAllFields() throws IOException {
        // All PhaseMetrics populated
        ShardProgressDocument.PhaseMetrics metrics = ShardProgressDocument.PhaseMetrics.builder()
            .status(ShardProgressDocument.PhaseStatus.COMPLETED)
            .startSeqNo(0)
            .targetSeqNo(100)
            .documentsIndexed(50)
            .documentsSkipped(5)
            .operationsApplied(30)
            .operationsSkipped(2)
            .bulkRetries(1)
            .rounds(2)
            .startTimeMillis(1000)
            .endTimeMillis(2000)
            .error(null)
            .build();

        ShardProgressDocument original = ShardProgressDocument.builder()
            .phase(ShardPhase.COMPLETED)
            .lastReplayedSeqNo(999)
            .backfillCutoffSeqNo(500)
            .error("final error")
            .backfill(metrics)
            .replay(metrics)
            .convergence(metrics)
            .catchingUp(metrics)
            .build();

        BytesStreamOutput out = new BytesStreamOutput();
        original.writeTo(out);
        StreamInput in = out.bytes().streamInput();
        ShardProgressDocument deserialized = new ShardProgressDocument(in);

        assertEquals(original, deserialized);
    }

    // ---- transitionHistory Writeable round-trip ----
    public void testPhaseTimingsWriteableRoundTrip() throws IOException {
        List<TransitionRecord<ShardPhase>> timings = List.of(
            new TransitionRecord<>(ShardPhase.PENDING, 1000L, 1010L),
            new TransitionRecord<>(ShardPhase.ACQUIRING_LEASE, 1010L, 1100L),
            new TransitionRecord<>(ShardPhase.BACKFILLING, 1100L, 5000L)
        );
        ShardProgressDocument original = sampleProgress().toBuilder().transitionHistory(timings).build();

        BytesStreamOutput out = new BytesStreamOutput();
        original.writeTo(out);
        StreamInput in = out.bytes().streamInput();
        ShardProgressDocument rt = new ShardProgressDocument(in);

        assertEquals(3, rt.transitionHistory().size());
        assertEquals(ShardPhase.PENDING, rt.transitionHistory().get(0).phase());
        assertEquals(1000L, rt.transitionHistory().get(0).startTimeMillis());
        assertEquals(1010L, rt.transitionHistory().get(0).endTimeMillis());
        assertEquals(10L, rt.transitionHistory().get(0).durationMillis());
        assertEquals(ShardPhase.ACQUIRING_LEASE, rt.transitionHistory().get(1).phase());
        assertEquals(ShardPhase.BACKFILLING, rt.transitionHistory().get(2).phase());
    }

    // ---- transitionHistory XContent round-trip ----
    public void testPhaseTimingsXContentRoundTrip() throws IOException {
        List<TransitionRecord<ShardPhase>> timings = List.of(
            new TransitionRecord<>(ShardPhase.PENDING, 1000L, 1010L),
            new TransitionRecord<>(ShardPhase.BACKFILLING, 1010L, 5000L)
        );
        ShardProgressDocument original = sampleProgress().toBuilder().transitionHistory(timings).build();
        ShardProgressDocument rt = roundTrip(original);

        assertEquals(2, rt.transitionHistory().size());
        assertEquals(ShardPhase.PENDING, rt.transitionHistory().get(0).phase());
        assertEquals(1000L, rt.transitionHistory().get(0).startTimeMillis());
        assertEquals(1010L, rt.transitionHistory().get(0).endTimeMillis());
        assertEquals(ShardPhase.BACKFILLING, rt.transitionHistory().get(1).phase());
    }

    // ---- transitionHistory empty by default ----
    public void testPhaseTimingsEmptyByDefault() {
        ShardProgressDocument doc = ShardProgressDocument.builder().phase(ShardPhase.PENDING).build();
        assertNotNull(doc.transitionHistory());
        assertTrue(doc.transitionHistory().isEmpty());
    }
}
