/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */
package com.atlassian.opensearch.aosc.utils;

import org.apache.logging.log4j.ThreadContext;

/**
 * Dedicated audit logger for AOSC migration lifecycle events. Emits human-readable lines
 * to the audit logger name {@code org.opensearch.aosc.audit}, with structured fields
 * surfaced via Log4j's {@link ThreadContext} (MDC) so operators can route the audit
 * stream to a dedicated {@code JsonLayout} appender for SIEM / log-aggregator ingestion.
 *
 * <h2>Why a dedicated logger?</h2>
 * <ul>
 *   <li><b>Separable</b> — operators can configure a separate file/appender for audit
 *       events without touching the rest of AOSC's main logs:
 *       <pre>
 *       logger.aosc_audit.name = org.opensearch.aosc.audit
 *       logger.aosc_audit.appenderRef.audit_file.ref = aosc_audit_file
 *       logger.aosc_audit.additivity = false
 *       </pre>
 *   </li>
 *   <li><b>Structured-ready</b> — {@link ThreadContext} keys ({@code migrationId},
 *       {@code shardId}, {@code aosc.event}, etc.) are emitted by Log4j's
 *       {@code JsonLayout} when {@code includeThreadContext=true} — no code changes
 *       required to switch from text to JSON.</li>
 *   <li><b>Graceful fallback</b> — when no special config is provided, audit events
 *       still appear as parameterized human-readable lines in the main AOSC log.</li>
 * </ul>
 *
 * <h2>Thread-context discipline</h2>
 * Each public method sets the relevant {@link ThreadContext} keys, emits exactly one
 * log line, and clears the keys it set in a {@code finally} block. We do NOT pollute
 * the calling thread's MDC beyond the duration of the audit call — this prevents the
 * keys from leaking into unrelated subsequent logs on the same thread (e.g., the
 * cluster-state-applier thread, the GENERIC threadpool, or test executors).
 *
 * <h2>Event vocabulary</h2>
 * The {@code aosc.event} key carries one of a small fixed set of event names.
 * Operators can build SIEM rules / dashboards keyed on this enum-like value:
 * <ul>
 *   <li>{@code phase_transition_coordinator} — coordinator phase change</li>
 *   <li>{@code phase_transition_shard} — shard worker phase change</li>
 *   <li>{@code lease_acquired} — retention lease created on source primary</li>
 *   <li>{@code lease_released} — retention lease removed from source primary</li>
 *   <li>{@code cutover_started} — cutover phase began</li>
 *   <li>{@code cutover_completed} — cutover succeeded; aliases swapped</li>
 *   <li>{@code cutover_failed} — cutover aborted; rollback attempted</li>
 *   <li>{@code migration_started} — coordinator left PENDING</li>
 *   <li>{@code migration_completed} — coordinator reached COMPLETED terminal</li>
 *   <li>{@code migration_failed} — coordinator reached FAILED terminal</li>
 *   <li>{@code migration_cancelled} — coordinator reached CANCELLED terminal</li>
 * </ul>
 *
 * <h2>Performance</h2>
 * Emits at INFO. {@link ThreadContext} put/remove on Log4j 2's default
 * {@code DefaultThreadContextMap} is a thread-local hashmap operation, so the
 * per-event overhead is expected to be dominated by the actual log write.
 */
public final class MigrationAuditLogger {

    /**
     * Dedicated logger name. Operators can route this independently of other AOSC loggers.
     * <p><b>Stable contract:</b> external monitoring/alerting may rely on this exact name.
     * Do not change without a deprecation cycle.</p>
     */
    public static final String AUDIT_LOGGER_NAME = "org.opensearch.aosc.audit";

    private static final AoscLogger AUDIT = AoscLogger.create(AUDIT_LOGGER_NAME);

    /** {@link ThreadContext} key — required on every event. */
    public static final String KEY_MIGRATION_ID = "migrationId";
    /** {@link ThreadContext} key — present on shard-scoped events. */
    public static final String KEY_SHARD_ID = "shardId";
    /** {@link ThreadContext} key — event-name discriminator (see class JavaDoc). */
    public static final String KEY_EVENT = "aosc.event";
    /** {@link ThreadContext} key — phase-transition source phase. */
    public static final String KEY_FROM_PHASE = "aosc.fromPhase";
    /** {@link ThreadContext} key — phase-transition destination phase. */
    public static final String KEY_TO_PHASE = "aosc.toPhase";
    /** {@link ThreadContext} key — optional human-readable reason / cause summary. */
    public static final String KEY_REASON = "aosc.reason";
    /** {@link ThreadContext} key — exception class for failure events. */
    public static final String KEY_ERROR_CLASS = "aosc.errorClass";
    /** {@link ThreadContext} key — source index (lease / cutover events). */
    public static final String KEY_SOURCE_INDEX = "aosc.sourceIndex";
    /** {@link ThreadContext} key — target index (cutover events). */
    public static final String KEY_TARGET_INDEX = "aosc.targetIndex";
    /** {@link ThreadContext} key — retention-lease seqNo (lease events). */
    public static final String KEY_LEASE_SEQ_NO = "aosc.leaseSeqNo";
    /** {@link ThreadContext} key — source / target document counts at cutover. */
    public static final String KEY_DOC_COUNT_SOURCE = "aosc.docCountSource";
    public static final String KEY_DOC_COUNT_TARGET = "aosc.docCountTarget";

