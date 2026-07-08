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
import org.apache.logging.log4j.Logger;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Composable structured logger that carries immutable context fields through the call stack.
 *
 * <p>Each layer in the AOSC component hierarchy creates a child logger by calling
 * {@link #with(String, Object)}, which returns a new immutable instance with the
 * additional field. Context accumulates as the logger is passed down:</p>
 *
 * <pre>{@code
 * AoscLogger nodeLog = AoscLogger.create(AoscShardService.class);
 * AoscLogger migLog  = nodeLog.with("migrationId", migrationId)
 *                              .with("shard", shardId)
 *                              .with("sourceIndex", sourceIndex);
 * AoscLogger engineLog = migLog.with("phase", "backfill");
 *
 * engineLog.info("Backfill starting: {} docs available", totalDocs);
 * // Output: [migrationId=abc shard=3 sourceIndex=my-idx phase=backfill] Backfill starting: 42000 docs available
 * }</pre>
 *
 * <h2>Design principles</h2>
 * <ul>
 *   <li><b>Immutable</b> — each {@code with()} returns a new instance. Thread-safe by construction.</li>
 *   <li><b>Composable</b> — context accumulates as the logger passes through constructor chains.</li>
 *   <li><b>Rendering is pluggable</b> — currently formats context as a {@code [key=value ...]} prefix
 *       in the log message. The underlying context map is preserved for future integration with
 *       ThreadContext (MDC), OpenSearchLogMessage, or any other structured logging mechanism.</li>
 * </ul>
 *
 * <h2>Context ownership convention</h2>
 * <p>The layer that <em>owns</em> the context adds it:</p>
 * <ul>
 *   <li><b>Self-identity</b> (role, migrationId, shard) — the callee adds context about itself.</li>
 *   <li><b>Caller-assigned role</b> (phase) — the caller adds context about how the callee is used
 *       in the caller's context (e.g., ShardMigrationWorker adds {@code phase=backfill} before
 *       passing to BackfillEngine, because the engine doesn't know its phase name).</li>
 * </ul>
 *
 * <h2>Per-call structured fields</h2>
 * <p>Use {@link #kv(String, Object)} to attach per-call structured fields that vary with each log statement:</p>
 * <pre>{@code
 * logger.info("Gradient decrease: W={}", currentW, kv("gradient", g), kv("targetBytes", targetBytes));
 * // Output: [migrationId=abc shard=3 phase=backfill] Gradient decrease: W=3 | gradient=1.5 targetBytes=512000
 * }</pre>
 */
public final class AoscLogger {

    private final Logger logger;
    private final Map<String, String> fields;
    private final String prefix;

    private AoscLogger(Logger logger, Map<String, String> fields) {
        this.logger = logger;
        this.fields = Collections.unmodifiableMap(fields);
        this.prefix = buildPrefix(fields);
    }

    /** Create a root logger with no context fields. */
    public static AoscLogger create(Class<?> clazz) {
        return new AoscLogger(LogManager.getLogger(clazz), Map.of());
    }

    /**
     * Create a new logger with the same context fields but bound to a different class name.
     * Use at constructor entry to bind the Log4j component name:
     * {@code this.logger = Objects.requireNonNull(logger, "logger").forClass(MyClass.class);}
     */
    public AoscLogger forClass(Class<?> clazz) {
        return new AoscLogger(LogManager.getLogger(clazz), fields);
    }

    /** Create a root logger with a custom logger name (e.g., for audit loggers). */
    public static AoscLogger create(String loggerName) {
        return new AoscLogger(LogManager.getLogger(loggerName), Map.of());
    }

    /**
     * Create a child logger with an additional context field. Returns a new immutable instance.
     *
     * @throws IllegalArgumentException if key is null, empty, or contains characters that would
     *                                  break structured parsing (spaces, equals signs, brackets)
     */
    public AoscLogger with(String key, Object value) {
        validateKey(key);
        LinkedHashMap<String, String> copy = new LinkedHashMap<>(fields);
        copy.put(key, String.valueOf(value));
        return new AoscLogger(logger, copy);
    }

    private static void validateKey(String key) {
        if (key == null || key.isEmpty()) {
            throw new IllegalArgumentException("AoscLogger key must not be null or empty");
        }
        for (int i = 0; i < key.length(); i++) {
            char c = key.charAt(i);
            if (c == ' ' || c == '=' || c == '[' || c == ']') {
                throw new IllegalArgumentException("AoscLogger key must not contain spaces or delimiters: '" + key + "'");
            }
        }
    }

    /** Returns the immutable context map. */
    public Map<String, String> context() {
        return fields;
    }

    /** Returns the pre-computed prefix string (e.g., {@code "[migrationId=abc shard=3] "}). */
    public String prefix() {
        return prefix;
    }

    /** Returns the underlying Log4j logger. Use sparingly — prefer the typed methods. */
    public Logger unwrap() {
        return logger;
    }

    // ---- Logging methods ----

    public void info(String msg, Object... args) {
        if (logger.isInfoEnabled()) {
            doLog(Level.INFO, msg, args);
        }
    }

    public void warn(String msg, Object... args) {
        if (logger.isWarnEnabled()) {
            doLog(Level.WARN, msg, args);
        }
    }

    public void error(String msg, Object... args) {
        if (logger.isErrorEnabled()) {
            doLog(Level.ERROR, msg, args);
        }
    }

    public void debug(String msg, Object... args) {
        if (logger.isDebugEnabled()) {
            doLog(Level.DEBUG, msg, args);
        }
    }

    public void trace(String msg, Object... args) {
        if (logger.isTraceEnabled()) {
            doLog(Level.TRACE, msg, args);
        }
    }

    // ---- Level checks ----

    public boolean isDebugEnabled() {
        return logger.isDebugEnabled();
    }

    public boolean isTraceEnabled() {
        return logger.isTraceEnabled();
    }

    // ---- Per-call structured fields ----

    /**
     * Create a per-call structured key-value pair. Pass as extra arguments after the message
     * placeholder args — they are separated from {@code {}} substitution and appended as
     * {@code key=value} pairs after the message body.
     */
    public static KeyValue kv(String key, Object value) {
        validateKey(key);
        return new KeyValue(key, value);
    }

    /** Holder for a per-call structured field. */
    public static final class KeyValue {
        private final String key;
        private final Object value;

        KeyValue(String key, Object value) {
            this.key = key;
            this.value = value;
        }
    }

    // ---- Internal ----

    private void doLog(Level level, String msg, Object... args) {
        if (args == null || args.length == 0 || !hasKeyValues(args)) {
            logger.log(level, prefix + msg, args);
            return;
        }
        // Separate regular args (for {} substitution) from KeyValue pairs.
        // Throwables are kept as the last regular arg so Log4j attaches them correctly.
        List<Object> regularArgs = new ArrayList<>();
        StringBuilder kvSuffix = new StringBuilder();
        Throwable thrown = null;
        for (Object arg : args) {
            if (arg instanceof KeyValue) {
                KeyValue pair = (KeyValue) arg;
                kvSuffix.append(' ').append(pair.key).append('=').append(pair.value);
            } else if (arg instanceof Throwable) {
                thrown = (Throwable) arg;
            } else {
                regularArgs.add(arg);
            }
        }
        String fullMsg = prefix + msg + " |" + kvSuffix;
        if (thrown != null) {
            regularArgs.add(thrown);
        }
        logger.log(level, fullMsg, regularArgs.toArray());
    }

    private static boolean hasKeyValues(Object[] args) {
        for (Object arg : args) {
            if (arg instanceof KeyValue) return true;
        }
        return false;
    }

    private static String buildPrefix(Map<String, String> fields) {
        if (fields.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder("[");
        boolean first = true;
        for (Map.Entry<String, String> entry : fields.entrySet()) {
            if (!first) sb.append(' ');
            sb.append(entry.getKey()).append('=').append(entry.getValue());
            first = false;
        }
        sb.append("] ");
        return sb.toString();
    }
}
