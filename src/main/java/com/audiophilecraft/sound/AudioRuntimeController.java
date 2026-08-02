package com.audiophilecraft.sound;

import java.nio.IntBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import net.minecraft.client.MinecraftClient;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;
import org.lwjgl.BufferUtils;
import org.lwjgl.openal.AL;
import org.lwjgl.openal.AL10;
import org.lwjgl.openal.ALCapabilities;

/**
 * Runs active playback sessions across the client tick and dedicated audio
 * thread.
 *
 * <p>Pause accounting, source cleanup, seeking and thread shutdown share the
 * AudioEngine lifecycle lock so playback callbacks keep their original
 * synchronization contract.
 */
final class AudioRuntimeController {
    private static final int ECHO_GROUP_COUNT = 9;
    private static final float ECHO_NORMALIZATION_EPSILON = 0.000001f;
    private static final float ECHO_NORMALIZATION_RECOVERY = 0.25f;
    private static final float ECHO_NORMALIZATION_TARGET = 0.75f;

    private final Object lifecycleLock;
    private final Map<UUID, PlaybackSession> sessions;
    private final Supplier<PlaybackSession> activeSessionSupplier;
    private final AudioEffectsController effects;
    private final ReverbBusAllocator reverbBusAllocator;
    private final AudioPlaybackController playback;
    private final IntBuffer reusableRestartBuffer = BufferUtils.createIntBuffer(1024);
    private final Map<PlaybackSession, float[]> echoNormalizationFactors = new HashMap<>();

    private volatile Vec3d listenerPosition = Vec3d.ZERO;
    private volatile Vec3d smoothedListenerPosition = Vec3d.ZERO;
    private volatile boolean externalPlaybackPaused;
    private ScheduledExecutorService audioThread;
    private long lastTickTime = System.nanoTime();

    AudioRuntimeController(
            Object lifecycleLock,
            Map<UUID, PlaybackSession> sessions,
            Supplier<PlaybackSession> activeSessionSupplier,
            AudioEffectsController effects,
            ReverbBusAllocator reverbBusAllocator,
            AudioPlaybackController playback) {
        this.lifecycleLock = lifecycleLock;
        this.sessions = sessions;
        this.activeSessionSupplier = activeSessionSupplier;
        this.effects = effects;
        this.reverbBusAllocator = reverbBusAllocator;
        this.playback = playback;
    }

    Vec3d listenerPosition() {
        return listenerPosition;
    }

    void updateListenerPosition(Vec3d position) {
        listenerPosition = position;
    }

    void syncListenerPosition(Vec3d position) {
        listenerPosition = position;
        smoothedListenerPosition = position;
    }

    void refreshReverbBusAssignments() {
        synchronized (lifecycleLock) {
            boolean hasSources = false;
            for (PlaybackSession session : sessions.values()) {
                if (!session.getStreamSources().isEmpty()) {
                    hasSources = true;
                    break;
                }
            }

            if (hasSources) {
                reverbBusAllocator.allocate(sessions.values(), listenerPosition);
            } else {
                reverbBusAllocator.reset();
            }
        }
    }

    double getTimeSinceStart() {
        PlaybackSession session = activeSession();
        if (session == null || !session.isPlaying() || session.getStreamStartTime() == 0) {
            return 0.0;
        }
        return (System.nanoTime() - session.getStreamStartTime()) / 1_000_000_000.0;
    }

    int getSampleRateForClock() {
        PlaybackSession session = activeSession();
        if (session == null) return 48000;

        for (AudioStreamBuffer buffer : session.getStreamBuffers().values()) {
            if (buffer.sampleRate > 0) {
                return buffer.sampleRate;
            }
        }
        return 48000;
    }

    void updateSourcesTick(World world) {
        MinecraftClient client = MinecraftClient.getInstance();
        boolean gamePaused = client.isPaused() || externalPlaybackPaused;

        updatePauseStates(gamePaused);
        effects.setGamePaused(gamePaused);
        if (gamePaused) {
            lastTickTime = System.nanoTime();
            return;
        }

        playback.refreshNearbyVenueProfiles(sessions.values(), world, listenerPosition);
        playback.refreshAcousticZoneSelection(listenerPosition);
        reverbBusAllocator.update(sessions.values(), listenerPosition);

        boolean removedSession = updatePlayingSessions(world);
        if (removedSession) {
            refreshReverbBusAssignments();
            playback.refreshAcousticZones(listenerPosition);
            checkAndShutdownThread();
        }

        updateListenerAcoustics(world);
        clearFinishedVenueState();
        effects.updateRoomBusOcclusion(calculateRoomBusOcclusion());
        effects.ensureVenueReverb();
        lastTickTime = System.nanoTime();
    }

