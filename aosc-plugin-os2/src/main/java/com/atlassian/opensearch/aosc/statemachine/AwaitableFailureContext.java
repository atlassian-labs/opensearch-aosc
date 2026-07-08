/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */
package com.atlassian.opensearch.aosc.statemachine;

import lombok.Value;
import lombok.experimental.Accessors;

/**
 * Failure context passed to the onFailure handler of an {@link AwaitableStateMachine}.
 *
 * @param <S> the state enum type
 */
@Value
@Accessors(fluent = true)
public class AwaitableFailureContext<S extends Enum<S>> {
    S failedInState;
    Exception cause;
    AwaitableStateMachine<S> sm;

    public String message() {
        return cause != null && cause.getMessage() != null ? cause.getMessage() : "unknown";
    }
}
