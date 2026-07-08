/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */
package com.atlassian.opensearch.aosc.statemachine;

import com.atlassian.opensearch.aosc.utils.jackson.JacksonToXContentObject;
import com.atlassian.opensearch.aosc.utils.jackson.JacksonWriteable;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Value;
import lombok.experimental.Accessors;
import lombok.extern.jackson.Jacksonized;

/**
 * Immutable record of a single phase execution in an {@link AwaitableStateMachine}.
 * Captures when the handler started and when it finished.
 *
 * @param <S> the state enum type
 */
@Value
@Jacksonized
@Builder
@AllArgsConstructor
@Accessors(fluent = true)
@JsonIgnoreProperties(ignoreUnknown = true)
public class TransitionRecord<S extends Enum<S>> implements JacksonToXContentObject, JacksonWriteable {

    @JsonProperty("phase")
    S phase;

    @JsonProperty("start_time_millis")
    long startTimeMillis;

    @JsonProperty("end_time_millis")
    long endTimeMillis;

    /** Duration in milliseconds. */
    public long durationMillis() {
        return endTimeMillis - startTimeMillis;
    }
}
