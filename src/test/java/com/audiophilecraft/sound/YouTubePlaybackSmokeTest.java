package com.audiophilecraft.sound;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import com.sedmelluq.discord.lavaplayer.format.StandardAudioDataFormats;
import com.sedmelluq.discord.lavaplayer.player.AudioLoadResultHandler;
import com.sedmelluq.discord.lavaplayer.player.AudioPlayer;
import com.sedmelluq.discord.lavaplayer.player.DefaultAudioPlayerManager;
import com.sedmelluq.discord.lavaplayer.player.event.AudioEventAdapter;
import com.sedmelluq.discord.lavaplayer.tools.FriendlyException;
import com.sedmelluq.discord.lavaplayer.track.AudioPlaylist;
import com.sedmelluq.discord.lavaplayer.track.AudioTrack;
import com.sedmelluq.discord.lavaplayer.track.playback.AudioFrame;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

class YouTubePlaybackSmokeTest {
    private static final String TEST_VIDEO = "https://www.youtube.com/watch?v=7hLLOHFmGMU";

    @Test
    @EnabledIfEnvironmentVariable(named = "AUDIOPHILECRAFT_YOUTUBE_SMOKE", matches = "(?i:true|1)")
    void anonymousClientsResolveAndDecodeRealYoutubeAudio() throws Exception {
        DefaultAudioPlayerManager manager = new DefaultAudioPlayerManager();
        manager.getConfiguration().setOutputFormat(StandardAudioDataFormats.COMMON_PCM_S16_BE);
        manager.registerSourceManager(InternetAudioLoader.createYoutubeSourceManager());

        AtomicReference<AudioTrack> loadedTrack = new AtomicReference<>();
        AtomicReference<String> loadFailure = new AtomicReference<>();
        CountDownLatch loaded = new CountDownLatch(1);
        AudioPlayer player = null;
        try {
            manager.loadItem(TEST_VIDEO, new AudioLoadResultHandler() {
                @Override
                public void trackLoaded(AudioTrack track) {
                    loadedTrack.set(track);
                    loaded.countDown();
                }

                @Override
                public void playlistLoaded(AudioPlaylist playlist) {
                    if (!playlist.getTracks().isEmpty()) {
                        loadedTrack.set(playlist.getTracks().get(0));
                    }
                    loaded.countDown();
                }

                @Override
                public void noMatches() {
                    loadFailure.set("No matches");
                    loaded.countDown();
                }

                @Override
                public void loadFailed(FriendlyException exception) {
                    loadFailure.set(exception.toString());
                    loaded.countDown();
                }
            });

            assertTrue(loaded.await(20, TimeUnit.SECONDS), "YouTube metadata resolution timed out");
            if (loadFailure.get() != null) {
                fail("YouTube metadata resolution failed: " + loadFailure.get());
            }

            AudioTrack track = loadedTrack.get();
            assertNotNull(track, "YouTube returned no playable track");
            AtomicReference<FriendlyException> playbackFailure = new AtomicReference<>();
            player = manager.createPlayer();
            player.addListener(new AudioEventAdapter() {
                @Override
                public void onTrackException(
                        AudioPlayer failedPlayer, AudioTrack failedTrack, FriendlyException exception) {
                    playbackFailure.set(exception);
                }
            });
            player.playTrack(track.makeClone());

            AudioFrame frame = player.provide(20, TimeUnit.SECONDS);
            FriendlyException failure = playbackFailure.get();
            if (failure != null) {
                fail("YouTube playback failed before PCM arrived: " + failure.getMessage());
            }
            assertNotNull(frame, "YouTube returned no PCM frame");
            assertTrue(frame.getData().length > 0, "YouTube returned an empty PCM frame");

            int sampleRate = frame.getFormat().sampleRate;
            int decodedFrames = pcmFrameCount(frame);
            int prebufferTarget = sampleRate * 10;
            while (decodedFrames < prebufferTarget) {
                frame = player.provide(5, TimeUnit.SECONDS);
                failure = playbackFailure.get();
                if (failure != null) {
                    fail("YouTube playback failed during prebuffer: " + failure.getMessage());
                }
                assertNotNull(frame, "YouTube stopped before the ten-second prebuffer completed");
                decodedFrames += pcmFrameCount(frame);
            }
            assertTrue(decodedFrames >= prebufferTarget, "YouTube did not produce the required prebuffer");
        } finally {
            if (player != null) player.destroy();
            manager.shutdown();
        }
    }

    private static int pcmFrameCount(AudioFrame frame) {
        int channelCount = frame.getFormat().channelCount;
        assertTrue(channelCount > 0, "YouTube returned an invalid channel count");
        return frame.getData().length / (Short.BYTES * channelCount);
    }
}
