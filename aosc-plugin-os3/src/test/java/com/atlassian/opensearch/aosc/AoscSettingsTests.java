/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */
package com.atlassian.opensearch.aosc;

import org.opensearch.common.settings.ClusterSettings;
import org.opensearch.common.settings.Settings;
import org.opensearch.test.OpenSearchTestCase;

import java.util.HashSet;

public class AoscSettingsTests extends OpenSearchTestCase {

    public void testDefaultClusterSettingsUseConservativeAdaptiveBatchProfile() {
        ClusterSettings clusterSettings = new ClusterSettings(Settings.EMPTY, new HashSet<>(AoscSettings.ALL));

        assertEquals(500, clusterSettings.get(AoscSettings.CONVERGENCE_THRESHOLD).intValue());
        assertEquals(2, clusterSettings.get(AoscSettings.MAX_CONCURRENT_PER_NODE).intValue());
        assertEquals("adaptive_batch", clusterSettings.get(AoscSettings.BACKFILL_CONTROLLER_TYPE));
        assertEquals("adaptive_batch", clusterSettings.get(AoscSettings.REPLAY_CONTROLLER_TYPE));
        assertEquals(1_000, clusterSettings.get(AoscSettings.BACKFILL_READ_PAGE_SIZE).intValue());
        assertEquals(500, clusterSettings.get(AoscSettings.BACKFILL_FIXED_BATCH_SIZE).intValue());
        assertEquals(500, clusterSettings.get(AoscSettings.REPLAY_FIXED_BATCH_SIZE).intValue());
        assertEquals(500, clusterSettings.get(AoscSettings.BACKFILL_BATCH_MAX_DOCS).intValue());
        assertEquals(500, clusterSettings.get(AoscSettings.REPLAY_BATCH_MAX_DOCS).intValue());
    }
}
