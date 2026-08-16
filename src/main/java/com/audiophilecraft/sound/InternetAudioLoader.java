package com.audiophilecraft.sound;

import com.audiophilecraft.AudiophileCraft;
import com.sedmelluq.discord.lavaplayer.format.StandardAudioDataFormats;
import com.sedmelluq.discord.lavaplayer.player.AudioLoadResultHandler;
import com.sedmelluq.discord.lavaplayer.player.AudioPlayer;
import com.sedmelluq.discord.lavaplayer.player.AudioPlayerManager;
import com.sedmelluq.discord.lavaplayer.player.DefaultAudioPlayerManager;
import com.sedmelluq.discord.lavaplayer.player.event.AudioEventAdapter;
import com.sedmelluq.discord.lavaplayer.source.http.HttpAudioSourceManager;
import com.sedmelluq.discord.lavaplayer.source.soundcloud.SoundCloudAudioSourceManager;
import com.sedmelluq.discord.lavaplayer.tools.FriendlyException;
import com.sedmelluq.discord.lavaplayer.track.AudioPlaylist;
import com.sedmelluq.discord.lavaplayer.track.AudioTrack;
import com.sedmelluq.discord.lavaplayer.track.AudioTrackInfo;
import com.sedmelluq.discord.lavaplayer.track.playback.AudioFrame;
import dev.lavalink.youtube.YoutubeAudioSourceManager;
import dev.lavalink.youtube.clients.AndroidVrWithThumbnail;
import dev.lavalink.youtube.clients.MusicWithThumbnail;
import dev.lavalink.youtube.clients.TvHtml5SimplyWithThumbnail;
import dev.lavalink.youtube.clients.WebEmbeddedWithThumbnail;
import dev.lavalink.youtube.clients.WebWithThumbnail;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.ShortBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Internet Audio Loader — LavaPlayer integration for URL-based music streaming.
 * Supports: YouTube (via youtube-source plugin), SoundCloud, HTTP direct links.
 * Normalizes decoded audio to interleaved stereo PCM 16-bit at native sample
 * rate for the OpenAL pipeline.
 */
public class InternetAudioLoader {

    private static final int DECODER_THREAD_COUNT =
            Math.max(2, Math.min(4, Runtime.getRuntime().availableProcessors() / 2));
    private static final int DECODER_QUEUE_CAPACITY = 32;

    private static InternetAudioLoader INSTANCE;

    private final RotatingResourcePool<AudioPlayerManager> playerManagers;
    private final ConcurrentHashMap<Long, StreamingRequestState> streamingRequests = new ConcurrentHashMap<>();
    private final AtomicInteger nextDecoderThreadId = new AtomicInteger(1);
    private final AtomicBoolean shutdownStarted = new AtomicBoolean(false);
    private final ExecutorService decoderExecutor;

    private static final class StreamingRequestState {
        private final long id;
        private final RotatingResourcePool.Lease<AudioPlayerManager> playerManagerLease;
        private final AudioPlayerManager requestPlayerManager;
        private final AtomicBoolean cancelled = new AtomicBoolean(false);
        private final AtomicBoolean playerManagerReleased = new AtomicBoolean(false);
        private volatile Future<?> loadTask;
        private volatile Future<?> decodeTask;
        private volatile AudioPlayer player;

        private StreamingRequestState(long id, RotatingResourcePool.Lease<AudioPlayerManager> playerManagerLease) {
            this.id = id;
            this.playerManagerLease = playerManagerLease;
            this.requestPlayerManager = playerManagerLease.resource();
        }

        private boolean isCancelled() {
            return cancelled.get();
        }

        private boolean cancel() {
            if (!cancelled.compareAndSet(false, true)) return false;
            Future<?> currentLoadTask = loadTask;
            if (currentLoadTask != null) currentLoadTask.cancel(true);
            Future<?> currentDecodeTask = decodeTask;
            if (currentDecodeTask != null) currentDecodeTask.cancel(true);
            AudioPlayer currentPlayer = player;
            if (currentPlayer != null) {
                currentPlayer.stopTrack();
            }
            releasePlayerManager();
            return true;
        }

        private boolean promotePlayerManager() {
            return playerManagerLease.promote();
        }

        private boolean usesCandidatePlayerManager() {
            return playerManagerLease.isCandidate();
        }

        private void releasePlayerManager() {
            if (playerManagerReleased.compareAndSet(false, true)) playerManagerLease.close();
        }
    }

