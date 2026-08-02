package com.audiophilecraft.sound;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;

/**
 * Coordinates venue probe scans and exposes the resulting acoustic profile.
 * Ray casting, material properties, and reverb preset calculation live in
 * dedicated collaborators while this class preserves the public scanner API.
 */
public class AdvancedAcousticScanner {

    public static final int RAY_COUNT = AcousticRayScanner.RAY_COUNT;
    private final AcousticRayScanner rayScanner = new AcousticRayScanner();
    private final VenuePresetCalculator presetCalculator = new VenuePresetCalculator();

    public static float getAbsorptionForReflection(Block block) {
        return AcousticMaterialTable.getAbsorption(block);
    }

    public static float getBlockTransmission(BlockState state, boolean isSubwoofer) {
        return AcousticMaterialTable.getBlockTransmission(state, isSubwoofer);
    }

    // ═══════════════════════════════════════════════════════════════════
    // VENUE-LOCKED REVERB SYSTEM
    // ═══════════════════════════════════════════════════════════════════

    /**
     * Immutable reverb preset calculated at a venue probe position.
     * Once created, these parameters never change during playback.
     */
    public static class VenuePreset {
        public final float decayTime;
        public final float gain;
        public final float gainHF;
        public final float gainLF;
        public final float reflectionsGain;
        public final float reflectionsDelay;
        public final float lateReverbGain;
        public final float lateReverbDelay;
        public final float echoTime;
        public final float echoDepth;
        public final float density;
        public final float diffusion;
        public final float decayHFRatio;
        public final float decayLFRatio;
        public final float airAbsorptionGainHF;
        public final boolean decayHFLimit;
        public final float enclosure;
        public final Vec3d probePosition;
        public final String tierName;

