/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */
package com.atlassian.opensearch.aosc.action.list;

import com.atlassian.opensearch.aosc.model.MigrationDocument;
import com.atlassian.opensearch.aosc.model.MigrationSummary;
import com.atlassian.opensearch.aosc.model.phase.CoordinatorPhase;
import com.atlassian.opensearch.aosc.service.coordinator.AoscCoordinatorService;
import com.atlassian.opensearch.aosc.service.coordinator.MigrationDocumentService;
import com.atlassian.opensearch.aosc.utils.AoscLogger;

import org.opensearch.action.support.ActionFilters;
import org.opensearch.action.support.clustermanager.TransportClusterManagerNodeAction;
import org.opensearch.cluster.ClusterState;
import org.opensearch.cluster.block.ClusterBlockException;
import org.opensearch.cluster.metadata.IndexNameExpressionResolver;
import org.opensearch.cluster.service.ClusterService;
import org.opensearch.common.inject.Inject;
import org.opensearch.core.action.ActionListener;
import org.opensearch.core.common.io.stream.StreamInput;
import org.opensearch.threadpool.ThreadPool;
import org.opensearch.transport.TransportService;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Transport handler for {@link ListMigrationsAction}.
 *
 * <p>Extends {@link TransportClusterManagerNodeAction} — requests are routed to the
 * cluster manager node where the coordinator cache lives.</p>
 *
 * <p>Read path:</p>
 * <ol>
 *   <li>List all migrations from Tier-1 ({@code .aosc-migrations} index).</li>
 *   <li>For non-terminal migrations, overlay live data from coordinator cache
 *       (authoritative phase + shard progress).</li>
 *   <li>Append any active coordinator-cache migrations not yet present in Tier-1.</li>
 *   <li>Truncate the merged result to {@code size}, recording whether truncation
 *       actually occurred.</li>
 *   <li>Project each {@link MigrationDocument} into a slim {@link MigrationSummary}
 *       for the wire response.</li>
 * </ol>
 *
 * <p>The {@code size} parameter applies to the <b>merged</b> result set
 * (terminal Tier-1 history + active coordinator cache), not just one of them.
 * Operators can always rely on {@code active_count} in the response to see the
 * true number of in-flight migrations even when the page is dominated by
 * terminal history.</p>
 */
public class TransportListMigrationsAction extends TransportClusterManagerNodeAction<ListMigrationsRequest, ListMigrationsResponse> {

    private final AoscLogger logger;

    private final AoscCoordinatorService coordinatorService;
    private final MigrationDocumentService migrationDocumentService;

    @Inject
    public TransportListMigrationsAction(
        TransportService transportService,
        ClusterService clusterService,
        ThreadPool threadPool,
        ActionFilters actionFilters,
        IndexNameExpressionResolver indexNameExpressionResolver,
        AoscCoordinatorService coordinatorService,
        MigrationDocumentService migrationDocumentService
    ) {
        super(
            ListMigrationsAction.NAME,
            transportService,
            clusterService,
            threadPool,
            actionFilters,
            ListMigrationsRequest::new,
            indexNameExpressionResolver
        );
        this.coordinatorService = Objects.requireNonNull(coordinatorService, "coordinatorService");
        this.migrationDocumentService = Objects.requireNonNull(migrationDocumentService, "migrationDocumentService");
        this.logger = AoscLogger.create(TransportListMigrationsAction.class);
    }

    @Override
    protected String executor() {
        return ThreadPool.Names.SAME;
    }

    @Override
    protected ListMigrationsResponse read(StreamInput in) throws IOException {
        return new ListMigrationsResponse(in);
    }

    @Override
    protected void clusterManagerOperation(
        ListMigrationsRequest request,
        ClusterState state,
        ActionListener<ListMigrationsResponse> listener
    ) {
        final int requestedSize = request.body().size();
        final List<CoordinatorPhase> statusFilter = request.body().statusFilter();

        // 1. List all migrations from Tier-1.
        //
        // Tier-1 failures (e.g. .aosc-migrations index RED, search rejection, security exception) are propagated to
        // the caller via ActionListener.onFailure() — same contract as the other read actions (Get, Status). The
        // alternative of silently substituting an empty list and continuing the merge would mask real terminal-history
        // outages: an operator running `_list?status=COMPLETED` during a Tier-1 outage would see an empty result and
        // wrongly conclude the migration "was lost", with no indication that the index was unavailable. Failing
        // explicitly is the safer behavior.
        migrationDocumentService.listMigrations(statusFilter, requestedSize).whenComplete((docs, ex) -> {
            if (ex != null) {
                logger.error("Tier-1 list failed; propagating to caller as 5xx", ex);
                listener.onFailure(ex instanceof Exception ? (Exception) ex : new RuntimeException(ex));
                return;
            }

            // 2. For non-terminal migrations, overlay live data from coordinator cache
            List<MigrationDocument> merged = new ArrayList<>(docs.size());
            Set<String> seenSourceIndices = new HashSet<>();
            for (MigrationDocument doc : docs) {
                seenSourceIndices.add(doc.sourceIndex());
                if (!CoordinatorPhase.TERMINALS.contains(doc.phase())) {
                    MigrationDocument liveDoc = coordinatorService.getActiveMigrationDocument(doc.sourceIndex());
                    if (liveDoc != null) {
                        merged.add(liveDoc);
                        continue;
                    }
                }
                merged.add(doc);
            }

            // 3. Add active migrations not yet in Tier-1 (index not created yet, or doc not indexed)
            final Collection<MigrationDocument> activeMigrations = coordinatorService.getAllActiveMigrationDocuments();
            for (MigrationDocument activeMigration : activeMigrations) {
                if (seenSourceIndices.contains(activeMigration.sourceIndex())) {
                    continue;
                }
                if (statusFilter.isEmpty() || statusFilter.contains(activeMigration.phase())) {
                    merged.add(activeMigration);
                    seenSourceIndices.add(activeMigration.sourceIndex());
                }
            }

            // 4. Truncate at requested size. Compute `truncated` BEFORE trimming so
            // the signal reflects an actual server-side drop (not coincidental size match).
            final boolean truncated = merged.size() > requestedSize;
            if (truncated) {
                merged = new ArrayList<>(merged.subList(0, requestedSize));
            }

            // 5. Project to slim summaries for the response.
            final List<MigrationSummary> summaries = new ArrayList<>(merged.size());
            for (MigrationDocument doc : merged) {
                summaries.add(MigrationSummary.from(doc));
            }

            // active_count is authoritative from the coordinator cache, independent
            // of any filter/cap applied to the listed page.
            final int activeCount = activeMigrations.size();

            listener.onResponse(
                new ListMigrationsResponse(
                    ListMigrationsResult.builder().migrations(summaries).truncated(truncated).activeCount(activeCount).build()
                )
            );
        });
    }

    @Override
    protected ClusterBlockException checkBlock(ListMigrationsRequest request, ClusterState state) {
        // List is a read-only operation — don't block on metadata writes.
        return null;
    }
}
