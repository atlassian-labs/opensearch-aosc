/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */
package com.atlassian.opensearch.aosc.service.adaptive;

import com.atlassian.opensearch.aosc.service.bulk.ConcurrentBulkWriter;
import com.atlassian.opensearch.aosc.utils.AoscLogger;
import com.atlassian.opensearch.aosc.utils.LC;

import java.util.Objects;

import static com.atlassian.opensearch.aosc.utils.AoscLogger.kv;

/**
 * Additive-Increase Multiplicative-Decrease (AIMD) adaptive batch controller.
 *
 * <p>Controls the byte budget ({@code targetBytes}) for each bulk request on a
 * per-shard basis, converting to a document count via a running estimate of
 * bytes-per-document ({@code ewmaDocBytes}).</p>
 *
 * <h3>Algorithm summary</h3>
 * <ol>
 *   <li>Update doc-size estimate (asymmetric EWMA — fast when docs bigger than expected).</li>
 *   <li>On OVERLOAD rejection → halve {@code targetBytes} (multiplicative decrease).</li>
 *   <li>Evaluate throughput trial — if last increase hurt throughput, undo it.</li>
 *   <li>After N consecutive <em>clean</em> successes → tentatively increase (additive increase).</li>
 *   <li>Track throughput baseline for future trial comparisons.</li>
 * </ol>
 *
 * <h3>Failure handling</h3>
 * <p>Any non-success outcome triggers multiplicative decrease: the target is
 * halved (floored at {@code minTargetBytes}), cooldown is set, and the
 * consecutive-ok counter resets. The {@link ConcurrentBulkWriter} handles retry
 * policy (e.g. max consecutive OVERLOADs).</p>
 *
 * <h3>Thread safety</h3>
 * <p>Not thread-safe. Caller must guarantee single-thread access per shard
 * (the {@link ConcurrentBulkWriter} async
 * recursion satisfies this). Concurrent calls will corrupt state.</p>
 */
public class AimdBatchSizeController implements BatchSizeController {

    private final AoscLogger logger;

    private final AimdConfig cfg;

    // ---- Adaptive state (single-threaded per shard — no locks needed) ----
    private long targetBytes;
    private double ewmaDocBytes;
    private double lastThroughputBpms;
    private long previousTargetBytes;
    private int consecutiveOk;
    private int cooldownTicks;
    private boolean trialPending;

    // ---- Telemetry counters ----
    private long batchesSeen;
    private long mdEvents;
    private long aiEvents;

    public AimdBatchSizeController(AoscLogger logger, AimdConfig cfg) {
        this.logger = Objects.requireNonNull(logger, "logger").forClass(AimdBatchSizeController.class);
        this.cfg = cfg;
        this.targetBytes = cfg.getMaxTargetBytes();
        this.ewmaDocBytes = cfg.getStartBytesPerDoc();
        this.lastThroughputBpms = 0;
        this.previousTargetBytes = cfg.getMaxTargetBytes();
        this.consecutiveOk = 0;
        this.cooldownTicks = 0;
        this.trialPending = false;
        this.batchesSeen = 0;
        this.mdEvents = 0;
        this.aiEvents = 0;
    }

    @Override
    public int nextBatchSize() {
        int raw = (int) (targetBytes / Math.max(ewmaDocBytes, 1.0));
        return Math.max(1, Math.min(raw, cfg.getMaxDocs()));
    }

    @Override
    public void observe(BulkOutcome outcome) {
        batchesSeen++;
        updateDocSizeEstimate(outcome);

        if (!outcome.isSuccess()) {
            handleFailure(outcome);
            return;
        }

        double throughput = computeThroughput(outcome);
        evaluateTrial(throughput);
        attemptAdditiveIncrease(outcome);
        updateThroughputBaseline(throughput);
    }

    // ---- Focused sub-methods ------------------------------------------------

    /**
     * Asymmetric EWMA of bytes-per-doc: learn fast (α=0.5) when docs are 50%+
     * bigger than expected (risk of oversized bulk → 429), learn gently (α=0.2)
     * otherwise (under-sized bulk is cheap, self-correcting).
     */
    private void updateDocSizeEstimate(BulkOutcome outcome) {
        if (outcome.getItemCount() > 0 && outcome.getBatchBytes() > 0) {
            double currentBpd = (double) outcome.getBatchBytes() / outcome.getItemCount();
            double alpha = (currentBpd > ewmaDocBytes * 1.5) ? 0.5 : 0.2;
            ewmaDocBytes = (1 - alpha) * ewmaDocBytes + alpha * currentBpd;
        }
    }

