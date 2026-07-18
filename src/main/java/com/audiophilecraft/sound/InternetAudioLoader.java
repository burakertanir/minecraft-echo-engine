package com.audiophilecraft.sound;

import com.sedmelluq.discord.lavaplayer.format.StandardAudioDataFormats;
import com.sedmelluq.discord.lavaplayer.player.AudioLoadResultHandler;
import com.sedmelluq.discord.lavaplayer.player.AudioPlayerManager;
import com.sedmelluq.discord.lavaplayer.player.DefaultAudioPlayerManager;
import com.sedmelluq.discord.lavaplayer.source.http.HttpAudioSourceManager;
import com.sedmelluq.discord.lavaplayer.source.soundcloud.SoundCloudAudioSourceManager;
import com.sedmelluq.discord.lavaplayer.tools.FriendlyException;
import com.sedmelluq.discord.lavaplayer.track.AudioPlaylist;
import com.sedmelluq.discord.lavaplayer.track.AudioTrack;
import com.sedmelluq.discord.lavaplayer.track.AudioTrackInfo;
import com.sedmelluq.discord.lavaplayer.track.playback.AudioFrame;
import dev.lavalink.youtube.YoutubeAudioSourceManager;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Internet Audio Loader — LavaPlayer integration for URL-based music streaming.
 * Supports: YouTube (via youtube-source plugin), SoundCloud, HTTP direct links.
 * Decodes audio to mono PCM 16-bit at native sample rate for our OpenAL
 * pipeline.
 */
public class InternetAudioLoader {

    private static InternetAudioLoader INSTANCE;
    private final AudioPlayerManager playerManager;
    private final AtomicLong nextStreamingRequestId = new AtomicLong(1L);

    private InternetAudioLoader() {
        // Force IPv4 to prevent Java network hangs on Windows (IPv6 timeouts)
        System.setProperty("java.net.preferIPv4Stack", "true");

        playerManager = new DefaultAudioPlayerManager();
        // CRITICAL: Set output format to raw PCM (not Opus!)
        // LavaPlayer defaults to Opus which is encoded — we need raw PCM for OpenAL
        playerManager.getConfiguration().setOutputFormat(StandardAudioDataFormats.COMMON_PCM_S16_BE);
        // Register YouTube source using the dedicated plugin (not the deprecated
        // built-in one)
        playerManager.registerSourceManager(new YoutubeAudioSourceManager());
        // Register other remote sources (SoundCloud, HTTP, etc.)
        playerManager.registerSourceManager(SoundCloudAudioSourceManager.createDefault());
        playerManager.registerSourceManager(new HttpAudioSourceManager());
    }

    public static synchronized InternetAudioLoader getInstance() {
        if (INSTANCE == null) {
            INSTANCE = new InternetAudioLoader();
        }
        return INSTANCE;
    }

    /**
     * Callback interface for async track loading.
     * pcmInterleaved is [L,R,L,R,...] for stereo sources, or [M,M,M,...] for mono.
     */
    public interface TrackLoadCallback {
        void onTrackLoaded(short[] pcmInterleaved, int sampleRate, String trackTitle);

        void onFailed(String reason);
    }

    public interface StreamingCallback {
        void onReady(
                long requestId,
                short[] pcmInterleaved,
                int decodedFrames,
                int totalExpectedFrames,
                int sampleRate,
                String title);

        void onMoreData(long requestId, int totalDecoded);

        void onComplete(long requestId);

        void onFailed(long requestId, String reason);
    }

    /**
     * Load and decode audio from a URL asynchronously.
     * The entire track is downloaded & decoded to PCM, then passed to the callback.
     * This runs on LavaPlayer's internal thread pool, NOT on the main/render
     * thread.
     *
     * @param url      URL or search query (e.g. "ytsearch:song name")
     * @param callback Called on completion or failure
     */
    public void loadTrack(String url, TrackLoadCallback callback) {

        playerManager.loadItem(url, new AudioLoadResultHandler() {
            @Override
            public void trackLoaded(AudioTrack track) {
                // Decode on a separate thread to avoid blocking
                CompletableFuture.runAsync(() -> decodeTrack(track, callback));
            }

            @Override
            public void playlistLoaded(AudioPlaylist playlist) {
                // For playlists, just play the first track
                if (playlist.getTracks().isEmpty()) {
                    callback.onFailed("Playlist is empty");
                    return;
                }

                AudioTrack selected = playlist.getSelectedTrack();
                if (selected == null) {
                    selected = playlist.getTracks().get(0);
                }

                final AudioTrack finalTrack = selected;
                CompletableFuture.runAsync(() -> decodeTrack(finalTrack, callback));
            }

            @Override
            public void noMatches() {
                System.err.println("InternetAudioLoader: No matches found for: " + url);
                callback.onFailed("No matches found for: " + url);
            }

            @Override
            public void loadFailed(FriendlyException exception) {
                System.err.println("InternetAudioLoader: Load failed: " + exception.getMessage());
                callback.onFailed("Load failed: " + exception.getMessage());
            }
        });
    }

