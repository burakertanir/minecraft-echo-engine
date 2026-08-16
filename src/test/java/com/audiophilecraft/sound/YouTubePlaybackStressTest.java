package com.audiophilecraft.sound;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.sedmelluq.discord.lavaplayer.format.StandardAudioDataFormats;
import com.sedmelluq.discord.lavaplayer.player.AudioLoadResultHandler;
import com.sedmelluq.discord.lavaplayer.player.AudioPlayer;
import com.sedmelluq.discord.lavaplayer.player.DefaultAudioPlayerManager;
import com.sedmelluq.discord.lavaplayer.player.event.AudioEventAdapter;
import com.sedmelluq.discord.lavaplayer.tools.FriendlyException;
import com.sedmelluq.discord.lavaplayer.track.AudioPlaylist;
import com.sedmelluq.discord.lavaplayer.track.AudioTrack;
import com.sedmelluq.discord.lavaplayer.track.playback.AudioFrame;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

class YouTubePlaybackStressTest {
    private static final String RUNS_ENV = "AUDIOPHILECRAFT_YOUTUBE_STRESS_RUNS";
    private static final String RETRIES_ENV = "AUDIOPHILECRAFT_YOUTUBE_STRESS_RETRIES";
    private static final String FRESH_MANAGER_ENV = "AUDIOPHILECRAFT_YOUTUBE_STRESS_FRESH_MANAGER";
    private static final String URL_ENV = "AUDIOPHILECRAFT_YOUTUBE_STRESS_URL";
    private static final String REPORT_ENV = "AUDIOPHILECRAFT_YOUTUBE_STRESS_REPORT";
    private static final int LOAD_TIMEOUT_SECONDS = 20;
    private static final int FIRST_FRAME_TIMEOUT_SECONDS = 20;
    private static final int NEXT_FRAME_TIMEOUT_SECONDS = 5;

    @Test
    @EnabledIfEnvironmentVariable(named = RUNS_ENV, matches = "[1-9][0-9]*")
    void repeatedProductionStylePrebuffersExposeIntermittentYoutubeFailures() throws Exception {
        int runs = Integer.parseInt(System.getenv(RUNS_ENV));
        int maxRetries = Integer.parseInt(System.getenv(RETRIES_ENV));
        boolean freshManagerEachCycle = Boolean.parseBoolean(System.getenv(FRESH_MANAGER_ENV));
        if (maxRetries < 0 || maxRetries > AudioPlaybackController.MAX_URL_RETRIES) {
            throw new IllegalArgumentException(
                    "Retry count must be between 0 and " + AudioPlaybackController.MAX_URL_RETRIES);
        }
        String url = System.getenv(URL_ENV);
        Path report = Path.of(System.getenv(REPORT_ENV));
        Files.createDirectories(report.getParent());

        int initialFailures = 0;
        int recoveredByRetry = 0;
        int terminalFailures = 0;
        int totalAttempts = 0;
        int[] successesByAttempt = new int[maxRetries + 1];
        List<Long> successfulCycleTimesMs = new ArrayList<>();
        List<String> terminalFailureDetails = new ArrayList<>();

        YouTubeProbe sharedProbe = freshManagerEachCycle ? null : new YouTubeProbe();
        try (BufferedWriter writer = Files.newBufferedWriter(report, StandardCharsets.UTF_8)) {
            writer.write("cycle,attempt,manager,success,elapsed_ms,sample_rate,decoded_frames,error");
            writer.newLine();

            for (int cycle = 1; cycle <= runs; cycle++) {
                long cycleStarted = System.nanoTime();
                ProbeResult finalResult = null;
                int successfulAttempt = -1;

                for (int retry = 0; retry <= maxRetries; retry++) {
                    if (retry > 0) {
                        Thread.sleep(AudioPlaybackController.retryBaseDelayMs(retry));
                    }

                    boolean cleanCandidate = AudioPlaybackController.shouldUseCleanPlayerManager(retry);
                    ProbeResult result;
                    if (retry == 0 && freshManagerEachCycle) {
                        try (YouTubeProbe freshProbe = new YouTubeProbe()) {
                            result = freshProbe.prebuffer(url);
                        }
                    } else if (cleanCandidate) {
                        YouTubeProbe candidateProbe = new YouTubeProbe();
                        result = candidateProbe.prebuffer(url);
                        if (result.success() && !freshManagerEachCycle) {
                            sharedProbe.close();
                            sharedProbe = candidateProbe;
                        } else {
                            candidateProbe.close();
                        }
                    } else {
                        result = sharedProbe.prebuffer(url);
                    }

                    totalAttempts++;
                    String managerType = retry == 0 && freshManagerEachCycle
                            ? "fresh"
                            : cleanCandidate ? "clean-candidate" : "shared";
                    writeResult(writer, cycle, retry, managerType, result);
                    writer.flush();
                    finalResult = result;
                    if (result.success()) {
                        successfulAttempt = retry;
                        successesByAttempt[retry]++;
                        break;
                    }
                    if (retry == 0) initialFailures++;
                }

                long cycleElapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - cycleStarted);
                if (successfulAttempt >= 0) {
                    successfulCycleTimesMs.add(cycleElapsedMs);
                    if (successfulAttempt > 0) recoveredByRetry++;
                } else {
                    terminalFailures++;
                    terminalFailureDetails.add("cycle " + cycle + ": " + finalResult.error());
                }

                System.out.printf(
                        Locale.ROOT,
                        "YT_STRESS_PROGRESS cycle=%d/%d result=%s attempt=%d cycleMs=%d initialFailures=%d terminalFailures=%d%n",
                        cycle,
                        runs,
                        successfulAttempt >= 0 ? "READY" : "FAILED",
                        successfulAttempt,
                        cycleElapsedMs,
                        initialFailures,
                        terminalFailures);
            }
        } finally {
            if (sharedProbe != null) sharedProbe.close();
        }

