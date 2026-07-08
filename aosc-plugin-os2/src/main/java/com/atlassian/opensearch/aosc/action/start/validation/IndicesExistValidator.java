/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */
package com.atlassian.opensearch.aosc.action.start.validation;

/**
 * Rejects if source or target index does not exist in the cluster state.
 * Must run before any validator that reads {@code sourceMeta()} / {@code targetMeta()}.
 */
public final class IndicesExistValidator implements MigrationStartValidator {
    @Override
    public void validate(ValidationContext ctx) {
        if (ctx.sourceMeta() == null) {
            throw new IllegalArgumentException("source index [" + ctx.request().getSourceIndex() + "] not found");
        }
        if (ctx.targetMeta() == null) {
            throw new IllegalArgumentException("target index [" + ctx.request().getTargetIndex() + "] not found");
        }
    }
}
