package com.audiophilecraft.sound;

import com.sedmelluq.discord.lavaplayer.player.AudioLoadResultHandler;
import com.sedmelluq.discord.lavaplayer.player.AudioPlayerManager;
import com.sedmelluq.discord.lavaplayer.player.DefaultAudioPlayerManager;
import com.sedmelluq.discord.lavaplayer.format.StandardAudioDataFormats;
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

    /**
     * Decode an AudioTrack to mono PCM (16-bit signed, native sample rate).
     * This blocks until the entire track is decoded.
     */
    private void decodeTrack(AudioTrack track, TrackLoadCallback callback) {
        try {
            AudioTrackInfo info = track.getInfo();
            String title = info.title;

            // LavaPlayer outputs stereo PCM at 48kHz by default (Opus standard)
            // Output format: 2 channels, 16-bit signed, 48000 Hz
            int sampleRate = 48000;
            int channels = 2;

            // Estimate buffer size: duration (ms) * sampleRate / 1000 * channels
            long durationMs = track.getDuration();
            if (durationMs <= 0 || durationMs > 60 * 60 * 1000) {
                // Cap at 60 minutes to prevent OOM
                durationMs = 60 * 60 * 1000;
            }

            int estimatedSamples = (int) ((durationMs / 1000.0) * sampleRate * channels);
            // Use a growing list instead of fixed array to handle variable bitrate
            java.util.List<short[]> chunks = new java.util.ArrayList<>();
            int totalSamples = 0;

            // Create a player and play the track through it
            var player = playerManager.createPlayer();
            try {
                player.playTrack(track);

                // Read all frames
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
                    if (data == null || data.length == 0)
                        continue;

                    // Convert bytes to shorts (BIG-ENDIAN — LavaPlayer PCM_S16_BE format)
                    short[] samples = new short[data.length / 2];
                    ByteBuffer.wrap(data).order(ByteOrder.BIG_ENDIAN).asShortBuffer().get(samples);

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
                if (mono > 32767)
                    mono = 32767;
                if (mono < -32768)
                    mono = -32768;
                monoData[i] = (short) mono;
            }
            stereoData = null; // Free stereo data

            // CRITICAL FIX: LavaPlayer does NOT resample to 48kHz!
            // The output frames contain samples at the SOURCE's native rate,
            // regardless of the configured output format.
            // We must derive the actual sample rate from the track duration.
            double trackDurationSec = durationMs / 1000.0;
            if (trackDurationSec > 0 && monoSamples > 0) {
                int actualSampleRate = (int) Math.round(monoSamples / trackDurationSec);
                sampleRate = actualSampleRate;
            }

            callback.onTrackLoaded(monoData, sampleRate, title);

        } catch (Exception e) {
            System.err.println("InternetAudioLoader: Decode error: " + e.getMessage());
            e.printStackTrace();
            callback.onFailed("Decode error: " + e.getMessage());
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
