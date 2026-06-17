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

/**
 * Internet Audio Loader — LavaPlayer integration for URL-based music streaming.
 * Supports: YouTube (via youtube-source plugin), SoundCloud, HTTP direct links.
 * Decodes audio to mono PCM 16-bit at native sample rate for our OpenAL
 * pipeline.
 */
public class InternetAudioLoader {

    private static InternetAudioLoader INSTANCE;
    private final AudioPlayerManager playerManager;

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
     */
    public interface TrackLoadCallback {
        void onTrackLoaded(short[] pcmData, int sampleRate, String trackTitle);

        void onFailed(String reason);
    }

    public interface StreamingCallback {
        void onReady(short[] pcmArray, int decodedSamples, int totalExpected, int sampleRate, String title);

        void onMoreData(int totalDecoded);

        void onComplete();

        void onFailed(String reason);
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

    public void loadTrackStreaming(String url, StreamingCallback callback) {
        playerManager.loadItem(url, new AudioLoadResultHandler() {
            @Override
            public void trackLoaded(AudioTrack track) {
                CompletableFuture.runAsync(() -> decodeTrackStreaming(track, callback));
            }

            @Override
            public void playlistLoaded(AudioPlaylist playlist) {
                if (playlist.getTracks().isEmpty()) {
                    callback.onFailed("Playlist empty");
                    return;
                }
                AudioTrack s = playlist.getSelectedTrack();
                if (s == null) s = playlist.getTracks().get(0);
                final AudioTrack t = s;
                CompletableFuture.runAsync(() -> decodeTrackStreaming(t, callback));
            }

            @Override
            public void noMatches() {
                callback.onFailed("No matches: " + url);
            }

            @Override
            public void loadFailed(FriendlyException ex) {
                callback.onFailed("Load failed: " + ex.getMessage());
            }
        });
    }

    private void decodeTrackStreaming(AudioTrack track, StreamingCallback callback) {
        int sampleRate = 48000;
        int channels = 2;
        int totalDecoded = 0;
        short[] pcm = null;
        int prebufferTarget;
        String title = track.getInfo().title;
        var player = playerManager.createPlayer();
        try {
            // CRITICAL BUGFIX: We MUST clone the track. If the user plays the exact same URL twice,
            // LavaPlayer uses the cached AudioTrack instance. A track can only be played once,
            // so without cloning it, the second playback instantly fails and returns no audio!
            player.playTrack(track.makeClone());
            AudioFrame first = player.provide(5000, java.util.concurrent.TimeUnit.MILLISECONDS);
            if (first != null && first.getFormat() != null) {
                sampleRate = first.getFormat().sampleRate;
                channels = first.getFormat().channelCount;
            }
            prebufferTarget = 10 * sampleRate;
            long durMs = track.getDuration();
            if (durMs <= 0 || durMs > 5 * 60 * 1000)
                durMs = 5 * 60 * 1000; // Cap at 5 min to avoid 115MB heap allocation
            int totalExpected = (int) (durMs / 1000.0 * sampleRate);
            pcm = new short[totalExpected];
            if (first != null && first.getData() != null && first.getData().length > 0) {
                totalDecoded += copyFrameToPcm(first, pcm, totalDecoded, channels);
            }
            int timeouts = 0;
            while (totalDecoded < prebufferTarget) {
                AudioFrame f = player.provide(5000, java.util.concurrent.TimeUnit.MILLISECONDS);
                if (f == null || f.getData() == null) {
                    if (player.getPlayingTrack() == null) break;
                    timeouts++;
                    if (timeouts > 4) { // 20 seconds
                        throw new RuntimeException("Stream buffering timed out! (Network/IPv6 or Codec issue)");
                    }
                    continue;
                }
                timeouts = 0;
                totalDecoded += copyFrameToPcm(f, pcm, totalDecoded, channels);
            }
            if (totalDecoded == 0) {
                throw new RuntimeException("0 samples decoded. Stream failed to start.");
            }
            final int prebuffered = totalDecoded;
            final int finalSR = sampleRate;
            final short[] pcmFinal = pcm;
            net.minecraft.client.MinecraftClient.getInstance()
                    .execute(() -> callback.onReady(pcmFinal, prebuffered, totalExpected, finalSR, title));
            while (true) {
                AudioFrame f = player.provide(5000, java.util.concurrent.TimeUnit.MILLISECONDS);
                if (f == null) {
                    if (player.getPlayingTrack() == null) break;
                    f = player.provide(10000, java.util.concurrent.TimeUnit.MILLISECONDS);
                    if (f == null) break;
                }
                if (f.getData() == null || f.getData().length == 0) continue;
                totalDecoded += copyFrameToPcm(f, pcm, totalDecoded, channels);
                final int td = totalDecoded;
                net.minecraft.client.MinecraftClient.getInstance().execute(() -> callback.onMoreData(td));
                if (totalDecoded >= totalExpected) break;
            }
            net.minecraft.client.MinecraftClient.getInstance().execute(() -> callback.onComplete());
        } catch (Throwable e) {
            System.err.println("CRITICAL DECODE ERROR: " + e.toString());
            e.printStackTrace();
            net.minecraft.client.MinecraftClient.getInstance().execute(() -> callback.onFailed(e.toString()));
        } finally {
            player.destroy();
        }
    }

