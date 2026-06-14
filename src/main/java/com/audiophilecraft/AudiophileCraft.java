package com.audiophilecraft;

import com.audiophilecraft.command.TestFacilityCommand;
import com.audiophilecraft.network.ModMessages;
import com.audiophilecraft.registry.ModBlockEntities;
import com.audiophilecraft.registry.ModBlocks;
import com.audiophilecraft.registry.ModItemGroups;
import com.audiophilecraft.registry.ModItems;
import com.audiophilecraft.registry.ModScreenHandlers;
import net.fabricmc.api.ModInitializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class AudiophileCraft implements ModInitializer {
    public static final String MOD_ID = "audiophilecraft";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    @Override
    public void onInitialize() {
        LOGGER.info("AudiophileCraft Initializing...");

        ModBlocks.registerModBlocks();
        ModItems.registerModItems();
        ModBlockEntities.register();
        ModItemGroups.registerItemGroups();
        ModScreenHandlers.registerScreenHandlers();
        ModMessages.registerC2SPackets();
        TestFacilityCommand.register();

        // Clear speaker registry per-dimension when a world unloads (server-side).
        // Prevents stale positions from World A leaking into World B.
        net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents.SERVER_STOPPED.register((server) -> {
            com.audiophilecraft.registry.SpeakerRegistry.clear();
            LOGGER.info("AudiophileCraft: Speaker registry cleared (server stopped).");
        });

        // Clear dimension-specific entries when a world unloads.
        net.fabricmc.fabric.api.event.lifecycle.v1.ServerWorldEvents.UNLOAD.register((server, world) -> {
            com.audiophilecraft.registry.SpeakerRegistry.clear(world.getRegistryKey());
            LOGGER.info(
                    "AudiophileCraft: Speaker registry cleared for dimension {}",
                    world.getRegistryKey().getValue());
        });
    }
}
