/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */
package com.atlassian.opensearch.aosc.action.start.validation;

import java.util.concurrent.CompletableFuture;

/**
 * Asynchronous pre-flight validator. Returns a future that completes normally on success
 * or exceptionally with {@link IllegalArgumentException}/{@link IllegalStateException} to reject.
 */
@FunctionalInterface
public interface AsyncMigrationStartValidator {
    CompletableFuture<Void> validate(ValidationContext ctx);
}