    void setExternalPlaybackPaused(boolean paused) {
        externalPlaybackPaused = paused;
    }

    private void updatePauseStates(boolean gamePaused) {
        synchronized (lifecycleLock) {
            for (PlaybackSession session : sessions.values()) {
                boolean shouldPause = gamePaused || session.isManuallyPaused();
                if (shouldPause || session.isPaused()) {
                    capturePausedPropagationTarget(session);
                }
                if (shouldPause == session.isPaused()) continue;

                if (shouldPause) {
                    session.setPaused(true);
                    session.setPauseStartTimestamp(System.nanoTime());
                    for (StreamSource source : session.getStreamSources()) {
                        source.pause();
                    }
                } else {
                    if (session.getPauseStartTimestamp() > 0 && session.getStreamStartTime() > 0) {
                        long pauseDuration = System.nanoTime() - session.getPauseStartTimestamp();
                        session.setStreamStartTime(session.getStreamStartTime() + pauseDuration);
                    }
                    session.setPaused(false);
                    for (StreamSource source : session.getStreamSources()) {
                        source.resume();
                    }
                }
            }
        }
    }

    private void capturePausedPropagationTarget(PlaybackSession session) {
        Vec3d currentPosition = smoothedListenerPosition;
        for (StreamSource source : session.getStreamSources()) {
            source.updatePausedDistanceSnapshot(currentPosition);
        }
        for (StreamSource source : session.getStreamSources()) {
            source.capturePausedPropagationTarget();
        }
    }

    private boolean updatePlayingSessions(World world) {
        boolean removedSession = false;
        Iterator<Map.Entry<UUID, PlaybackSession>> sessionIterator =
                sessions.entrySet().iterator();
        while (sessionIterator.hasNext()) {
            Map.Entry<UUID, PlaybackSession> entry = sessionIterator.next();
            PlaybackSession session = entry.getValue();
            if (!session.isPlaying() || session.getStreamStartTime() == 0) continue;

            double timeSinceStart = (System.nanoTime() - session.getStreamStartTime()) / 1_000_000_000.0;
            List<StreamSource> sourcesToRemove = new ArrayList<>();
            for (StreamSource source : session.getStreamSources()) {
                if (!source.update(world, listenerPosition, timeSinceStart)) {
                    sourcesToRemove.add(source);
                }
            }
            if (!sourcesToRemove.isEmpty()) {
                synchronized (lifecycleLock) {
                    for (StreamSource source : sourcesToRemove) {
                        source.cleanup();
                    }
                    session.getStreamSources().removeAll(sourcesToRemove);
                }
            }

            if (session.getStreamSources().isEmpty()) {
                echoNormalizationFactors.remove(session);
                playback.cancelUrlRequest(entry.getKey());
                synchronized (lifecycleLock) {
                    session.stopAll();
                    sessionIterator.remove();
                }
                removedSession = true;
            } else {
                normalizeEchoSends(session);
            }
        }
        return removedSession;
    }

