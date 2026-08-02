/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.youtube.addon;

import android.view.View;

import androidx.annotation.Nullable;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import java.util.function.LongConsumer;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.settings.BooleanSetting;
import app.morphe.extension.youtube.patches.VideoInformation;
import app.morphe.extension.youtube.shared.VideoState;
import app.morphe.extension.youtube.videoplayer.LegacyPlayerControlButton;
import kotlin.Unit;

/**
 * Hooks that add-on patch bundles can attach to at runtime.
 * <p>
 * Add-on bundles cannot depend on Morphe patch code at patch time, because the patcher loads
 * every bundle in its own class loader. Instead, the "Add-on support" patch injects the calls
 * to the injection points below, and an add-on subscribes to them from the registration method
 * it added to {@link AddOnManager#registerAddOns()}.
 * <p>
 * All listeners are called on the same thread as the hook that fires them, which for every hook
 * declared here is the main thread. An exception thrown by a listener is logged and does not
 * prevent the remaining listeners from being called.
 * <p>
 * Every listener declared here uses a functional interface of the Android platform, so that an
 * add-on can use a Java lambda for it. Add-ons must not use a Java lambda for the Kotlin event
 * classes of the extension, such as {@code VideoState.getOnChange()}: the interface of those
 * events is not on the dex classpath of an add-on bundle, so the lambda is compiled into a class
 * that does not implement the interface, which throws {@link AbstractMethodError} when the event
 * fires. That is why the events an add-on commonly needs are also declared here.
 * <p>
 * This class is a public contract. Existing method signatures must not be changed.
 */
@SuppressWarnings("unused")
public final class AddOnApi {

    /**
     * Version of this binary add-on contract. Add-ons must check this before using APIs added
     * after the original add-on-support patch.
     */
    public static final int API_VERSION = 1;

    /**
     * Legacy player control button slots added to the bottom controls layout
     * by the "Add-on support" patch. Each add-on claims one slot for the lifetime of the app.
     */
    private static final String[] LEGACY_BUTTON_SLOTS = {
            "morphe_addon_button_1",
            "morphe_addon_button_2",
            "morphe_addon_button_3",
            "morphe_addon_button_4",
    };

    private static final Map<String, String> legacyButtonSlotByAddOnId = new LinkedHashMap<>();

    private static final List<Consumer<View>> playerOverlayButtonsListeners = new CopyOnWriteArrayList<>();
    private static final List<Consumer<View>> legacyPlayerControlsListeners = new CopyOnWriteArrayList<>();
    private static final List<Runnable> newVideoStartedListeners = new CopyOnWriteArrayList<>();
    private static final List<Consumer<String>> videoIdListeners = new CopyOnWriteArrayList<>();
    private static final List<LongConsumer> videoTimeListeners = new CopyOnWriteArrayList<>();
    private static final List<Consumer<VideoState>> videoStateListeners = new CopyOnWriteArrayList<>();

    private static final VoiceOverEngineCoordinator voiceOverEngineCoordinator =
            new VoiceOverEngineCoordinator(failure -> Logger.printException(
                    () -> "Voice-over engine callback failure", failure));

    /**
     * Whether the video state event of the extension is observed.
     * The observer is added with the first listener, so the event is not observed if no add-on uses it.
     */
    private static boolean videoStateObserverAdded;

    //
    // Add-on API.
    //

    /**
     * Registers a voice-over engine that participates in exclusive voice-over ownership.
     *
     * <p>Engine ids use lowercase ASCII {@code [a-z0-9][a-z0-9._-]*}, have 1-64 characters,
     * and may not be {@code none} or {@code __none__}. Registration is permanent for the app
     * lifetime, so an id can be registered only once.
     *
     * <p>The built-in {@code official} id is reserved and is rejected by this public entry point.
     *
     * @param engineId stable id for an external engine, such as {@code yandex}
     * @param stopAction action that immediately stops this engine and releases its audio state
     * @return {@code true} only when a new valid engine was registered
     */
    public static boolean registerVoiceOverEngine(String engineId, Runnable stopAction) {
        Utils.verifyOnMainThread();
        return voiceOverEngineCoordinator.register(engineId, stopAction);
    }

