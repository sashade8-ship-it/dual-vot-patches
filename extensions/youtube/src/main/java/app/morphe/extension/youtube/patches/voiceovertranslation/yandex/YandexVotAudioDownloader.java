/*
 * Copyright (C) 2026 anddea
 *
 * This file is derived from the revanced-patches project:
 * https://github.com/anddea/revanced-patches
 *
 * Original author(s):
 * - anddea (https://github.com/anddea)
 *
 * Modified for Dual VoT Patches and Morphe 1.37.0.
 *
 * Licensed under the GNU General Public License v3.0.
 *
 * ------------------------------------------------------------------------
 * GPLv3 Section 7 – Additional Terms & Attribution Requirements
 * ------------------------------------------------------------------------
 *
 * This file contains substantial original work by the author(s) listed above.
 *
 * In accordance with Section 7 of the GNU General Public License v3.0,
 * the following additional terms apply to this file:
 *
 * 1. Source Credit Preservation (Section 7(b)): This specific copyright notice
 *    and the list of original authors above must be preserved in any copy
 *    or derivative work. You may add your own copyright notice below it,
 *    but you may not remove the original one.
 *
 * 2. Origin & Modification Marking (Section 7(c)): Modified versions must be
 *    clearly marked as such and must not be misrepresented as the original work.
 *
 * 3. Version Control Attribution (Section 7(b)): Ports or substantial
 *    modifications must retain historical authorship credit in version control.
 *
 * 4. User Interface Attribution (Section 7(b)): Derived works must maintain a
 *    visible acknowledgment to the original author(s) in the application UI.
 */

package app.morphe.extension.youtube.patches.voiceovertranslation.yandex;

import androidx.annotation.Nullable;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.innertube.utils.PlayerResponseOuterClass.Format;
import app.morphe.extension.shared.innertube.utils.PlayerResponseOuterClass.PlayerResponse;
import app.morphe.extension.shared.spoof.requests.StreamingDataRequest;

final class YandexVotAudioDownloader {
    private static final int CHUNK_SIZE_BYTES = 5_295_308;
    private static final int CONNECTION_TIMEOUT_MS = 15_000;
    private static final int READ_TIMEOUT_MS = 30_000;
    private static final String AUDIO_DOWNLOAD_TYPE = "web_api_steal_sig_and_n";
    private static final String USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                    "(KHTML, like Gecko) Chrome/134.0.0.0 YaBrowser/25.4.0.0 Safari/537.36";

    interface ProgressListener {
        boolean isCancelled();

        void onPreparing();

        void onUploading(int part, int totalParts);
    }

    private YandexVotAudioDownloader() {
    }

    static boolean downloadAndSend(
            String videoId,
            String videoUrl,
            String translationId,
            ProgressListener listener
    ) {
        if (isEmpty(videoId) || isEmpty(videoUrl) || isEmpty(translationId)) return false;
        if (listener.isCancelled()) return false;

        try {
            listener.onPreparing();
            Format audioFormat = fetchAudioFormat(videoId);
            if (listener.isCancelled()) return false;
            if (audioFormat == null || isEmpty(audioFormat.getUrl())) {
                Logger.printDebug(() -> "Yandex VOT audio upload: no audio format for " + videoId);
                return false;
            }

            String audioUrl = audioFormat.getUrl();
            long fileSize = resolveFileSize(audioUrl);
            if (listener.isCancelled()) return false;
            if (fileSize <= 0) {
                Logger.printDebug(() -> "Yandex VOT audio upload: unknown audio size for " + videoId);
                return false;
            }

            String fileId = makeFileId(audioFormat.getItag(), fileSize);
            int parts = toPartsCount(fileSize);
            if (parts <= 1) {
                listener.onUploading(1, 1);
                byte[] audioData = downloadRange(audioUrl, 0, fileSize - 1);
                return !listener.isCancelled()
                        && YandexVotApiClient.sendAudio(
                                videoUrl, translationId, fileId, audioData);
            }

            for (int i = 0; i < parts; i++) {
                if (listener.isCancelled()) return false;
                listener.onUploading(i + 1, parts);
                long start = (long) i * CHUNK_SIZE_BYTES;
                long end = Math.min(fileSize - 1, start + CHUNK_SIZE_BYTES - 1);
                byte[] audioData = downloadRange(audioUrl, start, end);
                if (listener.isCancelled()
                        || !YandexVotApiClient.sendPartialAudio(
                                videoUrl, translationId, fileId, parts, 1, i, audioData)) {
                    return false;
                }
            }

            return true;
        } catch (Exception e) {
            Logger.printException(() -> "Yandex VOT audio upload failed for " + videoId, e);
            return false;
        }
    }

    @Nullable
    private static Format fetchAudioFormat(String videoId) throws IOException {
        StreamingDataRequest request = StreamingDataRequest.getRequestForVideoId(videoId);
        Format cachedFormat = getAudioFormat(request);
        if (cachedFormat != null) return cachedFormat;

        StreamingDataRequest.fetchRequest(videoId, Collections.emptyMap());
        return getAudioFormat(StreamingDataRequest.getRequestForVideoId(videoId));
    }

    @Nullable
    private static Format getAudioFormat(@Nullable StreamingDataRequest request) throws IOException {
        if (request == null) return null;

        StreamingDataRequest.StreamData streamData = request.getStream();
        if (streamData == null) return null;

        byte[] playerResponseBytes = streamData.streamingData();
        if (playerResponseBytes == null || playerResponseBytes.length == 0) return null;

        PlayerResponse playerResponse = PlayerResponse.parseFrom(playerResponseBytes);
        if (!playerResponse.hasStreamingData()) return null;

        return selectBestAudioFormat(playerResponse.getStreamingData().getAdaptiveFormatsList());
    }

