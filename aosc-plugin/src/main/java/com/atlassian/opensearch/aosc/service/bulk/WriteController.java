/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */
package com.atlassian.opensearch.aosc.service.bulk;

import com.atlassian.opensearch.aosc.service.adaptive.BulkOutcome;

/**
 * Controls batch sizing, concurrency, and byte budget for bulk writes.
 *
 * <p>The "brain" of the write pipeline. All three knobs (batch size, concurrency W,
 * and byte budget) are owned by a single controller implementation.</p>
 *
 * <h3>Thread safety</h3>
 * Implementations must be thread-safe — {@link #handleOutcome} is called from
 * concurrent async callbacks while {@link #nextBatchSize()} is called from the
 * reader thread.
 */
public interface WriteController {

    /** Recommended document count for the next batch. Always positive. */
    int nextBatchSize();

    /**
     * Decide what to do based on a completed bulk outcome.
     *
     * @param outcome the observed bulk result; never null
     * @return the decision (success, pause+retry, or fatal)
     */
    WriteDecision handleOutcome(BulkOutcome outcome);

    /** Current desired concurrency (W). */
    int concurrency();

    /** Current byte budget per batch (bytes). */
    long maxBatchBytes();
}
