/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.youtube.addon;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

public class VoiceOverEngineCoordinatorTest {

    @Test
    public void rejectsInvalidDuplicateAndNullRegistrationsWithoutChangingState() {
        VoiceOverEngineCoordinator coordinator = new VoiceOverEngineCoordinator(failure -> {});

        assertFalse(coordinator.register(null, () -> {}));
        assertFalse(coordinator.register("", () -> {}));
        assertFalse(coordinator.register("Upper", () -> {}));
        assertFalse(coordinator.register(".starts-with-dot", () -> {}));
        assertFalse(coordinator.register("none", () -> {}));
        assertFalse(coordinator.register("__none__", () -> {}));
        assertFalse(coordinator.register(repeat('a', 65), () -> {}));
        assertFalse(coordinator.register("official", null));
        assertFalse(coordinator.register("official", () -> {}));
        assertNull(coordinator.getActiveEngineId());

        assertTrue(coordinator.registerOfficial(() -> {}));
        assertTrue(coordinator.register("yandex", () -> {}));
        assertFalse(coordinator.register("official", () -> {}));
        assertFalse(coordinator.deactivate("not_registered"));
        assertTrue(coordinator.activate("official"));
        assertEquals("official", coordinator.getActiveEngineId());
        assertFalse(coordinator.activate("not_registered"));
        assertEquals("official", coordinator.getActiveEngineId());
    }

    @Test
    public void handsOffOfficialAndYandexWithoutIntermediateInactiveNotification() {
        List<String> stops = new ArrayList<>();
        List<String> notifications = new ArrayList<>();
        VoiceOverEngineCoordinator coordinator = new VoiceOverEngineCoordinator(failure -> {});
        assertFalse(coordinator.register("official", () -> stops.add("untrusted-official")));
        assertTrue(coordinator.registerOfficial(() -> stops.add("official")));
        assertFalse(coordinator.registerOfficial(() -> stops.add("duplicate-official")));
        assertTrue(coordinator.register("yandex", () -> stops.add("yandex")));
        coordinator.addListener(notifications::add);

        assertTrue(coordinator.activate("official"));
        assertTrue(coordinator.activate("yandex"));
        assertEquals("yandex", coordinator.getActiveEngineId());
        assertEquals(Arrays.asList("official"), stops);
        assertEquals(Arrays.asList("official", "yandex"), notifications);

        assertTrue(coordinator.activate("official"));
        assertEquals("official", coordinator.getActiveEngineId());
        assertEquals(Arrays.asList("official", "yandex"), stops);
        assertEquals(Arrays.asList("official", "yandex", "official"), notifications);
    }

    @Test
    public void repeatedActivationIsAnIdempotentSuccess() {
        AtomicInteger stopCalls = new AtomicInteger();
        List<String> notifications = new ArrayList<>();
        VoiceOverEngineCoordinator coordinator = new VoiceOverEngineCoordinator(failure -> {});
        coordinator.registerOfficial(stopCalls::incrementAndGet);
        coordinator.addListener(notifications::add);

        assertTrue(coordinator.activate("official"));
        assertTrue(coordinator.activate("official"));

        assertEquals(0, stopCalls.get());
        assertEquals(Arrays.asList("official"), notifications);
    }

    @Test
    public void reentrantActivationOfTheCurrentEngineIsRejectedDuringNotification() {
        AtomicBoolean nestedActivationResult = new AtomicBoolean(true);
        VoiceOverEngineCoordinator coordinator = new VoiceOverEngineCoordinator(failure -> {});
        coordinator.registerOfficial(() -> {});
        coordinator.addListener(engineId -> {
            if ("official".equals(engineId)) {
                nestedActivationResult.set(coordinator.activate("official"));
            }
        });

        assertTrue(coordinator.activate("official"));

        assertFalse(nestedActivationResult.get());
        assertEquals("official", coordinator.getActiveEngineId());
    }

