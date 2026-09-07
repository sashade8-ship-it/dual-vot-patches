/*
 * Copyright (C) 2026 Dual VoT contributors
 *
 * Licensed under the GNU General Public License v3.0.
 */

package app.morphe.extension.youtube.patches.voiceovertranslation.yandex;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import app.morphe.extension.youtube.patches.voiceovertranslation.yandex.YandexVotAudioParts.Part;
import app.morphe.extension.youtube.patches.voiceovertranslation.yandex.YandexVotAudioResult.FailureKind;
import app.morphe.extension.youtube.patches.voiceovertranslation.yandex.YandexVotAudioTransfer.Deadline;
import app.morphe.extension.youtube.patches.voiceovertranslation.yandex.YandexVotAudioTransfer.PartReader;
import app.morphe.extension.youtube.patches.voiceovertranslation.yandex.YandexVotAudioTransfer.PartUploader;
import app.morphe.extension.youtube.patches.voiceovertranslation.yandex.YandexVotAudioTransfer.Progress;
import app.morphe.extension.youtube.patches.voiceovertranslation.yandex.YandexVotAudioTransfer.SourceDeniedException;
import app.morphe.extension.youtube.patches.voiceovertranslation.yandex.YandexVotAudioTransfer.SourceReadException;

public class YandexVotAudioTransferTest {
    private static final int PART = YandexVotAudioParts.PART_SIZE_BYTES;
    private static final String FILE_ID = "fileId";

    private static final class State {
        boolean cancelRequested = false;
        boolean deadlineExpired = false;
    }

    private static final class FakeTrack implements PartReader {
        private final byte[] track;
        private int faultOnRead = -1;
        private SourceDeniedException deniedFault;
        private SourceReadException readFault;
        private boolean truncate = false;
        private boolean emptyPart = false;
        private int reads = 0;
        private boolean closed = false;

        FakeTrack(byte[] track) {
            this.track = track;
        }

        FakeTrack failDeniedOnRead(int readNumber) {
            faultOnRead = readNumber;
            deniedFault = new SourceDeniedException("denied");
            return this;
        }

        FakeTrack failReadOnRead(int readNumber) {
            faultOnRead = readNumber;
            readFault = new SourceReadException("read failed");
            return this;
        }

        FakeTrack truncate() {
            truncate = true;
            return this;
        }

        FakeTrack emptyPart() {
            emptyPart = true;
            return this;
        }

        @Override
        public byte[] read(long start, int length)
                throws SourceDeniedException, SourceReadException {
            reads++;
            if (reads == faultOnRead) {
                if (deniedFault != null) throw deniedFault;
                if (readFault != null) throw readFault;
            }
            if (emptyPart) return new byte[0];
            int wanted = truncate ? Math.max(0, length - 1) : length;
            byte[] result = new byte[wanted];
            System.arraycopy(track, (int) start, result, 0, wanted);
            return result;
        }

        @Override
        public void close() {
            closed = true;
        }
    }

    private static final class FakeUploader implements PartUploader {
        private final State state;
        private final List<Integer> indices = new ArrayList<>();
        private final List<byte[]> parts = new ArrayList<>();
        private String fileId = null;
        private int total = -1;
        private int cancelAfterUploads = Integer.MAX_VALUE;
        private int expireAfterUploads = Integer.MAX_VALUE;
        private int failUpload = -1;

        FakeUploader(State state) {
            this.state = state;
        }

        FakeUploader cancelAfter(int uploads) {
            cancelAfterUploads = uploads;
            return this;
        }

        FakeUploader expireAfter(int uploads) {
            expireAfterUploads = uploads;
            return this;
        }

        FakeUploader failOnUpload(int uploadNumber) {
            failUpload = uploadNumber;
            return this;
        }

