/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */
package com.atlassian.opensearch.aosc.service.bulk;

import com.atlassian.opensearch.aosc.service.adaptive.BulkOutcome;
import com.atlassian.opensearch.aosc.service.adaptive.LatencyGradient;
import com.atlassian.opensearch.aosc.service.adaptive.RejectionKind;
import com.atlassian.opensearch.aosc.utils.AoscLogger;
import com.atlassian.opensearch.aosc.utils.LC;

import java.util.Objects;
import java.util.function.IntSupplier;
import java.util.function.LongSupplier;

import static com.atlassian.opensearch.aosc.utils.AoscLogger.kv;

/**
 * Unified latency-gradient-driven controller for batch size, concurrency (W),
 * and byte budget. The single brain for all write decisions in M2.
 *
 * <p>Uses a dual-EWMA {@link LatencyGradient} to detect rising server latency
 * and back off proactively — before rejections occur.</p>
 *
 * <p>OVERLOAD goes FATAL after {@code maxConsecutiveFailures} consecutive
 * failures with no successful write in between. Success resets the
 * consecutive failure counter (via {@link OverloadBackoff#decay()}).</p>
 *
 * <p>All methods are {@code synchronized} — called from concurrent async
 * callbacks and the single reader thread.</p>
 */
public class AdaptiveWriteController implements WriteController {

    private final AoscLogger logger;
    private final LatencyGradient gradient;
    private static final int MIN_W = 1;
    private final IntSupplier maxWSupplier;
    private final double decreaseThreshold;
    private final int probeInterval;
    private final LongSupplier minTargetBytesSupplier;
    private final LongSupplier maxTargetBytesSupplier;
    private final LongSupplier minStepBytesSupplier;
    private final IntSupplier maxDocsSupplier;
    private final OverloadBackoff backoff;

    // Concurrency state
    private int currentW;
    private int stableCount;

    // Batch sizing state (absorbed AIMD logic)
    private long targetBytes;
    private double ewmaDocBytes;

    public AdaptiveWriteController(
        AoscLogger logger,
        IntSupplier maxWSupplier,
        int initialW,
        double decreaseThreshold,
        int probeInterval,
        LongSupplier minTargetBytesSupplier,
        LongSupplier maxTargetBytesSupplier,
        LongSupplier minStepBytesSupplier,
        IntSupplier maxDocsSupplier,
        long startBytesPerDoc,
        OverloadBackoff backoff
    ) {
        this.logger = Objects.requireNonNull(logger, "logger").forClass(AdaptiveWriteController.class);
        Objects.requireNonNull(maxWSupplier, "maxWSupplier");
        int maxW = maxWSupplier.getAsInt();
        if (maxW < MIN_W) throw new IllegalArgumentException("maxW must be >= 1");
        if (initialW < MIN_W || initialW > maxW) throw new IllegalArgumentException("initialW must be in [1, maxW]");
        if (probeInterval < 1) throw new IllegalArgumentException("probeInterval must be >= 1");

        this.gradient = new LatencyGradient();
        this.maxWSupplier = maxWSupplier;
        this.decreaseThreshold = decreaseThreshold;
        this.probeInterval = probeInterval;
        this.minTargetBytesSupplier = Objects.requireNonNull(minTargetBytesSupplier, "minTargetBytesSupplier");
        this.maxTargetBytesSupplier = Objects.requireNonNull(maxTargetBytesSupplier, "maxTargetBytesSupplier");
        this.minStepBytesSupplier = Objects.requireNonNull(minStepBytesSupplier, "minStepBytesSupplier");
        this.maxDocsSupplier = Objects.requireNonNull(maxDocsSupplier, "maxDocsSupplier");
        this.backoff = Objects.requireNonNull(backoff, "backoff");

        this.currentW = initialW;
        this.stableCount = 0;
        this.targetBytes = maxTargetBytesSupplier.getAsLong();
        this.ewmaDocBytes = startBytesPerDoc;
    }

    @Override
    public synchronized int nextBatchSize() {
        int raw = (int) (targetBytes / Math.max(ewmaDocBytes, 1.0));
        return Math.max(1, Math.min(raw, maxDocsSupplier.getAsInt()));
    }

