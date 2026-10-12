/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/3684
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to Morphe contributions.
 */

package app.morphe.extension.youtube.patches.utils.requests;

import androidx.annotation.Nullable;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.SocketTimeoutException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.BooleanSupplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.requests.Requester;
import app.morphe.extension.youtube.patches.utils.requests.ChannelSearchRequest.ChannelSearchResult;

/**
 * The uploads of a channel, from the playlist that lists all of them newest first,
 * and the uploads whose title has every word of a search.
 * <p>
 * The channel search stops after a different number of pages every time and leaves out
 * different videos, so a sort of its results never shows the same first videos twice.
 * The uploads playlist always lists every video, Short and live stream in the order they were
 * published, so it makes the results of a sort complete and sorts them by date exactly.
 */
public final class ChannelUploadsRequest {

    public static final class Uploads {
        /** Uploads whose title has the search, newest first. */
        public final List<ChannelSearchResult> matches;
        /** Position of every listed upload, from 0 for the newest one. */
        public final Map<String, Integer> positions;
        /** Whether the channel has more uploads than are listed, which are all older. */
        public final boolean isTruncated;

        /**
         * No uploads, until they are listed.
         */
        public Uploads() {
            this(Collections.emptyList(), Collections.emptyMap(), false);
        }

        private Uploads(List<ChannelSearchResult> matches, Map<String, Integer> positions, boolean isTruncated) {
            this.matches = matches;
            this.positions = positions;
            this.isTruncated = isTruncated;
        }
    }

    public interface Callback {
        /**
         * Called on a background thread with the uploads listed so far.
         *
         * @param isComplete Whether the playlist was listed, or could not be.
         */
        void onUploads(Uploads uploads, boolean isComplete);
    }

    /**
     * A number with digit groups, which are separated by a dot, comma, apostrophe or space
     * depending on the language, and are of two digits in some languages.
     */
    private static final Pattern PATTERN_NUMBER = Pattern.compile(
            "\\d{1,3}(?:[.,'’\\s\u00A0\u202F]\\d{2,3})+|\\d+");

    /**
     * Each page has 20 uploads, so only the largest channels are partly listed.
     */
    private static final int MAX_PAGES = 500;

    private static final Map<String, Uploads> cache = Collections.synchronizedMap(
            Utils.createSizeRestrictedMap(10));

    private ChannelUploadsRequest() {
    }

    /**
     * Calls back after each page that has more matching uploads, and once more when done.
     */
    public static void fetch(String channelId, String query, BooleanSupplier isCancelled, Callback callback) {
        Locale locale = Requester.getAppLocale();
        String key = channelId + "\n" + query + "\n" + locale;

        Uploads cached = cache.get(key);
        if (cached != null) {
            Utils.runOnBackgroundThread(() -> callback.onUploads(cached, true));
            return;
        }

        Utils.runOnBackgroundThread(() -> {
            Uploads uploads = fetchPlaylist(channelId, query, locale, isCancelled, callback);
            if (uploads == null) {
                callback.onUploads(new Uploads(), true);
            } else if (!isCancelled.getAsBoolean()) {
                cache.put(key, uploads);
                callback.onUploads(uploads, true);
            }
        });
    }

    @Nullable
    private static Uploads fetchPlaylist(String channelId, String query, Locale locale,
                                         BooleanSupplier isCancelled, Callback callback) {
        Utils.verifyOffMainThread();

        final long startTime = System.currentTimeMillis();
        List<String> queryWords = normalizedWords(query);
        List<ChannelSearchResult> matches = new ArrayList<>();
        Map<String, Integer> positions = new HashMap<>();

        try {
            // The uploads playlist of a channel is its id with the "UC" prefix replaced,
            // and is browsed like a channel.
            JSONObject page = browse(ChannelSearchRoutes.createChannelBody(
                    "VLUU" + channelId.substring(2), locale));
            JSONArray items = page == null ? null : playlistItems(page);
            if (items == null) {
                return null;
            }

            for (int pageNumber = 1; ; pageNumber++) {
                if (isCancelled.getAsBoolean()) {
                    return null;
                }

                String token = null;
                boolean foundMatches = false;
                for (int i = 0, length = items.length(); i < length; i++) {
                    JSONObject item = items.optJSONObject(i);
                    if (item == null) continue;

                    JSONObject continuation = item.optJSONObject("continuationItemViewModel");
                    if (continuation != null) {
                        token = continuationToken(continuation);
                        continue;
                    }

                    ChannelSearchResult result = parseLockup(item.optJSONObject("lockupViewModel"));
                    if (result == null || positions.containsKey(result.videoId)) continue;

                    positions.put(result.videoId, positions.size());
                    if (matches(result.title, queryWords)) {
                        matches.add(result);
                        foundMatches = true;
                    }
                }

                if (token == null) {
                    return new Uploads(matches, positions, false);
                }
                if (pageNumber == MAX_PAGES) {
                    return new Uploads(matches, positions, true);
                }
                if (foundMatches) {
                    callback.onUploads(new Uploads(new ArrayList<>(matches), new HashMap<>(positions), true), false);
                }

                page = browse(ChannelSearchRoutes.createContinuationBody(token, locale));
                items = page == null ? null : continuationItems(page);
                if (items == null) {
                    // A page that could not be fetched leaves the older uploads out.
                    return new Uploads(matches, positions, true);
                }
            }
        } catch (Exception ex) {
            Logger.printException(() -> "Channel uploads failed", ex);
        } finally {
            Logger.printDebug(() -> "Fetched channel uploads: " + positions.size() + " matching: " + query
                    + ", items=" + matches.size() + " took: " + (System.currentTimeMillis() - startTime) + "ms");
        }
        return null;
    }