    private final AtomicLong nextStreamingRequestId = new AtomicLong(1L);

    private InternetAudioLoader() {
        // Force IPv4 to prevent Java network hangs on Windows (IPv6 timeouts)
        System.setProperty("java.net.preferIPv4Stack", "true");
        decoderExecutor = new ThreadPoolExecutor(
                DECODER_THREAD_COUNT,
                DECODER_THREAD_COUNT,
                0L,
                TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(DECODER_QUEUE_CAPACITY),
                task -> {
                    Thread thread =
                            new Thread(task, "AudiophileCraft-Decoder-" + nextDecoderThreadId.getAndIncrement());
                    thread.setDaemon(true);
                    return thread;
                },
                new ThreadPoolExecutor.AbortPolicy());

        playerManagers =
                new RotatingResourcePool<>(InternetAudioLoader::createPlayerManager, AudioPlayerManager::shutdown);
    }

    private static AudioPlayerManager createPlayerManager() {
        AudioPlayerManager manager = new DefaultAudioPlayerManager();
        // CRITICAL: Set output format to raw PCM (not Opus!)
        // LavaPlayer defaults to Opus which is encoded — we need raw PCM for OpenAL
        manager.getConfiguration().setOutputFormat(StandardAudioDataFormats.COMMON_PCM_S16_BE);
        // Mirror youtube-source's anonymous default rotation while preserving the
        // thumbnail metadata used by the tablet. TVHTML5_SIMPLY is an additional
        // non-OAuth playback fallback for videos rejected by the other clients.
        manager.registerSourceManager(createYoutubeSourceManager());
        // Register other remote sources (SoundCloud, HTTP, etc.)
        manager.registerSourceManager(SoundCloudAudioSourceManager.createDefault());
        manager.registerSourceManager(new HttpAudioSourceManager());
        return manager;
    }

    static YoutubeAudioSourceManager createYoutubeSourceManager() {
        return new YoutubeAudioSourceManager(
                new MusicWithThumbnail(),
                new AndroidVrWithThumbnail(),
                new WebWithThumbnail(),
                new WebEmbeddedWithThumbnail(),
                new TvHtml5SimplyWithThumbnail());
    }

    public static synchronized InternetAudioLoader getInstance() {
        if (INSTANCE == null) {
            INSTANCE = new InternetAudioLoader();
        }
        return INSTANCE;
    }

    public static void shutdownIfInitialized() {
        InternetAudioLoader loader;
        synchronized (InternetAudioLoader.class) {
            loader = INSTANCE;
        }
        if (loader != null) {
            loader.shutdown();
        }
    }

    /**
     * Destroys the current singleton so the next {@link #getInstance()} call
     * creates a brand-new {@link DefaultAudioPlayerManager} with fresh source
     * managers. Use this to recover from YouTube bot-protection / login-wall
     * errors that leave the internal HTTP session in a broken state.
     *
     * <p>All active streaming requests are cancelled before shutdown.
     * The decoder executor and player manager are fully terminated.
     */
    public static synchronized void resetInstance() {
        if (INSTANCE != null) {
            INSTANCE.shutdown();
            INSTANCE = null;
            AudiophileCraft.LOGGER.info("InternetAudioLoader instance reset (will recreate on next use).");
        }
    }

