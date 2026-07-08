/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */
package com.atlassian.opensearch.aosc.service.worker;

import com.atlassian.opensearch.aosc.action.update.UpdateShardMigrationStatusRequest;
import com.atlassian.opensearch.aosc.action.update.UpdateShardMigrationStatusResponse;
import com.atlassian.opensearch.aosc.model.ShardProgressDocument;
import com.atlassian.opensearch.aosc.model.phase.ShardPhase;
import com.atlassian.opensearch.aosc.utils.AoscLogger;

import com.carrotsearch.randomizedtesting.annotations.ThreadLeakScope;

import org.opensearch.client.Client;
import org.opensearch.core.action.ActionListener;
import org.opensearch.test.OpenSearchTestCase;
import org.opensearch.threadpool.TestThreadPool;
import org.opensearch.threadpool.ThreadPool;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;

@ThreadLeakScope(ThreadLeakScope.Scope.NONE)
public class ShardStatusReporterTests extends OpenSearchTestCase {

    private static final List<ShardPhase> ORDER = List.of(
        ShardPhase.PENDING,
        ShardPhase.ACQUIRING_LEASE,
        ShardPhase.BACKFILLING,
        ShardPhase.REPLAYING,
        ShardPhase.CONVERGING,
        ShardPhase.CONVERGED,
        ShardPhase.CATCHING_UP,
        ShardPhase.COMPLETING,
        ShardPhase.COMPLETED
    );

    private ThreadPool threadPool;
    private Client client;
    private ShardStatusReporter reporter;

    private final AtomicReference<ShardPhase> currentPhase = new AtomicReference<>(ShardPhase.PENDING);
    private final List<ShardPhase> received = new CopyOnWriteArrayList<>();
    private final AtomicInteger failuresRemaining = new AtomicInteger(0);
    private volatile boolean coordinatorGone;
    private volatile ShardPhase failingPhase; // when set, sends carrying this phase always fail

    @Override
    public void setUp() throws Exception {
        super.setUp();
        threadPool = new TestThreadPool("reporter-test");
        client = mock(Client.class);
        doAnswer(inv -> {
            UpdateShardMigrationStatusRequest req = inv.getArgument(1);
            @SuppressWarnings("unchecked")
            ActionListener<UpdateShardMigrationStatusResponse> listener = inv.getArgument(2);
            ShardPhase phase = req.body().progress().phase();
            received.add(phase);
            if (coordinatorGone) {
                listener.onFailure(new RuntimeException("Batcher closed for migration"));
            } else if (phase == failingPhase) {
                listener.onFailure(new RuntimeException("transient for " + phase));
            } else if (failuresRemaining.getAndDecrement() > 0) {
                listener.onFailure(new RuntimeException("transient"));
            } else {
                listener.onResponse(new UpdateShardMigrationStatusResponse());
            }
            return null;
        }).when(client).execute(any(), any(), any());
    }

    @Override
    public void tearDown() throws Exception {
        if (reporter != null) reporter.close();
        terminate(threadPool);
        super.tearDown();
    }

    private ShardStatusReporter newReporter(int maxRetries) {
        return new ShardStatusReporter(
            AoscLogger.create(ShardStatusReporter.class),
            client,
            threadPool,
            r -> new Thread(r, "test-shard-reporter"),
            "mig-1",
            7,
            () -> ShardProgressDocument.builder().phase(currentPhase.get()).build(), // build-at-send
            /*heartbeatMs*/ 50,
            /*baseDelayMs*/ 1,
            /*maxDelayMs*/ 5,
            /*giveUpTimeoutMs*/ 30_000,
            /*perAttemptTimeoutMs*/ 5_000,
            maxRetries
        );
    }

    public void testBarrierDeliveredCompletesFuture() throws Exception {
        reporter = newReporter(10);
        reporter.start();
        currentPhase.set(ShardPhase.BACKFILLING);
        reporter.reportTransition().get(5, TimeUnit.SECONDS); // completes on ack
        assertTrue("barrier phase was delivered", received.contains(ShardPhase.BACKFILLING));
    }

