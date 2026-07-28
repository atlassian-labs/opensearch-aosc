/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */
package com.atlassian.opensearch.aosc.service.coordinator;

import com.atlassian.opensearch.aosc.model.CutoverContext;
import com.atlassian.opensearch.aosc.utils.AoscLogger;
import com.atlassian.opensearch.aosc.utils.AsyncClientHelper;
import com.atlassian.opensearch.aosc.utils.IndexOperationUtils;
import com.atlassian.opensearch.aosc.utils.LC;
import com.atlassian.opensearch.aosc.utils.MigrationAuditLogger;

import org.opensearch.action.admin.indices.refresh.RefreshRequest;
import org.opensearch.action.search.SearchRequest;
import org.opensearch.index.query.QueryBuilder;
import org.opensearch.index.query.QueryBuilders;
import org.opensearch.search.builder.SearchSourceBuilder;
import org.opensearch.transport.client.Client;

import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;

import static com.atlassian.opensearch.aosc.utils.AoscLogger.kv;

/**
 * Handles the cutover phase of an AOSC migration: pre-swap validation,
 * alias swap, and rollback on failure.
 *
 * <p>The cutover sequence is:</p>
 * <ol>
 *   <li>Refresh both source and target indices in parallel to ensure accurate doc counts</li>
 *   <li>Count documents in both indices in parallel</li>
 *   <li>Validate doc counts are within tolerance</li>
 *   <li>Swap the alias from source to target</li>
 * </ol>
 *
 * <p>On failure, the future completes exceptionally. Alias rollback is handled
 * by the caller ({@code MigrationCoordinator.onEnterFailing}), not by this service.</p>
 *
 * <p>Returns a {@link CutoverContext} with metadata about the cutover
 * (doc counts, timestamps, validation result).</p>
 */
public class CutoverService {

    private final AoscLogger logger;
    private final Client client;
    private final IndexOperationUtils indexOperationUtils;
    /** Used as the audit-log {@code migrationId} field. Required, non-empty. */
    private final String migrationId;

    public CutoverService(AoscLogger logger, Client client, IndexOperationUtils indexOperationUtils, String migrationId) {
        this.logger = Objects.requireNonNull(logger, "logger").forClass(CutoverService.class);
        this.client = Objects.requireNonNull(client, "client");
        this.indexOperationUtils = Objects.requireNonNull(indexOperationUtils, "indexOperationUtils");
        this.migrationId = Objects.requireNonNull(migrationId, "migrationId");
        if (migrationId.isEmpty()) {
            throw new IllegalArgumentException("migrationId must not be empty");
        }
    }

    /**
     * Execute the cutover sequence with an optional validation query that scopes doc-count checks.
     *
     * @param sourceIndex       source index name
     * @param targetIndex       target index name
     * @param alias             alias to swap
     * @param docCountTolerance maximum allowed absolute difference between source and target doc counts (0 = exact match)
     * @param validationQuery   optional query to filter doc counts; {@code null} counts all docs (match_all)
     */
    public CompletableFuture<CutoverContext> executeCutover(
        String sourceIndex,
        String targetIndex,
        String alias,
        int docCountTolerance,
        QueryBuilder validationQuery
    ) {
        Objects.requireNonNull(sourceIndex, "sourceIndex");
        Objects.requireNonNull(targetIndex, "targetIndex");
        Objects.requireNonNull(alias, "alias");

        long cutoverStartMillis = System.currentTimeMillis();
        logger.info(
            "Starting cutover",
            kv(LC.EVENT, "cutover_start"),
            kv(LC.SOURCE_INDEX, sourceIndex),
            kv(LC.TARGET_INDEX, targetIndex),
            kv(LC.ALIAS, alias),
            kv(LC.TOLERANCE, docCountTolerance)
        );
        MigrationAuditLogger.recordCutoverStarted(migrationId, sourceIndex, targetIndex);

        // Step 1: Refresh both indices in parallel to ensure accurate doc counts
        return CompletableFuture.allOf(refreshIndex(sourceIndex), refreshIndex(targetIndex))
            // Step 2: Count docs in both indices
            .thenCompose(
                v -> countDocs(sourceIndex, validationQuery).thenCombine(
                    countDocs(targetIndex, validationQuery),
                    (sourceCount, targetCount) -> validateDocCounts(sourceCount, targetCount, docCountTolerance, cutoverStartMillis)
                )
            )
            // Step 3: Swap alias from source to target
            .thenCompose(ctxBuilder -> indexOperationUtils.swapAlias(sourceIndex, targetIndex, alias).thenApply(v -> {
                long cutoverEndMillis = System.currentTimeMillis();
                logger.info("Alias swap completed: {} -> {}", sourceIndex, targetIndex);
                CutoverContext built = ctxBuilder.aliasSwapSucceeded(true).cutoverEndMillis(cutoverEndMillis).build();
                MigrationAuditLogger.recordCutoverCompleted(
                    migrationId,
                    sourceIndex,
                    targetIndex,
                    built.sourceDocCount(),
                    built.targetDocCount()
                );
                return built;
            }).exceptionally(swapError -> {
                logger.error("Alias swap failed", swapError);
                long cutoverEndMillis = System.currentTimeMillis();
                CutoverContext failedCtx = ctxBuilder.aliasSwapSucceeded(false)
                    .cutoverEndMillis(cutoverEndMillis)
                    .errorMessage(swapError.getMessage())
                    .build();
                MigrationAuditLogger.recordCutoverFailed(migrationId, sourceIndex, targetIndex, swapError);
                throw new CutoverFailedException("Alias swap failed during cutover: " + swapError.getMessage(), swapError, failedCtx);
            }));
    }

