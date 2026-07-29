/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */
package com.atlassian.opensearch.aosc.service.worker;

import com.atlassian.opensearch.aosc.compat.MockClientFactory;
import com.atlassian.opensearch.aosc.utils.AoscLogger;
import com.atlassian.opensearch.aosc.utils.AsyncClientHelper;

import org.opensearch.core.index.Index;
import org.opensearch.core.index.shard.ShardId;
import org.opensearch.test.OpenSearchTestCase;

public class RetentionLeaseManagerTests extends OpenSearchTestCase {

    // ---- generateLeaseId ----
    public void testLeaseIdGenerationIsDeterministic() {
        String leaseId = RetentionLeaseManager.generateLeaseId("mig-123", 0);
        assertEquals("aosc-migration-mig-123-0", leaseId);
        assertEquals(leaseId, RetentionLeaseManager.generateLeaseId("mig-123", 0));
    }

    public void testLeaseIdWithVariousMigrationIds() {
        assertEquals("aosc-migration-abc-0", RetentionLeaseManager.generateLeaseId("abc", 0));
        assertEquals("aosc-migration-test-migration-id-5", RetentionLeaseManager.generateLeaseId("test-migration-id", 5));
    }

    public void testLeaseIdsDifferByShard() {
        String leaseId0 = RetentionLeaseManager.generateLeaseId("mig-123", 0);
        String leaseId1 = RetentionLeaseManager.generateLeaseId("mig-123", 1);
        assertNotEquals(leaseId0, leaseId1);
    }

    // ---- Constants ----
    public void testLeaseIdPrefixConstant() {
        assertEquals("aosc-migration-", RetentionLeaseManager.LEASE_ID_PREFIX);
    }

    public void testLeaseSourceConstant() {
        assertEquals("aosc-migration", RetentionLeaseManager.LEASE_SOURCE);
    }

    // ---- Constructor validation ----
    public void testConstructorRejectsNullClient() {
        ShardId shardId = new ShardId(new Index("test", "uuid"), 0);
        expectThrows(
            NullPointerException.class,
            () -> new RetentionLeaseManager(AoscLogger.create(RetentionLeaseManager.class), null, shardId, "mig-1")
        );
    }

    public void testConstructorRejectsNullShardId() {
        var client = MockClientFactory.mockClient();
        expectThrows(
            NullPointerException.class,
            () -> new RetentionLeaseManager(AoscLogger.create(RetentionLeaseManager.class), AsyncClientHelper.wrap(client), null, "mig-1")
        );
    }

    public void testConstructorRejectsNullMigrationId() {
        var client = MockClientFactory.mockClient();
        ShardId shardId = new ShardId(new Index("test", "uuid"), 0);
        expectThrows(
            NullPointerException.class,
            () -> new RetentionLeaseManager(AoscLogger.create(RetentionLeaseManager.class), AsyncClientHelper.wrap(client), shardId, null)
        );
    }

    // ---- leaseId() getter ----
    public void testLeaseIdGetter() {
        var client = MockClientFactory.mockClient();
        ShardId shardId = new ShardId(new Index("test", "uuid"), 3);
        RetentionLeaseManager manager = new RetentionLeaseManager(
            AoscLogger.create(RetentionLeaseManager.class),
            AsyncClientHelper.wrap(client),
            shardId,
            "mig-abc"
        );
        assertEquals("aosc-migration-mig-abc-3", manager.leaseId());
    }
}
