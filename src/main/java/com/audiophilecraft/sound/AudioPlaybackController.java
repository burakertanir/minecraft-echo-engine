package com.audiophilecraft.sound;

import static org.lwjgl.openal.AL10.*;
import static org.lwjgl.openal.AL11.*;
import static org.lwjgl.openal.EXTEfx.*;

import com.audiophilecraft.AudiophileCraft;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import net.minecraft.client.MinecraftClient;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.math.Vec3i;
import net.minecraft.world.World;
import org.lwjgl.BufferUtils;
import org.lwjgl.system.MemoryUtil;

/**
 * Builds playback sessions from local or internet PCM, creates OpenAL sources,
 * and coordinates venue scanning before playback starts.
 */
final class AudioPlaybackController {
    private final AudioEngine engine;
    private final Map<UUID, PlaybackSession> sessions;
    private final AudioEffectsController effects;
    private final AcousticZoneResolver acousticZoneResolver = new AcousticZoneResolver();
    private volatile List<AcousticZoneResolver.AcousticZone> acousticZones = List.of();
    private volatile Long requestedAcousticZoneId;
    private Long publishedAcousticZoneId;
    private final ConcurrentHashMap<UUID, Long> activeUrlRequestIds = new ConcurrentHashMap<>();

    private static final long DYNAMIC_VENUE_CHECK_INTERVAL_NANOS = 500_000_000L;
    private static final double DYNAMIC_VENUE_SCAN_RADIUS_SQ = 192.0 * 192.0;
    private static final int MAX_GROUPS_PER_VENUE_SCAN = 8;
    private static final int VENUE_SCAN_NEIGHBORHOOD_BLOCKS = 64;
    private static final int ACOUSTIC_DEBUG_ALL_INDEX = -2;
    private static final Long ACOUSTIC_DEBUG_ALL = Long.MIN_VALUE;
    private volatile boolean dynamicVenueScanInProgress;
    private long lastDynamicVenueCheckNanos;

    AudioPlaybackController(AudioEngine engine, Map<UUID, PlaybackSession> sessions, AudioEffectsController effects) {
        this.engine = engine;
        this.sessions = sessions;
        this.effects = effects;
    }

    void prepareStreamBuffers(PlaybackSession session, String trackId) {
        for (AudioStreamBuffer buffer : session.getStreamBuffers().values()) {
            buffer.cleanup();
        }
        session.getStreamBuffers().clear();

        OggDecoder.RawTrackData rawData = OggDecoder.loadOgg("sounds/" + trackId + ".ogg");
        if (rawData == null) return;

        try {
            rawData.pcmData.rewind();
            short[] pcmInterleaved = new short[rawData.pcmData.remaining()];
            rawData.pcmData.get(pcmInterleaved);
            int totalFrames = pcmInterleaved.length / 2;
            installSharedStereoBuffer(
                    session, trackId + "_stream", pcmInterleaved, totalFrames, totalFrames, rawData.sampleRate);
        } finally {
            if (rawData.pcmData != null) MemoryUtil.memFree(rawData.pcmData);
        }
    }

    private void installSharedStereoBuffer(
            PlaybackSession session,
            String trackId,
            short[] pcmInterleaved,
            int decodedFrames,
            int totalExpectedFrames,
            int sampleRate) {
        if (pcmInterleaved.length % 2 != 0) {
            throw new IllegalArgumentException("Stereo PCM must contain complete L/R frames");
        }

        AudioStreamBuffer sharedBuffer = new AudioStreamBuffer(trackId, sampleRate);
        sharedBuffer.initStreaming(pcmInterleaved, decodedFrames, totalExpectedFrames);
        session.getStreamBuffers().put(AudioEngine.TYPE_SUB, sharedBuffer);
        session.getStreamBuffers().put(AudioEngine.TYPE_MID, sharedBuffer);
        session.getStreamBuffers().put(AudioEngine.TYPE_LINE, sharedBuffer);
        session.getStreamBuffers().put(AudioEngine.TYPE_NORMAL, sharedBuffer);
    }

    void playTrack(UUID sessionUUID, String trackId, List<BlockPos> speakers, float power, float inputGain) {
        playTrackWithSpeakerData(sessionUUID, trackId, captureSpeakerData(speakers), power, inputGain);
    }

    void playTrackWithSpeakerData(
            UUID sessionUUID, String trackId, List<SpeakerPlaybackData> speakers, float power, float inputGain) {
        cancelUrlRequest(sessionUUID);
        PlaybackSession session = sessions.computeIfAbsent(sessionUUID, key -> new PlaybackSession(engine));
        engine.stopSessionContents(session);
        engine.loadPersistedEqIntoSession(session, sessionUUID);
        session.setPlayUrl("");
        resetGlobalVenueState(positionsOf(speakers));

        try {
            prepareStreamBuffers(session, trackId);
            finalizePlaybackPipeline(sessionUUID, speakers, power, inputGain, true);
        } catch (Exception e) {
            AudiophileCraft.LOGGER.error("Failed to start local track {} for session {}.", trackId, sessionUUID, e);
        }
    }