    private void normalizeEchoSends(PlaybackSession session) {
        Map<StreamSource, float[]> clusterContributions = new IdentityHashMap<>();
        float[] sourceEnergySquared = new float[ECHO_GROUP_COUNT];
        float[] clusterEnergySquared = new float[ECHO_GROUP_COUNT];
        boolean[] activeGroups = new boolean[ECHO_GROUP_COUNT];

        for (StreamSource source : session.getStreamSources()) {
            if (!source.isValid) continue;

            int groupIndex = echoGroupIndex(source);
            float contribution = Math.max(0.0f, source.getPendingEchoContribution());
            float[] groupContributions = clusterContributions.computeIfAbsent(
                    source.getEchoNormalizationCluster(), ignored -> new float[ECHO_GROUP_COUNT]);
            groupContributions[groupIndex] += contribution;
            sourceEnergySquared[groupIndex] += contribution * contribution;
            activeGroups[groupIndex] = true;
        }

        // Sources in one cluster share propagation timing and add coherently. The target
        // remains equal-power, so larger systems gain energy without over-summing arrays.
        for (float[] groupContributions : clusterContributions.values()) {
            for (int groupIndex = 0; groupIndex < ECHO_GROUP_COUNT; groupIndex++) {
                float clusterContribution = groupContributions[groupIndex];
                clusterEnergySquared[groupIndex] += clusterContribution * clusterContribution;
            }
        }

        float[] normalizationFactors = echoNormalizationFactors.computeIfAbsent(session, ignored -> {
            float[] factors = new float[ECHO_GROUP_COUNT];
            Arrays.fill(factors, 1.0f);
            return factors;
        });
        for (int groupIndex = 0; groupIndex < ECHO_GROUP_COUNT; groupIndex++) {
            if (!activeGroups[groupIndex]) {
                normalizationFactors[groupIndex] = 1.0f;
                continue;
            }

            float sourceEnergy = (float) Math.sqrt(sourceEnergySquared[groupIndex]);
            float clusterEnergy = (float) Math.sqrt(clusterEnergySquared[groupIndex]);
            float targetFactor = ECHO_NORMALIZATION_TARGET;
            if (clusterEnergy > ECHO_NORMALIZATION_EPSILON) {
                targetFactor = ECHO_NORMALIZATION_TARGET * sourceEnergy / clusterEnergy;
            }
            targetFactor = Math.max(0.0f, Math.min(1.0f, targetFactor));

            float previousFactor = normalizationFactors[groupIndex];
            float normalizedFactor = targetFactor;
            if (targetFactor > previousFactor) {
                normalizedFactor = previousFactor + (targetFactor - previousFactor) * ECHO_NORMALIZATION_RECOVERY;
            }
            normalizationFactors[groupIndex] = normalizedFactor;
        }

        for (StreamSource source : session.getStreamSources()) {
            if (!source.isValid) continue;
            source.applyEchoNormalization(normalizationFactors[echoGroupIndex(source)]);
        }
    }

    private int echoGroupIndex(StreamSource source) {
        int speakerTypeIndex;
        if (AudioEngine.TYPE_SUB.equals(source.speakerType)) {
            speakerTypeIndex = 0;
        } else if (AudioEngine.TYPE_MID.equals(source.speakerType)) {
            speakerTypeIndex = 1;
        } else {
            speakerTypeIndex = 2;
        }
        int channelIndex = Math.max(0, Math.min(2, source.getChannelMask()));
        return speakerTypeIndex * 3 + channelIndex;
    }

    private void updateListenerAcoustics(World world) {
        for (PlaybackSession session : sessions.values()) {
            if (session.isPlaying()) {
                effects.updateListenerAcoustics(world, listenerPosition);
                return;
            }
        }
    }

    private void clearFinishedVenueState() {
        PlaybackSession activeSession = activeSession();
        if (activeSession != null
                && activeSession.isPlaying()
                && activeSession.getStreamSources().isEmpty()
                && effects.getVenuePreset() != null) {
            effects.clearVenuePreset();
        }
    }

    private float[] calculateRoomBusOcclusion() {
        float[] roomBusOcclusion = {0.0f, 0.0f};
        boolean[] roomBusHasSources = {false, false};
        for (PlaybackSession session : sessions.values()) {
            for (StreamSource source : session.getStreamSources()) {
                EmitterGroup emitterGroup = source.getEmitterGroup();
                if (!source.isValid || emitterGroup == null) continue;

                int busIndex = emitterGroup.roomBusIndex();
                if (busIndex < 0 || busIndex >= roomBusOcclusion.length) continue;
                roomBusHasSources[busIndex] = true;
                roomBusOcclusion[busIndex] = Math.max(roomBusOcclusion[busIndex], source.currentOcclusion);
            }
        }

        for (int busIndex = 0; busIndex < roomBusOcclusion.length; busIndex++) {
            if (!roomBusHasSources[busIndex]) {
                roomBusOcclusion[busIndex] = 1.0f;
            }
        }
        return roomBusOcclusion;
    }

    void pauseAll() {
        synchronized (lifecycleLock) {
            for (PlaybackSession session : sessions.values()) {
                for (StreamSource source : session.getStreamSources()) {
                    source.pause();
                }
            }
            effects.muteEffectSlots();
        }
    }

    void resumeAll() {
        synchronized (lifecycleLock) {
            for (PlaybackSession session : sessions.values()) {
                for (StreamSource source : session.getStreamSources()) {
                    source.resume();
                }
            }
            effects.resumeEffectSlots();
        }
    }

    void stopSessionContents(PlaybackSession session) {
        if (session == null) return;
        synchronized (lifecycleLock) {
            echoNormalizationFactors.remove(session);
            session.stopAll();
            refreshReverbBusAssignments();
            playback.refreshAcousticZones(listenerPosition);
        }
    }