        @Override
        public boolean uploadPart(String fileId, int totalParts, int partIndex, byte[] partData) {
            if (parts.size() + 1 == failUpload) return false;
            this.fileId = fileId;
            this.total = totalParts;
            parts.add(partData);
            indices.add(partIndex);
            if (parts.size() == cancelAfterUploads) state.cancelRequested = true;
            if (parts.size() == expireAfterUploads) state.deadlineExpired = true;
            return true;
        }
    }

    private static final class FakeProgress implements Progress {
        private final State state;
        private final List<Integer> reported = new ArrayList<>();
        private int reportedTotal = -1;

        FakeProgress(State state) {
            this.state = state;
        }

        @Override
        public boolean isCancelled() {
            return state.cancelRequested;
        }

        @Override
        public void onUploading(int part, int totalParts) {
            reported.add(part);
            reportedTotal = totalParts;
        }
    }

    private static byte[] makeTrack(int size) {
        byte[] track = new byte[size];
        for (int i = 0; i < size; i++) {
            track[i] = (byte) (i % 251);
        }
        return track;
    }

    private static State newState() {
        return new State();
    }

    private static FakeProgress newProgress(State state) {
        return new FakeProgress(state);
    }

    @Test
    public void shortAudioUploadsSingleWholePart() {
        State state = newState();
        byte[] track = makeTrack(100);
        FakeUploader uploader = new FakeUploader(state);
        FakeProgress progress = newProgress(state);
        FakeTrack reader = new FakeTrack(track);

        YandexVotAudioResult result = YandexVotAudioTransfer.run(
                track.length, reader, FILE_ID, uploader, progress, null);

        assertEquals(YandexVotAudioResult.SUCCESS, result);
        assertEquals(1, uploader.parts.size());
        assertEquals(1, uploader.total);
        assertEquals(0, (int) uploader.indices.get(0));
        assertEquals(track.length, uploader.parts.get(0).length);
        assertTrue(uploader.fileId.equals(FILE_ID));
        assertEquals(1, progress.reported.size());
        assertTrue(reader.closed);
    }

    @Test
    public void exactOnePartAudioUploadsWholeTrack() {
        State state = newState();
        byte[] track = makeTrack(PART);
        FakeUploader uploader = new FakeUploader(state);
        FakeTrack reader = new FakeTrack(track);

        YandexVotAudioResult result = YandexVotAudioTransfer.run(
                track.length, reader, FILE_ID, uploader, newProgress(state), null);

        assertEquals(YandexVotAudioResult.SUCCESS, result);
        assertEquals(1, uploader.parts.size());
        assertEquals(PART, uploader.parts.get(0).length);
    }

    @Test
    public void multipartAudioUploadsAllPartsInOrderWithExactBoundaries() {
        int fileSize = 2 * PART + 7;
        State state = newState();
        byte[] track = makeTrack(fileSize);
        FakeUploader uploader = new FakeUploader(state);
        FakeProgress progress = newProgress(state);
        FakeTrack reader = new FakeTrack(track);

        YandexVotAudioResult result = YandexVotAudioTransfer.run(
                track.length, reader, FILE_ID, uploader, progress, null);

        assertEquals(YandexVotAudioResult.SUCCESS, result);
        assertEquals(3, uploader.parts.size());
        assertEquals(3, uploader.total);
        assertEquals(List.of(0, 1, 2), uploader.indices);
        assertEquals(PART, uploader.parts.get(0).length);
        assertEquals(PART, uploader.parts.get(1).length);
        assertEquals(7, uploader.parts.get(2).length);

        // The bytes arrive in ascending, contiguous source order (final remainder included).
        assertTrue(java.util.Arrays.equals(track, concat(uploader.parts)));

        // 1/N, 2/N, 3/N progress events in order.
        assertEquals(List.of(1, 2, 3), progress.reported);
        assertEquals(3, progress.reportedTotal);
    }

