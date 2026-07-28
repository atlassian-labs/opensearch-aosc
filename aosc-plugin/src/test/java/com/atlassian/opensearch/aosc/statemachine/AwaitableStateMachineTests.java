/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */
package com.atlassian.opensearch.aosc.statemachine;

import org.opensearch.test.OpenSearchTestCase;

import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

public class AwaitableStateMachineTests extends OpenSearchTestCase {

    enum Phase {
        INIT,
        ACTIVE,
        CUTTING_OVER,
        COMPLETED,
        CANCELLING,
        CANCELLED,
        FAILING,
        FAILED
    }

    private ExecutorService smExecutor;

    @Override
    public void setUp() throws Exception {
        super.setUp();
        smExecutor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "test-sm");
            t.setUncaughtExceptionHandler((thread, ex) -> logger.error("Uncaught exception in test-sm thread", ex));
            return t;
        });
    }

    @Override
    public void tearDown() throws Exception {
        smExecutor.shutdown();
        assertTrue("SM executor should shut down", smExecutor.awaitTermination(10, TimeUnit.SECONDS));
        super.tearDown();
    }

    private AwaitableStateMachine.Builder<Phase> baseBuilder() {
        return AwaitableStateMachine.builder("test", smExecutor, Phase.INIT)
            .permit(Phase.INIT, Phase.ACTIVE)
            .permit(Phase.ACTIVE, Phase.CUTTING_OVER)
            .permit(Phase.CUTTING_OVER, Phase.COMPLETED)
            .permit(Phase.ACTIVE, Phase.CANCELLING)
            .permit(Phase.ACTIVE, Phase.FAILING)
            .permit(Phase.CANCELLING, Phase.CANCELLED)
            .permit(Phase.CANCELLING, Phase.FAILING)
            .permit(Phase.FAILING, Phase.FAILED)
            .terminal(Phase.COMPLETED)
            .terminal(Phase.CANCELLED)
            .terminal(Phase.FAILED)
            .handler(Phase.COMPLETED, ctx -> CompletableFuture.completedFuture(Phase.COMPLETED))
            .handler(Phase.CANCELLED, ctx -> CompletableFuture.completedFuture(Phase.CANCELLED))
            .handler(Phase.FAILED, ctx -> CompletableFuture.completedFuture(Phase.FAILED))
            .writeBarrier((from, to) -> CompletableFuture.completedFuture(null))
            .onFailure(fc -> {});
    }

    // 1. Handler returns future → SM awaits → transitions
    public void testHandlerReturnsNextPhase() throws Exception {
        AwaitableStateMachine<Phase> sm = baseBuilder().handler(Phase.INIT, ctx -> CompletableFuture.completedFuture(Phase.ACTIVE))
            .handler(Phase.ACTIVE, ctx -> CompletableFuture.completedFuture(Phase.CUTTING_OVER))
            .handler(Phase.CUTTING_OVER, ctx -> CompletableFuture.completedFuture(Phase.COMPLETED))
            .build();
        sm.start();
        assertBusy(() -> assertEquals(Phase.COMPLETED, sm.currentState()));
        assertTrue(sm.isTerminal());
        sm.close();
    }

    // 2. Write barrier called between transitions
    public void testWriteBarrierCalledBetweenTransitions() throws Exception {
        AtomicInteger barrierCount = new AtomicInteger();
        AtomicReference<String> transitions = new AtomicReference<>("");
        AwaitableStateMachine<Phase> sm = baseBuilder().handler(Phase.INIT, ctx -> CompletableFuture.completedFuture(Phase.ACTIVE))
            .handler(Phase.ACTIVE, ctx -> CompletableFuture.completedFuture(Phase.CUTTING_OVER))
            .handler(Phase.CUTTING_OVER, ctx -> CompletableFuture.completedFuture(Phase.COMPLETED))
            .writeBarrier((from, to) -> {
                barrierCount.incrementAndGet();
                transitions.updateAndGet(s -> s + from + "->" + to + ";");
                return CompletableFuture.completedFuture(null);
            })
            .build();
        sm.start();
        assertBusy(() -> assertEquals(Phase.COMPLETED, sm.currentState()));
        // INIT->ACTIVE, ACTIVE->CUTTING_OVER, CUTTING_OVER->COMPLETED = 3 barriers
        assertEquals(3, barrierCount.get());
        assertTrue(transitions.get().contains("INIT->ACTIVE"));
        assertTrue(transitions.get().contains("ACTIVE->CUTTING_OVER"));
        assertTrue(transitions.get().contains("CUTTING_OVER->COMPLETED"));
        sm.close();
    }

    // 3. Write barrier failure triggers onFailure
    public void testWriteBarrierFailureTriggersOnFailure() throws Exception {
        AtomicReference<AwaitableFailureContext<Phase>> failure = new AtomicReference<>();
        CountDownLatch failureLatch = new CountDownLatch(1);
        AwaitableStateMachine<Phase> sm = baseBuilder().handler(Phase.INIT, ctx -> CompletableFuture.completedFuture(Phase.ACTIVE))
            .writeBarrier((from, to) -> CompletableFuture.failedFuture(new RuntimeException("barrier fail")))
            .onFailure(fc -> {
                failure.set(fc);
                failureLatch.countDown();
            })
            .build();
        sm.start();
        assertTrue(failureLatch.await(5, TimeUnit.SECONDS));
        assertEquals(Phase.ACTIVE, failure.get().failedInState());
        assertEquals("barrier fail", failure.get().message());
        sm.close();
    }

    // 4. transitionTo while handler in-flight
    public void testTransitionToWhileHandlerInFlight() throws Exception {
        CompletableFuture<Phase> slow = new CompletableFuture<>();
        CountDownLatch activeHandlerStarted = new CountDownLatch(1);
        CountDownLatch cancellingStarted = new CountDownLatch(1);
        AwaitableStateMachine<Phase> sm = baseBuilder().handler(Phase.INIT, ctx -> CompletableFuture.completedFuture(Phase.ACTIVE))
            .handler(Phase.ACTIVE, ctx -> {
                activeHandlerStarted.countDown();
                return slow;
            })
            .handler(Phase.CANCELLING, ctx -> {
                cancellingStarted.countDown();
                return CompletableFuture.completedFuture(Phase.CANCELLED);
            })
            .build();
        sm.start();
        // Wait for ACTIVE handler to actually start (so inFlightFuture is set to slow)
        assertTrue(activeHandlerStarted.await(5, TimeUnit.SECONDS));
        sm.transitionTo(Phase.CANCELLING);
        assertTrue(cancellingStarted.await(5, TimeUnit.SECONDS));
        assertBusy(() -> assertEquals(Phase.CANCELLED, sm.currentState()));
        assertTrue(slow.isCancelled());
        sm.close();
    }

    // 5. CANCELLING → FAILING allowed, reverse not
    public void testFailingOverridesCancelling() throws Exception {
        CountDownLatch cancellingLatch = new CountDownLatch(1);
        AwaitableStateMachine<Phase> sm = baseBuilder().handler(Phase.INIT, ctx -> CompletableFuture.completedFuture(Phase.ACTIVE))
            .handler(Phase.ACTIVE, ctx -> new CompletableFuture<>())
            .handler(Phase.CANCELLING, ctx -> {
                cancellingLatch.countDown();
                return new CompletableFuture<>();
            })
            .handler(Phase.FAILING, ctx -> CompletableFuture.completedFuture(Phase.FAILED))
            .build();
        sm.start();
        assertBusy(() -> assertEquals(Phase.ACTIVE, sm.currentState()));
        sm.transitionTo(Phase.CANCELLING);
        assertTrue(cancellingLatch.await(5, TimeUnit.SECONDS));
        assertEquals(Phase.CANCELLING, sm.currentState());
        sm.transitionTo(Phase.FAILING);
        assertBusy(() -> assertEquals(Phase.FAILED, sm.currentState()));
        // FAILED is terminal → transitionTo(CANCELLING) must be rejected
        CompletableFuture<Phase> back = sm.transitionTo(Phase.CANCELLING);
        ExecutionException ex = expectThrows(ExecutionException.class, () -> back.get(5, TimeUnit.SECONDS));
        assertTrue(ex.getCause() instanceof IllegalStateException);
        sm.close();
    }

    // 6. Stale epoch future dropped
    public void testStaleEpochFutureDropped() throws Exception {
        CompletableFuture<Phase> slow = new CompletableFuture<>();
        AtomicBoolean cuttingOverHandlerCalled = new AtomicBoolean(false);
        AwaitableStateMachine<Phase> sm = baseBuilder().handler(Phase.INIT, ctx -> CompletableFuture.completedFuture(Phase.ACTIVE))
            .handler(Phase.ACTIVE, ctx -> slow)
            .handler(Phase.CUTTING_OVER, ctx -> {
                cuttingOverHandlerCalled.set(true);
                return CompletableFuture.completedFuture(Phase.COMPLETED);
            })
            .handler(Phase.CANCELLING, ctx -> CompletableFuture.completedFuture(Phase.CANCELLED))
            .build();
        sm.start();
        assertBusy(() -> assertEquals(Phase.ACTIVE, sm.currentState()));
        // Override with CANCELLING — bumps epoch, cancels slow
        sm.transitionTo(Phase.CANCELLING);
        assertBusy(() -> assertEquals(Phase.CANCELLED, sm.currentState()));
        // Now complete the stale slow future — should be dropped
        slow.complete(Phase.CUTTING_OVER);
        smExecutor.submit(() -> {}).get(5, TimeUnit.SECONDS); // flush executor
        assertFalse("CUTTING_OVER handler should not fire from stale future", cuttingOverHandlerCalled.get());
        assertEquals(Phase.CANCELLED, sm.currentState());
        sm.close();
    }

    // 7. resumeAtState + start fires handler once with isResume=true
    public void testResumeAtStateThenStart() throws Exception {
        AtomicBoolean isResumeSeen = new AtomicBoolean(false);
        AtomicInteger handlerCalls = new AtomicInteger(0);
        AwaitableStateMachine<Phase> sm = baseBuilder().handler(Phase.ACTIVE, ctx -> {
            isResumeSeen.set(ctx.isResume());
            handlerCalls.incrementAndGet();
            return CompletableFuture.completedFuture(Phase.CUTTING_OVER);
        }).handler(Phase.CUTTING_OVER, ctx -> CompletableFuture.completedFuture(Phase.COMPLETED)).build();
        sm.resumeAtState(Phase.ACTIVE);
        sm.start();
        assertBusy(() -> assertEquals(Phase.COMPLETED, sm.currentState()));
        assertTrue(isResumeSeen.get());
        assertEquals(1, handlerCalls.get());
        sm.close();
    }

    // 8. close cancels in-flight future
    public void testCloseCallsCancelOnInFlightFuture() throws Exception {
        CompletableFuture<Phase> never = new CompletableFuture<>();
        CountDownLatch activeStarted = new CountDownLatch(1);
        AwaitableStateMachine<Phase> sm = baseBuilder().handler(Phase.INIT, ctx -> CompletableFuture.completedFuture(Phase.ACTIVE))
            .handler(Phase.ACTIVE, ctx -> {
                activeStarted.countDown();
                return never;
            })
            .build();
        sm.start();
        assertTrue(activeStarted.await(5, TimeUnit.SECONDS));
        sm.close();
        assertBusy(() -> assertTrue(never.isCancelled()));
    }

    // 9. Self-transition fails loudly
    public void testSelfTransitionFailsLoudly() throws Exception {
        AtomicReference<AwaitableFailureContext<Phase>> failure = new AtomicReference<>();
        CountDownLatch failureLatch = new CountDownLatch(1);
        AwaitableStateMachine<Phase> sm = baseBuilder().handler(Phase.INIT, ctx -> CompletableFuture.completedFuture(Phase.INIT))
            .onFailure(fc -> {
                failure.set(fc);
                failureLatch.countDown();
            })
            .build();
        sm.start();
        assertTrue(failureLatch.await(5, TimeUnit.SECONDS));
        assertEquals(Phase.INIT, failure.get().failedInState());
        assertTrue(failure.get().message().contains("returned self"));
        sm.close();
    }

    // 10. Terminal state frozen
    public void testTerminalStateFrozen() throws Exception {
        AwaitableStateMachine<Phase> sm = baseBuilder().handler(Phase.INIT, ctx -> CompletableFuture.completedFuture(Phase.ACTIVE))
            .handler(Phase.ACTIVE, ctx -> CompletableFuture.completedFuture(Phase.CUTTING_OVER))
            .handler(Phase.CUTTING_OVER, ctx -> CompletableFuture.completedFuture(Phase.COMPLETED))
            .build();
        sm.start();
        assertBusy(() -> assertEquals(Phase.COMPLETED, sm.currentState()));
        CompletableFuture<Phase> rejected = sm.transitionTo(Phase.CANCELLING);
        ExecutionException ex = expectThrows(ExecutionException.class, () -> rejected.get(5, TimeUnit.SECONDS));
        assertTrue(ex.getCause() instanceof IllegalStateException);
        assertEquals(Phase.COMPLETED, sm.currentState());
        sm.close();
    }

    // 11. Handler chain advances automatically
    public void testHandlerChainAdvancesAutomatically() throws Exception {
        AtomicReference<String> order = new AtomicReference<>("");
        AwaitableStateMachine<Phase> sm = baseBuilder().handler(Phase.INIT, ctx -> {
            order.updateAndGet(s -> s + "I");
            return CompletableFuture.completedFuture(Phase.ACTIVE);
        }).handler(Phase.ACTIVE, ctx -> {
            order.updateAndGet(s -> s + "A");
            return CompletableFuture.completedFuture(Phase.CUTTING_OVER);
        }).handler(Phase.CUTTING_OVER, ctx -> {
            order.updateAndGet(s -> s + "C");
            return CompletableFuture.completedFuture(Phase.COMPLETED);
        }).build();
        sm.start();
        assertBusy(() -> assertEquals(Phase.COMPLETED, sm.currentState()));
        assertEquals("IAC", order.get());
        sm.close();
    }

    // 12. Context isResume flag
    public void testContextIsResumeFlag() throws Exception {
        AtomicBoolean freshIsResume = new AtomicBoolean(true);
        AwaitableStateMachine<Phase> sm = baseBuilder().handler(Phase.INIT, ctx -> {
            freshIsResume.set(ctx.isResume());
            return CompletableFuture.completedFuture(Phase.ACTIVE);
        }).handler(Phase.ACTIVE, ctx -> new CompletableFuture<>()).build();
        sm.start();
        assertBusy(() -> assertEquals(Phase.ACTIVE, sm.currentState()));
        assertFalse("Fresh start should have isResume=false", freshIsResume.get());
        sm.close();
    }

    // 13. Context previousState
    public void testContextPreviousState() throws Exception {
        AtomicReference<Phase> captured = new AtomicReference<>();
        AwaitableStateMachine<Phase> sm = baseBuilder().handler(Phase.INIT, ctx -> CompletableFuture.completedFuture(Phase.ACTIVE))
            .handler(Phase.ACTIVE, ctx -> {
                captured.set(ctx.previousState());
                return CompletableFuture.completedFuture(Phase.CUTTING_OVER);
            })
            .handler(Phase.CUTTING_OVER, ctx -> CompletableFuture.completedFuture(Phase.COMPLETED))
            .build();
        sm.start();
        assertBusy(() -> assertEquals(Phase.COMPLETED, sm.currentState()));
        assertEquals(Phase.INIT, captured.get());
        sm.close();
    }

    // 14. Context payload bag
    public void testContextPayloadBag() throws Exception {
        AtomicReference<String> readValue = new AtomicReference<>();
        AwaitableStateMachine<Phase> sm = baseBuilder().handler(Phase.INIT, ctx -> {
            ctx.set("key", "hello");
            return CompletableFuture.completedFuture(Phase.ACTIVE);
        }).handler(Phase.ACTIVE, ctx -> {
            readValue.set(ctx.get("key", String.class));
            return CompletableFuture.completedFuture(Phase.CUTTING_OVER);
        }).handler(Phase.CUTTING_OVER, ctx -> CompletableFuture.completedFuture(Phase.COMPLETED)).build();
        sm.start();
        assertBusy(() -> assertEquals(Phase.COMPLETED, sm.currentState()));
        assertEquals("hello", readValue.get());
        sm.close();
    }

    // 15. permitFromAnyExcept
    public void testPermitFromAnyExcept() throws Exception {
        AwaitableStateMachine<Phase> sm = baseBuilder().permitFromAnyExcept(
            Phase.FAILING,
            Set.of(Phase.COMPLETED, Phase.CANCELLED, Phase.FAILED)
        )
            .handler(Phase.INIT, ctx -> CompletableFuture.completedFuture(Phase.ACTIVE))
            .handler(Phase.ACTIVE, ctx -> new CompletableFuture<>())
            .handler(Phase.FAILING, ctx -> CompletableFuture.completedFuture(Phase.FAILED))
            .build();
        sm.start();
        assertBusy(() -> assertEquals(Phase.ACTIVE, sm.currentState()));
        sm.transitionTo(Phase.FAILING);
        assertBusy(() -> assertEquals(Phase.FAILED, sm.currentState()));
        sm.close();
    }

    // 16. Invalid transition returns failed future
    public void testInvalidTransitionReturnsFailedFuture() throws Exception {
        AwaitableStateMachine<Phase> sm = baseBuilder().handler(Phase.INIT, ctx -> CompletableFuture.completedFuture(Phase.ACTIVE))
            .handler(Phase.ACTIVE, ctx -> new CompletableFuture<>())
            .build();
        sm.start();
        assertBusy(() -> assertEquals(Phase.ACTIVE, sm.currentState()));
        // ACTIVE → COMPLETED not permitted (must go through CUTTING_OVER)
        CompletableFuture<Phase> bad = sm.transitionTo(Phase.COMPLETED);
        ExecutionException ex = expectThrows(ExecutionException.class, () -> bad.get(5, TimeUnit.SECONDS));
        assertTrue(ex.getCause() instanceof IllegalStateException);
        assertEquals(Phase.ACTIVE, sm.currentState());
        sm.close();
    }

    // 17. Build validation
    public void testBuildValidation() {
        // No onFailure → exception
        expectThrows(
            IllegalStateException.class,
            () -> AwaitableStateMachine.builder("test", smExecutor, Phase.INIT)
                .terminal(Phase.COMPLETED)
                .handler(Phase.INIT, ctx -> CompletableFuture.completedFuture(Phase.ACTIVE))
                .build()
        );
        // No terminal → exception
        expectThrows(
            IllegalStateException.class,
            () -> AwaitableStateMachine.builder("test", smExecutor, Phase.INIT)
                .onFailure(fc -> {})
                .handler(Phase.INIT, ctx -> CompletableFuture.completedFuture(Phase.ACTIVE))
                .build()
        );
    }

    // 18. Epoch increments on transition
    public void testCurrentEpochIncrementsOnTransition() throws Exception {
        AwaitableStateMachine<Phase> sm = baseBuilder().handler(Phase.INIT, ctx -> CompletableFuture.completedFuture(Phase.ACTIVE))
            .handler(Phase.ACTIVE, ctx -> new CompletableFuture<>())
            .build();
        assertEquals(0, sm.currentEpoch());
        sm.start();
        assertBusy(() -> assertEquals(Phase.ACTIVE, sm.currentState()));
        assertBusy(() -> assertTrue(sm.currentEpoch() > 0));
        sm.close();
    }

    // 19. Superseded transitionTo future completes
    public void testTransitionToFutureCompletesWhenSuperseded() throws Exception {
        CompletableFuture<Phase> slow = new CompletableFuture<>();
        CountDownLatch cancellingHandlerStarted = new CountDownLatch(1);
        AwaitableStateMachine<Phase> sm = baseBuilder().handler(Phase.INIT, ctx -> CompletableFuture.completedFuture(Phase.ACTIVE))
            .handler(Phase.ACTIVE, ctx -> new CompletableFuture<>())
            .handler(Phase.CANCELLING, ctx -> {
                cancellingHandlerStarted.countDown();
                return slow;
            })
            .handler(Phase.FAILING, ctx -> CompletableFuture.completedFuture(Phase.FAILED))
            .build();
        sm.start();
        assertBusy(() -> assertEquals(Phase.ACTIVE, sm.currentState()));
        CompletableFuture<Phase> cancelFuture = sm.transitionTo(Phase.CANCELLING);
        // Wait for handler to start before superseding — currentState is set before the handler runs.
        assertTrue(cancellingHandlerStarted.await(5, TimeUnit.SECONDS));
        CompletableFuture<Phase> failFuture = sm.transitionTo(Phase.FAILING);
        Phase failResult = failFuture.get(5, TimeUnit.SECONDS);
        Phase cancelResult = cancelFuture.get(5, TimeUnit.SECONDS);
        assertEquals(Phase.FAILED, failResult);
        assertEquals(Phase.CANCELLING, cancelResult);
        assertEquals(Phase.FAILED, sm.currentState());
        sm.close();
    }

    // 20. Failure handler doesn't deadlock (can call back into SM)
    public void testFailureHandlerDoesNotDeadlock() throws Exception {
        AtomicBoolean transitionAttempted = new AtomicBoolean(false);
        CountDownLatch failureLatch = new CountDownLatch(1);
        AwaitableStateMachine<Phase> sm = baseBuilder().handler(Phase.INIT, ctx -> CompletableFuture.completedFuture(Phase.ACTIVE))
            .handler(Phase.ACTIVE, ctx -> CompletableFuture.completedFuture(Phase.CUTTING_OVER))
            .handler(Phase.FAILING, ctx -> CompletableFuture.completedFuture(Phase.FAILED))
            .writeBarrier((from, to) -> CompletableFuture.failedFuture(new RuntimeException("barrier fail")))
            .onFailure(fctx -> {
                try {
                    fctx.sm().transitionTo(Phase.FAILING);
                    transitionAttempted.set(true);
                } catch (Exception e) {
                    transitionAttempted.set(true);
                } finally {
                    failureLatch.countDown();
                }
            })
            .build();
        sm.start();
        assertTrue("Failure handler should complete without deadlock", failureLatch.await(5, TimeUnit.SECONDS));
        assertTrue(transitionAttempted.get());
        sm.close();
    }

    // 21. FAILING→CANCELLING rejected (no permit)
    public void testFailingToCancellingRejected() throws Exception {
        AwaitableStateMachine<Phase> sm = baseBuilder().handler(Phase.INIT, ctx -> CompletableFuture.completedFuture(Phase.ACTIVE))
            .handler(Phase.ACTIVE, ctx -> new CompletableFuture<>())
            .handler(Phase.FAILING, ctx -> new CompletableFuture<>())
            .build();
        sm.start();
        assertBusy(() -> assertEquals(Phase.ACTIVE, sm.currentState()));
        sm.transitionTo(Phase.FAILING);
        assertBusy(() -> assertEquals(Phase.FAILING, sm.currentState()));
        CompletableFuture<Phase> rejected = sm.transitionTo(Phase.CANCELLING);
        ExecutionException ex = expectThrows(ExecutionException.class, () -> rejected.get(5, TimeUnit.SECONDS));
        assertTrue(ex.getCause() instanceof IllegalStateException);
        assertEquals(Phase.FAILING, sm.currentState());
        sm.close();
    }

    // 22. resumeAtState with invalid state throws
    public void testResumeAtInvalidStateThrows() {
        AwaitableStateMachine<Phase> sm = baseBuilder().handler(Phase.INIT, ctx -> CompletableFuture.completedFuture(Phase.ACTIVE)).build();
        // CUTTING_OVER has no handler and is not terminal — should reject
        expectThrows(IllegalArgumentException.class, () -> sm.resumeAtState(Phase.CUTTING_OVER));
        sm.close();
    }

    // 23. Concurrent transitionTo — only one wins
    @SuppressWarnings("unchecked")
    public void testConcurrentTransitionTo() throws Exception {
        AwaitableStateMachine<Phase> sm = baseBuilder().handler(Phase.INIT, ctx -> CompletableFuture.completedFuture(Phase.ACTIVE))
            .handler(Phase.ACTIVE, ctx -> new CompletableFuture<>())
            .handler(Phase.CANCELLING, ctx -> CompletableFuture.completedFuture(Phase.CANCELLED))
            .build();
        sm.start();
        assertBusy(() -> assertEquals(Phase.ACTIVE, sm.currentState()));
        int threads = 10;
        CyclicBarrier barrier = new CyclicBarrier(threads);
        CompletableFuture<Phase>[] futures = new CompletableFuture[threads];
        Thread[] threadArr = new Thread[threads];
        for (int i = 0; i < threads; i++) {
            final int idx = i;
            threadArr[i] = new Thread(() -> {
                try {
                    barrier.await();
                    futures[idx] = sm.transitionTo(Phase.CANCELLING);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            });
            threadArr[i].start();
        }
        for (Thread t : threadArr)
            t.join(5000);
        // Wait for SM to settle
        assertBusy(() -> assertTrue(sm.isTerminal()));
        // Exactly one should succeed, rest should get rejected
        int succeeded = 0;
        int rejected = 0;
        for (CompletableFuture<Phase> f : futures) {
            assertNotNull(f);
            try {
                f.get(5, TimeUnit.SECONDS);
                succeeded++;
            } catch (ExecutionException e) {
                rejected++;
            }
        }
        assertEquals(1, succeeded);
        assertEquals(threads - 1, rejected);
        sm.close();
    }

    // 24. Handler that throws synchronously triggers onFailure
    public void testHandlerThrowsSynchronouslyTriggersOnFailure() throws Exception {
        AtomicReference<AwaitableFailureContext<Phase>> failure = new AtomicReference<>();
        CountDownLatch latch = new CountDownLatch(1);
        AwaitableStateMachine<Phase> sm = baseBuilder().handler(Phase.INIT, ctx -> { throw new RuntimeException("handler exploded"); })
            .onFailure(fc -> {
                failure.set(fc);
                latch.countDown();
            })
            .build();
        sm.start();
        assertTrue(latch.await(5, TimeUnit.SECONDS));
        assertEquals(Phase.INIT, failure.get().failedInState());
        assertEquals("handler exploded", failure.get().message());
        sm.close();
    }

    // 25. All operations run on executor thread
    public void testHandlerRunsOnExecutorThread() throws Exception {
        AtomicReference<String> handlerThread = new AtomicReference<>();
        AwaitableStateMachine<Phase> sm = baseBuilder().handler(Phase.INIT, ctx -> {
            handlerThread.set(Thread.currentThread().getName());
            return CompletableFuture.completedFuture(Phase.ACTIVE);
        }).handler(Phase.ACTIVE, ctx -> new CompletableFuture<>()).build();
        sm.start();
        assertBusy(() -> assertEquals(Phase.ACTIVE, sm.currentState()));
        assertEquals("test-sm", handlerThread.get());
        sm.close();
    }

    // =====================================================================
    // History API tests
    // =====================================================================

    // 26. History records all phases in a normal run
    public void testHistoryRecordsNormalRun() throws Exception {
        AwaitableStateMachine<Phase> sm = baseBuilder().handler(Phase.INIT, ctx -> CompletableFuture.completedFuture(Phase.ACTIVE))
            .handler(Phase.ACTIVE, ctx -> CompletableFuture.completedFuture(Phase.CUTTING_OVER))
            .handler(Phase.CUTTING_OVER, ctx -> CompletableFuture.completedFuture(Phase.COMPLETED))
            .build();
        sm.start();
        assertBusy(() -> assertTrue(sm.history().size() >= 4));

        List<TransitionRecord<Phase>> history = sm.history();
        assertEquals(4, history.size());
        assertEquals(Phase.INIT, history.get(0).phase());
        assertEquals(Phase.ACTIVE, history.get(1).phase());
        assertEquals(Phase.CUTTING_OVER, history.get(2).phase());
        assertEquals(Phase.COMPLETED, history.get(3).phase());

        for (TransitionRecord<Phase> r : history) {
            assertTrue("duration should be >= 0 for " + r.phase(), r.durationMillis() >= 0);
        }
        for (int i = 1; i < history.size(); i++) {
            assertTrue(history.get(i).startTimeMillis() >= history.get(i - 1).startTimeMillis());
        }
        sm.close();
    }

    // 27. History is an immutable snapshot
    public void testHistoryReturnsImmutableSnapshot() throws Exception {
        AwaitableStateMachine<Phase> sm = baseBuilder().handler(Phase.INIT, ctx -> CompletableFuture.completedFuture(Phase.ACTIVE))
            .handler(Phase.ACTIVE, ctx -> new CompletableFuture<>())
            .build();
        sm.start();
        assertBusy(() -> assertEquals(Phase.ACTIVE, sm.currentState()));

        List<TransitionRecord<Phase>> snapshot = sm.history();
        int sizeBefore = snapshot.size();

        expectThrows(UnsupportedOperationException.class, () -> snapshot.add(null));
        assertEquals(sizeBefore, snapshot.size());
        sm.close();
    }

    // 28. History records terminal phase with onTerminalReached duration
    public void testHistoryRecordsTerminalWithCallback() throws Exception {
        AtomicBoolean callbackRan = new AtomicBoolean();
        AwaitableStateMachine<Phase> sm = baseBuilder().handler(Phase.INIT, ctx -> CompletableFuture.completedFuture(Phase.ACTIVE))
            .handler(Phase.ACTIVE, ctx -> CompletableFuture.completedFuture(Phase.CUTTING_OVER))
            .handler(Phase.CUTTING_OVER, ctx -> CompletableFuture.completedFuture(Phase.COMPLETED))
            .onTerminalReached(state -> {
                callbackRan.set(true);
                return CompletableFuture.completedFuture(null);
            })
            .build();
        sm.start();
        assertBusy(() -> {
            assertTrue(sm.isTerminal());
            assertTrue(callbackRan.get());
            assertTrue("terminal record should be present", sm.history().size() >= 4);
        });

        List<TransitionRecord<Phase>> history = sm.history();
        TransitionRecord<Phase> terminalRecord = history.get(history.size() - 1);
        assertEquals(Phase.COMPLETED, terminalRecord.phase());
        assertTrue(terminalRecord.durationMillis() >= 0);
        sm.close();
    }

    // 29. Empty history before start
    public void testHistoryEmptyBeforeStart() {
        AwaitableStateMachine<Phase> sm = baseBuilder().handler(Phase.INIT, ctx -> CompletableFuture.completedFuture(Phase.ACTIVE))
            .handler(Phase.ACTIVE, ctx -> new CompletableFuture<>())
            .build();
        assertTrue(sm.history().isEmpty());
        sm.close();
    }
}