    void stopSession(UUID sessionId) {
        synchronized (lifecycleLock) {
            playback.cancelUrlRequest(sessionId);
            PlaybackSession session = sessions.remove(sessionId);
            if (session != null) {
                echoNormalizationFactors.remove(session);
                session.stopAll();
            }
            refreshReverbBusAssignments();
            playback.refreshAcousticZones(listenerPosition);
        }
        checkAndShutdownThread();
    }

    void stopAll() {
        synchronized (lifecycleLock) {
            playback.cancelAllUrlRequests();
            for (PlaybackSession session : sessions.values()) {
                session.stopAll();
            }
            sessions.clear();
            echoNormalizationFactors.clear();
            reverbBusAllocator.reset();
            playback.refreshAcousticZones(listenerPosition);
        }
        checkAndShutdownThread();
    }

    boolean hasNativeAudioResources() {
        for (PlaybackSession session : sessions.values()) {
            if (!session.getStreamSources().isEmpty()) return true;
        }
        return false;
    }

    boolean nativeSourcesValid() {
        for (PlaybackSession session : sessions.values()) {
            for (StreamSource source : session.getStreamSources()) {
                if (source.isValid && !AL10.alIsSource(source.sourceId)) return false;
            }
        }
        return true;
    }

    void abandonAfterAudioDeviceLoss() {
        playback.abandonAfterAudioDeviceLoss();
        synchronized (lifecycleLock) {
            if (audioThread != null) {
                audioThread.shutdownNow();
                audioThread = null;
            }

            for (PlaybackSession session : sessions.values()) {
                session.abandonAfterAudioDeviceLoss();
            }
            sessions.clear();
            echoNormalizationFactors.clear();
            reusableRestartBuffer.clear();
            reverbBusAllocator.abandonAssignments();
            playback.refreshAcousticZones(listenerPosition);
        }
    }

    private void checkAndShutdownThread() {
        synchronized (lifecycleLock) {
            for (PlaybackSession session : sessions.values()) {
                if (session.isPlaying()) return;
            }

            effects.clearVenueState();
            if (audioThread == null) return;

            audioThread.shutdownNow();
            try {
                audioThread.awaitTermination(50, TimeUnit.MILLISECONDS);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            }
            audioThread = null;
        }
    }