    /**
     * Callback interface for async track loading.
     * pcmInterleaved is always normalized to [L,R,L,R,...]; mono becomes [M,M,M,M,...].
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

        void onComplete(long requestId, int totalDecodedFrames);

        void onFailed(long requestId, String reason);
    }

    private void startLegacyDecode(
            AudioTrack track,
            TrackLoadCallback callback,
            RotatingResourcePool.Lease<AudioPlayerManager> playerManagerLease) {
        try {
            decoderExecutor.execute(
                    () -> decodeTrack(track, callback, playerManagerLease.resource(), playerManagerLease));
        } catch (RejectedExecutionException e) {
            playerManagerLease.close();
            callback.onFailed("Decoder queue is full or shutting down");
        }
    }

    /**
     * Load and decode audio from a URL asynchronously.
     * The entire track is downloaded & decoded to PCM, then passed to the callback.
     * Resolution runs on LavaPlayer's loader threads; PCM decoding runs on the
     * bounded AudiophileCraft decoder executor.
     *
     * @param url      URL or search query (e.g. "ytsearch:song name")
     * @param callback Called on completion or failure
     */
    public void loadTrack(String url, TrackLoadCallback callback) {
        RotatingResourcePool.Lease<AudioPlayerManager> playerManagerLease = playerManagers.acquireShared();
        try {
            playerManagerLease.resource().loadItem(url, new AudioLoadResultHandler() {
                @Override
                public void trackLoaded(AudioTrack track) {
                    // Decode on a separate thread to avoid blocking
                    startLegacyDecode(track, callback, playerManagerLease);
                }

                @Override
                public void playlistLoaded(AudioPlaylist playlist) {
                    // For playlists, just play the first track
                    if (playlist.getTracks().isEmpty()) {
                        try {
                            callback.onFailed("Playlist is empty");
                        } finally {
                            playerManagerLease.close();
                        }
                        return;
                    }

                    AudioTrack selected = playlist.getSelectedTrack();
                    if (selected == null) {
                        selected = playlist.getTracks().get(0);
                    }

                    startLegacyDecode(selected, callback, playerManagerLease);
                }

                @Override
                public void noMatches() {
                    AudiophileCraft.LOGGER.warn("No internet audio matches found for {}.", url);
                    try {
                        callback.onFailed("No matches found for: " + url);
                    } finally {
                        playerManagerLease.close();
                    }
                }

                @Override
                public void loadFailed(FriendlyException exception) {
                    AudiophileCraft.LOGGER.warn("Internet audio load failed for {}.", url, exception);
                    try {
                        callback.onFailed("Load failed: " + exception.getMessage());
                    } finally {
                        playerManagerLease.close();
                    }
                }
            });
        } catch (Throwable loadFailure) {
            playerManagerLease.close();
            callback.onFailed("Internet load could not start: " + loadFailure);
        }
    }

    /**
     * Synchronously resolve a LavaPlayer identifier (URL, bare video ID, or
     * "ytsearch:query") into its tracks.
     *
     * <p>Blocks the calling thread until LavaPlayer finishes resolving or
     * {@code timeoutMs} elapses. Returns an empty list on no-match, failure,
     * or timeout. Resolution itself still runs on LavaPlayer's own threads;
     * only the wait is synchronous.
     *
     * @param identifier URL, video ID, or "ytsearch:" search query
     * @param timeoutMs  maximum time to wait for resolution
     * @return resolved tracks (all playlist tracks for searches/playlists)
     */
    public List<AudioTrack> loadItemBlocking(String identifier, long timeoutMs) {
        List<AudioTrack> pending = new java.util.concurrent.CopyOnWriteArrayList<>();
        CountDownLatch done = new CountDownLatch(1);
        try (RotatingResourcePool.Lease<AudioPlayerManager> playerManagerLease = playerManagers.acquireShared()) {
            Future<Void> loadTask = playerManagerLease.resource().loadItem(identifier, new AudioLoadResultHandler() {
                @Override
                public void trackLoaded(AudioTrack track) {
                    try {
                        pending.add(track);
                    } finally {
                        done.countDown();
                    }
                }

                @Override
                public void playlistLoaded(AudioPlaylist playlist) {
                    try {
                        pending.addAll(playlist.getTracks());
                        if (pending.isEmpty() && playlist.getSelectedTrack() != null) {
                            pending.add(playlist.getSelectedTrack());
                        }
                    } finally {
                        done.countDown();
                    }
                }

                @Override
                public void noMatches() {
                    done.countDown();
                }

                @Override
                public void loadFailed(FriendlyException exception) {
                    AudiophileCraft.LOGGER.debug("Blocking internet load failed for {}.", identifier, exception);
                    done.countDown();
                }
            });
            try {
                if (!done.await(timeoutMs, TimeUnit.MILLISECONDS)) loadTask.cancel(true);
            } catch (InterruptedException e) {
                loadTask.cancel(true);
                Thread.currentThread().interrupt();
            }
        }
        return new ArrayList<>(pending);
    }

    public boolean cancelStreamingRequest(long requestId) {
        StreamingRequestState state = streamingRequests.get(requestId);
        if (state == null) return false;
        boolean cancelled = state.cancel();
        if (cancelled) {
            streamingRequests.remove(requestId, state);
            AudiophileCraft.LOGGER.debug("Cancelled internet audio request {}.", requestId);
        }
        return cancelled;
    }