    void cancelUrlRequest(UUID sessionUUID) {
        Long requestId = activeUrlRequestIds.remove(sessionUUID);
        if (requestId != null) {
            InternetAudioLoader.getInstance().cancelStreamingRequest(requestId);
        }
    }

    void cancelAllUrlRequests() {
        if (activeUrlRequestIds.isEmpty()) return;
        InternetAudioLoader loader = InternetAudioLoader.getInstance();
        for (Map.Entry<UUID, Long> entry : activeUrlRequestIds.entrySet()) {
            UUID sessionUUID = entry.getKey();
            Long requestId = entry.getValue();
            if (activeUrlRequestIds.remove(sessionUUID, requestId)) {
                loader.cancelStreamingRequest(requestId);
            }
        }
    }

    void abandonAfterAudioDeviceLoss() {
        dynamicVenueScanInProgress = false;
        lastDynamicVenueCheckNanos = 0L;
        cancelAllUrlRequests();
    }

    private boolean isActiveUrlRequest(UUID sessionUUID, long requestId) {
        Long activeRequestId = activeUrlRequestIds.get(sessionUUID);
        return activeRequestId != null && activeRequestId.longValue() == requestId;
    }

    void playFromUrl(UUID sessionUUID, String url, List<BlockPos> speakers, float power, float inputGain) {
        playFromUrlWithSpeakerData(sessionUUID, url, captureSpeakerData(speakers), power, inputGain, true, null);
    }

    void playFromUrlWithSpeakerData(
            UUID sessionUUID,
            String url,
            List<SpeakerPlaybackData> speakers,
            float power,
            float inputGain,
            boolean startImmediately,
            Consumer<UUID> onReadyCallback) {
        cancelUrlRequest(sessionUUID);
        InternetAudioLoader loader = InternetAudioLoader.getInstance();
        PlaybackSession existingSession = sessions.get(sessionUUID);
        if (existingSession != null) {
            engine.stopSessionContents(existingSession);
        }

        long startedRequestId = loader.loadTrackStreaming(url, new InternetAudioLoader.StreamingCallback() {
            @Override
            public void onReady(
                    long requestId,
                    short[] pcmInterleaved,
                    int decodedFrames,
                    int totalExpected,
                    int sampleRate,
                    String title) {
                if (!isActiveUrlRequest(sessionUUID, requestId)) {
                    AudiophileCraft.LOGGER.debug(
                            "Ignoring stale URL request {} for session {}.", requestId, sessionUUID);
                    return;
                }

                AudiophileCraft.LOGGER.debug("URL request {} is ready for session {}.", requestId, sessionUUID);

                PlaybackSession session = sessions.computeIfAbsent(sessionUUID, key -> new PlaybackSession(engine));
                engine.stopSessionContents(session);
                engine.loadPersistedEqIntoSession(session, sessionUUID);
                session.setPlayUrl(url);
                session.getStreamBuffers().clear();
                installSharedStereoBuffer(
                        session, "url_stream", pcmInterleaved, decodedFrames, totalExpected, sampleRate);

                resetGlobalVenueState(positionsOf(speakers));
                finalizePlaybackPipeline(sessionUUID, speakers, power, inputGain, startImmediately);
                if (!startImmediately && onReadyCallback != null) {
                    onReadyCallback.accept(sessionUUID);
                }
            }

            @Override
            public void onMoreData(long requestId, int totalDecoded) {
                if (!isActiveUrlRequest(sessionUUID, requestId)) return;
                PlaybackSession session = sessions.get(sessionUUID);
                if (session != null) {
                    AudioStreamBuffer buffer = session.getStreamBuffers().get(AudioEngine.TYPE_NORMAL);
                    if (buffer != null) buffer.updateDecodedLength(totalDecoded);
                }
            }

            @Override
            public void onComplete(long requestId, int totalDecodedFrames) {
                if (!activeUrlRequestIds.remove(sessionUUID, requestId)) return;
                PlaybackSession session = sessions.get(sessionUUID);
                if (session != null) {
                    AudioStreamBuffer buffer = session.getStreamBuffers().get(AudioEngine.TYPE_NORMAL);
                    if (buffer != null) buffer.completeStreaming(totalDecodedFrames);
                }
                AudiophileCraft.LOGGER.debug("URL request {} completed for session {}.", requestId, sessionUUID);
            }

            @Override
            public void onFailed(long requestId, String reason) {
                if (!activeUrlRequestIds.remove(sessionUUID, requestId)) return;
                AudiophileCraft.LOGGER.warn("URL request {} failed for session {}: {}", requestId, sessionUUID, reason);
                MinecraftClient.getInstance().execute(() -> {
                    if (MinecraftClient.getInstance().player != null) {
                        MinecraftClient.getInstance()
                                .player
                                .sendMessage(
                                        Text.literal("HATA (CRITICAL DECODE ERROR): " + reason)
                                                .formatted(Formatting.RED),
                                        false);
                    }
                });
            }
        });
        activeUrlRequestIds.put(sessionUUID, startedRequestId);
        AudiophileCraft.LOGGER.debug("URL request {} started for session {}.", startedRequestId, sessionUUID);
    }

