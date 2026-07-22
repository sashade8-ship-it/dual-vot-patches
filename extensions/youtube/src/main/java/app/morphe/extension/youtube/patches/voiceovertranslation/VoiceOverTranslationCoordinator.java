/*
 * Copyright 2026 Dual VoT Patches contributors.
 *
 * Licensed under the GNU General Public License v3.0.
 */

package app.morphe.extension.youtube.patches.voiceovertranslation;

import androidx.annotation.Nullable;

import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;

import app.morphe.extension.shared.Utils;
import app.morphe.extension.youtube.patches.VideoInformation;
import app.morphe.extension.youtube.patches.voiceovertranslation.yandex.YandexVoiceOverTranslationPatch;
import app.morphe.extension.youtube.settings.Settings;

/**
 * Single owner of voice-over playback. Both engines may be enabled in Settings,
 * but only one is allowed to produce translated audio at a time.
 */
@SuppressWarnings("unused")
public final class VoiceOverTranslationCoordinator {
    public enum Engine {
        NONE,
        OFFICIAL,
        YANDEX
    }

    private static Engine activeEngine = Engine.NONE;
    private static String currentVideoId = "";
    private static long currentVideoTimeMs;
    private static final Set<Runnable> stateChangeCallbacks = new CopyOnWriteArraySet<>();

    static {
        VoiceOverTranslationPatch.setOnTranslationStateChangeCallback(
                VoiceOverTranslationCoordinator::onOfficialStateChanged);
        YandexVoiceOverTranslationPatch.setOnTranslationStateChangeCallback(
                VoiceOverTranslationCoordinator::onYandexStateChanged);
    }

    private VoiceOverTranslationCoordinator() {}

    public static void initialize(VideoInformation.PlaybackController playbackController) {
        YandexVoiceOverTranslationPatch.initialize(playbackController);
    }

    public static void onVideoIdChanged(String videoId) {
        currentVideoId = videoId;
        // The Yandex engine needs the id even while inactive so it can start from
        // the current video immediately after its button is pressed.
        YandexVoiceOverTranslationPatch.onVideoIdChanged(videoId);
        if (activeEngine == Engine.OFFICIAL) {
            VoiceOverTranslationPatch.newVideoLoaded(videoId);
        }
    }

    public static void onVideoTimeChanged(long timeMs) {
        currentVideoTimeMs = timeMs;
        // Maintain Yandex's pending video metadata without allowing it to play
        // unless it is the selected engine.
        YandexVoiceOverTranslationPatch.setVideoTime(timeMs);

        if (activeEngine == Engine.OFFICIAL) {
            VoiceOverTranslationPatch.videoTimeChanged(timeMs);
        } else if (activeEngine == Engine.NONE) {
            VotOriginalVolumePatch.clearAudioMultiplier();
        }
    }

    public static void toggleOfficial() {
        Utils.verifyOnMainThread();
        if (!Settings.VOT_ENABLED.get()) return;

        if (activeEngine == Engine.OFFICIAL) {
            deactivateOfficial();
            activeEngine = Engine.NONE;
        } else {
            deactivateYandex();
            activeEngine = Engine.OFFICIAL;
            if (!VoiceOverTranslationPatch.isSessionEnabled()) {
                VoiceOverTranslationPatch.toggleTranslation();
            }
            if (!currentVideoId.isEmpty()) {
                VoiceOverTranslationPatch.newVideoLoaded(currentVideoId);
                VoiceOverTranslationPatch.videoTimeChanged(currentVideoTimeMs);
            }
        }
        notifyStateChanged();
    }

    public static void toggleYandex() {
        Utils.verifyOnMainThread();
        if (!Settings.DUAL_VOT_YANDEX_ENABLED.get()) return;

        if (activeEngine == Engine.YANDEX) {
            deactivateYandex();
            activeEngine = Engine.NONE;
        } else {
            deactivateOfficial();
            activeEngine = Engine.YANDEX;
            if (!currentVideoId.isEmpty()) {
                YandexVoiceOverTranslationPatch.onVideoIdChanged(currentVideoId);
                YandexVoiceOverTranslationPatch.setVideoTime(currentVideoTimeMs);
            }
            YandexVoiceOverTranslationPatch.toggleTranslation();
            if (!YandexVoiceOverTranslationPatch.isTranslationActive()
                    && !YandexVoiceOverTranslationPatch.translationStarting) {
                activeEngine = Engine.NONE;
            }
        }
        notifyStateChanged();
    }

    public static void stopAll() {
        Utils.verifyOnMainThread();
        deactivateOfficial();
        deactivateYandex();
        activeEngine = Engine.NONE;
        VotOriginalVolumePatch.clearAudioMultiplier();
        notifyStateChanged();
    }

    public static boolean isOfficialActive() {
        return activeEngine == Engine.OFFICIAL && VoiceOverTranslationPatch.isSessionEnabled();
    }

    public static boolean isYandexActive() {
        return activeEngine == Engine.YANDEX
                && (YandexVoiceOverTranslationPatch.isTranslationActive()
                || YandexVoiceOverTranslationPatch.translationStarting);
    }

    public static Engine getActiveEngine() {
        return activeEngine;
    }

    /**
     * Registers a player-control refresh callback. There are two independent
     * buttons, so this deliberately accumulates callbacks instead of allowing
     * one button to replace the other one.
     */
    public static void addOnStateChangeCallback(@Nullable Runnable callback) {
        if (callback != null) stateChangeCallbacks.add(callback);
    }

    private static void deactivateOfficial() {
        if (VoiceOverTranslationPatch.isSessionEnabled()) {
            VoiceOverTranslationPatch.toggleTranslation();
        } else {
            VoiceOverTranslationPatch.interruptSpeech();
        }
        VotOriginalVolumePatch.clearAudioMultiplier();
    }

    private static void deactivateYandex() {
        YandexVoiceOverTranslationPatch.cancelTranslation();
        VotOriginalVolumePatch.clearAudioMultiplier();
    }

    private static void onOfficialStateChanged() {
        if (activeEngine == Engine.OFFICIAL && !VoiceOverTranslationPatch.isSessionEnabled()) {
            activeEngine = Engine.NONE;
        }
        notifyStateChanged();
    }

    private static void onYandexStateChanged() {
        if (activeEngine == Engine.YANDEX
                && !YandexVoiceOverTranslationPatch.isTranslationActive()
                && !YandexVoiceOverTranslationPatch.translationStarting) {
            activeEngine = Engine.NONE;
        }
        notifyStateChanged();
    }

    private static void notifyStateChanged() {
        for (Runnable callback : stateChangeCallbacks) {
            callback.run();
        }
    }
}
