/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */
package com.atlassian.opensearch.aosc.service.adaptive;

import com.atlassian.opensearch.aosc.service.bulk.ConcurrentBulkWriter;

/**
 * Controls batch sizing for bulk writes. Implementations may adapt the batch
 * size based on observed outcomes (e.g. AIMD), or return a fixed value.
 *
 * <h3>Contract</h3>
 * <ul>
 *   <li>{@link #nextBatchSize()} returns the recommended document count for the
 *       next bulk request. Always returns a positive integer.</li>
 *   <li>{@link #observe(BulkOutcome)} is <em>advisory</em>: adaptive implementations
 *       use it to adjust future batch sizes; fixed-size implementations may no-op.
 *       Callers must not assume that calling {@code observe()} will change the value
 *       returned by {@code nextBatchSize()}.</li>
 * </ul>
 *
 * <h3>Thread safety</h3>
 * <p>Implementations are designed for single-threaded use within one
 * {@link ConcurrentBulkWriter} loop per shard.
 * No concurrent access is expected or required.</p>
 */
public interface BatchSizeController {

    /**
     * Returns the recommended document count for the next batch.
     * Implementations must never throw.
     *
     * @return a positive integer representing the batch size in documents
     */
    int nextBatchSize();

    /**
     * Observe the outcome of a completed bulk write. Adaptive implementations
     * use this feedback to adjust future batch sizes. Fixed-size implementations
     * may safely ignore this call (default: no-op). Implementations must never throw.
     *
     * @param outcome the bulk outcome to observe; never null
     */
    default void observe(BulkOutcome outcome) {
        // No-op by default — fixed-size controllers need not override.
    }

}
