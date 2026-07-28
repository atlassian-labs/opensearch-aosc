/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */
package com.atlassian.opensearch.aosc.utils;

import org.opensearch.test.OpenSearchTestCase;

/**
 * Validates the suppression logic for orphaned transport-level spans.
 *
 * <p>When a node is restarted mid-bulk-write (e.g. in FailoverIT), the OpenSearch framework's
 * transport spans ({@code dispatchedShardOperationOnPrimary}) are started but never ended.
 * The {@code suppressOrphanedTransportSpanValidation} method in {@code AoscIntegTestBase}
 * catches these known errors and suppresses them.</p>
 *
 * <p>This test validates the string-matching logic used to identify suppressible errors.</p>
 */
public class ClearOrphanedSpansTests extends OpenSearchTestCase {

    private static final String TRANSPORT_SPAN_ERROR =
        "SpanData validation failed for validator org.opensearch.test.telemetry.tracing.validators.AllSpansAreEndedProperly\n"
            + "MockSpanData{spanName='dispatchedShardOperationOnPrimary', hasEnded=false}";

    private static final String AOSC_SPAN_ERROR =
        "SpanData validation failed for validator org.opensearch.test.telemetry.tracing.validators.AllSpansAreEndedProperly\n"
            + "MockSpanData{spanName='migration_coordinator_run', hasEnded=false}";

    private static final String UNIQUE_ID_ERROR =
        "SpanData validation failed for validator org.opensearch.test.telemetry.tracing.validators.AllSpansHaveUniqueId";

    /**
     * Returns true if the error should be suppressed (transport span from node restart).
     */
    private static boolean shouldSuppress(String errorMessage) {
        return errorMessage != null
            && errorMessage.contains("AllSpansAreEndedProperly")
            && errorMessage.contains("dispatchedShardOperation");
    }

    public void testSuppressesTransportSpanError() {
        assertTrue("Transport span error should be suppressed", shouldSuppress(TRANSPORT_SPAN_ERROR));
    }

    public void testDoesNotSuppressAoscSpanError() {
        assertFalse("AOSC span error should NOT be suppressed", shouldSuppress(AOSC_SPAN_ERROR));
    }

    public void testDoesNotSuppressUniqueIdError() {
        assertFalse("UniqueId error should NOT be suppressed", shouldSuppress(UNIQUE_ID_ERROR));
    }

    public void testDoesNotSuppressNullMessage() {
        assertFalse("Null message should NOT be suppressed", shouldSuppress(null));
    }

    public void testDoesNotSuppressUnrelatedError() {
        assertFalse("Unrelated error should NOT be suppressed", shouldSuppress("Something else went wrong"));
    }

    public void testSuppressesReplicaSpanError() {
        String replicaError =
            "SpanData validation failed for validator org.opensearch.test.telemetry.tracing.validators.AllSpansAreEndedProperly\n"
                + "MockSpanData{spanName='dispatchedShardOperationOnReplica', hasEnded=false}";
        assertTrue("Replica transport span error should be suppressed", shouldSuppress(replicaError));
    }
}
