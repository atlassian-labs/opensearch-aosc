/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */
package com.atlassian.opensearch.aosc.action.cleanup;

import org.opensearch.common.io.stream.BytesStreamOutput;
import org.opensearch.core.common.io.stream.StreamInput;
import org.opensearch.test.OpenSearchTestCase;

import java.util.Arrays;

/**
 * Tests for {@link CleanupLeasesRequest} — covers the constructor contract and
 * round-trip transport serialization across all relevant input shapes.
 */
public class CleanupLeasesRequestTests extends OpenSearchTestCase {

    public void testNullIndicesIsRejected() {
        assertNotNull(new CleanupLeasesRequest(new CleanupLeasesBody(null, false)).validate());
    }

    public void testValidatePassesForValidInputs() {
        assertNull(new CleanupLeasesRequest(new CleanupLeasesBody(new String[0], false)).validate());
        assertNull(new CleanupLeasesRequest(new CleanupLeasesBody(new String[] { "foo" }, true)).validate());
    }

    public void testRoundTripAllIndicesNoDryRun() throws Exception {
        assertRoundTrip(new String[0], false);
    }

    public void testRoundTripSingleIndexDryRun() throws Exception {
        assertRoundTrip(new String[] { "my-index" }, true);
    }

    public void testRoundTripMultipleIndices() throws Exception {
        assertRoundTrip(new String[] { "a", "b", "c" }, false);
    }

    public void testRoundTripDryRunFlagPersists() throws Exception {
        // Verify dry_run round-trips independently of indices content.
        assertRoundTrip(new String[] { "x" }, true);
        assertRoundTrip(new String[] { "x" }, false);
    }

    private static void assertRoundTrip(String[] indices, boolean dryRun) throws Exception {
        CleanupLeasesRequest original = new CleanupLeasesRequest(new CleanupLeasesBody(indices, dryRun));
        try (BytesStreamOutput out = new BytesStreamOutput()) {
            original.writeTo(out);
            try (StreamInput in = out.bytes().streamInput()) {
                CleanupLeasesRequest copy = new CleanupLeasesRequest(in);
                assertArrayEquals(
                    "indices must round-trip: expected " + Arrays.toString(indices) + " got " + Arrays.toString(copy.body().indices()),
                    indices,
                    copy.body().indices()
                );
                assertEquals(dryRun, copy.body().dryRun());
            }
        }
    }
}
