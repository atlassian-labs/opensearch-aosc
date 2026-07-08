/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */
package com.atlassian.opensearch.aosc;

import com.atlassian.opensearch.aosc.service.bulk.ControllerType;
import com.atlassian.opensearch.aosc.utils.jackson.JacksonHelper;

import org.opensearch.common.settings.Setting;
import org.opensearch.common.unit.TimeValue;
import org.opensearch.core.common.unit.ByteSizeUnit;
import org.opensearch.core.common.unit.ByteSizeValue;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Cluster-level settings for the AOSC plugin.
 *
 * <p>Controller settings are grouped by phase ({@code backfill} / {@code replay}) and
 * sub-grouped by concern ({@code batch}, {@code overload}, {@code aimd}, {@code concurrency}).
 * The {@code aosc.<phase>.controller.type} setting selects which controller reads which
 * sub-group — unused settings are simply ignored.</p>
 *
 * <p>Precedence: API option &gt; cluster setting &gt; hardcoded default.</p>
 */
public final class AoscSettings {

    // ---- Non-controller settings (convergence, liveness, sharding) ----

    public static final Setting<Integer> CONVERGENCE_THRESHOLD = Setting.intSetting(
        "aosc.defaults.convergence_threshold",
        500,
        0,
        1_000_000,
        Setting.Property.Dynamic,
        Setting.Property.NodeScope
    );

    public static final Setting<Integer> MAX_CONVERGENCE_ROUNDS = Setting.intSetting(
        "aosc.defaults.max_convergence_rounds",
        1000,
        1,
        100_000,
        Setting.Property.Dynamic,
        Setting.Property.NodeScope
    );

    public static final Setting<TimeValue> LIVENESS_CHECK_INTERVAL = Setting.timeSetting(
        "aosc.liveness.check_interval",
        TimeValue.timeValueSeconds(30),
        TimeValue.timeValueSeconds(5),
        Setting.Property.Dynamic,
        Setting.Property.NodeScope
    );

    public static final Setting<TimeValue> LIVENESS_TIMEOUT = Setting.timeSetting(
        "aosc.liveness.timeout",
        TimeValue.timeValueSeconds(300),
        TimeValue.timeValueSeconds(10),
        Setting.Property.Dynamic,
        Setting.Property.NodeScope
    );

    public static final Setting<TimeValue> TARGET_READY_TIMEOUT = Setting.timeSetting(
        "aosc.target.ready_timeout",
        TimeValue.timeValueHours(4),
        TimeValue.timeValueMinutes(1),
        Setting.Property.Dynamic,
        Setting.Property.NodeScope
    );

    /**
     * Maximum shard workers per node that may hold a backfill permit simultaneously.
     * 0 = paused (emergency brake).
     */
    public static final Setting<Integer> MAX_CONCURRENT_PER_NODE = Setting.intSetting(
        "aosc.backfill.max_concurrent_per_node",
        2,
        0,
        1000,
        Setting.Property.Dynamic,
        Setting.Property.NodeScope
    );

    private static final String TRANSIENT_TARGET_SETTINGS_DEFAULT =
        "{\"index.number_of_replicas\":\"0\",\"index.refresh_interval\":\"-1\"}";

    public static final Setting<String> TRANSIENT_TARGET_SETTINGS = Setting.simpleString(
        "aosc.defaults.transient_target_settings",
        TRANSIENT_TARGET_SETTINGS_DEFAULT,
        AoscSettings::validateTransientTargetSettings,
        Setting.Property.Dynamic,
        Setting.Property.NodeScope
    );

    /** Cluster-wide default for whether to remove the source index write-block after a successful migration. */
    public static final Setting<Boolean> REMOVE_SOURCE_WRITE_BLOCK_ON_SUCCESS = Setting.boolSetting(
        "aosc.defaults.remove_source_write_block_on_success",
        false,
        Setting.Property.Dynamic,
        Setting.Property.NodeScope
    );

    public static final Setting<Integer> OVERLOAD_MAX_CONSECUTIVE_FAILURES = Setting.intSetting(
        "aosc.overload.max_consecutive_failures",
        50,
        1,
        10_000,
        Setting.Property.Dynamic,
        Setting.Property.NodeScope
    );

    // ==== Controller type selectors ====

    public static final Setting<String> BACKFILL_CONTROLLER_TYPE = Setting.simpleString(
        "aosc.backfill.controller.type",
        "adaptive_batch",
        AoscSettings::validateControllerType,
        Setting.Property.Dynamic,
        Setting.Property.NodeScope
    );

    public static final Setting<String> REPLAY_CONTROLLER_TYPE = Setting.simpleString(
        "aosc.replay.controller.type",
        "adaptive_batch",
        AoscSettings::validateReplayControllerType,
        Setting.Property.Dynamic,
        Setting.Property.NodeScope
    );

