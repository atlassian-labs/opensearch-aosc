/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */
package com.atlassian.opensearch.aosc.model.phase;

import java.util.Set;

/**
 * Per-shard worker lifecycle phases. Persisted in cluster state (Tier 0)
 * as part of AoscMigrationsClusterState.ShardMigrationClusterState.
 *
 * 12 states: linear pipeline with convergence loop, plus cancel/fail edges.
 */
public enum ShardPhase {
    PENDING,
    ACQUIRING_LEASE,
    BACKFILLING,
    REPLAYING,
    CONVERGING,
    CONVERGED,
    CATCHING_UP,
    COMPLETING,
    COMPLETED,
    CANCELLING,
    FAILING,
    CANCELLED,
    FAILED;

    /** Terminal phases: no further transitions are possible. */
    public static final Set<ShardPhase> TERMINALS = Set.of(COMPLETED, CANCELLED, FAILED);

    /** Returns true if this is a terminal state (no further transitions). */
    public boolean isTerminal() {
        return TERMINALS.contains(this);
    }

}
