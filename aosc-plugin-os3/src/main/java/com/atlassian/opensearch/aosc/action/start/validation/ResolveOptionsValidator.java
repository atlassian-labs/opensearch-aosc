/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */
package com.atlassian.opensearch.aosc.action.start.validation;

import com.atlassian.opensearch.aosc.model.MigrationRequestOptions;

/**
 * Merges cluster-level default options with API-provided overrides.
 * Mutates the request in-place (intentional — downstream validators and
 * the coordinator see the resolved options).
 */
public final class ResolveOptionsValidator implements MigrationStartValidator {
    @Override
    public void validate(ValidationContext ctx) {
        MigrationRequestOptions resolved = MigrationRequestOptions.defaultOptions(ctx.clusterSettings())
            .mergeWith(ctx.request().getOptions());
        ctx.request().setOptions(resolved);
    }
}
