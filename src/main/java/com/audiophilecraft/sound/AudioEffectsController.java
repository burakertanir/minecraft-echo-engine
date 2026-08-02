package com.audiophilecraft.sound;

import static org.lwjgl.openal.AL10.*;
import static org.lwjgl.openal.EXTEfx.*;

import java.util.List;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;

/** Owns shared OpenAL effects, venue reverb state, and listener-centric acoustic updates. */
final class AudioEffectsController {
    private static final float[] ZERO_PAN = {0f, 0f, 0f};
    private static final int PRIMARY_ROOM_BUS = 0;
    private static final int SECONDARY_ROOM_BUS = 1;
    private static final int LISTENER_REFLECTION_RANGE_BLOCKS = 30;
    private static final float ECHO_PROXIMITY_KNEE_START = 0.65f;
    private static final float ECHO_PROXIMITY_LIMIT = 0.78f;
    private static final float ECHO_GAIN_ATTACK = 0.30f;
    private static final float ECHO_GAIN_RELEASE = 0.20f;
    private static final int[][] LISTENER_SCAN_DIRECTIONS = {
        {1, 0, 0}, {-1, 0, 0},
        {0, 1, 0}, {0, -1, 0},
        {0, 0, 1}, {0, 0, -1}
    };

    private final AdvancedAcousticScanner acousticScanner = new AdvancedAcousticScanner();
    private final RoomReverbBus[] roomBuses = {new RoomReverbBus(), new RoomReverbBus()};
    private final AcousticProfile[] roomBusProfiles = new AcousticProfile[2];
    private final float[] roomBusMixGains = {1.0f, 1.0f};
    private final float[] smoothedRoomBusOcclusion = {1.0f, 1.0f};
    private final BlockPos.Mutable listenerScanPosition = new BlockPos.Mutable();

    private int slapbackEffectId;
    private int slapbackAuxSlotId;
    private boolean initialized;
    private AdvancedAcousticScanner.VenuePreset venuePreset;
    private boolean venuePresetApplied;
    private volatile float currentReflectionGainMultiplier = 1.0f;
    private volatile float currentReflectionDelay = -1.0f;
    private volatile float currentSlapbackGain;
    private boolean slapbackGainInitialized;
    private AdvancedAcousticScanner.VenueDescriptor storedVenueDescriptor;
    private Vec3d storedVenueProbePos;
    private long lastConfigGeneration;
    private boolean effectSlotsMuted;

    int getAuxSlotId() {
        return getRoomAuxSlotId(PRIMARY_ROOM_BUS);
    }

    int getRoomAuxSlotId(int busIndex) {
        if (busIndex < 0 || busIndex >= roomBuses.length) return 0;
        return roomBuses[busIndex].auxSlotId();
    }

    boolean isRoomBusAvailable(int busIndex) {
        return busIndex >= 0 && busIndex < roomBuses.length && roomBuses[busIndex].isAvailable();
    }

    void setRoomBusMixGain(int busIndex, float gain) {
        if (busIndex < 0 || busIndex >= roomBuses.length) return;
        roomBusMixGains[busIndex] = Math.max(0.0f, Math.min(1.0f, gain));
        roomBuses[busIndex].setSlotGain(effectSlotsMuted ? 0.0f : roomBusMixGains[busIndex]);
    }

    void assignRoomBusProfile(int busIndex, AcousticProfile profile) {
        if (busIndex < 0 || busIndex >= roomBuses.length || profile == null) return;
        AcousticProfile currentProfile = new AcousticProfile(
                profile.descriptor(),
                acousticScanner.descriptorToPreset(profile.descriptor(), profile.probePosition()));
        roomBusProfiles[busIndex] = currentProfile;
        if (roomBuses[busIndex].isAvailable()) {
            applyVenueReverb(busIndex, currentProfile.preset());
        }
    }

    int getSlapbackAuxSlotId() {
        return slapbackAuxSlotId;
    }

    boolean isInitialized() {
        return initialized;
    }