    @Nullable
    private static JSONObject browse(byte[] requestBody) {
        try {
            HttpURLConnection connection = ChannelSearchRoutes.getConnection(ChannelSearchRoutes.CHANNEL_SEARCH);
            connection.setFixedLengthStreamingMode(requestBody.length);
            connection.getOutputStream().write(requestBody);

            final int responseCode = connection.getResponseCode();
            if (responseCode != Requester.HTTP_STATUS_CODE_SUCCESS) {
                String error = Requester.parseErrorStringAndDisconnect(connection);
                Logger.printDebug(() -> "Channel uploads failed with code: " + responseCode + " error: " + error);
                return null;
            }

            return Requester.parseJSONObject(connection);
        } catch (SocketTimeoutException ex) {
            Logger.printInfo(() -> "Connection timeout", ex);
        } catch (IOException ex) {
            Logger.printInfo(() -> "Network error", ex);
        } catch (Exception ex) {
            Logger.printException(() -> "Channel uploads page failed", ex);
        }
        return null;
    }

    @Nullable
    private static JSONArray playlistItems(JSONObject json) {
        JSONObject contents = json.optJSONObject("contents");
        JSONObject singleColumn = contents == null ? null
                : contents.optJSONObject("singleColumnBrowseResultsRenderer");
        JSONArray tabs = singleColumn == null ? null : singleColumn.optJSONArray("tabs");
        JSONObject tab = tabs == null ? null : tabs.optJSONObject(0);
        JSONObject tabRenderer = tab == null ? null : tab.optJSONObject("tabRenderer");
        JSONObject content = tabRenderer == null ? null : tabRenderer.optJSONObject("content");
        JSONObject sectionList = content == null ? null : content.optJSONObject("sectionListRenderer");
        JSONArray sections = sectionList == null ? null : sectionList.optJSONArray("contents");
        if (sections == null) {
            return null;
        }

        for (int i = 0, length = sections.length(); i < length; i++) {
            JSONObject section = sections.optJSONObject(i);
            JSONObject itemSection = section == null ? null : section.optJSONObject("itemSectionRenderer");
            JSONArray items = itemSection == null ? null : itemSection.optJSONArray("contents");
            if (items != null) {
                return items;
            }
        }
        return null;
    }

    @Nullable
    private static JSONArray continuationItems(JSONObject json) {
        JSONArray actions = json.optJSONArray("onResponseReceivedActions");
        if (actions == null) {
            return null;
        }

        for (int i = 0, length = actions.length(); i < length; i++) {
            JSONObject action = actions.optJSONObject(i);
            JSONObject append = action == null ? null : action.optJSONObject("appendContinuationItemsAction");
            if (append != null) {
                return append.optJSONArray("continuationItems");
            }
        }
        return null;
    }

    @Nullable
    private static String continuationToken(JSONObject continuationItemViewModel) {
        JSONObject continuationCommand = continuationItemViewModel.optJSONObject("continuationCommand");
        JSONObject command = continuationCommand == null ? null
                : continuationCommand.optJSONObject("innertubeCommand");
        JSONObject continuation = command == null ? null : command.optJSONObject("continuationCommand");
        String token = continuation == null ? "" : continuation.optString("token");
        return token.isEmpty() ? null : token;
    }

    @Nullable
    private static ChannelSearchResult parseLockup(@Nullable JSONObject lockup) {
        if (lockup == null) {
            return null;
        }

        String videoId = lockup.optString("contentId");
        JSONObject metadata = lockup.optJSONObject("metadata");
        JSONObject lockupMetadata = metadata == null ? null : metadata.optJSONObject("lockupMetadataViewModel");
        String title = lockupMetadata == null ? "" : textContent(lockupMetadata.optJSONObject("title"));
        if (videoId.isEmpty() || title.isEmpty()) {
            return null;
        }

        JSONObject contentImage = lockup.optJSONObject("contentImage");
        JSONObject thumbnail = contentImage == null ? null : contentImage.optJSONObject("thumbnailViewModel");
        List<String> details = parseDetails(lockupMetadata);

        return ChannelSearchRequest.createResult(videoId, title, parseThumbnail(thumbnail),
                parseLength(thumbnail), details, parseViewCount(lockup, title, details));
    }

