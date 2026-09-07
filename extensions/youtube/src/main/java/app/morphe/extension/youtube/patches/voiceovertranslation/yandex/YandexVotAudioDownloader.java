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
 * The independent YouTube stream fallback is adapted from the MIT-licensed
 * ilyhalight/voice-over-translation project.
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

import android.net.Uri;

import androidx.annotation.Nullable;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.SecureRandom;
import java.util.List;
import java.util.Locale;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.innertube.utils.PlayerResponseOuterClass.Format;
import app.morphe.extension.shared.innertube.utils.PlayerResponseOuterClass.PlayerResponse;
import app.morphe.extension.shared.spoof.requests.StreamingDataRequest;

/**
 * Acquires the complete original compressed audio track of the currently playing video and
 * uploads it to Yandex in ordered parts of {@link YandexVotAudioParts#PART_SIZE_BYTES}.
 *
 * <p>Only the format list of the YouTube session that is actually playing the video is used
 * as the source. The obsolete standalone {@code ANDROID_VR} watch-page/player scraping is
 * gone: on supported app versions YouTube only authorizes a short initial prefix of such
 * externally requested URLs, so a whole-track fetch can never be proven from them. The
 * acquisition is transport-explicit and never falls back to empty audio.
 *
 * <p>Signed URLs, cookies, tokens and private request headers are never logged.
 */
final class YandexVotAudioDownloader {
    private static final int CONNECTION_TIMEOUT_MS = 15_000;
    private static final int READ_TIMEOUT_MS = 30_000;

    /**
     * Safety net for the whole acquisition + upload run. Every network operation is also
     * individually bounded by the connect/read timeouts, so a stalled run ends in bounded
     * time instead of waiting forever.
     */
    private static final long OVERALL_TRANSFER_DEADLINE_MS = 15 * 60 * 1000L;

    private static final String AUDIO_DOWNLOAD_TYPE = "web_api_steal_sig_and_n";
    private static final String USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                    "(KHTML, like Gecko) Chrome/134.0.0.0 YaBrowser/25.4.0.0 Safari/537.36";
    private static final String CPN_ALPHABET =
            "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789-_";
    private static final SecureRandom CPN_RANDOM = new SecureRandom();

    private record AudioFormatInfo(
            String url,
            int itag,
            long fileSize,
            String mimeType,
            int bitrate
    ) {
    }

    interface ProgressListener {
        boolean isCancelled();

        void onPreparing();

        void onUploading(int part, int totalParts);
    }

    private YandexVotAudioDownloader() {
    }

    static YandexVotAudioResult downloadAndSend(
            String videoId,
            String videoUrl,
            String translationId,
            ProgressListener listener
    ) {
        if (isEmpty(videoId) || isEmpty(videoUrl) || isEmpty(translationId)) {
            return YandexVotAudioResult.SOURCE_UNAVAILABLE;
        }
        if (listener == null || listener.isCancelled()) {
            return YandexVotAudioResult.CANCELLED;
        }

        try {
            listener.onPreparing();
            AudioFormatInfo audioFormat = resolveAudioFormat(videoId);
            if (listener.isCancelled()) return YandexVotAudioResult.CANCELLED;
            if (audioFormat == null || isEmpty(audioFormat.url())) {
                Logger.printDebug(() -> "Yandex VOT audio upload: no playing-session audio"
                        + " format for " + videoId);
                return YandexVotAudioResult.SOURCE_UNAVAILABLE;
            }

            String audioUrl = audioFormat.url();
            long fileSize;
            try {
                fileSize = audioFormat.fileSize() > 0
                        ? audioFormat.fileSize()
                        : resolveFileSize(audioUrl);
            } catch (YandexVotAudioTransfer.SourceDeniedException ex) {
                return YandexVotAudioResult.SOURCE_DENIED;
            } catch (IOException ex) {
                return YandexVotAudioResult.SOURCE_READ_FAILED;
            }
            if (listener.isCancelled()) return YandexVotAudioResult.CANCELLED;
            if (fileSize <= 0) {
                Logger.printDebug(() -> "Yandex VOT audio upload: unknown/empty audio size for "
                        + videoId);
                return YandexVotAudioResult.SOURCE_UNAVAILABLE;
            }

            int parts = YandexVotAudioParts.partCount(fileSize);
            Logger.printDebug(() -> "Yandex VOT audio upload: selected itag="
                    + audioFormat.itag() + ", mime=" + audioFormat.mimeType()
                    + ", bitrate=" + audioFormat.bitrate()
                    + ", bytes=" + fileSize + ", parts=" + parts);
            String fileId = makeFileId(audioFormat.itag(), fileSize);

            final long deadlineMs = android.os.SystemClock.elapsedRealtime()
                    + OVERALL_TRANSFER_DEADLINE_MS;
            YandexVotAudioTransfer.Deadline deadline =
                    () -> android.os.SystemClock.elapsedRealtime() >= deadlineMs;
            YandexVotAudioTransfer.PartReader reader =
                    new HttpPartReader(audioUrl);

            YandexVotAudioTransfer.Progress progress = new YandexVotAudioTransfer.Progress() {
                @Override
                public boolean isCancelled() {
                    return listener.isCancelled();
                }

                @Override
                public void onUploading(int part, int totalParts) {
                    listener.onUploading(part, totalParts);
                }
            };

            YandexVotAudioResult result = YandexVotAudioTransfer.run(
                    fileSize,
                    reader,
                    fileId,
                    new YandexVotApiUploader(videoUrl, translationId),
                    progress,
                    deadline
            );
            if (result.isSuccess()) {
                Logger.printDebug(() -> "Yandex VOT audio upload completed for " + videoId
                        + " (" + parts + " part(s))");
            } else {
                Logger.printDebug(() -> "Yandex VOT audio upload failed for " + videoId
                        + ": " + result);
            }
            return result;
        } catch (Exception e) {
            // Never expose the signed source URL or its host details. Fail the acquisition
            // with the generic source category and keep the exact cause out of logs.
            Logger.printDebug(() -> "Yandex VOT audio upload aborted unexpectedly for "
                    + videoId);
            return YandexVotAudioResult.SOURCE_READ_FAILED;
        }
    }

