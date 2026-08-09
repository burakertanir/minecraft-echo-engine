package com.audiophilecraft.sound;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sedmelluq.discord.lavaplayer.player.DefaultAudioPlayerManager;
import dev.lavalink.youtube.YoutubeAudioSourceManager;
import org.junit.jupiter.api.Test;
import org.mozilla.javascript.Context;

class InternetAudioDependencyCompatibilityTest {
    @Test
    void usesPatchedJacksonRuntimeForInternetMetadata() {
        assertEquals("2.18.9", ObjectMapper.class.getPackage().getImplementationVersion());
        assertDoesNotThrow(() -> new ObjectMapper().readTree("{\"title\":\"test\",\"duration\":1}"));
    }

    @Test
    void youtubeSourceInitializesWithPinnedRhinoRuntime() {
        assertEquals("1.7.15", Context.class.getPackage().getImplementationVersion());

        DefaultAudioPlayerManager playerManager = new DefaultAudioPlayerManager();
        try {
            assertDoesNotThrow(() -> playerManager.registerSourceManager(new YoutubeAudioSourceManager()));
        } finally {
            playerManager.shutdown();
        }
    }
}