    void createSourcesFromClusters(
            PlaybackSession session,
            List<List<BlockPos>> clusters,
            int[] counts,
            World world,
            float power,
            float inputGain) {
        Map<BlockPos, SpeakerPlaybackData> speakerData = new java.util.HashMap<>();
        for (List<BlockPos> cluster : clusters) {
            for (BlockPos position : cluster) {
                SpeakerPlaybackData data = SpeakerPlaybackData.isChunkLoaded(world, position)
                        ? SpeakerPlaybackData.capture(world, position)
                        : SpeakerPlaybackData.unknown(position);
                speakerData.put(position, data);
            }
        }
        createSourcesFromClusters(session, clusters, speakerData, power, inputGain);
    }

    private void createSourcesFromClusters(
            PlaybackSession session,
            List<List<BlockPos>> clusters,
            Map<BlockPos, SpeakerPlaybackData> speakerData,
            float power,
            float inputGain) {
        session.getEmitterGroups().clear();
        for (List<BlockPos> cluster : clusters) {
            EmitterGroup emitterGroup = new EmitterGroup(cluster);
            session.getEmitterGroups().add(emitterGroup);
            int[] clusterCounts = countSpeakerTypes(cluster, speakerData);
            StreamSource leaderSource = null;
            for (BlockPos position : cluster) {
                SpeakerPlaybackData metadata =
                        speakerData.getOrDefault(position, SpeakerPlaybackData.unknown(position));
                String speakerType = metadata.speakerType();
                float baseReferenceDistance = 3.0f;
                float baseMaxDistance = 64.0f;
                int sampleShiftMs = metadata.sampleShiftMs();
                int channelMask = metadata.channelMask();
                int speakerCount = 1;

                if (AudioEngine.TYPE_SUB.equals(speakerType)) {
                    baseReferenceDistance = 10.0f;
                    baseMaxDistance = 85.0f;
                    speakerCount = clusterCounts[0];
                } else if (AudioEngine.TYPE_MID.equals(speakerType)) {
                    baseReferenceDistance = 5.0f;
                    baseMaxDistance = 60.0f;
                    speakerCount = clusterCounts[1];
                } else if (AudioEngine.TYPE_LINE.equals(speakerType)) {
                    baseReferenceDistance = 3.0f;
                    baseMaxDistance = 50.0f;
                    speakerCount = clusterCounts[2];
                } else {
                    speakerCount = clusterCounts[3];
                }

                AudioStreamBuffer buffer = session.getStreamBuffers().get(speakerType);
                if (buffer == null) buffer = session.getStreamBuffers().get(AudioEngine.TYPE_NORMAL);
                if (buffer == null) continue;

                int sourceId = alGenSources();
                int error = alGetError();
                if (error != AL_NO_ERROR) {
                    AudiophileCraft.LOGGER.error(
                            "OpenAL source allocation failed at speaker {} of {} (error=0x{}).",
                            session.getStreamSources().size() + 1,
                            clusters.stream().mapToInt(List::size).sum(),
                            Integer.toHexString(error));
                    for (StreamSource source : session.getStreamSources()) {
                        source.cleanup();
                    }
                    session.getStreamSources().clear();
                    session.setPlaying(false);
                    break;
                }

                alSource3f(
                        sourceId, AL_POSITION, position.getX() + 0.5f, position.getY() + 0.5f, position.getZ() + 0.5f);
                alSourcef(sourceId, AL_ROLLOFF_FACTOR, 1.0f);
                alSourcef(sourceId, AL_MAX_DISTANCE, Float.MAX_VALUE);
                alSourcef(sourceId, AL_REFERENCE_DISTANCE, baseReferenceDistance);
                alSourcef(sourceId, AL_GAIN, 1.0f);
                alSourcef(sourceId, AL_PITCH, 1.0f);

                Direction facing = metadata.facing();
                int tiltDegrees = metadata.verticalTiltDeg();

                Vec3i vector = facing.getVector();
                float tiltRadians = (float) Math.toRadians(tiltDegrees);
                float cosine = (float) Math.cos(tiltRadians);
                float sine = (float) Math.sin(tiltRadians);
                float directionX = vector.getX() * cosine;
                float directionY = sine;
                float directionZ = vector.getZ() * cosine;
                alSource3f(sourceId, AL_DIRECTION, directionX, directionY, directionZ);

                int filterId = 0;
                int sendFilterId = 0;
                int echoSendFilterId = 0;
                try {
                    filterId = alGenFilters();
                    alFilteri(filterId, AL_FILTER_TYPE, AL_FILTER_LOWPASS);
                    alFilterf(filterId, AL_LOWPASS_GAIN, 1.0f);
                    alFilterf(filterId, AL_LOWPASS_GAINHF, 1.0f);
                    alSourcei(sourceId, AL_DIRECT_FILTER, filterId);

                    sendFilterId = alGenFilters();
                    alFilteri(sendFilterId, AL_FILTER_TYPE, AL_FILTER_LOWPASS);
                    alFilterf(sendFilterId, AL_LOWPASS_GAIN, 1.0f);
                    alFilterf(sendFilterId, AL_LOWPASS_GAINHF, 1.0f);
                    if (effects.getAuxSlotId() != 0) {
                        alSource3i(sourceId, AL_AUXILIARY_SEND_FILTER, effects.getAuxSlotId(), 0, sendFilterId);
                    }

                    echoSendFilterId = alGenFilters();
                    alFilteri(echoSendFilterId, AL_FILTER_TYPE, AL_FILTER_LOWPASS);
                    alFilterf(echoSendFilterId, AL_LOWPASS_GAIN, 1.0f);
                    alFilterf(echoSendFilterId, AL_LOWPASS_GAINHF, 1.0f);
                    if (effects.getSlapbackAuxSlotId() != 0) {
                        alSource3i(
                                sourceId,
                                AL_AUXILIARY_SEND_FILTER,
                                effects.getSlapbackAuxSlotId(),
                                1,
                                echoSendFilterId);
                    }
                } catch (Exception e) {
                    AudiophileCraft.LOGGER.warn("Failed to configure EFX filters/sends for speaker {}.", position, e);
                }

                StreamSource source = new StreamSource(
                        session,
                        sourceId,
                        buffer,
                        position,
                        power,
                        baseMaxDistance * power,
                        baseReferenceDistance * power,
                        directionX,
                        directionY,
                        directionZ,
                        speakerType,
                        filterId,
                        sendFilterId,
                        echoSendFilterId,
                        inputGain,
                        sampleShiftMs,
                        speakerCount,
                        emitterGroup,
                        leaderSource,
                        cluster.size(),
                        channelMask);
                session.getStreamSources().add(source);
                if (leaderSource == null) leaderSource = source;
            }
        }
    }

