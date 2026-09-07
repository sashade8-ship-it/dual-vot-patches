/*
 * Copyright (C) 2026 Dual VoT contributors
 *
 * Licensed under the GNU General Public License v3.0.
 */

package app.morphe.extension.youtube.patches.voiceovertranslation.yandex;

import java.io.IOException;
import java.util.List;

/**
 * Bounded, ordered streaming of the complete original (compressed) audio track into the
 * Yandex upload protocol parts.
 *
 * <p>The core is deliberately transport-free: the byte source and the Yandex uploader are
 * injected, so every ordering, cancellation, deadline, cleanup and failure-classification
 * rule is unit-tested without a device or a network. Only one part (at most
 * {@link YandexVotAudioParts#PART_SIZE_BYTES} bytes) is ever held in memory, so long
 * multipart tracks never reside in RAM as a whole.
 */
final class YandexVotAudioTransfer {
    private YandexVotAudioTransfer() {
    }

    /** Cancellation hook. {@link #isCancelled()} is checked before every part. */
    interface Progress {
        boolean isCancelled();

        void onUploading(int part, int totalParts);
    }

    /** Overall acquisition + upload deadline, checked before every part. */
    interface Deadline {
        boolean isExpired();
    }

    /** Source of the original compressed audio, read one bounded part at a time. */
    interface PartReader extends AutoCloseable {
        /**
         * Reads the closed byte range {@code [start, start + length)} and returns exactly
         * {@code length} bytes, or throws when the media host denied the range or the body
         * was truncated/empty. Implementations must never buffer more than one part.
         */
        byte[] read(long start, int length) throws SourceDeniedException, SourceReadException;

        /**
         * Releases any source resources. Called exactly once after every outcome.
         */
        @Override
        void close();
    }

    /** Uploader of one ordered part to Yandex. */
    interface PartUploader {
        /**
         * @param fileId the Yandex file id for the whole track
         * @param totalParts the total number of parts
         * @param partIndex zero-based part index
         * @param partData the part bytes, at most {@link YandexVotAudioParts#PART_SIZE_BYTES}
         * @return true when Yandex accepted the part
         */
        boolean uploadPart(String fileId, int totalParts, int partIndex, byte[] partData);
    }

    /** The media host rejected a requested range (for example HTTP 403 on later offsets). */
    static final class SourceDeniedException extends IOException {
        SourceDeniedException(String message) {
            super(message);
        }
    }

    /** A source read did not yield the requested part bytes. */
    static final class SourceReadException extends IOException {
        SourceReadException(String message) {
            super(message);
        }
    }

    /**
     * Streams the whole track from {@code reader} into Yandex parts in ascending order.
     *
     * @param fileSize total source size in bytes; zero or negative is an invalid/empty source
     * @param reader the injectable full-track source
     * @param fileId the Yandex file id for the whole track
     * @param uploader the injectable Yandex part uploader
     * @param progress cancellation and progress reporting
     * @param deadline overall deadline; may be null for no deadline
     */
    static YandexVotAudioResult run(
            long fileSize,
            PartReader reader,
            String fileId,
            PartUploader uploader,
            Progress progress,
            Deadline deadline
    ) {
        try {
            if (reader == null || uploader == null || progress == null) {
                return YandexVotAudioResult.SOURCE_UNAVAILABLE;
            }
            if (fileSize <= 0) {
                return YandexVotAudioResult.SOURCE_UNAVAILABLE;
            }
            List<YandexVotAudioParts.Part> parts = YandexVotAudioParts.parts(fileSize);
            int totalParts = parts.size();
            if (totalParts <= 0) {
                return YandexVotAudioResult.SOURCE_UNAVAILABLE;
            }
            if (progress.isCancelled()) {
                return YandexVotAudioResult.CANCELLED;
            }
            if (isExpired(deadline)) {
                return YandexVotAudioResult.DEADLINE_EXCEEDED;
            }

            for (int partIndex = 0; partIndex < totalParts; partIndex++) {
                if (progress.isCancelled()) {
                    return YandexVotAudioResult.CANCELLED;
                }
                if (isExpired(deadline)) {
                    return YandexVotAudioResult.DEADLINE_EXCEEDED;
                }
                progress.onUploading(partIndex + 1, totalParts);

                YandexVotAudioParts.Part part = parts.get(partIndex);
                byte[] partData;
                try {
                    partData = reader.read(part.start(), part.length());
                } catch (SourceDeniedException ex) {
                    return YandexVotAudioResult.SOURCE_DENIED;
                } catch (SourceReadException ex) {
                    return YandexVotAudioResult.SOURCE_READ_FAILED;
                }
                if (partData == null || partData.length != part.length()) {
                    // Never treat an empty or truncated part as a successful upload.
                    return YandexVotAudioResult.SOURCE_READ_FAILED;
                }
                if (!uploader.uploadPart(fileId, totalParts, partIndex, partData)) {
                    return YandexVotAudioResult.UPLOAD_FAILED;
                }
            }
            return YandexVotAudioResult.SUCCESS;
        } finally {
            try {
                if (reader != null) reader.close();
            } catch (Exception ignored) {
            }
        }
    }

    private static boolean isExpired(Deadline deadline) {
        return deadline != null && deadline.isExpired();
    }
}
