/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */
package com.atlassian.opensearch.aosc.action.start.validation;

import com.atlassian.opensearch.aosc.model.MigrationRequest;
import com.atlassian.opensearch.aosc.model.MigrationRequestOptions;
import com.atlassian.opensearch.aosc.transform.TransformFactory;

import org.opensearch.Version;
import org.opensearch.cluster.ClusterState;
import org.opensearch.cluster.metadata.IndexMetadata;
import org.opensearch.common.settings.ClusterSettings;
import org.opensearch.common.settings.Settings;
import org.opensearch.test.OpenSearchTestCase;
import org.opensearch.transport.client.Client;

import java.util.Collections;

import static org.mockito.Mockito.mock;

/**
 * Unit tests for {@link DataLossConsentValidator}: verifies it delegates to
 * {@code TransportStartMigrationAction.checkDataLossConsent} and rethrows any
 * non-null rejection.
 */
public class DataLossConsentValidatorTests extends OpenSearchTestCase {

    private static IndexMetadata buildMeta(String name, int shards) {
        return IndexMetadata.builder(name)
            .settings(
                Settings.builder()
                    .put(IndexMetadata.SETTING_VERSION_CREATED, Version.CURRENT)
                    .put(IndexMetadata.SETTING_NUMBER_OF_SHARDS, shards)
                    .put(IndexMetadata.SETTING_NUMBER_OF_REPLICAS, 0)
            )
            .build();
    }

    private ValidationContext ctx(IndexMetadata src, IndexMetadata tgt, MigrationRequestOptions opts) {
        MigrationRequest req = new MigrationRequest().setSourceIndex("src").setTargetIndex("tgt").setOptions(opts);
        return ValidationContext.of(
            req,
            ClusterState.EMPTY_STATE,
            src,
            tgt,
            new TransformFactory(null),
            new ClusterSettings(Settings.EMPTY, Collections.emptySet()),
            mock(Client.class)
        );
    }

    public void testRejectsBulkApiWithoutConsent() {
        // 2 -> 3 is BULK_API; null options => no consent => reject.
        IllegalArgumentException ex = expectThrows(
            IllegalArgumentException.class,
            () -> new DataLossConsentValidator().validate(ctx(buildMeta("src", 2), buildMeta("tgt", 3), null))
        );
        assertTrue(ex.getMessage(), ex.getMessage().contains("accept_data_loss_if_custom_routing_is_used=true"));
    }

    public void testAllowsBulkApiWithConsent() {
        MigrationRequestOptions opts = new MigrationRequestOptions().setAcceptDataLossIfCustomRoutingIsUsed(true);
        new DataLossConsentValidator().validate(ctx(buildMeta("src", 2), buildMeta("tgt", 3), opts));
    }

    public void testAllowsSameShard() {
        // 3 -> 3 = SAME_SHARD, never requires consent.
        new DataLossConsentValidator().validate(ctx(buildMeta("src", 3), buildMeta("tgt", 3), null));
    }
}