    private void createSourcesFromSpeakerData(
            PlaybackSession session, List<SpeakerPlaybackData> speakers, float power, float inputGain) {
        List<BlockPos> positions = positionsOf(speakers);
        List<List<BlockPos>> clusters = SpeakerClusterer.clusterSpeakers(positions);
        Map<BlockPos, SpeakerPlaybackData> metadataByPosition = new java.util.HashMap<>();
        for (SpeakerPlaybackData speaker : speakers) {
            metadataByPosition.put(speaker.position(), speaker);
        }
        createSourcesFromClusters(session, clusters, metadataByPosition, power, inputGain);
    }

    private static int[] countSpeakerTypes(
            List<BlockPos> cluster, Map<BlockPos, SpeakerPlaybackData> metadataByPosition) {
        int sub = 0;
        int mid = 0;
        int line = 0;
        int normal = 0;
        for (BlockPos position : cluster) {
            String type = metadataByPosition
                    .getOrDefault(position, SpeakerPlaybackData.unknown(position))
                    .speakerType();
            if (AudioEngine.TYPE_SUB.equals(type)) {
                sub++;
            } else if (AudioEngine.TYPE_MID.equals(type)) {
                mid++;
            } else if (AudioEngine.TYPE_LINE.equals(type)) {
                line++;
            } else {
                normal++;
            }
        }
        return new int[] {sub, mid, line, normal};
    }

    private static List<SpeakerPlaybackData> captureSpeakerData(List<BlockPos> positions) {
        if (positions == null || positions.isEmpty()) return List.of();
        World world = MinecraftClient.getInstance().world;
        List<SpeakerPlaybackData> result = new ArrayList<>(positions.size());
        for (BlockPos position : positions) {
            SpeakerPlaybackData data = SpeakerPlaybackData.isChunkLoaded(world, position)
                    ? SpeakerPlaybackData.capture(world, position)
                    : SpeakerPlaybackData.unknown(position);
            result.add(data);
        }
        return result;
    }

