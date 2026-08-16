package com.audiophilecraft.sound;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class AudioPlaybackRetryPolicyTest {
    @Test
    void performsSevenRetriesAfterTheInitialAttempt() {
        assertEquals(7, AudioPlaybackController.MAX_URL_RETRIES);
    }

    @Test
    void retryBackoffUsesTheExpectedBoundedSchedule() {
        assertEquals(750L, AudioPlaybackController.retryBaseDelayMs(1));
        assertEquals(1_250L, AudioPlaybackController.retryBaseDelayMs(2));
        assertEquals(2_000L, AudioPlaybackController.retryBaseDelayMs(3));
        assertEquals(3_000L, AudioPlaybackController.retryBaseDelayMs(4));
        assertEquals(4_000L, AudioPlaybackController.retryBaseDelayMs(5));
        assertEquals(5_000L, AudioPlaybackController.retryBaseDelayMs(6));
        assertEquals(6_000L, AudioPlaybackController.retryBaseDelayMs(7));
        assertEquals(
                22_000L,
                java.util.stream.LongStream.rangeClosed(1, AudioPlaybackController.MAX_URL_RETRIES)
                        .map(retry -> AudioPlaybackController.retryBaseDelayMs((int) retry))
                        .sum());
    }

    @Test
    void retryBackoffRejectsOutOfRangeAttempts() {
        assertThrows(IllegalArgumentException.class, () -> AudioPlaybackController.retryBaseDelayMs(0));
        assertThrows(IllegalArgumentException.class, () -> AudioPlaybackController.retryBaseDelayMs(8));
    }

    @Test
    void everyRetryUsesACleanYoutubeSourceManagerCandidate() {
        assertFalse(AudioPlaybackController.shouldUseCleanPlayerManager(0));
        for (int retry = 1; retry <= AudioPlaybackController.MAX_URL_RETRIES; retry++) {
            assertTrue(AudioPlaybackController.shouldUseCleanPlayerManager(retry));
        }
    }
}
