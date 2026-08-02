/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.youtube.addon;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import java.util.regex.Pattern;

/**
 * Main-thread-confined state machine behind {@link AddOnApi}'s voice-over engine contract.
 *
 * <p>The active id is volatile because add-ons may inspect it from a worker thread. All
 * mutations are deliberately performed by {@link AddOnApi} only after its main-thread check.
 * Keeping this class free of Android dependencies makes the transition semantics unit-testable.
 */
final class VoiceOverEngineCoordinator {

    static final String OFFICIAL_ENGINE_ID = "official";
    static final String YANDEX_ENGINE_ID = "yandex";

    private static final Pattern ENGINE_ID_PATTERN =
            Pattern.compile("[a-z0-9][a-z0-9._-]*");

    private final Map<String, Runnable> stopActionByEngineId = new LinkedHashMap<>();
    private final CopyOnWriteArrayList<Consumer<String>> listeners = new CopyOnWriteArrayList<>();
    private final Consumer<Throwable> failureReporter;

    /** Visible to worker-thread callers through {@link #getActiveEngineId()}. */
    private volatile String activeEngineId;

    /**
     * A stop callback and its ownership notification are arbitrary add-on code. While either is
     * running, nested activate/deactivate/stop calls are rejected. This makes a transition
     * atomic from the coordinator's perspective: a failed A-to-B handoff cannot be replaced by
     * a listener that happens to run during its final inactive notification.
     */
    private boolean transitionInProgress;

    VoiceOverEngineCoordinator(Consumer<Throwable> failureReporter) {
        this.failureReporter = failureReporter;
    }

    boolean register(String engineId, Runnable stopAction) {
        // "official" is owned by the base extension. External add-ons only reach this general
        // registration path, so they cannot race the built-in engine during class loading.
        if (OFFICIAL_ENGINE_ID.equals(engineId)) return false;
        return registerTrusted(engineId, stopAction);
    }

    /** Base-only registration path for the one reserved built-in engine id. */
    boolean registerOfficial(Runnable stopAction) {
        return registerTrusted(OFFICIAL_ENGINE_ID, stopAction);
    }

    private boolean registerTrusted(String engineId, Runnable stopAction) {
        if (!isValidEngineId(engineId) || stopAction == null || stopActionByEngineId.containsKey(engineId)) {
            return false;
        }

        stopActionByEngineId.put(engineId, stopAction);
        return true;
    }

    boolean activate(String engineId) {
        if (!isValidEngineId(engineId) || !stopActionByEngineId.containsKey(engineId)) return false;
        if (transitionInProgress) return false;
        if (engineId.equals(activeEngineId)) return true;

        transitionInProgress = true;
        try {
            if (activeEngineId != null && !stopCurrentEngine(false)) {
                // The failed stop already made the previous owner inactive. Do not start the new
                // engine after a failed handoff; consumers receive exactly one final inactive state.
                notifyListeners(null);
                return false;
            }

            activeEngineId = engineId;
            notifyListeners(engineId);
            return true;
        } finally {
            transitionInProgress = false;
        }
    }

    boolean deactivate(String engineId) {
        if (!isValidEngineId(engineId) || !stopActionByEngineId.containsKey(engineId)) return false;
        if (transitionInProgress) return false;
        // A valid, registered engine may be stopped repeatedly. Once it is already inactive,
        // no callback or notification is needed, but the request is still a successful no-op.
        if (!engineId.equals(activeEngineId)) return true;

        transitionInProgress = true;
        try {
            return stopCurrentEngine(true);
        } finally {
            transitionInProgress = false;
        }
    }

    boolean stopActive() {
        if (transitionInProgress) return false;
        // Mirror deactivate(engineId)'s idempotency for callers that do not know the owner.
        if (activeEngineId == null) return true;

        transitionInProgress = true;
        try {
            return stopCurrentEngine(true);
        } finally {
            transitionInProgress = false;
        }
    }

    String getActiveEngineId() {
        return activeEngineId;
    }

    void addListener(Consumer<String> listener) {
        if (listener != null) listeners.add(listener);
    }

    void removeListener(Consumer<String> listener) {
        if (listener != null) listeners.remove(listener);
    }

    static boolean isValidEngineId(String engineId) {
        return engineId != null
                && engineId.length() >= 1
                && engineId.length() <= 64
                && !"none".equals(engineId)
                && !"__none__".equals(engineId)
                && ENGINE_ID_PATTERN.matcher(engineId).matches();
    }

    /**
     * Clears ownership before calling the stop action. A direct stop emits the final null state;
     * a successful handoff suppresses that intermediate state and emits only the new owner.
     */
    private boolean stopCurrentEngine(boolean notifyInactive) {
        final String engineId = activeEngineId;
        final Runnable stopAction = stopActionByEngineId.get(engineId);

        // activeEngineId is cleared first so a callback can never observe itself as still owning
        // the voice-over channel. The public mutation entry points reject nested transitions until
        // the corresponding state notification completes.
        activeEngineId = null;

        boolean success = true;
        try {
            // A registered action is never null; keep this guard for resilience to malformed
            // bytecode injected by an incompatible third-party bundle.
            if (stopAction != null) stopAction.run();
        } catch (Throwable failure) {
            success = false;
            reportFailure(failure);
        }

        if (notifyInactive) notifyListeners(null);
        return success;
    }

    private void notifyListeners(String engineId) {
        for (Consumer<String> listener : listeners) {
            try {
                listener.accept(engineId);
            } catch (Throwable failure) {
                // One add-on must not suppress notification of later add-ons.
                reportFailure(failure);
            }
        }
    }

    private void reportFailure(Throwable failure) {
        try {
            failureReporter.accept(failure);
        } catch (Throwable ignored) {
            // Logging must never change the coordinator state machine.
        }
    }
}
