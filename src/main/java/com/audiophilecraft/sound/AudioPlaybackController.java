package com.audiophilecraft.sound;

import static org.lwjgl.openal.AL10.*;
import static org.lwjgl.openal.AL11.*;
import static org.lwjgl.openal.EXTEfx.*;

import java.nio.ShortBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import net.minecraft.block.BlockState;
import net.minecraft.client.MinecraftClient;
import net.minecraft.state.property.Properties;
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
    private final ConcurrentHashMap<UUID, Long> activeUrlRequestIds = new ConcurrentHashMap<>();

    // Incremented whenever a new playback pipeline is prepared. Async venue callbacks
    // must match this generation before applying global effects or starting sources.
    private volatile int trackGeneration;

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
            createStreamBufferForType(session, trackId, rawData, AudioEngine.TYPE_SUB);
            createStreamBufferForType(session, trackId, rawData, AudioEngine.TYPE_MID);
            createStreamBufferForType(session, trackId, rawData, AudioEngine.TYPE_LINE);
            createStreamBufferForType(session, trackId, rawData, AudioEngine.TYPE_NORMAL);
        } finally {
            if (rawData.pcmData != null) MemoryUtil.memFree(rawData.pcmData);
        }
    }

    private void createStreamBufferForType(
            PlaybackSession session, String trackId, OggDecoder.RawTrackData rawData, String type) {
        rawData.pcmData.rewind();
        short[] audioData = new short[rawData.pcmData.remaining()];
        rawData.pcmData.rewind();
        rawData.pcmData.get(audioData);
        applyDspForType(audioData, rawData.sampleRate, type);

        AudioStreamBuffer buffer = new AudioStreamBuffer(trackId + "_" + type, rawData.sampleRate);
        ShortBuffer pcm = MemoryUtil.memAllocShort(audioData.length);
        pcm.put(audioData);
        pcm.flip();
        buffer.setSourceData(pcm);
        session.getStreamBuffers().put(type, buffer);
    }

    void applyDspForType(short[] audioData, int sampleRate, String speakerType) {
        AudioDSP.applyGain(audioData, 0.60f);
        if (AudioEngine.TYPE_SUB.equals(speakerType)) {
            AudioDSP.applyFilter(audioData, sampleRate, AudioDSP.FilterType.LOW_PASS, 120, 0.707f, 0);
            AudioDSP.applyFilter(audioData, sampleRate, AudioDSP.FilterType.LOW_PASS, 120, 0.707f, 0);
        } else if (AudioEngine.TYPE_MID.equals(speakerType)) {
            AudioDSP.applyFilter(audioData, sampleRate, AudioDSP.FilterType.HIGH_PASS, 45, 0.577f, 0);
        } else if (AudioEngine.TYPE_LINE.equals(speakerType)) {
            AudioDSP.applyFilter(audioData, sampleRate, AudioDSP.FilterType.HIGH_PASS, 120, 0.707f, 0);
            AudioDSP.applyFilter(audioData, sampleRate, AudioDSP.FilterType.HIGH_PASS, 120, 0.707f, 0);
        }
        AudioDSP.applyPeakLimiter(audioData, 0.98f);
    }

    void playTrack(UUID sessionUUID, String trackId, List<BlockPos> speakers, float power, float inputGain) {
        PlaybackSession session = sessions.computeIfAbsent(sessionUUID, key -> new PlaybackSession(engine));
        session.stopAll();
        engine.loadPersistedEqIntoSession(session, sessionUUID);
        session.setPlayUrl("");
        resetGlobalVenueState(speakers);

        try {
            prepareStreamBuffers(session, trackId);
            finalizePlaybackPipeline(sessionUUID, speakers, power, inputGain, true);
        } catch (Exception e) {
            e.printStackTrace();
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

    private boolean isActiveUrlRequest(UUID sessionUUID, long requestId) {
        Long activeRequestId = activeUrlRequestIds.get(sessionUUID);
        return activeRequestId != null && activeRequestId.longValue() == requestId;
    }

    void playFromUrl(UUID sessionUUID, String url, List<BlockPos> speakers, float power, float inputGain) {
        playFromUrl(sessionUUID, url, speakers, power, inputGain, true, null);
    }

    void playFromUrl(
            UUID sessionUUID,
            String url,
            List<BlockPos> speakers,
            float power,
            float inputGain,
            boolean startImmediately,
            Consumer<UUID> onReadyCallback) {
        cancelUrlRequest(sessionUUID);
        InternetAudioLoader loader = InternetAudioLoader.getInstance();
        PlaybackSession existingSession = sessions.get(sessionUUID);
        if (existingSession != null) {
            existingSession.stopAll();
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
                    System.out.println(
                            "AudioEngine: Ignoring stale URL request #" + requestId + " for session " + sessionUUID);
                    return;
                }

                AudioStreamBuffer sharedBuffer = new AudioStreamBuffer("url_stream", sampleRate);
                System.out.println("AudioEngine: URL request #" + requestId + " ready for session " + sessionUUID);
                sharedBuffer.initStreaming(pcmInterleaved, decodedFrames, totalExpected);

                PlaybackSession session = sessions.computeIfAbsent(sessionUUID, key -> new PlaybackSession(engine));
                session.stopAll();
                engine.loadPersistedEqIntoSession(session, sessionUUID);
                session.setPlayUrl(url);
                session.getStreamBuffers().clear();
                session.getStreamBuffers().put(AudioEngine.TYPE_SUB, sharedBuffer);
                session.getStreamBuffers().put(AudioEngine.TYPE_MID, sharedBuffer);
                session.getStreamBuffers().put(AudioEngine.TYPE_LINE, sharedBuffer);
                session.getStreamBuffers().put(AudioEngine.TYPE_NORMAL, sharedBuffer);

                resetGlobalVenueState(speakers);
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
                System.out.println("AudioEngine: URL request #" + requestId + " completed for session " + sessionUUID);
            }

            @Override
            public void onFailed(long requestId, String reason) {
                if (!activeUrlRequestIds.remove(sessionUUID, requestId)) return;
                System.err.println("AudioEngine: URL request #" + requestId + " failed: " + reason);
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
        System.out.println("AudioEngine: URL request #" + startedRequestId + " started for session " + sessionUUID);
    }

    void createSourcesFromClusters(
            PlaybackSession session,
            List<List<BlockPos>> clusters,
            int[] counts,
            World world,
            float power,
            float inputGain) {
        for (List<BlockPos> cluster : clusters) {
            int[] clusterCounts = SpeakerClusterer.countSpeakerTypes(cluster, world);
            StreamSource leaderSource = null;
            for (BlockPos position : cluster) {
                String speakerType = AudioEngine.TYPE_NORMAL;
                float baseReferenceDistance = 3.0f;
                float baseMaxDistance = 64.0f;
                int sampleShiftMs = 0;
                int channelMask = 0;
                int speakerCount = 1;

                if (world != null) {
                    BlockState blockState = world.getBlockState(position);
                    var block = blockState.getBlock();
                    if (block instanceof com.audiophilecraft.block.SubwooferBlock) {
                        speakerType = AudioEngine.TYPE_SUB;
                        baseReferenceDistance = 10.0f;
                        baseMaxDistance = 85.0f;
                        speakerCount = clusterCounts[0];
                    } else if (block instanceof com.audiophilecraft.block.MidRangeBlock) {
                        speakerType = AudioEngine.TYPE_MID;
                        baseReferenceDistance = 5.0f;
                        baseMaxDistance = 60.0f;
                        speakerCount = clusterCounts[1];
                    } else if (block instanceof com.audiophilecraft.block.LineArrayBlock) {
                        speakerType = AudioEngine.TYPE_LINE;
                        baseReferenceDistance = 3.0f;
                        baseMaxDistance = 50.0f;
                        speakerCount = clusterCounts[2];
                    } else {
                        speakerCount = clusterCounts[3];
                    }

                    net.minecraft.block.entity.BlockEntity blockEntity = world.getBlockEntity(position);
                    if (blockEntity instanceof com.audiophilecraft.block.entity.SpeakerBlockEntity speaker) {
                        sampleShiftMs = speaker.getSampleShift();
                        channelMask = speaker.getChannelMask();
                    }
                }

                AudioStreamBuffer buffer = session.getStreamBuffers().get(speakerType);
                if (buffer == null) buffer = session.getStreamBuffers().get(AudioEngine.TYPE_NORMAL);
                if (buffer == null) continue;

                int sourceId = alGenSources();
                int error = alGetError();
                if (error != AL_NO_ERROR) {
                    System.err.println("AudioEngine: OPENAL SOURCE LIMIT HIT! Failed at speaker #"
                            + (session.getStreamSources().size() + 1) + " of "
                            + clusters.stream().mapToInt(List::size).sum()
                            + " (error=0x" + Integer.toHexString(error) + ")");
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

                Direction facing = Direction.SOUTH;
                int tiltDegrees = 0;
                if (world != null) {
                    BlockState state = world.getBlockState(position);
                    if (state.contains(Properties.HORIZONTAL_FACING)) {
                        facing = state.get(Properties.HORIZONTAL_FACING);
                    }
                    net.minecraft.block.entity.BlockEntity blockEntity = world.getBlockEntity(position);
                    if (blockEntity instanceof com.audiophilecraft.block.entity.SpeakerBlockEntity speaker) {
                        tiltDegrees = speaker.getVerticalTilt();
                    }
                }

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
                    System.err.println("AudioEngine: EFX filter/send setup failed: " + e.getMessage());
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
                        leaderSource,
                        cluster.size(),
                        channelMask);
                session.getStreamSources().add(source);
                if (leaderSource == null) leaderSource = source;
            }
        }
    }

    void startPlaybackWithVenueScan(
            PlaybackSession session, World world, List<BlockPos> speakers, boolean atomicStart) {
        Runnable startPlayback = () -> {
            session.setStreamStartTime(System.nanoTime());
            engine.syncListenerToCamera();

            if (atomicStart) {
                java.nio.IntBuffer sourceIds =
                        BufferUtils.createIntBuffer(session.getStreamSources().size());
                for (StreamSource source : session.getStreamSources()) {
                    alSourcei(source.sourceId, AL_LOOPING, AL_FALSE);
                    sourceIds.put(source.sourceId);
                }
                sourceIds.flip();
                alSourcePlayv(sourceIds);
            } else {
                for (StreamSource source : session.getStreamSources()) {
                    source.start();
                }
            }

            engine.startAudioThread();
        };

        if (!session.getStreamSources().isEmpty() && world != null) {
            List<List<BlockPos>> clusters = SpeakerClusterer.clusterSpeakers(speakers);
            List<Vec3d> clusterCenters = new ArrayList<>();
            for (List<BlockPos> cluster : clusters) {
                double centerX = 0;
                double centerY = 0;
                double centerZ = 0;
                for (BlockPos position : cluster) {
                    centerX += position.getX() + 0.5;
                    centerY += position.getY() + 0.5;
                    centerZ += position.getZ() + 0.5;
                }
                clusterCenters.add(
                        new Vec3d(centerX / cluster.size(), centerY / cluster.size(), centerZ / cluster.size()));
            }

            int generation = trackGeneration;
            CompletableFuture.supplyAsync(() -> {
                        try {
                            return effects.scanVenue(world, clusterCenters);
                        } catch (Exception e) {
                            System.err.println("Venue scan crash: " + e.getMessage());
                            return null;
                        }
                    })
                    .exceptionally(exception -> {
                        System.err.println("Venue scan future failed: " + exception.getMessage());
                        return null;
                    })
                    .thenAcceptAsync(
                            preset -> {
                                if (generation != trackGeneration) return;
                                if (preset != null) {
                                    effects.applyScannedVenuePreset(preset);
                                }
                                startPlayback.run();
                            },
                            MinecraftClient.getInstance()::execute);
        } else {
            startPlayback.run();
        }
    }

    void playFromPcmData(
            UUID sessionUUID, short[] pcmData, int sampleRate, List<BlockPos> speakers, float power, float inputGain) {
        PlaybackSession session = sessions.computeIfAbsent(sessionUUID, key -> new PlaybackSession(engine));
        session.stopAll();
        engine.loadPersistedEqIntoSession(session, sessionUUID);
        resetGlobalVenueState(speakers);

        ShortBuffer pcmBuffer = null;
        try {
            pcmBuffer = MemoryUtil.memAllocShort(pcmData.length);
            pcmBuffer.put(pcmData);
            pcmBuffer.flip();

            OggDecoder.RawTrackData rawData = new OggDecoder.RawTrackData();
            rawData.pcmData = pcmBuffer;
            rawData.sampleRate = sampleRate;
            rawData.channels = 1;
            rawData.format = AL_FORMAT_MONO16;

            for (AudioStreamBuffer buffer : session.getStreamBuffers().values()) {
                buffer.cleanup();
            }
            session.getStreamBuffers().clear();
            createStreamBufferForType(session, "url_track", rawData, AudioEngine.TYPE_SUB);
            createStreamBufferForType(session, "url_track", rawData, AudioEngine.TYPE_MID);
            createStreamBufferForType(session, "url_track", rawData, AudioEngine.TYPE_LINE);
            createStreamBufferForType(session, "url_track", rawData, AudioEngine.TYPE_NORMAL);

            finalizePlaybackPipeline(sessionUUID, speakers, power, inputGain, true);
        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            if (pcmBuffer != null) {
                MemoryUtil.memFree(pcmBuffer);
            }
        }
    }

    private void resetGlobalVenueState(List<BlockPos> speakers) {
        trackGeneration++;
        effects.resetVenueState(speakers);
    }

    private void finalizePlaybackPipeline(
            UUID sessionUUID, List<BlockPos> speakers, float power, float inputGain, boolean startImmediately) {
        if (speakers == null || speakers.isEmpty()) return;

        PlaybackSession session = sessions.get(sessionUUID);
        for (AudioStreamBuffer buffer : session.getStreamBuffers().values()) {
            if (buffer.sampleRate > 0) buffer.syncToTime(AudioEngine.BUFFER_LOOKAHEAD);
        }

        World world = MinecraftClient.getInstance().world;
        int[] counts = SpeakerClusterer.countSpeakerTypes(speakers, world);
        List<List<BlockPos>> clusters = SpeakerClusterer.clusterSpeakers(speakers);
        createSourcesFromClusters(session, clusters, counts, world, power, inputGain);

        if (startImmediately) {
            session.setPlaying(true);
            session.setPaused(false);
        }
        startPlaybackWithVenueScan(session, world, speakers, startImmediately);
    }
}
