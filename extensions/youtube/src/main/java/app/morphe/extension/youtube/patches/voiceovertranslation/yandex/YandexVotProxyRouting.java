/*
 * Copyright (C) 2026 Dual VoT contributors
 *
 * Licensed under the GNU General Public License v3.0.
 */

package app.morphe.extension.youtube.patches.voiceovertranslation.yandex;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/** Routes oversized audio uploads around workers that reject the legacy JSON envelope. */
final class YandexVotProxyRouting {
    static final String LARGE_AUDIO_FALLBACK_BASE_URL =
            "https://vot-new.toil-dump.workers.dev";

    private static final String AUDIO_UPLOAD_PATH = "/video-translation/audio";
    private static final String LIMITED_PROXY_BASE_URL = "https://vot-worker.eu.cc";
    private static final String LIMITED_PROXY_ALTERNATE_BASE_URL =
            "https://vot-worker.vtrans.eu.cc";
    private static volatile String oversizedProxyBaseUrl;

    private YandexVotProxyRouting() {
    }

    @NonNull
    static String initialUrl(
            @NonNull String configuredProxyBaseUrl,
            @NonNull String path,
            @NonNull String method
    ) {
        if (isAudioUpload(path, method)
                && configuredProxyBaseUrl.equals(oversizedProxyBaseUrl)) {
            return fallbackUrl(path);
        }
        return configuredProxyBaseUrl + path;
    }

    @Nullable
    static String retryUrlAfterResponse(
            @NonNull String configuredProxyBaseUrl,
            @NonNull String attemptedUrl,
            @NonNull String path,
            @NonNull String method,
            int responseCode
    ) {
        if (responseCode != 413
                || !isAudioUpload(path, method)
                || !isKnownLimitedProxy(configuredProxyBaseUrl)
                || attemptedUrl.equals(fallbackUrl(path))) {
            return null;
        }

        oversizedProxyBaseUrl = configuredProxyBaseUrl;
        return fallbackUrl(path);
    }

    private static boolean isAudioUpload(@NonNull String path, @NonNull String method) {
        return AUDIO_UPLOAD_PATH.equals(path) && "PUT".equals(method);
    }

    private static boolean isKnownLimitedProxy(@NonNull String proxyBaseUrl) {
        return LIMITED_PROXY_BASE_URL.equals(proxyBaseUrl)
                || LIMITED_PROXY_ALTERNATE_BASE_URL.equals(proxyBaseUrl);
    }

    @NonNull
    private static String fallbackUrl(@NonNull String path) {
        return LARGE_AUDIO_FALLBACK_BASE_URL + path;
    }

    static void resetForTests() {
        oversizedProxyBaseUrl = null;
    }
}
