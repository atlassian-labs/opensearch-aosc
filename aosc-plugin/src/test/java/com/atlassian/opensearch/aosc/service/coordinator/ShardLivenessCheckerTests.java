/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */
package com.atlassian.opensearch.aosc.service.coordinator;

import com.atlassian.opensearch.aosc.utils.AoscLogger;

import org.opensearch.common.unit.TimeValue;
import org.opensearch.core.index.shard.ShardId;
import org.opensearch.test.OpenSearchTestCase;
import org.opensearch.threadpool.ThreadPool;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Set;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;

public class ShardLivenessCheckerTests extends OpenSearchTestCase {

    private MutableClock clock;
    private ShardLivenessChecker checker;
    private ThreadPool mockThreadPool;

    private static final ShardId SHARD_0 = new ShardId("source", "_na_", 0);
    private static final ShardId SHARD_1 = new ShardId("source", "_na_", 1);

    @Override
    public void setUp() throws Exception {
        super.setUp();
        clock = new MutableClock(Instant.parse("2026-01-01T00:00:00Z"));
        mockThreadPool = mock(ThreadPool.class);
        // Mock scheduleWithFixedDelay to return a no-op cancellable (we test check() behavior, not scheduling)
        doAnswer(inv -> new ThreadPool.Cancellable() {
            @Override
            public boolean cancel() {
                return true;
            }

            @Override
            public boolean isCancelled() {
                return false;
            }
        }).when(mockThreadPool).scheduleWithFixedDelay(any(), any(), any());

        checker = new ShardLivenessChecker(
            AoscLogger.create(ShardLivenessChecker.class),
            mockThreadPool,
            () -> null,
            TimeValue.timeValueSeconds(30),
            TimeValue.timeValueSeconds(60),
            clock
        );
    }

    public void testHeartbeatReceived() {
        assertEquals(0, checker.trackedCount());
        checker.heartbeatReceived(SHARD_0);
        assertEquals(1, checker.trackedCount());
        checker.heartbeatReceived(SHARD_1);
        assertEquals(2, checker.trackedCount());
    }

    public void testNoStaleWhenFresh() {
        checker.heartbeatReceived(SHARD_0);
        checker.heartbeatReceived(SHARD_1);
        Set<ShardId> stale = checker.getStaleShardsOlderThan(Duration.ofSeconds(60));
        assertTrue(stale.isEmpty());
    }

    public void testStaleAfterTimeout() {
        checker.heartbeatReceived(SHARD_0);
        checker.heartbeatReceived(SHARD_1);
        clock.advance(Duration.ofSeconds(90));
        Set<ShardId> stale = checker.getStaleShardsOlderThan(Duration.ofSeconds(60));
        assertEquals(2, stale.size());
        assertTrue(stale.contains(SHARD_0));
        assertTrue(stale.contains(SHARD_1));
    }

    public void testPartiallyStale() {
        checker.heartbeatReceived(SHARD_0);
        clock.advance(Duration.ofSeconds(90));
        checker.heartbeatReceived(SHARD_1);
        Set<ShardId> stale = checker.getStaleShardsOlderThan(Duration.ofSeconds(60));
        assertEquals(1, stale.size());
        assertTrue(stale.contains(SHARD_0));
        assertFalse(stale.contains(SHARD_1));
    }

    public void testHeartbeatRefreshesTimestamp() {
        checker.heartbeatReceived(SHARD_0);
        clock.advance(Duration.ofSeconds(50));
        checker.heartbeatReceived(SHARD_0);
        clock.advance(Duration.ofSeconds(20));
        Set<ShardId> stale = checker.getStaleShardsOlderThan(Duration.ofSeconds(60));
        assertTrue(stale.isEmpty());
    }

    public void testGracePeriodPreventsStale() {
        checker.heartbeatReceived(SHARD_0);
        clock.advance(Duration.ofSeconds(90));
        checker.setGracePeriod(Duration.ofSeconds(30));
        Set<ShardId> stale = checker.getStaleShardsOlderThan(Duration.ofSeconds(60));
        assertTrue("No stale during grace period", stale.isEmpty());
    }

    public void testGracePeriodExpiry() {
        checker.heartbeatReceived(SHARD_0);
        clock.advance(Duration.ofSeconds(90));
        checker.setGracePeriod(Duration.ofSeconds(30));
        assertTrue(checker.getStaleShardsOlderThan(Duration.ofSeconds(60)).isEmpty());
        clock.advance(Duration.ofSeconds(31));
        Set<ShardId> stale = checker.getStaleShardsOlderThan(Duration.ofSeconds(60));
        assertEquals(1, stale.size());
        assertTrue(stale.contains(SHARD_0));
    }

    /** Mutable clock for deterministic testing. */
    private static class MutableClock extends Clock {
        private Instant now;

        MutableClock(Instant start) {
            this.now = start;
        }

        void advance(Duration d) {
            now = now.plus(d);
        }

        @Override
        public Instant instant() {
            return now;
        }

        @Override
        public ZoneId getZone() {
            return ZoneId.of("UTC");
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }
    }
}
