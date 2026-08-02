package com.audiophilecraft.compat;

import com.audiophilecraft.AudiophileCraft;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import net.fabricmc.loader.api.FabricLoader;

/** Reads Replay Mod playback state without making Replay Mod a required dependency. */
public final class ReplayModAudioBridge {
    private static final PlaybackState INACTIVE = new PlaybackState(false, false);

    private final boolean replayModLoaded;
    private boolean integrationAvailable;
    private Field replayModuleInstance;
    private Method getReplayHandler;
    private Method getReplaySender;
    private Method getReplaySpeed;

    public ReplayModAudioBridge() {
        replayModLoaded = FabricLoader.getInstance().isModLoaded("replaymod");
        integrationAvailable = replayModLoaded && initializeReflection();
    }

    public PlaybackState poll() {
        if (!integrationAvailable) return INACTIVE;

        try {
            Object replayModule = replayModuleInstance.get(null);
            if (replayModule == null) return INACTIVE;

            Object replayHandler = getReplayHandler.invoke(replayModule);
            if (replayHandler == null) return INACTIVE;

            Object replaySender = getReplaySender.invoke(replayHandler);
            if (replaySender == null) return new PlaybackState(true, false);

            Number speed = (Number) getReplaySpeed.invoke(replaySender);
            return new PlaybackState(true, speed.doubleValue() <= 0.0);
        } catch (ReflectiveOperationException | LinkageError | ClassCastException | SecurityException exception) {
            integrationAvailable = false;
            AudiophileCraft.LOGGER.warn(
                    "Replay Mod playback state is unavailable; Replay audio synchronization has been disabled.",
                    exception);
            return INACTIVE;
        }
    }

    private boolean initializeReflection() {
        try {
            ClassLoader loader = ReplayModAudioBridge.class.getClassLoader();
            Class<?> replayModuleClass = Class.forName("com.replaymod.replay.ReplayModReplay", false, loader);
            Class<?> replayHandlerClass = Class.forName("com.replaymod.replay.ReplayHandler", false, loader);
            Class<?> replaySenderClass = Class.forName("com.replaymod.replay.ReplaySender", false, loader);

            replayModuleInstance = replayModuleClass.getField("instance");
            getReplayHandler = replayModuleClass.getMethod("getReplayHandler");
            getReplaySender = replayHandlerClass.getMethod("getReplaySender");
            getReplaySpeed = replaySenderClass.getMethod("getReplaySpeed");
            AudiophileCraft.LOGGER.info("Replay Mod audio synchronization enabled.");
            return true;
        } catch (ReflectiveOperationException | LinkageError | SecurityException exception) {
            AudiophileCraft.LOGGER.warn(
                    "Replay Mod was detected, but its playback state could not be connected."
                            + " Normal AudiophileCraft playback will continue.",
                    exception);
            return false;
        }
    }

    public record PlaybackState(boolean active, boolean paused) {}
}
