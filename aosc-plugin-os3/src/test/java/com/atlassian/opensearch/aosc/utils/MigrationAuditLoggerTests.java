/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */
package com.atlassian.opensearch.aosc.utils;

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.ThreadContext;
import org.apache.logging.log4j.core.Layout;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.Configuration;
import org.apache.logging.log4j.core.config.LoggerConfig;
import org.apache.logging.log4j.core.config.Property;
import org.apache.logging.log4j.core.layout.PatternLayout;

import org.opensearch.test.OpenSearchTestCase;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Unit tests for {@link MigrationAuditLogger}.
 *
 * <p>Uses an in-process Log4j {@link AbstractAppender} attached to the dedicated audit
 * logger ({@link MigrationAuditLogger#AUDIT_LOGGER_NAME}) to capture each emitted event
 * along with its {@link ThreadContext} (MDC) snapshot. Each test asserts:</p>
 * <ul>
 *   <li>The expected event is emitted with the right structured fields.</li>
 *   <li>The {@link ThreadContext} is fully cleared after the call (no leakage).</li>
 *   <li>The human-readable message contains the key identifiers for grep-ability.</li>
 * </ul>
 */
public class MigrationAuditLoggerTests extends OpenSearchTestCase {

    private CapturingAppender appender;
    private LoggerContext loggerContext;
    private LoggerConfig loggerConfig;

    @Override
    public void setUp() throws Exception {
        super.setUp();
        loggerContext = (LoggerContext) LogManager.getContext(false);
        Configuration config = loggerContext.getConfiguration();
        appender = new CapturingAppender("AuditCapture", PatternLayout.createDefaultLayout(config));
        appender.start();
        loggerConfig = new LoggerConfig(MigrationAuditLogger.AUDIT_LOGGER_NAME, Level.INFO, false);
        loggerConfig.addAppender(appender, Level.INFO, null);
        config.addLogger(MigrationAuditLogger.AUDIT_LOGGER_NAME, loggerConfig);
        loggerContext.updateLoggers();
        // Ensure no leftover MDC from a previous test on the same thread.
        ThreadContext.clearMap();
    }

    @Override
    public void tearDown() throws Exception {
        try {
            Configuration config = loggerContext.getConfiguration();
            config.removeLogger(MigrationAuditLogger.AUDIT_LOGGER_NAME);
            loggerContext.updateLoggers();
            appender.stop();
            ThreadContext.clearMap();
        } finally {
            super.tearDown();
        }
    }

    public void testCoordinatorPhaseTransitionEmitsAllStructuredFields() {
        MigrationAuditLogger.recordCoordinatorPhaseTransition("mig-1", "PENDING", "ACTIVE", "test-reason");

        assertEquals("expected exactly one audit event", 1, appender.events().size());
        CapturedEvent event = appender.events().get(0);

        assertEquals("phase_transition_coordinator", event.context.get(MigrationAuditLogger.KEY_EVENT));
        assertEquals("mig-1", event.context.get(MigrationAuditLogger.KEY_MIGRATION_ID));
        assertEquals("PENDING", event.context.get(MigrationAuditLogger.KEY_FROM_PHASE));
        assertEquals("ACTIVE", event.context.get(MigrationAuditLogger.KEY_TO_PHASE));
        assertEquals("test-reason", event.context.get(MigrationAuditLogger.KEY_REASON));
        assertNull("no shardId on coordinator events", event.context.get(MigrationAuditLogger.KEY_SHARD_ID));

        assertTrue("message must include migrationId", event.message.contains("mig-1"));
        assertTrue("message must include phase transition", event.message.contains("PENDING -> ACTIVE"));

        assertThreadContextCleared();
    }

    public void testShardPhaseTransitionEmitsShardId() {
        MigrationAuditLogger.recordShardPhaseTransition("mig-2", 3, "BACKFILLING", "REPLAYING", null);

        assertEquals(1, appender.events().size());
        CapturedEvent event = appender.events().get(0);
        assertEquals("phase_transition_shard", event.context.get(MigrationAuditLogger.KEY_EVENT));
        assertEquals("mig-2", event.context.get(MigrationAuditLogger.KEY_MIGRATION_ID));
        assertEquals("3", event.context.get(MigrationAuditLogger.KEY_SHARD_ID));
        assertEquals("BACKFILLING", event.context.get(MigrationAuditLogger.KEY_FROM_PHASE));
        assertEquals("REPLAYING", event.context.get(MigrationAuditLogger.KEY_TO_PHASE));
        assertNull("reason omitted when null", event.context.get(MigrationAuditLogger.KEY_REASON));

        assertTrue(event.message.contains("mig-2"));
        assertTrue(event.message.contains("shardId=3"));
        assertThreadContextCleared();
    }

    public void testMigrationTerminalCompletedHasNoErrorClass() {
        MigrationAuditLogger.recordMigrationTerminal("mig-3", "COMPLETED", null);

        assertEquals(1, appender.events().size());
        CapturedEvent event = appender.events().get(0);
        assertEquals("migration_completed", event.context.get(MigrationAuditLogger.KEY_EVENT));
        assertEquals("mig-3", event.context.get(MigrationAuditLogger.KEY_MIGRATION_ID));
        assertEquals("COMPLETED", event.context.get(MigrationAuditLogger.KEY_TO_PHASE));
        assertNull(event.context.get(MigrationAuditLogger.KEY_ERROR_CLASS));
        assertNull(event.context.get(MigrationAuditLogger.KEY_REASON));
        assertThreadContextCleared();
    }

    public void testMigrationTerminalFailedIncludesErrorClass() {
        IllegalStateException cause = new IllegalStateException("boom");
        MigrationAuditLogger.recordMigrationTerminal("mig-4", "FAILED", cause);

        assertEquals(1, appender.events().size());
        CapturedEvent event = appender.events().get(0);
        assertEquals("migration_failed", event.context.get(MigrationAuditLogger.KEY_EVENT));
        assertEquals(cause.getClass().getName(), event.context.get(MigrationAuditLogger.KEY_ERROR_CLASS));
        assertEquals("boom", event.context.get(MigrationAuditLogger.KEY_REASON));
        assertTrue(event.message.contains("error="));
        assertThreadContextCleared();
    }

    public void testLeaseAcquiredEmitsSeqNoAndShardId() {
        MigrationAuditLogger.recordLeaseAcquired("mig-5", 7, 12345L);

        assertEquals(1, appender.events().size());
        CapturedEvent event = appender.events().get(0);
        assertEquals("lease_acquired", event.context.get(MigrationAuditLogger.KEY_EVENT));
        assertEquals("7", event.context.get(MigrationAuditLogger.KEY_SHARD_ID));
        assertEquals("12345", event.context.get(MigrationAuditLogger.KEY_LEASE_SEQ_NO));
        assertTrue(event.message.contains("seqNo=12345"));
        assertThreadContextCleared();
    }

    public void testLeaseReleasedSuccessOmitsErrorReason() {
        MigrationAuditLogger.recordLeaseReleased("mig-6", 0, false, null);

        assertEquals(1, appender.events().size());
        CapturedEvent event = appender.events().get(0);
        assertEquals("lease_released", event.context.get(MigrationAuditLogger.KEY_EVENT));
        assertEquals("0", event.context.get(MigrationAuditLogger.KEY_SHARD_ID));
        assertNull(event.context.get(MigrationAuditLogger.KEY_REASON));
        assertNull(event.context.get(MigrationAuditLogger.KEY_ERROR_CLASS));
        assertThreadContextCleared();
    }

    public void testLeaseReleasedBestEffortFailureCarriesErrorContext() {
        RuntimeException cause = new RuntimeException("transport timeout");
        MigrationAuditLogger.recordLeaseReleased("mig-7", 1, true, cause);

        assertEquals(1, appender.events().size());
        CapturedEvent event = appender.events().get(0);
        assertEquals("best_effort_failure", event.context.get(MigrationAuditLogger.KEY_REASON));
        assertEquals(cause.getClass().getName(), event.context.get(MigrationAuditLogger.KEY_ERROR_CLASS));
        assertTrue(event.message.contains("status=best_effort_failure"));
        assertThreadContextCleared();
    }

    public void testCutoverStartedCompletedEmitsDocCounts() {
        MigrationAuditLogger.recordCutoverStarted("mig-8", "src-idx", "tgt-idx");
        MigrationAuditLogger.recordCutoverCompleted("mig-8", "src-idx", "tgt-idx", 1000L, 999L);

        assertEquals(2, appender.events().size());
        CapturedEvent started = appender.events().get(0);
        CapturedEvent completed = appender.events().get(1);

        assertEquals("cutover_started", started.context.get(MigrationAuditLogger.KEY_EVENT));
        assertEquals("src-idx", started.context.get(MigrationAuditLogger.KEY_SOURCE_INDEX));
        assertEquals("tgt-idx", started.context.get(MigrationAuditLogger.KEY_TARGET_INDEX));

        assertEquals("cutover_completed", completed.context.get(MigrationAuditLogger.KEY_EVENT));
        assertEquals("1000", completed.context.get(MigrationAuditLogger.KEY_DOC_COUNT_SOURCE));
        assertEquals("999", completed.context.get(MigrationAuditLogger.KEY_DOC_COUNT_TARGET));
        assertTrue(completed.message.contains("docCountSource=1000"));
        assertTrue(completed.message.contains("docCountTarget=999"));
        assertThreadContextCleared();
    }

    public void testCutoverFailedIncludesError() {
        RuntimeException cause = new RuntimeException("alias swap rejected");
        MigrationAuditLogger.recordCutoverFailed("mig-9", "src", "tgt", cause);

        assertEquals(1, appender.events().size());
        CapturedEvent event = appender.events().get(0);
        assertEquals("cutover_failed", event.context.get(MigrationAuditLogger.KEY_EVENT));
        assertEquals(cause.getClass().getName(), event.context.get(MigrationAuditLogger.KEY_ERROR_CLASS));
        assertTrue(event.message.contains("alias swap rejected"));
        assertThreadContextCleared();
    }

    public void testThreadContextNotPollutedAcrossCalls() {
        // Pre-populate an unrelated MDC key — must survive untouched.
        ThreadContext.put("unrelated.key", "preserve-me");
        try {
            MigrationAuditLogger.recordShardPhaseTransition("mig-10", 4, "PENDING", "ACQUIRING_LEASE", null);
            assertEquals("audit logger must not clear unrelated MDC keys", "preserve-me", ThreadContext.get("unrelated.key"));
            assertNull("audit logger must clear its own keys", ThreadContext.get(MigrationAuditLogger.KEY_EVENT));
            assertNull(ThreadContext.get(MigrationAuditLogger.KEY_MIGRATION_ID));
            assertNull(ThreadContext.get(MigrationAuditLogger.KEY_SHARD_ID));
        } finally {
            ThreadContext.remove("unrelated.key");
        }
    }

    private void assertThreadContextCleared() {
        for (String key : new String[] {
            MigrationAuditLogger.KEY_EVENT,
            MigrationAuditLogger.KEY_MIGRATION_ID,
            MigrationAuditLogger.KEY_SHARD_ID,
            MigrationAuditLogger.KEY_FROM_PHASE,
            MigrationAuditLogger.KEY_TO_PHASE,
            MigrationAuditLogger.KEY_REASON,
            MigrationAuditLogger.KEY_ERROR_CLASS,
            MigrationAuditLogger.KEY_SOURCE_INDEX,
            MigrationAuditLogger.KEY_TARGET_INDEX,
            MigrationAuditLogger.KEY_LEASE_SEQ_NO,
            MigrationAuditLogger.KEY_DOC_COUNT_SOURCE,
            MigrationAuditLogger.KEY_DOC_COUNT_TARGET }) {
            assertNull("MDC key [" + key + "] leaked after audit call", ThreadContext.get(key));
        }
    }

    /** Captures the formatted message and a snapshot of {@link ThreadContext} per event. */
    private static final class CapturedEvent {
        final String message;
        final Map<String, String> context;

        CapturedEvent(String message, Map<String, String> context) {
            this.message = message;
            this.context = context;
        }
    }

    private static final class CapturingAppender extends AbstractAppender {
        private final List<CapturedEvent> events = new ArrayList<>();

        CapturingAppender(String name, Layout<?> layout) {
            super(name, null, layout, true, Property.EMPTY_ARRAY);
        }

        @Override
        public void append(LogEvent event) {
            // Snapshot the MDC AT THE TIME of the log call — Log4j passes a copy in event.getContextData().
            Map<String, String> snapshot = new HashMap<>(event.getContextData().toMap());
            events.add(new CapturedEvent(event.getMessage().getFormattedMessage(), snapshot));
        }

        List<CapturedEvent> events() {
            return events;
        }
    }
}
