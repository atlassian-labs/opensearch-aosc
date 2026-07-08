/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */
package com.atlassian.opensearch.aosc.service.worker;

import com.atlassian.opensearch.aosc.utils.AoscLogger;

import org.opensearch.test.OpenSearchTestCase;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

public class BackfillPermitManagerTests extends OpenSearchTestCase {

    // ---- Basic acquire/release ----

    public void testAcquireWithinLimitCompletesImmediately() {
        BackfillPermitManager manager = new BackfillPermitManager(AoscLogger.create(BackfillPermitManager.class), 3);
        assertTrue(manager.acquire("m1", 0).isDone());
        assertTrue(manager.acquire("m1", 1).isDone());
        assertTrue(manager.acquire("m1", 2).isDone());
        assertEquals(3, manager.issuedCount());
        assertEquals(0, manager.queueSize());
    }

    public void testAcquireBeyondLimitQueues() {
        BackfillPermitManager manager = new BackfillPermitManager(AoscLogger.create(BackfillPermitManager.class), 2);
        manager.acquire("m1", 0);
        manager.acquire("m1", 1);
        CompletableFuture<Void> f3 = manager.acquire("m1", 2);
        assertFalse("Should be queued when at limit", f3.isDone());
        assertEquals(2, manager.issuedCount());
        assertEquals(1, manager.queueSize());
    }

    public void testReleaseGrantsToNextWaiter() {
        BackfillPermitManager manager = new BackfillPermitManager(AoscLogger.create(BackfillPermitManager.class), 1);
        manager.acquire("m1", 0);
        CompletableFuture<Void> f2 = manager.acquire("m1", 1);
        CompletableFuture<Void> f3 = manager.acquire("m1", 2);

        assertFalse(f2.isDone());
        assertFalse(f3.isDone());

        manager.release("m1", 0);
        assertTrue("f2 should be granted after release", f2.isDone());
        assertFalse("f3 should still be queued", f3.isDone());
        assertEquals(1, manager.issuedCount());

        manager.release("m1", 1);
        assertTrue("f3 should be granted after second release", f3.isDone());
        assertEquals(1, manager.issuedCount());

        manager.release("m1", 2);
        assertEquals(0, manager.issuedCount());
    }

    // ---- FIFO ----

    public void testFifoOrdering() {
        BackfillPermitManager manager = new BackfillPermitManager(AoscLogger.create(BackfillPermitManager.class), 1);
        manager.acquire("m1", 0);

        List<Integer> order = new ArrayList<>();
        CompletableFuture<Void> f1 = manager.acquire("m1", 1);
        f1.thenRun(() -> order.add(1));
        CompletableFuture<Void> f2 = manager.acquire("m1", 2);
        f2.thenRun(() -> order.add(2));
        CompletableFuture<Void> f3 = manager.acquire("m1", 3);
        f3.thenRun(() -> order.add(3));

        manager.release("m1", 0);
        manager.release("m1", 1);
        manager.release("m1", 2);

        assertEquals(List.of(1, 2, 3), order);
    }

    // ---- Dynamic max permits ----

    public void testUpdateMaxPermitsIncreaseGrantsWaiters() {
        BackfillPermitManager manager = new BackfillPermitManager(AoscLogger.create(BackfillPermitManager.class), 1);
        manager.acquire("m1", 0);
        CompletableFuture<Void> f2 = manager.acquire("m1", 1);
        CompletableFuture<Void> f3 = manager.acquire("m1", 2);

        assertFalse(f2.isDone());
        assertFalse(f3.isDone());

        manager.updateMaxPermits(3);
        assertTrue("f2 should be granted after increase", f2.isDone());
        assertTrue("f3 should be granted after increase", f3.isDone());
        assertEquals(3, manager.issuedCount());
    }

    public void testUpdateMaxPermitsDecreaseDoesNotRevokeExisting() {
        BackfillPermitManager manager = new BackfillPermitManager(AoscLogger.create(BackfillPermitManager.class), 3);
        manager.acquire("m1", 0);
        manager.acquire("m1", 1);
        manager.acquire("m1", 2);
        assertEquals(3, manager.issuedCount());

        manager.updateMaxPermits(1);
        assertEquals("Existing permits should not be revoked", 3, manager.issuedCount());

        manager.release("m1", 0);
        manager.release("m1", 1);
        assertEquals(1, manager.issuedCount());

        CompletableFuture<Void> f4 = manager.acquire("m1", 3);
        assertFalse("New acquire should be queued at reduced limit", f4.isDone());

        manager.release("m1", 2);
        assertTrue("f4 should be granted after release", f4.isDone());
    }

