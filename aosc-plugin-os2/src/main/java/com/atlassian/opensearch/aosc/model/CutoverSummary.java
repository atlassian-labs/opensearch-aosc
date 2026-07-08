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

/**
 * Slim projection of {@link CutoverContext} suitable for the list API.
 *
 * <p>Includes only the high-signal fields needed for an overview of cutover
 * outcome: validation pass/fail, alias swap success, duration, and any error
 * message. Raw doc counts, tolerance, and timestamps are intentionally omitted
 * — callers needing full forensic detail should use the {@code Get} API.</p>
 *
 * <p>This object is included in {@code MigrationSummary} whenever the underlying
 * {@code CutoverContext} is present (i.e. cutover has been attempted), regardless
 * of the migration's terminal phase, so operators can inspect cutover state on
 * FAILED / CANCELLED migrations as well as successful ones.</p>
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
public class CutoverSummary implements JacksonWriteable, JacksonToXContentObject {

    @JsonProperty("doc_count_validation_passed")
    private final boolean docCountValidationPassed;

    @JsonProperty("alias_swap_succeeded")
    private final boolean aliasSwapSucceeded;

    /** Duration of the cutover in milliseconds; 0 if cutover did not complete. */
    @JsonProperty("duration_millis")
    private final long durationMillis;

    @JsonProperty("error_message")
    private final String errorMessage;

    /** Deserialize from XContent parser. */
    public static CutoverSummary fromXContent(org.opensearch.core.xcontent.XContentParser parser) throws IOException {
        return JacksonHelper.fromXContent(parser, CutoverSummary.class);
    }

    /**
     * Project a {@link CutoverContext} into a slim {@link CutoverSummary}.
     *
     * <p>The underlying {@link CutoverContext#durationMillis()} is computed as
     * {@code cutoverEndMillis - cutoverStartMillis} and is not internally clamped,
     * so it can be negative when the end timestamp is unset (cutover never
     * completed) or when clock skew makes the end precede the start. We clamp
     * to {@code >= 0} here so the wire value is always a non-negative duration
     * (with {@code 0} meaning "did not complete or no measurable time elapsed").</p>
     *
     * @param ctx full cutover context (may be null)
     * @return slim summary, or {@code null} if {@code ctx} is null
     */
    public static CutoverSummary from(CutoverContext ctx) {
        if (ctx == null) {
            return null;
        }
        return CutoverSummary.builder()
            .docCountValidationPassed(ctx.docCountValidationPassed())
            .aliasSwapSucceeded(ctx.aliasSwapSucceeded())
            .durationMillis(Math.max(0L, ctx.durationMillis()))
            .errorMessage(ctx.errorMessage())
            .build();
    }
}
