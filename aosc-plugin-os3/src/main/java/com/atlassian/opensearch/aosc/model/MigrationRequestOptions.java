/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */
package com.atlassian.opensearch.aosc.model;

import com.atlassian.opensearch.aosc.AoscSettings;
import com.atlassian.opensearch.aosc.utils.jackson.JacksonHelper;
import com.atlassian.opensearch.aosc.utils.jackson.JacksonToXContentObject;
import com.atlassian.opensearch.aosc.utils.jackson.JacksonWriteable;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.Accessors;

import org.opensearch.action.ActionRequestValidationException;
import org.opensearch.action.ValidateActions;
import org.opensearch.common.settings.ClusterSettings;
import org.opensearch.common.settings.Setting;
import org.opensearch.common.settings.Settings;
import org.opensearch.core.common.io.stream.StreamInput;
import org.opensearch.index.query.QueryBuilder;
import org.opensearch.index.query.QueryBuilders;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Objects;

/**
 * Per-migration tunable options. All fields are optional with sensible defaults.
 *
 * <p>Provided in the migration start request:</p>
 * <pre>
 * POST /_plugins/_aosc/my-index/_start
 * {
 *   "target": { "index": "my-index-v2" },
 *   "options": {
 *     "convergence_threshold_per_shard": 500
 *   }
 * }
 * </pre>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Accessors(chain = true)
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class MigrationRequestOptions implements JacksonWriteable, JacksonToXContentObject {

    @JsonProperty("convergence_threshold_per_shard")
    private Integer convergenceThresholdPerShard;

    @JsonProperty("max_convergence_rounds_per_shard")
    private Integer maxConvergenceRoundsPerShard;

    @JsonProperty("doc_count_tolerance")
    private Integer docCountTolerance;

    /**
     * Optional OpenSearch query DSL (as a raw map) used to filter document counts during cutover validation.
     * When set, both source and target doc counts are scoped to documents matching this query,
     * allowing partial-index migrations where not all source documents are expected in the target.
     *
     * <p>Example: {@code {"term": {"status": "active"}}} counts only active docs on both sides.</p>
     */
    @JsonProperty("validation_query")
    private Map<String, Object> validationQuery;

    @JsonProperty("accept_data_loss_if_custom_routing_is_used")
    private Boolean acceptDataLossIfCustomRoutingIsUsed;

    @JsonProperty("transient_target_settings")
    private Map<String, String> transientTargetSettings;

    @JsonProperty("target_ready_timeout_seconds")
    private Integer targetReadyTimeoutSeconds;

    /** Whether to remove the source index write-block after a successful migration. Defaults to {@code false} when unset. */
    @JsonProperty("remove_source_write_block_on_success")
    private Boolean removeSourceWriteBlockOnSuccess;

    /**
     * Parse {@code validation_query} into a {@link QueryBuilder}.
     * Returns {@code null} when the query is absent or empty.
     */
    public QueryBuilder getValidationQueryBuilder() {
        if (validationQuery == null || validationQuery.isEmpty()) {
            return null;
        }
        try {
            byte[] jsonBytes = JacksonHelper.writeAsBytes(validationQuery);
            return QueryBuilders.wrapperQuery(new String(jsonBytes, StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalArgumentException("Failed to parse validation_query: " + e.getMessage(), e);
        }
    }

    /** Resolves {@link #removeSourceWriteBlockOnSuccess} with its default — {@code false} when unset. */
    public boolean shouldRemoveSourceWriteBlockOnSuccess() {
        return Boolean.TRUE.equals(removeSourceWriteBlockOnSuccess);
    }

    public MigrationRequestOptions(StreamInput in) throws IOException {
        JacksonHelper.copyFields(in, this, MigrationRequestOptions.class);
    }

    /**
     * Builds default options from cluster settings, falling back to hardcoded defaults
     * for any setting not configured at cluster level.
     */
    public static MigrationRequestOptions defaultOptions(ClusterSettings clusterSettings) {
        Map<String, String> transientSettings = AoscSettings.parseTransientTargetSettings(
            clusterSettings.get(AoscSettings.TRANSIENT_TARGET_SETTINGS)
        );
        return new MigrationRequestOptions(
            clusterSettings.get(AoscSettings.CONVERGENCE_THRESHOLD),
            clusterSettings.get(AoscSettings.MAX_CONVERGENCE_ROUNDS),
            0,
            null, // validationQuery
            false,
            transientSettings.isEmpty() ? null : transientSettings,
            (int) clusterSettings.get(AoscSettings.TARGET_READY_TIMEOUT).getSeconds(),
            clusterSettings.get(AoscSettings.REMOVE_SOURCE_WRITE_BLOCK_ON_SUCCESS)
        );
    }

    public MigrationRequestOptions mergeWith(MigrationRequestOptions requestOptions) {
        if (requestOptions == null) {
            MigrationRequestOptions options = new MigrationRequestOptions();
            options.convergenceThresholdPerShard = this.convergenceThresholdPerShard;
            options.maxConvergenceRoundsPerShard = this.maxConvergenceRoundsPerShard;
            options.docCountTolerance = this.docCountTolerance;
            options.validationQuery = this.validationQuery;
            options.acceptDataLossIfCustomRoutingIsUsed = this.acceptDataLossIfCustomRoutingIsUsed;
            options.transientTargetSettings = this.transientTargetSettings;
            options.targetReadyTimeoutSeconds = this.targetReadyTimeoutSeconds;
            options.removeSourceWriteBlockOnSuccess = this.removeSourceWriteBlockOnSuccess;
            return options;
        }

        MigrationRequestOptions merged = new MigrationRequestOptions();
        merged.convergenceThresholdPerShard = requestOptions.convergenceThresholdPerShard != null
            ? requestOptions.convergenceThresholdPerShard
            : this.convergenceThresholdPerShard;
        merged.maxConvergenceRoundsPerShard = requestOptions.maxConvergenceRoundsPerShard != null
            ? requestOptions.maxConvergenceRoundsPerShard
            : this.maxConvergenceRoundsPerShard;
        merged.docCountTolerance = requestOptions.docCountTolerance != null ? requestOptions.docCountTolerance : this.docCountTolerance;
        merged.validationQuery = requestOptions.validationQuery != null ? requestOptions.validationQuery : this.validationQuery;
        merged.acceptDataLossIfCustomRoutingIsUsed = requestOptions.acceptDataLossIfCustomRoutingIsUsed != null
            ? requestOptions.acceptDataLossIfCustomRoutingIsUsed
            : this.acceptDataLossIfCustomRoutingIsUsed;
        merged.transientTargetSettings = Objects.nonNull(requestOptions.transientTargetSettings)
            ? requestOptions.transientTargetSettings
            : this.transientTargetSettings;
        merged.targetReadyTimeoutSeconds = Objects.nonNull(requestOptions.targetReadyTimeoutSeconds)
            ? requestOptions.targetReadyTimeoutSeconds
            : this.targetReadyTimeoutSeconds;
        merged.removeSourceWriteBlockOnSuccess = requestOptions.removeSourceWriteBlockOnSuccess != null
            ? requestOptions.removeSourceWriteBlockOnSuccess
            : this.removeSourceWriteBlockOnSuccess;
        return merged;
    }

    /** Deserialize from XContent parser (REST API input). */
    public static MigrationRequestOptions fromXContent(org.opensearch.core.xcontent.XContentParser parser) throws IOException {
        return JacksonHelper.fromXContent(parser, MigrationRequestOptions.class);
    }

    public ActionRequestValidationException validate() {
        ActionRequestValidationException ex = null;
        ex = validateSettingValue(AoscSettings.CONVERGENCE_THRESHOLD, "convergence_threshold_per_shard", convergenceThresholdPerShard, ex);
        ex = validateSettingValue(
            AoscSettings.MAX_CONVERGENCE_ROUNDS,
            "max_convergence_rounds_per_shard",
            maxConvergenceRoundsPerShard,
            ex
        );
        ex = validateSettingValue(
            AoscSettings.TARGET_READY_TIMEOUT,
            "target_ready_timeout_seconds",
            targetReadyTimeoutSeconds == null ? null : targetReadyTimeoutSeconds + "s",
            ex
        );
        if (docCountTolerance != null && docCountTolerance < 0) {
            ex = ValidateActions.addValidationError("doc_count_tolerance must be >= 0, got " + docCountTolerance, ex);
        }
        if (transientTargetSettings != null) {
            for (String key : transientTargetSettings.keySet()) {
                if (!key.startsWith("index.")) {
                    ex = ValidateActions.addValidationError(
                        "transient_target_settings keys must start with 'index.', got '" + key + "'",
                        ex
                    );
                }
            }
        }
        return ex;
    }

    private static ActionRequestValidationException validateSettingValue(
        Setting<?> setting,
        String fieldName,
        Object value,
        ActionRequestValidationException ex
    ) {
        if (value == null) {
            return ex;
        }
        try {
            setting.get(Settings.builder().put(setting.getKey(), value.toString()).build());
            return ex;
        } catch (IllegalArgumentException e) {
            return ValidateActions.addValidationError(fieldName + " is invalid: " + e.getMessage(), ex);
        }
    }
}