    // ---- Zero permits ----

    public void testZeroPermitsPausesAllAcquires() {
        BackfillPermitManager manager = new BackfillPermitManager(AoscLogger.create(BackfillPermitManager.class), 0);
        CompletableFuture<Void> f = manager.acquire("m1", 0);
        assertFalse("Should be queued when max=0", f.isDone());
        assertEquals(0, manager.issuedCount());
        assertEquals(1, manager.queueSize());
    }

    public void testUnpausingFromZeroGrantsWaiters() {
        BackfillPermitManager manager = new BackfillPermitManager(AoscLogger.create(BackfillPermitManager.class), 0);
        CompletableFuture<Void> f1 = manager.acquire("m1", 0);
        CompletableFuture<Void> f2 = manager.acquire("m1", 1);

        manager.updateMaxPermits(2);
        assertTrue(f1.isDone());
        assertTrue(f2.isDone());
        assertEquals(2, manager.issuedCount());
    }

    // ---- Cancel waiters ----

    public void testCancelWaitersCancelsAllQueued() {
        BackfillPermitManager manager = new BackfillPermitManager(AoscLogger.create(BackfillPermitManager.class), 1);
        manager.acquire("m1", 0);
        CompletableFuture<Void> f2 = manager.acquire("m1", 1);
        CompletableFuture<Void> f3 = manager.acquire("m1", 2);

        manager.cancelWaiters();
        assertTrue("f2 should be cancelled", f2.isCancelled());
        assertTrue("f3 should be cancelled", f3.isCancelled());
        assertEquals(0, manager.queueSize());
        assertEquals(1, manager.issuedCount());
    }

    public void testCancelledWaiterSkippedOnRelease() {
        BackfillPermitManager manager = new BackfillPermitManager(AoscLogger.create(BackfillPermitManager.class), 1);
        manager.acquire("m1", 0);
        CompletableFuture<Void> f2 = manager.acquire("m1", 1);
        CompletableFuture<Void> f3 = manager.acquire("m1", 2);

        f2.cancel(false);
        manager.release("m1", 0);
        assertFalse("f2 should remain cancelled", f2.isDone() && !f2.isCancelled());
        assertTrue("f3 should be granted (f2 was skipped)", f3.isDone());
    }

    public void testCancelWaitersIsIdempotent() {
        BackfillPermitManager manager = new BackfillPermitManager(AoscLogger.create(BackfillPermitManager.class), 1);
        manager.acquire("m1", 0);
        manager.acquire("m1", 1);
        assertEquals(1, manager.queueSize());

        manager.cancelWaiters();
        assertEquals(0, manager.queueSize());

        manager.cancelWaiters();
        assertEquals(0, manager.queueSize());
    }

    // ---- Validation ----

    public void testNegativeMaxPermitsThrows() {
        expectThrows(IllegalArgumentException.class, () -> new BackfillPermitManager(AoscLogger.create(BackfillPermitManager.class), -1));
    }

    public void testNegativeUpdateMaxPermitsThrows() {
        BackfillPermitManager manager = new BackfillPermitManager(AoscLogger.create(BackfillPermitManager.class), 5);
        expectThrows(IllegalArgumentException.class, () -> manager.updateMaxPermits(-1));
    }

    // ---- Idempotent release ----

    public void testReleaseIsIdempotent() {
        BackfillPermitManager manager = new BackfillPermitManager(AoscLogger.create(BackfillPermitManager.class), 2);
        manager.acquire("m1", 0);
        assertEquals(1, manager.issuedCount());

        manager.release("m1", 0);
        assertEquals(0, manager.issuedCount());

        // Second release is a no-op
        manager.release("m1", 0);
        assertEquals(0, manager.issuedCount());
    }

    public void testReleaseUnknownPermitIsNoOp() {
        BackfillPermitManager manager = new BackfillPermitManager(AoscLogger.create(BackfillPermitManager.class), 2);
        manager.release("m1", 99);
        assertEquals(0, manager.issuedCount());
    }

    // ---- Double acquire throws ----

    public void testDoubleAcquireThrows() {
        BackfillPermitManager manager = new BackfillPermitManager(AoscLogger.create(BackfillPermitManager.class), 3);
        manager.acquire("m1", 0);
        expectThrows(IllegalStateException.class, () -> manager.acquire("m1", 0));
        assertEquals(1, manager.issuedCount());
    }