    /**
     * Registers the base extension's reserved voice-over engine.
     *
     * <p>This is deliberately package-private: {@link AddOnManager}'s loader action calls it
     * before it invokes the bytecode-injected {@link AddOnManager#registerAddOns()} body.
     * Third-party add-ons can only use the public registration method above.
     */
    static boolean registerOfficialVoiceOverEngine(Runnable stopAction) {
        Utils.verifyOnMainThread();
        return voiceOverEngineCoordinator.registerOfficial(stopAction);
    }

    /**
     * Activates a registered voice-over engine, first stopping any previous owner.
     *
     * <p>A successful handoff notifies listeners only of the new id. If the outgoing stop action
     * fails, the coordinator remains inactive, notifies {@code null}, and this method returns
     * {@code false}; the requested engine is not activated.
     */
    public static boolean activateVoiceOverEngine(String engineId) {
        Utils.verifyOnMainThread();
        return voiceOverEngineCoordinator.activate(engineId);
    }

    /**
     * Stops the given engine when it is currently active.
     *
     * <p>Ownership is cleared before the engine callback runs, and listeners receive one final
     * {@code null} notification even when the callback fails. Stopping a valid registered engine
     * that is already inactive succeeds without a callback or notification.
     */
    public static boolean deactivateVoiceOverEngine(String engineId) {
        Utils.verifyOnMainThread();
        return voiceOverEngineCoordinator.deactivate(engineId);
    }

    /**
     * Stops whichever voice-over engine currently owns the channel.
     *
     * @return {@code true} when no engine is active or its stop callback succeeds; {@code false}
     * when a stop callback fails or a transition callback re-enters the coordinator
     */
    public static boolean stopActiveVoiceOverEngine() {
        Utils.verifyOnMainThread();
        return voiceOverEngineCoordinator.stopActive();
    }

    /**
     * Returns the active engine id, or {@code null} when no voice-over engine owns the channel.
     * This getter is safe to call from any thread.
     */
    @Nullable
    public static String getActiveVoiceOverEngineId() {
        return voiceOverEngineCoordinator.getActiveEngineId();
    }

    /** Adds a main-thread listener notified in registration order whenever ownership changes. */
    public static void addVoiceOverEngineListener(Consumer<String> listener) {
        Utils.verifyOnMainThread();
        voiceOverEngineCoordinator.addListener(listener);
    }

    /** Removes a previously added voice-over ownership listener. */
    public static void removeVoiceOverEngineListener(Consumer<String> listener) {
        Utils.verifyOnMainThread();
        voiceOverEngineCoordinator.removeListener(listener);
    }

    /**
     * Adds a listener that is called when the player overlay buttons are created.
     * The parameter is the view of an existing player button, which can be passed to
     * {@link app.morphe.extension.youtube.videoplayer.PlayerOverlayButton#addButton}.
     */
    public static void addPlayerOverlayButtonsListener(Consumer<View> listener) {
        playerOverlayButtonsListeners.add(listener);
    }

    /**
     * Adds a listener that is called when the legacy (old style) player controls are inflated.
     * The parameter is the controls view group, which can be passed to {@link #createLegacyButton}.
     */
    public static void addLegacyPlayerControlsListener(Consumer<View> listener) {
        legacyPlayerControlsListeners.add(listener);
    }

    /**
     * Adds a listener that is called when a video is opened or the current video is changed.
     * <p>
     * The listener is called very early, before the video id, video time, video length
     * and most other {@link VideoInformation} fields are set.
     */
    public static void addNewVideoStartedListener(Runnable listener) {
        newVideoStartedListeners.add(listener);
    }

    /**
     * Adds a listener that is called when the video id of the current video changes.
     * Supports regular videos and Shorts, and can be called more than once for the same video id.
     */
    public static void addVideoIdListener(Consumer<String> listener) {
        videoIdListeners.add(listener);
    }

    /**
     * Adds a listener that is called when the playback state of the video changes.
     */
    public static void addVideoStateListener(Consumer<VideoState> listener) {
        if (!videoStateObserverAdded) {
            videoStateObserverAdded = true;

            VideoState.getOnChange().addObserver(videoState -> {
                for (Consumer<VideoState> videoStateListener : videoStateListeners) {
                    try {
                        videoStateListener.accept(videoState);
                    } catch (Exception ex) {
                        Logger.printException(() -> "Video state listener failure", ex);
                    }
                }
                return Unit.INSTANCE;
            });
        }

        videoStateListeners.add(listener);
    }