    @Test
    public void deactivateAndStopClearBeforeOneFinalInactiveNotification() {
        AtomicInteger officialStops = new AtomicInteger();
        AtomicInteger yandexStops = new AtomicInteger();
        List<String> notifications = new ArrayList<>();
        VoiceOverEngineCoordinator coordinator = new VoiceOverEngineCoordinator(failure -> {});
        coordinator.registerOfficial(officialStops::incrementAndGet);
        coordinator.register("yandex", yandexStops::incrementAndGet);
        coordinator.addListener(notifications::add);

        assertTrue(coordinator.stopActive());
        assertTrue(coordinator.deactivate("yandex"));
        assertTrue(notifications.isEmpty());

        assertTrue(coordinator.activate("official"));
        assertTrue(coordinator.deactivate("yandex"));
        assertTrue(coordinator.deactivate("official"));
        assertNull(coordinator.getActiveEngineId());
        assertEquals(1, officialStops.get());
        assertEquals(Arrays.asList("official", null), notifications);

        assertTrue(coordinator.activate("yandex"));
        assertTrue(coordinator.stopActive());
        assertTrue(coordinator.stopActive());
        assertNull(coordinator.getActiveEngineId());
        assertEquals(1, yandexStops.get());
        assertEquals(Arrays.asList("official", null, "yandex", null), notifications);
    }

    @Test
    public void stopFailureLeavesCoordinatorInactiveAndDoesNotStartReplacement() {
        List<String> notifications = new ArrayList<>();
        AtomicInteger yandexStops = new AtomicInteger();
        VoiceOverEngineCoordinator coordinator = new VoiceOverEngineCoordinator(failure -> {});
        coordinator.registerOfficial(() -> {
            throw new IllegalStateException("expected stop failure");
        });
        coordinator.register("yandex", yandexStops::incrementAndGet);
        coordinator.addListener(notifications::add);

        assertTrue(coordinator.activate("official"));
        assertFalse(coordinator.activate("yandex"));

        assertNull(coordinator.getActiveEngineId());
        assertEquals(0, yandexStops.get());
        assertEquals(Arrays.asList("official", null), notifications);
    }

    @Test
    public void failedHandoffCannotBeReplacedByAReentrantInactiveListener() {
        AtomicBoolean listenerActivationResult = new AtomicBoolean(true);
        VoiceOverEngineCoordinator coordinator = new VoiceOverEngineCoordinator(failure -> {});
        coordinator.registerOfficial(() -> {
            throw new IllegalStateException("expected stop failure");
        });
        coordinator.register("yandex", () -> {});
        coordinator.addListener(engineId -> {
            if (engineId == null) {
                listenerActivationResult.set(coordinator.activate("yandex"));
            }
        });

        assertTrue(coordinator.activate("official"));
        assertFalse(coordinator.activate("yandex"));

        assertFalse(listenerActivationResult.get());
        assertNull(coordinator.getActiveEngineId());
    }

    @Test
    public void nestedStopDuringStopCallbackCannotRecursivelyStopOrReplaceOwner() {
        AtomicInteger officialStops = new AtomicInteger();
        AtomicBoolean nestedStopResult = new AtomicBoolean(true);
        AtomicBoolean nestedActivateResult = new AtomicBoolean(true);
        VoiceOverEngineCoordinator coordinator = new VoiceOverEngineCoordinator(failure -> {});
        coordinator.registerOfficial(() -> {
            officialStops.incrementAndGet();
            nestedStopResult.set(coordinator.stopActive());
            nestedActivateResult.set(coordinator.activate("official"));
        });
        coordinator.register("yandex", () -> {});

        assertTrue(coordinator.activate("official"));
        assertTrue(coordinator.activate("yandex"));

        assertEquals(1, officialStops.get());
        assertFalse(nestedStopResult.get());
        assertFalse(nestedActivateResult.get());
        assertEquals("yandex", coordinator.getActiveEngineId());
    }

    @Test
    public void listenerFailureIsIsolatedAndLaterListenersKeepRegistrationOrder() {
        AtomicInteger reportedFailures = new AtomicInteger();
        List<String> delivered = new ArrayList<>();
        VoiceOverEngineCoordinator coordinator = new VoiceOverEngineCoordinator(
                failure -> reportedFailures.incrementAndGet());
        coordinator.registerOfficial(() -> {});
        coordinator.addListener(engineId -> {
            throw new IllegalStateException("expected listener failure");
        });
        coordinator.addListener(delivered::add);

        assertTrue(coordinator.activate("official"));
        assertTrue(coordinator.deactivate("official"));

        assertEquals(Arrays.asList("official", null), delivered);
        assertEquals(2, reportedFailures.get());
    }

