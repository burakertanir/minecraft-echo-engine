package com.audiophilecraft.sound;

import static org.lwjgl.openal.AL10.*;

import java.nio.IntBuffer;
import java.nio.ShortBuffer;
import net.minecraft.util.math.Vec3d;
import org.lwjgl.BufferUtils;
import org.lwjgl.system.MemoryUtil;

public class StreamSource {
    public final int sourceId;
    public final AudioStreamBuffer streamBuffer;

    // ═══════════════════════════════════════════════════════════════════════
    // GLOBAL MASTER CLOCK ARCHITECTURE
    // All sources derive their read position from a single global sample time:
    // readPosition = globalSampleTime - propagationDelaySamples
    // No per-source local counter. Zero drift. Mathematically guaranteed.
    // 6 buffers × 1024 samples = ~128ms of runway in the sound card.
    // ═══════════════════════════════════════════════════════════════════════
    private static final int BUFFER_COUNT = 6;
    private static final int STREAM_BUFFER_SIZE = 1024; // ~21ms per buffer
    private final IntBuffer buffers;

    // Distance snapshot (volatile: written by audio thread during feed, read by
    // main thread for physics)
    private volatile float currentDistanceSnapshot = 0;

    // Delay-specific distance: for leaders = own distance, for followers = leader's
    // distance.
    // Written ONLY by the audio thread → no race condition with main thread.
    // Used exclusively by generatePcmBlock() for propagation delay calculation.
    private volatile float delayDistanceSnapshot = 0;

    // Output cursor: tracks the next sample position to generate.
    // RESET from global clock at start/seek/underrun → zero inter-source drift.
    // Between resets, advances sequentially by STREAM_BUFFER_SIZE to guarantee
    // buffer-boundary continuity (no gaps or overlaps → no crackle).
    private double outputCursor = 0;

    // Audio-thread-owned native buffer for OpenAL uploads
    private ShortBuffer audioThreadPcmBuffer;
    private short[] audioThreadRawAudio;

    // Metadata for physics
    public final net.minecraft.util.math.BlockPos pos;
    public float power; // Mutable for live power knob updates
    public final float maxDist;
    public final float refDist;
    public final float dirX, dirY, dirZ;
    public final String speakerType;
    public final int filterId;
    public final int sendFilterId;
    public final int echoSendFilterId;
    public final int sampleShiftMs;
    public final int speakerCount;
    public float targetOcclusion = 1.0f;
    public float currentOcclusion = 1.0f;
    public float inputGain = 1.0f; // New field: controlled by AudioEngine
    // (Debug field removed — unused)

    // Logic Clustering
    public final StreamSource clusterLeader;
    public final boolean isLeader;
    private final PlaybackSession session;

    // Smoothed versions for pop-free knob transitions
    private volatile float smoothedPower;
    private volatile float smoothedInputGain = 1.0f;

    // DSP Pipeline (crossover -> EQ -> softclip -> limiter)
    private final StreamDSPPipeline dspPipeline;

    // Reusable buffers (Optimization: avoids memAlloc/memFree every refill)
    private ShortBuffer reusablePcmBuffer;
    private short[] reusableRawAudio;
    private int clusterSize;

    // Valid check
    public volatile boolean isValid = false;

    public volatile boolean isFinished = false;

    // Publishes the fully-constructed object: set after all fields initialized
    private void publish() {
        this.isFinished = false;
        this.isValid = true;
    }

    // Fast fade-in to prevent harsh waveform snap-pops on manual seeks
    private long seekFadeSamplesRemaining = 0;

    public StreamSource(
            PlaybackSession session,
            int sourceId,
            AudioStreamBuffer streamBuffer,
            net.minecraft.util.math.BlockPos pos,
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
            StreamSource clusterLeader,
            int clusterSize) {
        this.session = session;
        this.sourceId = sourceId;
        this.streamBuffer = streamBuffer;

        // Metadata
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
        this.clusterSize = clusterSize;

        this.clusterLeader = clusterLeader;
        this.isLeader = (clusterLeader == null);

        // Initialize smoothed values to match starting values
        this.smoothedPower = power;

        // Use the inputGain passed directly from the tablet item
        this.inputGain = inputGain;
        this.smoothedInputGain = inputGain;

        // Initialize DSP pipeline
        float sr = (streamBuffer != null && streamBuffer.sampleRate > 0) ? (float) streamBuffer.sampleRate : 44100f;
        this.dspPipeline = new StreamDSPPipeline(this.session, this.speakerType, sr);

        // Allocate reusable buffers (once, not per-refill)
        this.reusablePcmBuffer = MemoryUtil.memAllocShort(STREAM_BUFFER_SIZE);
        this.reusableRawAudio = new short[STREAM_BUFFER_SIZE];

        // Audio-thread-owned buffers for background OpenAL uploads
        this.audioThreadPcmBuffer = MemoryUtil.memAllocShort(STREAM_BUFFER_SIZE);
        this.audioThreadRawAudio = new short[STREAM_BUFFER_SIZE];

        // CRITICAL: Calculate initial distance from listener BEFORE first buffer fill.
        // Without this, all speakers start with 0ms delay and slowly ramp up,
        // causing audible desync between near and far speakers for ~2 seconds.
        net.minecraft.client.MinecraftClient mc = net.minecraft.client.MinecraftClient.getInstance();
        if (mc.player != null) {
            double dx = pos.getX() + 0.5 - mc.player.getX();
            double dy = pos.getY() + 0.5 - mc.player.getY();
            double dz = pos.getZ() + 0.5 - mc.player.getZ();
            this.currentDistanceSnapshot = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
            // Initialize delay distance (cluster sync will be applied on first audio thread
            // tick)
            this.delayDistanceSnapshot = this.currentDistanceSnapshot;
        }

        // Generate Buffers
        this.buffers = BufferUtils.createIntBuffer(BUFFER_COUNT);
        alGenBuffers(this.buffers);

        // Initially fill OpenAL hardware queue with sequential absolute positions.
        // Buffer 0 = samples [0, 1024), Buffer 1 = [1024, 2048), etc.
        // All sources fill the same sample ranges → zero phase offset at start.
        for (int i = 0; i < BUFFER_COUNT; i++) {
            double bufferStartSample = (double) (i * STREAM_BUFFER_SIZE);
            generatePcmBlock(reusableRawAudio, bufferStartSample);
            reusablePcmBuffer.clear();
            reusablePcmBuffer.put(reusableRawAudio);
            reusablePcmBuffer.flip();
            alBufferData(buffers.get(i), AL_FORMAT_MONO16, reusablePcmBuffer, (int) streamBuffer.sampleRate);
        }
        this.outputCursor = (double) (BUFFER_COUNT * STREAM_BUFFER_SIZE);

        // Queue all buffers (but DON'T play yet — wait for batch start)
        alSourceQueueBuffers(sourceId, this.buffers);
        publish();
    }

    /**
     * Start playback. Called AFTER all StreamSources are created
     * to ensure all speakers start at exactly the same time.
     */
    public void start() {
        alSourcePlay(sourceId);
    }

