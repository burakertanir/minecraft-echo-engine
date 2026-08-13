package com.audiophilecraft.util;

import com.audiophilecraft.AudiophileCraft;
import com.audiophilecraft.sound.InternetAudioLoader;
import com.sedmelluq.discord.lavaplayer.track.AudioTrack;
import com.sedmelluq.discord.lavaplayer.track.AudioTrackInfo;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

public class YouTubeSearcher {

    private static final Pattern VIDEO_ID_PATTERN = Pattern.compile("[A-Za-z0-9_-]{11}");
    private static final int MAX_RESULTS = 5;
    private static final long SEARCH_TIMEOUT_MS = 15000L;

    public static class SearchResult {
        public final String videoId;
        public final String title;
        public final String channel;
        public final String duration;

        public SearchResult(String videoId, String title, String channel, String duration) {
            this.videoId = videoId;
            this.title = title;
            this.channel = channel;
            this.duration = duration;
        }
    }

    /**
     * Search YouTube for a query. Uses LavaPlayer's InnerTube search
     * ("ytsearch:") first — the same API path used for playback — and falls
     * back to direct HTML scraping only if the API path yields nothing.
     */
    public static List<SearchResult> search(String query) {
        List<SearchResult> results = searchViaLavaPlayer(query);
        if (results.isEmpty()) {
            results = searchViaHtmlScrape(query);
        }
        return results;
    }

    private static List<SearchResult> searchViaLavaPlayer(String query) {
        List<SearchResult> results = new ArrayList<>();
        if (query == null || query.trim().isEmpty()) return results;

        String trimmed = query.trim();
        String identifier;
        if (trimmed.startsWith("ytsearch:")
                || trimmed.startsWith("http")
                || VIDEO_ID_PATTERN.matcher(trimmed).matches()) {
            identifier = trimmed;
        } else {
            identifier = "ytsearch:" + trimmed;
        }

        try {
            List<AudioTrack> tracks = InternetAudioLoader.getInstance().loadItemBlocking(identifier, SEARCH_TIMEOUT_MS);
            for (AudioTrack track : tracks) {
                if (results.size() >= MAX_RESULTS) break;
                String videoId = extractVideoId(track);
                if (videoId == null) continue;
                AudioTrackInfo info = track.getInfo();
                results.add(new SearchResult(videoId, info.title, info.author, formatDuration(info.length)));
            }
        } catch (Exception e) {
            AudiophileCraft.LOGGER.warn("LavaPlayer search failed for query '{}'.", query, e);
        }
        return results;
    }

    private static String extractVideoId(AudioTrack track) {
        AudioTrackInfo info = track.getInfo();
        String identifier = info.identifier;
        if (identifier != null && VIDEO_ID_PATTERN.matcher(identifier).matches()) return identifier;
        String uri = info.uri;
        if (uri != null) {
            int eq = uri.indexOf("v=");
            if (eq != -1) {
                int end = uri.indexOf('&', eq);
                String id = uri.substring(eq + 2, end == -1 ? uri.length() : end);
                if (VIDEO_ID_PATTERN.matcher(id).matches()) return id;
            }
        }
        return null;
    }

    private static String formatDuration(long lengthMs) {
        if (lengthMs <= 0) return "";
        long totalSec = lengthMs / 1000;
        long hours = totalSec / 3600;
        long minutes = (totalSec % 3600) / 60;
        long seconds = totalSec % 60;
        if (hours > 0) return String.format("%d:%02d:%02d", hours, minutes, seconds);
        return String.format("%d:%02d", minutes, seconds);
    }

