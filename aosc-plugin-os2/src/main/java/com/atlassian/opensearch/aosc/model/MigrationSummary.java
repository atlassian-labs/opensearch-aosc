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

import java.io.IOException;
import java.util.EnumMap;
import java.util.Map;
import java.util.TreeMap;

/**
 * Slim, list-API projection of a {@link MigrationDocument}.
 *
 * <p>Carries only the high-signal fields needed for an overview / dashboard view
 * of a migration. The full {@link MigrationDocument} (with its potentially large
 * {@code shards} map, {@code transition_history}, {@code transform_script},
 * {@code options}, raw {@code cutover_context}, and {@code meta}) is intentionally
 * NOT included here — callers needing forensic detail should use the {@code Get}
 * API for a specific migration.</p>
 *
 * <p>The included {@link CutoverSummary} is present whenever the underlying
 * {@code cutover_context} is non-null (i.e. cutover has been attempted), regardless
 * of whether the migration eventually succeeded or failed.</p>
 */
@Getter
@Accessors(fluent = true)
@EqualsAndHashCode
@ToString
@Jacksonized
@Builder(toBuilder = true)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class MigrationSummary implements JacksonWriteable, JacksonToXContentObject {

    @JsonProperty("migration_id")
    private final String migrationId;

    @JsonProperty("source_index")
    private final String sourceIndex;

    @JsonProperty("target_index")
    private final String targetIndex;

    @JsonProperty("alias")
    private final String alias;

    @JsonProperty("phase")
    private final CoordinatorPhase phase;

    @JsonProperty("start_time_millis")
    private final long startTimeMillis;

    @JsonProperty("last_updated_millis")
    private final long lastUpdatedMillis;

    /** Top-level error, if the migration failed; null otherwise. */
    @JsonProperty("error_message")
    private final String errorMessage;

    /**
     * Distribution of shards by their current {@link ShardPhase}. Keys are the
     * stable {@code ShardPhase} enum names (Strings, for forward-compatibility on
     * the wire); values are counts. Phases with zero shards are omitted.
     *
     * <p>This is the only stored shard-related field — {@link #shardCount()} is
     * derived from it (sum of values).</p>
     */
    @JsonProperty("shards_by_phase")
    private final Map<String, Integer> shardsByPhase;

    /** Slim cutover summary; present iff the migration has a cutover_context. */
    @JsonProperty("cutover")
    private final CutoverSummary cutover;

    /**
     * Derived: {@code true} iff {@link #phase} is one of the terminal phases
     * ({@code COMPLETED} / {@code CANCELLED} / {@code FAILED}).
     *
     * <p>Exposed as a Jackson getter (not a stored field) so it serializes into
     * its own JSON property without bloating the constructor / builder / equals.
     * On deserialization the corresponding {@code is_terminal} JSON property is
     * silently ignored — the value is always recomputed from {@link #phase}.</p>
     */
    @JsonProperty("is_terminal")
    public boolean isTerminal() {
        return phase != null && phase.isTerminal();
    }

    /**
     * Derived: total number of shards tracked for this migration. Sum of all
     * values in {@link #shardsByPhase}; {@code 0} if the map is null or empty.
     *
     * <p>Exposed as a Jackson getter so it does not duplicate state already
     * present in {@link #shardsByPhase}. On deserialization the corresponding
     * {@code shard_count} JSON property is silently ignored.</p>
     */
    @JsonProperty("shard_count")
    public int shardCount() {
        if (shardsByPhase == null || shardsByPhase.isEmpty()) {
            return 0;
        }
        int total = 0;
        for (Integer count : shardsByPhase.values()) {
            if (count != null) {
                total += count;
            }
        }
        return total;
    }

    /**
     * Derived: elapsed migration time in seconds, computed as
     * {@code (last_updated_millis - start_time_millis) / 1000}. Clamped to {@code 0}
     * for unstarted migrations or inverted clocks (where last_updated < start).
     * For terminal migrations this naturally freezes once the document stops being
     * updated.
     *
     * <p>Exposed as a Jackson getter so it does not duplicate state already
     * present in {@link #startTimeMillis} and {@link #lastUpdatedMillis}. On
     * deserialization the corresponding {@code elapsed_seconds} JSON property is
     * silently ignored.</p>
     */
    @JsonProperty("elapsed_seconds")
    public long elapsedSeconds() {
        if (startTimeMillis <= 0L || lastUpdatedMillis < startTimeMillis) {
            return 0L;
        }
        return (lastUpdatedMillis - startTimeMillis) / 1000L;
    }

    /** Deserialize from XContent parser. */
    public static MigrationSummary fromXContent(org.opensearch.core.xcontent.XContentParser parser) throws IOException {
        return JacksonHelper.fromXContent(parser, MigrationSummary.class);
    }

    /**
     * Project a {@link MigrationDocument} into a slim {@link MigrationSummary}.
     *
     * <p>All derived fields ({@code is_terminal}, {@code shard_count},
     * {@code shards_by_phase}, {@code elapsed_seconds}) are computed from the
     * source document. The {@code cutover} field is included whenever
     * {@link MigrationDocument#cutoverContext()} is non-null.</p>
     */
    public static MigrationSummary from(MigrationDocument doc) {
        if (doc == null) {
            return null;
        }
        return MigrationSummary.builder()
            .migrationId(doc.migrationId())
            .sourceIndex(doc.sourceIndex())
            .targetIndex(doc.targetIndex())
            .alias(doc.alias())
            .phase(doc.phase())
            .startTimeMillis(doc.startTimeMillis())
            .lastUpdatedMillis(doc.lastUpdatedMillis())
            .errorMessage(doc.errorMessage())
            .shardsByPhase(computeShardsByPhase(doc.shards()))
            .cutover(CutoverSummary.from(doc.cutoverContext()))
            .build();
    }

    /**
     * Group shards by their current {@link ShardPhase}, using the enum name as
     * the JSON key. Returns an empty map for {@code null} or empty input. Phases
     * with zero shards are not present in the result. Output is sorted by key
     * for deterministic serialization.
     */
    private static Map<String, Integer> computeShardsByPhase(Map<Integer, ShardProgressDocument> shards) {
        if (shards == null || shards.isEmpty()) {
            return Map.of();
        }
        EnumMap<ShardPhase, Integer> counts = new EnumMap<>(ShardPhase.class);
        for (ShardProgressDocument shard : shards.values()) {
            if (shard == null || shard.phase() == null) {
                continue;
            }
            counts.merge(shard.phase(), 1, Integer::sum);
        }
        // Convert to a string-keyed, deterministically-ordered map for the wire format.
        Map<String, Integer> out = new TreeMap<>();
        for (Map.Entry<ShardPhase, Integer> e : counts.entrySet()) {
            out.put(e.getKey().name(), e.getValue());
        }
        return out;
    }
}
