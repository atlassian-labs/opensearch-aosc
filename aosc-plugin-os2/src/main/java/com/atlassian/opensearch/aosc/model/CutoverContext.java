/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */
package com.atlassian.opensearch.aosc.model;

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

import java.io.IOException;

/**
 * Immutable metadata record capturing the details of a cutover operation.
 *
 * <p>Persisted as a nested object within the {@link MigrationDocument} in the
 * {@code .aosc-migrations} index (Tier 1). Records doc counts, validation
 * results, timing, and any error that occurred during cutover.</p>
 */
@Getter
@Accessors(fluent = true)
@EqualsAndHashCode
@ToString
@Jacksonized
@Builder(toBuilder = true)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@JsonIgnoreProperties(ignoreUnknown = true)
public class CutoverContext implements JacksonWriteable, JacksonToXContentObject {

    @JsonProperty("source_doc_count")
    private final long sourceDocCount;

    @JsonProperty("target_doc_count")
    private final long targetDocCount;

    @JsonProperty("doc_count_tolerance")
    private final int docCountTolerance;

    @JsonProperty("doc_count_validation_passed")
    private final boolean docCountValidationPassed;

    @JsonProperty("alias_swap_succeeded")
    private final boolean aliasSwapSucceeded;

    @JsonProperty("cutover_start_millis")
    private final long cutoverStartMillis;

    @JsonProperty("cutover_end_millis")
    private final long cutoverEndMillis;

    @JsonProperty("error_message")
    private final String errorMessage;

    /** Deserialize from XContent parser. */
    public static CutoverContext fromXContent(org.opensearch.core.xcontent.XContentParser parser) throws IOException {
        return JacksonHelper.fromXContent(parser, CutoverContext.class);
    }

    /** Duration of the cutover in milliseconds. */
    public long durationMillis() {
        return cutoverEndMillis - cutoverStartMillis;
    }
}