    private void startStreamingDecode(StreamingRequestState state, AudioTrack track, StreamingCallback callback) {
        if (state.isCancelled()) {
            streamingRequests.remove(state.id, state);
            return;
        }
        try {
            Future<?> decodeTask = decoderExecutor.submit(() -> decodeTrackStreaming(state, track, callback));
            state.decodeTask = decodeTask;
            if (state.isCancelled()) decodeTask.cancel(true);
        } catch (RejectedExecutionException e) {
            failStreamingRequest(state, callback, "Decoder queue is full or shutting down");
        }
    }

    private void failStreamingRequest(StreamingRequestState state, StreamingCallback callback, String reason) {
        if (state.isCancelled()) return;
        if (streamingRequests.remove(state.id, state)) {
            dispatchIfActive(state, () -> callback.onFailed(state.id, reason));
            releasePlayerManagerAsync(state);
        }
    }

    private void releasePlayerManagerAsync(StreamingRequestState state) {
        try {
            java.util.concurrent.ForkJoinPool.commonPool().execute(state::releasePlayerManager);
        } catch (RejectedExecutionException ignored) {
            state.releasePlayerManager();
        }
    }

    private void failStreamingRequestFromDecoderWorker(
            StreamingRequestState state, StreamingCallback callback, String reason) {
        if (state.isCancelled()) return;
        if (streamingRequests.remove(state.id, state)) {
            dispatchFromDecoderWorker(() -> {
                if (!state.isCancelled()) callback.onFailed(state.id, reason);
            });
            releasePlayerManagerAsync(state);
        }
    }

    private void dispatchFromDecoderWorker(Runnable callback) {
        java.util.concurrent.ForkJoinPool.commonPool().execute(() -> net.minecraft.client.MinecraftClient.getInstance()
                .execute(callback));
    }

    private void dispatchIfActive(StreamingRequestState state, Runnable callback) {
        net.minecraft.client.MinecraftClient.getInstance().execute(() -> {
            if (!state.isCancelled()) callback.run();
        });
    }

    public long loadTrackStreaming(String url, StreamingCallback callback) {
        return loadTrackStreaming(url, callback, false);
    }

    long loadTrackStreaming(String url, StreamingCallback callback, boolean cleanCandidatePlayerManager) {
        long requestId = nextStreamingRequestId.getAndIncrement();
        RotatingResourcePool.Lease<AudioPlayerManager> playerManagerLease;
        try {
            playerManagerLease =
                    cleanCandidatePlayerManager ? playerManagers.acquireCandidate() : playerManagers.acquireShared();
        } catch (Throwable managerFailure) {
            dispatchFromDecoderWorker(
                    () -> callback.onFailed(requestId, "Failed to create internet source manager: " + managerFailure));
            return requestId;
        }
        StreamingRequestState state = new StreamingRequestState(requestId, playerManagerLease);
        streamingRequests.put(requestId, state);
        Future<Void> loadTask;
        try {
            loadTask = state.requestPlayerManager.loadItem(url, new AudioLoadResultHandler() {
                @Override
                public void trackLoaded(AudioTrack track) {
                    startStreamingDecode(state, track, callback);
                }

                @Override
                public void playlistLoaded(AudioPlaylist playlist) {
                    if (playlist.getTracks().isEmpty()) {
                        failStreamingRequest(state, callback, "Playlist empty");
                        return;
                    }
                    AudioTrack s = playlist.getSelectedTrack();
                    if (s == null) s = playlist.getTracks().get(0);
                    final AudioTrack t = s;
                    startStreamingDecode(state, t, callback);
                }

                @Override
                public void noMatches() {
                    failStreamingRequest(state, callback, "No matches: " + url);
                }

                @Override
                public void loadFailed(FriendlyException ex) {
                    failStreamingRequest(state, callback, "Load failed: " + ex.getMessage());
                }
            });
        } catch (Throwable loadFailure) {
            failStreamingRequestFromDecoderWorker(state, callback, "Internet load could not start: " + loadFailure);
            return requestId;
        }
        state.loadTask = loadTask;
        if (state.isCancelled()) loadTask.cancel(true);
        return requestId;
    }