    private MigrationAuditLogger() {
        // utility
    }

    /** Coordinator-level phase transition. */
    public static void recordCoordinatorPhaseTransition(String migrationId, String fromPhase, String toPhase, String reason) {
        emit(
            "phase_transition_coordinator",
            migrationId,
            null,
            fromPhase,
            toPhase,
            reason,
            null,
            "phase_transition_coordinator migrationId={} {} -> {}{}",
            migrationId,
            fromPhase,
            toPhase,
            reason == null ? "" : " reason=" + reason
        );
    }

    /** Shard worker phase transition. */
    public static void recordShardPhaseTransition(String migrationId, int shardId, String fromPhase, String toPhase, String reason) {
        ThreadContext.put(KEY_SHARD_ID, Integer.toString(shardId));
        try {
            emit(
                "phase_transition_shard",
                migrationId,
                shardId,
                fromPhase,
                toPhase,
                reason,
                null,
                "phase_transition_shard migrationId={} shardId={} {} -> {}{}",
                migrationId,
                shardId,
                fromPhase,
                toPhase,
                reason == null ? "" : " reason=" + reason
            );
        } finally {
            ThreadContext.remove(KEY_SHARD_ID);
        }
    }

    /** Migration started (coordinator left PENDING). */
    public static void recordMigrationStarted(String migrationId, String sourceIndex, String targetIndex) {
        ThreadContext.put(KEY_SOURCE_INDEX, sourceIndex);
        ThreadContext.put(KEY_TARGET_INDEX, targetIndex);
        try {
            emit(
                "migration_started",
                migrationId,
                null,
                null,
                null,
                null,
                null,
                "migration_started migrationId={} source={} target={}",
                migrationId,
                sourceIndex,
                targetIndex
            );
        } finally {
            ThreadContext.remove(KEY_SOURCE_INDEX);
            ThreadContext.remove(KEY_TARGET_INDEX);
        }
    }

    /** Migration reached terminal phase. {@code error} may be null on success/cancellation. */
    public static void recordMigrationTerminal(String migrationId, String terminalPhase, Throwable error) {
        String event;
        switch (terminalPhase) {
            case "COMPLETED":
                event = "migration_completed";
                break;
            case "FAILED":
                event = "migration_failed";
                break;
            case "CANCELLED":
                event = "migration_cancelled";
                break;
            default:
                event = "migration_terminal_unknown";
        }
        emit(
            event,
            migrationId,
            null,
            null,
            terminalPhase,
            error == null ? null : error.getMessage(),
            error,
            error == null ? "{} migrationId={}" : "{} migrationId={} error={}",
            error == null ? new Object[] { event, migrationId } : new Object[] { event, migrationId, error.toString() }
        );
    }

    /** Retention lease acquired on source primary. */
    public static void recordLeaseAcquired(String migrationId, int shardId, long seqNo) {
        ThreadContext.put(KEY_SHARD_ID, Integer.toString(shardId));
        ThreadContext.put(KEY_LEASE_SEQ_NO, Long.toString(seqNo));
        try {
            emit(
                "lease_acquired",
                migrationId,
                shardId,
                null,
                null,
                null,
                null,
                "lease_acquired migrationId={} shardId={} seqNo={}",
                migrationId,
                shardId,
                seqNo
            );
        } finally {
            ThreadContext.remove(KEY_SHARD_ID);
            ThreadContext.remove(KEY_LEASE_SEQ_NO);
        }
    }

    /** Retention lease released from source primary. */
    public static void recordLeaseReleased(String migrationId, int shardId, boolean bestEffortFailure, Throwable error) {
        ThreadContext.put(KEY_SHARD_ID, Integer.toString(shardId));
        try {
            String reason = bestEffortFailure ? "best_effort_failure" : null;
            emit(
                "lease_released",
                migrationId,
                shardId,
                null,
                null,
                reason,
                error,
                bestEffortFailure
                    ? "lease_released migrationId={} shardId={} status=best_effort_failure error={}"
                    : "lease_released migrationId={} shardId={}",
                bestEffortFailure
                    ? new Object[] { migrationId, shardId, error == null ? "<unknown>" : error.toString() }
                    : new Object[] { migrationId, shardId }
            );
        } finally {
            ThreadContext.remove(KEY_SHARD_ID);
        }
    }

