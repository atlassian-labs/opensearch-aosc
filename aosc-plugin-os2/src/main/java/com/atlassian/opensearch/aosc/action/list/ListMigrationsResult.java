/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */
package com.atlassian.opensearch.aosc.action.list;

import com.atlassian.opensearch.aosc.model.MigrationSummary;
import com.atlassian.opensearch.aosc.utils.jackson.JacksonToXContentObject;
import com.atlassian.opensearch.aosc.utils.jackson.JacksonWriteable;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import lombok.Builder;
import lombok.Value;
import lombok.experimental.Accessors;
import lombok.extern.jackson.Jacksonized;

import java.util.List;

/**
 * Data payload for {@link ListMigrationsResponse}.
 *
 * <p>Carries a list of slim {@link MigrationSummary} projections (rather than
 * full {@code MigrationDocument}s) so that the list response stays bounded in
 * size even for large clusters with many shards per migration. Callers needing
 * the full document for a specific migration should use the {@code Get} API.</p>
 *
 * <p>Pagination is intentionally NOT modeled at this time — the server applies
 * an internal cap on {@code size} and surfaces a {@link #truncated} hint when
 * that cap is reached during the merge step. A follow-up will introduce
 * cursor-based pagination once the index size warrants it.</p>
 */
@Value
@Jacksonized
@Builder
@Accessors(fluent = true)
@JsonIgnoreProperties(ignoreUnknown = true)
public class ListMigrationsResult implements JacksonWriteable, JacksonToXContentObject {

    /** Slim per-migration summaries, capped at the requested {@code size}. */
    @JsonProperty("migrations")
    List<MigrationSummary> migrations;

    /**
     * True iff the merged result set (Tier-1 + coordinator cache, post-dedup,
     * pre-trim) exceeded the requested {@code size} and was truncated by the
     * server. Independent of the list length on the wire.
     */
    @JsonProperty("truncated")
    boolean truncated;

    /**
     * Authoritative count of currently-active (non-terminal) migrations known to
     * the coordinator cache, regardless of which entries appear in
     * {@link #migrations} after filtering / capping. Always reflects reality on
     * the cluster manager.
     */
    @JsonProperty("active_count")
    int activeCount;
}
