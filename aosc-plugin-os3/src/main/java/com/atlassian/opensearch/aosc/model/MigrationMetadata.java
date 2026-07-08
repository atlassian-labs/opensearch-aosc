/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */
package com.atlassian.opensearch.aosc.model;

import com.atlassian.opensearch.aosc.utils.jackson.JacksonHelper;
import com.atlassian.opensearch.aosc.utils.jackson.JacksonToXContentObject;
import com.atlassian.opensearch.aosc.utils.jackson.JacksonWriteable;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

import lombok.EqualsAndHashCode;
import lombok.ToString;

import org.opensearch.core.common.io.stream.StreamInput;

import java.io.IOException;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Extensible key-value metadata bag for tracking resource state during migrations.
 *
 * <p>Attached to {@link ShardProgressDocument} (shard-level) and
 * {@link AoscMigrationsClusterState.ShardMigrationClusterState} (cluster state).
 * Tracks held resources (leases, write blocks) so that on crash/shutdown the
 * last-persisted metadata shows exactly what is orphaned.</p>
 *
 * <p>New keys can be added freely — old readers ignore unknown keys.</p>
 *
 * <p>Immutable. Use {@link #toBuilder()} to create modified copies.</p>
 */
@EqualsAndHashCode
@ToString
public final class MigrationMetadata implements JacksonWriteable, JacksonToXContentObject {

    // ---- Well-known keys (shard-level) ----
    public static final String ACTIVE_LEASE = "active_lease";

    // ---- Well-known keys (migration-level) ----
    public static final String WRITE_BLOCK_APPLIED = "write_block.applied";
    public static final String REBALANCE_DISABLED = "rebalance.disabled";
    public static final String ALIAS_SWAPPED = "alias.swapped";

    /** Key prefix for original target index settings captured before applying transient optimizations. */
    public static final String ORIGINAL_TARGET_SETTINGS_PREFIX = "target.original.";

    public static final MigrationMetadata EMPTY = new MigrationMetadata(Collections.emptyMap());

    private final Map<String, String> entries;

    @JsonCreator
    private MigrationMetadata(Map<String, String> entries) {
        this.entries = Collections.unmodifiableMap(new HashMap<>(entries));
    }

    public MigrationMetadata(StreamInput in) throws IOException {
        this(JacksonHelper.readFrom(in, MigrationMetadata.class).entries);
    }

    // ---- Accessors ----

    /** All entries. Never null, may be empty. */
    @JsonValue
    public Map<String, String> entries() {
        return entries;
    }

    /** Get a value by key, or null if absent. */
    public String get(String key) {
        return entries.get(key);
    }

    /** Get a boolean value, defaulting to false if absent. */
    public boolean getBoolean(String key) {
        return Boolean.parseBoolean(entries.get(key));
    }

    /** Get a long value, defaulting to the given fallback if absent or unparseable. */
    public long getLong(String key, long fallback) {
        String val = entries.get(key);
        if (val == null) return fallback;
        try {
            return Long.parseLong(val);
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    public boolean isEmpty() {
        return entries.isEmpty();
    }

    /**
     * Extract original target settings from metadata entries.
     * Returns keys with the `target.original.` prefix stripped.
     * An empty string value means the setting was at its default and should be reset via putNull.
     */
    public Map<String, String> originalTargetSettings() {
        Map<String, String> result = new HashMap<>();
        for (Map.Entry<String, String> e : entries.entrySet()) {
            if (e.getKey().startsWith(ORIGINAL_TARGET_SETTINGS_PREFIX)) {
                String settingKey = e.getKey().substring(ORIGINAL_TARGET_SETTINGS_PREFIX.length());
                String value = e.getValue().isEmpty() ? null : e.getValue();
                result.put(settingKey, value);
            }
        }
        return result;
    }

    // ---- Builder ----

    public static Builder builder() {
        return new Builder();
    }

    public Builder toBuilder() {
        return new Builder(entries);
    }

    public static final class Builder {
        private final Map<String, String> entries;

        private Builder() {
            this.entries = new HashMap<>();
        }

        private Builder(Map<String, String> seed) {
            this.entries = new HashMap<>(seed);
        }

        public Builder put(String key, String value) {
            Objects.requireNonNull(key, "key");
            Objects.requireNonNull(value, "value");
            entries.put(key, value);
            return this;
        }

        public Builder put(String key, boolean value) {
            return put(key, Boolean.toString(value));
        }

        public Builder put(String key, long value) {
            return put(key, Long.toString(value));
        }

        public Builder remove(String key) {
            entries.remove(key);
            return this;
        }

        /**
         * Adds original target settings to the builder.
         * Null values (settings at their default) are stored as empty string.
         */
        public Builder putOriginalTargetSettings(Map<String, String> settings) {
            for (Map.Entry<String, String> e : settings.entrySet()) {
                entries.put(ORIGINAL_TARGET_SETTINGS_PREFIX + e.getKey(), e.getValue() != null ? e.getValue() : "");
            }
            return this;
        }

        public MigrationMetadata build() {
            return new MigrationMetadata(entries);
        }
    }

    /** Deserialize from XContent parser. */
    public static MigrationMetadata fromXContent(org.opensearch.core.xcontent.XContentParser parser) throws IOException {
        return JacksonHelper.fromXContent(parser, MigrationMetadata.class);
    }
}
