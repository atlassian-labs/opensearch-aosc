/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */
package com.atlassian.opensearch.aosc.service.bulk;

/**
 * Outcome decision from a {@link WriteController} after observing a bulk write result.
 */
@lombok.Value
@lombok.experimental.Accessors(fluent = true)
public class WriteDecision {

    public enum Action {
        SUCCESS,
        PAUSE_AND_RETRY,
        FATAL
    }

    Action action;
    long pauseMillis;
    String reason;

    public static WriteDecision success() {
        return new WriteDecision(Action.SUCCESS, 0, null);
    }

    public static WriteDecision pauseAndRetry(long pauseMillis) {
        return new WriteDecision(Action.PAUSE_AND_RETRY, pauseMillis, null);
    }

    public static WriteDecision fatal(String reason) {
        return new WriteDecision(Action.FATAL, 0, reason);
    }
}