    private void decodeTrackStreaming(StreamingRequestState state, AudioTrack track, StreamingCallback callback) {
        long requestId = state.id;
        if (state.isCancelled()) return;
        int sampleRate = 48000;
        int totalDecoded = 0;
        short[] pcmInterleaved = null;
        int prebufferTarget;
        String title = track.getInfo().title;
        AudioPlayer player = null;
        AtomicReference<FriendlyException> playbackFailure = new AtomicReference<>();
        try {
            player = state.requestPlayerManager.createPlayer();
            player.addListener(new AudioEventAdapter() {
                @Override
                public void onTrackException(
                        AudioPlayer failedPlayer, AudioTrack failedTrack, FriendlyException exception) {
                    playbackFailure.compareAndSet(null, exception);
                }
            });
            state.player = player;
            player.playTrack(track.makeClone());
            if (state.isCancelled()) return;
            AudioFrame first = player.provide(5000, java.util.concurrent.TimeUnit.MILLISECONDS);
            if (first != null && first.getFormat() != null) {
                sampleRate = first.getFormat().sampleRate;
                getValidatedChannelCount(first);
            }
            prebufferTarget = 10 * sampleRate;
            long durMs = track.getDuration();
            if (durMs <= 0 || durMs > 10 * 60 * 1000) durMs = 10 * 60 * 1000;
            int totalExpected = (int) (durMs / 1000.0 * sampleRate);
            // Interleaved stereo: [L0,R0, L1,R1, L2,R2, ...], length = frames * 2
            pcmInterleaved = new short[totalExpected * 2];
            if (first != null && first.getData() != null && first.getData().length > 0) {
                totalDecoded += copyFrameToPcm(first, pcmInterleaved, totalDecoded);
            }
            int timeouts = 0;
            while (!state.isCancelled() && totalDecoded < prebufferTarget) {
                AudioFrame f = player.provide(5000, java.util.concurrent.TimeUnit.MILLISECONDS);
                if (state.isCancelled()) return;
                if (f == null || f.getData() == null || f.getData().length == 0) {
                    if (player.getPlayingTrack() == null) break;
                    timeouts++;
                    if (timeouts > 4) {
                        throw new RuntimeException("Stream buffering timed out! (Network/IPv6 or Codec issue)");
                    }
                    continue;
                }
                timeouts = 0;
                totalDecoded += copyFrameToPcm(f, pcmInterleaved, totalDecoded);
            }
            if (state.isCancelled()) return;
            if (totalDecoded == 0) {
                FriendlyException failure = playbackFailure.get();
                if (failure != null) {
                    throw new RuntimeException(
                            "0 samples decoded. YouTube playback failed: " + failure.getMessage(), failure);
                }
                throw new RuntimeException("0 samples decoded. Stream failed to start.");
            }
            final int prebuffered = totalDecoded;
            final int finalSR = sampleRate;
            final short[] pcmFinal = pcmInterleaved;
            if (state.usesCandidatePlayerManager()) {
                if (state.promotePlayerManager()) {
                    AudiophileCraft.LOGGER.info(
                            "Internet audio request {} promoted its healthy source manager for future tracks.",
                            requestId);
                } else {
                    AudiophileCraft.LOGGER.debug(
                            "Internet audio request {} became ready after another request had already replaced the shared source manager; keeping this request isolated.",
                            requestId);
                }
            }
            dispatchIfActive(
                    state, () -> callback.onReady(requestId, pcmFinal, prebuffered, totalExpected, finalSR, title));
            while (!state.isCancelled()) {
                AudioFrame f = player.provide(5000, java.util.concurrent.TimeUnit.MILLISECONDS);
                if (f == null) {
                    if (state.isCancelled()) return;
                    if (player.getPlayingTrack() == null) break;
                    f = player.provide(10000, java.util.concurrent.TimeUnit.MILLISECONDS);
                    if (f == null) break;
                }
                if (f.getData() == null || f.getData().length == 0) continue;
                totalDecoded += copyFrameToPcm(f, pcmInterleaved, totalDecoded);
                final int td = totalDecoded;
                dispatchIfActive(state, () -> callback.onMoreData(requestId, td));
                if (totalDecoded >= totalExpected) break;
            }
            int completedFrames = totalDecoded;
            dispatchIfActive(state, () -> callback.onComplete(requestId, completedFrames));
        } catch (Throwable e) {
            if (!state.isCancelled()) {
                AudiophileCraft.LOGGER.error("Critical streaming decode failure for request {}.", requestId, e);
                dispatchIfActive(state, () -> callback.onFailed(requestId, e.toString()));
            }
        } finally {
            state.player = null;
            try {
                if (player != null) player.destroy();
            } finally {
                streamingRequests.remove(requestId, state);
                state.releasePlayerManager();
            }
        }
    }
    /**
     * Copy a decoded frame into the always-stereo destination.
     * Mono frames are duplicated as [M,M]; stereo frames remain [L,R].
     *
     * @return number of frames (not shorts) written
     */
    private int copyFrameToPcm(AudioFrame frame, short[] destination, int frameOffset) {
        byte[] data = frame.getData();
        if (data == null || data.length == 0) return 0;

        int channels = getValidatedChannelCount(frame);
        int sourceFrames = getFrameCount(data, channels);
        int writableFrames = Math.min(sourceFrames, destination.length / 2 - frameOffset);
        if (writableFrames <= 0) return 0;

        ShortBuffer source = ByteBuffer.wrap(data).order(ByteOrder.BIG_ENDIAN).asShortBuffer();
        int destinationOffset = frameOffset * 2;
        if (channels == 1) {
            for (int frameIndex = 0; frameIndex < writableFrames; frameIndex++) {
                short sample = source.get();
                destination[destinationOffset + frameIndex * 2] = sample;
                destination[destinationOffset + frameIndex * 2 + 1] = sample;
            }
        } else {
            source.get(destination, destinationOffset, writableFrames * 2);
        }
        return writableFrames;
    }