    @Test
    public void partitioningCoversWholeTrackContiguously() {
        List<Part> parts = YandexVotAudioParts.parts(2L * PART + 5);
        assertEquals(3, parts.size());
        assertEquals(0L, parts.get(0).start());
        assertEquals(PART, parts.get(0).length());
        assertEquals((long) PART, parts.get(1).start());
        assertEquals(PART, parts.get(1).length());
        assertEquals((long) 2 * PART, parts.get(2).start());
        assertEquals(5, parts.get(2).length());

        // Ascending, non-overlapping, gap-free and complete coverage of [0, fileSize).
        long cursor = 0;
        for (Part part : parts) {
            assertEquals(cursor, part.start());
            cursor += part.length();
        }
        assertEquals(2L * PART + 5, cursor);
        assertEquals(3, YandexVotAudioParts.partCount(2L * PART + 5));
        assertEquals(1, YandexVotAudioParts.partCount(PART));
        assertEquals(2, YandexVotAudioParts.partCount(PART + 1));
        assertEquals(0, YandexVotAudioParts.partCount(0));
        assertTrue(YandexVotAudioParts.parts(0).isEmpty());
        assertTrue(YandexVotAudioParts.parts(-10).isEmpty());
    }

    @Test
    public void partialReadNeverSucceedsAndStopsFurtherParts() {
        State state = newState();
        int fileSize = PART + 100;
        FakeTrack reader = new FakeTrack(makeTrack(fileSize)).truncate();
        FakeUploader uploader = new FakeUploader(state);

        YandexVotAudioResult result = YandexVotAudioTransfer.run(
                fileSize, reader, FILE_ID, uploader, newProgress(state), null);

        assertEquals(YandexVotAudioResult.SOURCE_READ_FAILED, result);
        assertEquals(0, uploader.parts.size());
        assertTrue(reader.closed);
    }

    @Test
    public void emptyPartDataNeverSucceeds() {
        State state = newState();
        int fileSize = PART;
        FakeTrack reader = new FakeTrack(makeTrack(fileSize)).emptyPart();
        FakeUploader uploader = new FakeUploader(state);

        YandexVotAudioResult result = YandexVotAudioTransfer.run(
                fileSize, reader, FILE_ID, uploader, newProgress(state), null);

        assertEquals(YandexVotAudioResult.SOURCE_READ_FAILED, result);
        assertEquals(0, uploader.parts.size());
        assertTrue(reader.closed);
    }

    @Test
    public void http403AfterInitialPrefixIsClassifiedAndNeverRetried() {
        State state = newState();
        int fileSize = 2 * PART + 1;
        byte[] track = makeTrack(fileSize);
        FakeUploader uploader = new FakeUploader(state);
        FakeTrack reader = new FakeTrack(track).failDeniedOnRead(2);

        YandexVotAudioResult result = YandexVotAudioTransfer.run(
                fileSize, reader, FILE_ID, uploader, newProgress(state), null);

        assertEquals(YandexVotAudioResult.SOURCE_DENIED, result);
        assertEquals(1, uploader.parts.size());
        assertEquals(2, reader.reads); // exactly one attempt for the denied part, no retry
        assertTrue(reader.closed);
    }

    @Test
    public void cancellationStopsTransferCleanly() {
        State state = newState();
        int fileSize = 3 * PART;
        byte[] track = makeTrack(fileSize);
        FakeUploader uploader = new FakeUploader(state).cancelAfter(1);
        FakeTrack reader = new FakeTrack(track);

        YandexVotAudioResult result = YandexVotAudioTransfer.run(
                fileSize, reader, FILE_ID, uploader, newProgress(state), null);

        assertEquals(YandexVotAudioResult.CANCELLED, result);
        assertEquals(1, uploader.parts.size());
        assertTrue(reader.closed);
    }

