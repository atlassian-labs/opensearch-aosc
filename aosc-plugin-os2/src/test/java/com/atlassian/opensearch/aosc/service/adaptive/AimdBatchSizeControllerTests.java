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
 * Tests for {@link AimdBatchSizeController} — the AIMD adaptive batch sizing algorithm.
 */
public class AimdBatchSizeControllerTests extends OpenSearchTestCase {

    private AimdConfig defaultConfig() {
        return AimdConfig.builder()
            .enabled(true)
            .minTargetBytes(() -> 2 * 1024 * 1024L)       // 2 MB
            .maxTargetBytes(() -> 256 * 1024 * 1024L)      // 256 MB (also initial target)
            .maxDocs(() -> 5000)
            .startBytesPerDoc(() -> 10 * 1024L)            // 10 KB
            .increaseRatio(() -> 0.20)
            .increaseThreshold(() -> 3)
            .cooldownTicks(() -> 3)
            .trialRevertThreshold(() -> 0.10)
            .minStepBytes(() -> 256 * 1024L)
            .build();
    }

    private AimdBatchSizeController createController() {
        return new AimdBatchSizeController(AoscLogger.create(AimdBatchSizeController.class), defaultConfig());
    }

    private AimdBatchSizeController createController(AimdConfig cfg) {
        return new AimdBatchSizeController(AoscLogger.create(AimdBatchSizeController.class), cfg);
    }

    private BulkOutcome successOutcome(int itemCount, long bytesSize) {
        return BulkOutcome.success(100, 80, itemCount, bytesSize, 1);
    }

    private BulkOutcome successWithRetries(int itemCount, long bytesSize, int attempts) {
        return BulkOutcome.success(100, 80, itemCount, bytesSize, attempts);
    }

    private BulkOutcome overloadOutcome() {
        return BulkOutcome.failure(100, -1, 100, 1024 * 1024, 1, RejectionKind.OVERLOAD, "429 Too Many Requests");
    }

    private BulkOutcome transientOutcome() {
        return BulkOutcome.failure(100, -1, 100, 1024 * 1024, 1, RejectionKind.TRANSIENT, "timeout");
    }

    private BulkOutcome fatalOutcome() {
        return BulkOutcome.failure(100, -1, 100, 1024 * 1024, 1, RejectionKind.FATAL, "mapping conflict");
    }

    // ---- Initial state ----

    public void testInitialBatchSizeUsesStartConfig() {
        AimdBatchSizeController controller = createController();
        int docs = controller.nextBatchSize();
        // 64MB / 10KB = 6400, clamped to maxDocs=5000
        assertEquals(5000, docs);
    }

    public void testInitialBatchSizeSmallDocs() {
        AimdConfig cfg = defaultConfig().toBuilder().startBytesPerDoc(() -> 1024L).build(); // 1KB
        AimdBatchSizeController controller = createController(cfg);
        int docs = controller.nextBatchSize();
        // 64MB / 1KB = 65536, clamped to maxDocs=5000
        assertEquals(5000, docs);
    }

    public void testInitialBatchSizeLargeDocs() {
        AimdConfig cfg = defaultConfig().toBuilder().startBytesPerDoc(() -> 1024 * 1024L).build(); // 1MB
        AimdBatchSizeController controller = createController(cfg);
        int docs = controller.nextBatchSize();
        // 256MB / 1MB = 256
        assertEquals(256, docs);
    }

    public void testMinDocsFloor() {
        AimdConfig cfg = defaultConfig().toBuilder()
            .maxTargetBytes(() -> 1024L)          // 1KB target
            .startBytesPerDoc(() -> 10 * 1024L)     // 10KB per doc → raw=0
            .build();
        AimdBatchSizeController controller = createController(cfg);
        assertEquals(1, controller.nextBatchSize());
    }

    // ---- Multiplicative decrease (OVERLOAD) ----

    public void testOverloadHalvesTargetBytes() {
        AimdBatchSizeController controller = createController();
        long beforeTarget = controller.currentTargetBytes();

        controller.observe(overloadOutcome());

        assertEquals(beforeTarget / 2, controller.currentTargetBytes());
        assertEquals(1, controller.mdEvents());
    }

    public void testRepeatedOverloadRespectsFloor() {
        AimdConfig cfg = defaultConfig().toBuilder()
            .maxTargetBytes(() -> 8 * 1024 * 1024L) // 8MB
            .minTargetBytes(() -> 2 * 1024 * 1024L)   // 2MB floor
            .build();
        AimdBatchSizeController controller = createController(cfg);

        // Halve: 8→4→2→2(floor)
        controller.observe(overloadOutcome());
        assertEquals(4 * 1024 * 1024L, controller.currentTargetBytes());

        controller.observe(overloadOutcome());
        assertEquals(2 * 1024 * 1024L, controller.currentTargetBytes());

        controller.observe(overloadOutcome());
        assertEquals(2 * 1024 * 1024L, controller.currentTargetBytes()); // stays at floor

        assertEquals(3, controller.mdEvents());
    }

    public void testOverloadSetsCooldown() {
        AimdBatchSizeController controller = createController();
        controller.observe(overloadOutcome());
        assertEquals(defaultConfig().getCooldownTicks(), controller.cooldownTicks());
    }

    // ---- Additive increase ----