    /**
     * Forcibly jumps the active Audio Stream to a target timeline marker.
     * Uses global absolute positions — no local counter to reset.
     * Synchronized to prevent background AudioThread race conditions.
     */
    public synchronized void seekToTime(double timeSeconds) {
        if (!isValid) return;

        // CRITICAL: Reset finished state so seek works on ended tracks
        this.isFinished = false;

        // 50ms ramp-in fade to stop DAC cone snap/clicks from splicing peak waves
        this.seekFadeSamplesRemaining = (long) (0.05 * streamBuffer.sampleRate);

        // Reset IIR filter state to prevent impulse ringing at splice
        if (this.dspPipeline != null) {
            this.dspPipeline.reset();
        }
        // Reset delay state so it re-initializes from current distance
        this.lastRenderedDelaySamples = -1.0;

        // Flush OpenAL's queued buffers

        org.lwjgl.openal.AL10.alSourceStop(sourceId);

        int queued = org.lwjgl.openal.AL10.alGetSourcei(sourceId, org.lwjgl.openal.AL10.AL_BUFFERS_QUEUED);

        while (queued > 0) {

            org.lwjgl.openal.AL10.alSourceUnqueueBuffers(sourceId);

            queued--;
        }

        // Refill using global absolute positions from the seek target
        double seekStartSample = timeSeconds * streamBuffer.sampleRate;
        for (int i = 0; i < BUFFER_COUNT; i++) {
            double bufferStartSample = seekStartSample + (i * STREAM_BUFFER_SIZE);
            generatePcmBlock(reusableRawAudio, bufferStartSample);
            reusablePcmBuffer.clear();
            reusablePcmBuffer.put(reusableRawAudio);
            reusablePcmBuffer.flip();
            alBufferData(buffers.get(i), AL_FORMAT_MONO16, reusablePcmBuffer, (int) streamBuffer.sampleRate);
        }
        this.outputCursor = seekStartSample + (BUFFER_COUNT * STREAM_BUFFER_SIZE);

        // Queue and let AudioEngine trigger atomic alSourcePlayv
        org.lwjgl.openal.AL10.alSourceQueueBuffers(sourceId, this.buffers);
    }

    public synchronized boolean update(net.minecraft.world.World world, Vec3d listenerPos, double timeSeconds) {
        if (!isValid) return false;

        // ═══════════════════════════════════════════════════════════════
        // DECOUPLED: Speaker = static registry entry.
        // Chunk unload does NOT affect audio playback.
        // However, if the chunk IS loaded and the block is broken,
        // we must stop the source.
        //
        // IMPORTANT: On the client side, Minecraft may report a chunk as
        // "loaded" but with all block data replaced with AIR when the chunk
        // is beyond render distance. We must verify the chunk actually has
        // real block data before checking if the speaker block still exists.
        // ═══════════════════════════════════════════════════════════════
        if (world != null) {
            // Get the actual chunk object — if null or empty, chunk is unloaded → skip
            // check
            net.minecraft.world.chunk.Chunk chunk =
                    world.getChunk(pos.getX() >> 4, pos.getZ() >> 4, net.minecraft.world.chunk.ChunkStatus.FULL, false);
            if (chunk != null && !(chunk instanceof net.minecraft.world.chunk.EmptyChunk)) {
                net.minecraft.block.Block block = chunk.getBlockState(pos).getBlock();
                if (!(block instanceof com.audiophilecraft.block.SubwooferBlock)
                        && !(block instanceof com.audiophilecraft.block.MidRangeBlock)
                        && !(block instanceof com.audiophilecraft.block.SpeakerBlock)
                        && !(block instanceof com.audiophilecraft.block.LineArrayBlock)) {
                    return false; // Block was genuinely broken or replaced, destroy source
                }
            }
            // else: chunk is unloaded — speaker still exists, keep playing
        }

        // Distance is now calculated on the audio thread (computeNextBuffer).
        // We read the latest snapshot for physics calculations.
        float distance = this.currentDistanceSnapshot;
        double spkX = pos.getX() + 0.5;
        double spkY = pos.getY() + 0.5;
        double spkZ = pos.getZ() + 0.5;
        double dx = spkX - listenerPos.x;
        // Y eksenindeki fiziksel yüksekliği daraltır. (Açı ve hacim hesaplamaları için)
        double dy = (spkY - listenerPos.y) * com.audiophilecraft.config.LiveTuningConfig.get().physics_yFlatten;
        double dz = spkZ - listenerPos.z;

        // If song has reached EOF, wait until all queued buffers have naturally drained
        if (isFinished) {
            int queued = org.lwjgl.openal.AL10.alGetSourcei(sourceId, org.lwjgl.openal.AL10.AL_BUFFERS_QUEUED);
            int state = org.lwjgl.openal.AL10.alGetSourcei(sourceId, org.lwjgl.openal.AL10.AL_SOURCE_STATE);
            if (queued == 0 || state == org.lwjgl.openal.AL10.AL_STOPPED) {
                // Return false to trigger cleanup in AudioEngine.updateSourcesTick().
                // This frees the OpenAL source ID so Minecraft's own sound system
                // can use it. Seeking on a finished track is handled by pressing
                // play again (which calls playTrack() → stopAll() → new sources).
                return false;
            }
        }

        // Audio Physics (Gain, Occlusion, EFX) — uses audio-thread's distance snapshot
        updatePhysics(world, listenerPos, distance, dx, dy, dz);

        return true;
    }

    // Smoothing State
    private float smoothedGain = 0.0f;
    private float smoothedDirectGain = 1.0f;

    // Occlusion caching (avoid expensive ray-march every tick per speaker)
    private float cachedTargetOcclusion = 1.0f;
    private long lastOcclusionCalcTick = -1;
    private Vec3d lastOcclusionListenerPos = null;

