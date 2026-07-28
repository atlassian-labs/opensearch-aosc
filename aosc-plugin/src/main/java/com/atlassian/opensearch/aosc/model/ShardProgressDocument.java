/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */
package com.atlassian.opensearch.aosc.model;

import com.atlassian.opensearch.aosc.model.phase.ShardPhase;
import com.atlassian.opensearch.aosc.statemachine.TransitionRecord;
import com.atlassian.opensearch.aosc.utils.jackson.JacksonHelper;
import com.atlassian.opensearch.aosc.utils.jackson.JacksonToXContentObject;
import com.atlassian.opensearch.aosc.utils.jackson.JacksonWriteable;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.ToString;
import lombok.experimental.Accessors;
import lombok.extern.jackson.Jacksonized;

import org.opensearch.core.common.io.stream.StreamInput;

import java.io.IOException;
import java.util.Collections;
import java.util.List;

/**
 * Immutable snapshot of per-shard progress, embedded in the
 * {@link MigrationDocument#shards()} map (Tier 1). Contains the current phase,
 * checkpoint seq nos, and per-phase metrics with detailed counters.
 *
 * <p>Implements {@link org.opensearch.core.common.io.stream.Writeable} for
 * transport serialization (e.g., shard update requests between nodes).</p>
 */
@Getter
@Accessors(fluent = true)
@EqualsAndHashCode
@ToString
@Jacksonized
@Builder(toBuilder = true)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@JsonIgnoreProperties(ignoreUnknown = true)
public class ShardProgressDocument implements JacksonToXContentObject, JacksonWriteable {

    @JsonProperty("phase")
    private final ShardPhase phase;

    @JsonProperty("last_replayed_seq_no")
    private final long lastReplayedSeqNo;

    @JsonProperty("target_seq_no")
    private final long targetSeqNo;

    @JsonProperty("backfill_cutoff_seq_no")
    private final long backfillCutoffSeqNo;

    @JsonProperty("error")
    private final String error;

    @JsonProperty("backfill")
    private final PhaseMetrics backfill;

    @JsonProperty("replay")
    private final PhaseMetrics replay;

    @JsonProperty("convergence")
    private final PhaseMetrics convergence;

    @JsonProperty("catching_up")
    private final PhaseMetrics catchingUp;

    @JsonProperty("transition_history")
    @Builder.Default
    private final List<TransitionRecord<ShardPhase>> transitionHistory = Collections.emptyList();

    @JsonProperty("meta")
    @Builder.Default
    private final MigrationMetadata meta = MigrationMetadata.EMPTY;

    public ShardProgressDocument(StreamInput in) throws IOException {
        ShardProgressDocument obj = JacksonHelper.readFrom(in, ShardProgressDocument.class);
        this.phase = obj.phase;
        this.lastReplayedSeqNo = obj.lastReplayedSeqNo;
        this.targetSeqNo = obj.targetSeqNo;
        this.backfillCutoffSeqNo = obj.backfillCutoffSeqNo;
        this.error = obj.error;
        this.backfill = obj.backfill;
        this.replay = obj.replay;
        this.convergence = obj.convergence;
        this.catchingUp = obj.catchingUp;
        this.transitionHistory = obj.transitionHistory;
        this.meta = obj.meta;
    }

    /** Total documents indexed across all phases (currently only backfill contributes). */
    public long totalDocumentsIndexed() {
        return backfill != null ? backfill.documentsIndexed() : 0L;
    }

    /**
    /** Deserialize from XContent parser. */
    public static ShardProgressDocument fromXContent(org.opensearch.core.xcontent.XContentParser parser) throws IOException {
        return JacksonHelper.fromXContent(parser, ShardProgressDocument.class);
    }

    // ======== Inner enum: PhaseStatus ========

    /** Status of a single phase within a shard migration. */
    public enum PhaseStatus {
        NOT_STARTED,
        IN_PROGRESS,
        COMPLETED,
        FAILED
    }

    // ======== Inner class: PhaseMetrics ========

    /**
     * Progress counters for a single phase. Immutable.
     */
    @Getter
    @EqualsAndHashCode
    @ToString
    @Jacksonized
    @Builder(toBuilder = true)
    @AllArgsConstructor(access = AccessLevel.PRIVATE)
    @Accessors(fluent = true)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class PhaseMetrics implements JacksonToXContentObject, JacksonWriteable {

        @JsonProperty("status")
        private final PhaseStatus status;
        @JsonProperty("start_seq_no")
        private final long startSeqNo;
        @JsonProperty("target_seq_no")
        private final long targetSeqNo;
        @JsonProperty("last_processed_seq_no")
        private final long lastProcessedSeqNo;
        @JsonProperty("documents_indexed")
        private final long documentsIndexed;
        @JsonProperty("documents_skipped")
        private final long documentsSkipped;
        @JsonProperty("operations_applied")
        private final long operationsApplied;
        @JsonProperty("operations_skipped")
        private final long operationsSkipped;
        @JsonProperty("bulk_retries")
        private final long bulkRetries;
        @JsonProperty("rounds")
        private final int rounds;
        @JsonProperty("current_gap")
        private final long currentGap;
        @JsonProperty("start_time_millis")
        private final long startTimeMillis;
        @JsonProperty("end_time_millis")
        private final long endTimeMillis;
        @JsonProperty("error")
        private final String error;

        public PhaseMetrics(StreamInput in) throws IOException {
            PhaseMetrics obj = JacksonHelper.readFrom(in, PhaseMetrics.class);
            this.status = obj.status;
            this.startSeqNo = obj.startSeqNo;
            this.targetSeqNo = obj.targetSeqNo;
            this.lastProcessedSeqNo = obj.lastProcessedSeqNo;
            this.documentsIndexed = obj.documentsIndexed;
            this.documentsSkipped = obj.documentsSkipped;
            this.operationsApplied = obj.operationsApplied;
            this.operationsSkipped = obj.operationsSkipped;
            this.bulkRetries = obj.bulkRetries;
            this.rounds = obj.rounds;
            this.currentGap = obj.currentGap;
            this.startTimeMillis = obj.startTimeMillis;
            this.endTimeMillis = obj.endTimeMillis;
            this.error = obj.error;
        }

        public static PhaseMetrics notStarted() {
            return ShardProgressDocument.PhaseMetrics.builder().status(ShardProgressDocument.PhaseStatus.NOT_STARTED).build();
        }

        // writeTo provided by JacksonWriteable
        // toXContent provided by JacksonToXContentObject

        /** Deserialize from XContent parser. */
        public static PhaseMetrics fromXContent(org.opensearch.core.xcontent.XContentParser parser) throws IOException {
            return JacksonHelper.fromXContent(parser, PhaseMetrics.class);
        }
    }
}