    @Override
    public synchronized WriteDecision handleOutcome(BulkOutcome outcome) {
        updateDocSizeEstimate(outcome);

        double latency = outcome.perDocServerMillis();
        if (latency >= 0) {
            gradient.observe(latency);
        }

        if (!outcome.isSuccess()) {
            return handleFailure(outcome);
        }
        backoff.decay();

        if (!gradient.isWarmedUp()) {
            stableCount++;
            return WriteDecision.success();
        }

        double g = gradient.gradient();

        // DECREASE — gradient high, coordinated shrink on all knobs
        if (g > decreaseThreshold) {
            shrinkW();
            targetBytes = Math.max(targetBytes / 2, minTargetBytesSupplier.getAsLong());
            stableCount = 0;
            logger.debug(
                "Gradient decrease",
                kv(LC.EVENT, "gradient_decrease"),
                kv(LC.GRADIENT, g),
                kv(LC.W, currentW),
                kv(LC.TARGET_BYTES, targetBytes)
            );
            return WriteDecision.success();
        }

        // HOLD or INCREASE
        stableCount++;
        if (stableCount >= probeInterval) {
            if (currentW < maxWSupplier.getAsInt()) {
                int oldW = currentW;
                growW();
                logger.debug(
                    "Concurrency increase",
                    kv(LC.EVENT, "concurrency_increase"),
                    kv(LC.OLD_W, oldW),
                    kv(LC.W, currentW),
                    kv(LC.PROBE_INTERVAL, probeInterval)
                );
            } else {
                growBatchSize();
            }
            stableCount = 0;
        }

        return WriteDecision.success();
    }

    @Override
    public synchronized int concurrency() {
        return currentW;
    }

    @Override
    public synchronized long maxBatchBytes() {
        return Math.min(targetBytes * 2, maxTargetBytesSupplier.getAsLong());
    }

    // ---- Internal methods ----

    private WriteDecision handleFailure(BulkOutcome outcome) {
        if (outcome.getRejectionKind() == RejectionKind.OVERLOAD || outcome.getRejectionKind() == RejectionKind.TRANSIENT) {
            long pause = backoff.escalate();
            if (backoff.isExhausted()) {
                return WriteDecision.fatal("Overload backoff exhausted after " + backoff.consecutiveFailures() + " consecutive failures");
            }
            shrinkW();
            targetBytes = Math.max(targetBytes / 2, minTargetBytesSupplier.getAsLong());
            stableCount = 0;
            logger.info(
                "Overload backoff",
                kv(LC.EVENT, "overload"),
                kv(LC.REJECTION_KIND, outcome.getRejectionKind()),
                kv(LC.LEVEL, backoff.level()),
                kv(LC.W, currentW),
                kv(LC.TARGET_BYTES, targetBytes),
                kv(LC.BACKOFF_MS, pause)
            );
            return WriteDecision.pauseAndRetry(pause);
        }

        // Only FATAL
        String msg = "Bulk failed after " + outcome.getAttemptCount() + " attempts: " + outcome.getFailureMessage();
        return WriteDecision.fatal(msg);
    }

    /** Asymmetric EWMA: fast up (alpha=0.5), slow down (alpha=0.2). */
    private void updateDocSizeEstimate(BulkOutcome outcome) {
        if (outcome.getItemCount() > 0 && outcome.getBatchBytes() > 0) {
            double currentBpd = (double) outcome.getBatchBytes() / outcome.getItemCount();
            double alpha = (currentBpd > ewmaDocBytes * 1.5) ? 0.5 : 0.2;
            ewmaDocBytes = (1 - alpha) * ewmaDocBytes + alpha * currentBpd;
        }
    }

    private int shrinkW() {
        if (currentW <= MIN_W) return 0;
        currentW--;
        return -1;
    }

    private int growW() {
        if (currentW >= maxWSupplier.getAsInt()) return 0;
        currentW++;
        return 1;
    }

    private void growBatchSize() {
        if (targetBytes >= maxTargetBytesSupplier.getAsLong()) return;
        long step = Math.max((long) (targetBytes * 0.20), minStepBytesSupplier.getAsLong());
        targetBytes = Math.min(targetBytes + step, maxTargetBytesSupplier.getAsLong());
        logger.debug("Batch size probe", kv(LC.EVENT, "batch_probe"), kv(LC.TARGET_BYTES, targetBytes), kv(LC.W, currentW));
    }

    // ---- Telemetry ----

    public synchronized int currentW() {
        return currentW;
    }

    public synchronized long currentTargetBytes() {
        return targetBytes;
    }

    /** Visible for testing. */
    synchronized LatencyGradient gradient() {
        return gradient;
    }

    /** Visible for testing. */
    synchronized int stableCount() {
        return stableCount;
    }

    /** Visible for testing. */
    synchronized int overloadLevel() {
        return backoff.level();
    }
}
