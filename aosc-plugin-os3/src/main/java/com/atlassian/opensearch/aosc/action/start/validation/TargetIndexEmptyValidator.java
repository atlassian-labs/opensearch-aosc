/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */
package com.atlassian.opensearch.aosc.action.start.validation;

import org.opensearch.action.admin.indices.stats.IndicesStatsRequest;
import org.opensearch.core.action.ActionListener;

import java.util.concurrent.CompletableFuture;

/**
 * Async: verifies the target index is empty (zero documents).
 */
public final class TargetIndexEmptyValidator implements AsyncMigrationStartValidator {
    @Override
    public CompletableFuture<Void> validate(ValidationContext ctx) {
        CompletableFuture<Void> future = new CompletableFuture<>();
        IndicesStatsRequest statsRequest = new IndicesStatsRequest().indices(ctx.request().getTargetIndex()).docs(true);
        ctx.client().admin().indices().stats(statsRequest, ActionListener.wrap(statsResponse -> {
            long docCount = statsResponse.getTotal().getDocs() != null ? statsResponse.getTotal().getDocs().getCount() : 0;
            if (docCount > 0) {
                future.completeExceptionally(
                    new IllegalStateException(
                        "Migration precondition check failed: target index ["
                            + ctx.request().getTargetIndex()
                            + "] is not empty ("
                            + docCount
                            + " docs); use an empty target index"
                    )
                );
            } else {
                future.complete(null);
            }
        }, future::completeExceptionally));
        return future;
    }
}