    public void testAdditiveIncreaseAfterConsecutiveSuccesses() {
        AimdBatchSizeController controller = createController();

        // First halve via OVERLOAD so we have room to grow
        controller.observe(overloadOutcome());
        long halvedTarget = controller.currentTargetBytes();

        // Need cooldown (3 ticks) + increaseThreshold=3 consecutive clean successes
        for (int i = 0; i < 3; i++) {
            controller.observe(successOutcome(100, 1024 * 1024)); // burn cooldown
        }
        // Now 3 more for increase
        controller.observe(successOutcome(100, 1024 * 1024));
        controller.observe(successOutcome(100, 1024 * 1024));
        controller.observe(successOutcome(100, 1024 * 1024));

        // Should have increased by ~20% from halved target
        assertTrue("Target should increase after consecutive successes", controller.currentTargetBytes() > halvedTarget);
        assertEquals(1, controller.aiEvents());
    }

    public void testAdditiveIncreaseRespectsMaxTarget() {
        AimdConfig cfg = defaultConfig().toBuilder()
            .maxTargetBytes(() -> 250 * 1024 * 1024L) // 250MB max (also initial)
            .cooldownTicks(() -> 0)              // no cooldown for cleaner test
            .build();
        AimdBatchSizeController controller = createController(cfg);

        // Halve twice: 250MB → 125MB → 62.5MB
        controller.observe(overloadOutcome());
        controller.observe(overloadOutcome());

        // 3 successes to trigger AI: +20% of 62.5MB = ~12.5MB → ~75MB
        for (int i = 0; i < 3; i++) {
            controller.observe(successOutcome(100, 1024 * 1024));
        }

        assertTrue(controller.currentTargetBytes() <= cfg.getMaxTargetBytes());
        assertTrue(controller.currentTargetBytes() > 62 * 1024 * 1024L); // grew from ~62MB
    }

    // ---- All successes count equally ----

    public void testRetriedSuccessCountsTowardConsecutiveOk() {
        AimdBatchSizeController controller = createController();

        // Halve first so we have room to grow
        controller.observe(overloadOutcome());

        // Burn cooldown (3 ticks)
        for (int i = 0; i < 3; i++) {
            controller.observe(successOutcome(100, 1024 * 1024));
        }

        // 3 retried successes should trigger AI — all successes count equally
        controller.observe(successWithRetries(100, 1024 * 1024, 2));
        controller.observe(successWithRetries(100, 1024 * 1024, 2));
        controller.observe(successWithRetries(100, 1024 * 1024, 2));

        assertEquals(1, controller.aiEvents());
    }

    // ---- Cooldown ----

    public void testCooldownPreventsAdditiveIncrease() {
        AimdBatchSizeController controller = createController();

        // Trigger overload → sets cooldown
        controller.observe(overloadOutcome());
        long targetAfterMd = controller.currentTargetBytes();

        // Even with clean successes during cooldown, no AI
        for (int i = 0; i < 3; i++) {
            controller.observe(successOutcome(100, 1024 * 1024));
        }

        // Should still be at MD level (no increase during cooldown)
        assertEquals(targetAfterMd, controller.currentTargetBytes());
        assertEquals(0, controller.aiEvents());
    }

    public void testCooldownDecrementsAndEventuallyAllowsAI() {
        AimdConfig cfg = defaultConfig().toBuilder().cooldownTicks(() -> 2).increaseThreshold(() -> 1).build();
        AimdBatchSizeController controller = createController(cfg);

        controller.observe(overloadOutcome());
        long targetAfterMd = controller.currentTargetBytes();

        // Cooldown=2: first 2 successes decrement cooldown, 3rd triggers AI
        controller.observe(successOutcome(100, 1024 * 1024)); // cooldown 2→1
        controller.observe(successOutcome(100, 1024 * 1024)); // cooldown 1→0
        controller.observe(successOutcome(100, 1024 * 1024)); // AI fires

        assertTrue(controller.currentTargetBytes() > targetAfterMd);
        assertEquals(1, controller.aiEvents());
    }

    // ---- EWMA doc size ----

    public void testEwmaUpdatesWithObservedDocSize() {
        AimdConfig cfg = defaultConfig().toBuilder().startBytesPerDoc(() -> 10_000L).build();
        AimdBatchSizeController controller = createController(cfg);

        int docsBefore = controller.nextBatchSize();

        // Observe much larger docs (100KB each) → EWMA should increase → fewer docs
        controller.observe(successOutcome(100, 100 * 100_000L)); // 100KB/doc

        int docsAfter = controller.nextBatchSize();
        assertTrue("Larger docs should reduce batch doc count", docsAfter < docsBefore);
    }

    // ---- All failures halve target ----

    public void testTransientHalvesTarget() {
        AimdBatchSizeController controller = createController();
        long startTarget = controller.currentTargetBytes();

        controller.observe(transientOutcome());

        assertEquals(startTarget / 2, controller.currentTargetBytes());
        assertEquals(1, controller.mdEvents());
    }

    public void testFatalHalvesTarget() {
        AimdBatchSizeController controller = createController();
        long startTarget = controller.currentTargetBytes();

        controller.observe(fatalOutcome());

        assertEquals(startTarget / 2, controller.currentTargetBytes());
        assertEquals(1, controller.mdEvents());
    }

    // ---- Describe ----

    // ---- Telemetry ----

    public void testBatchesSeenCounter() {
        AimdBatchSizeController controller = createController();
        assertEquals(0, controller.batchesSeen());

        controller.observe(successOutcome(100, 1024));
        controller.observe(overloadOutcome());
        controller.observe(transientOutcome());

        assertEquals(3, controller.batchesSeen());
    }
}
