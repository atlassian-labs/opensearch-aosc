/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */
package com.atlassian.opensearch.aosc.service.adaptive;

import com.atlassian.opensearch.aosc.AoscSettings;

import lombok.Builder;

import org.opensearch.common.settings.ClusterSettings;

import java.util.function.DoubleSupplier;
import java.util.function.IntSupplier;
import java.util.function.LongSupplier;

/**
 * Configuration for the AIMD (Additive-Increase Multiplicative-Decrease)
 * adaptive batch sizing algorithm.
 *
 * <p>All tunables are stored as suppliers so that dynamic cluster setting
 * changes take effect immediately on already-running shard workers.</p>
 *
 * <p>Construct via {@link #defaults(ClusterSettings, boolean)} to read from cluster settings,
 * or use the Lombok {@code builder()} directly for tests.</p>
 */
@Builder(toBuilder = true)
public class AimdConfig {

    private final boolean enabled;
    @Builder.Default
    private final LongSupplier minTargetBytes = () -> 0L;
    @Builder.Default
    private final LongSupplier maxTargetBytes = () -> 0L;
    @Builder.Default
    private final IntSupplier maxDocs = () -> 0;
    @Builder.Default
    private final LongSupplier startBytesPerDoc = () -> 0L;
    @Builder.Default
    private final DoubleSupplier increaseRatio = () -> 0.0;
    @Builder.Default
    private final IntSupplier increaseThreshold = () -> 0;
    @Builder.Default
    private final IntSupplier cooldownTicks = () -> 0;
    @Builder.Default
    private final DoubleSupplier trialRevertThreshold = () -> 0.0;
    @Builder.Default
    private final LongSupplier minStepBytes = () -> 0L;

    public boolean isEnabled() {
        return enabled;
    }

    public long getMinTargetBytes() {
        return minTargetBytes.getAsLong();
    }

    public long getMaxTargetBytes() {
        return maxTargetBytes.getAsLong();
    }

    public int getMaxDocs() {
        return maxDocs.getAsInt();
    }

    public long getStartBytesPerDoc() {
        return startBytesPerDoc.getAsLong();
    }

    public double getIncreaseRatio() {
        return increaseRatio.getAsDouble();
    }

    public int getIncreaseThreshold() {
        return increaseThreshold.getAsInt();
    }

    public int getCooldownTicks() {
        return cooldownTicks.getAsInt();
    }

    public double getTrialRevertThreshold() {
        return trialRevertThreshold.getAsDouble();
    }

    public long getMinStepBytes() {
        return minStepBytes.getAsLong();
    }

    /**
     * Returns a configuration backed by live cluster settings for the given phase.
     */
    public static AimdConfig defaults(ClusterSettings cs, boolean isBackfill) {
        return AimdConfig.builder()
            .enabled(true)
            .minTargetBytes(
                () -> cs.get(isBackfill ? AoscSettings.BACKFILL_BATCH_MIN_BYTES : AoscSettings.REPLAY_BATCH_MIN_BYTES).getBytes()
            )
            .maxTargetBytes(
                () -> cs.get(isBackfill ? AoscSettings.BACKFILL_BATCH_MAX_BYTES : AoscSettings.REPLAY_BATCH_MAX_BYTES).getBytes()
            )
            .maxDocs(() -> cs.get(isBackfill ? AoscSettings.BACKFILL_BATCH_MAX_DOCS : AoscSettings.REPLAY_BATCH_MAX_DOCS))
            .startBytesPerDoc(
                () -> cs.get(isBackfill ? AoscSettings.BACKFILL_BATCH_START_BPD : AoscSettings.REPLAY_BATCH_START_BPD).getBytes()
            )
            .increaseRatio(() -> cs.get(isBackfill ? AoscSettings.BACKFILL_AIMD_INCREASE_RATIO : AoscSettings.REPLAY_AIMD_INCREASE_RATIO))
            .increaseThreshold(
                () -> cs.get(isBackfill ? AoscSettings.BACKFILL_AIMD_INCREASE_THRESHOLD : AoscSettings.REPLAY_AIMD_INCREASE_THRESHOLD)
            )
            .cooldownTicks(() -> cs.get(isBackfill ? AoscSettings.BACKFILL_AIMD_COOLDOWN_TICKS : AoscSettings.REPLAY_AIMD_COOLDOWN_TICKS))
            .trialRevertThreshold(
                () -> cs.get(isBackfill ? AoscSettings.BACKFILL_AIMD_TRIAL_REVERT : AoscSettings.REPLAY_AIMD_TRIAL_REVERT)
            )
            .minStepBytes(
                () -> cs.get(isBackfill ? AoscSettings.BACKFILL_AIMD_MIN_STEP_BYTES : AoscSettings.REPLAY_AIMD_MIN_STEP_BYTES).getBytes()
            )
            .build();
    }
}