        String summary = String.format(
                Locale.ROOT,
                "runs=%d maxRetries=%d freshManagerEachCycle=%s attempts=%d initialFailures=%d recoveredByRetry=%d terminalFailures=%d "
                        + "successByAttempt=%s p50Ms=%d p95Ms=%d p99Ms=%d maxMs=%d report=%s",
                runs,
                maxRetries,
                freshManagerEachCycle,
                totalAttempts,
                initialFailures,
                recoveredByRetry,
                terminalFailures,
                java.util.Arrays.toString(successesByAttempt),
                percentile(successfulCycleTimesMs, 0.50),
                percentile(successfulCycleTimesMs, 0.95),
                percentile(successfulCycleTimesMs, 0.99),
                percentile(successfulCycleTimesMs, 1.00),
                report.toAbsolutePath());
        System.out.println("YT_STRESS_SUMMARY " + summary);

        assertEquals(0, terminalFailures, summary + "; failures=" + terminalFailureDetails);
    }

    private static void writeResult(BufferedWriter writer, int cycle, int retry, String managerType, ProbeResult result)
            throws IOException {
        writer.write(Integer.toString(cycle));
        writer.write(',');
        writer.write(Integer.toString(retry));
        writer.write(',');
        writer.write(managerType);
        writer.write(',');
        writer.write(Boolean.toString(result.success()));
        writer.write(',');
        writer.write(Long.toString(result.elapsedMs()));
        writer.write(',');
        writer.write(Integer.toString(result.sampleRate()));
        writer.write(',');
        writer.write(Integer.toString(result.decodedFrames()));
        writer.write(',');
        writer.write(csv(result.error()));
        writer.newLine();
    }

    private static String csv(String value) {
        if (value == null) return "";
        return '"' + value.replace("\"", "\"\"").replace('\r', ' ').replace('\n', ' ') + '"';
    }

    private static long percentile(List<Long> samples, double percentile) {
        if (samples.isEmpty()) return -1L;
        List<Long> sorted = new ArrayList<>(samples);
        Collections.sort(sorted);
        int index = (int) Math.ceil(percentile * sorted.size()) - 1;
        return sorted.get(Math.max(0, Math.min(index, sorted.size() - 1)));
    }

    private record ProbeResult(boolean success, long elapsedMs, int sampleRate, int decodedFrames, String error) {
        private static ProbeResult success(long elapsedMs, int sampleRate, int decodedFrames) {
            return new ProbeResult(true, elapsedMs, sampleRate, decodedFrames, "");
        }

        private static ProbeResult failure(long elapsedMs, String error) {
            return new ProbeResult(false, elapsedMs, 0, 0, error);
        }
    }

    private static final class YouTubeProbe implements AutoCloseable {
        private final DefaultAudioPlayerManager manager = new DefaultAudioPlayerManager();

        private YouTubeProbe() {
            manager.getConfiguration().setOutputFormat(StandardAudioDataFormats.COMMON_PCM_S16_BE);
            manager.registerSourceManager(InternetAudioLoader.createYoutubeSourceManager());
        }

        private ProbeResult prebuffer(String url) {
            long started = System.nanoTime();
            AtomicReference<AudioTrack> loadedTrack = new AtomicReference<>();
            AtomicReference<String> loadFailure = new AtomicReference<>();
            CountDownLatch loaded = new CountDownLatch(1);
            AudioPlayer player = null;
            Future<Void> loadFuture = null;
            try {
                loadFuture = manager.loadItem(url, new AudioLoadResultHandler() {
                    @Override
                    public void trackLoaded(AudioTrack track) {
                        loadedTrack.compareAndSet(null, track);
                        loaded.countDown();
                    }

                    @Override
                    public void playlistLoaded(AudioPlaylist playlist) {
                        AudioTrack selected = playlist.getSelectedTrack();
                        if (selected == null && !playlist.getTracks().isEmpty()) {
                            selected = playlist.getTracks().get(0);
                        }
                        if (selected != null) loadedTrack.compareAndSet(null, selected);
                        loaded.countDown();
                    }

                    @Override
                    public void noMatches() {
                        loadFailure.compareAndSet(null, "No matches");
                        loaded.countDown();
                    }

                    @Override
                    public void loadFailed(FriendlyException exception) {
                        loadFailure.compareAndSet(null, describe(exception));
                        loaded.countDown();
                    }
                });

                if (!loaded.await(LOAD_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                    return failure(started, "Metadata resolution timed out");
                }
                if (loadFailure.get() != null) return failure(started, "Metadata: " + loadFailure.get());

                AudioTrack track = loadedTrack.get();
                if (track == null) return failure(started, "YouTube returned no playable track");

                AtomicReference<FriendlyException> playbackFailure = new AtomicReference<>();
                player = manager.createPlayer();
                player.addListener(new AudioEventAdapter() {
                    @Override
                    public void onTrackException(
                            AudioPlayer failedPlayer, AudioTrack failedTrack, FriendlyException exception) {
                        playbackFailure.compareAndSet(null, exception);
                    }
                });
                player.playTrack(track.makeClone());

                AudioFrame frame = player.provide(FIRST_FRAME_TIMEOUT_SECONDS, TimeUnit.SECONDS);
                if (playbackFailure.get() != null) {
                    return failure(started, "Playback before PCM: " + describe(playbackFailure.get()));
                }
                if (frame == null) return failure(started, "No first PCM frame");

                int sampleRate = frame.getFormat().sampleRate;
                int decodedFrames = pcmFrameCount(frame);
                int prebufferTarget = sampleRate * 10;
                while (decodedFrames < prebufferTarget) {
                    frame = player.provide(NEXT_FRAME_TIMEOUT_SECONDS, TimeUnit.SECONDS);
                    if (playbackFailure.get() != null) {
                        return failure(started, "Playback during prebuffer: " + describe(playbackFailure.get()));
                    }
                    if (frame == null) return failure(started, "Stream ended before ten-second prebuffer");
                    decodedFrames += pcmFrameCount(frame);
                }
                return ProbeResult.success(elapsedMs(started), sampleRate, decodedFrames);
            } catch (Throwable failure) {
                return failure(started, failure.toString());
            } finally {
                if (loadFuture != null && !loadFuture.isDone()) loadFuture.cancel(true);
                if (player != null) player.destroy();
            }
        }

        private static ProbeResult failure(long started, String reason) {
            return ProbeResult.failure(elapsedMs(started), reason);
        }

        private static long elapsedMs(long started) {
            return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
        }

        private static int pcmFrameCount(AudioFrame frame) {
            if (frame.getFormat() == null) throw new IllegalArgumentException("PCM frame has no format");
            int channelCount = frame.getFormat().channelCount;
            if (channelCount <= 0) throw new IllegalArgumentException("Invalid PCM channel count: " + channelCount);
            byte[] data = frame.getData();
            if (data == null || data.length == 0) throw new IllegalArgumentException("Empty PCM frame");
            int bytesPerFrame = Short.BYTES * channelCount;
            if (data.length % bytesPerFrame != 0) {
                throw new IllegalArgumentException("Malformed PCM frame length: " + data.length);
            }
            return data.length / bytesPerFrame;
        }

        private static String describe(FriendlyException exception) {
            String message = exception.getMessage();
            Throwable cause = exception.getCause();
            return cause == null ? message : message + " | cause=" + cause;
        }

        @Override
        public void close() {
            manager.shutdown();
        }
    }
}