    // ==== Shared overload settings (all controller types) ====

    public static final Setting<Long> BACKFILL_OVERLOAD_BASE_PAUSE = Setting.longSetting(
        "aosc.backfill.controller.overload.base_pause",
        2_000L,
        100L,
        600_000L,
        Setting.Property.Dynamic,
        Setting.Property.NodeScope
    );

    public static final Setting<Long> BACKFILL_OVERLOAD_MAX_PAUSE = Setting.longSetting(
        "aosc.backfill.controller.overload.max_pause",
        120_000L,
        1_000L,
        600_000L,
        Setting.Property.Dynamic,
        Setting.Property.NodeScope
    );

    public static final Setting<Long> REPLAY_OVERLOAD_BASE_PAUSE = Setting.longSetting(
        "aosc.replay.controller.overload.base_pause",
        2_000L,
        100L,
        600_000L,
        Setting.Property.Dynamic,
        Setting.Property.NodeScope
    );

    public static final Setting<Long> REPLAY_OVERLOAD_MAX_PAUSE = Setting.longSetting(
        "aosc.replay.controller.overload.max_pause",
        120_000L,
        1_000L,
        600_000L,
        Setting.Property.Dynamic,
        Setting.Property.NodeScope
    );

    // ==== Shared batch max bytes (all controller types) ====

    public static final Setting<ByteSizeValue> BACKFILL_BATCH_MAX_BYTES = Setting.byteSizeSetting(
        "aosc.backfill.controller.batch.max_bytes",
        new ByteSizeValue(100, ByteSizeUnit.MB),
        new ByteSizeValue(1, ByteSizeUnit.MB),
        new ByteSizeValue(512, ByteSizeUnit.MB),
        Setting.Property.Dynamic,
        Setting.Property.NodeScope
    );

    public static final Setting<ByteSizeValue> REPLAY_BATCH_MAX_BYTES = Setting.byteSizeSetting(
        "aosc.replay.controller.batch.max_bytes",
        new ByteSizeValue(100, ByteSizeUnit.MB),
        new ByteSizeValue(1, ByteSizeUnit.MB),
        new ByteSizeValue(512, ByteSizeUnit.MB),
        Setting.Property.Dynamic,
        Setting.Property.NodeScope
    );

    // ==== Read settings ====

    public static final Setting<Integer> BACKFILL_READ_PAGE_SIZE = Setting.intSetting(
        "aosc.backfill.read.page_size",
        1000,
        1,
        50_000,
        Setting.Property.Dynamic,
        Setting.Property.NodeScope
    );

    // ==== Fixed controller settings ====

    public static final Setting<Integer> BACKFILL_FIXED_BATCH_SIZE = Setting.intSetting(
        "aosc.backfill.controller.batch.size",
        500,
        1,
        50_000,
        Setting.Property.Dynamic,
        Setting.Property.NodeScope
    );

    public static final Setting<Integer> BACKFILL_FIXED_CONCURRENCY = Setting.intSetting(
        "aosc.backfill.controller.concurrency",
        1,
        1,
        32,
        Setting.Property.Dynamic,
        Setting.Property.NodeScope
    );

    public static final Setting<Integer> REPLAY_FIXED_BATCH_SIZE = Setting.intSetting(
        "aosc.replay.controller.batch.size",
        500,
        1,
        50_000,
        Setting.Property.Dynamic,
        Setting.Property.NodeScope
    );

    // ==== Adaptive batch settings (adaptive_batch + adaptive) ====

    public static final Setting<ByteSizeValue> BACKFILL_BATCH_MIN_BYTES = Setting.byteSizeSetting(
        "aosc.backfill.controller.batch.min_bytes",
        new ByteSizeValue(512, ByteSizeUnit.KB),
        new ByteSizeValue(1, ByteSizeUnit.KB),
        new ByteSizeValue(100, ByteSizeUnit.MB),
        Setting.Property.Dynamic,
        Setting.Property.NodeScope
    );

    public static final Setting<Integer> BACKFILL_BATCH_MAX_DOCS = Setting.intSetting(
        "aosc.backfill.controller.batch.max_docs",
        500,
        1,
        50_000,
        Setting.Property.Dynamic,
        Setting.Property.NodeScope
    );

    public static final Setting<ByteSizeValue> BACKFILL_BATCH_START_BPD = Setting.byteSizeSetting(
        "aosc.backfill.controller.batch.start_bytes_per_doc",
        new ByteSizeValue(10, ByteSizeUnit.KB),
        new ByteSizeValue(256, ByteSizeUnit.BYTES),
        new ByteSizeValue(1, ByteSizeUnit.MB),
        Setting.Property.Dynamic,
        Setting.Property.NodeScope
    );

