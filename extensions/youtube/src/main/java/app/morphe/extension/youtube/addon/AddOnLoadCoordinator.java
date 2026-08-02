/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.youtube.addon;

import java.util.function.Consumer;

/**
 * Any-thread-safe loader for injected add-on registration code.
 *
 * <p>The concrete Android dispatcher lives in {@link AddOnManager}; this small core keeps the
 * loading state and its edge cases independently testable on the JVM.
 */
final class AddOnLoadCoordinator {

    enum State {
        NOT_LOADED,
        LOADING,
        LOADED
    }

    interface MainThreadDispatcher {
        boolean isOnMainThread();

        void verifyOnMainThread();

        void runOnMainThread(Runnable action);
    }

    interface RegistrationAction {
        void run() throws Throwable;
    }

    private final MainThreadDispatcher mainThreadDispatcher;
    private final RegistrationAction registrationAction;
    private final Consumer<Throwable> failureReporter;

    private volatile State state = State.NOT_LOADED;
    /** Guarded by this instance. */
    private boolean mainThreadAttemptQueued;

    AddOnLoadCoordinator(MainThreadDispatcher mainThreadDispatcher,
                         RegistrationAction registrationAction,
                         Consumer<Throwable> failureReporter) {
        this.mainThreadDispatcher = mainThreadDispatcher;
        this.registrationAction = registrationAction;
        this.failureReporter = failureReporter;
    }

    /**
     * Ensures a single registration attempt. An off-main caller only queues a main-thread
     * attempt; it never changes the loading state itself.
     */
    void ensureLoaded() {
        if (state != State.NOT_LOADED) return;

        if (mainThreadDispatcher.isOnMainThread()) {
            loadOnMainThread();
            return;
        }

        synchronized (this) {
            if (state != State.NOT_LOADED || mainThreadAttemptQueued) return;
            mainThreadAttemptQueued = true;
        }

        try {
            mainThreadDispatcher.runOnMainThread(this::ensureLoaded);
        } catch (Throwable failure) {
            synchronized (this) {
                mainThreadAttemptQueued = false;
            }
            reportFailure(failure);
        }
    }

    private void loadOnMainThread() {
        mainThreadDispatcher.verifyOnMainThread();

        synchronized (this) {
            // A reentrant call from registerAddOns sees LOADING and returns without dispatching
            // a second registration attempt. A queued off-main attempt that arrives after this
            // synchronous load similarly sees LOADED and becomes a no-op.
            if (state != State.NOT_LOADED) return;
            mainThreadAttemptQueued = false;
            state = State.LOADING;
        }

        try {
            registrationAction.run();
        } catch (Throwable failure) {
            // A missing add-on class is an Error, so this intentionally catches Throwable.
            reportFailure(failure);
        } finally {
            synchronized (this) {
                // Loading completes even after a registration failure. Repeating injected
                // registration could otherwise duplicate listeners or enter an endless loop.
                state = State.LOADED;
                mainThreadAttemptQueued = false;
            }
        }
    }

    State getStateForTesting() {
        return state;
    }

    private void reportFailure(Throwable failure) {
        try {
            failureReporter.accept(failure);
        } catch (Throwable ignored) {
            // Error reporting must not change loader state.
        }
    }
}
