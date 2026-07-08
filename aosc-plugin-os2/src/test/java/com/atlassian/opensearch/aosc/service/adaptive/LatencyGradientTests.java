/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */
package com.atlassian.opensearch.aosc.service.adaptive;

import org.opensearch.test.OpenSearchTestCase;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

public class LatencyGradientTests extends OpenSearchTestCase {

    // ---- warmup ----

    public void testNotWarmedUpInitially() {
        LatencyGradient g = new LatencyGradient();
        assertFalse(g.isWarmedUp());
        assertEquals(0, g.samples());
    }

    public void testWarmsUpAfterFiveSamples() {
        LatencyGradient g = new LatencyGradient();
        for (int i = 0; i < LatencyGradient.WARMUP_SAMPLES - 1; i++) {
            g.observe(1.0);
            assertFalse(g.isWarmedUp());
        }
        g.observe(1.0);
        assertTrue(g.isWarmedUp());
        assertEquals(LatencyGradient.WARMUP_SAMPLES, g.samples());
    }

    // ---- gradient computation ----

    public void testGradientIsOneForConstantLatency() {
        LatencyGradient g = new LatencyGradient();
        for (int i = 0; i < 20; i++) {
            g.observe(5.0);
        }
        assertEquals(1.0, g.gradient(), 0.01);
    }

    public void testGradientAboveOneForIncreasingLatency() {
        LatencyGradient g = new LatencyGradient();
        // Seed with low latency
        for (int i = 0; i < 20; i++) {
            g.observe(1.0);
        }
        // Spike latency — short EWMA reacts fast, long stays slow
        for (int i = 0; i < 5; i++) {
            g.observe(10.0);
        }
        assertTrue("gradient should be > 1.0 for increasing latency, was " + g.gradient(), g.gradient() > 1.0);
    }

    public void testGradientBelowOneForDecreasingLatency() {
        LatencyGradient g = new LatencyGradient();
        // Seed with high latency
        for (int i = 0; i < 20; i++) {
            g.observe(10.0);
        }
        // Drop latency — short EWMA drops fast, long stays high
        for (int i = 0; i < 5; i++) {
            g.observe(1.0);
        }
        assertTrue("gradient should be < 1.0 for decreasing latency, was " + g.gradient(), g.gradient() < 1.0);
    }

    // ---- EWMA convergence ----

    public void testFirstObservationSeedsBothEwmas() {
        LatencyGradient g = new LatencyGradient();
        g.observe(5.0);
        assertEquals(5.0, g.shortEwma(), 0.001);
        assertEquals(5.0, g.longEwma(), 0.001);
    }

    public void testShortEwmaConvergesFasterThanLong() {
        LatencyGradient g = new LatencyGradient();
        g.observe(10.0);
        // Switch to 20.0
        g.observe(20.0);
        double shortAfterOne = g.shortEwma();
        double longAfterOne = g.longEwma();
        // Short EWMA (alpha=0.5) should have moved more toward 20 than long (alpha=0.1)
        assertTrue(shortAfterOne > longAfterOne);
        assertTrue(shortAfterOne > 14.0); // 0.5*20 + 0.5*10 = 15
        assertTrue(longAfterOne < 12.0);  // 0.1*20 + 0.9*10 = 11
    }

    // ---- edge cases ----

    public void testNegativeLatencyIgnored() {
        LatencyGradient g = new LatencyGradient();
        g.observe(-1.0);
        assertEquals(0, g.samples());
        assertFalse(g.isWarmedUp());
    }

    public void testGradientReturnsOneWithNoObservations() {
        LatencyGradient g = new LatencyGradient();
        assertEquals(1.0, g.gradient(), 0.0);
    }

    public void testZeroLatencyObservation() {
        LatencyGradient g = new LatencyGradient();
        g.observe(0.0);
        assertEquals(1, g.samples());
        assertEquals(0.0, g.shortEwma(), 0.0);
    }

    // ---- thread safety ----

    public void testConcurrentObserveAndGradientDoesNotThrow() throws Exception {
        LatencyGradient g = new LatencyGradient();
        int threadCount = 8;
        Thread[] threads = new Thread[threadCount];
        CountDownLatch latch = new CountDownLatch(threadCount);

        for (int t = 0; t < threadCount; t++) {
            final int seed = t;
            threads[t] = new Thread(() -> {
                for (int i = 0; i < 200; i++) {
                    g.observe(seed + i * 0.1);
                    g.gradient();
                    g.isWarmedUp();
                    g.samples();
                }
                latch.countDown();
            });
        }
        for (Thread t : threads)
            t.start();
        assertTrue(latch.await(10, TimeUnit.SECONDS));
        assertTrue(g.isWarmedUp());
        assertTrue(g.gradient() > 0);
    }
}
