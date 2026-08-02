package com.audiophilecraft.sound;

import com.audiophilecraft.block.LineArrayBlock;
import com.audiophilecraft.block.MidRangeBlock;
import com.audiophilecraft.block.SpeakerBlock;
import com.audiophilecraft.block.SubwooferBlock;
import com.audiophilecraft.config.LiveTuningConfig;
import net.minecraft.block.Block;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.ChunkStatus;
import net.minecraft.world.chunk.EmptyChunk;
import org.lwjgl.openal.AL10;

/**
 * Coordinates one OpenAL stream source's lifecycle.
 *
 * <p>Audio rendering, spatial response, occlusion and native resource ownership
 * live in focused collaborators. This class keeps their timing and lifecycle in
 * sync.
 */
public class StreamSource {
    public final int sourceId;
    private final StreamAudioRenderer audioRenderer;
    private final OpenALSourceResources openAlResources;
    private final SourceSpatialController spatialController;

    // Updated under the audio lifecycle lock and read by both runtime threads.
    private volatile float currentDistanceSnapshot;

    // Followers use their leader's distance for propagation delay synchronization.
    private volatile float delayDistanceSnapshot;
    private float pausedPropagationTarget = Float.NaN;

    // Public metadata retained for AudioEngine and tablet controls.
    public final BlockPos pos;
    public float power;
    public final float maxDist;
    public final float refDist;
    public final float dirX;
    public final float dirY;
    public final float dirZ;
    public final String speakerType;
    public final int filterId;
    public final int sendFilterId;
    public final int echoSendFilterId;
    public final int sampleShiftMs;
    public final int speakerCount;
    public float targetOcclusion = 1.0f;
    public float currentOcclusion = 1.0f;
    public float inputGain = 1.0f;

    public final StreamSource clusterLeader;
    public final boolean isLeader;
    private final EmitterGroup emitterGroup;

    public volatile boolean isValid;
    public volatile boolean isFinished;

    public StreamSource(
            PlaybackSession session,
            int sourceId,
            AudioStreamBuffer streamBuffer,
            BlockPos pos,
            float power,
            float maxDist,
            float refDist,
            float dirX,
            float dirY,
            float dirZ,
            String speakerType,
            int filterId,
            int sendFilterId,
            int echoSendFilterId,
            float inputGain,
            int sampleShiftMs,
            int speakerCount,
            EmitterGroup emitterGroup,
            StreamSource clusterLeader,
            int clusterSize,
            int initialChannelMask) {
        this.sourceId = sourceId;
        this.pos = pos;
        this.power = power;
        this.maxDist = maxDist;
        this.refDist = refDist;
        this.dirX = dirX;
        this.dirY = dirY;
        this.dirZ = dirZ;
        this.speakerType = speakerType;
        this.filterId = filterId;
        this.sendFilterId = sendFilterId;
        this.echoSendFilterId = echoSendFilterId;
        this.sampleShiftMs = sampleShiftMs;
        this.speakerCount = speakerCount;
        this.inputGain = inputGain;
        this.emitterGroup = emitterGroup;
        this.clusterLeader = clusterLeader;
        this.isLeader = clusterLeader == null;

        this.openAlResources = new OpenALSourceResources(sourceId, filterId, sendFilterId, echoSendFilterId);
        this.spatialController = new SourceSpatialController(
                session,
                openAlResources,
                pos,
                speakerType,
                refDist,
                dirX,
                dirY,
                dirZ,
                speakerCount,
                clusterSize,
                emitterGroup,
                power,
                inputGain);

        initializeDistanceSnapshots();

        // Followers must use the leader delay while priming their first buffers.
        if (!isLeader && clusterLeader.isValid) {
            delayDistanceSnapshot = clusterLeader.currentDistanceSnapshot;
        }

        this.audioRenderer = new StreamAudioRenderer(
                session,
                sourceId,
                streamBuffer,
                speakerType,
                sampleShiftMs,
                delayDistanceSnapshot,
                initialChannelMask,
                spatialController.smoothedInputGain());
        publish();
    }

    private void initializeDistanceSnapshots() {
        Vec3d listenerPosition = AudioEngine.captureCurrentListenerPosition();
        if (listenerPosition == null) return;

        LiveTuningConfig config = LiveTuningConfig.get();
        double deltaX = pos.getX() + 0.5 - listenerPosition.x;
        double deltaY = (pos.getY() + 0.5 - listenerPosition.y) * config.physics_yFlatten;
        double deltaZ = pos.getZ() + 0.5 - listenerPosition.z;
        currentDistanceSnapshot = (float) Math.sqrt(deltaX * deltaX + deltaY * deltaY + deltaZ * deltaZ);
        delayDistanceSnapshot = currentDistanceSnapshot;
        openAlResources.updateSpatialPosition(pos, listenerPosition, config.hrtf_yFlatten);
    }

    private void publish() {
        isFinished = false;
        isValid = true;
    }

    public EmitterGroup getEmitterGroup() {
        return emitterGroup;
    }

    /**
     * Sets the stereo channel used on the next OpenAL buffer refill.
     *
     * @param mask 0 for both, 1 for left, or 2 for right
     */
    public void setChannelMask(int mask) {
        audioRenderer.setChannelMask(mask);
    }

    public int getChannelMask() {
        return audioRenderer.getChannelMask();
    }

