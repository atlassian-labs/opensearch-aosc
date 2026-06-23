/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */
package com.atlassian.opensearch.aosc;

import com.atlassian.opensearch.aosc.model.MigrationRequestOptions;

import org.opensearch.common.settings.Settings;

/** Shared test helpers for AOSC unit tests. */
public final class AoscTestUtil {
    /** Returns a fully-populated {@link MigrationRequestOptions} with sensible defaults for tests. */
    public static MigrationRequestOptions defaultMigrationOptions() {
        return new MigrationRequestOptions().setConvergenceThresholdPerShard(AoscSettings.CONVERGENCE_THRESHOLD.getDefault(Settings.EMPTY))
            .setMaxConvergenceRoundsPerShard(AoscSettings.MAX_CONVERGENCE_ROUNDS.getDefault(Settings.EMPTY))
            .setDocCountTolerance(0)
            .setAcceptDataLossIfCustomRoutingIsUsed(false)
            .setTransientTargetSettings(
                AoscSettings.parseTransientTargetSettings(AoscSettings.TRANSIENT_TARGET_SETTINGS.getDefault(Settings.EMPTY))
            );
    }

    private AoscTestUtil() {}
}
