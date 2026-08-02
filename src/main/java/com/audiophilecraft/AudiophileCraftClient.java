package com.audiophilecraft;

import com.audiophilecraft.client.screen.AmplifierScreen;
import com.audiophilecraft.client.screen.SpeakerScreen;
import com.audiophilecraft.compat.ReplayModAudioBridge;
import com.audiophilecraft.network.ModMessages;
import com.audiophilecraft.registry.ModScreenHandlers;
import com.audiophilecraft.sound.AudioEngine;
import net.fabricmc.api.ClientModInitializer;
import net.minecraft.client.gui.screen.ingame.HandledScreens;

public class AudiophileCraftClient implements ClientModInitializer {
    private net.minecraft.client.world.ClientWorld trackedWorld;
    private final ReplayModAudioBridge replayModAudioBridge = new ReplayModAudioBridge();
    private boolean replayPlaybackActive;

    @Override
    public void onInitializeClient() {
        HandledScreens.register(ModScreenHandlers.AMPLIFIER_SCREEN_HANDLER, AmplifierScreen::new);
        HandledScreens.register(ModScreenHandlers.SPEAKER_SCREEN_HANDLER, SpeakerScreen::new);
        ModMessages.registerS2CPackets();

        // Initialize Live Tuning Config (hot-reload JSON)
        com.audiophilecraft.config.LiveTuningConfig.initialize();

        // NOTE: AudioEngine now uses lazy initialization on Minecraft's own OpenAL
        // context.
        // No init() call needed here.

        // Client Tick (20Hz): cleanup, pause/resume, broken speaker detection
        net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents.END_CLIENT_TICK.register(client -> {
            // Hot-reload tuning config (checks file every 20 ticks = 1 second)
            com.audiophilecraft.config.LiveTuningConfig.get().checkReload();

            AudioEngine engine = AudioEngine.getInstance();
            ReplayModAudioBridge.PlaybackState replayState = replayModAudioBridge.poll();
            boolean replayPlaybackClosed = replayPlaybackActive && !replayState.active();
            if (replayPlaybackClosed) {
                cleanupClientAudio("Replay playback closed");
            }
            replayPlaybackActive = replayState.active();
            engine.setExternalPlaybackPaused(replayState.paused());

            if (client.world != trackedWorld) {
                if (trackedWorld != null && !replayPlaybackClosed) {
                    stopClientAudio("Client world changed");
                }
                trackedWorld = client.world;
            }

            if (client.player != null && client.world != null) {
                engine.updateAudioDeviceFallback();
                engine.updateSourcesTick(client.world);
            }
        });

        // Render Frame (60-120Hz+): smooth gain interpolation + listener position
        net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents.START.register(context -> {
            net.minecraft.client.MinecraftClient mc = net.minecraft.client.MinecraftClient.getInstance();
            net.minecraft.client.render.Camera camera = context.camera();
            if (camera != null && camera.isReady()) {
                com.audiophilecraft.sound.AudioEngine.getInstance()
                        .updateListener(camera.getPos(), camera.getYaw(), camera.getPitch());
            } else if (mc.player != null) {
                // Fallback if camera is somehow not ready
                com.audiophilecraft.sound.AudioEngine.getInstance()
                        .updateListener(mc.player.getEyePos(), mc.player.getYaw(), mc.player.getPitch());
            }
        });

        // Stop ALL audio on Disconnect (Main Menu) — prevents audio leaking after server crash/disconnect
        net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents.DISCONNECT.register(
                (handler, client) -> {
                    trackedWorld = null;
                    replayPlaybackActive = false;
                    cleanupClientAudio("Client disconnected");
                });
    }

    private static void cleanupClientAudio(String reason) {
        AudiophileCraft.LOGGER.info("{}; cleaning up client audio.", reason);
        AudioEngine engine = AudioEngine.getInstance();
        engine.setExternalPlaybackPaused(false);
        engine.stopAll();
        engine.cleanupEfx();
        com.audiophilecraft.registry.SpeakerRegistry.clear();
    }

    private static void stopClientAudio(String reason) {
        AudiophileCraft.LOGGER.info("{}; stopping client audio.", reason);
        AudioEngine.getInstance().stopAll();
    }
}