    float getPendingEchoContribution() {
        return spatialController.pendingEchoContribution();
    }

    void applyEchoNormalization(float normalization) {
        spatialController.applyPendingEchoSend(normalization);
    }

    /** Starts playback after all sources in the session have been created. */
    public void start() {
        openAlResources.start();
    }

    public synchronized void seekToTime(double timeSeconds) {
        if (!isValid) return;

        isFinished =
                audioRenderer.seekToTime(timeSeconds, delayDistanceSnapshot, spatialController.smoothedInputGain());
    }

    public synchronized boolean update(World world, Vec3d listenerPosition, double timeSeconds) {
        if (!isValid) return false;
        if (!speakerStillExists(world)) return false;
        if (finishedBuffersHaveDrained()) return false;

        float distance = currentDistanceSnapshot;
        double deltaX = pos.getX() + 0.5 - listenerPosition.x;
        double deltaY = (pos.getY() + 0.5 - listenerPosition.y) * LiveTuningConfig.get().physics_yFlatten;
        double deltaZ = pos.getZ() + 0.5 - listenerPosition.z;

        spatialController.update(world, listenerPosition, distance, deltaX, deltaY, deltaZ, power, inputGain);
        targetOcclusion = spatialController.targetOcclusion();
        currentOcclusion = spatialController.currentOcclusion();
        return true;
    }

    private boolean speakerStillExists(World world) {
        if (world == null) return true;

        Chunk chunk = world.getChunk(pos.getX() >> 4, pos.getZ() >> 4, ChunkStatus.FULL, false);
        if (chunk == null || chunk instanceof EmptyChunk) {
            return true;
        }

        Block block = chunk.getBlockState(pos).getBlock();
        return block instanceof SubwooferBlock
                || block instanceof MidRangeBlock
                || block instanceof SpeakerBlock
                || block instanceof LineArrayBlock;
    }

    private boolean finishedBuffersHaveDrained() {
        if (!isFinished) return false;

        int queued = AL10.alGetSourcei(sourceId, AL10.AL_BUFFERS_QUEUED);
        int state = AL10.alGetSourcei(sourceId, AL10.AL_SOURCE_STATE);
        return queued == 0 || state == AL10.AL_STOPPED;
    }

    public BlockPos getPos() {
        return pos;
    }

    public AudioStreamBuffer getStreamBuffer() {
        return audioRenderer.getStreamBuffer();
    }

    public double getOutputCursor() {
        return audioRenderer.getOutputCursor();
    }

    /**
     * Feeds processed OpenAL buffers from the shared global audio clock.
     * Called from the background audio thread while this source is locked.
     */
    public synchronized boolean feedOpenALFromAudioThread(double globalSampleTime, Vec3d listenerPosition) {
        if (!isValid) return false;

        updateDistanceSnapshots(listenerPosition);
        StreamAudioRenderer.FeedResult result = audioRenderer.feed(
                globalSampleTime,
                delayDistanceSnapshot,
                spatialController.smoothedInputGain(),
                isFinished);
        isFinished = result.finished();
        return result.restartRequired();
    }

    private void updateDistanceSnapshots(Vec3d listenerPosition) {
        updateOwnDistanceSnapshot(listenerPosition);
        updateDelayDistanceSnapshot();
    }

    synchronized void updatePausedDistanceSnapshot(Vec3d listenerPosition) {
        if (!isValid) return;

        updateOwnDistanceSnapshot(listenerPosition);
    }

    synchronized void capturePausedPropagationTarget() {
        if (!isValid) return;

        updateDelayDistanceSnapshot();
        pausedPropagationTarget = delayDistanceSnapshot;
    }

    private void updateOwnDistanceSnapshot(Vec3d listenerPosition) {
        if (listenerPosition == null) return;

        double deltaX = pos.getX() + 0.5 - listenerPosition.x;
        double deltaY = (pos.getY() + 0.5 - listenerPosition.y) * LiveTuningConfig.get().physics_yFlatten;
        double deltaZ = pos.getZ() + 0.5 - listenerPosition.z;
        float ownDistance = (float) Math.sqrt(deltaX * deltaX + deltaY * deltaY + deltaZ * deltaZ);
        currentDistanceSnapshot = ownDistance;
    }

    private void updateDelayDistanceSnapshot() {
        if (!isLeader && clusterLeader != null && clusterLeader.isValid) {
            delayDistanceSnapshot = clusterLeader.currentDistanceSnapshot;
        } else {
            delayDistanceSnapshot = currentDistanceSnapshot;
        }
    }

    public void pause() {
        openAlResources.pause();
    }

    public synchronized void resume() {
        if (isValid) {
            if (!Float.isNaN(pausedPropagationTarget)) {
                audioRenderer.snapPropagationDelay(pausedPropagationTarget);
                pausedPropagationTarget = Float.NaN;
            }
            openAlResources.resume();
        }
    }

    /**
     * Releases Java-side native memory without calling OpenAL functions.
     * Used after the OpenAL context has been destroyed and old IDs are invalid.
     */
    public synchronized void releaseNativeMemory() {
        audioRenderer.releaseNativeMemory();
        isValid = false;
        isFinished = true;
    }

    public synchronized void cleanup() {
        if (!isValid) return;

        openAlResources.stop();
        releaseNativeMemory();
        openAlResources.delete(audioRenderer);
    }
}
