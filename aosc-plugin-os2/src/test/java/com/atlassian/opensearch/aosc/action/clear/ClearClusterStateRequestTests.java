/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */
package com.atlassian.opensearch.aosc.action.clear;

import org.opensearch.common.io.stream.BytesStreamOutput;
import org.opensearch.core.common.io.stream.StreamInput;
import org.opensearch.test.OpenSearchTestCase;

public class ClearClusterStateRequestTests extends OpenSearchTestCase {

    public void testDefaultValues() {
        ClearClusterStateRequest req = new ClearClusterStateRequest(ClearClusterStateBody.builder().build());
        assertTrue("dry_run should default to true", req.body().dryRun());
        assertTrue(req.body().tryClose());
        assertNull(req.body().migrationId());
        assertFalse(req.body().detailed());
        assertNull(req.validate());
    }

    public void testCustomValues() {
        ClearClusterStateRequest req = new ClearClusterStateRequest(new ClearClusterStateBody(true, false, "mig-123", true));
        assertTrue(req.body().dryRun());
        assertFalse(req.body().tryClose());
        assertEquals("mig-123", req.body().migrationId());
        assertTrue(req.body().detailed());
        assertNull(req.validate());
    }

    public void testValidateRejectsEmptyMigrationId() {
        ClearClusterStateRequest req = new ClearClusterStateRequest(new ClearClusterStateBody(false, true, "", false));
        assertNotNull(req.validate());
        assertTrue(req.validate().getMessage().contains("migration_id must not be empty"));
    }

    public void testValidateAcceptsNullMigrationId() {
        ClearClusterStateRequest req = new ClearClusterStateRequest(new ClearClusterStateBody(false, true, null, false));
        assertNull(req.validate());
    }

    public void testRoundTripSerializationDefaults() throws Exception {
        ClearClusterStateRequest original = new ClearClusterStateRequest(ClearClusterStateBody.builder().build());
        try (BytesStreamOutput out = new BytesStreamOutput()) {
            original.writeTo(out);
            try (StreamInput in = out.bytes().streamInput()) {
                ClearClusterStateRequest copy = new ClearClusterStateRequest(in);
                assertEquals(original.body().dryRun(), copy.body().dryRun());
                assertEquals(original.body().tryClose(), copy.body().tryClose());
                assertEquals(original.body().migrationId(), copy.body().migrationId());
                assertEquals(original.body().detailed(), copy.body().detailed());
            }
        }
    }

    public void testRoundTripSerializationWithAllFields() throws Exception {
        ClearClusterStateRequest original = new ClearClusterStateRequest(new ClearClusterStateBody(true, false, "mig-456", true));
        try (BytesStreamOutput out = new BytesStreamOutput()) {
            original.writeTo(out);
            try (StreamInput in = out.bytes().streamInput()) {
                ClearClusterStateRequest copy = new ClearClusterStateRequest(in);
                assertTrue(copy.body().dryRun());
                assertFalse(copy.body().tryClose());
                assertEquals("mig-456", copy.body().migrationId());
                assertTrue(copy.body().detailed());
            }
        }
    }
}