    /**
     * Resolves the best audio-only format from the player-response streaming data of the
     * session that is playing the video. No separate YouTube request is started here:
     * a standalone request does not share the working player session and cannot be proven
     * to authorize the whole track.
     */
    @Nullable
    private static AudioFormatInfo resolveAudioFormat(String videoId) throws Exception {
        StreamingDataRequest request = StreamingDataRequest.getRequestForVideoId(videoId);
        Format cachedFormat = getAudioFormat(request);
        if (cachedFormat == null) {
            return null;
        }
        return new AudioFormatInfo(
                addCpn(cachedFormat.getUrl()),
                cachedFormat.getItag(),
                -1,
                cachedFormat.getMimeType(),
                getBitrate(cachedFormat)
        );
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
        Format bestOpus = null;
        int bestOpusBitrate = Integer.MAX_VALUE;
        Format bestOther = null;
        int bestOtherBitrate = Integer.MAX_VALUE;

        for (Format format : formats) {
            if (isEmpty(format.getUrl()) || !isAudioFormat(format)) continue;

            int bitrate = getBitrate(format);
            boolean opus = format.getMimeType().toLowerCase(Locale.US).contains("opus");
            if (opus) {
                if (bestOpus == null || bitrate < bestOpusBitrate) {
                    bestOpus = format;
                    bestOpusBitrate = bitrate;
                }
            } else if (bestOther == null || bitrate < bestOtherBitrate) {
                bestOther = format;
                bestOtherBitrate = bitrate;
            }
        }

        return bestOpus != null ? bestOpus : bestOther;
    }

    private static boolean isAudioFormat(Format format) {
        String mimeType = format.getMimeType();
        if (mimeType != null && mimeType.startsWith("audio/")) return true;

        return switch (format.getItag()) {
            case 139, 140, 141, 171, 172, 249, 250, 251, 599, 600, 774 -> true;
            default -> false;
        };
    }

    private static int getBitrate(Format format) {
        int bitrate = format.getAverageBitrate() > 0
                ? format.getAverageBitrate()
                : format.getBitrate();
        return bitrate > 0 ? bitrate : Integer.MAX_VALUE - 1;
    }

    /**
     * Learns the total audio size from the {@code clen} URL parameter when present,
     * otherwise probes the first byte range. The probe result also proves the chosen
     * session URL still authorizes reads before a whole-track transfer is attempted.
     */
    private static long resolveFileSize(String audioUrl) throws IOException {
        long size = parseClen(audioUrl);
        if (size > 0) return size;

        HttpURLConnection connection = openAudioConnection(audioUrl, 0, 0);
        try {
            int code = connection.getResponseCode();
            if (code == HttpURLConnection.HTTP_FORBIDDEN) {
                throw new YandexVotAudioTransfer.SourceDeniedException(
                        "Media server denied the range probe (HTTP 403)");
            }
            if (code == HttpURLConnection.HTTP_PARTIAL) {
                size = parseContentRangeSize(connection.getHeaderField("Content-Range"));
                if (size > 0) return size;
            }
            if (code != HttpURLConnection.HTTP_OK
                    && code != HttpURLConnection.HTTP_PARTIAL) {
                throw new IOException("Audio size probe failed: HTTP " + code);
            }

            long contentLength = connection.getContentLengthLong();
            return contentLength > 0 ? contentLength : -1;
        } finally {
            connection.disconnect();
        }
    }

    /** Reads one Yandex part (bounded byte range) over plain HTTP(S). */
    private static final class HttpPartReader implements YandexVotAudioTransfer.PartReader {
        private final String audioUrl;

        HttpPartReader(String audioUrl) {
            this.audioUrl = audioUrl;
        }

