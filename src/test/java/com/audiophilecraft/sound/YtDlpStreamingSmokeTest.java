package com.audiophilecraft.sound;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

class YtDlpStreamingSmokeTest {
    private static final String TEST_VIDEO = "https://www.youtube.com/watch?v=7hLLOHFmGMU";

    @Test
    @EnabledIfEnvironmentVariable(named = "AUDIOPHILECRAFT_YOUTUBE_SMOKE", matches = "(?i:true|1)")
    void ytDlpResolvedStreamProducesRealPcm() throws Exception {
        YtDlpUrlResolver resolver = new YtDlpUrlResolver();
        assertTrue(resolver.isConfigured(), "yt-dlp must be configured in run/config/audiophilecraft_ytdlp.json");
        assertTrue(resolver.isYoutubeUrl(TEST_VIDEO), "test URL must be recognized as YouTube");

        InternetAudioLoader loader = InternetAudioLoader.getInstance();
        AtomicReference<String> ready = new AtomicReference<>();
        AtomicReference<String> failure = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(1);
        long requestId = loader.loadTrackStreaming(TEST_VIDEO, new InternetAudioLoader.StreamingCallback() {
            @Override
            public void onReady(
                    long id,
                    short[] pcmInterleaved,
                    int decodedFrames,
                    int totalExpectedFrames,
                    int sampleRate,
                    String title) {
                ready.set(title + "|frames=" + decodedFrames + "|rate=" + sampleRate);
                done.countDown();
            }

            @Override
            public void onMoreData(long id, int totalDecoded) {}

            @Override
            public void onComplete(long id, int totalDecodedFrames) {}

            @Override
            public void onFailed(long id, String reason) {
                failure.set(reason);
                done.countDown();
            }
        });

        assertTrue(done.await(180, TimeUnit.SECONDS), "streaming request " + requestId + " timed out");
        if (failure.get() != null) {
            fail("streaming failed: " + failure.get());
        }
        String readyInfo = ready.get();
        assertNotNull(readyInfo, "no READY callback");
        System.out.println("YTDLP_STREAM_READY " + readyInfo);
    }
}