    boolean nativeResourcesValid() {
        if (!initialized || !roomBuses[PRIMARY_ROOM_BUS].nativeResourcesValid()) return false;
        if ((slapbackEffectId == 0) != (slapbackAuxSlotId == 0)) return false;
        if (slapbackEffectId != 0 && (!alIsEffect(slapbackEffectId) || !alIsAuxiliaryEffectSlot(slapbackAuxSlotId))) {
            return false;
        }
        return !roomBuses[SECONDARY_ROOM_BUS].isAvailable() || roomBuses[SECONDARY_ROOM_BUS].nativeResourcesValid();
    }

    float getSlapbackGain() {
        return currentSlapbackGain;
    }

    AdvancedAcousticScanner.VenuePreset getVenuePreset() {
        return venuePreset;
    }

    AdvancedAcousticScanner.VenueDescriptor getStoredVenueDescriptor() {
        return storedVenueDescriptor;
    }

    void initialize() {
        if (initialized) return;

        try {
            alDistanceModel(AL_NONE);
            if (!roomBuses[PRIMARY_ROOM_BUS].initialize()) {
                System.err.println("AudioEngine: Failed to create primary room reverb bus");
                cleanupNativeEffects();
                return;
            }

            initializeSlapback();
            if (slapbackAuxSlotId == 0) {
                System.err.println("AudioEngine: Slapback effect unavailable");
            }

            if (!roomBuses[SECONDARY_ROOM_BUS].initialize()) {
                System.err.println("AudioEngine: Secondary room reverb unavailable; using one room bus");
            }

            initialized = true;
        } catch (Exception e) {
            System.err.println("AudioEngine: EFX init failed: " + e.getMessage());
            cleanupNativeEffects();
        }
    }

    private void initializeSlapback() {
        slapbackEffectId = alGenEffects();
        if (alGetError() != AL_NO_ERROR) {
            slapbackEffectId = 0;
            return;
        }
        alEffecti(slapbackEffectId, AL_EFFECT_TYPE, AL_EFFECT_ECHO);
        alEffectf(slapbackEffectId, AL_ECHO_DELAY, 0.1f);
        alEffectf(slapbackEffectId, AL_ECHO_LRDELAY, 0.1f);
        alEffectf(slapbackEffectId, AL_ECHO_DAMPING, 0.7f);
        alEffectf(slapbackEffectId, AL_ECHO_FEEDBACK, 0.3f);
        alEffectf(slapbackEffectId, AL_ECHO_SPREAD, -0.5f);
        if (alGetError() != AL_NO_ERROR) {
            alDeleteEffects(slapbackEffectId);
            slapbackEffectId = 0;
            return;
        }

        slapbackAuxSlotId = alGenAuxiliaryEffectSlots();
        if (alGetError() != AL_NO_ERROR) {
            alDeleteEffects(slapbackEffectId);
            slapbackEffectId = 0;
            slapbackAuxSlotId = 0;
            return;
        }
        alAuxiliaryEffectSloti(slapbackAuxSlotId, AL_EFFECTSLOT_EFFECT, slapbackEffectId);
        if (alGetError() != AL_NO_ERROR) {
            alDeleteAuxiliaryEffectSlots(slapbackAuxSlotId);
            alDeleteEffects(slapbackEffectId);
            slapbackAuxSlotId = 0;
            slapbackEffectId = 0;
        }
    }

    void ensureVenueReverb() {
        if (venuePreset == null) return;
        if (!roomBuses[PRIMARY_ROOM_BUS].isAvailable()) return;

        long currentGeneration = com.audiophilecraft.config.LiveTuningConfig.getReloadGeneration();
        if (currentGeneration != lastConfigGeneration && storedVenueDescriptor != null && storedVenueProbePos != null) {
            venuePreset = acousticScanner.descriptorToPreset(storedVenueDescriptor, storedVenueProbePos);
            for (int busIndex = 0; busIndex < roomBusProfiles.length; busIndex++) {
                AcousticProfile profile = roomBusProfiles[busIndex];
                if (profile == null) continue;
                AdvancedAcousticScanner.VenuePreset refreshedPreset =
                        acousticScanner.descriptorToPreset(profile.descriptor(), profile.probePosition());
                roomBusProfiles[busIndex] = new AcousticProfile(profile.descriptor(), refreshedPreset);
            }
            lastConfigGeneration = currentGeneration;
            // Reattaching the echo effect resets its delay buffer, so update it only on reload.
            applySlapbackConfig();
        }

        for (int busIndex = 0; busIndex < roomBuses.length; busIndex++) {
            AcousticProfile profile = roomBusProfiles[busIndex];
            if (profile != null && roomBuses[busIndex].isAvailable()) {
                applyVenueReverb(busIndex, profile.preset());
            }
        }
        venuePresetApplied = true;
    }

