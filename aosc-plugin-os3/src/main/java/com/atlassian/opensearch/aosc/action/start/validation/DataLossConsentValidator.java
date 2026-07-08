/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */
package com.atlassian.opensearch.aosc.action.start.validation;

import com.atlassian.opensearch.aosc.model.MigrationRequestOptions;
import com.atlassian.opensearch.aosc.model.ShardRoutingMode;
import com.atlassian.opensearch.aosc.utils.SyntheticRoutingHelper;

import org.opensearch.cluster.metadata.IndexMetadata;

import java.util.Objects;

/** Rejects if BULK_API routing mode without explicit data-loss consent. */
public final class DataLossConsentValidator implements MigrationStartValidator {

    @Override
    public void validate(ValidationContext ctx) {
        IllegalArgumentException rejection = checkDataLossConsent(ctx.sourceMeta(), ctx.targetMeta(), ctx.request().getOptions());
        if (rejection != null) {
            throw rejection;
        }
    }

    public static IllegalArgumentException checkDataLossConsent(
        IndexMetadata sourceMeta,
        IndexMetadata targetMeta,
        MigrationRequestOptions opts
    ) {
        Objects.requireNonNull(sourceMeta, "sourceMeta");
        Objects.requireNonNull(targetMeta, "targetMeta");
        ShardRoutingMode mode = SyntheticRoutingHelper.detectRoutingMode(sourceMeta, targetMeta);
        if (mode != ShardRoutingMode.BULK_API) {
            return null;
        }
        boolean accepted = opts != null && Boolean.TRUE.equals(opts.getAcceptDataLossIfCustomRoutingIsUsed());
        if (accepted) {
            return null;
        }
        int srcShards = sourceMeta.getNumberOfShards();
        int tgtShards = targetMeta.getNumberOfShards();
        String reason = tgtShards < srcShards
            ? "Shrinking from " + srcShards + " to " + tgtShards + " shards"
            : "Non-multiple shard migration (" + srcShards + " → " + tgtShards + " shards)";
        return new IllegalArgumentException(
            reason
                + " cannot safely preserve documents with custom routing. "
                + "Set options.accept_data_loss_if_custom_routing_is_used=true to proceed."
        );
    }
}
