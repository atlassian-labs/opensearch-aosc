/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */
package com.atlassian.opensearch.aosc.action.start.validation;

/** Builds the transform via the factory at {@code _start} so compile/dry-run/subclass errors surface as 400. */
public final class TransformFunctionValidator implements MigrationStartValidator {
    @Override
    public void validate(ValidationContext ctx) {
        ctx.transformFactory().create(ctx.request().getTransformScript(), ctx.sourceMeta(), ctx.targetMeta());
    }
}
