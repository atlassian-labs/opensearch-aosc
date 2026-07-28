/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */
package com.atlassian.opensearch.aosc.action.start.validation;

import org.opensearch.action.search.SearchRequest;
import org.opensearch.core.action.ActionListener;
import org.opensearch.index.query.QueryBuilder;
import org.opensearch.search.builder.SearchSourceBuilder;

import java.util.concurrent.CompletableFuture;

/**
 * Async validator that parses the user-supplied {@code validation_query} and
 * dry-runs it against both source and target indices at {@code _start} time.
 * Catches malformed JSON, unknown query types, and field-mapping mismatches
 * as a 400 immediately rather than failing hours later at cutover.
 * No-op when {@code validation_query} is absent.
 */
public final class ValidationQueryValidator implements AsyncMigrationStartValidator {
    @Override
    public CompletableFuture<Void> validate(ValidationContext ctx) {
        if (ctx.request().getOptions() == null) {
            return CompletableFuture.completedFuture(null);
        }

        QueryBuilder query;
        try {
            query = ctx.request().getOptions().getValidationQueryBuilder();
        } catch (Exception e) {
            return CompletableFuture.failedFuture(e);
        }
        if (query == null) {
            return CompletableFuture.completedFuture(null);
        }

        CompletableFuture<Void> srcFuture = dryRunSearch(ctx, ctx.request().getSourceIndex(), query);
        CompletableFuture<Void> tgtFuture = dryRunSearch(ctx, ctx.request().getTargetIndex(), query);
        return CompletableFuture.allOf(srcFuture, tgtFuture);
    }

    private static CompletableFuture<Void> dryRunSearch(ValidationContext ctx, String index, QueryBuilder query) {
        CompletableFuture<Void> future = new CompletableFuture<>();
        SearchRequest req = new SearchRequest(index).source(new SearchSourceBuilder().query(query).size(0));
        ctx.client()
            .search(
                req,
                ActionListener.wrap(
                    response -> future.complete(null),
                    ex -> future.completeExceptionally(
                        new IllegalArgumentException("validation_query failed on index [" + index + "]: " + ex.getMessage(), ex)
                    )
                )
            );
        return future;
    }
}