    private static List<BlockPos> positionsOf(List<SpeakerPlaybackData> speakers) {
        if (speakers == null || speakers.isEmpty()) return List.of();
        List<BlockPos> positions = new ArrayList<>(speakers.size());
        for (SpeakerPlaybackData speaker : speakers) {
            positions.add(speaker.position());
        }
        return positions;
    }

    void startPlaybackWithVenueScan(
            PlaybackSession session, World world, List<BlockPos> speakers, boolean startAfterScan) {
        Runnable startPlayback = () -> {
            if (startAfterScan) startPreparedSession(session);
        };

        if (!session.getStreamSources().isEmpty() && world != null) {
            Vec3d listenerPosition = MinecraftClient.getInstance().player != null
                    ? MinecraftClient.getInstance().player.getEyePos()
                    : Vec3d.ZERO;
            List<EmitterGroup> emitterGroups = new ArrayList<>(session.getEmitterGroups());
            emitterGroups.removeIf(group -> !isEmitterGroupScanReady(world, group, listenerPosition));
            emitterGroups.sort(
                    Comparator.comparingDouble(group -> group.center().squaredDistanceTo(listenerPosition)));
            if (emitterGroups.size() > MAX_GROUPS_PER_VENUE_SCAN) {
                emitterGroups = new ArrayList<>(emitterGroups.subList(0, MAX_GROUPS_PER_VENUE_SCAN));
            }
            if (emitterGroups.isEmpty()) {
                startPlayback.run();
                return;
            }

            List<Vec3d> clusterCenters = new ArrayList<>();
            for (EmitterGroup emitterGroup : emitterGroups) {
                clusterCenters.add(emitterGroup.center());
            }

            List<EmitterGroup> scannedGroups = List.copyOf(emitterGroups);
            int generation = session.getTrackGeneration();
            CompletableFuture.supplyAsync(
                            () -> {
                                try {
                                    return effects.scanVenue(world, clusterCenters);
                                } catch (Exception e) {
                                    AudiophileCraft.LOGGER.error("Initial venue scan failed.", e);
                                    return null;
                                }
                            },
                            MinecraftClient.getInstance()::execute)
                    .exceptionally(exception -> {
                        AudiophileCraft.LOGGER.error("Initial venue scan task failed.", exception);
                        return null;
                    })
                    .thenAcceptAsync(
                            sceneResult -> {
                                if (generation != session.getTrackGeneration()) return;
                                if (sceneResult != null) {
                                    applyGroupScans(scannedGroups, sceneResult);
                                    effects.applyScannedVenuePreset(sceneResult.combinedResult());
                                    refreshAcousticZones(listenerPosition);
                                    engine.refreshReverbBusAssignments();
                                }
                                startPlayback.run();
                            },
                            MinecraftClient.getInstance()::execute);
        } else {
            startPlayback.run();
        }
    }

    private void applyGroupScans(List<EmitterGroup> groups, AcousticSceneScanResult sceneResult) {
        List<AcousticScanResult> groupResults = sceneResult.groupResults();
        int count = Math.min(groups.size(), groupResults.size());
        for (int i = 0; i < count; i++) {
            EmitterGroup group = groups.get(i);
            group.applyAcousticScan(groupResults.get(i));
            group.activateRoomSendImmediately();
        }
    }

