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
import org.opensearch.common.unit.TimeValue;
import org.opensearch.core.action.ActionListener;
import org.opensearch.test.OpenSearchTestCase;
import org.opensearch.threadpool.TestThreadPool;
import org.opensearch.threadpool.ThreadPool;

import java.util.ArrayList;
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
public class CoordinatorUpdateChannelTests extends OpenSearchTestCase {

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
    private final AtomicReference<ShardPhase> currentPhase = new AtomicReference<>(ShardPhase.PENDING);
    private final List<ShardPhase> received = new CopyOnWriteArrayList<>();
    private final AtomicInteger inFlight = new AtomicInteger(0);
    private final AtomicInteger maxConcurrent = new AtomicInteger(0);
    private final AtomicInteger failuresRemaining = new AtomicInteger(0);

    @Override
    public void setUp() throws Exception {
        super.setUp();
        threadPool = new TestThreadPool("channel-test");
        client = mock(Client.class);
        // Ack asynchronously (small delay) so single-in-flight is genuinely exercised.
        doAnswer(inv -> {
            UpdateShardMigrationStatusRequest req = inv.getArgument(1);
            @SuppressWarnings("unchecked")
            ActionListener<UpdateShardMigrationStatusResponse> listener = inv.getArgument(2);
            received.add(req.body().progress().phase());
            int now = inFlight.incrementAndGet();
            maxConcurrent.accumulateAndGet(now, Math::max);
            boolean fail = failuresRemaining.getAndDecrement() > 0;
            threadPool.schedule(() -> {
                inFlight.decrementAndGet();
                if (fail) listener.onFailure(new RuntimeException("transient"));
                else listener.onResponse(new UpdateShardMigrationStatusResponse());
            }, TimeValue.timeValueMillis(5), ThreadPool.Names.GENERIC);
            return null;
        }).when(client).execute(any(), any(), any());
    }

    @Override
    public void tearDown() throws Exception {
        terminate(threadPool);
        super.tearDown();
    }

    private CoordinatorUpdateChannel newChannel(int maxConsecutiveFailures) {
        return new CoordinatorUpdateChannel(
            AoscLogger.create(CoordinatorUpdateChannel.class),
            client,
            threadPool,
            "mig-1",
            7,
            () -> ShardProgressDocument.builder().phase(currentPhase.get()).build(),
            /*retryBaseDelayMs*/ 20,
            maxConsecutiveFailures
        );
    }

    private static void assertMonotonic(List<ShardPhase> seq) {
        int last = -1;
        for (ShardPhase p : seq) {
            int idx = ORDER.indexOf(p);
            assertTrue("non-monotonic phase " + p + " in " + seq, idx >= last);
            last = idx;
        }
    }

    // The write barrier is BLOCKING: await each transition (as the SM does). Each is delivered,
    // in order, with never more than one send in flight.
    public void testBlockingBarrierDeliversEachTransitionInOrder() throws Exception {
        CoordinatorUpdateChannel channel = newChannel(10);
        for (ShardPhase p : ORDER) {
            currentPhase.set(p);
            channel.reportTransition(p).get(5, TimeUnit.SECONDS); // blocks until p is ack'd
        }
        assertEquals("every transition delivered, in order", ORDER, received);
        assertTrue("never more than one in flight, was " + maxConcurrent.get(), maxConcurrent.get() <= 1);
        channel.close();
    }

    // A transient failure is retried (bounded), and the blocking barrier future still completes.
    public void testRetryThenBarrierCompletes() throws Exception {
        failuresRemaining.set(2);
        currentPhase.set(ShardPhase.BACKFILLING);
        CoordinatorUpdateChannel channel = newChannel(10);

        channel.reportTransition(ShardPhase.BACKFILLING).get(5, TimeUnit.SECONDS);
        assertTrue("retried at least 3 attempts", received.size() >= 3);
        assertTrue("never more than one in flight, was " + maxConcurrent.get(), maxConcurrent.get() <= 1);
        channel.close();
    }

    // Exhausting retries fails the barrier future → the SM goes FAILING (unchanged contract).
    public void testGiveUpFailsBarrier() throws Exception {
        failuresRemaining.set(Integer.MAX_VALUE);
        currentPhase.set(ShardPhase.REPLAYING);
        CoordinatorUpdateChannel channel = newChannel(3);

        CompletableFuture<Void> waiter = channel.reportTransition(ShardPhase.REPLAYING);
        ExecutionException ex = expectThrows(ExecutionException.class, () -> waiter.get(5, TimeUnit.SECONDS));
        assertNotNull(ex.getCause());
        channel.close();
    }

    // A fire-and-forget heartbeat reaches the coordinator with the current phase.
    public void testHeartbeatDelivers() throws Exception {
        currentPhase.set(ShardPhase.CONVERGING);
        CoordinatorUpdateChannel channel = newChannel(10);

        channel.heartbeat();
        assertBusy(() -> assertTrue("heartbeat delivered", received.contains(ShardPhase.CONVERGING)), 5, TimeUnit.SECONDS);
        channel.close();
    }

    // Heartbeats interleaved with blocking transitions never reorder on the wire.
    public void testInterleavedHeartbeatsAndTransitionsMonotonic() throws Exception {
        CoordinatorUpdateChannel channel = newChannel(10);
        List<CompletableFuture<Void>> barrierWaiters = new ArrayList<>();
        for (ShardPhase p : ORDER) {
            currentPhase.set(p);
            channel.heartbeat();
            barrierWaiters.add(channel.reportTransition(p));
            channel.heartbeat();
            barrierWaiters.get(barrierWaiters.size() - 1).get(5, TimeUnit.SECONDS); // block like the SM
        }
        assertMonotonic(received);
        assertTrue("never more than one in flight, was " + maxConcurrent.get(), maxConcurrent.get() <= 1);
        channel.close();
    }

    // Build-at-send-time: a send queued while another is in flight reads the phase AT SEND TIME,
    // not when it was requested. This is what makes a late tick unable to put a stale phase on the
    // wire after a newer one — the queued send always picks up whatever the current phase has become.
    public void testQueuedSendReadsPhaseAtSendTimeNotRequestTime() throws Exception {
        // Manual-ack client: capture the phase + listener, do NOT ack until the test says so.
        List<ActionListener<UpdateShardMigrationStatusResponse>> pending = new ArrayList<>();
        doAnswer(inv -> {
            UpdateShardMigrationStatusRequest req = inv.getArgument(1);
            received.add(req.body().progress().phase());
            pending.add(inv.getArgument(2));
            return null;
        }).when(client).execute(any(), any(), any());

        currentPhase.set(ShardPhase.CONVERGED);
        CoordinatorUpdateChannel channel = newChannel(10);

        channel.heartbeat(); // send #1 fires at CONVERGED, held in flight (not yet ack'd)
        assertEquals(1, pending.size());
        assertEquals(ShardPhase.CONVERGED, received.get(0));

        channel.heartbeat(); // requested while phase is STILL CONVERGED and the gate is held -> queued, not sent
        assertEquals("single-in-flight: the second request is queued, not sent", 1, pending.size());

        currentPhase.set(ShardPhase.COMPLETED); // phase advances AFTER the queued request, before it actually sends

        pending.get(0).onResponse(new UpdateShardMigrationStatusResponse()); // ack #1 -> drains the queued send
        assertEquals(2, pending.size());
        assertEquals(
            "queued send must carry the phase at SEND time (COMPLETED), not request time (CONVERGED)",
            ShardPhase.COMPLETED,
            received.get(1)
        );

        pending.get(1).onResponse(new UpdateShardMigrationStatusResponse());
        channel.close();
    }
}