    /**
     * Legacy fallback: scrape the results page HTML and parse ytInitialData.
     * Only used when the InnerTube search path returns no results.
     */
    private static List<SearchResult> searchViaHtmlScrape(String query) {
        List<SearchResult> results = new ArrayList<>();
        try {
            String encodedQuery = URLEncoder.encode(query, StandardCharsets.UTF_8.toString());
            URL url = new URL("https://www.youtube.com/results?search_query=" + encodedQuery);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(8000);
            conn.setReadTimeout(8000);
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36");
            conn.setRequestProperty("Accept-Language", "en-US,en;q=0.9");

            StringBuilder response = new StringBuilder();
            try (BufferedReader in =
                    new BufferedReader(new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = in.readLine()) != null) {
                    response.append(line);
                }
            }

            String html = response.toString();
            String startMarker = "var ytInitialData = ";
            int startIndex = html.indexOf(startMarker);
            if (startIndex == -1) return results;

            int endIndex = html.indexOf(";</script>", startIndex);
            if (endIndex == -1) return results;

            String json = html.substring(startIndex + startMarker.length(), endIndex);

            int cursor = 0;
            while (results.size() < MAX_RESULTS) {
                int rendererIdx = json.indexOf("\"videoRenderer\":{", cursor);
                if (rendererIdx == -1) break;

                int nextRendererIdx = json.indexOf("\"videoRenderer\":{", rendererIdx + 17);
                int maxBoundary = nextRendererIdx != -1 ? nextRendererIdx : json.length();

                cursor = rendererIdx + 17;

                String videoId = extractBoundedJsonValue(json, "\"videoId\":\"", "\"", rendererIdx, maxBoundary);
                if (videoId == null) continue;

                String title = extractBoundedJsonValue(
                        json, "\"title\":{\"runs\":[{\"text\":\"", "\"", rendererIdx, maxBoundary);
                if (title == null) continue;

                String channel = extractBoundedJsonValue(
                        json, "\"shortBylineText\":{\"runs\":[{\"text\":\"", "\"", rendererIdx, maxBoundary);
                if (channel == null)
                    channel = extractBoundedJsonValue(
                            json, "\"longBylineText\":{\"runs\":[{\"text\":\"", "\"", rendererIdx, maxBoundary);
                if (channel == null) channel = "YouTube";

                String duration = extractBoundedJsonValue(json, "\"simpleText\":\"", "\"", rendererIdx, maxBoundary);
                if (duration == null) {
                    // Fallback check
                    duration = extractBoundedJsonValue(
                            json,
                            "\"lengthText\":{\"accessibility\":{\"accessibilityData\":{\"label\":\"",
                            "\"",
                            rendererIdx,
                            maxBoundary);
                    if (duration == null) duration = "";
                }

                results.add(new SearchResult(
                        videoId, cleanJsonString(title), cleanJsonString(channel), cleanJsonString(duration)));
            }

        } catch (Exception e) {
            AudiophileCraft.LOGGER.warn("YouTube search failed for query '{}'.", query, e);
        }
        return results;
    }

    private static String extractBoundedJsonValue(
            String json, String startKey, String endKey, int startSearchFrom, int maxBoundary) {
        int startIdx = json.indexOf(startKey, startSearchFrom);
        if (startIdx == -1 || startIdx > maxBoundary) return null;
        startIdx += startKey.length();

        int endIdx = startIdx;
        while (endIdx < maxBoundary && endIdx < json.length()) {
            if (json.startsWith(endKey, endIdx)) {
                if (endIdx > 0 && json.charAt(endIdx - 1) == '\\') {
                    endIdx++;
                } else {
                    break;
                }
            } else {
                endIdx++;
            }
        }

        if (endIdx >= maxBoundary || endIdx >= json.length()) return null;
        return json.substring(startIdx, endIdx);
    }

    private static String cleanJsonString(String str) {
        if (str == null) return null;
        str = str.replace("\\\"", "\"").replace("\\/", "/").replace("\\\\", "\\");

        StringBuilder sb = new StringBuilder();
        int i = 0;
        while (i < str.length()) {
            if (str.charAt(i) == '\\' && i + 5 < str.length() && str.charAt(i + 1) == 'u') {
                try {
                    int code = Integer.parseInt(str.substring(i + 2, i + 6), 16);
                    sb.append((char) code);
                    i += 6;
                    continue;
                } catch (NumberFormatException ignored) {
                }
            }
            sb.append(str.charAt(i));
            i++;
        }
        return sb.toString();
    }
}
