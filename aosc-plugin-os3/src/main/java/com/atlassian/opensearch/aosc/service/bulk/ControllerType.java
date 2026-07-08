/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */
package com.atlassian.opensearch.aosc.service.bulk;

import java.util.Locale;

/** Bulk write controller strategy selection. */
public enum ControllerType {
    /** Fixed batch size and concurrency from settings — no adaptation. */
    FIXED,
    /** Fixed concurrency from settings, AIMD-adaptive batch sizing. */
    ADAPTIVE_BATCH,
    /** Adaptive concurrency (gradient-based) + adaptive batch sizing. Backfill only. */
    ADAPTIVE;

    public static ControllerType fromString(String value) {
        return valueOf(value.toUpperCase(Locale.ROOT));
    }
}