    public long loadTrackStreaming(String url, StreamingCallback callback) {
        long requestId = nextStreamingRequestId.getAndIncrement();
        playerManager.loadItem(url, new AudioLoadResultHandler() {
            @Override
            public void trackLoaded(AudioTrack track) {
                CompletableFuture.runAsync(() -> decodeTrackStreaming(requestId, track, callback));
            }

            @Override
            public void playlistLoaded(AudioPlaylist playlist) {
                if (playlist.getTracks().isEmpty()) {
                    callback.onFailed(requestId, "Playlist empty");
                    return;
                }
                AudioTrack s = playlist.getSelectedTrack();
                if (s == null) s = playlist.getTracks().get(0);
                final AudioTrack t = s;
                CompletableFuture.runAsync(() -> decodeTrackStreaming(requestId, t, callback));
            }

            @Override
            public void noMatches() {
                callback.onFailed(requestId, "No matches: " + url);
            }

            @Override
            public void loadFailed(FriendlyException ex) {
                callback.onFailed(requestId, "Load failed: " + ex.getMessage());
            }
        });
        return requestId;
    }

    private void decodeTrackStreaming(long requestId, AudioTrack track, StreamingCallback callback) {
        int sampleRate = 48000;
        int channels = 2;
        int totalDecoded = 0;
        short[] pcmInterleaved = null;
        int prebufferTarget;
        String title = track.getInfo().title;
        var player = playerManager.createPlayer();
        try {
            player.playTrack(track.makeClone());
            AudioFrame first = player.provide(5000, java.util.concurrent.TimeUnit.MILLISECONDS);
            if (first != null && first.getFormat() != null) {
                sampleRate = first.getFormat().sampleRate;
                channels = first.getFormat().channelCount;
            }
            prebufferTarget = 10 * sampleRate;
            long durMs = track.getDuration();
            if (durMs <= 0 || durMs > 5 * 60 * 1000) durMs = 5 * 60 * 1000;
            int totalExpected = (int) (durMs / 1000.0 * sampleRate);
            // Interleaved stereo: [L0,R0, L1,R1, L2,R2, ...], length = frames * 2
            pcmInterleaved = new short[totalExpected * 2];
            if (first != null && first.getData() != null && first.getData().length > 0) {
                totalDecoded += copyFrameToPcm(first, pcmInterleaved, totalDecoded, channels);
            }
            int timeouts = 0;
            while (totalDecoded < prebufferTarget) {
                AudioFrame f = player.provide(5000, java.util.concurrent.TimeUnit.MILLISECONDS);
                if (f == null || f.getData() == null || f.getData().length == 0) {
                    if (player.getPlayingTrack() == null) break;
                    timeouts++;
                    if (timeouts > 4) {
                        throw new RuntimeException("Stream buffering timed out! (Network/IPv6 or Codec issue)");
                    }
                    continue;
                }
                timeouts = 0;
                totalDecoded += copyFrameToPcm(f, pcmInterleaved, totalDecoded, channels);
            }
            if (totalDecoded == 0) {
                throw new RuntimeException("0 samples decoded. Stream failed to start.");
            }
            final int prebuffered = totalDecoded;
            final int finalSR = sampleRate;
            final short[] pcmFinal = pcmInterleaved;
            net.minecraft.client.MinecraftClient.getInstance()
                    .execute(() -> callback.onReady(requestId, pcmFinal, prebuffered, totalExpected, finalSR, title));
            while (true) {
                AudioFrame f = player.provide(5000, java.util.concurrent.TimeUnit.MILLISECONDS);
                if (f == null) {
                    if (player.getPlayingTrack() == null) break;
                    f = player.provide(10000, java.util.concurrent.TimeUnit.MILLISECONDS);
                    if (f == null) break;
                }
                if (f.getData() == null || f.getData().length == 0) continue;
                totalDecoded += copyFrameToPcm(f, pcmInterleaved, totalDecoded, channels);
                final int td = totalDecoded;
                net.minecraft.client.MinecraftClient.getInstance().execute(() -> callback.onMoreData(requestId, td));
                if (totalDecoded >= totalExpected) break;
            }
            net.minecraft.client.MinecraftClient.getInstance().execute(() -> callback.onComplete(requestId));
        } catch (Throwable e) {
            System.err.println("CRITICAL DECODE ERROR: " + e.toString());
            e.printStackTrace();
            net.minecraft.client.MinecraftClient.getInstance()
                    .execute(() -> callback.onFailed(requestId, e.toString()));
        } finally {
            player.destroy();
        }
    }