    public static final Setting<Integer> BACKFILL_AIMD_INCREASE_THRESHOLD = Setting.intSetting(
        "aosc.backfill.controller.aimd.increase_threshold",
        3,
        1,
        100,
        Setting.Property.Dynamic,
        Setting.Property.NodeScope
    );

    public static final Setting<Double> BACKFILL_AIMD_INCREASE_RATIO = Setting.doubleSetting(
        "aosc.backfill.controller.aimd.increase_ratio",
        0.20,
        0.01,
        1.0,
        Setting.Property.Dynamic,
        Setting.Property.NodeScope
    );

    public static final Setting<ByteSizeValue> BACKFILL_AIMD_MIN_STEP_BYTES = Setting.byteSizeSetting(
        "aosc.backfill.controller.aimd.min_step_bytes",
        new ByteSizeValue(256, ByteSizeUnit.KB),
        new ByteSizeValue(1, ByteSizeUnit.KB),
        new ByteSizeValue(100, ByteSizeUnit.MB),
        Setting.Property.Dynamic,
        Setting.Property.NodeScope
    );

    public static final Setting<Integer> BACKFILL_AIMD_COOLDOWN_TICKS = Setting.intSetting(
        "aosc.backfill.controller.aimd.cooldown_ticks",
        2,
        0,
        100,
        Setting.Property.Dynamic,
        Setting.Property.NodeScope
    );

    public static final Setting<Double> BACKFILL_AIMD_TRIAL_REVERT = Setting.doubleSetting(
        "aosc.backfill.controller.aimd.trial_revert_threshold",
        0.10,
        0.0,
        1.0,
        Setting.Property.Dynamic,
        Setting.Property.NodeScope
    );

    // ---- Replay AIMD mirrors ----

    public static final Setting<ByteSizeValue> REPLAY_BATCH_MIN_BYTES = Setting.byteSizeSetting(
        "aosc.replay.controller.batch.min_bytes",
        new ByteSizeValue(512, ByteSizeUnit.KB),
        new ByteSizeValue(1, ByteSizeUnit.KB),
        new ByteSizeValue(100, ByteSizeUnit.MB),
        Setting.Property.Dynamic,
        Setting.Property.NodeScope
    );

    public static final Setting<Integer> REPLAY_BATCH_MAX_DOCS = Setting.intSetting(
        "aosc.replay.controller.batch.max_docs",
        500,
        1,
        50_000,
        Setting.Property.Dynamic,
        Setting.Property.NodeScope
    );

    public static final Setting<ByteSizeValue> REPLAY_BATCH_START_BPD = Setting.byteSizeSetting(
        "aosc.replay.controller.batch.start_bytes_per_doc",
        new ByteSizeValue(10, ByteSizeUnit.KB),
        new ByteSizeValue(256, ByteSizeUnit.BYTES),
        new ByteSizeValue(1, ByteSizeUnit.MB),
        Setting.Property.Dynamic,
        Setting.Property.NodeScope
    );

    public static final Setting<Integer> REPLAY_AIMD_INCREASE_THRESHOLD = Setting.intSetting(
        "aosc.replay.controller.aimd.increase_threshold",
        3,
        1,
        100,
        Setting.Property.Dynamic,
        Setting.Property.NodeScope
    );

    public static final Setting<Double> REPLAY_AIMD_INCREASE_RATIO = Setting.doubleSetting(
        "aosc.replay.controller.aimd.increase_ratio",
        0.20,
        0.01,
        1.0,
        Setting.Property.Dynamic,
        Setting.Property.NodeScope
    );

    public static final Setting<ByteSizeValue> REPLAY_AIMD_MIN_STEP_BYTES = Setting.byteSizeSetting(
        "aosc.replay.controller.aimd.min_step_bytes",
        new ByteSizeValue(256, ByteSizeUnit.KB),
        new ByteSizeValue(1, ByteSizeUnit.KB),
        new ByteSizeValue(100, ByteSizeUnit.MB),
        Setting.Property.Dynamic,
        Setting.Property.NodeScope
    );

    public static final Setting<Integer> REPLAY_AIMD_COOLDOWN_TICKS = Setting.intSetting(
        "aosc.replay.controller.aimd.cooldown_ticks",
        2,
        0,
        100,
        Setting.Property.Dynamic,
        Setting.Property.NodeScope
    );

    public static final Setting<Double> REPLAY_AIMD_TRIAL_REVERT = Setting.doubleSetting(
        "aosc.replay.controller.aimd.trial_revert_threshold",
        0.10,
        0.0,
        1.0,
        Setting.Property.Dynamic,
        Setting.Property.NodeScope
    );

    // ==== Adaptive controller concurrency (backfill only) ====

    public static final Setting<Integer> BACKFILL_CONCURRENCY_MAX = Setting.intSetting(
        "aosc.backfill.controller.concurrency.max",
        8,
        1,
        32,
        Setting.Property.Dynamic,
        Setting.Property.NodeScope
    );