    /** Cutover phase started. */
    public static void recordCutoverStarted(String migrationId, String sourceIndex, String targetIndex) {
        ThreadContext.put(KEY_SOURCE_INDEX, sourceIndex);
        ThreadContext.put(KEY_TARGET_INDEX, targetIndex);
        try {
            emit(
                "cutover_started",
                migrationId,
                null,
                null,
                null,
                null,
                null,
                "cutover_started migrationId={} source={} target={}",
                migrationId,
                sourceIndex,
                targetIndex
            );
        } finally {
            ThreadContext.remove(KEY_SOURCE_INDEX);
            ThreadContext.remove(KEY_TARGET_INDEX);
        }
    }

    /** Cutover succeeded; aliases swapped. */
    public static void recordCutoverCompleted(
        String migrationId,
        String sourceIndex,
        String targetIndex,
        long docCountSource,
        long docCountTarget
    ) {
        ThreadContext.put(KEY_SOURCE_INDEX, sourceIndex);
        ThreadContext.put(KEY_TARGET_INDEX, targetIndex);
        ThreadContext.put(KEY_DOC_COUNT_SOURCE, Long.toString(docCountSource));
        ThreadContext.put(KEY_DOC_COUNT_TARGET, Long.toString(docCountTarget));
        try {
            emit(
                "cutover_completed",
                migrationId,
                null,
                null,
                null,
                null,
                null,
                "cutover_completed migrationId={} source={} target={} docCountSource={} docCountTarget={}",
                migrationId,
                sourceIndex,
                targetIndex,
                docCountSource,
                docCountTarget
            );
        } finally {
            ThreadContext.remove(KEY_SOURCE_INDEX);
            ThreadContext.remove(KEY_TARGET_INDEX);
            ThreadContext.remove(KEY_DOC_COUNT_SOURCE);
            ThreadContext.remove(KEY_DOC_COUNT_TARGET);
        }
    }

    /** Cutover failed; rollback may have been attempted. */
    public static void recordCutoverFailed(String migrationId, String sourceIndex, String targetIndex, Throwable error) {
        ThreadContext.put(KEY_SOURCE_INDEX, sourceIndex);
        ThreadContext.put(KEY_TARGET_INDEX, targetIndex);
        try {
            emit(
                "cutover_failed",
                migrationId,
                null,
                null,
                null,
                error == null ? null : error.getMessage(),
                error,
                "cutover_failed migrationId={} source={} target={} error={}",
                migrationId,
                sourceIndex,
                targetIndex,
                error == null ? "<unknown>" : error.toString()
            );
        } finally {
            ThreadContext.remove(KEY_SOURCE_INDEX);
            ThreadContext.remove(KEY_TARGET_INDEX);
        }
    }

    // -------------------------------------------------------------------------
    // Internal helpers
    // -------------------------------------------------------------------------

    private static void emit(
        String event,
        String migrationId,
        Integer shardId,
        String fromPhase,
        String toPhase,
        String reason,
        Throwable error,
        String message,
        Object... args
    ) {
        ThreadContext.put(KEY_EVENT, event);
        if (migrationId != null) {
            ThreadContext.put(KEY_MIGRATION_ID, migrationId);
        }
        if (fromPhase != null) {
            ThreadContext.put(KEY_FROM_PHASE, fromPhase);
        }
        if (toPhase != null) {
            ThreadContext.put(KEY_TO_PHASE, toPhase);
        }
        if (reason != null) {
            ThreadContext.put(KEY_REASON, reason);
        }
        if (error != null) {
            ThreadContext.put(KEY_ERROR_CLASS, error.getClass().getName());
        }
        try {
            // Log at INFO so the audit stream is populated under default operator config.
            // Operators can re-route via the dedicated logger name.
            AUDIT.info(message, args);
        } finally {
            ThreadContext.remove(KEY_EVENT);
            if (migrationId != null) ThreadContext.remove(KEY_MIGRATION_ID);
            if (fromPhase != null) ThreadContext.remove(KEY_FROM_PHASE);
            if (toPhase != null) ThreadContext.remove(KEY_TO_PHASE);
            if (reason != null) ThreadContext.remove(KEY_REASON);
            if (error != null) ThreadContext.remove(KEY_ERROR_CLASS);
        }
    }
}
