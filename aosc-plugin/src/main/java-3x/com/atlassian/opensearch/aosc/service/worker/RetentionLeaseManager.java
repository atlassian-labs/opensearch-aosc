/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */
package com.atlassian.opensearch.aosc.service.worker;

import com.atlassian.opensearch.aosc.utils.AoscLogger;
import com.atlassian.opensearch.aosc.utils.AsyncClientHelper;
import com.atlassian.opensearch.aosc.utils.AsyncUtils;
import com.atlassian.opensearch.aosc.utils.MigrationAuditLogger;

import org.opensearch.core.index.shard.ShardId;
import org.opensearch.index.seqno.RetentionLeaseActions;
import org.opensearch.index.seqno.RetentionLeaseAlreadyExistsException;
import org.opensearch.index.seqno.RetentionLeaseNotFoundException;
import org.opensearch.transport.client.Client;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;

/**
 * Manages retention leases for a single shard migration.
 *
 * <p>Each {@code ShardMigrationWorker} creates one {@code RetentionLeaseManager}
 * for its shard. The manager handles acquire, renew, and release of the
 * retention lease that prevents translog trimming during replay.</p>
 *
 * <p>Lease operations use transport actions ({@link RetentionLeaseActions})
 * which route to the shard's primary node automatically.</p>
 */
public class RetentionLeaseManager {

    /** Prefix for all retention leases acquired by AOSC migrations.
     *  Used by {@code TransportCleanupLeasesAction} to filter AOSC-owned leases. */
    public static final String LEASE_ID_PREFIX = "aosc-migration-";

    /** Source string recorded on every AOSC retention lease. */
    public static final String LEASE_SOURCE = "aosc-migration";

    private final Client client;
    private final ShardId shardId;
    private final String migrationId;
    private final String leaseId;
    private final AoscLogger logger;

    /**
     * @param logger      structured logger with migration/shard context
     * @param client      the OpenSearch client for executing lease actions
     * @param shardId     the source shard to hold a lease on
     * @param migrationId the migration identifier (used to generate a deterministic lease ID)
     */
    public RetentionLeaseManager(AoscLogger logger, Client client, ShardId shardId, String migrationId) {
        this.logger = Objects.requireNonNull(logger, "logger").forClass(RetentionLeaseManager.class);
        this.client = Objects.requireNonNull(client, "client");
        this.shardId = Objects.requireNonNull(shardId, "shardId");
        this.migrationId = Objects.requireNonNull(migrationId, "migrationId");
        this.leaseId = generateLeaseId(migrationId, shardId.id());
    }

    /**
     * Acquire a retention lease at the given sequence number.
     *
     * <p>Idempotent: if the lease already exists (e.g., due to handler re-entry from
     * {@code resume()}), the {@link RetentionLeaseAlreadyExistsException} is swallowed.
     * The existing lease is ours (the ID includes the migration UUID), and its retaining
     * seqNo is at least as high as what we'd set — periodic {@link #renew} keeps it
     * current.</p>
     */
    public CompletableFuture<Void> acquire(long retainingSeqNo) {
        RetentionLeaseActions.AddRequest req = new RetentionLeaseActions.AddRequest(shardId, leaseId, retainingSeqNo, LEASE_SOURCE);
        logger.info("Acquiring retention lease at seqNo [{}]", retainingSeqNo);
        return AsyncClientHelper.executeAsync(client, RetentionLeaseActions.Add.INSTANCE, req).thenApply(r -> {
            MigrationAuditLogger.recordLeaseAcquired(migrationId, shardId.id(), retainingSeqNo);
            return (Void) null;
        }).exceptionally(e -> {
            if (AsyncUtils.hasCauseOfType(e, RetentionLeaseAlreadyExistsException.class)) {
                logger.info("Retention lease already exists (re-entry), proceeding");
                MigrationAuditLogger.recordLeaseAcquired(migrationId, shardId.id(), retainingSeqNo);
                return null;
            }
            throw (e instanceof RuntimeException) ? (RuntimeException) e : new RuntimeException(e);
        });
    }

    /**
     * Renew the retention lease, advancing the retaining sequence number.
     * Called periodically during replay/convergence to allow translog trimming
     * of already-replayed operations.
     *
     * <p>If the lease no longer exists (e.g., already released by the COMPLETED/FAILING
     * cleanup path, or removed by shard relocation), the {@link RetentionLeaseNotFoundException}
     * is swallowed and logged at DEBUG level. This keeps renewal idempotent and avoids
     * log noise from expected post-completion renewal ticks.</p>
     */
    public CompletableFuture<Void> renew(long retainingSeqNo) {
        RetentionLeaseActions.RenewRequest req = new RetentionLeaseActions.RenewRequest(shardId, leaseId, retainingSeqNo, LEASE_SOURCE);
        logger.debug("Renewing retention lease at seqNo [{}]", retainingSeqNo);
        return AsyncClientHelper.executeAsync(client, RetentionLeaseActions.Renew.INSTANCE, req)
            .thenApply(r -> (Void) null)
            .exceptionally(e -> {
                if (AsyncUtils.hasCauseOfType(e, RetentionLeaseNotFoundException.class)) {
                    logger.debug("Retention lease not found during renewal (already released), ignoring");
                    return null;
                }
                throw (e instanceof RuntimeException) ? (RuntimeException) e : new RuntimeException(e);
            });
    }

    /**
     * Release the retention lease. Called when the worker completes, fails,
     * or is cancelled. Best-effort — the returned future always completes
     * successfully (failures are logged but swallowed).
     */
    public CompletableFuture<Void> release() {
        RetentionLeaseActions.RemoveRequest req = new RetentionLeaseActions.RemoveRequest(shardId, leaseId);
        logger.info("Releasing retention lease");
        return AsyncClientHelper.executeAsync(client, RetentionLeaseActions.Remove.INSTANCE, req).thenApply(r -> {
            MigrationAuditLogger.recordLeaseReleased(migrationId, shardId.id(), false, null);
            return (Void) null;
        }).exceptionally(e -> {
            logger.warn("Failed to release retention lease, best-effort", e);
            MigrationAuditLogger.recordLeaseReleased(migrationId, shardId.id(), true, e);
            return null;
        });
    }

    /**
     * Generate a deterministic lease ID for a migration + shard combination.
     */
    static String generateLeaseId(String migrationId, int shardNum) {
        return LEASE_ID_PREFIX + migrationId + "-" + shardNum;
    }

    /**
     * @return the lease ID for this manager
     */
    public String leaseId() {
        return leaseId;
    }
}