    void refreshNearbyVenueProfiles(Collection<PlaybackSession> activeSessions, World world, Vec3d listenerPosition) {
        if (world == null || listenerPosition == null || dynamicVenueScanInProgress) return;

        long nowNanos = System.nanoTime();
        if (nowNanos - lastDynamicVenueCheckNanos < DYNAMIC_VENUE_CHECK_INTERVAL_NANOS) return;
        lastDynamicVenueCheckNanos = nowNanos;

        List<DynamicVenueCandidate> candidates = new ArrayList<>();
        for (PlaybackSession session : activeSessions) {
            if (!session.isPlaying()) continue;
            for (EmitterGroup group : session.getEmitterGroups()) {
                if (group.acousticProfile() != null || !isEmitterGroupScanReady(world, group, listenerPosition))
                    continue;
                double distanceSq = group.center().squaredDistanceTo(listenerPosition);
                if (distanceSq <= DYNAMIC_VENUE_SCAN_RADIUS_SQ) {
                    candidates.add(new DynamicVenueCandidate(session, group, distanceSq, session.getTrackGeneration()));
                }
            }
        }
        if (candidates.isEmpty()) return;

        candidates.sort(Comparator.comparingDouble(DynamicVenueCandidate::distanceSq));
        if (candidates.size() > MAX_GROUPS_PER_VENUE_SCAN) {
            candidates = new ArrayList<>(candidates.subList(0, MAX_GROUPS_PER_VENUE_SCAN));
        }
        List<DynamicVenueCandidate> scannedCandidates = List.copyOf(candidates);
        List<Vec3d> clusterCenters = new ArrayList<>(scannedCandidates.size());
        for (DynamicVenueCandidate candidate : scannedCandidates) {
            clusterCenters.add(candidate.group().center());
        }

        dynamicVenueScanInProgress = true;
        CompletableFuture.supplyAsync(
                        () -> {
                            try {
                                return effects.scanVenue(world, clusterCenters);
                            } catch (Exception e) {
                                AudiophileCraft.LOGGER.error("Dynamic venue scan failed.", e);
                                return null;
                            }
                        },
                        MinecraftClient.getInstance()::execute)
                .exceptionally(exception -> {
                    AudiophileCraft.LOGGER.error("Dynamic venue scan task failed.", exception);
                    return null;
                })
                .thenAcceptAsync(
                        sceneResult -> {
                            try {
                                if (sceneResult == null) return;
                                List<AcousticScanResult> groupResults = sceneResult.groupResults();
                                int count = Math.min(scannedCandidates.size(), groupResults.size());
                                boolean appliedCurrentProfile = false;
                                boolean allCandidatesCurrent = true;
                                for (int i = 0; i < count; i++) {
                                    DynamicVenueCandidate candidate = scannedCandidates.get(i);
                                    boolean candidateCurrent = candidate.generation()
                                            == candidate.session().getTrackGeneration();
                                    allCandidatesCurrent &= candidateCurrent;
                                    if (candidateCurrent
                                            && candidate.session().isPlaying()
                                            && candidate
                                                    .session()
                                                    .getEmitterGroups()
                                                    .contains(candidate.group())) {
                                        candidate.group().applyAcousticScan(groupResults.get(i));
                                        appliedCurrentProfile = true;
                                    }
                                }
                                if (appliedCurrentProfile && allCandidatesCurrent && effects.getVenuePreset() == null) {
                                    effects.applyScannedVenuePreset(sceneResult.combinedResult());
                                }
                                if (appliedCurrentProfile) {
                                    refreshAcousticZones(listenerPosition);
                                    engine.refreshReverbBusAssignments();
                                }
                            } finally {
                                dynamicVenueScanInProgress = false;
                            }
                        },
                        MinecraftClient.getInstance()::execute);
    }

    void refreshAcousticZones(Vec3d listenerPosition) {
        reconcileAcousticZones(acousticZoneResolver.resolve(sessions.values()));
        publishAcousticZoneSelection(listenerPosition, true);
    }

    void refreshAcousticZoneSelection(Vec3d listenerPosition) {
        publishAcousticZoneSelection(listenerPosition, false);
    }

    int getAcousticDebugZoneCount() {
        return acousticZones.size();
    }

    int getSelectedAcousticDebugZoneIndex() {
        Long requestedZoneId = requestedAcousticZoneId;
        if (requestedZoneId == null) return -1;
        if (requestedZoneId.equals(ACOUSTIC_DEBUG_ALL)) return ACOUSTIC_DEBUG_ALL_INDEX;
        List<AcousticZoneResolver.AcousticZone> zones = acousticZones;
        for (int index = 0; index < zones.size(); index++) {
            if (zones.get(index).id() == requestedZoneId) return index;
        }
        return -1;
    }

    void selectAcousticDebugZone(int zoneIndex, Vec3d listenerPosition) {
        List<AcousticZoneResolver.AcousticZone> zones = acousticZones;
        if (zoneIndex == ACOUSTIC_DEBUG_ALL_INDEX) {
            requestedAcousticZoneId = ACOUSTIC_DEBUG_ALL;
        } else if (zoneIndex < 0) {
            requestedAcousticZoneId = null;
        } else {
            if (zoneIndex >= zones.size()) return;
            requestedAcousticZoneId = zones.get(zoneIndex).id();
        }
        publishAcousticZoneSelection(listenerPosition, true);
    }

