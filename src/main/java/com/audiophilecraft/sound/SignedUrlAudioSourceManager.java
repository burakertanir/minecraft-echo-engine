package com.audiophilecraft.sound;

import com.sedmelluq.discord.lavaplayer.container.MediaContainerDetection;
import com.sedmelluq.discord.lavaplayer.container.MediaContainerDetectionResult;
import com.sedmelluq.discord.lavaplayer.container.MediaContainerRegistry;
import com.sedmelluq.discord.lavaplayer.player.AudioPlayerManager;
import com.sedmelluq.discord.lavaplayer.source.AudioSourceManager;
import com.sedmelluq.discord.lavaplayer.tools.FriendlyException;
import com.sedmelluq.discord.lavaplayer.tools.io.SeekableInputStream;
import com.sedmelluq.discord.lavaplayer.track.AudioItem;
import com.sedmelluq.discord.lavaplayer.track.AudioReference;
import com.sedmelluq.discord.lavaplayer.track.AudioTrack;
import com.sedmelluq.discord.lavaplayer.track.AudioTrackInfo;
import com.sedmelluq.discord.lavaplayer.track.info.AudioTrackInfoProvider;
import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.util.List;
import java.util.Locale;

/**
 * Loads YouTube stream URLs (the signed {@code googlevideo.com} URLs produced
 * by {@link YtDlpUrlResolver}) using the JVM's own HTTP stack and lavaplayer's
 * container registry. lavaplayer's bundled {@code HttpAudioSourceManager}
 * relies on Apache HttpClient, which gets stalled/blocked by YouTube's video
 * servers; plain {@link HttpURLConnection} works reliably against them.
 */
public final class SignedUrlAudioSourceManager implements AudioSourceManager {
    private static final long SEEK_SKIP_LIMIT = 128L * 1024L;

    @Override
    public String getSourceName() {
        return "audiophilecraft-signed-url";
    }

    @Override
    public AudioItem loadItem(AudioPlayerManager manager, AudioReference reference) {
        if (reference == null || reference.identifier == null || !isSignedUrl(reference.identifier)) {
            return null;
        }
        try {
            HttpSeekableStream stream = HttpSeekableStream.open(reference.identifier);
            MediaContainerDetection detection = new MediaContainerDetection(
                    MediaContainerRegistry.DEFAULT_REGISTRY,
                    reference,
                    stream,
                    com.sedmelluq.discord.lavaplayer.container.MediaContainerHints.from("audio/webm", "webm"));
            MediaContainerDetectionResult result = detection.detectContainer();
            if (!result.isContainerDetected() || !result.isSupportedFile()) {
                stream.close();
                throw new FriendlyException(
                        "Unsupported audio container in stream URL", FriendlyException.Severity.COMMON, null);
            }
            stream.seek(0);
            return result.getContainerDescriptor().createTrack(result.getTrackInfo(), stream);
        } catch (FriendlyException e) {
            throw e;
        } catch (Exception e) {
            throw new FriendlyException(
                    "Failed to open stream URL: " + e.getMessage(), FriendlyException.Severity.COMMON, e);
        }
    }

    private static boolean isSignedUrl(String url) {
        String lower = url.toLowerCase(Locale.ROOT);
        return lower.startsWith("https://") && lower.contains("googlevideo.com");
    }

    @Override
    public boolean isTrackEncodable(AudioTrack track) {
        return false;
    }

    @Override
    public void encodeTrack(AudioTrack track, DataOutput output) {
        throw new UnsupportedOperationException();
    }

    @Override
    public AudioTrack decodeTrack(AudioTrackInfo trackInfo, DataInput input) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void shutdown() {}

    /** A seekable view of an HTTP resource backed by the JVM HTTP stack. */
    static final class HttpSeekableStream extends SeekableInputStream {
        private static final long CHUNK_SIZE = 1024L * 1024L; // 1 MiB

        private final String url;
        private HttpURLConnection connection;
        private InputStream input;
        private long position;
        private long chunkEnd;
        private long totalLength = -1L;

        private HttpSeekableStream(String url) {
            super(-1L, SEEK_SKIP_LIMIT);
            this.url = url;
        }

        static HttpSeekableStream open(String url) throws IOException {
            HttpSeekableStream stream = new HttpSeekableStream(url);
            stream.reconnect(0L);
            return stream;
        }

        private void reconnect(long fromPosition) throws IOException {
            if (connection != null) connection.disconnect();
            URL target = URI.create(url).toURL();
            connection = (HttpURLConnection) target.openConnection();
            connection.setRequestMethod("GET");
            connection.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36");
            // YouTube's stream hosts return 403 to open-ended ranges; they only
            // serve bounded ranges like "bytes=0-1048575".
            long end = fromPosition + CHUNK_SIZE - 1L;
            connection.setRequestProperty("Range", "bytes=" + fromPosition + "-" + end);
            connection.setConnectTimeout(15_000);
            connection.setReadTimeout(60_000);
            int code = connection.getResponseCode();
            if (code != HttpURLConnection.HTTP_OK && code != HttpURLConnection.HTTP_PARTIAL) {
                throw new IOException("HTTP " + code + " from stream host");
            }
            String contentRange = connection.getHeaderField("Content-Range");
            if (contentRange != null && contentRange.contains("/")) {
                String total = contentRange
                        .substring(contentRange.lastIndexOf('/') + 1)
                        .trim();
                try {
                    totalLength = Long.parseLong(total);
                } catch (NumberFormatException ignored) {
                    // keep -1 (unknown)
                }
            }
            if (totalLength >= 0L) {
                end = Math.min(end, totalLength - 1L);
                contentLength = totalLength;
            }
            chunkEnd = end;
            input = connection.getInputStream();
            position = fromPosition;
        }

        private boolean needsNextChunk() {
            return position > chunkEnd && (totalLength < 0L || position < totalLength);
        }

        @Override
        public long getPosition() {
            return position;
        }

        @Override
        protected void seekHard(long targetPosition) throws IOException {
            if (targetPosition == position) return;
            if (targetPosition < 0L) throw new IOException("Negative seek target: " + targetPosition);
            reconnect(targetPosition);
        }

        @Override
        public boolean canSeekHard() {
            return true;
        }

        @Override
        public List<AudioTrackInfoProvider> getTrackInfoProviders() {
            return List.of();
        }

        @Override
        public int read() throws IOException {
            int value = input.read();
            if (value < 0 && needsNextChunk()) {
                reconnect(position);
                value = input.read();
            }
            if (value >= 0) position++;
            return value;
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            int read = input.read(buffer, offset, length);
            if (read < 0 && needsNextChunk()) {
                reconnect(position);
                read = input.read(buffer, offset, length);
            }
            if (read > 0) position += read;
            return read;
        }

        @Override
        public long skip(long amount) throws IOException {
            long skipped = input.skip(amount);
            position += skipped;
            return skipped;
        }

        @Override
        public int available() throws IOException {
            return input.available();
        }

        private long markPosition = -1L;

        @Override
        public void mark(int readLimit) {
            markPosition = position;
        }

        @Override
        public void reset() throws IOException {
            if (markPosition < 0L) throw new IOException("mark not set");
            long target = markPosition;
            markPosition = -1L;
            seek(target);
        }

        @Override
        public void close() throws IOException {
            if (connection != null) connection.disconnect();
        }
    }
}