    private void updatePhysics(
            net.minecraft.world.World world, Vec3d listenerPos, double distance, double dx, double dy, double dz) {
        com.audiophilecraft.config.LiveTuningConfig cfg = com.audiophilecraft.config.LiveTuningConfig.get();

        // --- SMOOTH POWER & INPUT GAIN ---
        // Lerp factor 0.04 = ~500ms ramp time at 20Hz tick rate
        // Slower ramp prevents audible clicks at buffer boundaries
        this.smoothedPower += (this.power - this.smoothedPower) * 0.04f;
        this.smoothedInputGain += (this.inputGain - this.smoothedInputGain) * 0.04f;

        // --- NATIVE OPENAL DISTANCE MODEL ---
        // Native OpenAL physics:
        // Gain = RefDist / (RefDist + Rolloff * (Dist - RefDist))

        // --- DIRECTIONALITY & OCCLUSION (Multipliers) ---

        // Directionality
        // --- PRE-CALCULATIONS ---
        float dirGain = 1.0f;
        float directionalFocus = 1.0f;
        float targetOcclusion = cachedTargetOcclusion;
        float proximityBoost = 1.0f;
        float attenuation = 1.0f;
        float dist = (float) currentDistanceSnapshot;
        double hzDot = 1.0; // Default to 1.0 (straight front)
        double vtDot = 1.0; // Default to 1.0

        // --- DIRECTIONALITY (If Applicable) ---
        if (!"sub".equals(speakerType)) {
            // Horizontal Vector (XZ Plane)
            double toLxHz = -dx;
            double toLzHz = -dz;
            double distHz = Math.sqrt(toLxHz * toLxHz + toLzHz * toLzHz);

            // Speaker Facing Vector (Horizontal)
            double dirXHz = dirX;
            double dirZHz = dirZ;
            double dirDistHz = Math.sqrt(dirXHz * dirXHz + dirZHz * dirZHz);

            hzDot = 0;
            vtDot = 0;

            if (distHz > 0.001 && dirDistHz > 0.001) {
                toLxHz /= distHz;
                toLzHz /= distHz;

                dirXHz /= dirDistHz;
                dirZHz /= dirDistHz;

                // Horizontal Dot Product (Yaw difference)
                hzDot = (dirXHz * toLxHz) + (dirZHz * toLzHz);
            }

            if (distance > 0.001) {
                // Vertical Dot Product (Pitch difference)
                // Normalize 3D vector for pitch evaluation
                double toLy = -dy / distance;
                // Clamp inputs to [-1.0, 1.0] to prevent Math.asin from returning NaN
                // (sine/arcsine domain error)
                toLy = Math.max(-1.0, Math.min(1.0, toLy));

                // If facing perfectly horizontal, dirY is 0.
                // We compare the listener's elevation angle to the speaker's elevation angle.
                double listenerPitch = Math.asin(toLy); // Radians
                double speakerPitch = Math.asin(Math.max(-1.0, Math.min(1.0, dirY))); // Radians

                // Cosine of the difference in pitch (vertical angle)
                // HRTF Y-axis flattening is handled in AudioEngine.updateListener()
                vtDot = Math.cos(listenerPitch - speakerPitch);
            }

            // Map domains from [-1, 1] to [0, 1] (0 = behind, 0.5 = 90deg side, 1 = front)
            double hzFactor = (hzDot + 1.0) / 2.0;
            double vtFactor = (vtDot + 1.0) / 2.0;

            // Exponents dictate the "Beam Width". Higher exponent = Laser beam (narrow).
            // Lower = Floodlight (wide).
            double hzExp = 1.0;
            double vtExp = 1.0;

            if ("line".equals(speakerType)) {
                hzExp = cfg.line_hzExp;
                vtExp = cfg.line_vtExpBase + Math.sqrt(clusterSize) * cfg.line_vtExpPerSpeaker;
            } else if ("mid".equals(speakerType)) {
                hzExp = cfg.mid_hzExp;
                vtExp = cfg.mid_vtExp;
            } else {
                hzExp = cfg.normal_hzExp;
                vtExp = cfg.normal_vtExp;
            }

            // Calculate directional shape
            double hzShape = Math.pow(hzFactor, hzExp);
            double vtShape = Math.pow(vtFactor, vtExp);

            // Combine axes.
            double combinedFocus = hzShape * vtShape;

            // Separate front and back hemispheres.
            // Rear hemisphere (hzDot < 0) is left completely untouched.
            // Front hemisphere (hzDot >= 0) has its treble reduction effect softened by 40%
            // at the center line.
            if (hzDot >= 0.0) {
                directionalFocus = (float) (combinedFocus + (1.0 - combinedFocus) * 0.40 * hzDot);
            } else {
                directionalFocus = (float) combinedFocus;
            }

            if ("line".equals(speakerType)) {
                dirGain = cfg.line_rearGain + (float) ((1.0 - cfg.line_rearGain) * combinedFocus);
            } else if ("mid".equals(speakerType)) {
                dirGain = cfg.mid_rearGain + (float) ((1.0 - cfg.mid_rearGain) * combinedFocus);
            } else {
                dirGain = cfg.normal_rearGain + (float) ((1.0 - cfg.normal_rearGain) * combinedFocus);
            }
        }

        // --- CLUSTER DELAY SYNC ---
        // MOVED to feedOpenALFromAudioThread (audio thread) via delayDistanceSnapshot.
        // Previously this ran on the main thread and wrote to currentDistanceSnapshot,
        // but the audio thread would immediately overwrite it → race condition.
        // Now the audio thread owns delayDistanceSnapshot exclusively.

        // --- RAYCAST OCCLUSION (CPU Expensive - Cache + Recalc Periodically) ---
        // Run per-source to maintain realistic occlusion where half a cluster can be
        // muffled independently
        if (world != null && distance > 1.5) {
            long nowTick = world.getTime();
            boolean movedEnough = (lastOcclusionListenerPos == null)
                    || (lastOcclusionListenerPos.squaredDistanceTo(listenerPos) > 0.04);
            // Distance-based throttling: far speakers recalc less often
            int recalcInterval;
            if (distance < 30.0) {
                recalcInterval = 2; // 0.25s — nearby, responsive
            } else if (distance < 100.0) {
                recalcInterval = 20; // 1.0s — mid-range
            } else {
                recalcInterval = 40; // 2.0s — far, barely audible anyway
            }
            boolean timeToRecalc = (lastOcclusionCalcTick < 0) || ((nowTick - lastOcclusionCalcTick) >= recalcInterval);

            if (movedEnough || timeToRecalc) {
                // ═══════════════════════════════════════════════════════════════
                // FIXED-STEP RAYCAST OCCLUSION
                // Walks from speaker to listener in 0.25-block steps.
                // Each solid block is naturally sampled ~4 times (1/0.25),
                // giving aggressive occlusion even for single-block walls.
                // ═══════════════════════════════════════════════════════════════

                // Ray origin: speaker center
                double ox = pos.getX() + 0.5;
                double oy = pos.getY() + 0.5;
                double oz = pos.getZ() + 0.5;

                // Ray direction: speaker → listener
                double rdx = listenerPos.x - ox;
                double rdy = listenerPos.y - oy;
                double rdz = listenerPos.z - oz;
                double rayLen = Math.sqrt(rdx * rdx + rdy * rdy + rdz * rdz);
                if (rayLen < 0.001) rayLen = 0.001;
                rdx /= rayLen;
                rdy /= rayLen;
                rdz /= rayLen;

                float stepSize = 0.25f;
                net.minecraft.util.math.BlockPos.Mutable checkPos = new net.minecraft.util.math.BlockPos.Mutable();
                boolean isSub = "sub".equals(this.speakerType);

                int solidStepCount = 0;
                float minTransmissionHit = 1.0f;
                boolean chunkUnloaded = false;

                int lastChunkX = Integer.MAX_VALUE;
                int lastChunkZ = Integer.MAX_VALUE;
                net.minecraft.world.chunk.Chunk currentChunk = null;

                for (float t = stepSize; t < rayLen; t += stepSize) {
                    int bx = (int) Math.floor(ox + rdx * t);
                    int by = (int) Math.floor(oy + rdy * t);
                    int bz = (int) Math.floor(oz + rdz * t);

                    // Skip the speaker block itself
                    if (bx == pos.getX() && by == pos.getY() && bz == pos.getZ()) continue;

                    checkPos.set(bx, by, bz);

                    int chunkX = bx >> 4;
                    int chunkZ = bz >> 4;

                    if (chunkX != lastChunkX || chunkZ != lastChunkZ) {
                        currentChunk =
                                world.getChunk(chunkX, chunkZ, net.minecraft.world.chunk.ChunkStatus.FULL, false);
                        lastChunkX = chunkX;
                        lastChunkZ = chunkZ;
                    }

                    if (currentChunk == null || currentChunk instanceof net.minecraft.world.chunk.EmptyChunk) {
                        // Unloaded chunk = no block data available.
                        // ABORT entire raycast and PRESERVE the last successfully computed occlusion
                        // value.
                        chunkUnloaded = true;
                        break;
                    } else {
                        net.minecraft.block.BlockState state = currentChunk.getBlockState(checkPos);
                        if (!state.isAir()) {
                            float blockTransmission = AdvancedAcousticScanner.getBlockTransmission(state, isSub);
                            // Only apply if it's an actual occluding material
                            if (blockTransmission < 1.0f) {
                                solidStepCount++;
                                if (blockTransmission < minTransmissionHit) {
                                    minTransmissionHit = blockTransmission;
                                }
                            }
                        }
                    }
                }

                float transmissionProduct = 1.0f;
                if (chunkUnloaded) {
                    transmissionProduct = -1.0f; // Sentinel: signals "abort, keep cached"
                } else if (solidStepCount > 0) {
                    float solidDistance = solidStepCount * stepSize;
                    float flexOffset = com.audiophilecraft.config.LiveTuningConfig.get().occ_raycast_flexOffset;
                    int blockThickness = (int) Math.max(1, Math.ceil(solidDistance - flexOffset));

                    float thicknessDecay = com.audiophilecraft.config.LiveTuningConfig.get().occ_thicknessDecay;
                    transmissionProduct = minTransmissionHit * (float) Math.pow(thicknessDecay, blockThickness - 1);

                    if (transmissionProduct < 0.001f) {
                        transmissionProduct = 0.001f;
                    }
                }

                // If transmissionProduct is -1.0, the raycast was aborted due to unloaded
                // chunks. Keep the last successfully computed occlusion value.
                if (transmissionProduct >= 0.0f) {
                    float newTarget = Math.max(0.002f, transmissionProduct);
                    cachedTargetOcclusion = newTarget;
                    this.targetOcclusion = newTarget;
                }
                lastOcclusionCalcTick = nowTick;
                lastOcclusionListenerPos = listenerPos;
            }
        }

        // DECAY TIMER REMOVED: Previously, occlusion would fade to 1.0 (open air) after
        // 2 seconds of no raycast recalc. This is WRONG — if the chunk is unloaded, the
        // wall is still physically there. The last known occlusion value is preserved
        // indefinitely until the chunk is loaded again and a fresh raycast can verify.

        // Use raw occlusion for smoothing to decouple LF and HF tracking.
        // Bass/Low-mid bypassing is now calculated mathematically at the filter stage.

        // Asymmetric Occlusion Smoothing (TEMPORAL HYSTERESIS):
        // Occluding (entering building, drop) = FAST (0.35f, highly responsive)
        // De-occluding (exiting, fade in) = SMOOTH BUT SWIFT (0.15f, takes ~0.3s)
        // With the raycast now properly hitting stairs/slabs, we don't need a massive
        // 2-second delay to hide staircase stuttering anymore. This makes it feel
        // premium.
        float occLerp = (targetOcclusion < this.currentOcclusion) ? cfg.occ_lerpIn : cfg.occ_lerpOut;
        this.currentOcclusion += (targetOcclusion - this.currentOcclusion) * occLerp;

        // --- MANUAL DISTANCE ATTENUATION (DYNAMIC LINEAR) ---
        // Inverse Distance model creates an infinite tail. User reports sound
        // persisting too long.
        // Solution: Dynamic Linear Model where Max Distance scales with Power.

        // Speaker count → DISTANCE scaling only (more speakers = longer throw)
        // This does NOT affect gain/volume — gain staging is fully manual via Input
        // Gain knob
        float arrayMultiplier = (float) Math.max(1.0, Math.sqrt(this.speakerCount));

        float effectiveRefDist = this.refDist;
        float baseMaxDist = 60.0f; // Default base max distance

        if ("sub".equals(this.speakerType)) {
            effectiveRefDist = cfg.sub_refDist * arrayMultiplier;
            baseMaxDist = cfg.sub_baseMaxDist * arrayMultiplier;
            // manualRolloff removed — dead code

            org.lwjgl.openal.AL10.alSourcei(
                    sourceId, org.lwjgl.openal.AL10.AL_SOURCE_RELATIVE, org.lwjgl.openal.AL10.AL_FALSE);
            org.lwjgl.openal.AL10.alSource3f(
                    sourceId,
                    org.lwjgl.openal.AL10.AL_POSITION,
                    (float) this.pos.getX() + 0.5f,
                    (float) this.pos.getY() + 0.5f,
                    (float) this.pos.getZ() + 0.5f);

        } else if ("mid".equals(this.speakerType)) {
            effectiveRefDist = cfg.mid_refDist * arrayMultiplier;
            baseMaxDist = cfg.mid_baseMaxDist * arrayMultiplier;

            org.lwjgl.openal.AL10.alSourcei(
                    sourceId, org.lwjgl.openal.AL10.AL_SOURCE_RELATIVE, org.lwjgl.openal.AL10.AL_FALSE);
            org.lwjgl.openal.AL10.alSource3f(
                    sourceId,
                    org.lwjgl.openal.AL10.AL_POSITION,
                    (float) this.pos.getX() + 0.5f,
                    (float) this.pos.getY() + 0.5f,
                    (float) this.pos.getZ() + 0.5f);

        } else { // Line Array
            effectiveRefDist = cfg.line_refDist * arrayMultiplier;
            baseMaxDist = cfg.line_baseMaxDist * arrayMultiplier;

            org.lwjgl.openal.AL10.alSourcei(
                    sourceId, org.lwjgl.openal.AL10.AL_SOURCE_RELATIVE, org.lwjgl.openal.AL10.AL_FALSE);
            org.lwjgl.openal.AL10.alSource3f(
                    sourceId,
                    org.lwjgl.openal.AL10.AL_POSITION,
                    (float) this.pos.getX() + 0.5f,
                    (float) this.pos.getY() + 0.5f,
                    (float) this.pos.getZ() + 0.5f);
        }

        // --- SOURCE RADIUS (Width/Spread) ---
        // Converts the sound from an infinitely small "pinpoint" (sharp line) into a
        // physical sphere.
        // As you get close to or enter the sphere, the sound spreads naturally across
        // both ears (stereo width).
        // 0x1031 is AL_EXT_SOURCE_RADIUS.
        //
        // REDUCED: Previous minimum of 1.0 block caused excessive L/R spread,
        // making the speaker sound like it was coming from both sides even when
        // standing directly in front. Tighter values preserve HRTF localization.
        float sourceRadius;
        if ("sub".equals(this.speakerType)) {
            sourceRadius = Math.max(0.5f, (float) Math.sqrt(this.speakerCount) * cfg.sourceRadius_sub);
        } else if ("mid".equals(this.speakerType)) {
            sourceRadius = Math.max(0.3f, (float) Math.sqrt(this.speakerCount) * cfg.sourceRadius_mid);
        } else {
            sourceRadius = Math.max(0.15f, (float) Math.sqrt(this.speakerCount) * cfg.sourceRadius_line);
        }
        org.lwjgl.openal.AL10.alSourcef(sourceId, 0x1031, sourceRadius);

        // Scale Reference Distance with Power (higher power = larger full-volume zone)
        // This matches dynamicMaxDist scaling, keeping the attenuation curve shape
        // consistent.
        // Power 1.0 -> No change. Power 5.0 -> RefDist x2.24 (full volume extends
        // further)
        effectiveRefDist *= (float) Math.max(1.0, Math.sqrt(this.power));

        // Dynamic Max Distance based on Power (Sqrt scaling matches user perception)
        // Power 0.1 -> Sqrt(0.1) ~ 0.31 -> Range ~31% of base.
        // Power 1.0 -> Range 100% of base.
        // Power 10.0 -> Range ~316% of base.
        float dynamicMaxDist = baseMaxDist * (float) Math.max(0.2, Math.sqrt(this.power)); // Min 20% range

        // --- INVERSE-SQUARE HYBRID ATTENUATION ---
        // Per-speaker rolloff exponent (balanced for concert use):
        // Sub = 1.5 (bass carries further, but not overwhelming)
        // Mid = 1.8 (balanced mid-range rolloff)
        // Line = 2.0 (treble fades naturally, stays audible longer)
        double rolloffExponent = cfg.mid_rolloffExponent; // Mid default
        if ("sub".equals(this.speakerType)) rolloffExponent = cfg.sub_rolloffExponent;
        if ("line".equals(this.speakerType)) rolloffExponent = cfg.line_rolloffExponent;

        if (dist <= effectiveRefDist) {
            // Within Reference Distance — full volume
            attenuation = 1.0f;
        } else if (dist > dynamicMaxDist) {
            // Beyond max range — silent (soft cutoff already brought it to 0)
            attenuation = 0.0f;
        } else {
            // Inverse-Square (generalized): (refDist / dist) ^ exponent
            double invSq = Math.pow(effectiveRefDist / dist, rolloffExponent);

            // Soft Cutoff: Cosine fade-out in the last 20% of range
            // Prevents the jarring "pop" of a hard cutoff when crossing maxDist
            double fadeStart = dynamicMaxDist * cfg.fadeStartPercent;
            if (dist > fadeStart) {
                double fadeRatio = (dist - fadeStart) / (dynamicMaxDist - fadeStart);
                double fadeMult = 0.5 * (1.0 + Math.cos(fadeRatio * Math.PI));
                invSq *= fadeMult;
            }

            attenuation = (float) Math.max(0.0, Math.min(1.0, invSq));
        }

        // --- PROXIMITY BOOST (Gentle Near-Field Effect) ---
        // Real speakers have a "near-field" zone where sound is uniform and strong.
        // Reduced boost values for more natural feel:
        // Sub: +25% max (was +50%)
        // Others: +15% max (was variable)
        if (dist < effectiveRefDist) {
            float proxFactor = 1.0f - (float) (dist / effectiveRefDist);
            proxFactor = proxFactor * proxFactor; // Quadratic ease

            float maxBoost;
            if ("sub".equals(this.speakerType)) {
                maxBoost = cfg.prox_sub_maxBoost;
            } else {
                maxBoost = cfg.prox_other_maxBoost;
            }
            proximityBoost = 1.0f + proxFactor * maxBoost;
        }

        // --- FREQUENCY-DEPENDENT OCCLUSION BYPASS ---
        // Bass and low-mid frequencies diffract and penetrate walls much more
        // effectively than treble.
        float gainOcclusion = this.currentOcclusion;
        if ("sub".equals(this.speakerType)) {
            gainOcclusion = cfg.occ_sub_floor + (1.0f - cfg.occ_sub_floor) * this.currentOcclusion;
        } else if ("mid".equals(this.speakerType)) {
            gainOcclusion = cfg.occ_mid_floor + (1.0f - cfg.occ_mid_floor) * this.currentOcclusion;
        } else if ("line".equals(this.speakerType)) {
            gainOcclusion = cfg.occ_line_floor + (1.0f - cfg.occ_line_floor) * this.currentOcclusion;
        }

        // --- FINAL GAIN CALCULATION ---
        // Gain = Power * Attenuation * Directionality * Boost * MixerGain
        float dspGain = 1.0f;
        float mixerGain = this.session.getMixerGain(this.speakerType);
        // 1. Calculate Base Magnitude
        float targetGain = this.smoothedPower * attenuation * dirGain * proximityBoost * dspGain * mixerGain;

        // 2. Safety Clamps (Apply Before Occlusion!)
        if (targetGain > 4.0f) targetGain = 4.0f;
        if (targetGain < 0.0f) targetGain = 0.0f;

        // 3. APPLY OCCLUSION AS FINAL MULTIPLIER
        // Even if power=10 tries to push gain to 10.0, it gets clamped to 4.0 BEFORE
        // hitting the wall.
        // This guarantees high-power speakers are mathematically forced to become much
        // quieter.
        targetGain *= gainOcclusion;

        // Safety Clamps
        if (targetGain > 4.0f) targetGain = 4.0f;
        if (targetGain < 0.0f) targetGain = 0.0f;

        // Smoothing (Low factor to prevent zipper noise from discrete gain steps)
        // Accelerated from 0.12f to 0.40f: removes the 300ms sluggishness when walking
        // quickly across a speaker's frontal cone, ensuring snappy volume changes.
        float gainDelta = targetGain - this.smoothedGain;
        float gainLerp = cfg.gain_smoothing;
        float absGainDelta = Math.abs(gainDelta);
        if (absGainDelta > 0.60f) {
            gainLerp = 0.85f;
        } else if (absGainDelta > 0.25f) {
            gainLerp = 0.65f;
        }
        this.smoothedGain += gainDelta * gainLerp;

        // HARD ZERO: When the mixer pot is at 0, bypass smoothing and force true
        // silence.
        // Without this, the asymptotic lerp never reaches exactly 0.0 (e.g. 0.0000003)
        // which some audio hardware still renders as barely audible sound.
        if (mixerGain < 0.001f) {
            this.smoothedGain = 0.0f;
        }

        // Apply to OpenAL
        org.lwjgl.openal.AL10.alSourcef(sourceId, org.lwjgl.openal.AL10.AL_GAIN, this.smoothedGain);

        // Setup complete.

        // --- EFX SEND ---
        // Air Absorption HF (Smoothed Release)
        float gainHF = 1.0f;
        // Near-field: don't apply air absorption. It makes treble feel like it "opens
        // late"
        // when approaching the array.
        float nearFieldNoAbsorb = effectiveRefDist * 2.0f;
        float absorbStart = dynamicMaxDist * 0.65f; // Start absorbing later (was 40%)
        if (dist > absorbStart && dist > nearFieldNoAbsorb) {
            float fadeRatio = (dist - absorbStart) / (dynamicMaxDist - absorbStart);
            if (fadeRatio > 1.0f) fadeRatio = 1.0f;

            // Cosine Interpolation for Air Absorption (S-Curve)
            // Linear fade felt too "sharp" or sudden.
            // 0.0 (Start) -> 1.0 (End)
            float smoothRatio = (float) (0.5 * (1.0 - Math.cos(fadeRatio * Math.PI)));

            // Max absorption is reduced to avoid an exaggerated "muffled then suddenly
            // bright"
            // sensation while moving.
            gainHF = 1.0f - (smoothRatio * 0.60f);
        }

        // Safety Clamp
        if (gainHF < 0.20f) gainHF = 0.20f;

        // Common HF Occlusion (applied to direct and reverb sends)
        // Walls cut treble brutally. Using the raw currentOcclusion with a harsher
        // curve.
        // When approaching / de-occluding, a harsh exponent makes treble "lag" behind.
        // Use a more linear curve during de-occlusion and in the near-field so highs
        // open faster.
        float hfOccExp = cfg.occ_hfExp_occluding;
        if (targetOcclusion > this.currentOcclusion) {
            hfOccExp = cfg.occ_hfExp_deoccluding;
        }
        if (dist < effectiveRefDist * 1.5f) {
            hfOccExp = Math.min(hfOccExp, 1.05f);
        }
        // Floor of 2%: behind solid walls, treble is nearly gone (98% cut).
        // Previous 10% floor was too generous — sounded like the wall wasn't there.
        float rawOccHF = (float) Math.pow(this.currentOcclusion, hfOccExp);
        float occlusionHF = 0.02f + 0.98f * rawOccHF;
        // Underwater HF absorption: water kills treble dramatically
        float underwaterHF = AudioEngine.getInstance().getUnderwaterHFGain();

        // 1. Omni-directional physics: Trebles are highly directional.
        // If we are behind the speaker (directionalFocus is low), treble dies
        // instantly, while bass stays.
        float highFreqDirectionality = 1.0f;
        if ("sub".equals(this.speakerType)) {
            highFreqDirectionality = 0.05f; // Subs have no treble
        } else {
            // Smoothly interpolate the minimum treble floor based on frontness (derived
            // from hzDot)
            // frontness = 1.0 (perfect front), 0.5 (sides), 0.0 (perfect back)
            double frontness = (hzDot + 1.0) / 2.0;
            double baseBehindFloor;
            double baseFrontFloor;

            if ("line".equals(speakerType)) {
                baseBehindFloor = cfg.hf_line_behindFloor;
                baseFrontFloor = cfg.hf_line_frontFloor;
            } else if ("mid".equals(speakerType)) {
                baseBehindFloor = cfg.hf_mid_behindFloor;
                baseFrontFloor = cfg.hf_mid_frontFloor;
            } else { // normal
                baseBehindFloor = cfg.hf_normal_behindFloor;
                baseFrontFloor = cfg.hf_normal_frontFloor;
            }

            double floor = baseBehindFloor + (baseFrontFloor - baseBehindFloor) * frontness;
            double range = 1.0 - floor;
            highFreqDirectionality = (float) (floor + range * directionalFocus);
        }

        // 2. Air Absorption (Thickness/Frequency based on distance)
        // Mid/Bass carries far, but Treble dies continuously over distance
        float airAbsorbHF = 1.0f;
        if ("line".equals(this.speakerType)) {
            // Line array treble fades continuously over distance
            // Every 135 blocks (was 45), treble cuts in half (Less aggressive air
            // absorption)
            airAbsorbHF = (float) Math.pow(0.5, dist / cfg.hf_air_absorb_halving_dist);
        }

        float directGainHF = gainHF * occlusionHF * underwaterHF * highFreqDirectionality * airAbsorbHF;

        // Direct Filter application (Occlusion + HF + Underwater + Directionality)
        // Ensure this tracks perfectly with the main amplitude so treble doesn't lag
        // behind mids.
        // Synchronized smoothing rate: 0.40f (matches smoothedGain to prevent phase
        // shift).
        float rawDirectGain;
        if ("sub".equals(this.speakerType)) {
            // BUGFIX: Subwoofers are omnidirectional. Do not multiply by dirGain,
            // otherwise bass disappears when walking around the array.
            rawDirectGain = gainOcclusion * Math.min(proximityBoost, 4.0f);
        } else {
            rawDirectGain = gainOcclusion * Math.min(proximityBoost, 4.0f) * dirGain;
        }
        float directDelta = rawDirectGain - this.smoothedDirectGain;
        float directLerp = 0.40f;
        float absDirectDelta = Math.abs(directDelta);
        if (absDirectDelta > 0.60f) {
            directLerp = 0.85f;
        } else if (absDirectDelta > 0.25f) {
            directLerp = 0.65f;
        }
        this.smoothedDirectGain += directDelta * directLerp;
        // HARD ZERO: match the main gain bypass
        if (mixerGain < 0.001f) {
            this.smoothedDirectGain = 0.0f;
        }
        float directGain = this.smoothedDirectGain;

        // BUGFIX (Trebles turning back on at distance):
        // OpenAL EFX AL_LOWPASS_GAINHF is a linear gain multiplier for high
        // frequencies.
        // If HF gain > Overall gain, the filter acts as a High-Shelf Boost!
        // We must NEVER allow treble gain to exceed the overall bass gain.
        // Floor: allow treble to go very low behind walls (was 0.10, too generous)
        if (directGainHF < 0.01f) {
            directGainHF = 0.01f;
        }
        if (directGainHF > directGain) {
            directGainHF = directGain;
        }

        // Native subwoofer crossover filter
        if ("sub".equals(this.speakerType)) {
            directGainHF = Math.min(directGainHF, 0.05f);
        }

        float unmutedDirectGainHF = directGainHF; // Save HF before direct mute for Reverb Send

        // ═══════════════════════════════════════════════════════════════
        // REVERB HF SEND DECOUPLING
        // ═══════════════════════════════════════════════════════════════
        // The reverb's treble should NOT drop when the listener walks behind the speaker!
        // The speaker still shoots full treble into the venue, so the room's reverberant
        // field will contain that treble.
        float reverbSendHF = gainHF * occlusionHF * underwaterHF * airAbsorbHF;
        if (reverbSendHF < 0.01f) reverbSendHF = 0.01f;
        if (reverbSendHF > 1.0f) reverbSendHF = 1.0f;
        if ("sub".equals(this.speakerType)) {
            reverbSendHF = Math.min(reverbSendHF, 0.05f);
        }

        if (filterId != 0) {
            // Apply Mid (Direct Sound) Mute
            if (AudioEngine.getInstance().isMidMuted()) {
                directGain = 0.0f;
                directGainHF = 0.0f;
            }

            org.lwjgl.openal.EXTEfx.alFilterf(filterId, org.lwjgl.openal.EXTEfx.AL_LOWPASS_GAIN, directGain);
            org.lwjgl.openal.EXTEfx.alFilterf(filterId, org.lwjgl.openal.EXTEfx.AL_LOWPASS_GAINHF, directGainHF);
            org.lwjgl.openal.AL10.alSourcei(sourceId, org.lwjgl.openal.EXTEfx.AL_DIRECT_FILTER, filterId);
        }

        // Reverb Send (Room - Index 0)
        if (sendFilterId != 0 && AudioEngine.getInstance().getAuxSlotId() != 0) {
            float reverbOcclusion = Math.max(0.15f, this.currentOcclusion);

            // ═══════════════════════════════════════════════════════════════
            // PHYSICALLY ACCURATE VENUE REVERB MODEL
            // ═══════════════════════════════════════════════════════════════
            // Real acoustics: The reverberant field in a venue is roughly
            // CONSTANT throughout the enclosed space. It scales linearly with
            // the speaker's POWER.
            // As you move away from speakers:
            // - Direct sound drops (inverse-square)
            // - Reverb stays roughly the same
            // - Result: Wet/Dry ratio INCREASES with distance NATURALLY.
            // There is no need to artificially change the reverb send based on distance.

            // 1. Base Room Send (proportional to Speaker Power)
            // We use a combination of the old config values as a base volume multiplier.
            float baseReverbVolume = (cfg.reverb_send_near + cfg.reverb_send_far) * 0.5f;
            float powerScaledSend = baseReverbVolume * this.smoothedPower;

            // 2. SOFT distance falloff: Reverb shouldn't drop when walking around the room,
            // but it MUST fade out eventually when you walk extremely far away, otherwise
            // open-air venues will have infinite phantom reverb.
            float softDistanceFalloff = (float) Math.pow(Math.max(0.001f, attenuation), 0.15f); // Very gentle falloff

            // 3. Combine: occlusion × power scaled send × soft falloff
            float sendGain = reverbOcclusion * powerScaledSend * softDistanceFalloff;

            // 4. WET FLOOR: minimum reverb level inside the venue
            float wetFloor = 0.04f * reverbOcclusion;
            if (sendGain < wetFloor) sendGain = wetFloor;

            // 5. RELAXED CAP
            if (sendGain > 0.60f) sendGain = 0.60f;

            // Apply Side (Reverb) Mute
            if (AudioEngine.getInstance().isSideMuted()) {
                sendGain = 0.0f;
            }

            // NORMALIZE WET GAIN BY ARRAY SIZE:
            // 100 speakers sending audio to the same OpenAL effect slot will accumulate 100x
            // the energy in the effect's input buffer, causing clipping and a massive WET/DRY imbalance.
            // We divide by sqrt(clusterSize) to normalize the acoustic power for Reverb.
            sendGain /= (float) Math.max(1.0, Math.sqrt(this.clusterSize));

            // Apply filter for Room Send
            org.lwjgl.openal.EXTEfx.alFilterf(sendFilterId, org.lwjgl.openal.EXTEfx.AL_LOWPASS_GAIN, sendGain);
            org.lwjgl.openal.EXTEfx.alFilterf(sendFilterId, org.lwjgl.openal.EXTEfx.AL_LOWPASS_GAINHF, reverbSendHF);

            // Send 0: Room Reverb
            org.lwjgl.openal.AL11.alSource3i(
                    sourceId,
                    org.lwjgl.openal.EXTEfx.AL_AUXILIARY_SEND_FILTER,
                    AudioEngine.getInstance().getAuxSlotId(),
                    0,
                    sendFilterId);

            // Send 1: Slapback Echo (uses its own dedicated filter to avoid
            // gain interference with the reverb send filter)
            if (AudioEngine.getInstance().getSlapbackAuxSlotId() != 0 && echoSendFilterId != 0) {
                // Echo send uses same occlusion but independent gain path.
                // It multiplies the base distance/occlusion send gain by the listener's
                // dynamic wall-proximity echo gain.
                float echoSendGain = sendGain * AudioEngine.getInstance().getSlapbackGain();

                // NORMALIZE WET GAIN BY ARRAY SIZE:
                // 100 speakers sending audio to the same OpenAL effect slot will accumulate 100x
                // the energy in the effect's input buffer, causing clipping and a massive WET/DRY imbalance.
                // We divide by sqrt(clusterSize) to normalize the acoustic power.
                echoSendGain /= (float) Math.max(1.0, Math.sqrt(this.clusterSize));
                org.lwjgl.openal.EXTEfx.alFilterf(
                        echoSendFilterId, org.lwjgl.openal.EXTEfx.AL_LOWPASS_GAIN, echoSendGain);
                org.lwjgl.openal.EXTEfx.alFilterf(
                        echoSendFilterId, org.lwjgl.openal.EXTEfx.AL_LOWPASS_GAINHF, reverbSendHF);
                org.lwjgl.openal.AL11.alSource3i(
                        sourceId,
                        org.lwjgl.openal.EXTEfx.AL_AUXILIARY_SEND_FILTER,
                        AudioEngine.getInstance().getSlapbackAuxSlotId(),
                        1,
                        echoSendFilterId);
            }
        }
    }

