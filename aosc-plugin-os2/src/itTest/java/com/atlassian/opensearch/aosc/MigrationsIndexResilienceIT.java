/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */
package com.atlassian.opensearch.aosc;

import com.atlassian.opensearch.aosc.service.coordinator.MigrationDocumentService;

import org.opensearch.action.admin.indices.settings.get.GetSettingsRequest;
import org.opensearch.test.OpenSearchIntegTestCase.ClusterScope;
import org.opensearch.test.OpenSearchIntegTestCase.Scope;

/**
 * Verifies that re-running index creation and schema enforcement on {@code .aosc-migrations} — as
 * happens on every cluster-manager election — is idempotent, and that the index carries
 * {@code auto_expand_replicas: "0-1"} so losing a single node can't take the cluster RED.
 */
@ClusterScope(scope = Scope.SUITE, numDataNodes = 2, numClientNodes = 0)
public class MigrationsIndexResilienceIT extends AoscIntegTestBase {

    private static final String MIGRATIONS_INDEX = ".aosc-migrations";

    public void testSchemaEnforcementIsIdempotentAndKeepsAutoExpandReplicas() {
        MigrationDocumentService service = internalCluster().getInstances(MigrationDocumentService.class).iterator().next();

        // Re-run createIndexWithRetry twice, exactly as repeated CM elections would. Each
        // ensureIndexExists() after a reset re-enters createIndexWithRetry; the index already exists,
        // so enforceSchema reconciles settings + mappings against the live index. A non-idempotent
        // (static) setting leaking into the reconcile set would fail updateSettings here.
        service.resetIndexCreated();
        service.ensureIndexExists().join();
        service.resetIndexCreated();
        service.ensureIndexExists().join();

        String autoExpand = client().admin()
            .indices()
            .getSettings(new GetSettingsRequest().indices(MIGRATIONS_INDEX))
            .actionGet()
            .getSetting(MIGRATIONS_INDEX, "index.auto_expand_replicas");
        assertEquals("0-1", autoExpand);

        // With two nodes auto_expand_replicas resolves to one replica, so the index stays green
        // (and would survive a node loss) rather than going RED as it did with 0 replicas.
        ensureGreen(MIGRATIONS_INDEX);
    }
}