    @Test
    public void offMainLoadQueuesOneAttemptAndReentrantRegistrationLoadsOnlyOnce() {
        FakeMainThreadDispatcher dispatcher = new FakeMainThreadDispatcher();
        AtomicInteger registrations = new AtomicInteger();
        AtomicInteger reportedFailures = new AtomicInteger();
        AddOnLoadCoordinator[] holder = new AddOnLoadCoordinator[1];
        holder[0] = new AddOnLoadCoordinator(
                dispatcher,
                () -> {
                    registrations.incrementAndGet();
                    holder[0].ensureLoaded();
                },
                failure -> reportedFailures.incrementAndGet());

        holder[0].ensureLoaded();
        holder[0].ensureLoaded();
        assertEquals(AddOnLoadCoordinator.State.NOT_LOADED, holder[0].getStateForTesting());
        assertEquals(0, registrations.get());
        assertEquals(1, dispatcher.queuedActions.size());

        dispatcher.onMainThread = true;
        dispatcher.runQueuedActions();

        assertEquals(AddOnLoadCoordinator.State.LOADED, holder[0].getStateForTesting());
        assertEquals(1, registrations.get());
        assertEquals(1, dispatcher.verifyCalls.get());
        assertEquals(0, reportedFailures.get());
    }

    @Test
    public void loadActionReservesOfficialBeforeSimulatedInjectedRegistration() {
        FakeMainThreadDispatcher dispatcher = new FakeMainThreadDispatcher();
        dispatcher.onMainThread = true;
        List<String> registrationOrder = new ArrayList<>();
        VoiceOverEngineCoordinator coordinator = new VoiceOverEngineCoordinator(failure -> {});
        AddOnLoadCoordinator loader = new AddOnLoadCoordinator(
                dispatcher,
                () -> {
                    assertTrue(coordinator.registerOfficial(() -> {}));
                    registrationOrder.add("base-official");

                    // Add-on registration is injected at index zero inside registerAddOns().
                    assertFalse(coordinator.register("official", () -> {}));
                    assertTrue(coordinator.register("yandex", () -> {}));
                    registrationOrder.add("injected-add-on");
                },
                failure -> {
                    throw new AssertionError("unexpected load failure", failure);
                });

        loader.ensureLoaded();

        assertEquals(AddOnLoadCoordinator.State.LOADED, loader.getStateForTesting());
        assertEquals(Arrays.asList("base-official", "injected-add-on"), registrationOrder);
        assertTrue(coordinator.activate("official"));
        assertTrue(coordinator.activate("yandex"));
        assertEquals("yandex", coordinator.getActiveEngineId());
    }

    @Test
    public void failedLoadStillBecomesLoadedAfterItsSingleAttempt() {
        FakeMainThreadDispatcher dispatcher = new FakeMainThreadDispatcher();
        dispatcher.onMainThread = true;
        AtomicInteger registrations = new AtomicInteger();
        AtomicInteger reportedFailures = new AtomicInteger();
        AddOnLoadCoordinator coordinator = new AddOnLoadCoordinator(
                dispatcher,
                () -> {
                    registrations.incrementAndGet();
                    throw new AssertionError("missing add-on class");
                },
                failure -> reportedFailures.incrementAndGet());

        coordinator.ensureLoaded();
        coordinator.ensureLoaded();

        assertEquals(AddOnLoadCoordinator.State.LOADED, coordinator.getStateForTesting());
        assertEquals(1, registrations.get());
        assertEquals(1, reportedFailures.get());
    }

    private static String repeat(char value, int count) {
        char[] values = new char[count];
        Arrays.fill(values, value);
        return new String(values);
    }

    private static final class FakeMainThreadDispatcher
            implements AddOnLoadCoordinator.MainThreadDispatcher {
        private final List<Runnable> queuedActions = new ArrayList<>();
        private final AtomicInteger verifyCalls = new AtomicInteger();
        private boolean onMainThread;

        @Override
        public boolean isOnMainThread() {
            return onMainThread;
        }

        @Override
        public void verifyOnMainThread() {
            assertTrue(onMainThread);
            verifyCalls.incrementAndGet();
        }

        @Override
        public void runOnMainThread(Runnable action) {
            queuedActions.add(action);
        }

        private void runQueuedActions() {
            while (!queuedActions.isEmpty()) {
                queuedActions.remove(0).run();
            }
        }
    }
}