    private void applyVenueReverb(int busIndex, AdvancedAcousticScanner.VenuePreset preset) {
        RoomReverbBus roomBus = roomBuses[busIndex];
        com.audiophilecraft.config.LiveTuningConfig config = com.audiophilecraft.config.LiveTuningConfig.get();
        float decayTime = preset.decayTime * config.reverb_decayMultiplier;
        float gain = preset.gain * config.reverb_gainMultiplier;
        float gainHF = preset.gainHF * config.reverb_gainHFMultiplier;
        float reflectionGain = preset.reflectionsGain * config.reverb_reflGainMultiplier;
        reflectionGain *= currentReflectionGainMultiplier;
        float lateGain = preset.lateReverbGain * config.reverb_lateGainMultiplier;
        float echoTime = preset.echoTime * config.reverb_distantEchoTimeMultiplier;
        float echoDepth = preset.echoDepth * config.reverb_distantEchoDepthMultiplier;
        float density = config.reverb_densityOverride >= 0 ? config.reverb_densityOverride : preset.density;
        float diffusion = config.reverb_diffusionOverride >= 0 ? config.reverb_diffusionOverride : preset.diffusion;

        float occlusion = smoothedRoomBusOcclusion[busIndex];
        float occlusionGain = config.masterOcc_gainFloor + (1.0f - config.masterOcc_gainFloor) * occlusion;
        float occlusionGainHF = (float) Math.pow(occlusion, config.masterOcc_hfExponent);
        gain *= occlusionGain;
        gainHF *= occlusionGainHF;

        decayTime = Math.max(0.1f, Math.min(20.0f, decayTime));
        gain = Math.max(0.0f, Math.min(1.0f, gain));
        gainHF = Math.max(0.0f, Math.min(1.0f, gainHF));
        reflectionGain = Math.max(0.0f, Math.min(3.16f, reflectionGain));
        lateGain = Math.max(0.0f, Math.min(10.0f, lateGain));
        echoTime = Math.max(0.075f, Math.min(0.25f, echoTime));
        echoDepth = Math.max(0.0f, Math.min(1.0f, echoDepth));
        density = Math.max(0.0f, Math.min(1.0f, density));
        diffusion = Math.max(0.0f, Math.min(1.0f, diffusion));

        roomBus.setFloat(AL_EAXREVERB_DECAY_TIME, AL_REVERB_DECAY_TIME, decayTime);
        roomBus.setFloat(AL_EAXREVERB_DECAY_HFRATIO, AL_REVERB_DECAY_HFRATIO, preset.decayHFRatio);
        roomBus.setFloat(AL_EAXREVERB_DECAY_LFRATIO, -1, preset.decayLFRatio);
        roomBus.setInt(AL_EAXREVERB_DECAY_HFLIMIT, AL_REVERB_DECAY_HFLIMIT, preset.decayHFLimit ? 1 : 0);
        float reflectionDelay = currentReflectionDelay >= 0.0f ? currentReflectionDelay : preset.reflectionsDelay;
        roomBus.setFloat(AL_EAXREVERB_REFLECTIONS_GAIN, AL_REVERB_REFLECTIONS_GAIN, reflectionGain);
        roomBus.setFloat(AL_EAXREVERB_REFLECTIONS_DELAY, AL_REVERB_REFLECTIONS_DELAY, reflectionDelay);
        roomBus.setPan(AL_EAXREVERB_REFLECTIONS_PAN, ZERO_PAN);
        roomBus.setFloat(AL_EAXREVERB_LATE_REVERB_GAIN, AL_REVERB_LATE_REVERB_GAIN, lateGain);
        roomBus.setFloat(AL_EAXREVERB_LATE_REVERB_DELAY, AL_REVERB_LATE_REVERB_DELAY, preset.lateReverbDelay);
        roomBus.setPan(AL_EAXREVERB_LATE_REVERB_PAN, ZERO_PAN);
        roomBus.setFloat(AL_EAXREVERB_ECHO_TIME, -1, echoTime);
        roomBus.setFloat(AL_EAXREVERB_ECHO_DEPTH, -1, echoDepth);
        roomBus.setFloat(AL_EAXREVERB_DENSITY, AL_REVERB_DENSITY, density);
        roomBus.setFloat(AL_EAXREVERB_DIFFUSION, AL_REVERB_DIFFUSION, diffusion);
        roomBus.setFloat(AL_EAXREVERB_GAIN, AL_REVERB_GAIN, gain);
        roomBus.setFloat(AL_EAXREVERB_GAINHF, AL_REVERB_GAINHF, gainHF);
        roomBus.setFloat(AL_EAXREVERB_GAINLF, -1, preset.gainLF);
        roomBus.setFloat(
                AL_EAXREVERB_AIR_ABSORPTION_GAINHF, AL_REVERB_AIR_ABSORPTION_GAINHF, preset.airAbsorptionGainHF);
        roomBus.attachEffect();
    }

