package com.audiophilecraft.sound;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.client.MinecraftClient;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;

/**
 * Stable public facade and coordinator for AudiophileCraft playback.
 *
 * <p>Rendering, effects, runtime scheduling and mixer persistence are owned by
 * focused collaborators. Existing callers continue to use this singleton as
 * the stable audio API.
 */
public class AudioEngine {
    static final double BUFFER_LOOKAHEAD = 0.5;
    static final String TYPE_NORMAL = "normal";
    static final String TYPE_SUB = "sub";
    static final String TYPE_MID = "mid";
    static final String TYPE_LINE = "line";

    private static AudioEngine INSTANCE;

    private final Map<UUID, PlaybackSession> sessions = new ConcurrentHashMap<>();
    private final ListenerController listener = ListenerController.getInstance();
    private final AudioEffectsController effects;
    private final ReverbBusAllocator reverbBusAllocator;
    private final AudioPlaybackController playback;
    private final AudioMixerController mixer;
    private final AudioRuntimeController runtime;
    private final AudioDeviceFallbackController audioDeviceFallback;

    private AudioEngine() {
        effects = new AudioEffectsController();
        reverbBusAllocator = new ReverbBusAllocator(effects);
        playback = new AudioPlaybackController(this, sessions, effects);
        mixer = new AudioMixerController(sessions, this::getActiveSession);
        runtime = new AudioRuntimeController(
                this, sessions, this::getActiveSession, effects, reverbBusAllocator, playback);
        audioDeviceFallback = new AudioDeviceFallbackController(this);
    }

    public static synchronized AudioEngine getInstance() {
        if (INSTANCE == null) {
            INSTANCE = new AudioEngine();
        }
        return INSTANCE;
    }