    private void reconcileAcousticZones(List<AcousticZoneResolver.AcousticZone> resolvedZones) {
        Map<Long, AcousticZoneResolver.AcousticZone> remainingZones = new HashMap<>();
        for (AcousticZoneResolver.AcousticZone zone : resolvedZones) {
            remainingZones.put(zone.id(), zone);
        }

        List<AcousticZoneResolver.AcousticZone> orderedZones = new ArrayList<>(resolvedZones.size());
        for (AcousticZoneResolver.AcousticZone previousZone : acousticZones) {
            AcousticZoneResolver.AcousticZone currentZone = remainingZones.remove(previousZone.id());
            if (currentZone != null) orderedZones.add(currentZone);
        }
        for (AcousticZoneResolver.AcousticZone zone : resolvedZones) {
            if (remainingZones.remove(zone.id()) != null) orderedZones.add(zone);
        }
        acousticZones = List.copyOf(orderedZones);

        Long requestedZoneId = requestedAcousticZoneId;
        if (requestedZoneId != null && !requestedZoneId.equals(ACOUSTIC_DEBUG_ALL) && !containsZone(requestedZoneId)) {
            requestedAcousticZoneId = null;
        }
    }

    private boolean containsZone(long zoneId) {
        for (AcousticZoneResolver.AcousticZone zone : acousticZones) {
            if (zone.id() == zoneId) return true;
        }
        return false;
    }

    private void publishAcousticZoneSelection(Vec3d listenerPosition, boolean forcePublish) {
        MinecraftClient client = MinecraftClient.getInstance();
        PlaybackSession activeSession = client.player != null ? sessions.get(client.player.getUuid()) : null;
        if (requestedAcousticZoneId != null
                && requestedAcousticZoneId.equals(ACOUSTIC_DEBUG_ALL)
                && publishAllAcousticZones(activeSession, forcePublish)) {
            return;
        }
        AcousticZoneResolver.AcousticZone selectedZone = findRequestedAcousticZone();
        if (selectedZone == null) {
            selectedZone = acousticZoneResolver.selectDebugZone(acousticZones, activeSession, listenerPosition);
        }
        Long selectedZoneId = selectedZone != null ? selectedZone.id() : null;
        AdvancedAcousticScanner.VenuePreset expectedPreset =
                selectedZone != null ? selectedZone.debugResult().profile().preset() : null;
        boolean selectionAlreadyPublished = Objects.equals(publishedAcousticZoneId, selectedZoneId)
                && AdvancedAcousticScanner.getLastDebugPreset() == expectedPreset;
        if (!forcePublish && selectionAlreadyPublished) return;

        publishedAcousticZoneId = selectedZoneId;
        if (selectedZone == null) {
            AdvancedAcousticScanner.resetDebugState(List.of());
        } else {
            AdvancedAcousticScanner.publishDebugResult(selectedZone.debugResult(), selectedZone.speakerPositions());
        }
        com.audiophilecraft.client.screen.PointCloudRenderer.invalidateCache();
    }

    private boolean publishAllAcousticZones(PlaybackSession activeSession, boolean forcePublish) {
        if (activeSession == null || activeSession.getEmitterGroups().isEmpty()) {
            requestedAcousticZoneId = null;
            return false;
        }
        Set<EmitterGroup> activeGroups = Collections.newSetFromMap(new IdentityHashMap<>());
        activeGroups.addAll(activeSession.getEmitterGroups());
        List<AcousticZoneResolver.AcousticZone> ourZones = new ArrayList<>();
        for (AcousticZoneResolver.AcousticZone zone : acousticZones) {
            if (zone.containsAny(activeGroups)) ourZones.add(zone);
        }
        if (ourZones.isEmpty()) {
            requestedAcousticZoneId = null;
            return false;
        }
        AcousticZoneResolver.DebugMerge merged = AcousticZoneResolver.mergeZonesForDebug(ourZones);
        if (merged == null) {
            requestedAcousticZoneId = null;
            return false;
        }
        AdvancedAcousticScanner.VenuePreset expectedPreset =
                merged.scanResult().profile().preset();
        boolean selectionAlreadyPublished = Objects.equals(publishedAcousticZoneId, ACOUSTIC_DEBUG_ALL)
                && AdvancedAcousticScanner.getLastDebugPreset() == expectedPreset;
        if (!forcePublish && selectionAlreadyPublished) return true;

        publishedAcousticZoneId = ACOUSTIC_DEBUG_ALL;
        AdvancedAcousticScanner.publishDebugResult(merged.scanResult(), merged.speakers());
        com.audiophilecraft.client.screen.PointCloudRenderer.invalidateCache();
        return true;
    }

    private AcousticZoneResolver.AcousticZone findRequestedAcousticZone() {
        Long requestedZoneId = requestedAcousticZoneId;
        if (requestedZoneId == null) return null;
        for (AcousticZoneResolver.AcousticZone zone : acousticZones) {
            if (zone.id() == requestedZoneId) return zone;
        }
        requestedAcousticZoneId = null;
        return null;
    }

