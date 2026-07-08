/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */
package com.atlassian.opensearch.aosc.service.bulk;

import org.opensearch.common.Randomness;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntSupplier;
import java.util.function.LongSupplier;

/**
 * Exponential backoff with jitter for overload recovery.
 * Thread-safe via {@link AtomicInteger} for the backoff level.
 */
public class OverloadBackoff {

    private final LongSupplier basePauseMsSupplier;
    private final LongSupplier maxPauseMsSupplier;
    private final IntSupplier maxConsecutiveFailuresSupplier;
    private final AtomicInteger level = new AtomicInteger();
    private final AtomicInteger consecutiveFailures = new AtomicInteger();

    public OverloadBackoff(LongSupplier basePauseMsSupplier, LongSupplier maxPauseMsSupplier, IntSupplier maxConsecutiveFailures) {
        this.basePauseMsSupplier = basePauseMsSupplier;
        this.maxPauseMsSupplier = maxPauseMsSupplier;
        this.maxConsecutiveFailuresSupplier = maxConsecutiveFailures;
    }

    /** Escalates the backoff level and returns the computed pause with jitter. */
    public long escalate() {
        int lvl = level.incrementAndGet();
        consecutiveFailures.incrementAndGet();
        long base = basePauseMsSupplier.getAsLong();
        long max = maxPauseMsSupplier.getAsLong();
        int shift = Math.min(lvl - 1, 62);
        long multiplier = 1L << shift;
        long pause = (base > 0 && multiplier > Long.MAX_VALUE / base) ? max : Math.min(base * multiplier, max);
        long jitter = (long) (pause * 0.25 * Randomness.get().nextDouble());
        return pause + jitter;
    }

    /** Decays the backoff level by one step and resets the consecutive failure counter. */
    public void decay() {
        level.updateAndGet(l -> Math.max(0, l - 1));
        consecutiveFailures.set(0);
    }

    public int level() {
        return level.get();
    }

    /** Number of consecutive {@link #escalate()} calls since the last {@link #decay()}. */
    public int consecutiveFailures() {
        return consecutiveFailures.get();
    }

    /** True if consecutive failures have reached the configured maximum. */
    public boolean isExhausted() {
        return consecutiveFailures.get() >= maxConsecutiveFailuresSupplier.getAsInt();
    }
}