        public VenuePreset(
                float decayTime,
                float gain,
                float gainHF,
                float gainLF,
                float reflectionsGain,
                float reflectionsDelay,
                float lateReverbGain,
                float lateReverbDelay,
                float echoTime,
                float echoDepth,
                float density,
                float diffusion,
                float decayHFRatio,
                float decayLFRatio,
                float airAbsorptionGainHF,
                boolean decayHFLimit,
                float enclosure,
                Vec3d probePosition,
                String tierName) {
            this.decayTime = decayTime;
            this.gain = gain;
            this.gainHF = gainHF;
            this.gainLF = gainLF;
            this.reflectionsGain = reflectionsGain;
            this.reflectionsDelay = reflectionsDelay;
            this.lateReverbGain = lateReverbGain;
            this.lateReverbDelay = lateReverbDelay;
            this.echoTime = echoTime;
            this.echoDepth = echoDepth;
            this.density = density;
            this.diffusion = diffusion;
            this.decayHFRatio = decayHFRatio;
            this.decayLFRatio = decayLFRatio;
            this.airAbsorptionGainHF = airAbsorptionGainHF;
            this.decayHFLimit = decayHFLimit;
            this.enclosure = enclosure;
            this.probePosition = probePosition;
            this.tierName = tierName;
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    // MULTI-PROBE VENUE SCANNING SYSTEM
    // ═══════════════════════════════════════════════════════════════════

    /**
     * Raw acoustic metrics from a single probe's Spherical Fibonacci scan.
     */
    public static class ProbeResult {
        public final float nearHitRatio; // Rays ≤5 blocks / RAY_COUNT
        public final float midHitRatio; // Rays 6-15 blocks / RAY_COUNT
        public final float farHitRatio; // Rays 16+ blocks / RAY_COUNT
        public final float skyEscapeRatio; // Rays hitting nothing / RAY_COUNT
        public final float avgAbsorption;
        public final float meanDist;
        public final float variance; // Std dev of ray distances
        public final float enclosure; // wallsHit / RAY_COUNT
        public final float trueVolume; // Monte Carlo true geometric volume
        public final float trueSurfaceArea; // Monte Carlo true geometric surface area
        public final float[] distances; // Raw N-ray distances
        public final float[] absorptions; // Raw N-ray absorptions

        public ProbeResult(
                float nearHitRatio,
                float midHitRatio,
                float farHitRatio,
                float skyEscapeRatio,
                float avgAbsorption,
                float meanDist,
                float variance,
                float enclosure,
                float trueVolume,
                float trueSurfaceArea,
                float[] distances,
                float[] absorptions) {
            this.nearHitRatio = nearHitRatio;
            this.midHitRatio = midHitRatio;
            this.farHitRatio = farHitRatio;
            this.skyEscapeRatio = skyEscapeRatio;
            this.avgAbsorption = avgAbsorption;
            this.meanDist = meanDist;
            this.variance = variance;
            this.enclosure = enclosure;
            this.trueVolume = trueVolume;
            this.trueSurfaceArea = trueSurfaceArea;
            this.distances = distances;
            this.absorptions = absorptions;
        }
    }

    /**
     * Merged venue-level acoustic descriptor from multiple probes.
     */
    public static class VenueDescriptor {
        public final float enclosure; // 0.0 = open air, 1.0 = fully sealed
        public final float scale; // Effective room "radius" (meters)
        public final float reflectivity; // 1.0 - avgAbsorption
        public final float diffusion; // High variance = more diffuse
        public final float openness; // Sky escape ratio
        public final float earlyDensity; // Near + mid hit density
        public final float latePotential; // Far hit + scale contribution
        public final float avgAbsorption; // For direct use in Sabine/HF/LF
        public final float trueVolume; // True Monte Carlo Volume
        public final float trueSurfaceArea; // True Monte Carlo Surface Area

        public VenueDescriptor(
                float enclosure,
                float scale,
                float reflectivity,
                float diffusion,
                float openness,
                float earlyDensity,
                float latePotential,
                float avgAbsorption,
                float trueVolume,
                float trueSurfaceArea) {
            this.enclosure = enclosure;
            this.scale = scale;
            this.reflectivity = reflectivity;
            this.diffusion = diffusion;
            this.openness = openness;
            this.earlyDensity = earlyDensity;
            this.latePotential = latePotential;
            this.avgAbsorption = avgAbsorption;
            this.trueVolume = trueVolume;
            this.trueSurfaceArea = trueSurfaceArea;
        }
    }

    public ProbeResult scanProbe(World world, Vec3d probePos, List<Vec3d> outPointCloud) {
        return rayScanner.scan(world, probePos, outPointCloud);
    }

    private static volatile List<Vec3d> lastPointCloud = List.of();
    private static volatile Set<BlockPos> lastVenueBlocks = Set.of();
    private static volatile List<BlockPos> lastSpeakers = List.of();
    private static volatile AcousticProfile lastDebugProfile;

    public static List<Vec3d> getLastPointCloud() {
        return lastPointCloud;
    }

    public static Set<BlockPos> getLastVenueBlocks() {
        return lastVenueBlocks;
    }

    public static List<BlockPos> getLastSpeakers() {
        return lastSpeakers;
    }

    public static VenuePreset getLastDebugPreset() {
        AcousticProfile profile = lastDebugProfile;
        return profile != null ? profile.preset() : null;
    }

    public static VenueDescriptor getLastDebugDescriptor() {
        AcousticProfile profile = lastDebugProfile;
        return profile != null ? profile.descriptor() : null;
    }

    public static void publishDebugResult(AcousticScanResult result) {
        if (result == null) {
            clearDebugGeometry();
            return;
        }
        lastPointCloud = result.pointCloud();
        lastVenueBlocks = result.venueBlocks();
        lastDebugProfile = result.profile();
    }

    public static void publishDebugResult(AcousticScanResult result, List<BlockPos> speakers) {
        publishDebugResult(result);
        lastSpeakers = speakers == null ? List.of() : List.copyOf(speakers);
    }

    public static void resetDebugState(List<BlockPos> speakers) {
        lastPointCloud = List.of();
        lastVenueBlocks = Set.of();
        lastSpeakers = speakers == null ? List.of() : List.copyOf(speakers);
        lastDebugProfile = null;
    }

    public static void clearDebugGeometry() {
        lastPointCloud = List.of();
        lastVenueBlocks = Set.of();
        lastDebugProfile = null;
    }

    public VenueDescriptor mergeProbes(List<ProbeResult> probes) {
        if (probes.isEmpty()) return null;

        float totalWeight = probes.size();
        float wEnclosure = 0, wAbsorption = 0;
        float wNear = 0, wMid = 0, wFar = 0, wSky = 0, wVariance = 0;
        float wScale = 0, wVolume = 0, wSurfaceArea = 0;
        float maxLatePotential = 0;

        for (ProbeResult p : probes) {
            wEnclosure += p.enclosure;
            wAbsorption += p.avgAbsorption;
            wNear += p.nearHitRatio;
            wMid += p.midHitRatio;
            wFar += p.farHitRatio;
            wSky += p.skyEscapeRatio;
            wVariance += p.variance;
            wScale += p.meanDist;
            wVolume += p.trueVolume;
            wSurfaceArea += p.trueSurfaceArea;

            float lp = p.farHitRatio + (p.meanDist / 40.0f);
            if (lp > maxLatePotential) {
                maxLatePotential = lp;
            }
        }

        float enclosure = wEnclosure / totalWeight;
        float scale = wScale / totalWeight;
        float avgAbsorption = wAbsorption / totalWeight;
        float reflectivity = 1.0f - avgAbsorption;
        float openness = wSky / totalWeight;
        float earlyDensity = (wNear + wMid) / totalWeight;
        float latePotential = maxLatePotential;
        float diffusion = Math.min(1.0f, (wVariance / totalWeight) / 15.0f);
        float trueVolume = wVolume / totalWeight;
        float trueSurfaceArea = wSurfaceArea / totalWeight;

        return new VenueDescriptor(
                enclosure,
                scale,
                reflectivity,
                diffusion,
                openness,
                earlyDensity,
                latePotential,
                avgAbsorption,
                trueVolume,
                trueSurfaceArea);
    }

    /**
     * Scans emitter groups in one pass and preserves the existing combined venue
     * profile until group-aware routing is enabled.
     *
     * @param world          The Minecraft world
     * @param clusterCenters List of physical cluster centers (where sound actually
     *                       emits)
     * @return Combined and per-group acoustic profiles
     */
    public AcousticSceneScanResult scanEmitterGroups(World world, List<Vec3d> clusterCenters) {
        if (world == null || clusterCenters == null || clusterCenters.isEmpty()) return null;

        List<Vec3d> currentCloud = new ArrayList<>();
        List<ProbeResult> probes = new ArrayList<>();
        List<AcousticScanResult> groupResults = new ArrayList<>();

        // Limit the number of clusters we scan to prevent lag spikes if a user builds
        // 100 isolated speakers
        int maxClustersToScan = Math.min(clusterCenters.size(), 8);

        for (int i = 0; i < maxClustersToScan; i++) {
            Vec3d centerPos = clusterCenters.get(i);
            List<Vec3d> groupCloud = new ArrayList<>();
            ProbeResult probe = scanProbe(world, centerPos, groupCloud);
            probes.add(probe);
            currentCloud.addAll(groupCloud);

            VenueDescriptor groupDescriptor = mergeProbes(List.of(probe));
            AcousticProfile groupProfile = createProfile(groupDescriptor, centerPos, groupCloud);
            groupResults.add(createScanResult(groupProfile, groupCloud, probe));
        }

        VenueDescriptor desc = mergeProbes(probes);
        if (desc == null) return null;

        // Use the first cluster's position as the reference probe position for the
        // preset
        Vec3d referencePos = clusterCenters.get(0);
        AcousticProfile combinedProfile = createProfile(desc, referencePos, currentCloud);
        AcousticScanResult combinedResult = createScanResult(combinedProfile, currentCloud, null);
        return new AcousticSceneScanResult(combinedResult, groupResults);
    }

    public AcousticScanResult scanProfile(World world, List<Vec3d> clusterCenters) {
        AcousticSceneScanResult result = scanEmitterGroups(world, clusterCenters);
        return result == null ? null : result.combinedResult();
    }

    /** Calculates a self-contained acoustic profile for one emitter group. */
    public AcousticScanResult scanProfile(World world, Vec3d emitterCenter) {
        return scanProfile(world, List.of(emitterCenter));
    }

    private AcousticProfile createProfile(VenueDescriptor desc, Vec3d referencePos, List<Vec3d> pointCloud) {
        VenueDescriptor profileDescriptor = new VenueDescriptor(
                desc.enclosure,
                desc.scale,
                desc.reflectivity,
                desc.diffusion,
                desc.openness,
                desc.earlyDensity,
                desc.latePotential,
                desc.avgAbsorption,
                computeBoundingBoxVolume(pointCloud),
                desc.trueSurfaceArea);
        VenuePreset preset = descriptorToPreset(profileDescriptor, referencePos);
        return new AcousticProfile(profileDescriptor, preset);
    }

    private AcousticScanResult createScanResult(
            AcousticProfile profile, List<Vec3d> pointCloud, ProbeResult probeResult) {
        Set<BlockPos> hitBlocks = new HashSet<>();
        for (Vec3d point : pointCloud) {
            hitBlocks.add(
                    new BlockPos((int) Math.floor(point.x), (int) Math.floor(point.y), (int) Math.floor(point.z)));
        }
        return new AcousticScanResult(profile, pointCloud, hitBlocks, probeResult);
    }

    /**
     * Compatibility entry point for callers that only need the calculated preset.
     */
    public VenuePreset scanVenue(World world, List<Vec3d> clusterCenters) {
        AcousticScanResult result = scanProfile(world, clusterCenters);
        if (result == null) return null;
        publishDebugResult(result);
        return result.profile().preset();
    }

    /**
     * Compute a probe-position-independent volume from the aggregated point cloud.
     * Uses axis-aligned bounding box: (maxX - minX) × (maxY - minY) × (maxZ -
     * minZ).
     * The point cloud already captures all surfaces hit by rays across all probes.
     */
    private static float computeBoundingBoxVolume(List<Vec3d> pointCloud) {
        if (pointCloud == null || pointCloud.isEmpty()) return 1000.0f;

        float minX = Float.MAX_VALUE, minY = Float.MAX_VALUE, minZ = Float.MAX_VALUE;
        float maxX = -Float.MAX_VALUE, maxY = -Float.MAX_VALUE, maxZ = -Float.MAX_VALUE;

        for (Vec3d pt : pointCloud) {
            if (pt.x < minX) minX = (float) pt.x;
            if (pt.y < minY) minY = (float) pt.y;
            if (pt.z < minZ) minZ = (float) pt.z;
            if (pt.x > maxX) maxX = (float) pt.x;
            if (pt.y > maxY) maxY = (float) pt.y;
            if (pt.z > maxZ) maxZ = (float) pt.z;
        }

        float dx = Math.max(0.1f, maxX - minX);
        float dy = Math.max(0.1f, maxY - minY);
        float dz = Math.max(0.1f, maxZ - minZ);
        return dx * dy * dz;
    }

    /**
     * Convert a VenueDescriptor into an immutable VenuePreset using
     * the Sabine equation and the existing preset logic.
     */
    public VenuePreset descriptorToPreset(VenueDescriptor d, Vec3d probePos) {
        return presetCalculator.calculate(d, probePos);
    }

    /**
     * Backward-compatible wrapper: single probe scan (used if no stage direction
     * available).
     */
    public VenuePreset scanAtPosition(World world, Vec3d probePos) {
        List<Vec3d> centers = new ArrayList<>();
        centers.add(probePos);
        return scanVenue(world, centers);
    }
}