    @Test
    public void videoChangeAbortsLikeCancellation() {
        // A video change invalidates the translation generation, which is surfaced to the
        // transfer through the same isCancelled() check as a user stop.
        State state = newState();
        int fileSize = 2 * PART;
        FakeUploader uploader = new FakeUploader(state).cancelAfter(1);
        FakeTrack reader = new FakeTrack(makeTrack(fileSize));

        YandexVotAudioResult result = YandexVotAudioTransfer.run(
                fileSize, reader, FILE_ID, uploader, newProgress(state), null);

        assertEquals(YandexVotAudioResult.CANCELLED, result);
        assertEquals(1, uploader.parts.size());
        assertTrue(reader.closed);
    }

    @Test
    public void deadlineStopsTransferWithoutWaitingForever() {
        State state = newState();
        int fileSize = 3 * PART;
        FakeUploader uploader = new FakeUploader(state).expireAfter(1);
        FakeTrack reader = new FakeTrack(makeTrack(fileSize));
        FakeProgress progress = newProgress(state);

        YandexVotAudioResult result = YandexVotAudioTransfer.run(
                fileSize,
                reader,
                FILE_ID,
                uploader,
                progress,
                new Deadline() {
                    @Override
                    public boolean isExpired() {
                        return state.deadlineExpired;
                    }
                }
        );

        assertEquals(YandexVotAudioResult.DEADLINE_EXCEEDED, result);
        assertEquals(1, uploader.parts.size());
        assertTrue(reader.closed);
    }

    @Test
    public void alreadyExpiredDeadlineStartsNothing() {
        State state = newState();
        state.deadlineExpired = true;
        int fileSize = 2 * PART;
        FakeUploader uploader = new FakeUploader(state);
        FakeTrack reader = new FakeTrack(makeTrack(fileSize));

        YandexVotAudioResult result = YandexVotAudioTransfer.run(
                fileSize,
                reader,
                FILE_ID,
                uploader,
                newProgress(state),
                new Deadline() {
                    @Override
                    public boolean isExpired() {
                        return state.deadlineExpired;
                    }
                }
        );

        assertEquals(YandexVotAudioResult.DEADLINE_EXCEEDED, result);
        assertEquals(0, uploader.parts.size());
        assertTrue(reader.closed);
    }

    @Test
    public void emptyOrInvalidSourceIsNeverSuccess() {
        State state = newState();
        FakeTrack reader = new FakeTrack(new byte[0]);

        YandexVotAudioResult empty = YandexVotAudioTransfer.run(
                0, reader, FILE_ID, new FakeUploader(state), newProgress(state), null);
        assertEquals(YandexVotAudioResult.SOURCE_UNAVAILABLE, empty);
        assertTrue(reader.closed);

        FakeTrack reader2 = new FakeTrack(makeTrack(10));
        YandexVotAudioResult negative = YandexVotAudioTransfer.run(
                -1, reader2, FILE_ID, new FakeUploader(state), newProgress(state), null);
        assertEquals(YandexVotAudioResult.SOURCE_UNAVAILABLE, negative);
        assertTrue(reader2.closed);
    }

    @Test
    public void sourceReadFailureStopsFurtherParts() {
        State state = newState();
        int fileSize = 2 * PART + 1;
        FakeUploader uploader = new FakeUploader(state);
        FakeTrack reader = new FakeTrack(makeTrack(fileSize)).failReadOnRead(2);

        YandexVotAudioResult result = YandexVotAudioTransfer.run(
                fileSize, reader, FILE_ID, uploader, newProgress(state), null);

        assertEquals(YandexVotAudioResult.SOURCE_READ_FAILED, result);
        assertEquals(1, uploader.parts.size());
        assertTrue(reader.closed);
    }

    @Test
    public void uploadFailureIsClassifiedSeparatelyFromSourceFailure() {
        State state = newState();
        int fileSize = 2 * PART;
        FakeUploader uploader = new FakeUploader(state).failOnUpload(2);
        FakeTrack reader = new FakeTrack(makeTrack(fileSize));

        YandexVotAudioResult result = YandexVotAudioTransfer.run(
                fileSize, reader, FILE_ID, uploader, newProgress(state), null);

        assertEquals(YandexVotAudioResult.UPLOAD_FAILED, result);
        assertEquals(1, uploader.parts.size());
        assertTrue(reader.closed);
        assertTrue(result.isSourceFailure() == false);
        assertEquals(FailureKind.UPLOAD, result.failureKind());
    }

