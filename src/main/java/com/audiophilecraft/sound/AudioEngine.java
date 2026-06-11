package com.audiophilecraft.sound;

import static org.lwjgl.openal.AL10.*;
import static org.lwjgl.openal.AL11.*;
import static org.lwjgl.openal.EXTEfx.*;

import java.nio.ShortBuffer;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import net.minecraft.block.BlockState;
import net.minecraft.client.MinecraftClient;
import net.minecraft.state.property.Properties;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.math.Vec3i;
import net.minecraft.world.World;
import org.lwjgl.openal.ALC10;
import org.lwjgl.openal.ALCCapabilities;
import org.lwjgl.openal.SOFTHRTF;
import org.lwjgl.system.MemoryUtil;

public class AudioEngine {
    private static AudioEngine INSTANCE;

    // Active playback session
    private final java.util.Map<java.util.UUID, PlaybackSession> sessions =
            new java.util.concurrent.ConcurrentHashMap<>();
    private java.util.UUID activeSessionId = null;

    // Listener state (delegated to ListenerController)
    private final ListenerController listener = ListenerController.getInstance();
    // (kept for gradual migration)
    private volatile Vec3d listenerPos = Vec3d.ZERO;
    private volatile Vec3d smoothedListenerPos = Vec3d.ZERO;
    private float listenerYaw = 0;

    private float listenerPitch = 0;

    // Global Pause State

    // Seek Atomicity Guard â€” prevents audio thread from feeding sources mid-seek
    // getActiveSession().isSeeking() in PlaybackSession

    // Track Generation â€” increments on each playTrack(), used to discard stale
    // venue scan callbacks
    private volatile int trackGeneration = 0;

    // Underwater State (for global HF filtering)
    private boolean isUnderwater = false;
    private float smoothedUnderwaterHF = 1.0f; // 1.0 = normal, 0.08 = deep underwater

    // --- Master Reverb Occlusion ---
    private float smoothedMasterOcclusion = 1.0f;

    private final OpenALContextManager openAL = OpenALContextManager.getInstance();
    // EFX EAX Reverb System
    private int reverbEffectId = 0;
    private int auxSlotId = 0;

    private boolean efxInitialized = false;

    // Acoustic Scanner (used only for venue probe scans at playback start)
    private final AdvancedAcousticScanner acousticScanner = new AdvancedAcousticScanner();

    // Streaming System

    // Time Tracking
    // read by audio thread
    private static final double BUFFER_LOOKAHEAD = 0.5; // Low-latency pipeline: 6 initial + 3 precomputed buffers Ã—
    // 1024 = 9216 samples (~0.19s) PLUS delay headroom

    // Background Audio Thread (pre-computes PCM buffers off main thread)
    private ScheduledExecutorService audioThread;

    // Venue-Locked Reverb System
    private AdvancedAcousticScanner.VenuePreset venuePreset = null;
    private boolean venuePresetApplied = false;

    // --- Dynamic Early Reflections ---
    private volatile float currentReflGain = -1.0f;
    private volatile float currentReflDelay = -1.0f;
    // Live Tuning: stored descriptor for regenerating preset when config changes
    private AdvancedAcousticScanner.VenueDescriptor storedVenueDescriptor = null;
    private net.minecraft.util.math.Vec3d storedVenueProbePos = null;
    private long lastConfigGeneration = 0;

    // Direct buffer allocation caching (Prevents native memory JVM GC thrashing in
    // hot loop)
    private final java.nio.IntBuffer reusableRestartBuffer = org.lwjgl.BufferUtils.createIntBuffer(1024);

    private AudioEngine() {
        // Private constructor for singleton
    }

    private static final java.util.UUID DEFAULT_SID = java.util.UUID.fromString("00000000-0000-0000-0000-000000000001");

    public PlaybackSession getActiveSession() {
        if (activeSessionId == null) activeSessionId = DEFAULT_SID;
        return sessions.computeIfAbsent(activeSessionId, k -> new PlaybackSession(this));
    }

    public void ensureActiveSession(java.util.UUID id) {
        activeSessionId = id;
        sessions.computeIfAbsent(id, k -> new PlaybackSession(this));
    }

    public static synchronized AudioEngine getInstance() {
        if (INSTANCE == null) {
            INSTANCE = new AudioEngine();
        }
        return INSTANCE;
    }

    /**
     * Enable HRTF (Head-Related Transfer Function) for binaural 3D audio,
     * AND simultaneously request an expanded source pool (256 mono sources).
     *
     * OpenAL Soft's default source limit (typically 32) is far too low for
     * large PA systems with 4+ speaker clusters. Without raising this limit,
     * alGenSources() returns AL_INVALID_OPERATION after the first ~20 sources,
     * causing the outermost speaker clusters to be silently skipped.
     *
     * alcResetDeviceSOFT is the only way to change ALC attributes (like
     * ALC_MONO_SOURCES) on an existing context without destroying it.
     * We piggyback this request onto the HRTF reset so it happens in one call.
     */
    private void enableHrtf() {
        try {
            long context = ALC10.alcGetCurrentContext();
            if (context == 0L) {
                System.err.println("[enableHrtf] No current context, aborting.");
                return;
            }
            long device = ALC10.alcGetContextsDevice(context);
            if (device == 0L) {
                System.err.println("[enableHrtf] No device for context, aborting.");
                return;
            }

            // LOG: Current state before reset
            int preResetSources = ALC10.alcGetInteger(device, org.lwjgl.openal.ALC11.ALC_MONO_SOURCES);
            int preResetStereo = ALC10.alcGetInteger(device, org.lwjgl.openal.ALC11.ALC_STEREO_SOURCES);
            int preResetError = ALC10.alcGetError(device);
            System.out.println("[enableHrtf] PRE-RESET: monoSources=" + preResetSources + " stereoSources="
                    + preResetStereo + " alcError=0x" + Integer.toHexString(preResetError));

            ALCCapabilities alcCaps = org.lwjgl.openal.ALC.createCapabilities(device);
            System.out.println("[enableHrtf] ALC_SOFT_HRTF=" + alcCaps.ALC_SOFT_HRTF);

            if (alcCaps.ALC_SOFT_HRTF) {
                int numHrtf = ALC10.alcGetInteger(device, SOFTHRTF.ALC_NUM_HRTF_SPECIFIERS_SOFT);
                System.out.println("[enableHrtf] HRTF profiles found: " + numHrtf);

                if (numHrtf > 0) {
                    int[] attrs = {
                        SOFTHRTF.ALC_HRTF_SOFT, ALC10.ALC_TRUE, org.lwjgl.openal.ALC11.ALC_MONO_SOURCES, 1024, 0
                    };
                    System.out.println("[enableHrtf] Calling alcResetDeviceSOFT(HRTF=TRUE, MONO=1024)...");
                    boolean success = SOFTHRTF.alcResetDeviceSOFT(device, attrs);
                    int postResetError = ALC10.alcGetError(device);
                    System.out.println("[enableHrtf] alcResetDeviceSOFT returned: " + success + " alcError=0x"
                            + Integer.toHexString(postResetError));

                    if (success) {
                        int hrtfStatus = ALC10.alcGetInteger(device, SOFTHRTF.ALC_HRTF_STATUS_SOFT);
                        int actualSources = ALC10.alcGetInteger(device, org.lwjgl.openal.ALC11.ALC_MONO_SOURCES);
                        int actualStereo = ALC10.alcGetInteger(device, org.lwjgl.openal.ALC11.ALC_STEREO_SOURCES);
                        int postAlError = org.lwjgl.openal.AL10.alGetError();
                        System.out.println("[enableHrtf] POST-RESET: hrtfStatus=" + hrtfStatus
                                + " actualMonoSources=" + actualSources + " actualStereoSources=" + actualStereo
                                + " alError=0x" + Integer.toHexString(postAlError));
                    } else {
                        System.err.println("[enableHrtf] alcResetDeviceSOFT FAILED! alcError=0x"
                                + Integer.toHexString(postResetError));
                        System.err.println("[enableHrtf] Trying sources-only fallback...");
                        int[] attrsNoHrtf = {org.lwjgl.openal.ALC11.ALC_MONO_SOURCES, 1024, 0};
                        boolean fallback = SOFTHRTF.alcResetDeviceSOFT(device, attrsNoHrtf);
                        int fallbackError = ALC10.alcGetError(device);
                        System.out.println("[enableHrtf] Fallback reset returned: " + fallback + " alcError=0x"
                                + Integer.toHexString(fallbackError));
                    }
                } else {
                    System.out.println("[enableHrtf] No HRTF profiles, trying sources-only...");
                    int[] attrs = {org.lwjgl.openal.ALC11.ALC_MONO_SOURCES, 1024, 0};
                    boolean success = SOFTHRTF.alcResetDeviceSOFT(device, attrs);
                    int postError = ALC10.alcGetError(device);
                    System.out.println("[enableHrtf] Sources-only reset returned: " + success + " alcError=0x"
                            + Integer.toHexString(postError));
                }
            } else {
                System.out.println("[enableHrtf] ALC_SOFT_HRTF not available, skipping.");
            }
        } catch (Exception e) {
            System.err.println("[enableHrtf] EXCEPTION: " + e.getMessage());
            e.printStackTrace();
        }
    }

    public int getAuxSlotId() {
        return auxSlotId;
    }

