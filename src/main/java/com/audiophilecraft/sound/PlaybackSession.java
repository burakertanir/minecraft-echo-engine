package com.audiophilecraft.sound;

import static org.lwjgl.openal.AL10.*;

import com.audiophilecraft.AudiophileCraft;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import net.minecraft.client.MinecraftClient;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

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

    // Physical speaker groups and their independently scanned acoustic profiles
    private final List<EmitterGroup> emitterGroups = new CopyOnWriteArrayList<>();

    // Audio buffers by speaker type
    private final Map<String, AudioStreamBuffer> streamBuffers = new ConcurrentHashMap<>();

    // Venue reverb
    private AdvancedAcousticScanner.VenuePreset venuePreset = null;
    private boolean venuePresetApplied = false;
    private AdvancedAcousticScanner.VenueDescriptor storedVenueDescriptor = null;
    private net.minecraft.util.math.Vec3d storedVenueProbePos = null;
    private long lastConfigGeneration = 0;

    // Time tracking
    private volatile long streamStartTime = 0;
    private volatile boolean isPlaying = false;
    private long pauseStartTimestamp = 0;

    // Playback control
    private volatile boolean isPaused = false;
    private volatile boolean isManuallyPaused = false;
    private volatile boolean isSeeking = false;
    private final java.util.concurrent.atomic.AtomicInteger trackGeneration =
            new java.util.concurrent.atomic.AtomicInteger(0);
    private volatile String playUrl = "";

    // Mixer
    private volatile float mixerGainSub = 1.0f;
    private volatile float mixerGainMid = 1.0f;
    private volatile float mixerGainLine = 1.0f;
    private volatile boolean midMuted = false;
    private volatile boolean sideMuted = false;

    // 5-band EQ (dB) — elements accessed only under synchronized(getEqDb/setEqDb)
    private final float[] subEq = new float[] {0.0f, 0.0f, 0.0f, 0.0f, 0.0f};
    private final float[] midEq = new float[] {-3.0f, 0.0f, 0.0f, 2.0f, 3.0f};
    private final float[] lineEq = new float[] {-3.0f, 0.0f, 0.0f, 2.0f, 3.0f};

    // 5-band EQ Q (bandwidth), default 1.0
    private final float[] subEqQ = new float[] {1f, 1f, 1f, 1f, 1f};
    private final float[] midEqQ = new float[] {0.1f, 1.0f, 1.0f, 0.7f, 0.1f};
    private final float[] lineEqQ = new float[] {0.1f, 1.0f, 1.0f, 0.7f, 0.1f};

    public PlaybackSession(AudioEngine engine) {
        this.engine = engine;
    }

    // --- Source accessors ---

    public List<StreamSource> getStreamSources() {
        return streamSources;
    }

    public int getSourceCount() {
        return streamSources.size();
    }

    public List<EmitterGroup> getEmitterGroups() {
        return emitterGroups;
    }

    // --- Buffer accessors ---

    public Map<String, AudioStreamBuffer> getStreamBuffers() {
        return streamBuffers;
    }

    // --- Venue ---

    public AdvancedAcousticScanner.VenuePreset getVenuePreset() {
        return venuePreset;
    }

    public void setVenuePreset(AdvancedAcousticScanner.VenuePreset preset) {
        this.venuePreset = preset;
    }

    public boolean isVenuePresetApplied() {
        return venuePresetApplied;
    }

    public void setVenuePresetApplied(boolean applied) {
        this.venuePresetApplied = applied;
    }

    public AdvancedAcousticScanner.VenueDescriptor getStoredVenueDescriptor() {
        return storedVenueDescriptor;
    }

    public void setStoredVenueDescriptor(AdvancedAcousticScanner.VenueDescriptor d) {
        this.storedVenueDescriptor = d;
    }

    public net.minecraft.util.math.Vec3d getStoredVenueProbePos() {
        return storedVenueProbePos;
    }

    public void setStoredVenueProbePos(net.minecraft.util.math.Vec3d p) {
        this.storedVenueProbePos = p;
    }

    public long getLastConfigGeneration() {
        return lastConfigGeneration;
    }

    public void setLastConfigGeneration(long g) {
        this.lastConfigGeneration = g;
    }

    // --- Reflections ---

    // --- Clock ---

    public long getStreamStartTime() {
        return streamStartTime;
    }

    public void setStreamStartTime(long t) {
        this.streamStartTime = t;
    }

    public boolean isPlaying() {
        return isPlaying;
    }

    public void setPlaying(boolean p) {
        this.isPlaying = p;
    }

    public long getPauseStartTimestamp() {
        return pauseStartTimestamp;
    }

    public void setPauseStartTimestamp(long t) {
        this.pauseStartTimestamp = t;
    }

    // --- Control ---

    public boolean isPaused() {
        return isPaused;
    }

    public void setPaused(boolean p) {
        this.isPaused = p;
    }

    public boolean isManuallyPaused() {
        return isManuallyPaused;
    }

    public void setManuallyPaused(boolean p) {
        this.isManuallyPaused = p;
    }

    public boolean isSeeking() {
        return isSeeking;
    }

    public void setSeeking(boolean s) {
        this.isSeeking = s;
    }

    public String getPlayUrl() {
        return playUrl;
    }

    public void setPlayUrl(String url) {
        this.playUrl = url;
    }

    public int getTrackGeneration() {
        return trackGeneration.get();
    }

    public int incrementTrackGeneration() {
        return trackGeneration.incrementAndGet();
    }

    // --- Mixer ---

    public float getMixerGain(String speakerType) {
        switch (speakerType) {
            case "sub":
                return mixerGainSub;
            case "mid":
                return mixerGainMid;
            case "line":
                return mixerGainLine;
            default:
                return 1.0f;
        }
    }

    public void setMixerGain(String speakerType, float gain) {
        gain = Math.max(0.0f, Math.min(gain, 1.0f));
        switch (speakerType) {
            case "sub":
                mixerGainSub = gain;
                break;
            case "mid":
                mixerGainMid = gain;
                break;
            case "line":
                mixerGainLine = gain;
                break;
        }
    }

    public boolean isMidMuted() {
        return midMuted;
    }

    public void setMidMuted(boolean m) {
        this.midMuted = m;
    }

    public boolean isSideMuted() {
        return sideMuted;
    }

    public void setSideMuted(boolean s) {
        this.sideMuted = s;
    }

    public synchronized float getEqDb(String speakerType, int band) {
        if (band < 0 || band > 4) return 0f;
        float[] eq = getEqArray(speakerType);
        return eq != null ? eq[band] : 0f;
    }

    public synchronized void setEqDb(String speakerType, int band, float db) {
        if (band < 0 || band > 4) return;
        db = Math.max(-9f, Math.min(db, 9f));
        float[] eq = getEqArray(speakerType);
        if (eq != null) eq[band] = db;
    }

    private float[] getEqArray(String speakerType) {
        switch (speakerType) {
            case "sub":
                return subEq;
            case "mid":
                return midEq;
            case "line":
                return lineEq;
            default:
                return null;
        }
    }

    private float[] getEqQArray(String speakerType) {
        switch (speakerType) {
            case "sub":
                return subEqQ;
            case "mid":
                return midEqQ;
            case "line":
                return lineEqQ;
            default:
                return null;
        }
    }

    public synchronized float getEqQ(String speakerType, int band) {
        if (band < 0 || band > 4) return 1f;
        float[] eq = getEqQArray(speakerType);
        return eq != null ? eq[band] : 1f;
    }

    public synchronized void setEqQ(String speakerType, int band, float q) {
        if (band < 0 || band > 4) return;
        q = Math.max(0.1f, Math.min(q, 10.0f));
        float[] eq = getEqQArray(speakerType);
        if (eq != null) eq[band] = q;
    }

    // --- Cleanup ---

    public void stopAll() {
        incrementTrackGeneration();
        isPlaying = false;
        isPaused = false;
        isManuallyPaused = false;
        streamStartTime = 0;
        playUrl = ""; // Clear stale URL so the UI won't confuse "same URL stopped" with "still active"

        for (StreamSource source : streamSources) {
            if (source.isValid) {
                alSourceStop(source.sourceId);
                source.cleanup();
            }
        }
        streamSources.clear();
        emitterGroups.clear();
        for (AudioStreamBuffer buffer : streamBuffers.values()) {
            buffer.cleanup();
        }
        streamBuffers.clear();
        venuePreset = null;
        venuePresetApplied = false;
    }

    void abandonAfterAudioDeviceLoss() {
        incrementTrackGeneration();
        isPlaying = false;
        isPaused = false;
        isManuallyPaused = false;
        isSeeking = false;
        streamStartTime = 0L;
        pauseStartTimestamp = 0L;
        playUrl = "";

        for (StreamSource source : streamSources) {
            source.releaseNativeMemory();
        }
        streamSources.clear();
        emitterGroups.clear();
        for (AudioStreamBuffer buffer : streamBuffers.values()) {
            buffer.cleanup();
        }
        streamBuffers.clear();
        venuePreset = null;
        venuePresetApplied = false;
        storedVenueDescriptor = null;
        storedVenueProbePos = null;
    }

    /**
     * Start playing a track through this session.
     * Delegates to AudioEngine for OpenAL/EFX/thread orchestration.
     */
    public void playTrack(String trackId, List<BlockPos> speakers, float power, float inputGain) {
        engine.stopAll();
        incrementTrackGeneration();

        AdvancedAcousticScanner.resetDebugState(speakers);
        setVenuePreset(null);
        setVenuePresetApplied(false);
        setStoredVenueDescriptor(null);
        setStoredVenueProbePos(null);
        com.audiophilecraft.client.screen.PointCloudRenderer.invalidateCache();

        while (alGetError() != AL_NO_ERROR) {
            /* drain */
        }
        engine.initEfx();

        if (speakers == null || speakers.isEmpty()) return;

        try {
            engine.prepareStreamBuffers(this, trackId);
            for (AudioStreamBuffer buffer : streamBuffers.values()) {
                if (buffer.sampleRate > 0) buffer.syncToTime(BUFFER_LOOKAHEAD);
            }

            World world = MinecraftClient.getInstance().world;
            int[] counts = SpeakerClusterer.countSpeakerTypes(speakers, world);
            List<List<BlockPos>> clusters = SpeakerClusterer.clusterSpeakers(speakers);
            engine.createSourcesFromClusters(this, clusters, counts, world, power, inputGain);
            engine.startPlaybackWithVenueScan(this, world, speakers, true);
        } catch (Exception e) {
            AudiophileCraft.LOGGER.error(
                    "Failed to rebuild playback session for track {} with {} speakers.", trackId, speakers.size(), e);
        }
    }
}