    public net.minecraft.util.math.BlockPos getPos() {
        return pos;
    }

    /** Returns the shared AudioStreamBuffer for this source. */
    public AudioStreamBuffer getStreamBuffer() {
        return streamBuffer;
    }

    /** Returns the current output cursor position (sample index). */
    public double getOutputCursor() {
        return outputCursor;
    }

    // Delay State — single authoritative source across both thread paths
    private double lastRenderedDelaySamples = -1.0;

    /**
     * ═══════════════════════════════════════════════════════════════════════
     * GLOBAL MASTER CLOCK — AUDIO-THREAD BUFFER FEEDING
     * ═══════════════════════════════════════════════════════════════════════
     * Called from the BACKGROUND AUDIO THREAD (not the main/render thread).
     * Performs the full OpenAL buffer lifecycle using the GLOBAL sample time:
     * 1. Update distance from listener (200Hz, FPS-independent)
     * 2. Unqueue processed (empty) buffers from the sound card
     * 3. Generate NEW PCM inline using: readPos = globalSampleTime -
     * propagationDelay
     * 4. Queue them back to keep the sound card fed
     * 5. Signal atomic restart if underrun occurred
     *
     * NO precomputed queue. NO local samplesWritten counter.
     * ALL sources use the SAME globalSampleTime → zero drift, guaranteed.
     *
     * @param globalSampleTime Current wall-clock position in samples (shared by ALL
     *                         sources)
     * @param listenerPos      Current smoothed listener position for distance
     *                         calculation
     * @return true if this source needs an atomic restart (underrun recovery)
     */
    public synchronized boolean feedOpenALFromAudioThread(double globalSampleTime, Vec3d listenerPos) {
        if (!isValid) return false;

        // ═══════════════════════════════════════════════════════════════
        // DISTANCE CALCULATION: Done on audio thread at 200Hz.
        // Completely independent of Minecraft's FPS or tick rate.
        // ═══════════════════════════════════════════════════════════════
        if (listenerPos != null) {
            double spkX = pos.getX() + 0.5;
            double spkY = pos.getY() + 0.5;
            double spkZ = pos.getZ() + 0.5;
            double dx = spkX - listenerPos.x;
            // Y eksenindeki fiziksel yüksekliği daraltır. (Gecikme, Doppler ve Mesafe
            // zayıflaması için)
            double dy = (spkY - listenerPos.y) * com.audiophilecraft.config.LiveTuningConfig.get().physics_yFlatten;
            double dz = spkZ - listenerPos.z;
            float ownDistance = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
            this.currentDistanceSnapshot = ownDistance;

            // CLUSTER DELAY SYNC (audio thread owned — no race condition)
            // Followers use leader's distance for propagation delay.
            // Leaders in the for-loop are always processed BEFORE followers
            // (insertion order), so leader's currentDistanceSnapshot is fresh.

            if (!isLeader && clusterLeader != null && clusterLeader.isValid) {

                this.delayDistanceSnapshot = clusterLeader.currentDistanceSnapshot;

            } else {

                this.delayDistanceSnapshot = ownDistance;
            }
        }

        // 1. Check how many buffers the sound card has finished playing
        int processed = org.lwjgl.openal.AL10.alGetSourcei(sourceId, org.lwjgl.openal.AL10.AL_BUFFERS_PROCESSED);

        while (processed > 0) {
            // 2. Unqueue the empty buffer
            int bufferId = org.lwjgl.openal.AL10.alSourceUnqueueBuffers(sourceId);

            if (!isFinished) {
                // 3. Use outputCursor for sequential buffer continuity.
                // This ensures zero gaps/overlaps between consecutive buffers.
                double bufferStartSample = this.outputCursor;

                // RING BUFFER GUARD: Ensure decoded data is available
                long readEnd = (long) (bufferStartSample + STREAM_BUFFER_SIZE);
                if (readEnd <= streamBuffer.getWriteCursor()) {
                    generatePcmBlock(audioThreadRawAudio, bufferStartSample);
                    audioThreadPcmBuffer.clear();
                    audioThreadPcmBuffer.put(audioThreadRawAudio);
                    audioThreadPcmBuffer.flip();
                    alBufferData(bufferId, AL_FORMAT_MONO16, audioThreadPcmBuffer, (int) streamBuffer.sampleRate);
                    org.lwjgl.openal.AL10.alSourceQueueBuffers(sourceId, bufferId);
                    this.outputCursor += STREAM_BUFFER_SIZE;
                } else {
                    // Ring buffer not ready — generate silence to keep pipeline alive
                    java.util.Arrays.fill(audioThreadRawAudio, (short) 0);
                    audioThreadPcmBuffer.clear();
                    audioThreadPcmBuffer.put(audioThreadRawAudio);
                    audioThreadPcmBuffer.flip();
                    alBufferData(bufferId, AL_FORMAT_MONO16, audioThreadPcmBuffer, (int) streamBuffer.sampleRate);
                    org.lwjgl.openal.AL10.alSourceQueueBuffers(sourceId, bufferId);
                    this.outputCursor += STREAM_BUFFER_SIZE;
                }
            }
            // else: EOF reached — don't queue anything, let OpenAL drain naturally.
            processed--;
        }

        // 5. Underrun recovery
        int state = org.lwjgl.openal.AL10.alGetSourcei(sourceId, org.lwjgl.openal.AL10.AL_SOURCE_STATE);
        if (state == org.lwjgl.openal.AL10.AL_STOPPED && !isFinished) {
            int queued = org.lwjgl.openal.AL10.alGetSourcei(sourceId, org.lwjgl.openal.AL10.AL_BUFFERS_QUEUED);
            if (queued > 0) {
                // Snap outputCursor to global clock on underrun recovery
                this.outputCursor = globalSampleTime + ((double) queued * STREAM_BUFFER_SIZE);
                return true; // SIGNAL FOR ATOMIC RESTART
            }
        }

        return false;
    }