    /**
     * Initialize OpenAL EFX with EAX Reverb for physics-based room simulation.
     * Called lazily on first playTrack().
     */
    public void initEfx() {
        if (efxInitialized) return;
        efxInitialized = true;

        // Enable HRTF and expanded source pool for all users
        enableHrtf();

        // HRTF and source pool expansion are now handled via alsoft.ini
        // (placed in %AppData%/alsoft.ini). This avoids alcResetDeviceSOFT
        // which was disrupting Minecraft's sound engine.

        try {
            // Create EAX Reverb Effect (superior to basic AL_EFFECT_REVERB)
            reverbEffectId = alGenEffects();

            if (alGetError() != AL_NO_ERROR) {
                System.err.println("AudioEngine: Failed to create EFX effect");
                reverbEffectId = 0;
                return;
            }

            // Try EAX Reverb first, fall back to basic reverb
            alEffecti(reverbEffectId, AL_EFFECT_TYPE, AL_EFFECT_EAXREVERB);

            if (alGetError() != AL_NO_ERROR) {
                alEffecti(reverbEffectId, AL_EFFECT_TYPE, AL_EFFECT_REVERB);
                if (alGetError() != AL_NO_ERROR) {
                    System.err.println("AudioEngine: No reverb support available");
                    alDeleteEffects(reverbEffectId);
                    reverbEffectId = 0;
                    return;
                }
            }

            // --- CRITICAL DISTANCE MODEL SETTINGS ---
            // Current Issue: Sound stops attenuating at MaxDist due to CLAMPED model.
            // Fix: Use AL_INVERSE_DISTANCE (Standard) so sound fades naturally forever.
            alDistanceModel(AL_NONE);

            // Rolloff Factor Default
            // alListenerf(AL_ROLLOFF_FACTOR, 1.0f); // Per-source is better

            // 1. Primary Reverb (Dynamic - Updated by Scanner)
            alEffectf(reverbEffectId, AL_EAXREVERB_DECAY_TIME, 0.3f);
            alEffectf(reverbEffectId, AL_EAXREVERB_REFLECTIONS_GAIN, 0.3f);
            alEffectf(reverbEffectId, AL_EAXREVERB_REFLECTIONS_DELAY, 0.02f);
            alEffectf(reverbEffectId, AL_EAXREVERB_LATE_REVERB_GAIN, 0.1f);
            alEffectf(reverbEffectId, AL_EAXREVERB_LATE_REVERB_DELAY, 0.04f);
            alEffectf(reverbEffectId, AL_EAXREVERB_DIFFUSION, 0.7f);
            alEffectf(reverbEffectId, AL_EAXREVERB_DENSITY, 0.5f);
            alEffectf(reverbEffectId, AL_EAXREVERB_GAIN, 0.3f);
            alEffectf(reverbEffectId, AL_EAXREVERB_GAINHF, 0.6f);
            alEffectf(reverbEffectId, AL_EAXREVERB_GAINLF, 0.8f);
            alEffectf(reverbEffectId, AL_EAXREVERB_DECAY_HFRATIO, 0.5f);
            alEffectf(reverbEffectId, AL_EAXREVERB_DECAY_LFRATIO, 1.1f);
            alEffectf(reverbEffectId, AL_EAXREVERB_AIR_ABSORPTION_GAINHF, 0.994f);
            alEffecti(reverbEffectId, AL_EAXREVERB_DECAY_HFLIMIT, 1);

            // Create Auxiliary Effect Slots
            auxSlotId = alGenAuxiliaryEffectSlots();

            if (alGetError() != AL_NO_ERROR) {
                System.err.println("AudioEngine: Failed to create aux slot");
                alDeleteEffects(reverbEffectId);
                reverbEffectId = 0;
                return;
            }

            // Attach effect to slot
            alAuxiliaryEffectSloti(auxSlotId, AL_EFFECTSLOT_EFFECT, reverbEffectId);

        } catch (Exception e) {
            System.err.println("AudioEngine: EFX init failed: " + e.getMessage());
            reverbEffectId = 0;
            auxSlotId = 0;
        }
    }

    public AdvancedAcousticScanner.VenuePreset getVenuePreset() {
        return this.venuePreset;
    }

    public AdvancedAcousticScanner.VenueDescriptor getStoredVenueDescriptor() {
        return storedVenueDescriptor;
    }

    /**
     * Apply venue reverb to EFX every tick.
     * Re-applies each tick so LiveTuningConfig multipliers take effect in
     * real-time.
     * Also regenerates VenuePreset from stored descriptor when config changes.
     */
    private void ensureVenueReverb() {
        if (venuePreset == null) return;
        if (auxSlotId == 0 || reverbEffectId == 0) return;

        // If config was reloaded, regenerate the VenuePreset from stored descriptor
        long currentGen = com.audiophilecraft.config.LiveTuningConfig.getReloadGeneration();
        if (currentGen != lastConfigGeneration && storedVenueDescriptor != null && storedVenueProbePos != null) {
            this.venuePreset = acousticScanner.descriptorToPreset(storedVenueDescriptor, storedVenueProbePos);
            lastConfigGeneration = currentGen;
        }

        applyVenueReverbToEfx();
        venuePresetApplied = true;
    }

    /**
     * Apply the locked venue reverb preset to the EFX effect.
     * Parameters come from the one-time probe scan and never change.
     */
    private void applyVenueReverbToEfx() {
        com.audiophilecraft.config.LiveTuningConfig cfg = com.audiophilecraft.config.LiveTuningConfig.get();

        float decayTime = venuePreset.decayTime * cfg.reverb_decayMultiplier;
        float gain = venuePreset.gain * cfg.reverb_gainMultiplier;
        float gainHF = venuePreset.gainHF * cfg.reverb_gainHFMultiplier;
        float reflGain = venuePreset.reflectionsGain * cfg.reverb_reflGainMultiplier;
        float lateGain = venuePreset.lateReverbGain * cfg.reverb_lateGainMultiplier;
        float density = cfg.reverb_densityOverride >= 0 ? cfg.reverb_densityOverride : venuePreset.density;
        float diffusion = cfg.reverb_diffusionOverride >= 0 ? cfg.reverb_diffusionOverride : venuePreset.diffusion;

        // Clamp to OpenAL EAX Reverb limits
        decayTime = Math.max(0.1f, Math.min(20.0f, decayTime));
        gain = Math.max(0.0f, Math.min(1.0f, gain));
        gainHF = Math.max(0.0f, Math.min(1.0f, gainHF));
        reflGain = Math.max(0.0f, Math.min(3.16f, reflGain));
        lateGain = Math.max(0.0f, Math.min(10.0f, lateGain));
        density = Math.max(0.0f, Math.min(1.0f, density));
        diffusion = Math.max(0.0f, Math.min(1.0f, diffusion));

        alEffectf(reverbEffectId, AL_EAXREVERB_DECAY_TIME, decayTime);
        alEffectf(reverbEffectId, AL_EAXREVERB_DECAY_HFRATIO, venuePreset.decayHFRatio);
        alEffectf(reverbEffectId, AL_EAXREVERB_DECAY_LFRATIO, venuePreset.decayLFRatio);
        alEffecti(reverbEffectId, AL_EAXREVERB_DECAY_HFLIMIT, venuePreset.decayHFLimit ? 1 : 0);

        // Thread-safe application of dynamic early reflections (calculated in tick
        // thread)
        if (currentReflGain >= 0.0f && currentReflDelay >= 0.0f) {
            alEffectf(reverbEffectId, AL_EAXREVERB_REFLECTIONS_GAIN, currentReflGain);
            alEffectf(reverbEffectId, AL_EAXREVERB_REFLECTIONS_DELAY, currentReflDelay);
        }

        alEffectfv(reverbEffectId, AL_EAXREVERB_REFLECTIONS_PAN, new float[] {0, 0, 0});
        alEffectf(reverbEffectId, AL_EAXREVERB_LATE_REVERB_GAIN, lateGain);
        alEffectf(reverbEffectId, AL_EAXREVERB_LATE_REVERB_DELAY, venuePreset.lateReverbDelay);
        alEffectfv(reverbEffectId, AL_EAXREVERB_LATE_REVERB_PAN, new float[] {0, 0, 0});
        alEffectf(reverbEffectId, AL_EAXREVERB_DENSITY, density);
        alEffectf(reverbEffectId, AL_EAXREVERB_DIFFUSION, diffusion);
        alEffectf(reverbEffectId, AL_EAXREVERB_GAIN, gain);
        alEffectf(reverbEffectId, AL_EAXREVERB_GAINHF, gainHF);
        alEffectf(reverbEffectId, AL_EAXREVERB_GAINLF, venuePreset.gainLF);
        alEffectf(reverbEffectId, AL_EAXREVERB_AIR_ABSORPTION_GAINHF, venuePreset.airAbsorptionGainHF);
        alAuxiliaryEffectSloti(auxSlotId, AL_EFFECTSLOT_EFFECT, reverbEffectId);
    }

