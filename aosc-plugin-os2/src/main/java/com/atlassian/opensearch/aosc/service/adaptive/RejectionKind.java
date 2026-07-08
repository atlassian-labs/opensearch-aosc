/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */
package com.atlassian.opensearch.aosc.service.adaptive;

/**
 * Classification of bulk request failures to guide adaptive batch size decisions.
 */
public enum RejectionKind {
    /**
     * Request succeeded, or every per-item failure was ignorable (currently 409
     * version conflicts, which are expected during backfill). Treated as success.
     */
    NONE,

    /**
     * Overload rejection: target indexing pressure, 429 Too Many Requests,
     * write threadpool rejection, circuit breaker trip, or transport/HTTP
     * payload-size overflow. Batch size should decrease.
     */
    OVERLOAD,

    /**
     * Transient failure: network blips, generic 5xx errors, timeouts.
     * Retry may succeed; do not adjust batch size.
     */
    TRANSIENT,

    /**
     * Fatal error: 4xx (except 429 and 409) — mapping/parse errors, malformed requests.
     * Do not retry; do not adjust batch size.
     */
    FATAL
}