    /**
     * Multiplicative decrease on hard rejection (429 / threadpool / circuit breaker).
     * Halves {@code targetBytes}, resets streak, sets cooldown, cancels pending trial.
     */
    private void handleFailure(BulkOutcome outcome) {
        long before = targetBytes;
        targetBytes = Math.max(targetBytes / 2, cfg.getMinTargetBytes());
        consecutiveOk = 0;
        cooldownTicks = cfg.getCooldownTicks();
        trialPending = false;
        mdEvents++;
        logger.info(
            "AIMD multiplicative decrease",
            kv(LC.EVENT, "aimd_decrease"),
            kv(LC.BEFORE_BYTES, before),
            kv(LC.TARGET_BYTES, targetBytes),
            kv(LC.ATTEMPT_COUNT, outcome.getAttemptCount())
        );
    }

    /**
     * Bytes per millisecond for this batch, or 0 if not measurable.
     */
    private double computeThroughput(BulkOutcome outcome) {
        return (outcome.getBatchBytes() > 0 && outcome.getTookMillis() > 0)
            ? (double) outcome.getBatchBytes() / outcome.getTookMillis()
            : 0;
    }

    /**
     * If a trial is pending, evaluate whether the last additive increase improved throughput.
     * <ul>
     *   <li><strong>Cold start</strong> (no baseline): accept neutrally.</li>
     *   <li><strong>Bad</strong> (throughput ≤ −5%): revert to previous target + cooldown.</li>
     *   <li><strong>Good/marginal</strong>: keep the increase.</li>
     * </ul>
     */
    private void evaluateTrial(double throughput) {
        if (!trialPending) return;
        if (lastThroughputBpms > 0 && throughput <= lastThroughputBpms * (1.0 - cfg.getTrialRevertThreshold())) {
            // Bad trial — revert, cooldown, keep step size for future retry
            long revertedTo = previousTargetBytes;
            targetBytes = previousTargetBytes;
            cooldownTicks = cfg.getCooldownTicks();
            logger.info("AIMD trial revert", kv(LC.EVENT, "aimd_trial_revert"), kv(LC.TARGET_BYTES, revertedTo));
        }
        trialPending = false;
    }

    /**
     * After enough consecutive successes and no cooldown, tentatively
     * increase {@code targetBytes} and mark a trial pending.
     */
    private void attemptAdditiveIncrease(BulkOutcome outcome) {
        consecutiveOk++;

        if (cooldownTicks > 0) {
            cooldownTicks--;
        } else if (consecutiveOk >= cfg.getIncreaseThreshold() && targetBytes < cfg.getMaxTargetBytes()) {
            previousTargetBytes = targetBytes;
            long step = Math.max((long) (targetBytes * cfg.getIncreaseRatio()), cfg.getMinStepBytes());
            targetBytes = Math.min(targetBytes + step, cfg.getMaxTargetBytes());
            trialPending = true;
            consecutiveOk = 0;
            aiEvents++;
            if (step > 0) {
                logger.info(
                    "AIMD additive increase",
                    kv(LC.EVENT, "aimd_increase"),
                    kv(LC.BEFORE_BYTES, previousTargetBytes),
                    kv(LC.TARGET_BYTES, targetBytes),
                    kv(LC.STEP_BYTES, step)
                );
            } else {
                logger.trace("AIMD additive increase no-op", kv(LC.EVENT, "aimd_increase_noop"), kv(LC.STEP_BYTES, step));
            }
        }
    }

    /**
     * Smooth throughput baseline (EWMA, α=0.3) for future trial comparisons.
     * Updated <em>after</em> trial evaluation so the trial compares against
     * the pre-increase baseline.
     */
    private void updateThroughputBaseline(double throughput) {
        lastThroughputBpms = (lastThroughputBpms <= 0) ? throughput : 0.7 * lastThroughputBpms + 0.3 * throughput;
    }

    // ---- Telemetry accessors ------------------------------------------------

    public long currentTargetBytes() {
        return targetBytes;
    }

    /** Count of multiplicative decrease events (overload rejections). */
    public long mdEvents() {
        return mdEvents;
    }

    /** Count of additive increase events. */
    public long aiEvents() {
        return aiEvents;
    }

    /** Current cooldown tick counter. */
    public int cooldownTicks() {
        return cooldownTicks;
    }

    /** Total batches observed. */
    public long batchesSeen() {
        return batchesSeen;
    }

}