    /**
     * Copy a decoded audio frame into the interleaved PCM array.
     * @return number of frames (not shorts) written
     */
    private int copyFrameToPcm(AudioFrame frame, short[] dest, int offset, int channels) {
        byte[] data = frame.getData();
        if (data == null) return 0;
        int frames = data.length / (2 * channels);
        // Clamp to destination capacity to avoid ArrayIndexOutOfBounds
        int maxFrames = dest.length / channels - offset;
        if (maxFrames <= 0) return 0;
        if (frames > maxFrames) frames = maxFrames;
        java.nio.ByteBuffer.wrap(data)
                .order(java.nio.ByteOrder.BIG_ENDIAN)
                .asShortBuffer()
                .get(dest, offset * channels, frames * channels);
        return frames;
    }

    /**
     * Decode an AudioTrack to interleaved stereo PCM.
     * This blocks until the entire track is decoded.
     */
    private void decodeTrack(AudioTrack track, TrackLoadCallback callback) {
        try {
            AudioTrackInfo info = track.getInfo();
            String title = info.title;

            int sampleRate = 48000;
            int channels = 2;

            long durationMs = track.getDuration();
            if (durationMs <= 0 || durationMs > 20 * 60 * 1000) {
                durationMs = 20 * 60 * 1000;
            }

            int estimatedSamples = (int) ((durationMs / 1000.0) * sampleRate * channels);
            java.util.List<short[]> chunks = new java.util.ArrayList<>();
            int totalSamples = 0;

            var player = playerManager.createPlayer();
            try {
                player.playTrack(track);

                AudioFrame firstFrame = player.provide(5000, java.util.concurrent.TimeUnit.MILLISECONDS);
                if (firstFrame != null && firstFrame.getFormat() != null) {
                    sampleRate = firstFrame.getFormat().sampleRate;
                    channels = firstFrame.getFormat().channelCount;
                    byte[] data = firstFrame.getData();
                    if (data != null && data.length > 0) {
                        short[] samples = new short[data.length / 2];
                        ByteBuffer.wrap(data)
                                .order(ByteOrder.BIG_ENDIAN)
                                .asShortBuffer()
                                .get(samples);
                        chunks.add(samples);
                        totalSamples += samples.length;
                    }
                }

                // Read remaining frames
                while (true) {
                    AudioFrame frame = player.provide(5000, java.util.concurrent.TimeUnit.MILLISECONDS);

                    if (frame == null) {
                        if (player.getPlayingTrack() == null) break;
                        frame = player.provide(10000, java.util.concurrent.TimeUnit.MILLISECONDS);
                        if (frame == null) {
                            break;
                        }
                    }

                    byte[] data = frame.getData();
                    if (data == null || data.length == 0) continue;

                    // Convert bytes to shorts (BIG-ENDIAN — LavaPlayer PCM_S16_BE format)
                    short[] samples = new short[data.length / 2];
                    ByteBuffer.wrap(data)
                            .order(ByteOrder.BIG_ENDIAN)
                            .asShortBuffer()
                            .get(samples);

                    chunks.add(samples);
                    totalSamples += samples.length;
                }
            } finally {
                player.destroy();
            }

            if (totalSamples == 0) {
                callback.onFailed("Decoded 0 samples from track");
                return;
            }

            // Merge all chunks into a single interleaved stereo array
            short[] stereoData = new short[totalSamples];
            int offset = 0;
            for (short[] chunk : chunks) {
                System.arraycopy(chunk, 0, stereoData, offset, chunk.length);
                offset += chunk.length;
            }
            chunks.clear();

            callback.onTrackLoaded(stereoData, sampleRate, title);

        } catch (Throwable e) {
            System.err.println("InternetAudioLoader: Decode error: " + e.toString());
            e.printStackTrace();
            callback.onFailed("Decode error: " + e.toString());
        }
    }

    /**
     * Cleanup resources.
     */
    public void shutdown() {
        if (playerManager != null) {
            playerManager.shutdown();
        }
    }
}
