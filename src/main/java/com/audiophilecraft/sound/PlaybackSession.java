package com.audiophilecraft.sound;

import com.audiophilecraft.block.LineArrayBlock;
import com.audiophilecraft.block.MidRangeBlock;
import com.audiophilecraft.block.SubwooferBlock;
import com.audiophilecraft.block.entity.SpeakerBlockEntity;
import net.minecraft.client.MinecraftClient;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import org.lwjgl.BufferUtils;
import org.lwjgl.system.MemoryUtil;

import java.nio.IntBuffer;
import java.nio.ShortBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.lwjgl.openal.AL10.*;
import static org.lwjgl.openal.AL11.AL_SEC_OFFSET;

/**
 * Holds all state for one playback session: speakers, buffers, mixer, venue reverb, clock.
 * Multiple sessions can coexist — each with its own track, mixer settings, and venue preset.
 * AudioEngine orchestrates sessions and owns the shared EFX reverb slot.
 */
public class PlaybackSession {

    private static final double BUFFER_LOOKAHEAD = 0.5;

    // Reference back to AudioEngine for EFX/source pool
    private final AudioEngine engine;

    // Speaker source list (thread-safe: main/audio thread)
    private final List<StreamSource> streamSources = new CopyOnWriteArrayList<>();

    // Audio buffers by speaker type
    private final Map<String, AudioStreamBuffer> streamBuffers = new ConcurrentHashMap<>();

    // Venue reverb
    private AdvancedAcousticScanner.VenuePreset venuePreset = null;
    private boolean venuePresetApplied = false;
    private AdvancedAcousticScanner.VenueDescriptor storedVenueDescriptor = null;
    private net.minecraft.util.math.Vec3d storedVenueProbePos = null;
    private long lastConfigGeneration = 0;

    // Dynamic early reflections
    private volatile float currentReflGain = -1.0f;
    private volatile float currentReflDelay = -1.0f;

    // Time tracking
    private volatile long streamStartTime = 0;
    private volatile boolean isPlaying = false;
    private long pauseStartTimestamp = 0;

    // Playback control
    private volatile boolean isPaused = false;
    private volatile boolean isSeeking = false;
    private volatile int trackGeneration = 0;

    // Mixer
    private volatile float mixerGainSub = 1.0f;
    private volatile float mixerGainMid = 1.0f;
    private volatile float mixerGainLine = 1.0f;
    private volatile boolean midMuted = false;
    private volatile boolean sideMuted = false;

    // 5-band EQ
    private volatile float[] subEq = new float[5];
    private volatile float[] midEq = new float[5];
    private volatile float[] lineEq = new float[5];

    public PlaybackSession(AudioEngine engine) {
        this.engine = engine;
    }

    // --- Source accessors ---

    public List<StreamSource> getStreamSources() { return streamSources; }
    public int getSourceCount() { return streamSources.size(); }

    // --- Buffer accessors ---

    public Map<String, AudioStreamBuffer> getStreamBuffers() { return streamBuffers; }

    // --- Venue ---

    public AdvancedAcousticScanner.VenuePreset getVenuePreset() { return venuePreset; }
    public void setVenuePreset(AdvancedAcousticScanner.VenuePreset preset) { this.venuePreset = preset; }

    public boolean isVenuePresetApplied() { return venuePresetApplied; }
    public void setVenuePresetApplied(boolean applied) { this.venuePresetApplied = applied; }

    public AdvancedAcousticScanner.VenueDescriptor getStoredVenueDescriptor() { return storedVenueDescriptor; }
    public void setStoredVenueDescriptor(AdvancedAcousticScanner.VenueDescriptor d) { this.storedVenueDescriptor = d; }

    public net.minecraft.util.math.Vec3d getStoredVenueProbePos() { return storedVenueProbePos; }
    public void setStoredVenueProbePos(net.minecraft.util.math.Vec3d p) { this.storedVenueProbePos = p; }

    public long getLastConfigGeneration() { return lastConfigGeneration; }
    public void setLastConfigGeneration(long g) { this.lastConfigGeneration = g; }

    // --- Reflections ---

    public float getCurrentReflGain() { return currentReflGain; }
    public void setCurrentReflGain(float g) { this.currentReflGain = g; }
    public float getCurrentReflDelay() { return currentReflDelay; }
    public void setCurrentReflDelay(float d) { this.currentReflDelay = d; }

    // --- Clock ---

    public long getStreamStartTime() { return streamStartTime; }
    public void setStreamStartTime(long t) { this.streamStartTime = t; }

    public boolean isPlaying() { return isPlaying; }
    public void setPlaying(boolean p) { this.isPlaying = p; }

    public long getPauseStartTimestamp() { return pauseStartTimestamp; }
    public void setPauseStartTimestamp(long t) { this.pauseStartTimestamp = t; }

    // --- Control ---

    public boolean isPaused() { return isPaused; }
    public void setPaused(boolean p) { this.isPaused = p; }
    public boolean isSeeking() { return isSeeking; }
    public void setSeeking(boolean s) { this.isSeeking = s; }
    public int getTrackGeneration() { return trackGeneration; }
    public int incrementTrackGeneration() { return ++this.trackGeneration; }

    // --- Mixer ---

    public float getMixerGain(String speakerType) {
        switch (speakerType) {
            case "sub":  return mixerGainSub;
            case "mid":  return mixerGainMid;
            case "line": return mixerGainLine;
            default:     return 1.0f;
        }
    }

    public void setMixerGain(String speakerType, float gain) {
        gain = Math.max(0.0f, Math.min(gain, 1.0f));
        switch (speakerType) {
            case "sub":  mixerGainSub = gain; break;
            case "mid":  mixerGainMid = gain; break;
            case "line": mixerGainLine = gain; break;
        }
    }

    public boolean isMidMuted() { return midMuted; }
    public void setMidMuted(boolean m) { this.midMuted = m; }
    public boolean isSideMuted() { return sideMuted; }
    public void setSideMuted(boolean s) { this.sideMuted = s; }

    public synchronized float getEqDb(String speakerType, int band) {
        if (band < 0 || band > 4) return 0f;
        float[] eq = getEqArray(speakerType);
        return eq != null ? eq[band] : 0f;
    }

    public synchronized void setEqDb(String speakerType, int band, float db) {
        if (band < 0 || band > 4) return;
        db = Math.max(-12f, Math.min(db, 12f));
        float[] eq = getEqArray(speakerType);
        if (eq != null) eq[band] = db;
    }

    private float[] getEqArray(String speakerType) {
        switch (speakerType) {
            case "sub":  return subEq;
            case "mid":  return midEq;
            case "line": return lineEq;
            default:     return null;
        }
    }

    // --- Cleanup ---

    public void stopAll() {
        for (StreamSource source : streamSources) {
            if (source.isValid) {
                alSourceStop(source.sourceId);
            }
        }
        streamSources.clear();
        streamBuffers.clear();
        isPlaying = false;
    }
}
