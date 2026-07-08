/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */
package com.atlassian.opensearch.aosc.service.bulk;

import org.opensearch.test.OpenSearchTestCase;

public class OverloadBackoffTests extends OpenSearchTestCase {

    public void testEscalateReturnsIncreasingPauses() {
        OverloadBackoff backoff = new OverloadBackoff(() -> 100L, () -> 100_000L, () -> 50);

        long p1 = backoff.escalate();
        long p2 = backoff.escalate();
        long p3 = backoff.escalate();

        assertEquals(1, backoff.level() - 2);
        assertTrue("Level-2 pause (" + p2 + ") should be >= level-1 pause (" + p1 + ")", p2 >= p1);
        assertTrue("Level-3 pause (" + p3 + ") should be >= level-2 pause (" + p2 + ")", p3 >= p2);
    }

    public void testDecayDecreasesLevel() {
        OverloadBackoff backoff = new OverloadBackoff(() -> 100L, () -> 100_000L, () -> 50);
        backoff.escalate();
        backoff.escalate();
        assertEquals(2, backoff.level());

        backoff.decay();
        assertEquals(1, backoff.level());

        backoff.decay();
        assertEquals(0, backoff.level());

        backoff.decay();
        assertEquals(0, backoff.level());
    }

    public void testPauseCappedAtMax() {
        OverloadBackoff backoff = new OverloadBackoff(() -> 1000L, () -> 2000L, () -> 50);

        for (int i = 0; i < 20; i++) {
            long pause = backoff.escalate();
            assertTrue("Pause " + pause + " should be <= max (2500, with 25% jitter)", pause <= 2500L);
        }
    }

    public void testJitterIsNonNegativeAndWithinBound() {
        OverloadBackoff backoff = new OverloadBackoff(() -> 1000L, () -> 100_000L, () -> 50);

        for (int i = 0; i < 50; i++) {
            backoff = new OverloadBackoff(() -> 1000L, () -> 100_000L, () -> 50);
            long pause = backoff.escalate();
            long basePause = 1000L;
            assertTrue("Pause " + pause + " should be >= base " + basePause, pause >= basePause);
            assertTrue("Pause " + pause + " should be <= base * 1.25", pause <= (long) (basePause * 1.25));
        }
    }

    public void testShiftOverflowCappedAtMax() {
        OverloadBackoff backoff = new OverloadBackoff(() -> 1000L, () -> 120_000L, () -> 50);

        for (int i = 0; i < 100; i++) {
            long pause = backoff.escalate();
            assertTrue("Pause at level " + backoff.level() + " must be positive, was " + pause, pause > 0);
            assertTrue("Pause at level " + backoff.level() + " must be <= max*1.25, was " + pause, pause <= 150_000L);
        }
    }

    public void testConsecutiveFailuresTracksEscalations() {
        OverloadBackoff backoff = new OverloadBackoff(() -> 100L, () -> 100_000L, () -> 50);
        assertEquals(0, backoff.consecutiveFailures());
        backoff.escalate();
        assertEquals(1, backoff.consecutiveFailures());
        backoff.escalate();
        assertEquals(2, backoff.consecutiveFailures());
    }

    public void testDecayResetsConsecutiveFailures() {
        OverloadBackoff backoff = new OverloadBackoff(() -> 100L, () -> 100_000L, () -> 50);
        backoff.escalate();
        backoff.escalate();
        backoff.escalate();
        assertEquals(3, backoff.consecutiveFailures());
        backoff.decay();
        assertEquals(0, backoff.consecutiveFailures());
    }

    public void testIsExhaustedAtLimit() {
        OverloadBackoff backoff = new OverloadBackoff(() -> 100L, () -> 100_000L, () -> 3);
        assertFalse(backoff.isExhausted());
        backoff.escalate();
        assertFalse(backoff.isExhausted());
        backoff.escalate();
        assertFalse(backoff.isExhausted());
        backoff.escalate();
        assertTrue(backoff.isExhausted());
    }

    public void testIsExhaustedResetsOnDecay() {
        OverloadBackoff backoff = new OverloadBackoff(() -> 100L, () -> 100_000L, () -> 3);
        backoff.escalate();
        backoff.escalate();
        backoff.escalate();
        assertTrue(backoff.isExhausted());
        backoff.decay();
        assertFalse(backoff.isExhausted());
    }

    public void testDynamicSupplierValuesReadFresh() {
        long[] base = { 100L };
        long[] max = { 10_000L };
        OverloadBackoff backoff = new OverloadBackoff(() -> base[0], () -> max[0], () -> 50);

        long p1 = backoff.escalate();
        assertTrue(p1 >= 100L && p1 <= 125L);

        base[0] = 500L;
        backoff = new OverloadBackoff(() -> base[0], () -> max[0], () -> 50);
        long p2 = backoff.escalate();
        assertTrue("After changing base to 500, pause " + p2 + " should be >= 500", p2 >= 500L);
    }
}
