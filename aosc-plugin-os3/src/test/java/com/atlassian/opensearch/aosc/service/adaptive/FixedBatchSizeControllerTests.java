/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */
package com.atlassian.opensearch.aosc.service.adaptive;

import org.opensearch.test.OpenSearchTestCase;

/**
 * Tests for {@link FixedBatchSizeController}.
 */
public class FixedBatchSizeControllerTests extends OpenSearchTestCase {

    public void testNextBatchSizeReturnsFixedSize() {
        FixedBatchSizeController controller = new FixedBatchSizeController(() -> 500);
        assertEquals(500, controller.nextBatchSize());
        assertEquals(500, controller.nextBatchSize());
    }

    public void testObserveSuccessKeepsSize() {
        FixedBatchSizeController controller = new FixedBatchSizeController(() -> 500);
        controller.observe(BulkOutcome.success(100, 80, 500, 1024 * 1024, 1));
        assertEquals(500, controller.nextBatchSize());
    }

    public void testObserveOverloadKeepsSize() {
        FixedBatchSizeController controller = new FixedBatchSizeController(() -> 500);
        controller.observe(BulkOutcome.failure(100, -1, 500, 1024 * 1024, 1, RejectionKind.OVERLOAD, "rejected"));
        assertEquals(500, controller.nextBatchSize());
    }

    public void testObserveTransientKeepsSize() {
        FixedBatchSizeController controller = new FixedBatchSizeController(() -> 500);
        controller.observe(BulkOutcome.failure(100, -1, 500, 1024 * 1024, 1, RejectionKind.TRANSIENT, "timeout"));
        assertEquals(500, controller.nextBatchSize());
    }

    public void testObserveFatalKeepsSize() {
        FixedBatchSizeController controller = new FixedBatchSizeController(() -> 500);
        controller.observe(BulkOutcome.failure(100, -1, 500, 1024 * 1024, 1, RejectionKind.FATAL, "mapping error"));
        assertEquals(500, controller.nextBatchSize());
    }

    public void testSizeUnchangedAfterMultipleFailures() {
        FixedBatchSizeController controller = new FixedBatchSizeController(() -> 500);
        controller.observe(BulkOutcome.failure(100, -1, 500, 1024 * 1024, 1, RejectionKind.OVERLOAD, "rejected"));
        controller.observe(BulkOutcome.failure(100, -1, 500, 1024 * 1024, 1, RejectionKind.TRANSIENT, "timeout"));
        controller.observe(BulkOutcome.success(100, 80, 500, 1024 * 1024, 1));
        assertEquals(500, controller.nextBatchSize());
    }
}