    /**
     * ═══════════════════════════════════════════════════════════════════════
     * STATELESS PCM GENERATION (GLOBAL MASTER CLOCK)
     * ═══════════════════════════════════════════════════════════════════════
     * Generates one buffer of PCM audio for an absolute sample position.
     * Read position is derived from the global timeline minus propagation delay:
     * readPos = bufferStartSample + i - propagationDelaySamples
     *
     * This method is STATELESS with respect to timeline — it can generate
     * audio for ANY absolute sample position. The only state it carries is
     * the delay smoother (lastRenderedDelaySamples) for Doppler-free transitions.
     *
     * @param output            Pre-allocated array of size STREAM_BUFFER_SIZE to
     *                          fill
     * @param bufferStartSample Absolute sample position for the start of this
     *                          buffer
     * @return true if PCM was generated, false if sampleRate is invalid
     */
    private synchronized boolean generatePcmBlock(short[] output, double bufferStartSample) {
        double sampleRate = streamBuffer.sampleRate;
        if (sampleRate <= 0) return false;

        double speedOfSound = com.audiophilecraft.config.LiveTuningConfig.get().speedOfSound;
        double targetDelaySeconds = (delayDistanceSnapshot / speedOfSound) + (this.sampleShiftMs / 1000.0);
        double targetDelaySamples = targetDelaySeconds * sampleRate;

        if (lastRenderedDelaySamples < 0) {
            lastRenderedDelaySamples = targetDelaySamples;
        }

        boolean finished = false;

        // ═══════════════════════════════════════════════════════════════
        // ALWAYS SMOOTH: Slew-Limited Exponential Moving Average (EMA)
        // Decoupled from buffer boundaries, completely eliminating
        // "staircase" pitch-crackling when flying quickly.
        // ═══════════════════════════════════════════════════════════════
        double currentDelay = lastRenderedDelaySamples;
        double endDelay = targetDelaySamples;

        // 1.5% max pitch shift limit (Doppler shift clamp).
        // Prevents sound from playing backwards if teleporting.
        double maxDeltaPerSample = 0.015;

        for (int i = 0; i < STREAM_BUFFER_SIZE; i++) {
            double delta = endDelay - currentDelay;
            double step = delta * 0.001; // ~50ms smooth approach curve

            if (step > maxDeltaPerSample) step = maxDeltaPerSample;
            if (step < -maxDeltaPerSample) step = -maxDeltaPerSample;

            currentDelay += step;

            // GLOBAL MASTER CLOCK: absolute position minus propagation delay
            double readPos = (bufferStartSample + i) - currentDelay;

            if (readPos >= streamBuffer.getTotalSamples()) {
                finished = true;
                output[i] = 0;
            } else if (readPos < 0) {
                output[i] = 0;
            } else {
                output[i] = streamBuffer.getSampleLagrange(readPos);
            }
        }

        lastRenderedDelaySamples = currentDelay;

        // --- CUSHION FADE: Prevent arbitrary waveform snap Pops via 50ms lerp ---
        if (seekFadeSamplesRemaining > 0) {
            double totalFadeSamples = 0.05 * streamBuffer.sampleRate;
            for (int i = 0; i < STREAM_BUFFER_SIZE; i++) {
                if (seekFadeSamplesRemaining > 0) {
                    double fade = 1.0 - ((double) seekFadeSamplesRemaining / totalFadeSamples);
                    // Fast sine ease-in for smoothest acoustical transition
                    fade = Math.sin(fade * Math.PI / 2.0);
                    output[i] = (short) (output[i] * fade);
                    seekFadeSamplesRemaining--;
                }
            }
        }

        // ═══════════════════════════════════════════════════════════════
        // DSP STAGE (shared by all branches)
        // ═══════════════════════════════════════════════════════════════
        // DSP STAGE (shared by all branches)
        // DSP pipeline
        this.dspPipeline.process(output, (float) streamBuffer.sampleRate, this.smoothedInputGain, this.smoothedPower);
        if (finished) {
            isFinished = true;
        }

        return true;
    }

