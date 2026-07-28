/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */
package com.atlassian.opensearch.aosc.utils;

/**
 * Logging constants — canonical key names for structured log fields.
 *
 * <p>Every key used in {@link AoscLogger#with(String, Object)} or
 * {@link AoscLogger#kv(String, Object)} should be declared here.
 * This prevents key explosion and ensures consistent naming across
 * the codebase.</p>
 *
 * <p>Usage: {@code logger.info("msg", kv(LC.EVENT, "overload"), kv(LC.W, 3))}</p>
 */
public final class LC {

    private LC() {}

    // ---- Identity (set via .with()) ----
    public static final String MIGRATION_ID = "migrationId";
    public static final String SHARD = "shard";
    public static final String SOURCE_INDEX = "sourceIndex";
    public static final String TARGET_INDEX = "targetIndex";
    public static final String PHASE = "phase";
    public static final String ROLE = "role";

    // ---- Event type ----
    public static final String EVENT = "event";

    // ---- Adaptive write controller ----
    public static final String W = "W";
    public static final String OLD_W = "oldW";
    public static final String TARGET_BYTES = "targetBytes";
    public static final String BEFORE_BYTES = "beforeBytes";
    public static final String STEP_BYTES = "stepBytes";
    public static final String GRADIENT = "gradient";
    public static final String PROBE_INTERVAL = "probeInterval";
    public static final String BACKOFF_MS = "backoffMs";
    public static final String REJECTION_KIND = "rejectionKind";
    public static final String LEVEL = "level";
    public static final String ATTEMPT_COUNT = "attemptCount";
    public static final String PAUSE_MS = "pauseMs";

    // ---- Backfill / Replay ----
    public static final String OPS_APPLIED = "opsApplied";
    public static final String OPS_SKIPPED = "opsSkipped";
    public static final String OPS_REPLAYED = "opsReplayed";
    public static final String BATCHES = "batches";
    public static final String DOCS = "docs";
    public static final String CHECKPOINT = "checkpoint";
    public static final String REQUEUED_OPS = "requeuedOps";

    // ---- Cutover ----
    public static final String SOURCE_COUNT = "sourceCount";
    public static final String TARGET_COUNT = "targetCount";
    public static final String DIFF = "diff";
    public static final String TOLERANCE = "tolerance";
    public static final String ALIAS = "alias";

    // ---- Replay / Convergence ----
    public static final String FROM_SEQ_NO = "fromSeqNo";
    public static final String TARGET_SEQ_NO = "targetSeqNo";
    public static final String LAST_PROCESSED_SEQ_NO = "lastProcessedSeqNo";
    public static final String RANGE = "range";
    public static final String ROUTING_MODE = "routingMode";

}
