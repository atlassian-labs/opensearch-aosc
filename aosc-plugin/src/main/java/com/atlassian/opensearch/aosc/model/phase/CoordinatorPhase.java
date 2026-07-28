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
 * Coordinator lifecycle phases. Persisted in cluster state (Tier 0)
 * as part of AoscMigrationsClusterState.Entry.
 *
 * 11 states: linear pipeline plus cancel/fail edges.
 */
public enum CoordinatorPhase {
    INITIALIZING,
    ACTIVE,
    PREPARING_TARGET,
    CUTTING_OVER,
    CATCHING_UP,
    COMPLETING,
    COMPLETED,
    CANCELLING,
    CANCELLED,
    FAILING,
    FAILED;

    /** Immutable set of terminal states. */
    public static final Set<CoordinatorPhase> TERMINALS = Set.of(COMPLETED, CANCELLED, FAILED);

    /** Returns true if this is a terminal state. */
    public boolean isTerminal() {
        return TERMINALS.contains(this);
    }
}
