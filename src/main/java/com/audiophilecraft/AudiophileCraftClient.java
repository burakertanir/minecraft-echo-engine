package com.audiophilecraft;

import com.audiophilecraft.client.screen.AmplifierScreen;
import com.audiophilecraft.client.screen.SpeakerScreen;
import com.audiophilecraft.registry.ModScreenHandlers;
import com.audiophilecraft.network.ModMessages;
import net.fabricmc.api.ClientModInitializer;
import net.minecraft.client.gui.screen.ingame.HandledScreens;

public class AudiophileCraftClient implements ClientModInitializer {
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

            if (client.player != null && client.world != null) {
                com.audiophilecraft.sound.AudioEngine.getInstance().updateSourcesTick(client.world);
            }
        });

        // Render Frame (60-120Hz+): smooth gain interpolation + listener position
        net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents.START.register(context -> {
            net.minecraft.client.MinecraftClient mc = net.minecraft.client.MinecraftClient.getInstance();
            if (mc.player != null) {
                com.audiophilecraft.sound.AudioEngine.getInstance().updateListener(
                        mc.player.getEyePos(),
                        mc.player.getYaw(),
                        mc.player.getPitch());
                com.audiophilecraft.sound.AudioEngine.getInstance().updateGains();
            }
        });

        // Stop Audio on Disconnect (Main Menu)
        net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents.DISCONNECT
                .register((handler, client) -> {
                    System.out.println("AudiophileCraft: Disconnected. Stopping Audio Engine.");
                    com.audiophilecraft.sound.AudioEngine.getInstance().cleanupEfx();
                    // CRITICAL: Clear the speaker registry so positions from the old world
                    // don't persist into the next world. Without this, stale positions
                    // resolve to AIR blocks and waste OpenAL sources, causing real speakers
                    // in the new world to be silently skipped (source limit hit).
                    com.audiophilecraft.registry.SpeakerRegistry.clear();
                    System.out.println("AudiophileCraft: Speaker registry cleared.");
                });
    }
}
