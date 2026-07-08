/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */
package com.atlassian.opensearch.aosc.utils;

import org.opensearch.test.OpenSearchTestCase;

import java.util.Map;

public class AoscLoggerTests extends OpenSearchTestCase {

    public void testCreateReturnsEmptyContext() {
        AoscLogger logger = AoscLogger.create(AoscLoggerTests.class);
        assertTrue(logger.context().isEmpty());
    }

    public void testWithAddsField() {
        AoscLogger logger = AoscLogger.create(AoscLoggerTests.class).with("migrationId", "mig-1");
        assertEquals(Map.of("migrationId", "mig-1"), logger.context());
    }

    public void testWithIsImmutable() {
        AoscLogger parent = AoscLogger.create(AoscLoggerTests.class).with("migrationId", "mig-1");
        AoscLogger child = parent.with("shard", 3);
        // Parent unchanged
        assertEquals(Map.of("migrationId", "mig-1"), parent.context());
        // Child has both
        assertEquals(2, child.context().size());
        assertEquals("mig-1", child.context().get("migrationId"));
        assertEquals("3", child.context().get("shard"));
    }

    public void testWithOverwritesExistingKey() {
        AoscLogger logger = AoscLogger.create(AoscLoggerTests.class).with("phase", "backfill").with("phase", "replay");
        assertEquals("replay", logger.context().get("phase"));
        assertEquals(1, logger.context().size());
    }

    public void testContextPreservesInsertionOrder() {
        AoscLogger logger = AoscLogger.create(AoscLoggerTests.class)
            .with("migrationId", "mig-1")
            .with("shard", 3)
            .with("phase", "backfill");
        String[] keys = logger.context().keySet().toArray(new String[0]);
        assertArrayEquals(new String[] { "migrationId", "shard", "phase" }, keys);
    }

    public void testUnwrapReturnsNonNull() {
        AoscLogger logger = AoscLogger.create(AoscLoggerTests.class);
        assertNotNull(logger.unwrap());
    }

    public void testKvCreatesKeyValuePair() {
        AoscLogger.KeyValue pair = AoscLogger.kv("gradient", 1.5);
        assertNotNull(pair);
    }

    public void testLoggingWithKvDoesNotThrow() {
        AoscLogger logger = AoscLogger.create(AoscLoggerTests.class).with("migrationId", "test-mig").with("shard", 0);
        logger.info("Gradient decrease: W={}", 3, AoscLogger.kv("gradient", 1.5), AoscLogger.kv("targetBytes", 512000));
        logger.info("No kv args: value={}", 42);
        logger.info("Only kv args", AoscLogger.kv("key", "value"));
    }

    public void testWithRejectsNullKey() {
        AoscLogger logger = AoscLogger.create(AoscLoggerTests.class);
        expectThrows(IllegalArgumentException.class, () -> logger.with(null, "value"));
    }

    public void testWithRejectsEmptyKey() {
        AoscLogger logger = AoscLogger.create(AoscLoggerTests.class);
        expectThrows(IllegalArgumentException.class, () -> logger.with("", "value"));
    }

    public void testWithRejectsKeyWithSpaces() {
        AoscLogger logger = AoscLogger.create(AoscLoggerTests.class);
        expectThrows(IllegalArgumentException.class, () -> logger.with("bad key", "value"));
    }

    public void testWithRejectsKeyWithEquals() {
        AoscLogger logger = AoscLogger.create(AoscLoggerTests.class);
        expectThrows(IllegalArgumentException.class, () -> logger.with("bad=key", "value"));
    }

    public void testWithRejectsKeyWithBrackets() {
        AoscLogger logger = AoscLogger.create(AoscLoggerTests.class);
        expectThrows(IllegalArgumentException.class, () -> logger.with("[bad]", "value"));
    }

    public void testThrowablePassedAsVararg() {
        AoscLogger logger = AoscLogger.create(AoscLoggerTests.class).with("migrationId", "test");
        RuntimeException ex = new RuntimeException("boom");
        // Throwables passed via varargs are handled by doLog — extracted and attached properly
        logger.error("something failed: {}", "details", ex);
        logger.warn("something concerning", ex);
    }

    public void testLoggingDoesNotThrow() {
        AoscLogger logger = AoscLogger.create(AoscLoggerTests.class).with("migrationId", "test-mig").with("shard", 0);
        // Just verifying no exceptions — actual output verified manually
        logger.info("test info message: docs={}", 100);
        logger.warn("test warn message");
        logger.error("test error message: {}", new RuntimeException("boom"));
        logger.debug("test debug message");
        logger.trace("test trace message");
    }
}
