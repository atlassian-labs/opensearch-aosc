/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */
package com.atlassian.opensearch.aosc.action.cleanup;

import org.opensearch.common.io.stream.BytesStreamOutput;
import org.opensearch.common.xcontent.XContentType;
import org.opensearch.core.common.bytes.BytesReference;
import org.opensearch.core.common.io.stream.StreamInput;
import org.opensearch.core.xcontent.DeprecationHandler;
import org.opensearch.core.xcontent.MediaTypeRegistry;
import org.opensearch.core.xcontent.ToXContent;
import org.opensearch.core.xcontent.XContentBuilder;
import org.opensearch.test.OpenSearchTestCase;

import java.util.List;
import java.util.Map;

/**
 * Tests for {@link CleanupLeasesResponse} — round-trip serialization, builder counters,
 * and XContent rendering shape.
 */
public class CleanupLeasesResponseTests extends OpenSearchTestCase {

    public void testBuilderTalliesReleasedAndFailed() {
        CleanupLeasesResponse response = new CleanupLeasesResponse(
            CleanupLeasesResult.builder()
                .dryRun(false)
                .leases(
                    List.of(
                        infoFor("idx1", 0, "aosc-migration-a-0", true, null),
                        infoFor("idx1", 1, "aosc-migration-a-1", true, null),
                        infoFor("idx2", 0, "aosc-migration-b-0", false, "boom")
                    )
                )
                .build()
        );

        assertFalse(response.body().dryRun());
        assertEquals(2, response.body().releasedCount());
        assertEquals(1, response.body().failedCount());
        assertEquals(3, response.body().leases().size());
    }

    public void testDryRunBuilderDoesNotIncrementCounters() {
        CleanupLeasesResponse response = new CleanupLeasesResponse(
            CleanupLeasesResult.builder()
                .dryRun(true)
                .leases(
                    List.of(infoFor("idx1", 0, "aosc-migration-a-0", false, null), infoFor("idx1", 1, "aosc-migration-a-1", false, null))
                )
                .build()
        );

        assertTrue(response.body().dryRun());
        assertEquals(0, response.body().releasedCount());
        assertEquals(0, response.body().failedCount());
        assertEquals(2, response.body().leases().size());
    }

    public void testRoundTripPreservesAllFields() throws Exception {
        CleanupLeasesResponse original = new CleanupLeasesResponse(
            CleanupLeasesResult.builder()
                .dryRun(false)
                .leases(
                    List.of(
                        infoFor("idx1", 0, "aosc-migration-a-0", true, null),
                        infoFor("idx2", 1, "aosc-migration-b-1", false, "RetentionLeaseNotFoundException: gone")
                    )
                )
                .build()
        );

        try (BytesStreamOutput out = new BytesStreamOutput()) {
            original.writeTo(out);
            try (StreamInput in = out.bytes().streamInput()) {
                CleanupLeasesResponse copy = new CleanupLeasesResponse(in);
                assertEquals(original.body().dryRun(), copy.body().dryRun());
                assertEquals(original.body().releasedCount(), copy.body().releasedCount());
                assertEquals(original.body().failedCount(), copy.body().failedCount());
                assertEquals(original.body().leases().size(), copy.body().leases().size());

                CleanupLeasesResult.LeaseInfo first = copy.body().leases().get(0);
                assertEquals("idx1", first.index());
                assertEquals(0, first.shard());
                assertEquals("aosc-migration-a-0", first.leaseId());
                assertTrue(first.released());
                assertNull(first.error());

                CleanupLeasesResult.LeaseInfo second = copy.body().leases().get(1);
                assertFalse(second.released());
                assertEquals("RetentionLeaseNotFoundException: gone", second.error());
            }
        }
    }

    @SuppressWarnings("unchecked")
    public void testToXContentShape() throws Exception {
        CleanupLeasesResponse response = new CleanupLeasesResponse(
            CleanupLeasesResult.builder().dryRun(true).leases(List.of(infoFor("foo", 2, "aosc-migration-mig123-2", false, null))).build()
        );

        XContentBuilder builder = MediaTypeRegistry.contentBuilder(XContentType.JSON);
        response.toXContent(builder, ToXContent.EMPTY_PARAMS);
        BytesReference bytes = BytesReference.bytes(builder);

        Map<String, Object> parsed = XContentType.JSON.xContent()
            .createParser(xContentRegistry(), DeprecationHandler.IGNORE_DEPRECATIONS, bytes.streamInput())
            .map();

        assertEquals(Boolean.TRUE, parsed.get("dry_run"));
        assertEquals(0, ((Number) parsed.get("released")).intValue());
        assertEquals(0, ((Number) parsed.get("failed")).intValue());
        List<Object> leases = (List<Object>) parsed.get("leases");
        assertEquals(1, leases.size());

        Map<String, Object> entry = (Map<String, Object>) leases.get(0);
        assertEquals("foo", entry.get("index"));
        assertEquals(2, ((Number) entry.get("shard")).intValue());
        assertEquals("aosc-migration-mig123-2", entry.get("lease_id"));
        assertEquals("aosc-migration", entry.get("source"));
        assertEquals(42L, ((Number) entry.get("retaining_seq_no")).longValue());
        assertEquals(Boolean.FALSE, entry.get("released"));
        assertFalse("error key should be omitted when null", entry.containsKey("error"));
    }

    public void testToXContentIncludesErrorWhenPresent() throws Exception {
        CleanupLeasesResponse response = new CleanupLeasesResponse(
            CleanupLeasesResult.builder()
                .dryRun(false)
                .leases(List.of(infoFor("foo", 0, "aosc-migration-mig-0", false, "something broke")))
                .build()
        );

        XContentBuilder builder = MediaTypeRegistry.contentBuilder(XContentType.JSON);
        response.toXContent(builder, ToXContent.EMPTY_PARAMS);
        BytesReference bytes = BytesReference.bytes(builder);

        @SuppressWarnings("unchecked")
        Map<String, Object> parsed = XContentType.JSON.xContent()
            .createParser(xContentRegistry(), DeprecationHandler.IGNORE_DEPRECATIONS, bytes.streamInput())
            .map();

        @SuppressWarnings("unchecked")
        Map<String, Object> entry = (Map<String, Object>) ((List<Object>) parsed.get("leases")).get(0);
        assertEquals("something broke", entry.get("error"));
    }

    private static CleanupLeasesResult.LeaseInfo infoFor(String index, int shard, String leaseId, boolean released, String error) {
        return new CleanupLeasesResult.LeaseInfo(index, shard, leaseId, "aosc-migration", 42L, released, error);
    }
}