    void startAudioThread() {
        if (audioThread != null && !audioThread.isShutdown()) return;

        ALCapabilities capabilities = AL.getCapabilities();
        audioThread = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "AudiophileCraft-Audio");
            thread.setDaemon(true);
            thread.setPriority(Thread.MAX_PRIORITY);
            return thread;
        });

        audioThread.execute(() -> {
            try {
                AL.setCurrentThread(capabilities);
            } catch (Exception exception) {
                System.err.println(
                        "AudioEngine: Failed to propagate AL caps to audio thread: " + exception.getMessage());
            }
        });
        audioThread.scheduleWithFixedDelay(this::processAudioBackground, 0, 5, TimeUnit.MILLISECONDS);
    }

    private void processAudioBackground() {
        synchronized (lifecycleLock) {
            if (Thread.interrupted()) {
                Thread.currentThread().interrupt();
                return;
            }

            try {
                List<PlaybackSession> sessionSnapshot = new ArrayList<>(sessions.values());
                decodeAhead(sessionSnapshot);
                Vec3d currentPosition = smoothListenerPosition();
                feedSources(sessionSnapshot, currentPosition);
                restartUnderrunSources();
            } catch (Exception exception) {
                System.err.println("[AudioEngine] processAudioBackground failed: " + exception.getMessage());
                exception.printStackTrace();
            }
        }
    }

    private void decodeAhead(List<PlaybackSession> sessionSnapshot) {
        for (PlaybackSession session : sessionSnapshot) {
            if (!canProcess(session)) continue;

            double wallTime = session.getStreamStartTime() > 0
                    ? (System.nanoTime() - session.getStreamStartTime()) / 1_000_000_000.0
                    : 0.0;
            List<AudioStreamBuffer> buffers =
                    new ArrayList<>(session.getStreamBuffers().values());
            for (AudioStreamBuffer buffer : buffers) {
                if (buffer.sampleRate > 0) {
                    buffer.syncToTime(wallTime + AudioEngine.BUFFER_LOOKAHEAD);
                }
            }
        }
    }

    private Vec3d smoothListenerPosition() {
        Vec3d rawPosition = listenerPosition;
        if (rawPosition != null) {
            Vec3d previousPosition = smoothedListenerPosition;
            if (previousPosition == null) previousPosition = rawPosition;
            double smoothing = 0.35;
            smoothedListenerPosition = new Vec3d(
                    previousPosition.x + (rawPosition.x - previousPosition.x) * smoothing,
                    previousPosition.y + (rawPosition.y - previousPosition.y) * smoothing,
                    previousPosition.z + (rawPosition.z - previousPosition.z) * smoothing);
        }
        return smoothedListenerPosition;
    }

    private void feedSources(List<PlaybackSession> sessionSnapshot, Vec3d currentPosition) {
        for (PlaybackSession session : sessionSnapshot) {
            if (!canProcess(session)) continue;

            double wallTime = session.getStreamStartTime() > 0
                    ? (System.nanoTime() - session.getStreamStartTime()) / 1_000_000_000.0
                    : 0.0;
            int sampleRate = sampleRate(session);
            double globalSampleTime = wallTime * sampleRate;

            for (StreamSource source : session.getStreamSources()) {
                if (source.feedOpenALFromAudioThread(globalSampleTime, currentPosition)
                        && reusableRestartBuffer.remaining() > 0
                        && source.isValid) {
                    reusableRestartBuffer.put(source.sourceId);
                }
            }
        }
    }

    private void restartUnderrunSources() {
        if (reusableRestartBuffer.position() == 0) return;

        reusableRestartBuffer.flip();
        AL10.alSourcePlayv(reusableRestartBuffer);
        reusableRestartBuffer.clear();
    }

    private static boolean canProcess(PlaybackSession session) {
        return session.isPlaying() && !session.isPaused() && !session.isSeeking();
    }

    private static int sampleRate(PlaybackSession session) {
        for (AudioStreamBuffer buffer : session.getStreamBuffers().values()) {
            if (buffer.sampleRate > 0) {
                return buffer.sampleRate;
            }
        }
        return 48000;
    }

    double getTotalPlaybackDuration() {
        return getTotalPlaybackDuration(activeSession());
    }

    double getTotalPlaybackDuration(PlaybackSession session) {
        if (session == null
                || !session.isPlaying()
                || session.getStreamBuffers().isEmpty()) {
            return 0.0;
        }
        AudioStreamBuffer buffer =
                session.getStreamBuffers().values().iterator().next();
        return buffer != null ? buffer.getTotalDurationSeconds() : 0.0;
    }

    double getCurrentPlaybackTime() {
        return getCurrentPlaybackTime(activeSession());
    }

    double getCurrentPlaybackTime(PlaybackSession session) {
        if (session == null || !session.isPlaying() || session.getStreamStartTime() == 0) {
            return 0.0;
        }

        long now = System.nanoTime();
        if (session.isPaused() && session.getPauseStartTimestamp() > 0) {
            now = session.getPauseStartTimestamp();
        }
        return (now - session.getStreamStartTime()) / 1_000_000_000.0;
    }

    void seek(double timeSeconds) {
        seek(activeSession(), timeSeconds);
    }

    void seek(PlaybackSession session, double timeSeconds) {
        synchronized (lifecycleLock) {
            seekLocked(session, timeSeconds);
        }
    }

    private void seekLocked(PlaybackSession session, double timeSeconds) {
        if (session == null || !session.isPlaying()) return;

        double totalDuration = getTotalPlaybackDuration(session);
        double targetTime = Math.max(0.0, timeSeconds);
        if (totalDuration > 0.0) {
            targetTime = Math.min(targetTime, totalDuration);
        }
        if (Math.abs(getCurrentPlaybackTime(session) - targetTime) < 0.5) return;

        session.setSeeking(true);
        try {
            if (session.getStreamSources().isEmpty()) return;

            long now = System.nanoTime();
            if (session.isPaused()) {
                session.setPauseStartTimestamp(now);
            }
            session.setStreamStartTime(now - (long) (targetTime * 1_000_000_000.0));

            for (AudioStreamBuffer buffer : session.getStreamBuffers().values()) {
                buffer.seekToTime(targetTime - 0.1);
                buffer.syncToTime(targetTime + AudioEngine.BUFFER_LOOKAHEAD);
            }

            IntBuffer sourceIds =
                    BufferUtils.createIntBuffer(session.getStreamSources().size());
            for (StreamSource source : session.getStreamSources()) {
                source.seekToTime(targetTime);
                sourceIds.put(source.sourceId);
            }
            sourceIds.flip();
            if (!session.isPaused()) {
                AL10.alSourcePlayv(sourceIds);
            }
        } finally {
            session.setSeeking(false);
        }
    }

    private PlaybackSession activeSession() {
        return activeSessionSupplier.get();
    }
}