    /**
     * Listener-centric Early Reflections scanner.
     * Fires 6 rays from the listener position to find immediate wall proximity.
     * Updates AL_EAXREVERB_REFLECTIONS_GAIN and AL_EAXREVERB_REFLECTIONS_DELAY
     * live.
     */
    private void updateListenerReflections(World world) {
        if (this.venuePreset == null || reverbEffectId == 0 || auxSlotId == 0) return;

        com.audiophilecraft.config.LiveTuningConfig cfg = com.audiophilecraft.config.LiveTuningConfig.get();

        float[][] DIRS = {
            {1, 0, 0}, {-1, 0, 0},
            {0, 1, 0}, {0, -1, 0},
            {0, 0, 1}, {0, 0, -1}
        };

        int maxDist = 10; // 10 blocks max for early reflections radius
        float minDist = maxDist;

        for (int i = 0; i < 6; i++) {
            float dirX = DIRS[i][0];
            float dirY = DIRS[i][1];
            float dirZ = DIRS[i][2];
            float hitDist = maxDist;

            net.minecraft.util.math.BlockPos.Mutable checkPos = new net.minecraft.util.math.BlockPos.Mutable();
            for (int step = 1; step <= maxDist; step++) {
                checkPos.set(
                        (int) Math.floor(listenerPos.x + dirX * step),
                        (int) Math.floor(listenerPos.y + dirY * step),
                        (int) Math.floor(listenerPos.z + dirZ * step));

                net.minecraft.block.BlockState state = world.getBlockState(checkPos);
                if (state.isSolidBlock(world, checkPos)) {
                    hitDist = step;
                    break;
                }
            }
            if (hitDist < minDist) {
                minDist = hitDist;
            }
        }

        // 1. Dynamic Delay based on NEAREST wall
        // Reverted to 2000.0f divisor. Long delays ruin the "fullness" because EAX
        // reflections
        // are diffuse clusters, not discrete echoes. They must arrive <30ms to fuse and
        // thicken the sound.
        float dynamicReflDelay = Math.max(0.001f, Math.min(minDist * 2.0f / 2000.0f, 0.3f));

        // 2. Dynamic Gain based on distance to the NEAREST wall.
        float distanceFactor = Math.max(0.0f, Math.min(1.0f, 1.0f - (minDist / (float) maxDist)));

        // Retrieve base reflection gain calculated from venue material (vReflGain)
        float baseReflGain = venuePreset.reflectionsGain * cfg.reverb_reflGainMultiplier;

        // Scale baseReflGain dynamically.
        // The venue preset already sets a baseline (1.0x) for the room's average
        // reflectivity.
        // When you walk close to a wall, we BOOST the early reflections up to 2.5x.
        float dynamicReflGain = baseReflGain * (1.0f + (distanceFactor * 0.6f));
        dynamicReflGain = Math.max(0.0f, Math.min(3.16f, dynamicReflGain));

        // Safely pass to the render thread instead of calling OpenAL directly
        this.currentReflGain = dynamicReflGain;
        this.currentReflDelay = dynamicReflDelay;
    }

    /**
     * Calculate venue probe position from speaker cluster.
     * Weighted: 50% mid, 30% sub, 20% high/line array
     */
    private Vec3d calculateVenueProbe(List<StreamSource> sources) {
        double subX = 0, subY = 0, subZ = 0;
        int subCount = 0;
        double midX = 0, midY = 0, midZ = 0;
        int midCount = 0;
        double highX = 0, highY = 0, highZ = 0;
        int highCount = 0;

        for (StreamSource s : sources) {
            BlockPos p = s.getPos();
            double px = p.getX() + 0.5, py = p.getY() + 0.5, pz = p.getZ() + 0.5;
            if ("sub".equals(s.speakerType)) {
                subX += px;
                subY += py;
                subZ += pz;
                subCount++;
            } else if ("mid".equals(s.speakerType)) {
                midX += px;
                midY += py;
                midZ += pz;
                midCount++;
            } else {
                highX += px;
                highY += py;
                highZ += pz;
                highCount++;
            }
        }

        double totalX = 0, totalY = 0, totalZ = 0;
        double totalWeight = 0;

        if (midCount > 0) {
            totalX += (midX / midCount) * 0.5;
            totalY += (midY / midCount) * 0.5;
            totalZ += (midZ / midCount) * 0.5;
            totalWeight += 0.5;
        }
        if (subCount > 0) {
            totalX += (subX / subCount) * 0.3;
            totalY += (subY / subCount) * 0.3;
            totalZ += (subZ / subCount) * 0.3;
            totalWeight += 0.3;
        }
        if (highCount > 0) {
            totalX += (highX / highCount) * 0.2;
            totalY += (highY / highCount) * 0.2;
            totalZ += (highZ / highCount) * 0.2;
            totalWeight += 0.2;
        }

        if (totalWeight > 0) {
            return new Vec3d(totalX / totalWeight, totalY / totalWeight, totalZ / totalWeight);
        }

        // Fallback: simple average
        double avgX = 0, avgY = 0, avgZ = 0;
        for (StreamSource s : sources) {
            avgX += s.getPos().getX() + 0.5;
            avgY += s.getPos().getY() + 0.5;
            avgZ += s.getPos().getZ() + 0.5;
        }
        return new Vec3d(avgX / sources.size(), avgY / sources.size(), avgZ / sources.size());
    }

    /**
     * Calculate stage-front direction from speaker facing vectors.
     * This is the average direction speakers are pointing at (towards the
     * audience).
     */
    private Vec3d calculateStageDirection(List<StreamSource> sources) {
        double totalDirX = 0, totalDirY = 0, totalDirZ = 0;
        for (StreamSource s : sources) {
            totalDirX += s.dirX;
            totalDirY += s.dirY;
            totalDirZ += s.dirZ;
        }
        double len = Math.sqrt(totalDirX * totalDirX + totalDirY * totalDirY + totalDirZ * totalDirZ);
        if (len < 0.001) {
            return new Vec3d(1, 0, 0); // Fallback: face +X
        }
        return new Vec3d(totalDirX / len, totalDirY / len, totalDirZ / len);
    }

    /**
     * Applies global occlusion to the main EFX Reverb effect.
     * Called by updateSourcesTick to prevent lingering reverb tails from passing
     * through solid walls at full volume.
     */
    private void updateMasterReverbOcclusion(float targetMasterOcclusion) {
        if (reverbEffectId == 0 || venuePreset == null) return;

        com.audiophilecraft.config.LiveTuningConfig cfg = com.audiophilecraft.config.LiveTuningConfig.get();
        // Smooth the master occlusion
        float lerpRate =
                (targetMasterOcclusion < this.smoothedMasterOcclusion) ? cfg.masterOcc_lerpIn : cfg.masterOcc_lerpOut;
        this.smoothedMasterOcclusion += (targetMasterOcclusion - this.smoothedMasterOcclusion) * lerpRate;

        float masterGain = venuePreset.gain
                * (cfg.masterOcc_gainFloor + (1.0f - cfg.masterOcc_gainFloor) * this.smoothedMasterOcclusion);

        // HF (Treble) gets completely smothered by walls
        float masterGainHF =
                venuePreset.gainHF * (float) Math.pow(this.smoothedMasterOcclusion, cfg.masterOcc_hfExponent);

        // Ensure values stay within the absolute limits OpenAL allows
        masterGain = Math.max(0.0f, Math.min(1.0f, masterGain));
        masterGainHF = Math.max(0.01f, Math.min(1.0f, masterGainHF));

        alEffectf(reverbEffectId, AL_EAXREVERB_GAIN, masterGain);
        alEffectf(reverbEffectId, AL_EAXREVERB_GAINHF, masterGainHF);

        // Slot needs to be "re-attached" to instantly update some drivers
        alAuxiliaryEffectSloti(auxSlotId, AL_EFFECTSLOT_EFFECT, reverbEffectId);
    }