    private void applySlapbackConfig() {
        if (slapbackEffectId == 0 || slapbackAuxSlotId == 0) return;
        com.audiophilecraft.config.LiveTuningConfig config = com.audiophilecraft.config.LiveTuningConfig.get();
        alEffectf(slapbackEffectId, AL_ECHO_DELAY, config.echo_delay);
        alEffectf(slapbackEffectId, AL_ECHO_LRDELAY, config.echo_delay);
        alEffectf(slapbackEffectId, AL_ECHO_DAMPING, config.echo_damping);
        alEffectf(slapbackEffectId, AL_ECHO_FEEDBACK, config.echo_feedback);
        alEffectf(slapbackEffectId, AL_ECHO_SPREAD, config.echo_spread);
        alAuxiliaryEffectSloti(slapbackAuxSlotId, AL_EFFECTSLOT_EFFECT, slapbackEffectId);
        // Dynamic echo volume is controlled by each StreamSource; the shared slot stays fixed.
        alAuxiliaryEffectSlotf(slapbackAuxSlotId, AL_EFFECTSLOT_GAIN, 1.0f);
    }

    void updateListenerAcoustics(World world, Vec3d listenerPosition) {
        boolean reflectionsAvailable = venuePreset != null && roomBuses[PRIMARY_ROOM_BUS].isAvailable();
        boolean slapbackAvailable = slapbackEffectId != 0 && slapbackAuxSlotId != 0;
        if (world == null || listenerPosition == null || (!reflectionsAvailable && !slapbackAvailable)) return;

        float minimumDistance = LISTENER_REFLECTION_RANGE_BLOCKS;
        float proximityEnergySum = 0.0f;
        float weightedAbsorptionSum = 0.0f;
        float absorptionWeight = 0.0f;

        for (int[] direction : LISTENER_SCAN_DIRECTIONS) {
            float hitDistance = LISTENER_REFLECTION_RANGE_BLOCKS;
            float absorption = 0.0f;
            for (int step = 1; step <= LISTENER_REFLECTION_RANGE_BLOCKS; step++) {
                listenerScanPosition.set(
                        (int) Math.floor(listenerPosition.x + direction[0] * step),
                        (int) Math.floor(listenerPosition.y + direction[1] * step),
                        (int) Math.floor(listenerPosition.z + direction[2] * step));
                net.minecraft.block.BlockState state = world.getBlockState(listenerScanPosition);
                if (state.isSolidBlock(world, listenerScanPosition)) {
                    hitDistance = step;
                    if (slapbackAvailable) {
                        absorption = AdvancedAcousticScanner.getAbsorptionForReflection(state.getBlock());
                    }
                    break;
                }
            }
            if (hitDistance < minimumDistance) minimumDistance = hitDistance;

            float proximity = Math.max(0.0f, 1.0f - hitDistance / (float) LISTENER_REFLECTION_RANGE_BLOCKS);
            float proximityEnergy = proximity * proximity;
            proximityEnergySum += proximityEnergy;
            weightedAbsorptionSum += absorption * proximityEnergy;
            absorptionWeight += proximityEnergy;
        }

        if (reflectionsAvailable) {
            updateListenerReflections(minimumDistance);
        }
        if (slapbackAvailable) {
            float averageProximity = (float) Math.sqrt(proximityEnergySum / LISTENER_SCAN_DIRECTIONS.length);
            float averageAbsorption = absorptionWeight > 0.0001f ? weightedAbsorptionSum / absorptionWeight : 0.0f;
            updateListenerSlapback(averageProximity, averageAbsorption);
        }
    }

