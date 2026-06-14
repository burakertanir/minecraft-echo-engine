package com.audiophilecraft.sound;

import static org.lwjgl.openal.AL10.*;

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
    private volatile int trackGeneration = 0;
    private volatile String playUrl = "";

    // Mixer
    private volatile float mixerGainSub = 1.0f;
    private volatile float mixerGainMid = 1.0f;
    private volatile float mixerGainLine = 1.0f;
    private boolean midMuted = false;
    private boolean sideMuted = false;

    // 5-band EQ (dB)
    private volatile float[] subEq = new float[5];
    private volatile float[] midEq = new float[5];
    private volatile float[] lineEq = new float[5];

    // 5-band EQ Q (bandwidth), default 1.0
    private float[] subEqQ = new float[] {1f, 1f, 1f, 1f, 1f};
    private float[] midEqQ = new float[] {1f, 1f, 1f, 1f, 1f};
    private float[] lineEqQ = new float[] {1f, 1f, 1f, 1f, 1f};

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
        return trackGeneration;
    }

    public int incrementTrackGeneration() {
        return ++this.trackGeneration;
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
        db = Math.max(-12f, Math.min(db, 12f));
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
        for (StreamSource source : streamSources) {
            if (source.isValid) {
                alSourceStop(source.sourceId);
                source.cleanup();
            }
        }
        streamSources.clear();
        streamBuffers.clear();
        isPlaying = false;
        isPaused = false;
        isManuallyPaused = false;
        playUrl = ""; // Clear stale URL so the UI won't confuse "same URL stopped" with "still active"
        streamStartTime = 0;
        venuePreset = null;
        venuePresetApplied = false;
    }

    /**
     * Start playing a track through this session.
     * Delegates to AudioEngine for OpenAL/EFX/thread orchestration.
     */
    public void playTrack(String trackId, List<BlockPos> speakers, float power, float inputGain) {
        engine.stopAll();
        incrementTrackGeneration();

        AdvancedAcousticScanner.lastPointCloud.clear();
        AdvancedAcousticScanner.lastVenueBlocks.clear();
        AdvancedAcousticScanner.lastSpeakers =
                speakers != null ? new java.util.ArrayList<>(speakers) : new java.util.ArrayList<>();
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
            setPlaying(true);
            setPaused(false);
            for (AudioStreamBuffer buffer : streamBuffers.values()) {
                if (buffer.sampleRate > 0) buffer.syncToTime(BUFFER_LOOKAHEAD);
            }

            World world = MinecraftClient.getInstance().world;
            int[] counts = SpeakerClusterer.countSpeakerTypes(speakers, world);
            List<List<BlockPos>> clusters = SpeakerClusterer.clusterSpeakers(speakers);
            engine.createSourcesFromClusters(this, clusters, counts, world, power, inputGain);
            engine.startPlaybackWithVenueScan(this, world, speakers, false);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
