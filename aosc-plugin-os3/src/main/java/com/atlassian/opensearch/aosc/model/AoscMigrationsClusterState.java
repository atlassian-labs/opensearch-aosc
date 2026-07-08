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
import com.atlassian.opensearch.aosc.model.transform.TransformScript;
import com.atlassian.opensearch.aosc.utils.jackson.JacksonHelper;
import com.atlassian.opensearch.aosc.utils.jackson.JacksonToXContentObject;
import com.atlassian.opensearch.aosc.utils.jackson.JacksonWriteable;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.ToString;
import lombok.experimental.Accessors;
import lombok.extern.jackson.Jacksonized;

import org.opensearch.Version;
import org.opensearch.cluster.AbstractDiffable;
import org.opensearch.cluster.AbstractNamedDiffable;
import org.opensearch.cluster.ClusterState;
import org.opensearch.cluster.DiffableUtils;
import org.opensearch.cluster.NamedDiff;
import org.opensearch.core.common.io.stream.StreamInput;
import org.opensearch.core.common.io.stream.StreamOutput;
import org.opensearch.core.xcontent.ToXContent;
import org.opensearch.core.xcontent.XContentBuilder;

import java.io.IOException;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * Cluster state custom metadata tracking all active AOSC migrations.
 * Tier 0 state — available during bootstrap before indices recover.
 *
 * <p>Uses {@code Map<String, Entry>} keyed by migrationId so that
 * {@link DiffableUtils} can compute <b>entry-level diffs</b> — only
 * changed/added/removed entries are serialized on the wire, not the
 * entire collection.</p>
 *
 * <p>Each {@link Entry} extends {@link AbstractDiffable} so it participates
 * in the map diff protocol. {@link ShardMigrationClusterState} is small enough
 * that full-replacement per entry is efficient.</p>
 *
 * <p>Modeled after SnapshotsInProgress.</p>
 */
public class AoscMigrationsClusterState extends AbstractNamedDiffable<ClusterState.Custom> implements ClusterState.Custom {

    public static final String TYPE = "aosc_migrations";
    public static final AoscMigrationsClusterState EMPTY = new AoscMigrationsClusterState(Collections.emptyMap());

    private final Map<String, Entry> entries;

    public AoscMigrationsClusterState(Map<String, Entry> entries) {
        this.entries = Collections.unmodifiableMap(new HashMap<>(entries));
    }

    public AoscMigrationsClusterState(StreamInput in) throws IOException {
        this.entries = Collections.unmodifiableMap(in.readMap(StreamInput::readString, Entry::new));
    }

    /** All active migration entries, keyed by migrationId. */
    public Map<String, Entry> entries() {
        return entries;
    }

    /** Find entry by migration ID, or null. */
    public Entry getEntry(String migrationId) {
        return entries.get(migrationId);
    }

    /** Returns the entry whose sourceIndex matches, or null if not found. */
    public Entry getEntryBySourceIndex(String sourceIndex) {
        return entries.values().stream().filter(e -> sourceIndex.equals(e.sourceIndex())).findFirst().orElse(null);
    }

    /** Return new instance with entry added or replaced (by migrationId). */
    public AoscMigrationsClusterState withEntry(Entry entry) {
        Map<String, Entry> newEntries = new HashMap<>(entries);
        newEntries.put(entry.migrationId(), entry);
        return new AoscMigrationsClusterState(newEntries);
    }

    /** Return new instance with entry removed. */
    public AoscMigrationsClusterState withoutEntry(String migrationId) {
        Map<String, Entry> newEntries = new HashMap<>(entries);
        newEntries.remove(migrationId);
        return new AoscMigrationsClusterState(newEntries);
    }

    // ---- Writeable ----

    @Override
    public void writeTo(StreamOutput out) throws IOException {
        out.writeMap(entries, StreamOutput::writeString, (o, v) -> v.writeTo(o));
    }

    // ---- NamedDiffable ----

    @Override
    public String getWriteableName() {
        return TYPE;
    }

    @Override
    public Version getMinimalSupportedVersion() {
        return Version.CURRENT;
    }

    public static NamedDiff<ClusterState.Custom> readDiffFrom(StreamInput in) throws IOException {
        return readDiffFrom(ClusterState.Custom.class, TYPE, in);
    }

    // ---- XContent ----