    /**
     * Updates the OpenAL listener position and orientation.
     * Called every render frame.
     */
    public void updateListener(Vec3d pos, float yaw, float pitch) {
        if (getActiveSession() == null) return;
        // Position - Update instantly
        // listenerPos stores the REAL position for physics/distance calculations
        this.listenerPos = pos;

        // â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•
        // HRTF Y-AXIS FLATTENING (Listener-Side)
        // â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•
        // HRTF uses the elevation angle between listener and source positions.
        // Adjusting source Y doesn't work well because HRTF is angle-based:
        // if source is directly above, scaling Y doesn't change the 90Â° angle.
        //
        // Instead, we shift the LISTENER Y that OpenAL sees toward the
        // weighted average Y of active sources. This directly changes the
        // elevation angle for ALL sources simultaneously.
        //
        // Factor 0.4 = listener Y moves 40% toward the average source Y.
        // Result: HRTF perceives sources as being much closer to ear level.
        // â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•
        float openAlListenerY = (float) pos.y;
        if (!getActiveSession().getStreamSources().isEmpty()) {
            double avgSourceY = 0;
            int count = 0;
            for (StreamSource s : getActiveSession().getStreamSources()) {
                if (s.isValid && !s.isFinished) {
                    avgSourceY += s.getPos().getY() + 0.5;
                    count++;
                }
            }
            if (count > 0) {
                avgSourceY /= count;
                openAlListenerY = (float) (avgSourceY
                        - (avgSourceY - pos.y) * com.audiophilecraft.config.LiveTuningConfig.get().hrtf_yFlatten);
            }
        }
        alListener3f(AL_POSITION, (float) pos.x, openAlListenerY, (float) pos.z);

        // Removed rotation smoothing (lerping) as it causes phase glitching/pitching
        // with the HRTF/OpenAL 3D spatializer when rotating the camera.
        this.listenerYaw = yaw;
        this.listenerPitch = pitch;
        listener.update(pos, yaw, pitch);

        float useYaw = yaw;
        float usePitch = pitch;

        // Velocity (assume zero for now, or track delta)
        alListener3f(AL_VELOCITY, 0f, 0f, 0f);

        // Orientation calculation
        // pitch: 0=horizon, -90=up, +90=down
        // yaw: 0=south, 90=west, 180=north, 270=east
        // Look vector (AT)
        // Minecraft's orientation:
        // yaw 0 = South (+Z), 90 = West (-X), 180 = North (-Z), 270 = East (+X)
        // pitch -90 = Straight UP, 90 = Straight DOWN

        // Convert to radians and flip signs to match standard mathematics
        float pitchRad = (float) Math.toRadians(usePitch);
        float yawRad = (float) Math.toRadians(-useYaw); // -yaw for correct CW/CCW

        float cosPitch = (float) Math.cos(pitchRad);
        float sinPitch = (float) Math.sin(pitchRad);
        float cosYaw = (float) Math.cos(yawRad);
        float sinYaw = (float) Math.sin(yawRad);

        // AT vector (Where the listener is looking)
        // OpenAL uses same coordinate axes as Minecraft world:
        // sources and listener positions are in world coords,
        // so AT must also point in the actual world direction.
        // Yaw 0 = South (+Z), 90 = West (-X), etc.
        float atX = sinYaw * cosPitch;
        float atY = -sinPitch;
        float atZ = cosYaw * cosPitch;

        // --- ROBUST UP VECTOR (Cross Product Method) ---
        // To guarantee the UP vector is perfectly perpendicular (orthogonal) to the AT
        // vector
        // without arbitrary trigonometry flipping, we use the standard 3D math
        // approach:
        // 1. Define a temporary "Right" vector by crossing AT with World Up (0, 1, 0)
        // 2. Define the real "UP" vector by crossing Right with AT
        // 3. If looking straight up/down, standard cross product fails, so we handle
        // it.

        float upX, upY, upZ;

        if (Math.abs(usePitch) > 89.9f) {
            // Extreme angles (looking straight up or straight down)
            // If looking straight down (pitch = 90), AT is (0, -1, 0).
            // The top of your head points FORWARD (which is exactly where you were facing
            // before looking down)
            // Forward in Minecraft is (-sinYaw, 0, -cosYaw).
            // If looking straight UP (pitch = -90), AT is (0, 1, 0).
            // The top of your head points BACKWARD.
            float sign = Math.signum(usePitch); // 1.0 for down, -1.0 for up
            upX = sinYaw * sign;
            upY = 0.0f;
            upZ = cosYaw * sign;
        } else {
            // Normal angles: Cross Product for perfect 90-degree orthoganality

            // World Up
            float worldUpX = 0.0f;
            float worldUpY = 1.0f;
            float worldUpZ = 0.0f;

            // Right = AT x WorldUp
            float rightX = atY * worldUpZ - atZ * worldUpY;
            float rightY = atZ * worldUpX - atX * worldUpZ;
            float rightZ = atX * worldUpY - atY * worldUpX;

            // Normalize Right vector
            float rightLen = (float) Math.sqrt(rightX * rightX + rightY * rightY + rightZ * rightZ);
            rightX /= rightLen;
            rightY /= rightLen;
            rightZ /= rightLen;

            // True UP = Right x AT
            upX = rightY * atZ - rightZ * atY;
            upY = rightZ * atX - rightX * atZ;
            upZ = rightX * atY - rightY * atX;

            // Normalize UP vector
            float upLen = (float) Math.sqrt(upX * upX + upY * upY + upZ * upZ);
            upX /= upLen;
            upY /= upLen;
            upZ /= upLen;
        }

        float[] orientation = new float[] {atX, atY, atZ, upX, upY, upZ};
        alListenerfv(AL_ORIENTATION, orientation);

        // --- UNDERWATER DETECTION ---
        // When the listener is submerged, HF is dramatically absorbed by water.
        // We set a flag here and apply the filter in StreamSource.updatePhysics()
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player != null) {
            isUnderwater = mc.player.isSubmergedInWater();
        }
        // Smooth transition to prevent pop when entering/exiting water
        float targetUnderwaterHF = isUnderwater ? 0.08f : 1.0f;
        smoothedUnderwaterHF += (targetUnderwaterHF - smoothedUnderwaterHF) * 0.15f;
    }

    /** Returns the smoothed underwater HF gain (0.08 = submerged, 1.0 = normal) */
    public float getUnderwaterHFGain() {
        return listener.getUnderwaterHFGain();
    }

    // --- MIXER STATE (Client-Side Only â€” No Network Required) ---

    // Mid/Side (Direct/Reverb) Mute States

    public boolean isMidMuted() {
        return getActiveSession().isMidMuted();
    }

    public void setMidMuted(boolean muted) {
        getActiveSession().setMidMuted(muted);
    }

    public boolean isSideMuted() {
        return getActiveSession().isSideMuted();
    }

    public void setSideMuted(boolean muted) {
        getActiveSession().setSideMuted(muted);
    }

    // 5-Band Parametric EQ per speaker type (dB, range: -12 to +12)

    public float getMixerGain(String speakerType) {
        return getActiveSession().getMixerGain(speakerType);
    }

    public void setMixerGain(String speakerType, float gain) {
        getActiveSession().setMixerGain(speakerType, gain);
    }

    /** Get EQ dB for a speaker type and band (0 to 4) */
    public synchronized float getEqDb(String speakerType, int band) {
        return getActiveSession().getEqDb(speakerType, band);
    }

    /** Set EQ dB for a speaker type and band (0 to 4). Range: -12 to +12 */
    public synchronized void setEqDb(String speakerType, int band, float db) {
        getActiveSession().setEqDb(speakerType, band, db);
    }

    // Q (Bandwidth) per speaker type and band (range: 0.1 to 10.0, default: 1.0)
    private volatile float[] subEqQ = new float[] {1f, 1f, 1f, 1f, 1f};
    private volatile float[] midEqQ = new float[] {1f, 1f, 1f, 1f, 1f};
    private volatile float[] lineEqQ = new float[] {1f, 1f, 1f, 1f, 1f};

    /** Get EQ Q for a speaker type and band (0 to 4) */
    public synchronized float getEqQ(String speakerType, int band) {
        if (band < 0 || band > 4) return 1f;
        if ("sub".equals(speakerType)) return subEqQ[band];
        if ("mid".equals(speakerType)) return midEqQ[band];
        if ("line".equals(speakerType)) return lineEqQ[band];
        return 1f;
    }

    /** Set EQ Q for a speaker type and band (0 to 4). Range: 0.1 to 10.0 */
    public synchronized void setEqQ(String speakerType, int band, float q) {
        if (band < 0 || band > 4) return;
        q = Math.max(0.1f, Math.min(q, 10.0f));
        if ("sub".equals(speakerType)) subEqQ[band] = q;
        else if ("mid".equals(speakerType)) midEqQ[band] = q;
        else if ("line".equals(speakerType)) lineEqQ[band] = q;
    }

    /**
     * Smooth gain interpolation placeholder.
     * Called every render frame.
     */
    public void updateGains() {
        if (getActiveSession() == null || this.listenerPos == null) return;

        // Ensure venue reverb is applied if a preset exists
        ensureVenueReverb();

        // Note: Per-source physics (gain, occlusion, etc.) is now handled
        // in StreamSource.update() which runs every tick.
    }

    /**
     * Thread-safe wall-clock time since playback started.
     * Audio thread calls this to derive globalSampleTime for ALL sources.
     * Pause duration is already factored out via getActiveSession().getStreamStartTime() offset.
     * Returns 0.0 if not playing.
     */
    public double getTimeSinceStart() {
        if (getActiveSession() == null
                || !getActiveSession().isPlaying()
                || getActiveSession().getStreamStartTime() == 0) return 0.0;
        return (System.nanoTime() - getActiveSession().getStreamStartTime()) / 1_000_000_000.0;
    }

    /**
     * Returns the sample rate of the currently active audio stream.
     * Used by the global master clock to convert wall-clock seconds to sample
     * position.
     */
    public int getSampleRateForClock() {
        for (AudioStreamBuffer buffer : getActiveSession().getStreamBuffers().values()) {
            if (buffer.sampleRate > 0) {
                return buffer.sampleRate;
            }
        }
        return 48000; // Safe fallback
    }

    private long lastTickTime = System.nanoTime();

    /**
     * Cleanup and logic update. Called every client tick (20Hz).
     */
    public void updateSourcesTick(World world) {
        MinecraftClient mc = MinecraftClient.getInstance();
        boolean gamePaused = mc.isPaused();

        if (gamePaused != getActiveSession().isPaused()) {
            getActiveSession().setPaused(gamePaused);
            if (getActiveSession().isPaused()) {
                // Game Just Paused: Record timestamp
                getActiveSession().setPauseStartTimestamp(System.nanoTime());
                pauseAll();
            } else {
                // Game Just Resumed: Calculate duration and shift start time
                if (getActiveSession().getPauseStartTimestamp() > 0
                        && getActiveSession().getStreamStartTime() > 0) {
                    long pauseDuration = System.nanoTime() - getActiveSession().getPauseStartTimestamp();
                    getActiveSession()
                            .setStreamStartTime(getActiveSession().getStreamStartTime()
                                    + pauseDuration); // "Freeze" the timeline during pause
                }
                resumeAll();
            }
        }

        // Don't update logic if paused
        if (getActiveSession().isPaused()) {
            lastTickTime = System.nanoTime(); // Reset delta tracking when paused
            return;
        }

        // OPTIMIZATION: Continuous environment analysis is disabled to save CPU.
        // We only care about the PA system, which does a one-time scan via
        // scanAtPosition() later.
        // analyzeEnvironment(world);

        // Update sources for ALL playing sessions
        for (PlaybackSession session : sessions.values()) {
            if (session.isPlaying() && session.getStreamStartTime() != 0) {
                long now = System.nanoTime();
                double timeSinceStart = (now - session.getStreamStartTime()) / 1_000_000_000.0;

                java.util.List<StreamSource> toRemove = new java.util.ArrayList<>();
                for (StreamSource source : session.getStreamSources()) {
                    if (!source.update(world, this.listenerPos, timeSinceStart)) {
                        source.cleanup();
                        toRemove.add(source);
                    }
                }
                session.getStreamSources().removeAll(toRemove);
            }
        }

        // Apply Listener-based Early Reflections dynamically every tick
        boolean anyPlaying = false;
        for (PlaybackSession s : sessions.values()) {
            if (s.isPlaying()) {
                anyPlaying = true;
                break;
            }
        }
        if (anyPlaying) {
            updateListenerReflections(world);
        }

        // --- VENUE-LOCKED REVERB STATE FIX ---
        // If all sources finished naturally during ACTIVE playback, clear the venue
        // preset.
        // Otherwise, the player will be stuck with the stadium reverb forever.
        // Guard: only clear if we were actually playing (not during device recovery)
        if (getActiveSession().isPlaying()
                && getActiveSession().getStreamSources().isEmpty()
                && this.venuePreset != null) {
            this.venuePreset = null;
            this.venuePresetApplied = false;
        }

        // --- OCCLUSION CLUSTERING (REMOVED) ---
        // Clustering was sharing occlusion values between speakers up to 8 blocks
        // apart.
        // If one speaker peeks out from behind a wall, the *entire cluster* instantly
        // gets
        // unoccluded, causing a sudden pop in volume and treble even for speakers still
        // behind the wall.
        // Raycast performance is already throttled per-speaker in StreamSource.java.

        // If the player steps outside the building where the music is playing, the
        // overall
        // room reverb (the tail of the stadium or hall) should also be physically
        // muffled and blocked
        // by the walls. It should not hang in the player's ears like an artificial
        // overlay.
        float maxOcclusion = 0.0f;
        int activeSourceCount = 0;
        for (PlaybackSession session : sessions.values()) {
            for (StreamSource source : session.getStreamSources()) {
                activeSourceCount++;
                if (source.currentOcclusion > maxOcclusion) {
                    maxOcclusion = source.currentOcclusion;
                }
            }
        }

        // If not playing anything, keep maxOcclusion at 1.0 so ambient sound is normal
        if (activeSourceCount == 0) {
            maxOcclusion = 1.0f;
        }

        updateMasterReverbOcclusion(maxOcclusion);

        // Ensure venue reverb is applied if preset exists
        ensureVenueReverb();

        lastTickTime = System.nanoTime();
    }

    public void pauseAll() {
        for (PlaybackSession session : sessions.values()) {
            for (StreamSource sound : session.getStreamSources()) {
                sound.pause();
            }
        }
        // Mute aux effect slots to kill reverb tails during pause
        if (auxSlotId != 0) {
            alAuxiliaryEffectSlotf(auxSlotId, AL_EFFECTSLOT_GAIN, 0.0f);
        }
    }

    /**
     * Stops a specific session immediately.
     */
    public void stopSession(java.util.UUID sessionUUID) {
        PlaybackSession session = sessions.remove(sessionUUID);
        if (session != null) {
            session.stopAll();
        }
        checkAndShutdownThread();
    }

    /**
     * Stops all active audio sources across all sessions immediately.
     */
    public void stopAll() {
        for (PlaybackSession session : sessions.values()) {
            session.stopAll();
        }
        sessions.clear();
        checkAndShutdownThread();
    }

    private void checkAndShutdownThread() {
        // Clear venue preset so reverb falls back to listener-based scanner
        this.venuePreset = null;
        this.venuePresetApplied = false;
        this.storedVenueDescriptor = null;
        this.storedVenueProbePos = null;

        boolean anyPlaying = false;
        for (PlaybackSession s : sessions.values()) {
            if (s.isPlaying()) {
                anyPlaying = true;
                break;
            }
        }
        if (!anyPlaying && audioThread != null) {
            audioThread.shutdownNow();
            try {
                audioThread.awaitTermination(50, java.util.concurrent.TimeUnit.MILLISECONDS);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
            }
            audioThread = null;
        }
    }

    public void resumeAll() {
        for (PlaybackSession session : sessions.values()) {
            for (StreamSource sound : session.getStreamSources()) {
                sound.resume();
            }
        }
        // Restore aux effect slot gain before resuming sources
        if (auxSlotId != 0) {
            alAuxiliaryEffectSlotf(auxSlotId, AL_EFFECTSLOT_GAIN, 1.0f);
        }
    }

    /**
     * Start the background audio processing thread.
     * Runs every 5ms, pre-computing PCM buffers for all active StreamSources.
     */
    private void startAudioThread() {
        if (audioThread != null && !audioThread.isShutdown()) {
            return; // Thread is already running and handling sessions
        }

        // Capture LWJGL OpenAL capabilities from the current (render) thread.
        // These must be propagated to the audio thread so it can make AL calls.
        final org.lwjgl.openal.ALCapabilities alCaps = org.lwjgl.openal.AL.getCapabilities();

        audioThread = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "AudiophileCraft-Audio");
            t.setDaemon(true);
            return t;
        });

        // One-shot init: set AL capabilities on the audio thread before any work
        audioThread.execute(() -> {
            try {
                org.lwjgl.openal.AL.setCurrentThread(alCaps);
            } catch (Exception e) {
                System.err.println("AudioEngine: Failed to propagate AL caps to audio thread: " + e.getMessage());
            }
        });

        audioThread.scheduleWithFixedDelay(this::processAudioBackground, 0, 5, TimeUnit.MILLISECONDS);
    }

    /**
     * Background audio processing loop.
     * Phase 1: Smooth listener position (kill FPS jitter).
     * Phase 2: Pre-computes PCM data for all active sources with up-to-date
     * distance.
     * Phase 3: Feeds the pre-computed data to OpenAL (unqueue â†’ fill â†’ queue).
     * All phases run entirely on this thread, making audio independent from the
     * render thread. Minecraft can freeze for seconds without audio dropping out.
     */
    private synchronized void processAudioBackground() {
        // Respect interrupt: executor shutdown will interrupt us
        if (Thread.interrupted()) {
            Thread.currentThread().interrupt();
            return;
        }
        try {
            double currentWallTime = 0.0;
            // Phase 0: Decode OGG on background thread
            for (PlaybackSession session : sessions.values()) {
                if (!session.isPlaying() || session.isPaused() || session.isSeeking()) continue;
                currentWallTime = session.getStreamStartTime() > 0
                        ? (System.nanoTime() - session.getStreamStartTime()) / 1_000_000_000.0
                        : 0.0;
                for (AudioStreamBuffer buffer : session.getStreamBuffers().values()) {
                    if (buffer.sampleRate > 0) {
                        buffer.syncToTime(currentWallTime + BUFFER_LOOKAHEAD);
                    }
                }
            }

            // Phase 1: Smooth listener position
            Vec3d rawPos = this.listenerPos;
            if (rawPos != null) {
                Vec3d prev = this.smoothedListenerPos;
                if (prev == null) prev = rawPos;
                double alpha = 0.35;
                this.smoothedListenerPos = new Vec3d(
                        prev.x + (rawPos.x - prev.x) * alpha,
                        prev.y + (rawPos.y - prev.y) * alpha,
                        prev.z + (rawPos.z - prev.z) * alpha);
            }
            Vec3d currentPos = this.smoothedListenerPos;

            for (PlaybackSession session : sessions.values()) {
                if (!session.isPlaying() || session.isPaused() || session.isSeeking()) continue;
                double sessionWallTime = session.getStreamStartTime() > 0
                        ? (System.nanoTime() - session.getStreamStartTime()) / 1_000_000_000.0
                        : 0.0;
                int sampleRate = 48000;
                for (AudioStreamBuffer buffer : session.getStreamBuffers().values()) {
                    if (buffer.sampleRate > 0) {
                        sampleRate = buffer.sampleRate;
                        break;
                    }
                }
                double globalSampleTime = sessionWallTime * sampleRate;

                for (StreamSource source : session.getStreamSources()) {
                    if (source.feedOpenALFromAudioThread(globalSampleTime, currentPos)) {
                        if (reusableRestartBuffer.remaining() > 0) {
                            reusableRestartBuffer.put(source.sourceId);
                        }
                    }
                }
            }

            if (reusableRestartBuffer.position() > 0) {
                int count = reusableRestartBuffer.position();
                reusableRestartBuffer.flip();
                org.lwjgl.openal.AL10.alSourcePlayv(reusableRestartBuffer);
            }
        } catch (Exception e) {
            System.err.println("[AudioEngine] processAudioBackground failed: " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * Full cleanup including EFX resources.
     */
    public void cleanupEfx() {
        stopAll();
        if (auxSlotId != 0) {
            alAuxiliaryEffectSloti(auxSlotId, AL_EFFECTSLOT_EFFECT, AL_EFFECT_NULL);
            alDeleteAuxiliaryEffectSlots(auxSlotId);
            auxSlotId = 0;
        }
        if (reverbEffectId != 0) {
            alDeleteEffects(reverbEffectId);
            reverbEffectId = 0;
        }
        // Free Stream Buffers (off-heap memory)
        for (PlaybackSession session : sessions.values()) {
            for (AudioStreamBuffer buffer : session.getStreamBuffers().values()) {
                buffer.cleanup();
            }
            session.getStreamBuffers().clear();
        }

        efxInitialized = false;
        openAL.destroy(0, 0);
    }

    // Speaker Types for Cache Keys
    private static final String TYPE_NORMAL = "normal";
    private static final String TYPE_SUB = "sub";
    private static final String TYPE_MID = "mid";
    private static final String TYPE_LINE = "line";

    // Stream Buffers Management
    public void prepareStreamBuffers(PlaybackSession session, String trackId) {
        for (AudioStreamBuffer buffer : session.getStreamBuffers().values()) {
            buffer.cleanup();
        }
        session.getStreamBuffers().clear();

        // Load Raw Data once
        OggDecoder.RawTrackData rawData = OggDecoder.loadOgg("sounds/" + trackId + ".ogg");
        if (rawData == null) return;

        // Create 3 Buffers (Sub, Mid, Line) + Normal?
        createStreamBufferForType(session, trackId, rawData, TYPE_SUB);
        createStreamBufferForType(session, trackId, rawData, TYPE_MID);
        createStreamBufferForType(session, trackId, rawData, TYPE_LINE);
        createStreamBufferForType(session, trackId, rawData, TYPE_NORMAL);

        // Free the native PCM buffer to prevent memory leak
        MemoryUtil.memFree(rawData.pcmData);
    }

    private void createStreamBufferForType(
            PlaybackSession session, String trackId, OggDecoder.RawTrackData rawData, String type) {
        // Clone data for processing
        short[] audioData = new short[rawData.pcmData.remaining()];
        rawData.pcmData.rewind();
        rawData.pcmData.get(audioData);

        // Apply DSP
        applyDspForType(audioData, rawData.sampleRate, type);

        // Create Buffer
        AudioStreamBuffer buffer = new AudioStreamBuffer(trackId + "_" + type, rawData.sampleRate);

        // Fill Buffer
        ShortBuffer pcm = MemoryUtil.memAllocShort(audioData.length);
        pcm.put(audioData);
        pcm.flip();

        buffer.setSourceData(pcm);
        session.getStreamBuffers().put(type, buffer);
    }

    public void applyDspForType(short[] audioData, int sampleRate, String speakerType) {
        // Pre-gain for headroom: fixed at 0.60 for all incoming tracks
        AudioDSP.applyGain(audioData, 0.60f);
        if (TYPE_SUB.equals(speakerType)) {
            // 24dB/oct Butterworth LP crossover at 100Hz
            AudioDSP.applyFilter(audioData, sampleRate, AudioDSP.FilterType.LOW_PASS, 100, 0.707f, 0);
            AudioDSP.applyFilter(audioData, sampleRate, AudioDSP.FilterType.LOW_PASS, 100, 0.707f, 0);
        } else if (TYPE_MID.equals(speakerType)) {
            // 24dB/oct HP at 100Hz (matches sub crossover) + 24dB/oct LP at 2000Hz
            AudioDSP.applyFilter(audioData, sampleRate, AudioDSP.FilterType.HIGH_PASS, 100, 0.707f, 0);
            AudioDSP.applyFilter(audioData, sampleRate, AudioDSP.FilterType.HIGH_PASS, 100, 0.707f, 0);
            AudioDSP.applyFilter(audioData, sampleRate, AudioDSP.FilterType.LOW_PASS, 2000, 0.707f, 0);
            AudioDSP.applyFilter(audioData, sampleRate, AudioDSP.FilterType.LOW_PASS, 2000, 0.707f, 0);
        } else if (TYPE_LINE.equals(speakerType)) {
            // Line arrays in real life output both Mid and High frequencies (everything
            // except Sub)
            // 24dB/oct HP at 100Hz (no Low-Pass so it extends to 20kHz)
            AudioDSP.applyFilter(audioData, sampleRate, AudioDSP.FilterType.HIGH_PASS, 100, 0.707f, 0);
            AudioDSP.applyFilter(audioData, sampleRate, AudioDSP.FilterType.HIGH_PASS, 100, 0.707f, 0);
        }

        // SAFETY LIMITER: Prevents hard digital clipping
        AudioDSP.applyPeakLimiter(audioData, 0.98f);
    }

    public void playTrack(
            java.util.UUID sessionUUID, String trackId, List<BlockPos> speakers, float power, float inputGain) {
        sessions.computeIfAbsent(sessionUUID, k -> new PlaybackSession(this)).stopAll();
        trackGeneration++;

        // Clear old venue scan data
        AdvancedAcousticScanner.lastPointCloud.clear();
        AdvancedAcousticScanner.lastVenueBlocks.clear();
        AdvancedAcousticScanner.lastSpeakers =
                speakers != null ? new java.util.ArrayList<>(speakers) : new java.util.ArrayList<>();
        venuePreset = null;
        venuePresetApplied = false;
        storedVenueDescriptor = null;
        storedVenueProbePos = null;
        com.audiophilecraft.client.screen.PointCloudRenderer.invalidateCache();

        while (alGetError() != AL_NO_ERROR) {
            /* drain */ }
        initEfx();

        if (speakers == null || speakers.isEmpty()) return;

        try {
            prepareStreamBuffers(sessions.get(sessionUUID), trackId);
            sessions.get(sessionUUID).setPlaying(true);
            sessions.get(sessionUUID).setPaused(false);
            for (AudioStreamBuffer buffer :
                    sessions.get(sessionUUID).getStreamBuffers().values()) {
                if (buffer.sampleRate > 0) buffer.syncToTime(BUFFER_LOOKAHEAD);
            }

            // Shared logic
            World world = MinecraftClient.getInstance().world;
            int[] counts = SpeakerClusterer.countSpeakerTypes(speakers, world);
            List<List<BlockPos>> clusters = SpeakerClusterer.clusterSpeakers(speakers);
            createSourcesFromClusters(sessions.get(sessionUUID), clusters, counts, world, power, inputGain);
            startPlaybackWithVenueScan(sessions.get(sessionUUID), world, speakers, false);

        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public void updateInputGain(float gain) {
        if (getActiveSession() == null) return;
        for (StreamSource ss : getActiveSession().getStreamSources()) {
            ss.inputGain = gain;
        }
    }

    /**
     * Live update power for all active StreamSources.
     * Called when the power knob is changed in the GUI.
     * Smoothing is handled inside StreamSource.updatePhysics().
     */
    public void updatePower(float power) {
        if (getActiveSession() == null) return;
        for (StreamSource ss : getActiveSession().getStreamSources()) {
            ss.power = power;
        }
    }

    /**
     * Play audio from an internet URL (YouTube, SoundCloud, HTTP, etc.)
     * Uses InternetAudioLoader (LavaPlayer) to resolve and decode the URL to PCM,
     * then feeds into the existing DSP/StreamSource/OpenAL pipeline.
     *
     * @param url       The URL to play (e.g. YouTube link, HTTP audio file)
     * @param speakers  Connected speaker positions
     * @param power     Amplifier power
     * @param inputGain Input gain multiplier
     */
    public void playFromUrl(
            java.util.UUID sessionUUID, String url, List<BlockPos> speakers, float power, float inputGain) {
        InternetAudioLoader.getInstance().loadTrackStreaming(url, new InternetAudioLoader.StreamingCallback() {
            @Override
            public void onReady(short[] pcmArray, int decodedSamples, int totalExpected, int sampleRate, String title) {
                AudioStreamBuffer sharedBuf = new AudioStreamBuffer("url_stream", sampleRate);
                sharedBuf.initStreaming(pcmArray, decodedSamples, totalExpected);
                PlaybackSession session =
                        sessions.computeIfAbsent(sessionUUID, k -> new PlaybackSession(AudioEngine.this));
                session.stopAll();
                session.getStreamBuffers().clear();
                session.getStreamBuffers().put(TYPE_SUB, sharedBuf);
                session.getStreamBuffers().put(TYPE_MID, sharedBuf);
                session.getStreamBuffers().put(TYPE_LINE, sharedBuf);
                session.getStreamBuffers().put(TYPE_NORMAL, sharedBuf);

                trackGeneration++;
                AdvancedAcousticScanner.lastPointCloud.clear();
                AdvancedAcousticScanner.lastVenueBlocks.clear();
                AdvancedAcousticScanner.lastSpeakers =
                        speakers != null ? new java.util.ArrayList<>(speakers) : new java.util.ArrayList<>();
                venuePreset = null;
                venuePresetApplied = false;
                storedVenueDescriptor = null;
                storedVenueProbePos = null;
                com.audiophilecraft.client.screen.PointCloudRenderer.invalidateCache();
                while (alGetError() != AL_NO_ERROR) {
                    /* drain */
                }
                initEfx();
                if (speakers == null || speakers.isEmpty()) return;
                sessions.get(sessionUUID).setPlaying(true);
                sessions.get(sessionUUID).setPaused(false);
                sharedBuf.syncToTime(BUFFER_LOOKAHEAD);
                World world = MinecraftClient.getInstance().world;
                int[] counts = SpeakerClusterer.countSpeakerTypes(speakers, world);
                List<List<BlockPos>> clusters = SpeakerClusterer.clusterSpeakers(speakers);
                createSourcesFromClusters(sessions.get(sessionUUID), clusters, counts, world, power, inputGain);
                startPlaybackWithVenueScan(sessions.get(sessionUUID), world, speakers, false);
            }

            @Override
            public void onMoreData(int totalDecoded) {
                PlaybackSession session = sessions.get(sessionUUID);
                if (session != null) {
                    AudioStreamBuffer buf = session.getStreamBuffers().get(TYPE_NORMAL);
                    if (buf != null) buf.updateDecodedLength(totalDecoded);
                }
            }

            @Override
            public void onComplete() {}

            @Override
            public void onFailed(String reason) {
                System.err.println("AudioEngine: URL load failed: " + reason);
                net.minecraft.client.MinecraftClient.getInstance().execute(() -> {
                    if (net.minecraft.client.MinecraftClient.getInstance().player != null) {
                        net.minecraft.client.MinecraftClient.getInstance()
                                .player
                                .sendMessage(
                                        net.minecraft.text.Text.literal("HATA (CRITICAL DECODE ERROR): " + reason)
                                                .formatted(net.minecraft.util.Formatting.RED),
                                        false);
                    }
                });
            }
        });
    }

    // â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•
    // SHARED HELPERS â€” Used by both playTrack() and playFromPcmData()
    // â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•

    public void createSourcesFromClusters(
            PlaybackSession session,
            List<List<BlockPos>> clusters,
            int[] counts,
            World world,
            float power,
            float inputGain) {
        for (List<BlockPos> cluster : clusters) {
            StreamSource leaderSource = null;
            for (BlockPos pos : cluster) {
                String speakerType = TYPE_NORMAL;
                float baseRefDist = 3.0f;
                float baseMaxDist = 64.0f;
                int sampleShiftMs = 0;
                int speakerCount = 1;

                if (world != null) {
                    var blockState = world.getBlockState(pos);
                    var block = blockState.getBlock();
                    if (block instanceof com.audiophilecraft.block.SubwooferBlock) {
                        speakerType = TYPE_SUB;
                        baseRefDist = 10.0f;
                        baseMaxDist = 85.0f;
                        speakerCount = counts[0];
                    } else if (block instanceof com.audiophilecraft.block.MidRangeBlock) {
                        speakerType = TYPE_MID;
                        baseRefDist = 5.0f;
                        baseMaxDist = 60.0f;
                        speakerCount = counts[1];
                    } else if (block instanceof com.audiophilecraft.block.LineArrayBlock) {
                        speakerType = TYPE_LINE;
                        baseRefDist = 3.0f;
                        baseMaxDist = 50.0f;
                        speakerCount = counts[2];
                    } else {
                        speakerCount = counts[3];
                    }
                    net.minecraft.block.entity.BlockEntity be = world.getBlockEntity(pos);
                    if (be instanceof com.audiophilecraft.block.entity.SpeakerBlockEntity speakerBe) {
                        sampleShiftMs = speakerBe.getSampleShift();
                    }
                }

                AudioStreamBuffer buffer = session.getStreamBuffers().get(speakerType);
                if (buffer == null) buffer = session.getStreamBuffers().get(TYPE_NORMAL);
                if (buffer == null) continue;

                int sourceId = alGenSources();
                int err = alGetError();
                if (err != AL_NO_ERROR) {
                    System.err.println("AudioEngine: OPENAL SOURCE LIMIT HIT! Failed at speaker #"
                            + (session.getStreamSources().size() + 1) + " of "
                            + clusters.stream().mapToInt(List::size).sum()
                            + " (error=0x" + Integer.toHexString(err) + ")");
                    // Clean up any sources created so far â€” partial playback is worse than
                    // silence
                    for (StreamSource s : session.getStreamSources()) {
                        s.cleanup();
                    }
                    session.getStreamSources().clear();
                    session.setPlaying(false);
                    break;
                }

                alSource3f(sourceId, AL_POSITION, pos.getX() + 0.5f, pos.getY() + 0.5f, pos.getZ() + 0.5f);
                alSourcef(sourceId, AL_ROLLOFF_FACTOR, 1.0f);
                alSourcef(sourceId, AL_MAX_DISTANCE, Float.MAX_VALUE);
                alSourcef(sourceId, AL_REFERENCE_DISTANCE, baseRefDist);
                alSourcef(sourceId, AL_GAIN, 1.0f);
                alSourcef(sourceId, AL_PITCH, 1.0f);

                Direction facing = Direction.SOUTH;
                int tiltDeg = 0;
                if (world != null) {
                    BlockState state = world.getBlockState(pos);
                    if (state.contains(Properties.HORIZONTAL_FACING)) {
                        facing = state.get(Properties.HORIZONTAL_FACING);
                    }
                    net.minecraft.block.entity.BlockEntity sbe = world.getBlockEntity(pos);
                    if (sbe instanceof com.audiophilecraft.block.entity.SpeakerBlockEntity speaker) {
                        tiltDeg = speaker.getVerticalTilt();
                    }
                }
                Vec3i vec = facing.getVector();
                float tiltRad = (float) Math.toRadians(tiltDeg);
                float cosT = (float) Math.cos(tiltRad);
                float sinT = (float) Math.sin(tiltRad);
                float dirX = vec.getX() * cosT;
                float dirY = sinT;
                float dirZ = vec.getZ() * cosT;
                alSource3f(sourceId, AL_DIRECTION, dirX, dirY, dirZ);

                int filterId = 0, sendFilterId = 0;
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
                    if (auxSlotId != 0) {
                        alSource3i(sourceId, AL_AUXILIARY_SEND_FILTER, auxSlotId, 0, sendFilterId);
                    }
                } catch (Exception e) {
                    System.err.println("AudioEngine: EFX filter/send setup failed: " + e.getMessage());
                }

                StreamSource ss = new StreamSource(
                        session,
                        sourceId,
                        buffer,
                        pos,
                        power,
                        baseMaxDist * power,
                        baseRefDist * power,
                        dirX,
                        dirY,
                        dirZ,
                        speakerType,
                        filterId,
                        sendFilterId,
                        inputGain,
                        sampleShiftMs,
                        speakerCount,
                        leaderSource,
                        cluster.size());
                session.getStreamSources().add(ss);

                if (leaderSource == null) leaderSource = ss;
            }
        }
    }

    /**
     * Performs venue acoustic scan and starts playback.
     * If world is null or no sources exist, starts playback immediately.
     *
     * @param atomicStart If true, uses alSourcePlayv for simultaneous start (URL
     *                    path).
     *                    If false, uses source.start() individually (OGG path).
     */
    public void startPlaybackWithVenueScan(
            PlaybackSession session, World world, List<BlockPos> speakers, boolean atomicStart) {
        Runnable startPlayback = () -> {
            if (MinecraftClient.getInstance().cameraEntity != null) {
                this.listenerPos = MinecraftClient.getInstance().cameraEntity.getPos();
                this.smoothedListenerPos = this.listenerPos;
            }
            session.setStreamStartTime(System.nanoTime());

            if (atomicStart) {
                java.nio.IntBuffer sourceIds = org.lwjgl.BufferUtils.createIntBuffer(
                        session.getStreamSources().size());
                for (StreamSource source : session.getStreamSources()) {
                    org.lwjgl.openal.AL10.alSourcei(
                            source.sourceId, org.lwjgl.openal.AL10.AL_LOOPING, org.lwjgl.openal.AL10.AL_FALSE);
                    sourceIds.put(source.sourceId);
                }
                sourceIds.flip();
                org.lwjgl.openal.AL10.alSourcePlayv(sourceIds);
            } else {
                for (StreamSource source : session.getStreamSources()) {
                    source.start();
                }
            }
            startAudioThread();
        };

        if (!session.getStreamSources().isEmpty() && world != null) {
            Vec3d probePos = calculateVenueProbe(session.getStreamSources());
            Vec3d stageDir = calculateStageDirection(session.getStreamSources());

            int gen = trackGeneration;
            java.util.concurrent.CompletableFuture.supplyAsync(() -> {
                        try {
                            return acousticScanner.scanVenue(world, probePos, stageDir);
                        } catch (Exception e) {
                            System.err.println("Venue scan crash: " + e.getMessage());
                            return null;
                        }
                    })
                    .exceptionally(ex -> {
                        System.err.println("Venue scan future failed: " + ex.getMessage());
                        return null;
                    })
                    .thenAcceptAsync(
                            preset -> {
                                if (gen != trackGeneration) return; // stale callback
                                if (preset != null) {
                                    this.venuePreset = preset;
                                    this.storedVenueDescriptor = acousticScanner.getLastDescriptor();
                                    this.storedVenueProbePos = acousticScanner.getLastProbePos();
                                    this.lastConfigGeneration =
                                            com.audiophilecraft.config.LiveTuningConfig.getReloadGeneration();
                                    applyVenueReverbToEfx();
                                }
                                startPlayback.run();
                            },
                            MinecraftClient.getInstance()::execute);
        } else {
            startPlayback.run();
        }
    }

    /**
     * Play from raw mono PCM data (used by InternetAudioLoader callback).
     * Creates DSP-processed stream buffers and spawns StreamSources.
     */
    public void playFromPcmData(
            java.util.UUID sessionUUID,
            short[] pcmData,
            int sampleRate,
            List<BlockPos> speakers,
            float power,
            float inputGain) {
        sessions.computeIfAbsent(sessionUUID, k -> new PlaybackSession(this)).stopAll();
        while (alGetError() != AL_NO_ERROR) {
            /* drain */ }
        initEfx();

        if (speakers == null || speakers.isEmpty()) return;

        try {
            // Wrap PCM data into RawTrackData
            java.nio.ShortBuffer pcmBuffer = org.lwjgl.system.MemoryUtil.memAllocShort(pcmData.length);
            pcmBuffer.put(pcmData);
            pcmBuffer.flip();

            OggDecoder.RawTrackData rawData = new OggDecoder.RawTrackData();
            rawData.pcmData = pcmBuffer;
            rawData.sampleRate = sampleRate;
            rawData.channels = 1;
            rawData.format = org.lwjgl.openal.AL10.AL_FORMAT_MONO16;

            // Prepare stream buffers
            for (AudioStreamBuffer buffer :
                    sessions.get(sessionUUID).getStreamBuffers().values()) {
                buffer.cleanup();
            }
            sessions.get(sessionUUID).getStreamBuffers().clear();
            createStreamBufferForType(sessions.get(sessionUUID), "url_track", rawData, TYPE_SUB);
            createStreamBufferForType(sessions.get(sessionUUID), "url_track", rawData, TYPE_MID);
            createStreamBufferForType(sessions.get(sessionUUID), "url_track", rawData, TYPE_LINE);
            createStreamBufferForType(sessions.get(sessionUUID), "url_track", rawData, TYPE_NORMAL);
            org.lwjgl.system.MemoryUtil.memFree(pcmBuffer);

            sessions.get(sessionUUID).setPlaying(true);
            sessions.get(sessionUUID).setPaused(false);
            for (AudioStreamBuffer buffer :
                    sessions.get(sessionUUID).getStreamBuffers().values()) {
                if (buffer.sampleRate > 0) buffer.syncToTime(BUFFER_LOOKAHEAD);
            }

            // Shared logic
            World world = MinecraftClient.getInstance().world;
            int[] counts = SpeakerClusterer.countSpeakerTypes(speakers, world);
            List<List<BlockPos>> clusters = SpeakerClusterer.clusterSpeakers(speakers);
            createSourcesFromClusters(sessions.get(sessionUUID), clusters, counts, world, power, inputGain);
            startPlaybackWithVenueScan(sessions.get(sessionUUID), world, speakers, true);

        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    /**
     * Updates the OpenAL direction vector for all active streams of a speaker
     * immediately. Called from the client UI or network packets.
     */
    public void updateSpeakerTilt(BlockPos speakerPos, int tiltDeg) {
        if (getActiveSession() == null) return;
        net.minecraft.client.world.ClientWorld world = net.minecraft.client.MinecraftClient.getInstance().world;
        if (world == null) return;
        net.minecraft.block.BlockState state = world.getBlockState(speakerPos);
        if (!state.contains(net.minecraft.state.property.Properties.HORIZONTAL_FACING)) return;

        net.minecraft.util.math.Direction facing = state.get(net.minecraft.state.property.Properties.HORIZONTAL_FACING);
        net.minecraft.util.math.Vec3i vec = facing.getVector();
        float tiltRad = (float) Math.toRadians(tiltDeg);
        float cosT = (float) Math.cos(tiltRad);
        float sinT = (float) Math.sin(tiltRad);
        float dirX = vec.getX() * cosT;
        float dirY = sinT;
        float dirZ = vec.getZ() * cosT;

        for (StreamSource ss : getActiveSession().getStreamSources()) {
            if (ss.pos.equals(speakerPos) && !TYPE_SUB.equals(ss.speakerType)) {
                alSource3f(ss.sourceId, AL_DIRECTION, dirX, dirY, dirZ);
            }
        }
    }

    /**
     * Fetch Total Duration in Seconds for the currently playing track.
     */
    public double getTotalPlaybackDuration() {
        if (getActiveSession() == null
                || !getActiveSession().isPlaying()
                || getActiveSession().getStreamBuffers().isEmpty()) return 0.0;
        AudioStreamBuffer buf =
                getActiveSession().getStreamBuffers().values().iterator().next();
        return buf != null ? buf.getTotalDurationSeconds() : 0.0;
    }

    /**
     * Fetch Current Playback Time in Seconds.
     */
    public double getCurrentPlaybackTime() {
        if (getActiveSession() == null
                || !getActiveSession().isPlaying()
                || getActiveSession().getStreamStartTime() == 0) return 0.0;
        long now = System.nanoTime();
        double timeSinceStart = (now - getActiveSession().getStreamStartTime()) / 1_000_000_000.0;
        return timeSinceStart;
    }

    /**
     * Globally Seek all playing channels to the designated timestamp.
     * Alters the base physical stream clock so all nodes fast-forward
     * homogeneously.
     */
    public synchronized void seek(double timeSeconds) {
        if (getActiveSession() == null || !getActiveSession().isPlaying()) return;

        // Clamp to bounds
        double totalDuration = getTotalPlaybackDuration();
        if (timeSeconds < 0) timeSeconds = 0;
        if (totalDuration > 0 && timeSeconds > totalDuration) timeSeconds = totalDuration;

        // Echo Debouncing (Client-Side Prediction Defense)
        // If a local client jumped the track manually, the server echoes the packet
        // 200ms later.
        // We do not want to "jump back" 200ms to the exact same marker and cause a
        // track stutter.
        if (Math.abs(getCurrentPlaybackTime() - timeSeconds) < 0.5) {
            return; // Already within the target window realistically
        }

        // ATOMIC SEEK: Block the audio thread from feeding OpenAL sources
        // while we update the global clock and re-align every speaker.
        // Without this, processAudioBackground() can fire mid-seek and cause
        // underrun recovery to snap outputCursor to the wrong position,
        // resulting in 3-4 speakers playing asynchronously.
        getActiveSession().setSeeking(true);
        try {
            // Nothing to seek if all sources were cleaned up (song ended naturally)
            if (getActiveSession().getStreamSources().isEmpty()) return;

            // Shift absolute temporal timeline baseline
            long now = System.nanoTime();

            // If the game is paused, adjust the pause tracker so it doesn't double-cancel
            // the seek on resume
            if (getActiveSession().isPaused()) {
                getActiveSession().setPauseStartTimestamp(now);
            }

            getActiveSession().setStreamStartTime(now - (long) (timeSeconds * 1_000_000_000.0));

            // Force raw JLayer decoder index jumps
            for (AudioStreamBuffer buffer :
                    getActiveSession().getStreamBuffers().values()) {
                // Buffer up to 0.1s INTO THE PAST to cushion StreamSource physics delays.
                // When speakers simulate spatial distance, they read slightly backwards in the
                // ring buffer.
                // If the buffer starts exactly at timeSeconds, spatial delay forces a read of 0
                // (causing a crackle).
                buffer.seekToTime(timeSeconds - 0.1);

                // Pre-fill next half-second buffer window so StreamSource AL generators don't
                // read zeros
                buffer.syncToTime(timeSeconds + BUFFER_LOOKAHEAD);
            }

            // Broadcast snap offsets into ALL actively playing physical speakers locally
            // using atomic AL Source commands
            java.nio.IntBuffer sourceIds = org.lwjgl.BufferUtils.createIntBuffer(
                    getActiveSession().getStreamSources().size());
            for (StreamSource source : getActiveSession().getStreamSources()) {
                source.seekToTime(timeSeconds); // Aligns playhead and hardware queue internally (does NOT call
                // alSourcePlay)
                sourceIds.put(source.sourceId);
            }
            sourceIds.flip();
            org.lwjgl.openal.AL10.alSourcePlayv(sourceIds); // ATOMIC HARDWARE START: NO SPEAKER PHASE STAGGER
        } finally {
            getActiveSession().setSeeking(false); // Resume audio thread feeding
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // MULTIPLAYER SYNC METHODS
    // Called by S2C packet handlers to update running sessions remotely.
    // ═══════════════════════════════════════════════════════════════════════

    public void seekForSession(java.util.UUID sessionUUID, float targetTime) {
        PlaybackSession session = sessions.get(sessionUUID);
        if (session != null) {
            // Note: currently seek logic alters the base time of the stream.
            // If true multi-session seeking is needed, it should be implemented in PlaybackSession.
            // For now, if the session is active, we just call the global seek.
            if (sessionUUID.equals(activeSessionId)) seek(targetTime);
        }
    }

    public void setEqDbForSession(java.util.UUID sessionUUID, String speakerType, int band, float db) {
        PlaybackSession session = sessions.get(sessionUUID);
        if (session != null) session.setEqDb(speakerType, band, db);
    }

    public void setEqQForSession(java.util.UUID sessionUUID, String speakerType, int band, float q) {
        PlaybackSession session = sessions.get(sessionUUID);
        if (session != null) session.setEqQ(speakerType, band, q);
    }

    public void setMixerGainForSession(java.util.UUID sessionUUID, String speakerType, float gain) {
        PlaybackSession session = sessions.get(sessionUUID);
        if (session != null) session.setMixerGain(speakerType, gain);
    }

    public void updateInputGainForSession(java.util.UUID sessionUUID, float gain) {
        PlaybackSession session = sessions.get(sessionUUID);
        if (session != null) {
            for (StreamSource source : session.getStreamSources()) {
                source.inputGain = gain;
            }
        }
    }

    public void updatePowerForSession(java.util.UUID sessionUUID, float power) {
        PlaybackSession session = sessions.get(sessionUUID);
        if (session != null) {
            for (StreamSource source : session.getStreamSources()) {
                source.power = power;
            }
        }
    }
}
