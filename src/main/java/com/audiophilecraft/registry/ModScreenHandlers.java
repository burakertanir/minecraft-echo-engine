package com.audiophilecraft.registry;

import com.audiophilecraft.screen.AmplifierScreenHandler;
import com.audiophilecraft.screen.SpeakerScreenHandler;
import net.fabricmc.fabric.api.screenhandler.v1.ExtendedScreenHandlerType;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.screen.ScreenHandlerType;
import net.minecraft.util.Identifier;

public class ModScreenHandlers {
    public static ScreenHandlerType<AmplifierScreenHandler> AMPLIFIER_SCREEN_HANDLER;
    public static ScreenHandlerType<SpeakerScreenHandler> SPEAKER_SCREEN_HANDLER;

    public static void registerScreenHandlers() {
        AMPLIFIER_SCREEN_HANDLER = Registry.register(
                Registries.SCREEN_HANDLER,
                new Identifier("audiophilecraft", "amplifier_screen_handler"),
                new ExtendedScreenHandlerType<>(AmplifierScreenHandler::new));

        SPEAKER_SCREEN_HANDLER = Registry.register(
                Registries.SCREEN_HANDLER,
                new Identifier("audiophilecraft", "speaker_screen_handler"),
                new ExtendedScreenHandlerType<>(SpeakerScreenHandler::new));
    }
}
