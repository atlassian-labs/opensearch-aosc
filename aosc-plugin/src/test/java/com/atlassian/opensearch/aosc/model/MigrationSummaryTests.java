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

import org.opensearch.test.OpenSearchTestCase;

import java.util.HashMap;
import java.util.Map;

/**
 * Unit tests for {@link MigrationSummary} — the slim list-API projection of
 * {@link MigrationDocument}. These pin down the field-projection contract,
 * derived-field math, and the cutover-presence rule that the list API depends on.
 */
public class MigrationSummaryTests extends OpenSearchTestCase {

    public void testNullDocReturnsNull() {
        assertNull(MigrationSummary.from(null));
    }

    public void testFlatFieldsAreCopied() {
        MigrationDocument doc = MigrationDocument.builder()
            .migrationId("mig-1")
            .sourceIndex("src-1")
            .targetIndex("tgt-1")
            .alias("alias-1")
            .phase(CoordinatorPhase.ACTIVE)
            .startTimeMillis(1_000L)
            .lastUpdatedMillis(4_000L)
            .errorMessage(null)
            .build();

        MigrationSummary s = MigrationSummary.from(doc);

        assertEquals("mig-1", s.migrationId());
        assertEquals("src-1", s.sourceIndex());
        assertEquals("tgt-1", s.targetIndex());
        assertEquals("alias-1", s.alias());
        assertEquals(CoordinatorPhase.ACTIVE, s.phase());
        assertEquals(1_000L, s.startTimeMillis());
        assertEquals(4_000L, s.lastUpdatedMillis());
        assertNull(s.errorMessage());
    }

    public void testIsTerminalDerivedFromPhase() {
        for (CoordinatorPhase p : CoordinatorPhase.values()) {
            MigrationDocument doc = MigrationDocument.builder().migrationId("m").phase(p).build();
            MigrationSummary s = MigrationSummary.from(doc);
            assertEquals("phase " + p + " terminal mismatch", p.isTerminal(), s.isTerminal());
        }
    }

    public void testElapsedSecondsNormalCase() {
        MigrationDocument doc = MigrationDocument.builder().startTimeMillis(10_000L).lastUpdatedMillis(15_500L).build();
        assertEquals(5L, MigrationSummary.from(doc).elapsedSeconds());
    }

    public void testElapsedSecondsZeroForUnstartedOrInverted() {
        // Not started yet (start=0) -> 0
        MigrationDocument unstarted = MigrationDocument.builder().startTimeMillis(0L).lastUpdatedMillis(5_000L).build();
        assertEquals(0L, MigrationSummary.from(unstarted).elapsedSeconds());

        // lastUpdated < start (clock skew / race) -> 0, never negative
        MigrationDocument inverted = MigrationDocument.builder().startTimeMillis(10_000L).lastUpdatedMillis(5_000L).build();
        assertEquals(0L, MigrationSummary.from(inverted).elapsedSeconds());
    }

    public void testShardCountAndShardsByPhase() {
        Map<Integer, ShardProgressDocument> shards = new HashMap<>();
        shards.put(0, ShardProgressDocument.builder().phase(ShardPhase.BACKFILLING).build());
        shards.put(1, ShardProgressDocument.builder().phase(ShardPhase.BACKFILLING).build());
        shards.put(2, ShardProgressDocument.builder().phase(ShardPhase.REPLAYING).build());
        shards.put(3, ShardProgressDocument.builder().phase(ShardPhase.COMPLETED).build());

        MigrationDocument doc = MigrationDocument.builder().shards(shards).build();
        MigrationSummary s = MigrationSummary.from(doc);

        assertEquals(4, s.shardCount());
        Map<String, Integer> byPhase = s.shardsByPhase();
        assertEquals(Integer.valueOf(2), byPhase.get(ShardPhase.BACKFILLING.name()));
        assertEquals(Integer.valueOf(1), byPhase.get(ShardPhase.REPLAYING.name()));
        assertEquals(Integer.valueOf(1), byPhase.get(ShardPhase.COMPLETED.name()));
        // No zero-count entries.
        assertFalse("phases with zero shards should be omitted", byPhase.containsKey(ShardPhase.PENDING.name()));
    }

    public void testEmptyShardsYieldsZeroCountAndEmptyMap() {
        MigrationDocument doc = MigrationDocument.builder().build();  // shards defaults to empty
        MigrationSummary s = MigrationSummary.from(doc);
        assertEquals(0, s.shardCount());
        assertTrue(s.shardsByPhase().isEmpty());
    }

    public void testCutoverPresentWhenContextNonNull_includingTerminalFailures() {
        // A FAILED migration that did attempt cutover before failing. The cutover
        // summary MUST still be present so operators can inspect outcome.
        CutoverContext ctx = CutoverContext.builder()
            .docCountValidationPassed(false)
            .aliasSwapSucceeded(false)
            .cutoverStartMillis(100L)
            .cutoverEndMillis(250L)
            .errorMessage("alias swap timed out")
            .build();
        MigrationDocument failed = MigrationDocument.builder().phase(CoordinatorPhase.FAILED).cutoverContext(ctx).build();

        MigrationSummary s = MigrationSummary.from(failed);
        assertNotNull("cutover summary must be present for FAILED migration that attempted cutover", s.cutover());
        assertFalse(s.cutover().docCountValidationPassed());
        assertFalse(s.cutover().aliasSwapSucceeded());
        assertEquals(150L, s.cutover().durationMillis());
        assertEquals("alias swap timed out", s.cutover().errorMessage());
    }

    public void testCutoverAbsentWhenContextNull() {
        MigrationDocument noCutover = MigrationDocument.builder().phase(CoordinatorPhase.ACTIVE).build();
        assertNull(MigrationSummary.from(noCutover).cutover());
    }

    /**
     * {@link CutoverContext#durationMillis()} can be negative when the end timestamp is unset (e.g., cutover began but
     * never completed) or when clock skew puts {@code cutoverEndMillis < cutoverStartMillis}. The slim projection MUST
     * clamp the wire value to {@code >= 0} so consumers never see a nonsensical negative duration.
     */
    public void testCutoverDurationClampedToZeroWhenNegative() {
        // End is unset (== 0) but start is set -> raw computed duration is negative.
        CutoverContext negativeCtx = CutoverContext.builder()
            .docCountValidationPassed(false)
            .aliasSwapSucceeded(false)
            .cutoverStartMillis(5_000L)
            .cutoverEndMillis(0L)
            .build();
        assertTrue("precondition: raw duration is negative", negativeCtx.durationMillis() < 0L);

        MigrationDocument doc = MigrationDocument.builder().phase(CoordinatorPhase.FAILED).cutoverContext(negativeCtx).build();

        CutoverSummary summary = MigrationSummary.from(doc).cutover();
        assertNotNull(summary);
        assertEquals("negative duration must be clamped to 0 on the wire", 0L, summary.durationMillis());
    }
}