    private int copyFrameToPcm(AudioFrame frame, short[] dest, int offset, int channels) {
        byte[] data = frame.getData();
        if (data == null) return 0;
        short[] samples = new short[data.length / 2];
        java.nio.ByteBuffer.wrap(data)
                .order(java.nio.ByteOrder.BIG_ENDIAN)
                .asShortBuffer()
                .get(samples);
        int count = samples.length / channels;
        for (int i = 0; i < count && offset + i < dest.length; i++) {
            int sum = samples[i * channels] + (channels >= 2 ? samples[i * channels + 1] : samples[i * channels]);
            int mono = Math.round(sum * (1.0f / channels));
            dest[offset + i] = (short) Math.max(-32768, Math.min(32767, mono));
        }
        return Math.min(count, dest.length - offset);
    }

    /**
     * Decode an AudioTrack to mono PCM/**
     * Decode an AudioTrack to mono PCM (16-bit signed, native sample rate).
     * This blocks until the entire track is decoded.
     */
    private void decodeTrack(AudioTrack track, TrackLoadCallback callback) {
        try {
            AudioTrackInfo info = track.getInfo();
            String title = info.title;

            // LavaPlayer outputs stereo PCM at the source's native sample rate
            int sampleRate = 48000;
            int channels = 2;

            // Estimate buffer size: duration (ms) * sampleRate / 1000 * channels
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

                // Read first frame to get real sample rate (VBR fix)
                AudioFrame firstFrame = player.provide(5000, java.util.concurrent.TimeUnit.MILLISECONDS);
                if (firstFrame != null && firstFrame.getFormat() != null) {
                    sampleRate = firstFrame.getFormat().sampleRate;
                    channels = firstFrame.getFormat().channelCount;
                    // Reprocess first frame
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
                        // No more data or timeout — check if track ended
                        if (player.getPlayingTrack() == null) {
                            break; // Track finished
                        }
                        // Timeout without data, but track still playing — might be buffering
                        // Try a few more times then give up
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

            // Merge all chunks into a single array
            short[] stereoData = new short[totalSamples];
            int offset = 0;
            for (short[] chunk : chunks) {
                System.arraycopy(chunk, 0, stereoData, offset, chunk.length);
                offset += chunk.length;
            }
            chunks.clear(); // Free memory

            // Downmix stereo to mono (required for 3D OpenAL spatialization)
            int monoSamples = totalSamples / channels;
            short[] monoData = new short[monoSamples];
            for (int i = 0; i < monoSamples; i++) {
                int left = stereoData[i * 2];
                int right = stereoData[i * 2 + 1];
                int sum = (left + right);
                int mono = Math.round(sum * 0.5f);
                if (mono > 32767) mono = 32767;
                if (mono < -32768) mono = -32768;
                monoData[i] = (short) mono;
            }
            stereoData = null; // Free stereo data

            callback.onTrackLoaded(monoData, sampleRate, title);

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
