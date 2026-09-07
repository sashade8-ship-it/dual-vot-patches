/*
 * Copyright (C) 2026 Dual VoT contributors
 *
 * Licensed under the GNU General Public License v3.0.
 */

package app.morphe.extension.youtube.patches.voiceovertranslation.yandex;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import org.junit.After;
import org.junit.Test;

import java.util.HashMap;
import java.util.Map;

/** Covers request selection only; live Cronet authorization remains an APK/device gate. */
public class YandexVotPlayerMediaTransportTest {
    private static final String VIDEO_A = "A1b2C3d4E5_";
    private static final String VIDEO_B = "Z9y8X7w6V5_";

    @After
    public void clearSnapshots() {
        YandexVotPlayerMediaTransport.resetForTests();
    }

    @Test
    public void retainsExactBodylessPlayerAudioRequestAndHeaders() {
        Map<String, String> headers = new HashMap<>();
        headers.put("X-Goog-Visitor-Id", "runtime-only");
        headers.put("User-Agent", "YouTube");
        headers.put("not-a-string", null);
        String url = "https://r.example/videoplayback?id=" + VIDEO_A
                + "&itag=251&clen=12345";

        YandexVotPlayerMediaTransport.recordPlayerMediaRequest(url, 1, null, headers, VIDEO_A);

        YandexVotPlayerMediaTransport.Snapshot snapshot =
                YandexVotPlayerMediaTransport.find(VIDEO_A, 251);
        assertNotNull(snapshot);
        assertEquals(url, snapshot.url);
        assertEquals("YouTube", snapshot.headers.get("User-Agent"));
        assertEquals("runtime-only", snapshot.headers.get("X-Goog-Visitor-Id"));
        assertNull(snapshot.headers.get("not-a-string"));
    }

    @Test
    public void neverTreatsBodyCarryingSabrRequestAsRawAudio() {
        String url = "https://r.example/videoplayback?id=" + VIDEO_A + "&itag=251";

        YandexVotPlayerMediaTransport.recordPlayerMediaRequest(
                url, 2, new byte[] {1, 2}, new HashMap<>(), VIDEO_A);

        assertNull(YandexVotPlayerMediaTransport.find(VIDEO_A, 251));
    }

    @Test
    public void newVideoInvalidatesPreviousRequestCredentials() {
        YandexVotPlayerMediaTransport.recordPlayerMediaRequest(
                "https://r.example/videoplayback?id=" + VIDEO_A + "&itag=251",
                1, null, new HashMap<>(), VIDEO_A);
        YandexVotPlayerMediaTransport.recordPlayerMediaRequest(
                "https://r.example/videoplayback?id=" + VIDEO_B + "&itag=250",
                1, null, new HashMap<>(), VIDEO_B);

        assertNull(YandexVotPlayerMediaTransport.find(VIDEO_A, 251));
        assertNotNull(YandexVotPlayerMediaTransport.find(VIDEO_B, 250));
    }

    @Test
    public void rejectsNonMediaAndUnidentifiedRequests() {
        YandexVotPlayerMediaTransport.recordPlayerMediaRequest(
                "https://r.example/other?id=" + VIDEO_A + "&itag=251",
                1, null, new HashMap<>(), VIDEO_A);
        YandexVotPlayerMediaTransport.recordPlayerMediaRequest(
                "https://r.example/videoplayback?itag=251",
                1, null, new HashMap<>(), "not-a-video-id");

        assertNull(YandexVotPlayerMediaTransport.find(VIDEO_A, 251));
    }
}
