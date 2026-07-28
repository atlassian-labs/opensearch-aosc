/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */
package com.atlassian.opensearch.aosc.statemachine;

import org.apache.logging.log4j.Logger;

import org.opensearch.common.logging.Loggers;
import org.opensearch.common.util.concurrent.FutureUtils;

import java.io.Closeable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Async state machine with executor-based actor confinement.
 *
 * <h2>How it works (read this first)</h2>
 *
 * <p>The SM has a current state, a set of handlers (one per state), and a write barrier.
 * When a handler completes, the SM:
 * <ol>
 *   <li>Validates the returned next-state against the permit table</li>
 *   <li>Updates the current state</li>
 *   <li>Runs the write barrier (e.g., persist to cluster state)</li>
 *   <li>Fires the next handler</li>
 * </ol>
 * This loop continues until a terminal state is reached or the SM is closed.
 *
 * <h2>Threading</h2>
 *
 * <p>All state mutations are serialized on a single-threaded {@link Executor} provided
 * by the caller. Handler code runs on this thread and does NOT need to be thread-safe.
 * Async work (e.g., cluster state writes) runs on other threads, but completions are
 * always posted back to the executor before the SM processes them.
 *
 * <p>The executor <b>MUST</b> be single-threaded and FIFO-ordered. The SM does not own
 * the executor — the caller manages its lifecycle.
 *
 * <p>{@link #currentState()} and {@link #currentEpoch()} are volatile reads, safe from
 * any thread (for status APIs / logging).
 *
 * @param <S> the state enum type
 */
public class AwaitableStateMachine<S extends Enum<S>> implements Closeable {

    /** Context passed to handlers. */
    public interface Context<S> {
        /** True if this handler was entered via {@link #resumeAtState} (CM failover). */
        boolean isResume();

        /** The state we transitioned from, or null if this is the initial state. */
        S previousState();

        /** Read a value from the shared context bag. */
        <T> T get(String key, Class<T> type);

        /** Write a value to the shared context bag (readable by subsequent handlers). */
        void set(String key, Object value);
    }

    // -- Configuration (immutable after construction) --

    private final Executor executor;
    private final String id;
    private final Logger logger;
    private final Map<S, Function<Context<S>, CompletableFuture<S>>> handlers;
    private final Map<S, EnumSet<S>> validTransitions;
    private final Set<S> terminalStates;
    private final BiFunction<S, S, CompletableFuture<Void>> writeBarrier;
    private final Consumer<AwaitableFailureContext<S>> failureHandler;
    private final Function<S, CompletableFuture<Void>> onTerminalReached;

    // -- Actor-confined state (only touched on the executor thread) --
    // currentState and epoch are also volatile so external readers can observe them.

    private volatile S currentState;
    private volatile long epoch;
    private boolean started;
    private boolean closed;
    private boolean resumePending;
    private CompletableFuture<S> inFlightFuture;  // the handler's future, so we can cancel it
    private CompletableFuture<S> pendingResult;    // the transitionTo caller's future, so we can settle it on override

    // Shared bag — handlers can store data here for later handlers to read
    private final Map<String, Object> contextBag = new ConcurrentHashMap<>();

    // Transition history — writes happen on the executor thread (and whenComplete threads),
    // reads happen from transport/REST threads via history(). Guarded by synchronized(transitionHistory).
    private final List<TransitionRecord<S>> transitionHistory = new ArrayList<>();

    // =====================================================================
    // Construction
    // =====================================================================

    private AwaitableStateMachine(
        String id,
        Executor executor,
        S initial,
        Map<S, Function<Context<S>, CompletableFuture<S>>> handlers,
        Map<S, EnumSet<S>> validTransitions,
        Set<S> terminalStates,
        BiFunction<S, S, CompletableFuture<Void>> writeBarrier,
        Consumer<AwaitableFailureContext<S>> failureHandler,
        Function<S, CompletableFuture<Void>> onTerminalReached
    ) {
        this.id = id;
        this.executor = Objects.requireNonNull(executor, "executor");
        this.logger = Loggers.getLogger(AwaitableStateMachine.class, id);
        this.currentState = initial;
        this.handlers = handlers;
        this.validTransitions = validTransitions;
        this.terminalStates = terminalStates;
        this.writeBarrier = writeBarrier;
        this.failureHandler = failureHandler;
        this.onTerminalReached = onTerminalReached;
    }

    public static <S extends Enum<S>> Builder<S> builder(String name, Executor executor, S initialState) {
        return new Builder<>(name, executor, initialState);
    }

    // =====================================================================
    // Public API — all safe to call from any thread
    // =====================================================================

    public String id() {
        return id;
    }

    /** Current state (volatile read — safe from any thread). */
    public S currentState() {
        return currentState;
    }

    /**
     * Set a context entry before start(). Handlers can read this via {@link Context#get(String, Class)}.
     * Useful for seeding state on CM failover (e.g., known shard states for gate preFill).
     */
    public void setContext(String key, Object value) {
        contextBag.put(key, value);
    }

    /** Current epoch (volatile read — safe from any thread). Increments on every transition. */
    public long currentEpoch() {
        return epoch;
    }

    /** True if the SM has reached a terminal state. */
    public boolean isTerminal() {
        return terminalStates.contains(currentState);
    }

    /** Snapshot of all phase transition records so far. Safe from any thread. */
    public List<TransitionRecord<S>> history() {
        synchronized (transitionHistory) {
            return List.copyOf(transitionHistory);
        }
    }

    /**
     * Set the state for resume (e.g., after CM failover). Must be called BEFORE {@link #start()}.
     * The subsequent start() will fire the handler with {@link Context#isResume()} = true.
     */
    public void resumeAtState(S state) {
        if (!handlers.containsKey(state) && !terminalStates.contains(state)) {
            throw new IllegalArgumentException("Cannot resume at " + state + " — no handler and not terminal");
        }
        post(() -> {
            if (closed || started) return;
            this.currentState = state;
            this.resumePending = true;
        });
    }

    /** Start the SM — fires the handler for the current state. */
    public void start() {
        post(() -> {
            if (closed || started) return;
            started = true;
            boolean isResume = resumePending;
            resumePending = false;
            runHandler(currentState, epoch, isResume, null, null);
        });
    }

    /**
     * Request an external transition (e.g., cancel or fail from outside the SM).
     * Returns a future that completes when the transition + handler chain resolves.
     */
    public CompletableFuture<S> transitionTo(S target) {
        CompletableFuture<S> result = new CompletableFuture<>();
        postOrFail(() -> {
            // Guard: can we transition?
            if (closed) {
                result.completeExceptionally(new IllegalStateException("SM closed"));
                return;
            }
            if (terminalStates.contains(currentState)) {
                result.completeExceptionally(new IllegalStateException("SM frozen in terminal state " + currentState));
                return;
            }
            if (!isPermitted(currentState, target)) {
                result.completeExceptionally(new IllegalStateException("Not permitted: " + currentState + " -> " + target));
                return;
            }

            // Cancel whatever the SM is currently doing
            cancelInFlight();

            // Advance to the target state
            S oldState = currentState;
            epoch++;
            currentState = target;
            logger.debug("[{}] transition {} -> {} (epoch={})", id, oldState, target, epoch);

            // Run barrier, then fire handler for target
            persistThenRun(oldState, target, epoch, false, result);
        }, result);
        return result;
    }

    @Override
    public void close() {
        closeAsync();
    }

    /** Async close — returns a future that completes when the SM is fully shut down. */
    public CompletableFuture<Void> closeAsync() {
        CompletableFuture<Void> done = new CompletableFuture<>();
        postOrFail(() -> {
            if (closed) {
                done.complete(null);
                return;
            }
            closed = true;
            cancelInFlight();
            done.complete(null);
        }, done);
        return done;
    }

    // =====================================================================
    // The advance loop — this is the heart of the SM
    //
    // The loop is: runHandler → (handler completes) → advance → persistThenRun → runHandler → ...
    // It stops when a terminal state is reached or the SM is closed.
    // =====================================================================

    /**
     * Step 1: Run the handler for the given state.
     * The handler returns a CompletableFuture of the next state.
     * When it completes, we post back to the executor and call advance().
     */
    private void runHandler(S state, long atEpoch, boolean isResume, S previousState, CompletableFuture<S> callerFuture) {
        if (isStale(atEpoch)) {
            settle(callerFuture, currentState);
            return;
        }

        Function<Context<S>, CompletableFuture<S>> handler = handlers.get(state);
        if (handler == null) {
            settle(callerFuture, state);
            return;
        }

        // Store callerFuture so transitionTo() can settle it if it overrides us
        this.pendingResult = callerFuture;

        // Invoke the handler — this runs on the executor thread
        long handlerStartTime = System.currentTimeMillis();
        CompletableFuture<S> future;
        try {
            future = handler.apply(new ContextSnapshot(isResume, previousState, contextBag));
        } catch (Exception e) {
            recordPhase(state, handlerStartTime);
            this.pendingResult = null;
            handleError(state, e, callerFuture);
            return;
        }
        if (future == null) {
            recordPhase(state, handlerStartTime);
            this.pendingResult = null;
            handleError(state, new NullPointerException("Handler for " + state + " returned null"), callerFuture);
            return;
        }

        inFlightFuture = future;

        // When the handler's async work completes, post back to executor and advance
        future.whenComplete((nextState, error) -> {
            long handlerEndTime = System.currentTimeMillis();
            post(() -> {
                recordPhase(state, handlerStartTime, handlerEndTime);
                advance(state, atEpoch, nextState, error);
            });
        });
    }

    /**
     * Step 2: The handler completed. Validate the result and move to the next state.
     */
    private void advance(S fromState, long atEpoch, S nextState, Throwable error) {
        inFlightFuture = null;
        CompletableFuture<S> callerFuture = this.pendingResult;
        this.pendingResult = null;

        // Drop stale completions (a transitionTo() happened while we were waiting)
        if (isStale(atEpoch)) return;

        // Handle errors from the handler
        if (error != null) {
            Throwable cause = (error instanceof CompletionException) ? error.getCause() : error;
            if (cause instanceof CancellationException) return; // expected from transitionTo override
            handleError(fromState, asException(cause), callerFuture);
            return;
        }

        // Null next state
        if (nextState == null) {
            handleError(fromState, new NullPointerException("Handler returned null next state"), callerFuture);
            return;
        }

        // Self-transition is a bug — handlers must return a different state
        if (nextState == fromState) {
            handleError(fromState, new IllegalStateException("Handler for " + fromState + " returned self — this is a bug"), callerFuture);
            return;
        }

        // Validate the transition
        if (!isPermitted(fromState, nextState)) {
            handleError(
                fromState,
                new IllegalStateException("Handler returned invalid transition: " + fromState + " -> " + nextState),
                callerFuture
            );
            return;
        }

        // Advance the state
        S oldState = fromState;
        epoch++;
        currentState = nextState;
        logger.debug("[{}] advance {} -> {} (epoch={})", id, oldState, nextState, epoch);

        // Terminal state: persist, then we're done
        if (terminalStates.contains(nextState)) {
            persistTerminal(oldState, nextState, callerFuture);
            return;
        }

        // Non-terminal: persist, then run the next handler
        persistThenRun(oldState, nextState, epoch, false, callerFuture);
    }

    /**
     * Step 3a (non-terminal): Run the write barrier, then fire the next handler.
     */
    private void persistThenRun(S oldState, S newState, long atEpoch, boolean isResume, CompletableFuture<S> callerFuture) {
        runBarrier(oldState, newState).whenComplete((ok, error) -> post(() -> {
            if (isStale(atEpoch)) {
                settle(callerFuture, currentState);
                return;
            }
            if (error != null) {
                handleError(newState, asException(error), callerFuture);
                return;
            }
            runHandler(newState, atEpoch, isResume, oldState, callerFuture);
        }));
    }

    /**
     * Step 3b (terminal): Run the write barrier for the final state, then settle.
     */
    private void persistTerminal(S oldState, S terminalState, CompletableFuture<S> callerFuture) {
        long terminalStartMs = System.currentTimeMillis();
        runBarrier(oldState, terminalState).whenComplete((ok, error) -> post(() -> {
            if (error != null) {
                logger.warn("[{}] Write barrier failed at terminal {}: {}", id, terminalState, error.getMessage());
                recordPhase(terminalState, terminalStartMs);
                handleError(terminalState, asException(error), callerFuture);
                return;
            }
            if (onTerminalReached != null) {
                try {
                    long terminalEndMs = System.currentTimeMillis();
                    onTerminalReached.apply(terminalState).whenComplete((v, e) -> post(() -> {
                        recordPhase(terminalState, terminalStartMs, terminalEndMs);
                        if (e != null) {
                            logger.warn("[{}] onTerminalReached callback failed for {}", id, terminalState, e);
                        }
                        settle(callerFuture, terminalState);
                    }));
                } catch (Exception e) {
                    recordPhase(terminalState, terminalStartMs);
                    logger.warn("[{}] onTerminalReached callback threw synchronously for {}", id, terminalState, e);
                    settle(callerFuture, terminalState);
                }
            } else {
                recordPhase(terminalState, terminalStartMs);
                settle(callerFuture, terminalState);
            }
        }));
    }

    // =====================================================================
    // Helpers — small methods that do one thing
    // =====================================================================

    /** Record a phase with endTime = now. */
    private void recordPhase(S phase, long startMs) {
        synchronized (transitionHistory) {
            transitionHistory.add(new TransitionRecord<>(phase, startMs, System.currentTimeMillis()));
        }
    }

    /** Record a phase with an explicit end time (captured off-thread, recorded on executor). */
    private void recordPhase(S phase, long startMs, long endMs) {
        synchronized (transitionHistory) {
            transitionHistory.add(new TransitionRecord<>(phase, startMs, endMs));
        }
    }

    private boolean isStale(long atEpoch) {
        return closed || epoch != atEpoch;
    }

    /** Check if a transition from→to is in the permit table. */
    private boolean isPermitted(S from, S to) {
        EnumSet<S> permitted = validTransitions.get(from);
        return permitted != null && permitted.contains(to);
    }

    /** Cancel any in-flight handler and settle any pending caller future. */
    private void cancelInFlight() {
        if (inFlightFuture != null) {
            FutureUtils.cancel(inFlightFuture);
            inFlightFuture = null;
        }
        if (pendingResult != null) {
            pendingResult.complete(currentState);
            pendingResult = null;
        }
    }

    /** Settle a caller's future with a successful result (null-safe). */
    private void settle(CompletableFuture<S> future, S state) {
        if (future != null) future.complete(state);
    }

    /** Report an error: invoke the failure handler and fail the caller's future. */
    private void handleError(S state, Exception ex, CompletableFuture<S> callerFuture) {
        logger.warn("[{}] Error in state {}: {}", id, state, ex.getMessage());
        try {
            failureHandler.accept(new AwaitableFailureContext<>(state, ex, this));
        } catch (Exception e) {
            logger.error("[{}] Failure handler itself threw", id, e);
        }
        if (callerFuture != null) callerFuture.completeExceptionally(ex);
    }

    /** Run the write barrier (null-safe, exception-safe). */
    private CompletableFuture<Void> runBarrier(S oldState, S newState) {
        if (writeBarrier == null) return CompletableFuture.completedFuture(null);
        try {
            CompletableFuture<Void> r = writeBarrier.apply(oldState, newState);
            return r != null ? r : CompletableFuture.completedFuture(null);
        } catch (Exception e) {
            return CompletableFuture.failedFuture(e);
        }
    }

    /** Convert a Throwable to Exception, re-throwing Errors. */
    private static Exception asException(Throwable t) {
        if (t instanceof Error) throw (Error) t;
        return t instanceof Exception ? (Exception) t : new RuntimeException(t);
    }

    // =====================================================================
    // Executor posting — safe wrappers that handle shutdown
    // =====================================================================

    /** Post a task to the executor. Silently drops if executor is shut down. */
    private void post(Runnable task) {
        try {
            executor.execute(task);
        } catch (RejectedExecutionException e) {
            logger.warn("[{}] Executor rejected task (shutting down)", id);
        }
    }

    /** Post a task to the executor. If rejected, fail the given future. */
    private void postOrFail(Runnable task, CompletableFuture<?> toSettle) {
        try {
            executor.execute(task);
        } catch (RejectedExecutionException e) {
            logger.warn("[{}] Executor rejected task (shutting down)", id);
            if (toSettle != null && !toSettle.isDone()) {
                toSettle.completeExceptionally(new IllegalStateException("SM executor shut down", e));
            }
        }
    }

    // =====================================================================
    // Context — per-invocation snapshot passed to handlers
    // =====================================================================

    private static final class ContextSnapshot<S> implements Context<S> {
        private final boolean isResumeFlag;
        private final S previousStateRef;
        private final Map<String, Object> bag;

        ContextSnapshot(boolean isResume, S previousState, Map<String, Object> bag) {
            this.isResumeFlag = isResume;
            this.previousStateRef = previousState;
            this.bag = bag;
        }

        @Override
        public boolean isResume() {
            return isResumeFlag;
        }

        @Override
        public S previousState() {
            return previousStateRef;
        }

        @Override
        @SuppressWarnings("unchecked")
        public <T> T get(String key, Class<T> type) {
            Object v = bag.get(key);
            return v == null ? null : type.cast(v);
        }

        @Override
        public void set(String key, Object value) {
            if (value == null) bag.remove(key);
            else bag.put(key, value);
        }
    }

    // =====================================================================
    // Builder
    // =====================================================================

    public static class Builder<S extends Enum<S>> {
        private final String name;
        private final Executor executor;
        private final S initialState;
        private final Map<S, Function<Context<S>, CompletableFuture<S>>> handlers = new HashMap<>();
        private final Map<S, EnumSet<S>> validTransitions = new HashMap<>();
        private final Set<S> terminalStates = new HashSet<>();
        private BiFunction<S, S, CompletableFuture<Void>> writeBarrier;
        private Consumer<AwaitableFailureContext<S>> failureHandler;
        private Function<S, CompletableFuture<Void>> onTerminalReached;
        private final List<PermitFromAnyExcept<S>> deferredAnyExcept = new ArrayList<>();

        private Builder(String name, Executor executor, S initialState) {
            this.name = Objects.requireNonNull(name);
            this.executor = Objects.requireNonNull(executor, "executor");
            this.initialState = Objects.requireNonNull(initialState);
        }

        /** Register a handler for a state. Handler returns a future of the next state. */
        public Builder<S> handler(S state, Function<Context<S>, CompletableFuture<S>> handler) {
            handlers.put(state, Objects.requireNonNull(handler));
            return this;
        }

        /** Allow transition from → to. */
        public Builder<S> permit(S from, S to) {
            validTransitions.computeIfAbsent(from, k -> EnumSet.noneOf(declaringClass(from))).add(to);
            return this;
        }

        /** Allow transition to target from any state except the listed ones. */
        public Builder<S> permitFromAnyExcept(S targetState, Set<S> except) {
            deferredAnyExcept.add(new PermitFromAnyExcept<>(targetState, except));
            return this;
        }

        /** Mark a state as terminal (SM stops advancing when reached). */
        public Builder<S> terminal(S state) {
            terminalStates.add(state);
            return this;
        }

        /** Set the write barrier — called between transitions to persist state durably. */
        public Builder<S> writeBarrier(BiFunction<S, S, CompletableFuture<Void>> barrier) {
            this.writeBarrier = barrier;
            return this;
        }

        /**
         * Set the terminal callback — called when a terminal state is reached, after the write barrier.
         * The returned future must complete before the SM settles the caller's future.
         */
        public Builder<S> onTerminalReached(Function<S, CompletableFuture<Void>> callback) {
            this.onTerminalReached = callback;
            return this;
        }

        public Builder<S> onFailure(Consumer<AwaitableFailureContext<S>> handler) {
            this.failureHandler = handler;
            return this;
        }

        @SuppressWarnings("unchecked")
        public AwaitableStateMachine<S> build() {
            if (failureHandler == null) throw new IllegalStateException("onFailure handler is required");
            if (terminalStates.isEmpty()) throw new IllegalStateException("At least one terminal state is required");

            // Expand permitFromAnyExcept now that we know all enum constants
            Class<S> enumCls = initialState.getDeclaringClass();
            for (PermitFromAnyExcept<S> p : deferredAnyExcept) {
                for (S from : enumCls.getEnumConstants()) {
                    if (p.except.contains(from) || from == p.target) continue;
                    validTransitions.computeIfAbsent(from, k -> EnumSet.noneOf(enumCls)).add(p.target);
                }
            }

            return new AwaitableStateMachine<>(
                name,
                executor,
                initialState,
                Collections.unmodifiableMap(handlers),
                Collections.unmodifiableMap(validTransitions),
                Collections.unmodifiableSet(EnumSet.copyOf(terminalStates)),
                writeBarrier,
                failureHandler,
                onTerminalReached
            );
        }

        @SuppressWarnings("unchecked")
        private static <S extends Enum<S>> Class<S> declaringClass(S state) {
            return state.getDeclaringClass();
        }
    }

    private static final class PermitFromAnyExcept<S extends Enum<S>> {
        final S target;
        final Set<S> except;

        PermitFromAnyExcept(S target, Set<S> except) {
            this.target = target;
            this.except = except;
        }
    }
}