    private static boolean isEmitterGroupScanReady(World world, EmitterGroup group, Vec3d listenerPosition) {
        Vec3d center = group.center();
        double listenerDistanceSq = center.squaredDistanceTo(listenerPosition);
        if (listenerDistanceSq > DYNAMIC_VENUE_SCAN_RADIUS_SQ) return false;

        BlockPos centerPosition =
                new BlockPos((int) Math.floor(center.x), (int) Math.floor(center.y), (int) Math.floor(center.z));
        if (!SpeakerPlaybackData.isChunkLoaded(world, centerPosition)) return false;
        if (listenerDistanceSq <= 32.0 * 32.0) return true;
        int[][] offsets = {
            {VENUE_SCAN_NEIGHBORHOOD_BLOCKS, 0},
            {-VENUE_SCAN_NEIGHBORHOOD_BLOCKS, 0},
            {0, VENUE_SCAN_NEIGHBORHOOD_BLOCKS},
            {0, -VENUE_SCAN_NEIGHBORHOOD_BLOCKS}
        };
        for (int[] offset : offsets) {
            if (!SpeakerPlaybackData.isChunkLoaded(world, centerPosition.add(offset[0], 0, offset[1]))) {
                return false;
            }
        }
        return true;
    }

    private record DynamicVenueCandidate(
            PlaybackSession session, EmitterGroup group, double distanceSq, int generation) {}

    void startPreparedSession(PlaybackSession session) {
        if (session == null) return;

        synchronized (engine) {
            if (session.isPlaying() || session.getStreamSources().isEmpty()) return;

            for (AudioStreamBuffer buffer : session.getStreamBuffers().values()) {
                if (buffer.sampleRate > 0) buffer.syncToTime(AudioEngine.BUFFER_LOOKAHEAD);
            }

            session.setStreamStartTime(System.nanoTime());
            session.beginEchoFadeIn();
            engine.syncListenerToCamera();

            java.nio.IntBuffer sourceIds =
                    BufferUtils.createIntBuffer(session.getStreamSources().size());
            for (StreamSource source : session.getStreamSources()) {
                alSourcei(source.sourceId, AL_LOOPING, AL_FALSE);
                sourceIds.put(source.sourceId);
            }
            sourceIds.flip();
            alSourcePlayv(sourceIds);

            session.setPlaying(true);
            session.setPaused(false);
            engine.startAudioThread();
        }
    }

    void playFromPcmData(
            UUID sessionUUID, short[] pcmData, int sampleRate, List<BlockPos> speakers, float power, float inputGain) {
        cancelUrlRequest(sessionUUID);
        PlaybackSession session = sessions.computeIfAbsent(sessionUUID, key -> new PlaybackSession(engine));
        engine.stopSessionContents(session);
        engine.loadPersistedEqIntoSession(session, sessionUUID);
        resetGlobalVenueState(speakers);

        try {
            short[] pcmInterleaved = new short[Math.multiplyExact(pcmData.length, 2)];
            for (int i = 0; i < pcmData.length; i++) {
                pcmInterleaved[i * 2] = pcmData[i];
                pcmInterleaved[i * 2 + 1] = pcmData[i];
            }

            for (AudioStreamBuffer buffer : session.getStreamBuffers().values()) {
                buffer.cleanup();
            }
            session.getStreamBuffers().clear();
            installSharedStereoBuffer(
                    session, "pcm_stream", pcmInterleaved, pcmData.length, pcmData.length, sampleRate);

            finalizePlaybackPipeline(sessionUUID, captureSpeakerData(speakers), power, inputGain, true);
        } catch (Exception e) {
            AudiophileCraft.LOGGER.error("Failed to start PCM playback for session {}.", sessionUUID, e);
        }
    }

    private void resetGlobalVenueState(List<BlockPos> speakers) {
        dynamicVenueScanInProgress = false;
        lastDynamicVenueCheckNanos = 0L;
        effects.resetVenueState(speakers);
    }

    private void finalizePlaybackPipeline(
            UUID sessionUUID,
            List<SpeakerPlaybackData> speakers,
            float power,
            float inputGain,
            boolean startImmediately) {
        if (speakers == null || speakers.isEmpty()) return;

        PlaybackSession session = sessions.get(sessionUUID);
        if (session == null) return;
        for (AudioStreamBuffer buffer : session.getStreamBuffers().values()) {
            if (buffer.sampleRate > 0) buffer.syncToTime(AudioEngine.BUFFER_LOOKAHEAD);
        }

        World world = MinecraftClient.getInstance().world;
        createSourcesFromSpeakerData(session, speakers, power, inputGain);

        startPlaybackWithVenueScan(session, world, positionsOf(speakers), startImmediately);
    }
}
