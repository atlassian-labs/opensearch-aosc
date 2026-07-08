/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */
package com.atlassian.opensearch.aosc.action.status;

import com.atlassian.opensearch.aosc.utils.jackson.JacksonToXContentObject;
import com.atlassian.opensearch.aosc.utils.jackson.JacksonWriteable;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Value;
import lombok.experimental.Accessors;
import lombok.extern.jackson.Jacksonized;

/** Data body for {@link GetMigrationStatusRequest}. */
@Value
@Jacksonized
@Builder
@AllArgsConstructor
@Accessors(fluent = true)
@JsonIgnoreProperties(ignoreUnknown = true)
public class GetMigrationStatusBody implements JacksonWriteable, JacksonToXContentObject {

    @JsonProperty("source_index")
    String sourceIndex;
}