    @Override
    public XContentBuilder toXContent(XContentBuilder builder, ToXContent.Params params) throws IOException {
        builder.startArray(TYPE);
        for (Entry entry : entries.values()) {
            entry.toXContent(builder, params);
        }
        builder.endArray();
        return builder;
    }

    // ---- equals / hashCode / toString ----

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        AoscMigrationsClusterState that = (AoscMigrationsClusterState) o;
        return entries.equals(that.entries);
    }

    @Override
    public int hashCode() {
        return entries.hashCode();
    }

    @Override
    public String toString() {
        return "AoscMigrationsClusterState{entries=" + entries.size() + "}";
    }

    // ======== Inner class: Entry ========

    /**
     * One active migration. Immutable — use {@code toBuilder()} for mutations.
     *
     * <p>Extends {@link AbstractDiffable} so that map-level diffing via
     * {@link DiffableUtils} only sends changed entries on the wire.</p>
     */
    @Getter
    @Jacksonized
    @Builder(toBuilder = true)
    @AllArgsConstructor(access = AccessLevel.PRIVATE)
    @Accessors(fluent = true)
    @EqualsAndHashCode(callSuper = false)
    @ToString
    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class Entry extends AbstractDiffable<Entry> implements JacksonWriteable, JacksonToXContentObject {

        @JsonProperty("migration_id")
        String migrationId;
        @JsonProperty("source_index")
        String sourceIndex;
        @JsonProperty("target_index")
        String targetIndex;
        @JsonProperty("alias")
        String alias;
        @JsonProperty("transform_script")
        TransformScript transformScript;
        @JsonProperty("options")
        MigrationRequestOptions options;
        @JsonProperty("phase")
        CoordinatorPhase phase;
        @JsonProperty("routing_mode")
        ShardRoutingMode routingMode;
        @JsonProperty("start_time_millis")
        long startTimeMillis;
        @JsonProperty("shards")
        @Builder.Default
        Map<Integer, ShardMigrationClusterState> shards = Collections.emptyMap();
        @JsonProperty("failure")
        String failure;
        @JsonProperty("meta")
        @Builder.Default
        MigrationMetadata meta = MigrationMetadata.EMPTY;

        /** Deserialize from transport stream. */
        public Entry(StreamInput in) throws IOException {
            Entry obj = JacksonHelper.readFrom(in, Entry.class);
            this.migrationId = obj.migrationId;
            this.sourceIndex = obj.sourceIndex;
            this.targetIndex = obj.targetIndex;
            this.alias = obj.alias;
            this.transformScript = obj.transformScript;
            this.options = obj.options;
            this.phase = obj.phase;
            this.routingMode = obj.routingMode;
            this.startTimeMillis = obj.startTimeMillis;
            this.shards = obj.shards;
            this.failure = obj.failure;
            this.meta = obj.meta;
        }
    }

    // ======== Inner class: ShardMigrationClusterState ========

    /**
     * Per-shard status in cluster state. Lean — only phase + checkpoint.
     * Detailed counters live in Tier 1 (.aosc-migrations child docs).
     */
    @Getter
    @Jacksonized
    @Builder(toBuilder = true)
    @AllArgsConstructor(access = AccessLevel.PRIVATE)
    @Accessors(fluent = true)
    @EqualsAndHashCode(callSuper = false)
    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class ShardMigrationClusterState implements JacksonWriteable, JacksonToXContentObject {

        @JsonProperty("phase")
        ShardPhase phase;
        @JsonProperty("last_replayed_seq_no")
        long lastReplayedSeqNo;
        @JsonProperty("backfill_cutoff_seq_no")
        long backfillCutoffSeqNo;
        @JsonProperty("failure")
        String failure;
        @JsonProperty("meta")
        @Builder.Default
        MigrationMetadata meta = MigrationMetadata.EMPTY;

        /** Deserialize from transport stream. */
        public ShardMigrationClusterState(StreamInput in) throws IOException {
            ShardMigrationClusterState obj = JacksonHelper.readFrom(in, ShardMigrationClusterState.class);
            this.phase = obj.phase;
            this.lastReplayedSeqNo = obj.lastReplayedSeqNo;
            this.backfillCutoffSeqNo = obj.backfillCutoffSeqNo;
            this.failure = obj.failure;
            this.meta = obj.meta;
        }
    }
}
