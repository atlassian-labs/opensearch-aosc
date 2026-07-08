/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */
package com.atlassian.opensearch.aosc.action.clear;

import com.atlassian.opensearch.aosc.model.AoscMigrationsClusterState;
import com.atlassian.opensearch.aosc.utils.jackson.JacksonToXContentObject;
import com.atlassian.opensearch.aosc.utils.jackson.JacksonWriteable;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Value;
import lombok.experimental.Accessors;
import lombok.extern.jackson.Jacksonized;

import java.util.Map;

/** Rich result body for {@link ClearClusterStateResponse}. */
@Value
@Jacksonized
@Builder
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
@Accessors(fluent = true)
public class ClearClusterStateResult implements JacksonWriteable, JacksonToXContentObject {

    @JsonProperty("dry_run")
    boolean dryRun;

    @JsonProperty("try_close")
    boolean tryClose;

    @JsonProperty("migrations")
    Map<String, MigrationAction> migrations;

    /** Per-migration-id summary of actions taken (or that would be taken in dry-run). */
    @Value
    @Jacksonized
    @Builder
    @AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @Accessors(fluent = true)
    public static class MigrationAction implements JacksonWriteable, JacksonToXContentObject {

        @JsonProperty("source_index")
        String sourceIndex;

        @JsonProperty("phase")
        String phase;

        /** "cleared", "not_found", or "would_clear" (dry-run). */
        @JsonProperty("cluster_state")
        String clusterState;

        /** "cleared", "not_found", or "would_clear" (dry-run). */
        @JsonProperty("active_migrations")
        String activeMigrations;

        @JsonProperty("closed")
        boolean closed;

        /** Full cluster state entry — only populated when detailed=true. */
        @JsonProperty("entry")
        AoscMigrationsClusterState.Entry entry;
    }
}