    @Nullable
    private static Format selectBestAudioFormat(List<Format> formats) {
        Format best = null;
        int bestBitrate = -1;

        for (Format format : formats) {
            if (isEmpty(format.getUrl()) || !isAudioFormat(format)) continue;

            int bitrate = format.getAverageBitrate() > 0
                    ? format.getAverageBitrate()
                    : format.getBitrate();
            if (best == null || bitrate > bestBitrate) {
                best = format;
                bestBitrate = bitrate;
            }
        }

        return best;
    }

    private static boolean isAudioFormat(Format format) {
        String mimeType = format.getMimeType();
        if (mimeType != null && mimeType.startsWith("audio/")) return true;

        return switch (format.getItag()) {
            case 139, 140, 141, 171, 172, 249, 250, 251, 599, 600, 774 -> true;
            default -> false;
        };
    }

    private static long resolveFileSize(String audioUrl) throws IOException {
        long size = parseClen(audioUrl);
        if (size > 0) return size;

        HttpURLConnection connection = openAudioConnection(audioUrl, 0, 0);
        try {
            int code = connection.getResponseCode();
            if (code == HttpURLConnection.HTTP_PARTIAL) {
                size = parseContentRangeSize(connection.getHeaderField("Content-Range"));
                if (size > 0) return size;
            }

            long contentLength = connection.getContentLengthLong();
            return contentLength > 0 ? contentLength : -1;
        } finally {
            connection.disconnect();
        }
    }

    private static byte[] downloadRange(String audioUrl, long start, long end) throws IOException {
        long expectedSize = end - start + 1;
        if (expectedSize <= 0 || expectedSize > Integer.MAX_VALUE) {
            throw new IOException("Invalid audio range size: " + expectedSize);
        }

        HttpURLConnection connection = openAudioConnection(audioUrl, start, end);
        try {
            int code = connection.getResponseCode();
            if (code != HttpURLConnection.HTTP_PARTIAL && code != HttpURLConnection.HTTP_OK) {
                throw new IOException("Audio download failed: HTTP " + code);
            }
            if (code == HttpURLConnection.HTTP_OK && start > 0) {
                throw new IOException("Audio server ignored range request");
            }

            try (InputStream inputStream = connection.getInputStream()) {
                return readBytes(inputStream, expectedSize);
            }
        } finally {
            connection.disconnect();
        }
    }

    private static HttpURLConnection openAudioConnection(
            String audioUrl,
            long start,
            long end
    ) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) new URL(audioUrl).openConnection();
        connection.setRequestMethod("GET");
        connection.setRequestProperty("Range", "bytes=" + start + "-" + end);
        connection.setRequestProperty("Accept", "*/*");
        connection.setRequestProperty("Accept-Encoding", "identity");
        connection.setRequestProperty("User-Agent", USER_AGENT);
        connection.setConnectTimeout(CONNECTION_TIMEOUT_MS);
        connection.setReadTimeout(READ_TIMEOUT_MS);
        connection.setInstanceFollowRedirects(true);
        return connection;
    }

    private static byte[] readBytes(InputStream inputStream, long expectedSize) throws IOException {
        ByteArrayOutputStream out =
                new ByteArrayOutputStream((int) Math.min(expectedSize, CHUNK_SIZE_BYTES));
        byte[] buffer = new byte[8192];
        long total = 0;
        int read;
        while ((read = inputStream.read(buffer)) != -1) {
            total += read;
            if (total > expectedSize) {
                throw new IOException("Audio range exceeded expected size");
            }
            out.write(buffer, 0, read);
        }
        if (total == 0) {
            throw new IOException("Empty audio range");
        }
        return out.toByteArray();
    }

    private static long parseClen(String audioUrl) {
        int queryStart = audioUrl.indexOf('?');
        if (queryStart < 0 || queryStart == audioUrl.length() - 1) return -1;

        String query = audioUrl.substring(queryStart + 1);
        String[] params = query.split("&");
        for (String param : params) {
            if (!param.startsWith("clen=")) continue;
            try {
                return Long.parseLong(param.substring(5));
            } catch (NumberFormatException ignored) {
                return -1;
            }
        }
        return -1;
    }

    private static long parseContentRangeSize(@Nullable String contentRange) {
        if (contentRange == null) return -1;

        int slash = contentRange.lastIndexOf('/');
        if (slash < 0 || slash == contentRange.length() - 1) return -1;

        try {
            return Long.parseLong(contentRange.substring(slash + 1).trim());
        } catch (NumberFormatException ignored) {
            return -1;
        }
    }

    private static int toPartsCount(long fileSize) throws IOException {
        long parts = (fileSize + CHUNK_SIZE_BYTES - 1) / CHUNK_SIZE_BYTES;
        if (parts <= 0 || parts > Integer.MAX_VALUE) {
            throw new IOException("Invalid audio parts count: " + parts);
        }
        return (int) parts;
    }

    private static String makeFileId(int itag, long fileSize) {
        return String.format(
                Locale.US,
                "{\"downloadType\":\"%s\",\"itag\":%d,\"minChunkSize\":%d,\"fileSize\":\"%d\"}",
                AUDIO_DOWNLOAD_TYPE,
                itag,
                CHUNK_SIZE_BYTES,
                fileSize
        );
    }

    private static boolean isEmpty(@Nullable String value) {
        return value == null || value.isEmpty();
    }
}
