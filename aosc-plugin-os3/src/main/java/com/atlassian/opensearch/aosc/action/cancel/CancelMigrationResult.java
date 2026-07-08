/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */
package com.atlassian.opensearch.aosc.action.cancel;

import com.atlassian.opensearch.aosc.model.phase.CoordinatorPhase;
import com.atlassian.opensearch.aosc.utils.jackson.JacksonToXContentObject;
import com.atlassian.opensearch.aosc.utils.jackson.JacksonWriteable;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import lombok.Builder;
import lombok.Value;
import lombok.experimental.Accessors;
import lombok.extern.jackson.Jacksonized;

/** Data payload for {@link CancelMigrationResponse}. */
@Value
@Jacksonized
@Builder
@Accessors(fluent = true)
@JsonIgnoreProperties(ignoreUnknown = true)
public class CancelMigrationResult implements JacksonWriteable, JacksonToXContentObject {

    @JsonProperty("accepted")
    boolean accepted;

    @JsonProperty("phase")
    CoordinatorPhase phase;
}