    public PlaybackSession getActiveSession() {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player != null) {
            return sessions.computeIfAbsent(client.player.getUuid(), key -> new PlaybackSession(this));
        }
        return null;
    }

    public void ensureActiveSession(UUID sessionId) {
        sessions.computeIfAbsent(sessionId, key -> new PlaybackSession(this));
    }

    public int getSlapbackAuxSlotId() {
        return effects.getSlapbackAuxSlotId();
    }

    public float getSlapbackGain() {
        return effects.getSlapbackGain();
    }

    public int getAuxSlotId() {
        return effects.getAuxSlotId();
    }

    public int getAuxSlotId(EmitterGroup emitterGroup) {
        int busIndex = emitterGroup != null ? emitterGroup.roomBusIndex() : 0;
        int slotId = effects.getRoomAuxSlotId(busIndex);
        return slotId != 0 ? slotId : effects.getAuxSlotId();
    }

    void refreshReverbBusAssignments() {
        runtime.refreshReverbBusAssignments();
    }

    public void initEfx() {
        effects.initialize();
    }

    public AdvancedAcousticScanner.VenuePreset getVenuePreset() {
        return effects.getVenuePreset();
    }

    public AdvancedAcousticScanner.VenueDescriptor getStoredVenueDescriptor() {
        return effects.getStoredVenueDescriptor();
    }

    public void updateListener(Vec3d position, float yaw, float pitch) {
        if (getActiveSession() == null) return;
        runtime.updateListenerPosition(position);
        listener.update(position, yaw, pitch);
    }

    void syncListenerToCamera() {
        Vec3d cameraPosition = captureCurrentListenerPosition();
        if (cameraPosition != null) {
            runtime.syncListenerPosition(cameraPosition);
        }
    }

    static Vec3d captureCurrentListenerPosition() {
        MinecraftClient client = MinecraftClient.getInstance();
        net.minecraft.client.render.Camera camera = client.gameRenderer.getCamera();
        if (camera != null && camera.isReady()) {
            return camera.getPos();
        }
        if (client.player != null) {
            return client.player.getEyePos();
        }
        return client.cameraEntity != null ? client.cameraEntity.getPos() : null;
    }

    public float getUnderwaterHFGain() {
        return listener.getUnderwaterHFGain();
    }

    public boolean isMidMuted() {
        return mixer.isMidMuted();
    }

    public void setMidMuted(boolean muted) {
        mixer.setMidMuted(muted);
    }

    public boolean isSideMuted() {
        return mixer.isSideMuted();
    }

    public void setSideMuted(boolean muted) {
        mixer.setSideMuted(muted);
    }

    public float getMixerGain(String speakerType) {
        return mixer.getMixerGain(speakerType);
    }

    public void setMixerGain(String speakerType, float gain) {
        mixer.setMixerGain(speakerType, gain);
    }

    public synchronized float getEqDb(String speakerType, int band) {
        return mixer.getEqDb(speakerType, band);
    }

    public synchronized void setEqDb(String speakerType, int band, float db) {
        mixer.setEqDb(speakerType, band, db);
    }

    public synchronized float getEqQ(String speakerType, int band) {
        return mixer.getEqQ(speakerType, band);
    }

    public synchronized void setEqQ(String speakerType, int band, float q) {
        mixer.setEqQ(speakerType, band, q);
    }

    public void updateGains() {
        if (getActiveSession() == null || runtime.listenerPosition() == null) return;
        effects.ensureVenueReverb();
    }

    public double getTimeSinceStart() {
        return runtime.getTimeSinceStart();
    }

    public int getSampleRateForClock() {
        return runtime.getSampleRateForClock();
    }

    public void updateSourcesTick(World world) {
        runtime.updateSourcesTick(world);
    }

    public void updateAudioDeviceFallback() {
        audioDeviceFallback.tick();
    }

    public void pauseAll() {
        runtime.pauseAll();
    }

    synchronized void stopSessionContents(PlaybackSession session) {
        runtime.stopSessionContents(session);
    }

    public synchronized void stopSession(UUID sessionId) {
        runtime.stopSession(sessionId);
    }

    public synchronized void stopAll() {
        runtime.stopAll();
    }

    public void resumeAll() {
        runtime.resumeAll();
    }

    void startAudioThread() {
        runtime.startAudioThread();
    }

    public void cleanupEfx() {
        audioDeviceFallback.reset();
        reverbBusAllocator.reset();
        effects.cleanup();
    }

    boolean hasNativeAudioState() {
        return effects.isInitialized() || runtime.hasNativeAudioResources();
    }

    boolean nativeAudioResourcesValid() {
        boolean effectsValid = !effects.isInitialized() || effects.nativeResourcesValid();
        return effectsValid && runtime.nativeSourcesValid();
    }

    synchronized void abandonLostAudioBackend() {
        runtime.abandonAfterAudioDeviceLoss();
        effects.abandonNativeResources();
    }

    synchronized boolean restoreAudioBackend() {
        while (org.lwjgl.openal.AL10.alGetError() != org.lwjgl.openal.AL10.AL_NO_ERROR) {
            // Drain errors left behind while the old device was disappearing.
        }
        effects.initialize();
        return effects.isInitialized();
    }

    void loadPersistedEqIntoSession(PlaybackSession session, UUID sessionId) {
        mixer.loadPersistedEqIntoSession(session, sessionId);
    }

    public void prepareStreamBuffers(PlaybackSession session, String trackId) {
        playback.prepareStreamBuffers(session, trackId);
    }

    public void toggleManualPause(UUID sessionId) {
        PlaybackSession session = sessions.get(sessionId);
        if (session != null && session.isPlaying()) {
            session.setManuallyPaused(!session.isManuallyPaused());
        }
    }

    public void playTrack(UUID sessionId, String trackId, List<BlockPos> speakers, float power, float inputGain) {
        playback.playTrack(sessionId, trackId, speakers, power, inputGain);
    }

    public void playTrackWithSpeakerData(
            UUID sessionId, String trackId, List<SpeakerPlaybackData> speakers, float power, float inputGain) {
        playback.playTrackWithSpeakerData(sessionId, trackId, speakers, power, inputGain);
    }

    public void updateInputGain(float gain) {
        mixer.updateInputGain(gain);
    }

    public void updatePower(float power) {
        mixer.updatePower(power);
    }

    public void playFromUrl(UUID sessionId, String url, List<BlockPos> speakers, float power, float inputGain) {
        playback.playFromUrl(sessionId, url, speakers, power, inputGain);
    }

    public void playFromUrl(
            UUID sessionId,
            String url,
            List<SpeakerPlaybackData> speakers,
            float power,
            float inputGain,
            boolean startImmediately,
            java.util.function.Consumer<UUID> onReadyCallback) {
        playback.playFromUrlWithSpeakerData(
                sessionId, url, speakers, power, inputGain, startImmediately, onReadyCallback);
    }

    public void createSourcesFromClusters(
            PlaybackSession session,
            List<List<BlockPos>> clusters,
            int[] counts,
            World world,
            float power,
            float inputGain) {
        playback.createSourcesFromClusters(session, clusters, counts, world, power, inputGain);
    }

    public void startPlaybackWithVenueScan(
            PlaybackSession session, World world, List<BlockPos> speakers, boolean startAfterScan) {
        playback.startPlaybackWithVenueScan(session, world, speakers, startAfterScan);
    }

    public void playFromPcmData(
            UUID sessionId, short[] pcmData, int sampleRate, List<BlockPos> speakers, float power, float inputGain) {
        playback.playFromPcmData(sessionId, pcmData, sampleRate, speakers, power, inputGain);
    }

    public void updateSpeakerTilt(BlockPos speakerPosition, int tiltDegrees) {
        mixer.updateSpeakerTilt(speakerPosition, tiltDegrees);
    }

    public double getTotalPlaybackDuration() {
        return runtime.getTotalPlaybackDuration();
    }

    public double getTotalPlaybackDuration(PlaybackSession session) {
        return runtime.getTotalPlaybackDuration(session);
    }

    public double getCurrentPlaybackTime() {
        return runtime.getCurrentPlaybackTime();
    }

    public double getCurrentPlaybackTime(PlaybackSession session) {
        return runtime.getCurrentPlaybackTime(session);
    }

    public synchronized void seek(double timeSeconds) {
        runtime.seek(timeSeconds);
    }

    public synchronized void seek(PlaybackSession session, double timeSeconds) {
        runtime.seek(session, timeSeconds);
    }

    public void startSessionPlayback(UUID sessionId) {
        PlaybackSession session = sessions.get(sessionId);
        if (session != null) {
            playback.startPreparedSession(session);
        }
    }

    public void seekForSession(UUID sessionId, float targetTime) {
        PlaybackSession session = sessions.get(sessionId);
        if (session != null) {
            seek(session, targetTime);
        }
    }

    public void setEqDbForSession(UUID sessionId, String speakerType, int band, float db) {
        mixer.setEqDbForSession(sessionId, speakerType, band, db);
    }

    public void setEqQForSession(UUID sessionId, String speakerType, int band, float q) {
        mixer.setEqQForSession(sessionId, speakerType, band, q);
    }

    public void setMixerGainForSession(UUID sessionId, String speakerType, float gain) {
        mixer.setMixerGainForSession(sessionId, speakerType, gain);
    }

    public void updateInputGainForSession(UUID sessionId, float gain) {
        mixer.updateInputGainForSession(sessionId, gain);
    }

    public void updatePowerForSession(UUID sessionId, float power) {
        mixer.updatePowerForSession(sessionId, power);
    }

    public void applyChannelMaskToSpeaker(BlockPos speakerPosition, int mask) {
        mixer.applyChannelMaskToSpeaker(speakerPosition, mask);
    }
}
