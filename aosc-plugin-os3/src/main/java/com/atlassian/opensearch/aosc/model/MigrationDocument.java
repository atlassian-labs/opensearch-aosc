/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */
package com.atlassian.opensearch.aosc.model;

import com.atlassian.opensearch.aosc.model.phase.CoordinatorPhase;
import com.atlassian.opensearch.aosc.model.transform.TransformScript;
import com.atlassian.opensearch.aosc.statemachine.TransitionRecord;
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

import org.opensearch.core.common.io.stream.StreamInput;

import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Immutable metadata record for an AOSC migration, persisted as a
 * <b>monolith document</b> in the {@code .aosc-migrations} system index (Tier 1).
 *
 * <p>Contains migration-level configuration, overall phase, and embedded
 * per-shard progress in the {@link #shards} map. One document per migration.</p>
 *
 * <p>This is the Tier 1 complement to {@link AoscMigrationsClusterState.Entry}
 * (Tier 0, cluster state). The document stores fields that are too large
 * or too detailed for cluster state: transform scripts, synthetic routings,
 * migration options, and per-shard metrics.</p>
 */
@Getter
@Accessors(fluent = true)
@EqualsAndHashCode
@ToString
@Builder(toBuilder = true)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Jacksonized
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class MigrationDocument implements JacksonWriteable, JacksonToXContentObject {

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
    @JsonProperty("transform_script")
    private final TransformScript transformScript;
    @JsonProperty("options")
    @Builder.Default
    private final MigrationRequestOptions options = new MigrationRequestOptions();
    @JsonProperty("shard_routing_mode")
    private final ShardRoutingMode shardRoutingMode;
    @JsonProperty("synthetic_routings")
    private final String[] syntheticRoutings;
    @JsonProperty("start_time_millis")
    private final long startTimeMillis;
    @JsonProperty("last_updated_millis")
    private final long lastUpdatedMillis;
    @JsonProperty("error_message")
    private final String errorMessage;
    @JsonProperty("cutover_context")
    private final CutoverContext cutoverContext;
    @JsonProperty("shards")
    @Builder.Default
    private final Map<Integer, ShardProgressDocument> shards = Collections.emptyMap();

    @JsonProperty("transition_history")
    @Builder.Default
    private final List<TransitionRecord<CoordinatorPhase>> transitionHistory = Collections.emptyList();

    @JsonProperty("meta")
    @Builder.Default
    private final MigrationMetadata meta = MigrationMetadata.EMPTY;

    public MigrationDocument(StreamInput in) throws IOException {
        MigrationDocument obj = JacksonHelper.readFrom(in, MigrationDocument.class);
        this.migrationId = obj.migrationId;
        this.sourceIndex = obj.sourceIndex;
        this.targetIndex = obj.targetIndex;
        this.alias = obj.alias;
        this.phase = obj.phase;
        this.transformScript = obj.transformScript;
        this.options = obj.options;
        this.shardRoutingMode = obj.shardRoutingMode;
        this.syntheticRoutings = obj.syntheticRoutings;
        this.startTimeMillis = obj.startTimeMillis;
        this.lastUpdatedMillis = obj.lastUpdatedMillis;
        this.errorMessage = obj.errorMessage;
        this.cutoverContext = obj.cutoverContext;
        this.transitionHistory = obj.transitionHistory;
        this.meta = obj.meta;
        this.shards = obj.shards;
    }

    /** Deserialize from XContent parser. */
    public static MigrationDocument fromXContent(org.opensearch.core.xcontent.XContentParser parser) throws IOException {
        return JacksonHelper.fromXContent(parser, MigrationDocument.class);
    }

    public MigrationDocument withPhase(CoordinatorPhase newPhase) {
        return this.toBuilder().phase(newPhase).lastUpdatedMillis(System.currentTimeMillis()).build();
    }
}
