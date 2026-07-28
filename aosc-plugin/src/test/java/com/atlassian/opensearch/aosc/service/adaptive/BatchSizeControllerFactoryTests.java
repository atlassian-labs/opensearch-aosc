/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */
package com.atlassian.opensearch.aosc.service.adaptive;

import com.atlassian.opensearch.aosc.utils.AoscLogger;

import org.opensearch.test.OpenSearchTestCase;

/**
 * Tests for {@link FixedBatchSizeController} and {@link AimdBatchSizeController} construction.
 */
public class BatchSizeControllerFactoryTests extends OpenSearchTestCase {

    public void testFixedControllerReturnsSameSizeAlways() {
        BatchSizeController controller = new FixedBatchSizeController(() -> 500);
        assertEquals(500, controller.nextBatchSize());
        controller.observe(BulkOutcome.success(100, 10, 100, 1024, 1));
        assertEquals(500, controller.nextBatchSize());
    }

    public void testAimdControllerAdaptsBatchSize() {
        AimdConfig cfg = AimdConfig.builder()
            .enabled(true)
            .minTargetBytes(() -> 512 * 1024L)
            .maxTargetBytes(() -> 256 * 1024 * 1024L)
            .maxDocs(() -> 1000)
            .startBytesPerDoc(() -> 10 * 1024L)
            .increaseRatio(() -> 0.20)
            .increaseThreshold(() -> 3)
            .cooldownTicks(() -> 2)
            .trialRevertThreshold(() -> 0.10)
            .minStepBytes(() -> 256 * 1024L)
            .build();
        AimdBatchSizeController controller = new AimdBatchSizeController(AoscLogger.create(AimdBatchSizeController.class), cfg);
        assertTrue(controller.nextBatchSize() > 0);
    }

    public void testIndependentInstances() {
        AimdConfig cfg = AimdConfig.builder()
            .enabled(true)
            .minTargetBytes(() -> 512 * 1024L)
            .maxTargetBytes(() -> 256 * 1024 * 1024L)
            .maxDocs(() -> 50000)
            .startBytesPerDoc(() -> 10 * 1024L)
            .increaseRatio(() -> 0.20)
            .increaseThreshold(() -> 3)
            .cooldownTicks(() -> 2)
            .trialRevertThreshold(() -> 0.10)
            .minStepBytes(() -> 256 * 1024L)
            .build();
        AimdBatchSizeController c1 = new AimdBatchSizeController(AoscLogger.create(AimdBatchSizeController.class), cfg);
        AimdBatchSizeController c2 = new AimdBatchSizeController(AoscLogger.create(AimdBatchSizeController.class), cfg);
        assertNotSame(c1, c2);

        c1.observe(BulkOutcome.failure(100, -1, 100, 1024 * 1024, 1, RejectionKind.OVERLOAD, "rejected"));
        assertNotEquals(c1.nextBatchSize(), c2.nextBatchSize());
    }
}
