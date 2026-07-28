/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */
package com.atlassian.opensearch.aosc.action.start.validation;

import com.atlassian.opensearch.aosc.AoscSettings;
import com.atlassian.opensearch.aosc.model.MigrationRequest;
import com.atlassian.opensearch.aosc.model.MigrationRequestOptions;
import com.atlassian.opensearch.aosc.transform.TransformFactory;

import org.opensearch.client.Client;
import org.opensearch.cluster.ClusterState;
import org.opensearch.common.settings.ClusterSettings;
import org.opensearch.common.settings.Settings;
import org.opensearch.test.OpenSearchTestCase;

import java.util.HashSet;

import static org.mockito.Mockito.mock;

/**
 * Unit tests for {@link ResolveOptionsValidator}: verifies it mutates the request
 * in-place with resolved options (cluster defaults merged with overrides).
 */
public class ResolveOptionsValidatorTests extends OpenSearchTestCase {

    private static ClusterSettings clusterSettings() {
        return new ClusterSettings(Settings.EMPTY, new HashSet<>(AoscSettings.ALL));
    }

    public void testResolvesOptionsOnRequest() {
        MigrationRequest req = new MigrationRequest().setSourceIndex("src").setTargetIndex("tgt");
        // No options on the request — should be filled with defaults after validate.
        assertNull("precondition: no options on request", req.getOptions());

        ValidationContext ctx = ValidationContext.of(
            req,
            ClusterState.EMPTY_STATE,
            null,
            null,
            new TransformFactory(null),
            clusterSettings(),
            mock(Client.class)
        );

        new ResolveOptionsValidator().validate(ctx);

        MigrationRequestOptions resolved = req.getOptions();
        assertNotNull("ResolveOptionsValidator should populate options", resolved);
    }

    public void testOverridesPreserved() {
        MigrationRequestOptions overrides = new MigrationRequestOptions().setAcceptDataLossIfCustomRoutingIsUsed(true);
        MigrationRequest req = new MigrationRequest().setSourceIndex("src").setTargetIndex("tgt").setOptions(overrides);

        ValidationContext ctx = ValidationContext.of(
            req,
            ClusterState.EMPTY_STATE,
            null,
            null,
            new TransformFactory(null),
            clusterSettings(),
            mock(Client.class)
        );

        new ResolveOptionsValidator().validate(ctx);
        assertEquals(Boolean.TRUE, req.getOptions().getAcceptDataLossIfCustomRoutingIsUsed());
    }
}