    // ---- Internal helpers ----

    private CutoverContext.CutoverContextBuilder validateDocCounts(
        long sourceCount,
        long targetCount,
        int docCountTolerance,
        long cutoverStartMillis
    ) {
        logger.info("Doc counts - source: {}, target: {}", sourceCount, targetCount);

        long diff = sourceCount > targetCount ? sourceCount - targetCount : targetCount - sourceCount;
        boolean valid = diff <= docCountTolerance;

        if (!valid) {
            String msg = String.format(
                Locale.ROOT,
                "Doc count validation failed: source=%d, target=%d, diff=%d, tolerance=%d",
                sourceCount,
                targetCount,
                diff,
                docCountTolerance
            );
            logger.error(msg);
            throw new DocCountValidationException(msg, sourceCount, targetCount, docCountTolerance);
        }

        logger.info(
            "Doc count validation passed",
            kv(LC.EVENT, "doc_count_validation"),
            kv(LC.SOURCE_COUNT, sourceCount),
            kv(LC.TARGET_COUNT, targetCount),
            kv(LC.DIFF, diff),
            kv(LC.TOLERANCE, docCountTolerance)
        );
        return CutoverContext.builder()
            .sourceDocCount(sourceCount)
            .targetDocCount(targetCount)
            .docCountTolerance(docCountTolerance)
            .docCountValidationPassed(true)
            .cutoverStartMillis(cutoverStartMillis);
    }

    /**
     * Refresh an index to make all documents searchable for accurate counts.
     */
    CompletableFuture<Void> refreshIndex(String index) {
        return AsyncClientHelper.executeRefreshAsync(client, new RefreshRequest(index)).thenApply(response -> {
            logger.debug("Refreshed index {}", index);
            return null;
        });
    }

    /**
     * Count documents in {@code index}, optionally filtered by a user-supplied validation query.
     * When {@code validationQuery} is {@code null}, counts all docs (match_all).
     */
    CompletableFuture<Long> countDocs(String index, QueryBuilder validationQuery) {
        QueryBuilder query = validationQuery != null ? validationQuery : QueryBuilders.matchAllQuery();
        SearchRequest searchRequest = new SearchRequest(index).source(new SearchSourceBuilder().query(query).size(0).trackTotalHits(true));
        return AsyncClientHelper.executeSearchAsync(client, searchRequest).thenApply(response -> response.getHits().getTotalHits().value());
    }

    // ---- Exception types ----

    /**
     * Thrown when doc count validation fails before alias swap.
     */
    public static class DocCountValidationException extends RuntimeException {
        private final long sourceDocCount;
        private final long targetDocCount;
        private final int tolerance;

        public DocCountValidationException(String message, long sourceDocCount, long targetDocCount, int tolerance) {
            super(message);
            this.sourceDocCount = sourceDocCount;
            this.targetDocCount = targetDocCount;
            this.tolerance = tolerance;
        }

        public long sourceDocCount() {
            return sourceDocCount;
        }

        public long targetDocCount() {
            return targetDocCount;
        }

        public int tolerance() {
            return tolerance;
        }
    }

    /**
     * Thrown when the alias swap itself fails (after doc count validation passed).
     * Contains the {@link CutoverContext} with metadata about the failed cutover.
     */
    public static class CutoverFailedException extends RuntimeException {
        private final CutoverContext cutoverContext;

        public CutoverFailedException(String message, Throwable cause, CutoverContext cutoverContext) {
            super(message, cause);
            this.cutoverContext = cutoverContext;
        }

        public CutoverContext cutoverContext() {
            return cutoverContext;
        }
    }
}