    public static final Setting<Integer> BACKFILL_CONCURRENCY_PROBE_INTERVAL = Setting.intSetting(
        "aosc.backfill.controller.concurrency.probe_interval",
        5,
        1,
        100,
        Setting.Property.Dynamic,
        Setting.Property.NodeScope
    );

    public static final Setting<Double> BACKFILL_CONCURRENCY_GRADIENT_THRESHOLD = Setting.doubleSetting(
        "aosc.backfill.controller.concurrency.gradient_threshold",
        1.5,
        1.0,
        10.0,
        Setting.Property.Dynamic,
        Setting.Property.NodeScope
    );

    // ==== ALL settings list ====

    public static final List<Setting<?>> ALL = List.of(
        // Non-controller
        CONVERGENCE_THRESHOLD,
        MAX_CONVERGENCE_ROUNDS,
        LIVENESS_CHECK_INTERVAL,
        LIVENESS_TIMEOUT,
        TARGET_READY_TIMEOUT,
        MAX_CONCURRENT_PER_NODE,
        TRANSIENT_TARGET_SETTINGS,
        REMOVE_SOURCE_WRITE_BLOCK_ON_SUCCESS,
        OVERLOAD_MAX_CONSECUTIVE_FAILURES,
        // Controller selectors
        BACKFILL_CONTROLLER_TYPE,
        REPLAY_CONTROLLER_TYPE,
        // Shared overload
        BACKFILL_OVERLOAD_BASE_PAUSE,
        BACKFILL_OVERLOAD_MAX_PAUSE,
        REPLAY_OVERLOAD_BASE_PAUSE,
        REPLAY_OVERLOAD_MAX_PAUSE,
        // Shared batch max bytes
        BACKFILL_BATCH_MAX_BYTES,
        REPLAY_BATCH_MAX_BYTES,
        // Fixed
        BACKFILL_READ_PAGE_SIZE,
        BACKFILL_FIXED_BATCH_SIZE,
        BACKFILL_FIXED_CONCURRENCY,
        REPLAY_FIXED_BATCH_SIZE,
        // Backfill AIMD
        BACKFILL_BATCH_MIN_BYTES,
        BACKFILL_BATCH_MAX_DOCS,
        BACKFILL_BATCH_START_BPD,
        BACKFILL_AIMD_INCREASE_THRESHOLD,
        BACKFILL_AIMD_INCREASE_RATIO,
        BACKFILL_AIMD_MIN_STEP_BYTES,
        BACKFILL_AIMD_COOLDOWN_TICKS,
        BACKFILL_AIMD_TRIAL_REVERT,
        // Replay AIMD
        REPLAY_BATCH_MIN_BYTES,
        REPLAY_BATCH_MAX_DOCS,
        REPLAY_BATCH_START_BPD,
        REPLAY_AIMD_INCREASE_THRESHOLD,
        REPLAY_AIMD_INCREASE_RATIO,
        REPLAY_AIMD_MIN_STEP_BYTES,
        REPLAY_AIMD_COOLDOWN_TICKS,
        REPLAY_AIMD_TRIAL_REVERT,
        // Adaptive_batch concurrency
        // Adaptive concurrency
        BACKFILL_CONCURRENCY_MAX,
        BACKFILL_CONCURRENCY_PROBE_INTERVAL,
        BACKFILL_CONCURRENCY_GRADIENT_THRESHOLD
    );

    private AoscSettings() {}

    // ---- Validators ----

    private static void validateControllerType(String value) {
        if (value == null || value.isEmpty()) return;
        ControllerType.fromString(value);
    }

    private static void validateReplayControllerType(String value) {
        if (value == null || value.isEmpty()) return;
        ControllerType type = ControllerType.fromString(value);
        if (type == ControllerType.ADAPTIVE) {
            throw new IllegalArgumentException("Replay controller does not support 'adaptive' — use 'fixed' or 'adaptive_batch'");
        }
    }

    // ---- Utilities ----

    @SuppressWarnings("unchecked")
    public static Map<String, String> parseTransientTargetSettings(String json) {
        if (json == null || json.isBlank() || json.equals("{}")) {
            return Collections.emptyMap();
        }
        try {
            Map<String, String> result = JacksonHelper.readValue(json, Map.class);
            return Collections.unmodifiableMap(result);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to parse transient_target_settings JSON: " + json, e);
        }
    }

    private static void validateTransientTargetSettings(String json) {
        if (json == null || json.isBlank()) return;
        Map<String, String> parsed = parseTransientTargetSettings(json);
        for (String key : parsed.keySet()) {
            if (!key.startsWith("index.")) {
                throw new IllegalArgumentException("transient_target_settings keys must start with 'index.', got '" + key + "'");
            }
        }
    }
}