    // ---- releaseAllForMigration ----

    public void testReleaseAllForMigrationReleasesHeldPermits() {
        BackfillPermitManager manager = new BackfillPermitManager(AoscLogger.create(BackfillPermitManager.class), 10);
        manager.acquire("m1", 0);
        manager.acquire("m1", 1);
        manager.acquire("m1", 2);
        manager.acquire("m2", 0);
        assertEquals(4, manager.issuedCount());

        manager.releaseAllForMigration("m1");
        assertEquals(1, manager.issuedCount());
    }

    public void testReleaseAllForMigrationCancelsQueuedWaiters() {
        BackfillPermitManager manager = new BackfillPermitManager(AoscLogger.create(BackfillPermitManager.class), 1);
        manager.acquire("m1", 0);
        CompletableFuture<Void> m1Queued = manager.acquire("m1", 1);
        CompletableFuture<Void> m2Queued = manager.acquire("m2", 0);

        manager.releaseAllForMigration("m1");
        assertTrue("m1 queued waiter should be cancelled", m1Queued.isCancelled());
        // m2's waiter should be granted after m1 bulk release
        assertTrue("m2 should be granted after m1 bulk release", m2Queued.isDone());
        assertFalse("m2 should not be cancelled", m2Queued.isCancelled());
        assertEquals(1, manager.issuedCount());
    }

    public void testReleaseAllForMigrationIsIdempotent() {
        BackfillPermitManager manager = new BackfillPermitManager(AoscLogger.create(BackfillPermitManager.class), 10);
        manager.acquire("m1", 0);
        manager.releaseAllForMigration("m1");
        assertEquals(0, manager.issuedCount());

        // Second call is a no-op
        manager.releaseAllForMigration("m1");
        assertEquals(0, manager.issuedCount());
    }

    public void testReleaseAllForMigrationAfterIndividualRelease() {
        BackfillPermitManager manager = new BackfillPermitManager(AoscLogger.create(BackfillPermitManager.class), 10);
        manager.acquire("m1", 0);
        manager.acquire("m1", 1);
        manager.release("m1", 0);
        assertEquals(1, manager.issuedCount());

        // Bulk release should only release shard 1
        manager.releaseAllForMigration("m1");
        assertEquals(0, manager.issuedCount());
    }

    // ---- forceReset ----

    public void testForceResetClearsEverything() {
        BackfillPermitManager manager = new BackfillPermitManager(AoscLogger.create(BackfillPermitManager.class), 2);
        manager.acquire("m1", 0);
        manager.acquire("m1", 1);
        CompletableFuture<Void> queued = manager.acquire("m2", 0);
        assertEquals(2, manager.issuedCount());
        assertEquals(1, manager.queueSize());

        manager.forceReset();
        assertEquals(0, manager.issuedCount());
        assertEquals(0, manager.queueSize());
        assertTrue("Queued waiter should be cancelled", queued.isCancelled());

        // Can acquire again after reset
        assertTrue(manager.acquire("m3", 0).isDone());
        assertEquals(1, manager.issuedCount());
    }

    // ---- Staging reproduction: failed migration must not starve subsequent migration ----

    public void testFailedMigrationReleasesPermitsForNextMigration() {
        int maxPermits = 3;
        BackfillPermitManager manager = new BackfillPermitManager(AoscLogger.create(BackfillPermitManager.class), maxPermits);

        // m1: 3 shards acquire permits, 2 more are queued
        manager.acquire("m1", 0);
        manager.acquire("m1", 1);
        manager.acquire("m1", 2);
        CompletableFuture<Void> m1s3 = manager.acquire("m1", 3);
        CompletableFuture<Void> m1s4 = manager.acquire("m1", 4);

        assertFalse("shard 3 should be queued", m1s3.isDone());
        assertFalse("shard 4 should be queued", m1s4.isDone());
        assertEquals(maxPermits, manager.issuedCount());

        // m1 FAILS — coordinator calls releaseAllForMigration
        manager.releaseAllForMigration("m1");

        assertEquals(0, manager.issuedCount());
        assertEquals(0, manager.queueSize());
        assertTrue("m1 shard 3 waiter should be cancelled", m1s3.isCancelled());
        assertTrue("m1 shard 4 waiter should be cancelled", m1s4.isCancelled());

        // m2 starts — must be able to acquire permits immediately
        assertTrue("m2 shard 0 should get permit", manager.acquire("m2", 0).isDone());
        assertTrue("m2 shard 1 should get permit", manager.acquire("m2", 1).isDone());
        assertTrue("m2 shard 2 should get permit", manager.acquire("m2", 2).isDone());
        assertEquals(maxPermits, manager.issuedCount());
    }