    public void pause() {
        org.lwjgl.openal.AL10.alSourcePause(sourceId);
    }

    public void resume() {
        if (isValid) {
            org.lwjgl.openal.AL10.alSourcePlay(sourceId);
        }
    }

    /**
     * Safely releases Java-side native memory (MemoryUtil) without calling OpenAL
     * functions.
     * Used when the OpenAL context is destroyed and old IDs are invalid.
     */
    public synchronized void releaseNativeMemory() {
        // Free reusable native buffer
        if (reusablePcmBuffer != null) {
            try {
                MemoryUtil.memFree(reusablePcmBuffer);
            } catch (Exception e) {
                /* ignore */ }
            reusablePcmBuffer = null;
        }

        // Free audio-thread native buffer
        if (audioThreadPcmBuffer != null) {
            try {
                MemoryUtil.memFree(audioThreadPcmBuffer);
            } catch (Exception e) {
                /* ignore */ }
            audioThreadPcmBuffer = null;
        }

        isValid = false;
        isFinished = true;
    }

    public synchronized void cleanup() {
        if (!isValid) return; // Prevent double cleanup

        alSourceStop(sourceId);

        releaseNativeMemory();

        // Safely unqueue all processed/queued streaming buffers before detaching

        int queued = org.lwjgl.openal.AL10.alGetSourcei(sourceId, org.lwjgl.openal.AL10.AL_BUFFERS_QUEUED);

        while (queued > 0) {

            org.lwjgl.openal.AL10.alSourceUnqueueBuffers(sourceId);

            queued--;
        }

        org.lwjgl.openal.AL10.alSourcei(sourceId, org.lwjgl.openal.AL10.AL_BUFFER, 0);

        // Detach filters/sends before deletion
        try {
            org.lwjgl.openal.AL10.alSourcei(
                    sourceId, org.lwjgl.openal.EXTEfx.AL_DIRECT_FILTER, org.lwjgl.openal.EXTEfx.AL_FILTER_NULL);
            org.lwjgl.openal.AL11.alSource3i(
                    sourceId,
                    org.lwjgl.openal.EXTEfx.AL_AUXILIARY_SEND_FILTER,
                    0,
                    0,
                    org.lwjgl.openal.EXTEfx.AL_FILTER_NULL);
            org.lwjgl.openal.AL11.alSource3i(
                    sourceId,
                    org.lwjgl.openal.EXTEfx.AL_AUXILIARY_SEND_FILTER,
                    0,
                    1,
                    org.lwjgl.openal.EXTEfx.AL_FILTER_NULL);
        } catch (Exception e) {
            System.err.println("StreamSource: Failed to detach filters/sends: " + e.getMessage());
        }

        // Delete the source FIRST. Once the source is deleted, no references to the
        // buffers can exist.
        alDeleteSources(sourceId);

        // Now safe to delete the buffers and filters
        alDeleteBuffers(buffers);
        try {
            if (filterId != 0) org.lwjgl.openal.EXTEfx.alDeleteFilters(filterId);
            if (sendFilterId != 0) org.lwjgl.openal.EXTEfx.alDeleteFilters(sendFilterId);
            if (echoSendFilterId != 0) org.lwjgl.openal.EXTEfx.alDeleteFilters(echoSendFilterId);
        } catch (Exception e) {
            System.err.println("StreamSource: Failed to delete filters: " + e.getMessage());
        }

        // Drain any OpenAL errors to prevent error queue buildup
        while (alGetError() != AL_NO_ERROR) {
            /* drain */ }
    }
}