    /**
     * Adds a listener that is called with the current playback time, usually once per second.
     */
    public static void addVideoTimeListener(LongConsumer listener) {
        videoTimeListeners.add(listener);
    }

    /**
     * Creates a button in the legacy (old style) player controls, using one of the button slots
     * the "Add-on support" patch adds to the bottom controls layout.
     * <p>
     * The same {@code addOnId} always uses the same slot, and the number of slots is limited.
     * Call this from a {@link #addLegacyPlayerControlsListener} listener.
     * <p>
     * The drawable is resolved the same way as for the built-in legacy buttons, which means a
     * {@code _bold} variant of the drawable is used unless the user restored the old player buttons.
     *
     * @param addOnId       Identifier of the add-on, such as its patch name. Must be stable.
     * @param controlsView  The view passed to the legacy player controls listener.
     * @param drawableName  Resource name of the button drawable, or null to keep the slot icon.
     * @return The created button, or null if no slot is available or the slots are not patched in.
     */
    @Nullable
    public static LegacyPlayerControlButton createLegacyButton(String addOnId,
                                                               View controlsView,
                                                               @Nullable String drawableName,
                                                               BooleanSetting setting,
                                                               View.OnClickListener onClickListener,
                                                               @Nullable View.OnLongClickListener onLongClickListener) {
        try {
            String slotId = legacyButtonSlotByAddOnId.get(addOnId);
            if (slotId == null) {
                final int slotIndex = legacyButtonSlotByAddOnId.size();
                if (slotIndex >= LEGACY_BUTTON_SLOTS.length) {
                    Logger.printException(() -> "No legacy player button slot is left for add-on: " + addOnId);
                    return null;
                }
                slotId = LEGACY_BUTTON_SLOTS[slotIndex];
                legacyButtonSlotByAddOnId.put(addOnId, slotId);
            }

            return new LegacyPlayerControlButton(
                    controlsView,
                    slotId,
                    null,
                    drawableName,
                    setting,
                    onClickListener,
                    onLongClickListener
            );
        } catch (Exception ex) {
            Logger.printException(() -> "createLegacyButton failure for add-on: " + addOnId, ex);
            return null;
        }
    }

    //
    // Injection points.
    //

    /**
     * Injection point.
     */
    public static void initializeButton(View controlsView) {
        Utils.verifyOnMainThread();
        AddOnManager.ensureLoaded();
        for (Consumer<View> listener : playerOverlayButtonsListeners) {
            try {
                listener.accept(controlsView);
            } catch (Exception ex) {
                Logger.printException(() -> "Player overlay buttons listener failure", ex);
            }
        }
    }

    /**
     * Injection point.
     */
    public static void initializeLegacyButton(View controlsView) {
        Utils.verifyOnMainThread();
        AddOnManager.ensureLoaded();
        for (Consumer<View> listener : legacyPlayerControlsListeners) {
            try {
                listener.accept(controlsView);
            } catch (Exception ex) {
                Logger.printException(() -> "Legacy player controls listener failure", ex);
            }
        }
    }

    /**
     * Injection point.
     */
    public static void newVideoStarted(VideoInformation.PlaybackController ignoredPlayerController) {
        Utils.verifyOnMainThread();
        AddOnManager.ensureLoaded();
        for (Runnable listener : newVideoStartedListeners) {
            try {
                listener.run();
            } catch (Exception ex) {
                Logger.printException(() -> "New video started listener failure", ex);
            }
        }
    }

    /**
     * Injection point.
     */
    public static void videoIdChanged(String videoId) {
        Utils.verifyOnMainThread();
        AddOnManager.ensureLoaded();
        for (Consumer<String> listener : videoIdListeners) {
            try {
                listener.accept(videoId);
            } catch (Exception ex) {
                Logger.printException(() -> "Video id listener failure", ex);
            }
        }
    }

    /**
     * Injection point.
     */
    public static void videoTimeChanged(long videoTime) {
        Utils.verifyOnMainThread();
        AddOnManager.ensureLoaded();
        for (LongConsumer listener : videoTimeListeners) {
            try {
                listener.accept(videoTime);
            } catch (Exception ex) {
                Logger.printException(() -> "Video time listener failure", ex);
            }
        }
    }

    private AddOnApi() {
    }
}
