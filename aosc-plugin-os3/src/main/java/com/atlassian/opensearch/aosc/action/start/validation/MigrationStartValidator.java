/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */
package com.atlassian.opensearch.aosc.action.start.validation;

/**
 * Synchronous pre-flight validator run during {@code _start}. Implementations
 * throw {@link IllegalArgumentException} or {@link IllegalStateException} to reject.
 */
@FunctionalInterface
public interface MigrationStartValidator {
    void validate(ValidationContext ctx);
}
