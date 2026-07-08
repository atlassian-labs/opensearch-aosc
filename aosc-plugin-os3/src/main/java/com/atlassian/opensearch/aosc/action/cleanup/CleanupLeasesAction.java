/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */
package com.atlassian.opensearch.aosc.action.cleanup;

import com.atlassian.opensearch.aosc.service.worker.RetentionLeaseManager;

import org.opensearch.action.ActionType;

/**
 * Action to discover and release orphaned AOSC retention leases.
 *
 * <p>OpenSearch only exposes retention lease management via internal transport actions
 * ({@code RetentionLeaseActions.Add}, {@code Renew}, {@code Remove}). When an AOSC
 * migration fails catastrophically (e.g., node crash before {@code release()} runs),
 * the retention leases it acquired remain on the source shards, preventing translog
 * trimming and segment merging.
 *
 * <p>This action enumerates leases on the requested indices, filters down to those
 * whose ID begins with {@code aosc-migration-} (the
 * {@link RetentionLeaseManager#LEASE_ID_PREFIX}),
 * and either lists them ({@code dry_run=true}) or removes them.
 */
public class CleanupLeasesAction extends ActionType<CleanupLeasesResponse> {

    public static final CleanupLeasesAction INSTANCE = new CleanupLeasesAction();
    public static final String NAME = "cluster:admin/aosc/leases/cleanup";

    private CleanupLeasesAction() {
        super(NAME, CleanupLeasesResponse::new);
    }
}
