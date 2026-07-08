/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */
package com.atlassian.opensearch.aosc.action.cleanup;

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

import java.util.List;

/** Data body for {@link CleanupLeasesResponse}. */
@Value
@Jacksonized
@Builder
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
@Accessors(fluent = true)
public class CleanupLeasesResult implements JacksonWriteable, JacksonToXContentObject {

    @JsonProperty("dry_run")
    boolean dryRun;

    @JsonProperty("leases")
    List<LeaseInfo> leases;

    /** Count of successfully released leases — derived from {@link #leases}. Zero in dry-run mode. */
    @JsonProperty("released")
    public int releasedCount() {
        if (dryRun || leases == null) return 0;
        return (int) leases.stream().filter(LeaseInfo::released).count();
    }

    /** Count of failed lease releases — derived from {@link #leases}. Zero in dry-run mode. */
    @JsonProperty("failed")
    public int failedCount() {
        if (dryRun || leases == null) return 0;
        return (int) leases.stream().filter(l -> !l.released()).count();
    }

    @Value
    @Jacksonized
    @Builder
    @AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @Accessors(fluent = true)
    public static class LeaseInfo implements JacksonWriteable, JacksonToXContentObject {

        @JsonProperty("index")
        String index;

        @JsonProperty("shard")
        int shard;

        @JsonProperty("lease_id")
        String leaseId;

        @JsonProperty("source")
        String source;

        @JsonProperty("retaining_seq_no")
        long retainingSeqNo;

        @JsonProperty("released")
        boolean released;

        @JsonProperty("error")
        String error;
    }
}
