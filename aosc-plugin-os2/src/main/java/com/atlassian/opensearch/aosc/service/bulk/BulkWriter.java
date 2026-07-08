/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */
package com.atlassian.opensearch.aosc.service.bulk;

import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/**
 * Writes documents from a {@link DocSource} to OpenSearch in batches.
 * Engines depend on this interface, not on concrete implementations.
 *
 * <p>The {@link DocSource} passed to {@link #consumeAsync} must be thread-safe
 * ({@code poll()} and {@code returnForRetry()} may be called from different threads).
 */
public interface BulkWriter {
    <M> CompletableFuture<Void> consumeAsync(DocSource<M> source, Consumer<PreparedBatch<M>> progressCallback);

    void cancel();
}