    public void testBarrierRetriesThenCompletes() throws Exception {
        failuresRemaining.set(2);
        reporter = newReporter(10);
        reporter.start();
        currentPhase.set(ShardPhase.REPLAYING);
        reporter.reportTransition().get(5, TimeUnit.SECONDS);
        assertTrue("retried at least twice before success, was " + received.size(), received.size() >= 3);
    }

    public void testBarrierExhaustsRetriesFailsFuture() {
        failuresRemaining.set(Integer.MAX_VALUE); // never succeeds
        reporter = newReporter(3);
        reporter.start();
        currentPhase.set(ShardPhase.CONVERGING);
        ExecutionException ee = expectThrows(ExecutionException.class, () -> reporter.reportTransition().get(5, TimeUnit.SECONDS));
        assertNotNull("future failed -> SM would transition to FAILING", ee.getCause());
    }

    public void testCoordinatorGoneCompletesFutureSuccessfully() throws Exception {
        coordinatorGone = true;
        reporter = newReporter(10);
        reporter.start();
        currentPhase.set(ShardPhase.COMPLETING);
        // "Batcher closed" is non-retryable and treated as delivered — the future completes, not fails.
        reporter.reportTransition().get(5, TimeUnit.SECONDS);
    }

    public void testHeartbeatSendsCurrentPhaseAtSendTime() throws Exception {
        reporter = newReporter(10);
        reporter.start();
        // No barrier enqueued: the loop's poll timeout drives heartbeats, each built at send time —
        // so changing the current phase is reflected by subsequent heartbeats (build-at-send).
        currentPhase.set(ShardPhase.BACKFILLING);
        assertBusy(() -> assertTrue(received.contains(ShardPhase.BACKFILLING)), 5, TimeUnit.SECONDS);
        currentPhase.set(ShardPhase.REPLAYING);
        assertBusy(() -> assertTrue(received.contains(ShardPhase.REPLAYING)), 5, TimeUnit.SECONDS);
    }

    public void testTransitionsDeliveredInOrder() throws Exception {
        reporter = newReporter(10);
        reporter.start();
        for (ShardPhase p : ORDER) {
            currentPhase.set(p);
            reporter.reportTransition().get(5, TimeUnit.SECONDS); // block like the SM does
        }
        // Single-threaded delivery means the wire sequence never regresses (heartbeats may repeat a phase).
        assertMonotonic(received);
        assertTrue("every transition was delivered", received.containsAll(ORDER));
    }

    public void testNewerBarrierSupersedesInFlight() throws Exception {
        failingPhase = ShardPhase.CONVERGING; // the first barrier can never be ack'd
        reporter = newReporter(1000); // high count so it would otherwise retry ~forever
        reporter.start();

        currentPhase.set(ShardPhase.CONVERGING);
        CompletableFuture<Void> superseded = reporter.reportTransition();
        assertBusy(() -> assertTrue(received.contains(ShardPhase.CONVERGING)), 5, TimeUnit.SECONDS); // it's retrying

        // An interrupt-style newer barrier arrives; the in-flight one must stop and hand off, not fail the shard.
        currentPhase.set(ShardPhase.CONVERGED);
        CompletableFuture<Void> newer = reporter.reportTransition();

        newer.get(5, TimeUnit.SECONDS); // the newer barrier is delivered
        superseded.get(5, TimeUnit.SECONDS); // the superseded one completes normally (not exceptionally)
    }

    public void testCloseUnblocksPendingBarrier() throws Exception {
        failuresRemaining.set(Integer.MAX_VALUE); // keep the barrier retrying
        reporter = newReporter(1000);
        reporter.start();
        currentPhase.set(ShardPhase.BACKFILLING);
        CompletableFuture<Void> f = reporter.reportTransition();
        assertBusy(() -> assertFalse(received.isEmpty()), 5, TimeUnit.SECONDS); // delivery has started
        reporter.close();
        expectThrows(ExecutionException.class, () -> f.get(5, TimeUnit.SECONDS)); // SM is unblocked, not hung
    }

    private static void assertMonotonic(List<ShardPhase> seq) {
        int last = -1;
        for (ShardPhase p : seq) {
            int idx = ORDER.indexOf(p);
            assertTrue("non-monotonic phase " + p + " in " + seq, idx >= last);
            last = idx;
        }
    }
}