    private void updateListenerReflections(float minimumDistance) {
        float reflectionDelay = Math.max(0.001f, Math.min(minimumDistance * 2.0f / 2000.0f, 0.3f));
        float distanceFactor =
                Math.max(0.0f, Math.min(1.0f, 1.0f - minimumDistance / LISTENER_REFLECTION_RANGE_BLOCKS));
        currentReflectionGainMultiplier = 1.0f + distanceFactor * 0.5f;
        currentReflectionDelay = reflectionDelay;
    }

    private void updateListenerSlapback(float averageProximity, float averageAbsorption) {
        com.audiophilecraft.config.LiveTuningConfig config = com.audiophilecraft.config.LiveTuningConfig.get();
        float compressedProximity = compressEchoProximity(averageProximity);
        float gain = config.echo_baseGain + compressedProximity * (config.echo_maxGain - config.echo_baseGain);
        if (averageAbsorption > 0.30f) gain *= 1.0f - averageAbsorption;

        if (!slapbackGainInitialized) {
            currentSlapbackGain = gain;
            slapbackGainInitialized = true;
        } else {
            float smoothing = gain > currentSlapbackGain ? ECHO_GAIN_ATTACK : ECHO_GAIN_RELEASE;
            currentSlapbackGain += (gain - currentSlapbackGain) * smoothing;
        }
    }

    private static float compressEchoProximity(float proximity) {
        float clamped = Math.max(0.0f, Math.min(1.0f, proximity));
        if (clamped <= ECHO_PROXIMITY_KNEE_START) return clamped;

        float kneeRange = 1.0f - ECHO_PROXIMITY_KNEE_START;
        float progress = (clamped - ECHO_PROXIMITY_KNEE_START) / kneeRange;
        float smoothProgress = progress * progress * (3.0f - 2.0f * progress);
        return ECHO_PROXIMITY_KNEE_START + (ECHO_PROXIMITY_LIMIT - ECHO_PROXIMITY_KNEE_START) * smoothProgress;
    }

    void updateRoomBusOcclusion(float[] targetOcclusion) {
        if (!roomBuses[PRIMARY_ROOM_BUS].isAvailable() || venuePreset == null) return;
        com.audiophilecraft.config.LiveTuningConfig config = com.audiophilecraft.config.LiveTuningConfig.get();
        for (int busIndex = 0; busIndex < smoothedRoomBusOcclusion.length; busIndex++) {
            float target = targetOcclusion != null && busIndex < targetOcclusion.length
                    ? Math.max(0.0f, Math.min(1.0f, targetOcclusion[busIndex]))
                    : 1.0f;
            float current = smoothedRoomBusOcclusion[busIndex];
            float lerpRate = target < current ? config.masterOcc_lerpIn : config.masterOcc_lerpOut;
            smoothedRoomBusOcclusion[busIndex] += (target - current) * lerpRate;
        }
    }