    /**
     * The view count shown under the title is rounded, such as "6.9M views", but the label
     * read aloud has the exact one. Both are localized and the label has other numbers too,
     * so the exact count is the first number after the title that starts with the same digits
     * as the rounded one.
     *
     * @return The exact view count, or -1 if it is not known.
     */
    private static long parseViewCount(JSONObject lockup, String title, List<String> details) {
        if (details.isEmpty()) {
            return -1;
        }

        Matcher roundedMatcher = PATTERN_NUMBER.matcher(details.get(0));
        if (!roundedMatcher.find()) {
            return -1;
        }
        String roundedDigits = digits(roundedMatcher.group());

        JSONObject rendererContext = lockup.optJSONObject("rendererContext");
        JSONObject accessibility = rendererContext == null ? null
                : rendererContext.optJSONObject("accessibilityContext");
        String label = accessibility == null ? "" : accessibility.optString("label");
        final int titleIndex = label.indexOf(title);
        if (titleIndex >= 0) {
            label = label.substring(titleIndex + title.length());
        }

        Matcher matcher = PATTERN_NUMBER.matcher(label);
        while (matcher.find()) {
            String exactDigits = digits(matcher.group());
            if (exactDigits.startsWith(roundedDigits)) {
                try {
                    return Long.parseLong(exactDigits);
                } catch (NumberFormatException ex) {
                    return -1;
                }
            }
        }
        return -1;
    }

    private static String digits(String number) {
        return number.replaceAll("\\D", "");
    }

    private static String parseThumbnail(@Nullable JSONObject thumbnail) {
        JSONObject image = thumbnail == null ? null : thumbnail.optJSONObject("image");
        return image == null ? "" : ChannelSearchRequest.widestThumbnail(image.optJSONArray("sources"));
    }

    /**
     * @return The length badge on the thumbnail, such as "17:31".
     */
    private static String parseLength(@Nullable JSONObject thumbnail) {
        JSONArray overlays = thumbnail == null ? null : thumbnail.optJSONArray("overlays");
        if (overlays == null) {
            return "";
        }

        for (int i = 0, length = overlays.length(); i < length; i++) {
            JSONObject overlay = overlays.optJSONObject(i);
            JSONObject bottomOverlay = overlay == null ? null
                    : overlay.optJSONObject("thumbnailBottomOverlayViewModel");
            JSONArray badges = bottomOverlay == null ? null : bottomOverlay.optJSONArray("badges");
            JSONObject badge = badges == null ? null : badges.optJSONObject(0);
            JSONObject badgeViewModel = badge == null ? null : badge.optJSONObject("thumbnailBadgeViewModel");
            if (badgeViewModel != null) {
                return badgeViewModel.optString("text");
            }
        }
        return "";
    }

    /**
     * @return The view count and the age of the upload, as shown under the title.
     *         The first row is the channel name, which is left out.
     */
    private static List<String> parseDetails(JSONObject lockupMetadata) {
        List<String> details = new ArrayList<>();
        JSONObject metadata = lockupMetadata.optJSONObject("metadata");
        JSONObject contentMetadata = metadata == null ? null : metadata.optJSONObject("contentMetadataViewModel");
        JSONArray rows = contentMetadata == null ? null : contentMetadata.optJSONArray("metadataRows");
        if (rows == null) {
            return details;
        }

        for (int i = 1, length = rows.length(); i < length; i++) {
            JSONObject row = rows.optJSONObject(i);
            JSONArray parts = row == null ? null : row.optJSONArray("metadataParts");
            if (parts == null) continue;

            for (int j = 0, partsLength = parts.length(); j < partsLength; j++) {
                JSONObject part = parts.optJSONObject(j);
                String text = part == null ? "" : textContent(part.optJSONObject("text"));
                if (!text.isEmpty()) {
                    details.add(text);
                }
            }
        }
        return details;
    }

    private static String textContent(@Nullable JSONObject text) {
        return text == null ? "" : text.optString("content");
    }

    /**
     * Lower case words without accents, so a search matches titles written with or without them.
     */
    private static List<String> normalizedWords(String text) {
        List<String> words = new ArrayList<>();
        for (String word : normalize(text).split("\\s+")) {
            if (!word.isEmpty()) {
                words.add(word);
            }
        }
        return words;
    }

    private static String normalize(String text) {
        return Utils.normalizeTextToLowercase(text).replace('’', '\'');
    }

    /**
     * @return Whether the text has every word of the search.
     */
    public static boolean matches(String text, String query) {
        return matches(text, normalizedWords(query));
    }

    private static boolean matches(String text, List<String> queryWords) {
        if (queryWords.isEmpty()) {
            return false;
        }

        String normalizedText = normalize(text);
        for (String word : queryWords) {
            if (!normalizedText.contains(word)) {
                return false;
            }
        }
        return true;
    }
}
