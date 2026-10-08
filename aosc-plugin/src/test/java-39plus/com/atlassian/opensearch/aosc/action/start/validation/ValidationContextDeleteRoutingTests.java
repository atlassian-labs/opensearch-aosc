/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */
package com.atlassian.opensearch.aosc.action.start.validation;

import com.atlassian.opensearch.aosc.model.DeleteRoutingStrategy;
import com.atlassian.opensearch.aosc.model.MigrationRequest;
import com.atlassian.opensearch.aosc.transform.TransformFactory;
import com.atlassian.opensearch.aosc.utils.AsyncClientHelper;

import org.opensearch.Version;
import org.opensearch.cluster.ClusterState;
import org.opensearch.cluster.metadata.IndexMetadata;
import org.opensearch.cluster.node.DiscoveryNodes;
import org.opensearch.common.settings.ClusterSettings;
import org.opensearch.common.settings.Settings;
import org.opensearch.test.OpenSearchTestCase;

import java.util.Collections;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class ValidationContextDeleteRoutingTests extends OpenSearchTestCase {

    public void testStrategyIsSelectedOnce() {
        ClusterState state = mock(ClusterState.class);
        DiscoveryNodes nodes = mock(DiscoveryNodes.class);
        when(state.nodes()).thenReturn(nodes);
        when(nodes.getSize()).thenReturn(1);
        when(nodes.getMinNodeVersion()).thenReturn(Version.CURRENT, Version.fromString("3.8.0"));

        ValidationContext context = ValidationContext.of(
            new MigrationRequest(),
            state,
            null,
            null,
            new TransformFactory(null),
            new ClusterSettings(Settings.EMPTY, Collections.emptySet()),
            mock(AsyncClientHelper.class)
        );

        assertEquals(DeleteRoutingStrategy.TRANSLOG_ROUTING, context.deleteRoutingStrategy());
        assertEquals(DeleteRoutingStrategy.TRANSLOG_ROUTING, context.deleteRoutingStrategy());
        verify(nodes, times(1)).getMinNodeVersion();
    }

    public void testTranslogRoutingDoesNotRequireLegacyConsent() {
        ClusterState state = mock(ClusterState.class);
        DiscoveryNodes nodes = mock(DiscoveryNodes.class);
        when(state.nodes()).thenReturn(nodes);
        when(nodes.getSize()).thenReturn(1);
        when(nodes.getMinNodeVersion()).thenReturn(Version.CURRENT);
        IndexMetadata source = metadata("source", 2);
        IndexMetadata target = metadata("target", 3);

        ValidationContext context = ValidationContext.of(
            new MigrationRequest().setSourceIndex("source").setTargetIndex("target").setAlias("alias"),
            state,
            source,
            target,
            new TransformFactory(null),
            new ClusterSettings(Settings.EMPTY, Collections.emptySet()),
            mock(AsyncClientHelper.class)
        );

        new DataLossConsentValidator().validate(context);
    }

    private static IndexMetadata metadata(String name, int shards) {
        return IndexMetadata.builder(name)
            .settings(
                Settings.builder()
                    .put(IndexMetadata.SETTING_VERSION_CREATED, Version.CURRENT)
                    .put(IndexMetadata.SETTING_NUMBER_OF_SHARDS, shards)
                    .put(IndexMetadata.SETTING_NUMBER_OF_REPLICAS, 0)
            )
            .build();
    }
}