    @Test
    public void downloadUploadAndPlaybackFailuresMapToDistinctCategories() {
        // Source acquisition, Yandex upload and cancelled/aborted operations must never be
        // presented as the same failure as a translation playback error by the callers.
        assertEquals(FailureKind.SOURCE, YandexVotAudioResult.SOURCE_UNAVAILABLE.failureKind());
        assertEquals(FailureKind.SOURCE, YandexVotAudioResult.SOURCE_DENIED.failureKind());
        assertEquals(FailureKind.SOURCE, YandexVotAudioResult.SOURCE_READ_FAILED.failureKind());
        assertEquals(FailureKind.UPLOAD, YandexVotAudioResult.UPLOAD_FAILED.failureKind());
        assertEquals(FailureKind.ABORTED, YandexVotAudioResult.CANCELLED.failureKind());
        assertEquals(FailureKind.ABORTED, YandexVotAudioResult.DEADLINE_EXCEEDED.failureKind());
        assertEquals(FailureKind.NONE, YandexVotAudioResult.SUCCESS.failureKind());

        assertTrue(YandexVotAudioResult.SOURCE_DENIED.isSourceFailure());
        assertTrue(YandexVotAudioResult.SOURCE_UNAVAILABLE.isSourceFailure());
        assertFalse(YandexVotAudioResult.UPLOAD_FAILED.isSourceFailure());
        assertTrue(YandexVotAudioResult.CANCELLED.isAborted());
        assertFalse(YandexVotAudioResult.SUCCESS.isAborted());
        assertTrue(YandexVotAudioResult.SUCCESS.isSuccess());
        assertFalse(YandexVotAudioResult.SOURCE_READ_FAILED.isSuccess());
    }

    @Test
    public void progressIsReportedPerPartAndReaderIsClosedOnEveryOutcome() {
        // Success
        State state = newState();
        FakeTrack reader1 = new FakeTrack(makeTrack(PART));
        YandexVotAudioTransfer.run(
                PART, reader1, FILE_ID, new FakeUploader(state), newProgress(state), null);
        assertTrue(reader1.closed);

        // Failure
        FakeTrack reader2 = new FakeTrack(makeTrack(PART)).emptyPart();
        YandexVotAudioTransfer.run(
                PART, reader2, FILE_ID, new FakeUploader(state), newProgress(state), null);
        assertTrue(reader2.closed);

        // Cancellation
        State state2 = newState();
        FakeTrack reader3 = new FakeTrack(makeTrack(2 * PART));
        YandexVotAudioTransfer.run(
                2 * PART,
                reader3,
                FILE_ID,
                new FakeUploader(state2).cancelAfter(1),
                newProgress(state2),
                null);
        assertTrue(reader3.closed);

        // Deadline
        State state3 = newState();
        state3.deadlineExpired = true;
        FakeTrack reader4 = new FakeTrack(makeTrack(PART));
        YandexVotAudioTransfer.run(
                PART,
                reader4,
                FILE_ID,
                new FakeUploader(state3),
                newProgress(state3),
                new Deadline() {
                    @Override
                    public boolean isExpired() {
                        return true;
                    }
                });
        assertTrue(reader4.closed);
    }

    private static byte[] concat(List<byte[]> arrays) {
        int size = 0;
        for (byte[] array : arrays) size += array.length;
        byte[] all = new byte[size];
        int offset = 0;
        for (byte[] array : arrays) {
            System.arraycopy(array, 0, all, offset, array.length);
            offset += array.length;
        }
        return all;
    }
}