    private int getValidatedChannelCount(AudioFrame frame) {
        if (frame.getFormat() == null) {
            throw new IllegalArgumentException("Internet audio frame is missing its PCM format");
        }
        int channels = frame.getFormat().channelCount;
        if (channels != 1 && channels != 2) {
            throw new IllegalArgumentException(
                    "Unsupported internet audio channel count: " + channels + " (only mono and stereo are supported)");
        }
        return channels;
    }

    private int getFrameCount(byte[] data, int channels) {
        int bytesPerFrame = Short.BYTES * channels;
        if (data.length % bytesPerFrame != 0) {
            throw new IllegalArgumentException(
                    "Malformed internet PCM frame: " + data.length + " bytes for " + channels + " channels");
        }
        return data.length / bytesPerFrame;
    }

    private short[] convertFrameToStereoPcm(AudioFrame frame) {
        byte[] data = frame.getData();
        if (data == null || data.length == 0) return new short[0];
        int channels = getValidatedChannelCount(frame);
        short[] stereo = new short[getFrameCount(data, channels) * 2];
        copyFrameToPcm(frame, stereo, 0);
        return stereo;
    }

    /**
     * Decode an AudioTrack to interleaved stereo PCM.
     * This blocks until the entire track is decoded.
     */
    private void decodeTrack(
            AudioTrack track,
            TrackLoadCallback callback,
            AudioPlayerManager requestPlayerManager,
            RotatingResourcePool.Lease<AudioPlayerManager> playerManagerLease) {
        try {
            AudioTrackInfo info = track.getInfo();
            String title = info.title;

            int sampleRate = 48000;
            java.util.List<short[]> chunks = new java.util.ArrayList<>();
            int totalSamples = 0;

            AudioPlayer player = requestPlayerManager.createPlayer();
            try {
                player.playTrack(track);

                AudioFrame firstFrame = player.provide(5000, TimeUnit.MILLISECONDS);
                if (firstFrame != null) {
                    if (firstFrame.getFormat() != null) sampleRate = firstFrame.getFormat().sampleRate;
                    short[] samples = convertFrameToStereoPcm(firstFrame);
                    if (samples.length > 0) {
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

                    short[] samples = convertFrameToStereoPcm(frame);
                    if (samples.length == 0) continue;
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
            AudiophileCraft.LOGGER.error("Legacy internet audio decode failed.", e);
            callback.onFailed("Decode error: " + e.toString());
        } finally {
            playerManagerLease.close();
        }
    }

    /**
     * Cleanup resources.
     */
    private void shutdown() {
        if (!shutdownStarted.compareAndSet(false, true)) return;
        for (StreamingRequestState state : streamingRequests.values()) {
            state.cancel();
        }
        streamingRequests.clear();
        decoderExecutor.shutdownNow();
        playerManagers.close();
        try {
            if (!decoderExecutor.awaitTermination(2, TimeUnit.SECONDS)) {
                AudiophileCraft.LOGGER.warn("Internet audio decoder executor did not terminate cleanly.");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
