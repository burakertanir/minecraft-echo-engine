package com.audiophilecraft.sound;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sedmelluq.discord.lavaplayer.player.DefaultAudioPlayerManager;
import dev.lavalink.youtube.YoutubeAudioSourceManager;
import dev.lavalink.youtube.clients.AndroidVrWithThumbnail;
import dev.lavalink.youtube.clients.MusicWithThumbnail;
import dev.lavalink.youtube.clients.TvHtml5SimplyWithThumbnail;
import dev.lavalink.youtube.clients.WebEmbeddedWithThumbnail;
import dev.lavalink.youtube.clients.WebWithThumbnail;
import java.util.Arrays;
import java.util.List;
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

    @Test
    void anonymousYoutubeClientsPreserveOfficialFallbacksAndThumbnails() {
        YoutubeAudioSourceManager source = InternetAudioLoader.createYoutubeSourceManager();
        try {
            assertEquals(
                    List.of(
                            MusicWithThumbnail.class,
                            AndroidVrWithThumbnail.class,
                            WebWithThumbnail.class,
                            WebEmbeddedWithThumbnail.class,
                            TvHtml5SimplyWithThumbnail.class),
                    Arrays.stream(source.getClients()).map(Object::getClass).toList());
        } finally {
            source.shutdown();
        }
    }
}
