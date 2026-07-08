/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */
package com.atlassian.opensearch.aosc.service.bulk;

import com.atlassian.opensearch.aosc.service.adaptive.BatchSizeController;
import com.atlassian.opensearch.aosc.service.adaptive.BulkOutcome;
import com.atlassian.opensearch.aosc.service.adaptive.RejectionKind;
import com.atlassian.opensearch.aosc.utils.AoscLogger;

import java.util.Objects;
import java.util.function.IntSupplier;
import java.util.function.LongSupplier;

/**
 * M1 {@link WriteController} implementation. Wraps an existing
 * {@link BatchSizeController} (AIMD or Fixed) and adds OVERLOAD
 * retry/pause decisions with exponential backoff and gradual decay.
 *
 * <p>OVERLOAD goes FATAL after {@code maxConsecutiveFailures} consecutive
 * failures with no successful write in between. Success resets the
 * consecutive failure counter (via {@link OverloadBackoff#decay()}).</p>
 *
 * <p>Concurrency and maxBatchBytes are read from suppliers on every call,
 * so dynamic cluster setting changes are reflected immediately.</p>
 *
 * <p>All methods are {@code synchronized} — called from concurrent async
 * callbacks (outcome handling) and the single reader thread (batch sizing).</p>
 */
public class SimpleWriteController implements WriteController {

    private final AoscLogger logger;
    private final BatchSizeController sizeController;
    private final IntSupplier concurrencySupplier;
    private final LongSupplier maxBatchBytesSupplier;
    private final OverloadBackoff backoff;

    public SimpleWriteController(
        AoscLogger logger,
        BatchSizeController sizeController,
        IntSupplier concurrencySupplier,
        LongSupplier maxBatchBytesSupplier,
        OverloadBackoff backoff
    ) {
        this.logger = Objects.requireNonNull(logger, "logger").forClass(SimpleWriteController.class);
        this.sizeController = Objects.requireNonNull(sizeController, "sizeController");
        this.concurrencySupplier = Objects.requireNonNull(concurrencySupplier, "concurrencySupplier");
        this.maxBatchBytesSupplier = Objects.requireNonNull(maxBatchBytesSupplier, "maxBatchBytesSupplier");
        this.backoff = Objects.requireNonNull(backoff, "backoff");
    }

    @Override
    public synchronized int nextBatchSize() {
        return sizeController.nextBatchSize();
    }

    @Override
    public synchronized WriteDecision handleOutcome(BulkOutcome outcome) {
        sizeController.observe(outcome);

        if (outcome.isSuccess()) {
            backoff.decay();
            return WriteDecision.success();
        }

        if (outcome.getRejectionKind() == RejectionKind.OVERLOAD || outcome.getRejectionKind() == RejectionKind.TRANSIENT) {
            long pause = backoff.escalate();
            if (backoff.isExhausted()) {
                return WriteDecision.fatal("Overload backoff exhausted after " + backoff.consecutiveFailures() + " consecutive failures");
            }
            logger.info(
                "{} (level {}, consecutive {}), backing off {}ms",
                outcome.getRejectionKind(),
                backoff.level(),
                backoff.consecutiveFailures(),
                pause
            );
            return WriteDecision.pauseAndRetry(pause);
        }

        // Only FATAL — BulkWriteHelper already exhausted its internal retries
        String msg = "Bulk failed after " + outcome.getAttemptCount() + " attempts: " + outcome.getFailureMessage();
        return WriteDecision.fatal(msg);
    }

    @Override
    public int concurrency() {
        return Math.max(1, concurrencySupplier.getAsInt());
    }

    @Override
    public long maxBatchBytes() {
        return Math.max(1L, maxBatchBytesSupplier.getAsLong());
    }

    /** Visible for testing. */
    synchronized int overloadLevel() {
        return backoff.level();
    }
}