    public void testFailedMigrationWithPartialWorkerRelease() {
        BackfillPermitManager manager = new BackfillPermitManager(AoscLogger.create(BackfillPermitManager.class), 3);

        manager.acquire("m1", 0);
        manager.acquire("m1", 1);
        manager.acquire("m1", 2);

        // Worker for shard 0 closes cleanly and releases
        manager.release("m1", 0);
        assertEquals(2, manager.issuedCount());

        // Bulk cleanup catches shards 1 and 2
        manager.releaseAllForMigration("m1");
        assertEquals(0, manager.issuedCount());

        // Next migration works fine
        assertTrue("m2 should get permit", manager.acquire("m2", 0).isDone());
    }

    public void testReleaseBeforeGrantMarksDeadAndSkips() {
        // Reproduces: worker in PENDING calls acquire (queued), then migration
        // fails and worker calls release before the waiter is granted.
        BackfillPermitManager manager = new BackfillPermitManager(AoscLogger.create(BackfillPermitManager.class), 1);
        manager.acquire("m1", 0); // fills the single permit
        CompletableFuture<Void> m1s1 = manager.acquire("m1", 1); // queued
        assertFalse(m1s1.isDone());

        // Worker for shard 1 closes (migration failed) — calls release before granted
        manager.release("m1", 1);

        // Now release shard 0 — triggers grantWaiters
        manager.release("m1", 0);

        // m1s1 should be cancelled (dead), NOT granted
        assertTrue("Dead waiter should be cancelled", m1s1.isCancelled());
        assertEquals("No permits should be held", 0, manager.issuedCount());

        // New migration should work fine
        assertTrue(manager.acquire("m2", 0).isDone());
        assertEquals(1, manager.issuedCount());
    }

    public void testTwoMigrationsIndependent() {
        BackfillPermitManager manager = new BackfillPermitManager(AoscLogger.create(BackfillPermitManager.class), 4);

        manager.acquire("m1", 0);
        manager.acquire("m1", 1);
        manager.acquire("m2", 0);
        manager.acquire("m2", 1);
        assertEquals(4, manager.issuedCount());

        // m1 fails — m2 unaffected
        manager.releaseAllForMigration("m1");
        assertEquals(2, manager.issuedCount());

        manager.release("m2", 0);
        manager.release("m2", 1);
        assertEquals(0, manager.issuedCount());
    }

    // ---- Concurrency stress ----

    public void testConcurrentAcquireRelease() throws Exception {
        int maxPermits = 5;
        int numThreads = 20;
        int opsPerThread = 100;
        BackfillPermitManager manager = new BackfillPermitManager(AoscLogger.create(BackfillPermitManager.class), maxPermits);
        AtomicInteger concurrentActive = new AtomicInteger(0);
        AtomicInteger maxConcurrentSeen = new AtomicInteger(0);
        CyclicBarrier barrier = new CyclicBarrier(numThreads);
        CountDownLatch done = new CountDownLatch(numThreads);

        ExecutorService executor = Executors.newFixedThreadPool(numThreads);
        try {
            for (int t = 0; t < numThreads; t++) {
                final int threadId = t;
                executor.submit(() -> {
                    try {
                        barrier.await(5, TimeUnit.SECONDS);
                        for (int i = 0; i < opsPerThread; i++) {
                            // Use unique shard ID per iteration to avoid double-acquire
                            int shardId = threadId * opsPerThread + i;
                            CompletableFuture<Void> permit = manager.acquire("stress", shardId);
                            permit.get(5, TimeUnit.SECONDS);
                            int active = concurrentActive.incrementAndGet();
                            maxConcurrentSeen.accumulateAndGet(active, Math::max);
                            Thread.yield();
                            concurrentActive.decrementAndGet();
                            manager.release("stress", shardId);
                        }
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    } finally {
                        done.countDown();
                    }
                });
            }

            assertTrue("All threads should complete", done.await(30, TimeUnit.SECONDS));
            assertEquals("All permits should be returned", 0, manager.issuedCount());
            assertEquals("Queue should be empty", 0, manager.queueSize());
            assertTrue(
                "Max concurrent (" + maxConcurrentSeen.get() + ") exceeded permits (" + maxPermits + ")",
                maxConcurrentSeen.get() <= maxPermits
            );
        } finally {
            executor.shutdownNow();
        }
    }
}