        @Override
        public byte[] read(long start, int length)
                throws YandexVotAudioTransfer.SourceDeniedException,
                YandexVotAudioTransfer.SourceReadException {
            if (length <= 0 || length > YandexVotAudioParts.PART_SIZE_BYTES) {
                throw new YandexVotAudioTransfer.SourceReadException("Invalid audio part size");
            }
            long end = start + length - 1;

            HttpURLConnection connection = null;
            try {
                connection = openAudioConnection(audioUrl, start, end);
                int code = connection.getResponseCode();
                if (code == HttpURLConnection.HTTP_FORBIDDEN) {
                    throw new YandexVotAudioTransfer.SourceDeniedException(
                            "Media server denied the requested range (HTTP 403)");
                }
                if (code == HttpURLConnection.HTTP_PARTIAL) {
                    verifyRangeStart(connection.getHeaderField("Content-Range"), start);
                } else if (code == HttpURLConnection.HTTP_OK) {
                    // The server ignored the Range header. Only a first (single) part request
                    // can still be salvaged by reading the leading bytes.
                    if (start > 0) {
                        throw new YandexVotAudioTransfer.SourceReadException(
                                "Media server ignored the range request");
                    }
                } else {
                    throw new YandexVotAudioTransfer.SourceReadException(
                            "Audio download failed: HTTP " + code);
                }

                try (InputStream inputStream = connection.getInputStream()) {
                    return readExactly(inputStream, length);
                }
            } catch (YandexVotAudioTransfer.SourceDeniedException ex) {
                throw ex;
            } catch (YandexVotAudioTransfer.SourceReadException ex) {
                throw ex;
            } catch (IOException ex) {
                throw new YandexVotAudioTransfer.SourceReadException(
                        "Audio part could not be read");
            } finally {
                if (connection != null) connection.disconnect();
            }
        }

        @Override
        public void close() {
            // Every part connection is closed inside read(); nothing else to release.
        }
    }

    private static void verifyRangeStart(@Nullable String contentRange, long expectedStart)
            throws YandexVotAudioTransfer.SourceReadException {
        if (contentRange == null) return;

        int dash = contentRange.indexOf('-');
        if (dash <= 0) return;
        long actualStart;
        try {
            actualStart = Long.parseLong(contentRange.substring(0, dash).trim());
        } catch (NumberFormatException ex) {
            return;
        }
        if (actualStart != expectedStart) {
            throw new YandexVotAudioTransfer.SourceReadException(
                    "Media server returned an unexpected byte range");
        }
    }

    private static byte[] readExactly(InputStream inputStream, int length)
            throws YandexVotAudioTransfer.SourceReadException {
        if (length == 0) return new byte[0];

        ByteArrayOutputStream out = new ByteArrayOutputStream(length);
        byte[] buffer = new byte[8192];
        int total = 0;
        try {
            int read;
            while (total < length
                    && (read = inputStream.read(buffer, 0, Math.min(buffer.length, length - total)))
                    != -1) {
                if (read <= 0) continue;
                out.write(buffer, 0, read);
                total += read;
            }
        } catch (IOException ex) {
            throw new YandexVotAudioTransfer.SourceReadException(
                    "Audio part could not be read");
        }
        if (total != length) {
            throw new YandexVotAudioTransfer.SourceReadException(
                    "Incomplete audio part: expected " + length + " bytes, got " + total);
        }
        return out.toByteArray();
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

    /** Uploads a whole part to Yandex using the existing validated protocol calls. */
    private record YandexVotApiUploader(String videoUrl, String translationId)
            implements YandexVotAudioTransfer.PartUploader {
        @Override
        public boolean uploadPart(String fileId, int totalParts, int partIndex, byte[] partData) {
            if (totalParts <= 1) {
                return YandexVotApiClient.sendAudio(videoUrl, translationId, fileId, partData);
            }
            return YandexVotApiClient.sendPartialAudio(
                    videoUrl, translationId, fileId, totalParts, 1, partIndex, partData);
        }
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

    private static String addCpn(String audioUrl) {
        return Uri.parse(audioUrl)
                .buildUpon()
                .appendQueryParameter("cpn", makeCpn())
                .build()
                .toString();
    }

    private static String makeCpn() {
        StringBuilder cpn = new StringBuilder(16);
        for (int i = 0; i < 16; i++) {
            cpn.append(CPN_ALPHABET.charAt(CPN_RANDOM.nextInt(CPN_ALPHABET.length())));
        }
        return cpn.toString();
    }

    private static String makeFileId(int itag, long fileSize) {
        return String.format(
                Locale.US,
                "{\"downloadType\":\"%s\",\"itag\":%d,\"minChunkSize\":%d,\"fileSize\":\"%d\"}",
                AUDIO_DOWNLOAD_TYPE,
                itag,
                YandexVotAudioParts.PART_SIZE_BYTES,
                fileSize
        );
    }

    private static boolean isEmpty(@Nullable String value) {
        return value == null || value.isEmpty();
    }
}