    void setGamePaused(boolean paused) {
        for (RoomReverbBus roomBus : roomBuses) {
            roomBus.setPaused(paused);
        }
        if (slapbackAuxSlotId != 0) {
            alAuxiliaryEffectSloti(slapbackAuxSlotId, AL_EFFECTSLOT_EFFECT, paused ? AL_EFFECT_NULL : slapbackEffectId);
        }
    }

    void muteEffectSlots() {
        effectSlotsMuted = true;
        for (int busIndex = 0; busIndex < roomBuses.length; busIndex++) {
            roomBuses[busIndex].setSlotGain(0.0f);
        }
        if (slapbackAuxSlotId != 0) alAuxiliaryEffectSlotf(slapbackAuxSlotId, AL_EFFECTSLOT_GAIN, 0.0f);
    }

    void resumeEffectSlots() {
        effectSlotsMuted = false;
        for (int busIndex = 0; busIndex < roomBuses.length; busIndex++) {
            roomBuses[busIndex].setSlotGain(roomBusMixGains[busIndex]);
        }
        if (slapbackAuxSlotId != 0) alAuxiliaryEffectSlotf(slapbackAuxSlotId, AL_EFFECTSLOT_GAIN, 1.0f);
    }

    void clearVenueState() {
        venuePreset = null;
        venuePresetApplied = false;
        storedVenueDescriptor = null;
        storedVenueProbePos = null;
        currentReflectionGainMultiplier = 1.0f;
        currentReflectionDelay = -1.0f;
        java.util.Arrays.fill(roomBusProfiles, null);
        java.util.Arrays.fill(smoothedRoomBusOcclusion, 1.0f);
    }

    void resetVenueState(List<BlockPos> speakers) {
        AdvancedAcousticScanner.resetDebugState(speakers);
        clearVenueState();
        com.audiophilecraft.client.screen.PointCloudRenderer.invalidateCache();
        while (alGetError() != AL_NO_ERROR) {
            // Drain stale OpenAL errors before lazy EFX initialization.
        }
        initialize();
    }

    void clearVenuePreset() {
        venuePreset = null;
        venuePresetApplied = false;
    }

    AcousticSceneScanResult scanVenue(World world, List<Vec3d> clusterCenters) {
        return acousticScanner.scanEmitterGroups(world, clusterCenters);
    }

    void applyScannedVenuePreset(AcousticScanResult result) {
        AcousticProfile profile = result.profile();
        venuePreset = profile.preset();
        storedVenueDescriptor = profile.descriptor();
        storedVenueProbePos = profile.probePosition();
        AdvancedAcousticScanner.publishDebugResult(result);
        lastConfigGeneration = com.audiophilecraft.config.LiveTuningConfig.getReloadGeneration();
        for (int busIndex = 0; busIndex < roomBuses.length; busIndex++) {
            assignRoomBusProfile(busIndex, profile);
        }
        applySlapbackConfig();
    }

    void cleanup() {
        cleanupNativeEffects();
        currentSlapbackGain = 0.0f;
        slapbackGainInitialized = false;
        initialized = false;
        effectSlotsMuted = false;
    }

    void abandonNativeResources() {
        slapbackEffectId = 0;
        slapbackAuxSlotId = 0;
        for (RoomReverbBus roomBus : roomBuses) {
            roomBus.abandonNativeResources();
        }
        clearVenueState();
        currentSlapbackGain = 0.0f;
        slapbackGainInitialized = false;
        initialized = false;
        effectSlotsMuted = false;
    }

    private void cleanupNativeEffects() {
        if (slapbackAuxSlotId != 0) {
            alAuxiliaryEffectSloti(slapbackAuxSlotId, AL_EFFECTSLOT_EFFECT, AL_EFFECT_NULL);
            alDeleteAuxiliaryEffectSlots(slapbackAuxSlotId);
            slapbackAuxSlotId = 0;
        }
        if (slapbackEffectId != 0) {
            alDeleteEffects(slapbackEffectId);
            slapbackEffectId = 0;
        }
        for (RoomReverbBus roomBus : roomBuses) {
            roomBus.cleanup();
        }
    }
}
