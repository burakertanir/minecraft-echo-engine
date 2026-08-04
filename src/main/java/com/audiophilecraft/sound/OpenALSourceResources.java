package com.audiophilecraft.sound;

import static org.lwjgl.openal.AL10.AL_BUFFER;
import static org.lwjgl.openal.AL10.AL_FALSE;
import static org.lwjgl.openal.AL10.AL_GAIN;
import static org.lwjgl.openal.AL10.AL_NO_ERROR;
import static org.lwjgl.openal.AL10.AL_POSITION;
import static org.lwjgl.openal.AL10.AL_SOURCE_RELATIVE;
import static org.lwjgl.openal.AL10.alDeleteSources;
import static org.lwjgl.openal.AL10.alGetError;
import static org.lwjgl.openal.AL10.alSource3f;
import static org.lwjgl.openal.AL10.alSourcePause;
import static org.lwjgl.openal.AL10.alSourcePlay;
import static org.lwjgl.openal.AL10.alSourceStop;
import static org.lwjgl.openal.AL10.alSourcef;
import static org.lwjgl.openal.AL10.alSourcei;
import static org.lwjgl.openal.EXTEfx.AL_AUXILIARY_SEND_FILTER;
import static org.lwjgl.openal.EXTEfx.AL_DIRECT_FILTER;
import static org.lwjgl.openal.EXTEfx.AL_FILTER_NULL;
import static org.lwjgl.openal.EXTEfx.AL_LOWPASS_GAIN;
import static org.lwjgl.openal.EXTEfx.AL_LOWPASS_GAINHF;
import static org.lwjgl.openal.EXTEfx.alDeleteFilters;
import static org.lwjgl.openal.EXTEfx.alFilterf;

import com.audiophilecraft.AudiophileCraft;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import org.lwjgl.openal.AL11;

/** Owns one OpenAL source and its direct, room-send and echo-send filters. */
final class OpenALSourceResources {
    private static final int AL_SOURCE_RADIUS = 0x1031;

    private final int sourceId;
    private final int directFilterId;
    private final int roomSendFilterId;
    private final int echoSendFilterId;

    OpenALSourceResources(int sourceId, int directFilterId, int roomSendFilterId, int echoSendFilterId) {
        this.sourceId = sourceId;
        this.directFilterId = directFilterId;
        this.roomSendFilterId = roomSendFilterId;
        this.echoSendFilterId = echoSendFilterId;
    }

    boolean hasDirectFilter() {
        return directFilterId != 0;
    }

    boolean hasRoomSendFilter() {
        return roomSendFilterId != 0;
    }

    boolean hasEchoSendFilter() {
        return echoSendFilterId != 0;
    }

    void start() {
        alSourcePlay(sourceId);
    }

    void pause() {
        alSourcePause(sourceId);
    }

    void stop() {
        alSourceStop(sourceId);
    }

    void resume() {
        alSourcePlay(sourceId);
    }

    void updateSpatialPosition(BlockPos position, Vec3d listenerPosition, float yFlatten) {
        float sourceX = position.getX() + 0.5f;
        float sourceY = position.getY() + 0.5f;
        float sourceZ = position.getZ() + 0.5f;
        if (listenerPosition != null) {
            float clampedFlatten = Math.max(0.0f, Math.min(1.0f, yFlatten));
            sourceY = (float) (listenerPosition.y + (sourceY - listenerPosition.y) * clampedFlatten);
        }

        alSourcei(sourceId, AL_SOURCE_RELATIVE, AL_FALSE);
        alSource3f(sourceId, AL_POSITION, sourceX, sourceY, sourceZ);
    }

    void setRadius(float radius) {
        alSourcef(sourceId, AL_SOURCE_RADIUS, radius);
    }

    void setGain(float gain) {
        alSourcef(sourceId, AL_GAIN, gain);
    }

    void applyDirectFilter(float gain, float highFrequencyGain) {
        if (!hasDirectFilter()) return;
        alFilterf(directFilterId, AL_LOWPASS_GAIN, gain);
        alFilterf(directFilterId, AL_LOWPASS_GAINHF, highFrequencyGain);
        alSourcei(sourceId, AL_DIRECT_FILTER, directFilterId);
    }

    void applyRoomSend(int auxiliarySlotId, float gain, float highFrequencyGain) {
        if (!hasRoomSendFilter() || auxiliarySlotId == 0) return;
        alFilterf(roomSendFilterId, AL_LOWPASS_GAIN, gain);
        alFilterf(roomSendFilterId, AL_LOWPASS_GAINHF, highFrequencyGain);
        AL11.alSource3i(sourceId, AL_AUXILIARY_SEND_FILTER, auxiliarySlotId, 0, roomSendFilterId);
    }

    void applyEchoSend(int auxiliarySlotId, float gain, float highFrequencyGain) {
        if (!hasEchoSendFilter() || auxiliarySlotId == 0) return;
        alFilterf(echoSendFilterId, AL_LOWPASS_GAIN, gain);
        alFilterf(echoSendFilterId, AL_LOWPASS_GAINHF, highFrequencyGain);
        AL11.alSource3i(sourceId, AL_AUXILIARY_SEND_FILTER, auxiliarySlotId, 1, echoSendFilterId);
    }

    void delete(StreamAudioRenderer renderer) {
        // Force-stop to ensure all queued buffers become processed/detachable.
        alSourceStop(sourceId);
        // Detach ALL buffers at once — works reliably on stopped sources and
        // avoids the old loop that could leave queued-but-unprocessed buffers
        // attached, causing alDeleteSources to silently fail (zombie sources).
        alSourcei(sourceId, AL_BUFFER, 0);

        try {
            alSourcei(sourceId, AL_DIRECT_FILTER, AL_FILTER_NULL);
            AL11.alSource3i(sourceId, AL_AUXILIARY_SEND_FILTER, 0, 0, AL_FILTER_NULL);
            AL11.alSource3i(sourceId, AL_AUXILIARY_SEND_FILTER, 0, 1, AL_FILTER_NULL);
        } catch (Exception exception) {
            AudiophileCraft.LOGGER.warn("Failed to detach OpenAL filters/sends from source {}.", sourceId, exception);
        }

        alDeleteSources(sourceId);
        renderer.deleteOpenAlBuffers();
        deleteFilters();

        while (alGetError() != AL_NO_ERROR) {
            // Drain stale OpenAL errors.
        }
    }

    private void deleteFilters() {
        try {
            if (directFilterId != 0) alDeleteFilters(directFilterId);
            if (roomSendFilterId != 0) alDeleteFilters(roomSendFilterId);
            if (echoSendFilterId != 0) alDeleteFilters(echoSendFilterId);
        } catch (Exception exception) {
            AudiophileCraft.LOGGER.warn("Failed to delete OpenAL filters for source {}.", sourceId, exception);
        }
    }
}
