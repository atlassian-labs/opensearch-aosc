/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */
package com.atlassian.opensearch.aosc.service.adaptive;

/**
 * Dual-EWMA latency gradient tracker for adaptive write concurrency control.
 *
 * <p>Tracks short-term and long-term exponentially weighted moving averages of
 * per-document server latency. The gradient ({@code short / long}) indicates
 * whether latency is increasing (> 1.0) or decreasing (< 1.0).</p>
 *
 * <p>Thread-safe: all public methods are synchronized as a defensive measure,
 * even though the caller ({@code AdaptiveWriteController}) is already synchronized.</p>
 */
public class LatencyGradient {

    public static final double SHORT_ALPHA = 0.5;
    public static final double LONG_ALPHA = 0.1;
    public static final int WARMUP_SAMPLES = 5;

    private double shortEwma;
    private double longEwma;
    private int samples;

    public synchronized void observe(double perDocMillis) {
        if (perDocMillis < 0) return;
        if (samples == 0) {
            shortEwma = perDocMillis;
            longEwma = perDocMillis;
        } else {
            shortEwma = SHORT_ALPHA * perDocMillis + (1 - SHORT_ALPHA) * shortEwma;
            longEwma = LONG_ALPHA * perDocMillis + (1 - LONG_ALPHA) * longEwma;
        }
        samples++;
    }

    /** Ratio of short-term to long-term EWMA. > 1.0 means latency is increasing. */
    public synchronized double gradient() {
        if (longEwma <= 0) return 1.0;
        return shortEwma / longEwma;
    }

    public synchronized boolean isWarmedUp() {
        return samples >= WARMUP_SAMPLES;
    }

    public synchronized int samples() {
        return samples;
    }

    /** Visible for testing. */
    synchronized double shortEwma() {
        return shortEwma;
    }

    /** Visible for testing. */
    synchronized double longEwma() {
        return longEwma;
    }
}
