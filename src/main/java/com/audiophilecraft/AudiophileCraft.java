package com.audiophilecraft;

import com.audiophilecraft.registry.ModBlockEntities;
import com.audiophilecraft.registry.ModBlocks;
import com.audiophilecraft.registry.ModItemGroups;
import com.audiophilecraft.registry.ModItems;
import com.audiophilecraft.registry.ModScreenHandlers;
import com.audiophilecraft.network.ModMessages;
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

        // Clear speaker registry when a world unloads (server-side).
        // Prevents stale positions from World A leaking into World B.
        net.fabricmc.fabric.api.event.lifecycle.v1.ServerWorldEvents.UNLOAD.register((server, world) -> {
            com.audiophilecraft.registry.SpeakerRegistry.clear();
            LOGGER.info("AudiophileCraft: Speaker registry cleared (world unload).");
        });
    }
}
