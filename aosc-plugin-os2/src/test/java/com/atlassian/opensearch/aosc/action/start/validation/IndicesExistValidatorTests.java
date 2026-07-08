/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */
package com.atlassian.opensearch.aosc.action.start.validation;

import com.atlassian.opensearch.aosc.model.MigrationRequest;
import com.atlassian.opensearch.aosc.transform.TransformFactory;

import org.opensearch.Version;
import org.opensearch.client.Client;
import org.opensearch.cluster.ClusterState;
import org.opensearch.cluster.metadata.IndexMetadata;
import org.opensearch.common.settings.ClusterSettings;
import org.opensearch.common.settings.Settings;
import org.opensearch.test.OpenSearchTestCase;

import java.util.Collections;

import static org.mockito.Mockito.mock;

/**
 * Unit tests for {@link IndicesExistValidator}.
 */
public class IndicesExistValidatorTests extends OpenSearchTestCase {

    private final IndicesExistValidator validator = new IndicesExistValidator();

    private static IndexMetadata buildMeta(String name) {
        return IndexMetadata.builder(name)
            .settings(
                Settings.builder()
                    .put(IndexMetadata.SETTING_VERSION_CREATED, Version.CURRENT)
                    .put(IndexMetadata.SETTING_NUMBER_OF_SHARDS, 1)
                    .put(IndexMetadata.SETTING_NUMBER_OF_REPLICAS, 0)
            )
            .build();
    }

    private ValidationContext ctx(IndexMetadata sourceMeta, IndexMetadata targetMeta) {
        MigrationRequest req = new MigrationRequest().setSourceIndex("src").setTargetIndex("tgt");
        return ValidationContext.of(
            req,
            ClusterState.EMPTY_STATE,
            sourceMeta,
            targetMeta,
            new TransformFactory(null),
            new ClusterSettings(Settings.EMPTY, Collections.emptySet()),
            mock(Client.class)
        );
    }

    public void testMissingSource() {
        IllegalArgumentException ex = expectThrows(IllegalArgumentException.class, () -> validator.validate(ctx(null, buildMeta("tgt"))));
        assertTrue(ex.getMessage(), ex.getMessage().contains("source index [src] not found"));
    }

    public void testMissingTarget() {
        IllegalArgumentException ex = expectThrows(IllegalArgumentException.class, () -> validator.validate(ctx(buildMeta("src"), null)));
        assertTrue(ex.getMessage(), ex.getMessage().contains("target index [tgt] not found"));
    }

    public void testBothPresentPasses() {
        // Should not throw
        validator.validate(ctx(buildMeta("src"), buildMeta("tgt")));
    }
}
