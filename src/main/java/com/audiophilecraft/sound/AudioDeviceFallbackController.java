package com.audiophilecraft.sound;

import static org.lwjgl.openal.ALC10.ALC_FALSE;
import static org.lwjgl.openal.ALC10.alcGetContextsDevice;
import static org.lwjgl.openal.ALC10.alcGetCurrentContext;
import static org.lwjgl.openal.ALC10.alcGetInteger;
import static org.lwjgl.openal.ALC10.alcIsExtensionPresent;
import static org.lwjgl.openal.EXTDisconnect.ALC_CONNECTED;

import com.audiophilecraft.AudiophileCraft;

/**
 * Recovers mod-owned OpenAL resources after Minecraft replaces a disconnected
 * audio device.
 */
final class AudioDeviceFallbackController {
    private static final long CHECK_INTERVAL_NANOS = 1_000_000_000L;

    private final AudioEngine engine;
    private long nextCheckNanos;
    private long observedContext;
    private boolean recoveryPending;

    AudioDeviceFallbackController(AudioEngine engine) {
        this.engine = engine;
    }

    void tick() {
        long nowNanos = System.nanoTime();
        if (nowNanos < nextCheckNanos) return;
        nextCheckNanos = nowNanos + CHECK_INTERVAL_NANOS;

        long currentContext = findHealthyContext();
        if (!engine.hasNativeAudioState() && !recoveryPending) {
            if (currentContext != 0L) observedContext = currentContext;
            return;
        }

        if (currentContext == 0L) {
            beginRecovery();
            return;
        }

        boolean contextChanged = observedContext != 0L && observedContext != currentContext;
        boolean resourcesInvalid = !contextChanged && !engine.nativeAudioResourcesValid();
        if (contextChanged || resourcesInvalid) {
            beginRecovery();
        }

        observedContext = currentContext;
        if (recoveryPending && engine.restoreAudioBackend()) {
            recoveryPending = false;
            AudiophileCraft.LOGGER.info("AudiophileCraft audio backend recovered; playback can be started again.");
        }
    }

    void reset() {
        nextCheckNanos = 0L;
        observedContext = 0L;
        recoveryPending = false;
    }

    private void beginRecovery() {
        if (recoveryPending) return;

        recoveryPending = true;
        engine.abandonLostAudioBackend();
        AudiophileCraft.LOGGER.warn(
                "AudiophileCraft detected an unavailable OpenAL backend; stale playback resources were released.");
    }

    private static long findHealthyContext() {
        try {
            long context = alcGetCurrentContext();
            if (context == 0L) return 0L;

            long device = alcGetContextsDevice(context);
            if (device == 0L) return 0L;
            if (alcIsExtensionPresent(device, "ALC_EXT_disconnect")
                    && alcGetInteger(device, ALC_CONNECTED) == ALC_FALSE) {
                return 0L;
            }
            return context;
        } catch (RuntimeException exception) {
            return 0L;
        }
    }
}
