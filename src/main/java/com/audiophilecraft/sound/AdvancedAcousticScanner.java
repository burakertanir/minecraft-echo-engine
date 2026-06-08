package com.audiophilecraft.sound;

import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;
import java.util.HashMap;
import java.util.Map;

/**
 * Advanced Acoustic Scanner using 26-Ray Temporal Slicing and the Sabine
 * Equation
 * for physics-based reverb parameter calculation.
 *
 * The scanner casts 26 rays (full 3x3x3 neighborhood minus center) from the
 * listener position, distributing the workload across 5 ticks to prevent CPU
 * spikes.
 * After all rays are updated, it computes room geometry and applies the Sabine
 * formula
 * to derive physically accurate reverb parameters.
 */
public class AdvancedAcousticScanner {

    // ─── Spherical Fibonacci Ray Directions ────────────────────────────
    // Generates highly uniform, evenly distributed points on a sphere
    public static final int RAY_COUNT = 1000;
    private static final float[][] RAY_DIRS_NORM = new float[RAY_COUNT][3];

    static {
        // Golden angle in radians
        float phi = (float) (Math.PI * (3.0 - Math.sqrt(5.0)));

        for (int i = 0; i < RAY_COUNT; i++) {
            // y goes from 1 to -1
            float y = 1 - (i / (float) (RAY_COUNT - 1)) * 2;
            float radius = (float) Math.sqrt(1 - y * y); // radius at y
            float theta = phi * i; // golden angle increment

            float x = (float) Math.cos(theta) * radius;
            float z = (float) Math.sin(theta) * radius;

            RAY_DIRS_NORM[i][0] = x;
            RAY_DIRS_NORM[i][1] = y;
            RAY_DIRS_NORM[i][2] = z;
        }
    }

    // ─── Constants ────────────────────────────────────────────────────
    private static final int MAX_RAY_DIST = 256; // Max ray distance for venue probe scans

    // ─── Material Absorption Lookup (HashMap for O(1) performance) ────────────
    private static final Map<Block, Float> ABSORPTION_MAP = new HashMap<>();
    static {
        // Highly Reflective (Hard, smooth surfaces)
        for (Block b : new Block[] {
                Blocks.STONE, Blocks.COBBLESTONE, Blocks.MOSSY_COBBLESTONE,
                Blocks.STONE_BRICKS, Blocks.MOSSY_STONE_BRICKS, Blocks.CRACKED_STONE_BRICKS,
                Blocks.CHISELED_STONE_BRICKS, Blocks.SMOOTH_STONE, Blocks.POLISHED_ANDESITE,
                Blocks.POLISHED_DIORITE, Blocks.POLISHED_GRANITE, Blocks.ANDESITE,
                Blocks.DIORITE, Blocks.GRANITE, Blocks.DEEPSLATE, Blocks.POLISHED_DEEPSLATE,
                Blocks.DEEPSLATE_BRICKS, Blocks.DEEPSLATE_TILES, Blocks.TUFF, Blocks.CALCITE,
                Blocks.DRIPSTONE_BLOCK, Blocks.POINTED_DRIPSTONE, Blocks.BEDROCK
        })
            ABSORPTION_MAP.put(b, 0.06f);
        for (Block b : new Block[] {
                Blocks.OBSIDIAN, Blocks.CRYING_OBSIDIAN, Blocks.END_STONE, Blocks.END_STONE_BRICKS,
                Blocks.IRON_BLOCK, Blocks.GOLD_BLOCK, Blocks.DIAMOND_BLOCK, Blocks.NETHERITE_BLOCK,
                Blocks.COPPER_BLOCK, Blocks.ANVIL, Blocks.ICE, Blocks.PACKED_ICE, Blocks.BLUE_ICE
        })
            ABSORPTION_MAP.put(b, 0.02f);
        for (Block b : new Block[] {
                Blocks.BRICKS, Blocks.NETHER_BRICKS, Blocks.RED_NETHER_BRICKS
        })
            ABSORPTION_MAP.put(b, 0.04f);
        for (Block b : new Block[] {
                Blocks.WHITE_CONCRETE, Blocks.LIGHT_GRAY_CONCRETE, Blocks.GRAY_CONCRETE,
                Blocks.BLACK_CONCRETE, Blocks.RED_CONCRETE, Blocks.ORANGE_CONCRETE,
                Blocks.YELLOW_CONCRETE, Blocks.LIME_CONCRETE, Blocks.GREEN_CONCRETE,
                Blocks.CYAN_CONCRETE, Blocks.LIGHT_BLUE_CONCRETE, Blocks.BLUE_CONCRETE,
                Blocks.PURPLE_CONCRETE, Blocks.MAGENTA_CONCRETE, Blocks.PINK_CONCRETE,
                Blocks.BROWN_CONCRETE
        })
            ABSORPTION_MAP.put(b, 0.05f);
        for (Block b : new Block[] {
                Blocks.TERRACOTTA, Blocks.WHITE_TERRACOTTA, Blocks.LIGHT_GRAY_TERRACOTTA,
                Blocks.GRAY_TERRACOTTA, Blocks.BLACK_TERRACOTTA, Blocks.RED_TERRACOTTA,
                Blocks.ORANGE_TERRACOTTA, Blocks.YELLOW_TERRACOTTA, Blocks.LIME_TERRACOTTA,
                Blocks.GREEN_TERRACOTTA, Blocks.CYAN_TERRACOTTA, Blocks.LIGHT_BLUE_TERRACOTTA,
                Blocks.BLUE_TERRACOTTA, Blocks.PURPLE_TERRACOTTA, Blocks.MAGENTA_TERRACOTTA,
                Blocks.PINK_TERRACOTTA, Blocks.BROWN_TERRACOTTA
        })
            ABSORPTION_MAP.put(b, 0.06f);
        for (Block b : new Block[] {
                Blocks.GLASS, Blocks.GLASS_PANE, Blocks.WHITE_STAINED_GLASS,
                Blocks.WHITE_STAINED_GLASS_PANE, Blocks.TINTED_GLASS
        })
            ABSORPTION_MAP.put(b, 0.10f);
        for (Block b : new Block[] {
                Blocks.PRISMARINE, Blocks.PRISMARINE_BRICKS, Blocks.DARK_PRISMARINE,
                Blocks.QUARTZ_BLOCK, Blocks.SMOOTH_QUARTZ, Blocks.QUARTZ_BRICKS, Blocks.QUARTZ_PILLAR
        })
            ABSORPTION_MAP.put(b, 0.04f);
        // Medium Absorption (Wood, Dirt, Sand)
        for (Block b : new Block[] {
                Blocks.OAK_PLANKS, Blocks.SPRUCE_PLANKS, Blocks.BIRCH_PLANKS, Blocks.JUNGLE_PLANKS,
                Blocks.ACACIA_PLANKS, Blocks.DARK_OAK_PLANKS, Blocks.MANGROVE_PLANKS,
                Blocks.CHERRY_PLANKS, Blocks.BAMBOO_PLANKS, Blocks.CRIMSON_PLANKS, Blocks.WARPED_PLANKS,
                Blocks.OAK_LOG, Blocks.SPRUCE_LOG, Blocks.BIRCH_LOG, Blocks.JUNGLE_LOG,
                Blocks.ACACIA_LOG, Blocks.DARK_OAK_LOG, Blocks.MANGROVE_LOG, Blocks.CHERRY_LOG,
                Blocks.STRIPPED_OAK_LOG, Blocks.STRIPPED_SPRUCE_LOG, Blocks.STRIPPED_BIRCH_LOG,
                Blocks.STRIPPED_JUNGLE_LOG
        })
            ABSORPTION_MAP.put(b, 0.15f);
        for (Block b : new Block[] {
                Blocks.DIRT, Blocks.COARSE_DIRT, Blocks.ROOTED_DIRT, Blocks.GRASS_BLOCK,
                Blocks.PODZOL, Blocks.MYCELIUM, Blocks.FARMLAND, Blocks.DIRT_PATH,
                Blocks.MUD, Blocks.MUDDY_MANGROVE_ROOTS
        })
            ABSORPTION_MAP.put(b, 0.25f);
        for (Block b : new Block[] {
                Blocks.SAND, Blocks.RED_SAND, Blocks.SANDSTONE, Blocks.RED_SANDSTONE,
                Blocks.SMOOTH_SANDSTONE, Blocks.SMOOTH_RED_SANDSTONE, Blocks.GRAVEL,
                Blocks.CLAY, Blocks.SOUL_SAND, Blocks.SOUL_SOIL
        })
            ABSORPTION_MAP.put(b, 0.20f);
        ABSORPTION_MAP.put(Blocks.BOOKSHELF, 0.35f);
        ABSORPTION_MAP.put(Blocks.CHISELED_BOOKSHELF, 0.35f);
        // High Absorption (Soft materials)
        for (Block b : new Block[] {
                Blocks.WHITE_WOOL, Blocks.LIGHT_GRAY_WOOL, Blocks.GRAY_WOOL, Blocks.BLACK_WOOL,
                Blocks.RED_WOOL, Blocks.ORANGE_WOOL, Blocks.YELLOW_WOOL, Blocks.LIME_WOOL,
                Blocks.GREEN_WOOL, Blocks.CYAN_WOOL, Blocks.LIGHT_BLUE_WOOL, Blocks.BLUE_WOOL,
                Blocks.PURPLE_WOOL, Blocks.MAGENTA_WOOL, Blocks.PINK_WOOL, Blocks.BROWN_WOOL
        })
            ABSORPTION_MAP.put(b, 0.80f);
        for (Block b : new Block[] {
                Blocks.WHITE_CARPET, Blocks.LIGHT_GRAY_CARPET, Blocks.GRAY_CARPET, Blocks.BLACK_CARPET,
                Blocks.RED_CARPET, Blocks.ORANGE_CARPET, Blocks.YELLOW_CARPET, Blocks.LIME_CARPET,
                Blocks.GREEN_CARPET, Blocks.CYAN_CARPET, Blocks.LIGHT_BLUE_CARPET, Blocks.BLUE_CARPET,
                Blocks.PURPLE_CARPET, Blocks.MAGENTA_CARPET, Blocks.PINK_CARPET, Blocks.BROWN_CARPET,
                Blocks.MOSS_CARPET
        })
            ABSORPTION_MAP.put(b, 0.70f);
        for (Block b : new Block[] {
                Blocks.SNOW_BLOCK, Blocks.SNOW, Blocks.POWDER_SNOW
        })
            ABSORPTION_MAP.put(b, 0.65f);
        for (Block b : new Block[] {
                Blocks.OAK_LEAVES, Blocks.SPRUCE_LEAVES, Blocks.BIRCH_LEAVES, Blocks.JUNGLE_LEAVES,
                Blocks.ACACIA_LEAVES, Blocks.DARK_OAK_LEAVES, Blocks.MANGROVE_LEAVES,
                Blocks.CHERRY_LEAVES, Blocks.AZALEA_LEAVES, Blocks.FLOWERING_AZALEA_LEAVES
        })
            ABSORPTION_MAP.put(b, 0.40f);
        ABSORPTION_MAP.put(Blocks.MOSS_BLOCK, 0.55f);
        ABSORPTION_MAP.put(Blocks.SPONGE, 0.75f);
        ABSORPTION_MAP.put(Blocks.WET_SPONGE, 0.75f);
        ABSORPTION_MAP.put(Blocks.HAY_BLOCK, 0.65f);
        for (Block b : new Block[] {
                Blocks.WHITE_BED, Blocks.LIGHT_GRAY_BED, Blocks.GRAY_BED, Blocks.BLACK_BED,
                Blocks.RED_BED, Blocks.ORANGE_BED, Blocks.YELLOW_BED, Blocks.LIME_BED,
                Blocks.GREEN_BED, Blocks.CYAN_BED, Blocks.LIGHT_BLUE_BED, Blocks.BLUE_BED,
                Blocks.PURPLE_BED, Blocks.MAGENTA_BED, Blocks.PINK_BED, Blocks.BROWN_BED
        })
            ABSORPTION_MAP.put(b, 0.50f);
        // Nether
        for (Block b : new Block[] {
                Blocks.NETHERRACK, Blocks.NETHER_BRICKS, Blocks.BASALT, Blocks.SMOOTH_BASALT,
                Blocks.POLISHED_BASALT, Blocks.BLACKSTONE, Blocks.POLISHED_BLACKSTONE,
                Blocks.POLISHED_BLACKSTONE_BRICKS, Blocks.GILDED_BLACKSTONE
        })
            ABSORPTION_MAP.put(b, 0.04f);
        for (Block b : new Block[] {
                Blocks.NETHER_WART_BLOCK, Blocks.WARPED_WART_BLOCK, Blocks.SHROOMLIGHT
        })
            ABSORPTION_MAP.put(b, 0.30f);
        for (Block b : new Block[] {
                Blocks.SCULK, Blocks.SCULK_CATALYST, Blocks.SCULK_VEIN
        })
            ABSORPTION_MAP.put(b, 0.50f);
        ABSORPTION_MAP.put(Blocks.AMETHYST_BLOCK, 0.08f);
        ABSORPTION_MAP.put(Blocks.BUDDING_AMETHYST, 0.08f);
        ABSORPTION_MAP.put(Blocks.WATER, 0.95f);
        ABSORPTION_MAP.put(Blocks.LAVA, 0.90f);
    }

    /**
     * Returns the acoustic absorption coefficient for a given block (O(1) HashMap
     * lookup).
     * α = 0.0: Perfect reflector. α = 1.0: Perfect absorber.
     */
    private static float getAbsorptionCoefficient(Block block) {
        return ABSORPTION_MAP.getOrDefault(block, 0.10f);
    }

    /**
     * Public accessor for EarlyReflectionEngine.
     * Returns absorption coefficient for reflection gain calculation.
     * Reflection energy = 1.0 - absorption.
     */
    public static float getAbsorptionForReflection(Block block) {
        return getAbsorptionCoefficient(block);
    }


    // ─── Public API ────────────────────────────────────────────────────
    // NOTE: Live listener-based scanning has been removed.
    // Only venue-locked probe scanning is used (scanVenue, scanProbe, etc.).

    // ─── Material-Based Sound Transmission (for Occlusion) ─────────────

    /**
     * Returns the sound transmission factor for a block.
     * This is how much sound energy passes THROUGH the material:
     * 0.0 = perfectly soundproof (no sound passes)
     * 1.0 = completely transparent (all sound passes)
     *
     * Different from absorption (surface reflection for reverb).
     * Transmission depends on mass, density, and material stiffness.
     *
     * @param block       The block to check
     * @param isSubwoofer If true, bass frequencies pass through more easily
     * @return Transmission factor (0.0 to 1.0)
     */
    public static float getBlockTransmission(BlockState state, boolean isSubwoofer) {
        com.audiophilecraft.config.LiveTuningConfig cfg = com.audiophilecraft.config.LiveTuningConfig.get();
        float transmission = getBaseTransmission(state);

        // Compress block-to-block variance to make Minecraft walls feel more realistic.
        float standardWall = cfg.occ_standardWall;
        transmission = standardWall + (transmission - standardWall) * cfg.occ_varianceBlend;

        // Bass frequencies (subwoofer) penetrate walls much more easily
        if (isSubwoofer) {
            transmission = (float) Math.sqrt(transmission);
        }

        return Math.max(0.01f, Math.min(1.0f, transmission));
    }

    /**
     * Base transmission factor by material.
     * Grouped by density/mass tiers (Sound Transmission Class - STC equivalent).
     */
    private static float getBaseTransmission(BlockState state) {
        net.minecraft.sound.BlockSoundGroup sound = state.getSoundGroup();

        if (sound == net.minecraft.sound.BlockSoundGroup.STONE ||
                sound == net.minecraft.sound.BlockSoundGroup.DEEPSLATE ||
                sound == net.minecraft.sound.BlockSoundGroup.METAL ||
                sound == net.minecraft.sound.BlockSoundGroup.GILDED_BLACKSTONE ||
                sound == net.minecraft.sound.BlockSoundGroup.NETHERITE ||
                sound == net.minecraft.sound.BlockSoundGroup.ANVIL ||
                sound == net.minecraft.sound.BlockSoundGroup.LODESTONE ||
                sound == net.minecraft.sound.BlockSoundGroup.COPPER) {
            return 0.08f;
        }

        if (sound == net.minecraft.sound.BlockSoundGroup.GLASS) {
            return 0.40f;
        }

        if (sound == net.minecraft.sound.BlockSoundGroup.WOOD ||
                sound == net.minecraft.sound.BlockSoundGroup.BAMBOO_WOOD ||
                sound == net.minecraft.sound.BlockSoundGroup.CHERRY_WOOD ||
                sound == net.minecraft.sound.BlockSoundGroup.NETHER_WOOD) {
            return 0.20f;
        }

        if (sound == net.minecraft.sound.BlockSoundGroup.WOOL ||
                sound == net.minecraft.sound.BlockSoundGroup.SNOW ||
                sound == net.minecraft.sound.BlockSoundGroup.SLIME ||
                sound == net.minecraft.sound.BlockSoundGroup.HONEY ||
                sound == net.minecraft.sound.BlockSoundGroup.MOSS_BLOCK ||
                sound == net.minecraft.sound.BlockSoundGroup.MOSS_CARPET ||
                sound == net.minecraft.sound.BlockSoundGroup.GRASS) {
            return 0.35f;
        }

        if (sound == net.minecraft.sound.BlockSoundGroup.SAND ||
                sound == net.minecraft.sound.BlockSoundGroup.GRAVEL ||
                sound == net.minecraft.sound.BlockSoundGroup.MUD ||
                sound == net.minecraft.sound.BlockSoundGroup.SOUL_SAND) {
            return 0.25f;
        }

        return 0.15f; // Default for unknown solid blocks
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
        public final float density;
        public final float diffusion;
        public final float decayHFRatio;
        public final float decayLFRatio;
        public final float airAbsorptionGainHF;
        public final boolean decayHFLimit;
        public final float enclosure;
        public final Vec3d probePosition;
        public final String tierName;

        public VenuePreset(float decayTime, float gain, float gainHF, float gainLF,
                float reflectionsGain, float reflectionsDelay,
                float lateReverbGain, float lateReverbDelay,
                float density, float diffusion, float decayHFRatio, float decayLFRatio,
                float airAbsorptionGainHF, boolean decayHFLimit, float enclosure, Vec3d probePosition,
                String tierName) {
            this.decayTime = decayTime;
            this.gain = gain;
            this.gainHF = gainHF;
            this.gainLF = gainLF;
            this.reflectionsGain = reflectionsGain;
            this.reflectionsDelay = reflectionsDelay;
            this.lateReverbGain = lateReverbGain;
            this.lateReverbDelay = lateReverbDelay;
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

        public ProbeResult(float nearHitRatio, float midHitRatio, float farHitRatio,
                float skyEscapeRatio, float avgAbsorption, float meanDist,
                float variance, float enclosure, float trueVolume, float trueSurfaceArea, float[] distances, float[] absorptions) {
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

        public VenueDescriptor(float enclosure, float scale, float reflectivity,
                float diffusion, float openness, float earlyDensity,
                float latePotential, float avgAbsorption, float trueVolume, float trueSurfaceArea) {
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

    /**
     * Cast rays from a single probe position and extract raw metrics.
     * Optionally collects the hit coordinates into outPointCloud.
     */
    public ProbeResult scanProbe(World world, Vec3d probePos, java.util.List<Vec3d> outPointCloud) {
        BlockPos probeBlock = new BlockPos(
                (int) Math.floor(probePos.x),
                (int) Math.floor(probePos.y),
                (int) Math.floor(probePos.z));

        float[] distances = new float[RAY_COUNT];
        float[] absorptions = new float[RAY_COUNT];

        for (int i = 0; i < RAY_COUNT; i++) {
            float dirX = RAY_DIRS_NORM[i][0];
            float dirY = RAY_DIRS_NORM[i][1];
            float dirZ = RAY_DIRS_NORM[i][2];
            float hitDist = MAX_RAY_DIST;
            float hitAbsorption = 1.0f;

            BlockPos.Mutable checkPos = new BlockPos.Mutable();
            for (int step = 1; step <= MAX_RAY_DIST; step++) {
                checkPos.set(
                        (int) Math.floor(probePos.x + dirX * step),
                        (int) Math.floor(probePos.y + dirY * step),
                        (int) Math.floor(probePos.z + dirZ * step));
                if (checkPos.equals(probeBlock))
                    continue;

                BlockState state = world.getBlockState(checkPos);
                if (state.isSolidBlock(world, checkPos)) {
                    hitDist = step;
                    hitAbsorption = getAbsorptionCoefficient(state.getBlock());

                    if (outPointCloud != null) {
                        outPointCloud.add(new Vec3d(
                                probePos.x + dirX * hitDist,
                                probePos.y + dirY * hitDist,
                                probePos.z + dirZ * hitDist));
                    }

                    break;
                }
            }
            distances[i] = hitDist;
            absorptions[i] = hitAbsorption;
        }

        // Classify hits
        int nearHits = 0, midHits = 0, farHits = 0, skyEscapes = 0;
        float totalAbsorption = 0, totalDist = 0;
        int wallsHit = 0;

        for (int i = 0; i < RAY_COUNT; i++) {
            totalAbsorption += absorptions[i];
            if (distances[i] < MAX_RAY_DIST) {
                wallsHit++;
                totalDist += distances[i];
                if (distances[i] <= 5.0f)
                    nearHits++;
                else if (distances[i] <= 15.0f)
                    midHits++;
                else
                    farHits++;
            } else {
                skyEscapes++;
            }
        }

        float avgAbsorption = totalAbsorption / (float) RAY_COUNT;
        float meanDist = wallsHit > 0 ? totalDist / wallsHit : 10.0f;
        float enclosure = wallsHit / (float) RAY_COUNT;

        // Variance (std dev of ray distances)
        float sumSqDiff = 0;
        float sumCubeDist = 0;
        float sumSqDist = 0;
        int validVolumeRays = 0;
        
        for (int i = 0; i < RAY_COUNT; i++) {
            float dist = distances[i];
            float diff = dist - meanDist;
            sumSqDiff += diff * diff;
            
            // Exclude sky escapes (MAX_RAY_DIST) from volume geometry so they don't blow up the math (256^3)
            if (dist < MAX_RAY_DIST) {
                sumCubeDist += (dist * dist * dist);
                sumSqDist += (dist * dist);
                validVolumeRays++;
            }
        }
        float variance = (float) Math.sqrt(sumSqDiff / (float) RAY_COUNT);
        
        if (validVolumeRays == 0) validVolumeRays = 1; // Failsafe
        
        // Monte Carlo True Geometric Volume: (4/3 * Pi) * average(r^3)
        float averageCubeDist = sumCubeDist / (float) validVolumeRays;
        float trueVolume = (4.0f * (float) Math.PI / 3.0f) * averageCubeDist;

        // Monte Carlo True Geometric Surface Area: 4 * Pi * average(r^2) * 1.2f (roughness factor)
        float averageSqDist = sumSqDist / (float) validVolumeRays;
        float trueSurfaceArea = 4.0f * (float) Math.PI * averageSqDist * 1.2f;

        return new ProbeResult(
                nearHits / (float) RAY_COUNT, midHits / (float) RAY_COUNT, farHits / (float) RAY_COUNT,
                skyEscapes / (float) RAY_COUNT, avgAbsorption, meanDist,
                variance, enclosure, trueVolume, trueSurfaceArea, distances, absorptions);
    }

    /**
     * Compute robust clearance for a direction sector using dot-product cone
     * selection and adaptive 3-tier classification. This prevents pillar traps.
     *
     * @param distances     N ray distances from the center probe
     * @param targetDir     Normalized direction vector for the sector
     * @param coneThreshold Minimum dot product to include a ray (0.3 = ~72° cone)
     * @return Robust clearance distance for this sector
     */
    private float sectorClearance(float[] distances, float[] targetDir, float coneThreshold) {
        // Collect distances of rays within the cone
        float[] sectorDists = new float[RAY_COUNT];
        int count = 0;

        for (int i = 0; i < RAY_COUNT; i++) {
            float dot = RAY_DIRS_NORM[i][0] * targetDir[0]
                    + RAY_DIRS_NORM[i][1] * targetDir[1]
                    + RAY_DIRS_NORM[i][2] * targetDir[2];
            if (dot > coneThreshold) {
                sectorDists[count++] = distances[i];
            }
        }

        if (count == 0)
            return MAX_RAY_DIST;

        // Sort ASCENDING (smallest first)
        java.util.Arrays.sort(sectorDists, 0, count);

        float min = sectorDists[0];
        float max = sectorDists[count - 1];
        float range = max - min;

        // Calculate mean
        float sum = 0;
        for (int i = 0; i < count; i++) {
            sum += sectorDists[i];
        }
        float mean = sum / count;

        // Calculate standard deviation
        float varianceSum = 0;
        for (int i = 0; i < count; i++) {
            float diff = sectorDists[i] - mean;
            varianceSum += diff * diff;
        }
        float stdDev = (float) Math.sqrt(varianceSum / count);

        // Coefficient of Variation
        float cv = stdDev / mean;

        // IQR-based outlier detection
        float q1 = sectorDists[count / 4];
        float q3 = sectorDists[(count * 3) / 4];
        float iqr = q3 - q1;
        float outlierThreshold = q3 + (1.5f * iqr);

        // Count outliers
        int outlierCount = 0;
        for (int i = 0; i < count; i++) {
            if (sectorDists[i] > outlierThreshold) {
                outlierCount++;
            }
        }
        float outlierRatio = (float) outlierCount / count;

        // OPEN AIR DETECTION: If >60% are outliers (sky rays)
        if (outlierRatio > 0.6f) {
            // Use only wall hits (filter out sky)
            int validCount = 0;
            float validSum = 0;
            for (int i = 0; i < count; i++) {
                if (sectorDists[i] <= outlierThreshold) {
                    validSum += sectorDists[i];
                    validCount++;
                }
            }

            if (validCount > 0) {
                float clearance = validSum / validCount;
                // Conservative clamp for open areas
                return Math.min(clearance, min * 2.0f);
            } else {
                // No walls at all - completely open sky
                return min;
            }
        }

        float clearance;

        if (range < 8.0f || cv < 0.3f) {
            // ═══════════════════════════════════════════════════
            // TIER 1: SMALL/UNIFORM SPACE (e.g., 10x10 room)
            // ═══════════════════════════════════════════════════
            clearance = min; // Use minimum - safest approach

        } else if (range < 30.0f || cv < 0.8f) {
            // ═══════════════════════════════════════════════════
            // TIER 2: MEDIUM SPACE (e.g., 60x40 club with pillars)
            // ═══════════════════════════════════════════════════
            // Use bottom 30th percentile average
            int percentile30 = Math.max(1, (int) (count * 0.30f));
            float percentileSum = 0;
            for (int i = 0; i < percentile30; i++) {
                percentileSum += sectorDists[i];
            }
            clearance = percentileSum / percentile30;

            // Safety clamp: never exceed 3x minimum
            clearance = Math.min(clearance, min * 3.0f);

        } else {
            // ═══════════════════════════════════════════════════
            // TIER 3: LARGE/VARIABLE SPACE (e.g., 200x200 stadium)
            // ═══════════════════════════════════════════════════
            // Use bottom 40th percentile average (more aggressive filtering)
            int percentile40 = Math.max(1, (int) (count * 0.40f));
            float percentileSum = 0;
            for (int i = 0; i < percentile40; i++) {
                percentileSum += sectorDists[i];
            }
            clearance = percentileSum / percentile40;

            // Safety clamp: never exceed 5x minimum
            clearance = Math.min(clearance, min * 5.0f);
        }

        // Final safety: always respect minimum
        clearance = Math.max(clearance, min);

        return clearance;
    }

    /**
     * Merge multiple ProbeResults into a single VenueDescriptor.
     * Center probe gets 2x weight.
     */
    // Live Tuning: store last descriptor for regeneration
    private volatile VenueDescriptor lastDescriptor = null;
    private Vec3d lastProbePos = null;
    public static java.util.List<Vec3d> lastPointCloud = java.util.Collections.synchronizedList(new java.util.ArrayList<>());
    public static volatile java.util.Set<net.minecraft.util.math.BlockPos> lastVenueBlocks = new java.util.HashSet<>();
    public static java.util.List<net.minecraft.util.math.BlockPos> lastSpeakers = java.util.Collections.synchronizedList(new java.util.ArrayList<>());

    /**
     * Get the last VenueDescriptor generated by scanVenue (for live tuning
     * regeneration)
     */
    public VenueDescriptor getLastDescriptor() {
        return lastDescriptor;
    }

    /** Get the last probe position used by scanVenue */
    public Vec3d getLastProbePos() {
        return lastProbePos;
    }

    /** Get the 3D Point Cloud of the venue's geometric boundaries */
    public static java.util.List<Vec3d> getLastPointCloud() {
        return lastPointCloud;
    }

    /** Get the complete solid block map of the venue */
    public static java.util.Set<net.minecraft.util.math.BlockPos> getLastVenueBlocks() {
        return lastVenueBlocks;
    }

    /** Get the speakers that initiated the last scan */
    public static java.util.List<net.minecraft.util.math.BlockPos> getLastSpeakers() {
        return lastSpeakers;
    }

    public VenueDescriptor mergeProbes(java.util.List<ProbeResult> probes) {
        float totalWeight = 0;
        float wEnclosure = 0, wScale = 0, wAbsorption = 0;
        float wNear = 0, wMid = 0, wFar = 0, wSky = 0, wVariance = 0, wTrueVolume = 0, wTrueSurfaceArea = 0;

        for (int i = 0; i < probes.size(); i++) {
            ProbeResult p = probes.get(i);
            float weight = (i == 0) ? 1.5f : 1.0f; // Center probe = 1.5x weight (reduced placement bias)
            totalWeight += weight;

            wEnclosure += p.enclosure * weight;
            wScale += p.meanDist * weight;
            wAbsorption += p.avgAbsorption * weight;
            wNear += p.nearHitRatio * weight;
            wMid += p.midHitRatio * weight;
            wFar += p.farHitRatio * weight;
            wSky += p.skyEscapeRatio * weight;
            wVariance += p.variance * weight;
            wTrueVolume += p.trueVolume * weight;
            wTrueSurfaceArea += p.trueSurfaceArea * weight;
        }

        float enclosure = wEnclosure / totalWeight;
        float scale = wScale / totalWeight;
        float avgAbsorption = wAbsorption / totalWeight;
        float reflectivity = 1.0f - avgAbsorption;
        float openness = wSky / totalWeight;
        float earlyDensity = (wNear + wMid) / totalWeight;
        float latePotential = (wFar / totalWeight) + (scale / 40.0f); // Normalize scale
        float diffusion = Math.min(1.0f, (wVariance / totalWeight) / 15.0f); // Normalize
        float trueVolume = wTrueVolume / totalWeight;
        float trueSurfaceArea = wTrueSurfaceArea / totalWeight;

        return new VenueDescriptor(enclosure, scale, reflectivity, diffusion,
                openness, earlyDensity, latePotential, avgAbsorption, trueVolume, trueSurfaceArea);
    }

    /**
     * Main entry point: Adaptive 2-Phase Multi-Probe Venue Scan.
     *
     * Phase 1: Coarse scan from center → compute sector clearances
     * Phase 2: Place adaptive probes → scan all → merge → generate preset
     *
     * @param world     The Minecraft world
     * @param centerPos Weighted speaker centroid
     * @param stageDir  Normalized stage-front direction (from speaker facing)
     * @return Locked VenuePreset for the entire playback session
     */
    public VenuePreset scanVenue(World world, Vec3d centerPos, Vec3d stageDir) {
        if (world == null || centerPos == null)
            return null;

        // Normalize stageDir (safety)
        double sdLen = stageDir.length();
        if (sdLen < 0.001)
            stageDir = new Vec3d(1, 0, 0); // Fallback
        else
            stageDir = stageDir.multiply(1.0 / sdLen);

        // ─── POINT CLOUD CACHE ──────────────────────────────
        java.util.List<Vec3d> currentCloud = new java.util.ArrayList<>();

        // ─── PHASE 1: Coarse Scan from Center ───────────────────────
        ProbeResult centerProbe = scanProbe(world, centerPos, currentCloud);

        // Compute sector clearances using robust top-k averaging
        float[] frontDir = { (float) stageDir.x, (float) stageDir.y, (float) stageDir.z };

        // Left = cross(stageDir, up)
        float[] leftDir = {
                (float) (stageDir.z), // cross(dir, up).x = dir.z
                0.0f,
                (float) (-stageDir.x) // cross(dir, up).z = -dir.x
        };
        float leftLen = (float) Math.sqrt(leftDir[0] * leftDir[0] + leftDir[2] * leftDir[2]);
        if (leftLen > 0.001f) {
            leftDir[0] /= leftLen;
            leftDir[2] /= leftLen;
        }

        float[] rightDir = { -leftDir[0], 0.0f, -leftDir[2] };
        float[] upDir = { 0.0f, 1.0f, 0.0f };

        float frontClearance = sectorClearance(centerProbe.distances, frontDir, 0.3f);
        float leftClearance = sectorClearance(centerProbe.distances, leftDir, 0.3f);
        float rightClearance = sectorClearance(centerProbe.distances, rightDir, 0.3f);
        float upClearance = sectorClearance(centerProbe.distances, upDir, 0.3f);

        // ─── PHASE 2: Adaptive Probe Placement ──────────────────────
        java.util.List<ProbeResult> probes = new java.util.ArrayList<>();
        probes.add(centerProbe); // Index 0 = center (gets 2x weight)

        // Front probe: 40% of front clearance
        Vec3d frontProbePos = centerPos.add(stageDir.multiply(frontClearance * 0.40));
        probes.add(scanProbe(world, frontProbePos, currentCloud));

        // Left probe: 40% of left clearance
        Vec3d leftVec = new Vec3d(leftDir[0], leftDir[1], leftDir[2]);
        Vec3d leftProbePos = centerPos.add(leftVec.multiply(leftClearance * 0.40));
        probes.add(scanProbe(world, leftProbePos, currentCloud));

        // Right probe: 40% of right clearance
        Vec3d rightVec = new Vec3d(rightDir[0], rightDir[1], rightDir[2]);
        Vec3d rightProbePos = centerPos.add(rightVec.multiply(rightClearance * 0.40));
        probes.add(scanProbe(world, rightProbePos, currentCloud));

        // Above probe: 50% of up clearance
        Vec3d aboveProbePos = centerPos.add(0, upClearance * 0.50, 0);
        probes.add(scanProbe(world, aboveProbePos, currentCloud));

        // Save the massive aggregated point cloud for GUI rendering
        AdvancedAcousticScanner.lastPointCloud.clear();
        AdvancedAcousticScanner.lastPointCloud.addAll(currentCloud);

        // ─── BUILD VENUE MAP: ONLY blocks where reverb rays hit ─────

        // This is the TRUE reverb scan - only surfaces the scanner detected.

        if (!currentCloud.isEmpty()) {

            java.util.Set<net.minecraft.util.math.BlockPos> hitBlocks = new java.util.HashSet<>();

            for (Vec3d pt : currentCloud) {

                hitBlocks.add(new net.minecraft.util.math.BlockPos(

                        (int) Math.floor(pt.x), (int) Math.floor(pt.y), (int) Math.floor(pt.z)));

            }

            java.util.HashSet<net.minecraft.util.math.BlockPos> newBlocks = new java.util.HashSet<>();
            newBlocks.addAll(hitBlocks);
            AdvancedAcousticScanner.lastVenueBlocks = newBlocks; // atomic replace

        }
        // ─── PHASE 3-4: Merge Probes → VenueDescriptor ──────────────
        VenueDescriptor desc = mergeProbes(probes);

        // ─── PHASE 5: Descriptor → VenuePreset ──────────────────────
        this.lastDescriptor = desc;
        this.lastProbePos = centerPos;
        return descriptorToPreset(desc, centerPos);
    }

    /**
     * Convert a VenueDescriptor into an immutable VenuePreset using
     * the Sabine equation and the existing preset logic.
     */
    public VenuePreset descriptorToPreset(VenueDescriptor d, Vec3d probePos) {
        com.audiophilecraft.config.LiveTuningConfig cfg = com.audiophilecraft.config.LiveTuningConfig.get();
        float vAvgAbsorption = d.avgAbsorption;
        float vMeanDist = d.scale;
        float vEnclosure = d.enclosure;
        float vOpenness = d.openness;

        boolean isOpenAir = vOpenness > cfg.openAir_openness_threshold;
        boolean isStronglyOpen = vOpenness > cfg.openAir_stronglyOpen_threshold;

        // The preset system now handles open-air dynamically via Sabine physics and LiveTuningConfig multipliers.

        // ═══════════════════════════════════════════════════════════════
        // ENCLOSED / SEMI-ENCLOSED VENUE (standard tier logic below)
        // ═══════════════════════════════════════════════════════════════

        // Effective enclosure: penalize enclosure when sky escape is high.
        float effectiveEnclosure = vEnclosure * (1.0f - vOpenness * 0.8f);
        effectiveEnclosure = Math.max(0.0f, Math.min(1.0f, effectiveEnclosure));

        // Geometry (Sabine formula)
        float vVolume = d.trueVolume;
        // Use the mathematically correct true surface area integrated from the rays
        float surfaceArea = d.trueSurfaceArea;
        float totalSabins = Math.max(0.01f, surfaceArea * vAvgAbsorption);

        // Sabine RT60 (Mid Decay)
        float vDecay = 0.161f * vVolume / totalSabins;

        // Pure Sabine RT60 governs the decay without artificial enclosure curves.

        if (vDecay < 0.1f)
            vDecay = 0.1f;

        // Open-air decay hard caps removed; Sabine handles open-air volume dynamically.

        if (vDecay > 15.0f)
            vDecay = 15.0f;

        // ─── REVERB GAIN ────────────────────────────────────────────
        // Use effectiveEnclosure for gain calculation.
        float baseEnclosureMultiplier = 0.5f + (effectiveEnclosure * 0.5f);
        // Open-air minimum gain is dynamically configurable
        float minGain = isOpenAir ? cfg.openAir_dynamic_minGain : 0.20f;
        float vGain = Math.max(minGain, baseEnclosureMultiplier * 0.65f);
        float vGainHF = 0.3f + (1.0f - vAvgAbsorption) * 0.6f;
        float vGainLF = 0.7f + effectiveEnclosure * 0.3f;

        // 6-Tier Preset System
        float roomFactor = Math.min(vMeanDist / 30.0f, 1.0f);
        float vReflGain, vReflDelay, lateReverbMultiplier, vLateGain, vLateDelay;

        // Reflection material factor: open air absorbs more (grass, dirt).
        // Use a lower configurable floor for open-air so reflections stay subtle.
        float reflFloor = isOpenAir ? cfg.openAir_dynamic_reflGainFloor : 0.20f;
        float reflectionMaterialFactor = Math.max(reflFloor, 1.0f - vAvgAbsorption);

        // Use EFFECTIVE enclosure for tier selection volume.
        // This prevents a stage building from pushing an open-air venue into Tier 5/6.
        float effectiveVolume = vVolume * effectiveEnclosure;
        float effectiveMeanDist = vMeanDist * (float) Math.sqrt(effectiveEnclosure);

        boolean tier10 = (effectiveVolume > cfg.tier10_volumeThreshold || effectiveMeanDist > cfg.tier10_distThreshold) && !isOpenAir;
        boolean tier9 = (effectiveVolume > cfg.tier9_volumeThreshold || effectiveMeanDist > cfg.tier9_distThreshold) && !isOpenAir;
        boolean tier8 = (effectiveVolume > cfg.tier8_volumeThreshold || effectiveMeanDist > cfg.tier8_distThreshold) && !isOpenAir;
                boolean tier7 = (effectiveVolume > cfg.tier7_volumeThreshold || effectiveMeanDist > cfg.tier7_distThreshold)
                && !isOpenAir;

        String tierName = "";
        if (tier10) {
            tierName = "TIER 10 (INFINITE CATHEDRAL / VOID)";
            vGain = Math.max(cfg.tier10_minGain, baseEnclosureMultiplier * cfg.tier10_gainMul);
            vReflGain = Math.max(0.0f, reflectionMaterialFactor * cfg.tier10_reflGainMul);
            float maxLate10 = effectiveEnclosure > 0.8f ? cfg.tier10_maxLateMultiplier_highEncl : cfg.tier10_maxLateMultiplier_lowEncl;
            lateReverbMultiplier = Math.min(cfg.tier10_lateReverbMul + (roomFactor * cfg.tier10_lateReverbRoomScale), maxLate10);
        } else if (tier9) {
            tierName = "TIER 9 (MEGA COMPLEX / CITY BLOCK)";
            vGain = Math.max(cfg.tier9_minGain, baseEnclosureMultiplier * cfg.tier9_gainMul);
            vReflGain = Math.max(0.0f, reflectionMaterialFactor * cfg.tier9_reflGainMul);
            float maxLate9 = effectiveEnclosure > 0.8f ? cfg.tier9_maxLateMultiplier_highEncl : cfg.tier9_maxLateMultiplier_lowEncl;
            lateReverbMultiplier = Math.min(cfg.tier9_lateReverbMul + (roomFactor * cfg.tier9_lateReverbRoomScale), maxLate9);
        } else if (tier8) {
            tierName = "TIER 8 (COLOSSAL DOME / HANGAR)";
            vGain = Math.max(cfg.tier8_minGain, baseEnclosureMultiplier * cfg.tier8_gainMul);
            vReflGain = Math.max(0.0f, reflectionMaterialFactor * cfg.tier8_reflGainMul);
            float maxLate8 = effectiveEnclosure > 0.8f ? cfg.tier8_maxLateMultiplier_highEncl : cfg.tier8_maxLateMultiplier_lowEncl;
            lateReverbMultiplier = Math.min(cfg.tier8_lateReverbMul + (roomFactor * cfg.tier8_lateReverbRoomScale), maxLate8);
        } else 
        if (tier7) {
            tierName = "TIER 7 (MASSIVE STADIUM)";
            // TIER 7: MASSIVE ENCLOSED STADIUM (Live Tunable)
            vGain = Math.max(cfg.tier7_minGain, baseEnclosureMultiplier * cfg.tier7_gainMul);
            vReflGain = Math.max(0.0f, reflectionMaterialFactor * cfg.tier7_reflGainMul);
            float maxLateMultiplier = effectiveEnclosure > 0.8f ? cfg.tier7_maxLateMultiplier_highEncl
                    : cfg.tier7_maxLateMultiplier_lowEncl;
            lateReverbMultiplier = Math.min(cfg.tier7_lateReverbMul + (roomFactor * cfg.tier7_lateReverbRoomScale),
                    maxLateMultiplier);
        } else if ((effectiveVolume > cfg.tier6_volumeThreshold || effectiveMeanDist > cfg.tier6_distThreshold)
                && !isStronglyOpen) {
            tierName = "TIER 6 (ARENA / CONCERT HALL)";
            // TIER 6: ARENA / CONCERT HALL (Live Tunable)
            vGain = Math.max(cfg.tier6_minGain, baseEnclosureMultiplier * cfg.tier6_gainMul);
            vReflGain = Math.max(0.0f, reflectionMaterialFactor * cfg.tier6_reflGainMul);
            lateReverbMultiplier = cfg.tier6_lateReverbMul + (roomFactor * cfg.tier6_lateReverbRoomScale);
        } else if (effectiveVolume > cfg.tier5_volumeThreshold || effectiveMeanDist > cfg.tier5_distThreshold) {
            tierName = "TIER 5 (LARGE CLUB / GYMNASIUM)";
            // TIER 5: LARGE CLUB / GYMNASIUM (Live Tunable)
            vGain = Math.max(cfg.tier5_minGain, baseEnclosureMultiplier * cfg.tier5_gainMul);
            vReflGain = Math.max(0.0f, reflectionMaterialFactor * cfg.tier5_reflGainMul);
            lateReverbMultiplier = cfg.tier5_lateReverbMul + (roomFactor * cfg.tier5_lateReverbRoomScale);
        } else if (effectiveVolume > cfg.tier4_volumeThreshold || effectiveMeanDist > cfg.tier4_distThreshold) {
            tierName = "TIER 4 (LARGE ROOM / SMALL HALL)";
            // TIER 4: LARGE ROOM / SMALL HALL (Live Tunable)
            vGain = Math.max(cfg.tier4_minGain, baseEnclosureMultiplier * cfg.tier4_gainMul);
            vReflGain = Math.max(0.0f, reflectionMaterialFactor * cfg.tier4_reflGainMul);
            lateReverbMultiplier = cfg.tier4_lateReverbMul + (roomFactor * cfg.tier4_lateReverbRoomScale);
        } else if (effectiveVolume > cfg.tier3_volumeThreshold || effectiveMeanDist > cfg.tier3_distThreshold) {
            tierName = "TIER 3 (MEDIUM ROOM / STUDIO)";
            // TIER 3: MEDIUM ROOM / STUDIO (Live Tunable)
            vGain = Math.max(cfg.tier3_minGain, baseEnclosureMultiplier * cfg.tier3_gainMul);
            vReflGain = Math.max(0.0f, reflectionMaterialFactor * cfg.tier3_reflGainMul);
            lateReverbMultiplier = cfg.tier3_lateReverbMul;
        } else if (effectiveVolume > cfg.tier2_volumeThreshold || effectiveMeanDist > cfg.tier2_distThreshold) {
            tierName = "TIER 2 (SMALL ROOM)";
            // TIER 2: SMALL ROOM (Live Tunable)
            vGain = Math.max(cfg.tier2_minGain, baseEnclosureMultiplier * cfg.tier2_gainMul);
            vReflGain = Math.min(cfg.tier2_reflGainMax,
                    Math.max(0.0f, reflectionMaterialFactor * cfg.tier2_reflGainMul));
            lateReverbMultiplier = cfg.tier2_lateReverbMul;
        } else {
            tierName = "TIER 1 (TINY SPACE / CLOSET)";
            // TIER 1: CLOSET / TINY SPACE (Live Tunable)
            vGain = Math.max(cfg.tier1_minGain, baseEnclosureMultiplier * cfg.tier1_gainMul);
            vReflGain = Math.min(cfg.tier1_reflGainMax,
                    Math.max(0.0f, reflectionMaterialFactor * cfg.tier1_reflGainMul));
            lateReverbMultiplier = cfg.tier1_lateReverbMul;
        }

        if (isOpenAir) {
            tierName += " [OPEN AIR]";
        }

        // ─── OPEN AIR: LATE TAIL SUPPRESSION ────────────────────────
        // Sound escapes to the sky — late reverb tail dissipates.
        // Late reverb requires high-order multi-path reflections. In open spaces, 
        // energy escapes before forming a tail, so it drops exponentially.
        float tailRetention = (float) Math.pow(effectiveEnclosure, 2.5);
        float openAirTailMultiplier = cfg.openAir_dynamic_lateReverbMul + (tailRetention * (1.0f - cfg.openAir_dynamic_lateReverbMul));
        lateReverbMultiplier *= openAirTailMultiplier;

        // ─── OPEN AIR: EXTRA GAIN SUPPRESSION ───────────────────────
        // General reverb gain drops as the space opens up, but early reflections 
        // (vReflGain) remain strong because they bounce off the immediate ground/walls.
        if (isOpenAir) {
            float openSuppression = 1.0f - (vOpenness * 0.6f);
            vGain *= openSuppression;
            // Early reflections (vReflGain) are NOT suppressed here!
        }

        // Global delay and gain calculations
        if (vMeanDist > 10.0f) {
            float soften = tier7 ? 0.15f : 0.30f;
            vReflGain *= (1.0f - (roomFactor * soften));
        }
        vReflDelay = Math.max(0.001f, Math.min(vMeanDist * 2.0f / 4000.0f, 0.3f));
        vLateGain = vReflGain * lateReverbMultiplier;
        vLateDelay = Math.min(vReflDelay + 0.02f, 0.1f);

        // Density & Diffusion
        float vDensity = (0.7f + effectiveEnclosure * 0.3f) - (roomFactor * 0.15f);
        vDensity = Math.max(0.4f, Math.min(1.0f, vDensity));

        float vDiffusion = 0.3f + d.diffusion * 0.7f;

        // Dynamic HF Decay Ratio
        float hfRatio = 1.05f - (vAvgAbsorption * 0.45f) - ((1.0f - effectiveEnclosure) * 0.15f);
        float vHFRatio = Math.max(0.45f, Math.min(hfRatio, 1.05f));

        // Dynamic LF Decay Ratio
        float volumeScale = Math.min(vVolume / 30000.0f, 1.0f);
        float openAirPenaltyLF = (1.0f - effectiveEnclosure) * 0.15f;
        float lfRatio = 0.70f - (volumeScale * 0.15f) - openAirPenaltyLF;
        float vLFRatio = Math.max(0.40f, Math.min(lfRatio, 0.70f));

        boolean vHFLimit = vAvgAbsorption < 0.2f;
        float vAirAbs = vMeanDist > 15.0f ? 0.95f : 0.994f;

        return new VenuePreset(vDecay, vGain, vGainHF, vGainLF,
                vReflGain, vReflDelay, vLateGain, vLateDelay,
                vDensity, vDiffusion, vHFRatio, vLFRatio, vAirAbs, vHFLimit, effectiveEnclosure, probePos, tierName);
    }

    /**
     * Backward-compatible wrapper: single probe scan (used if no stage direction
     * available).
     */
    public VenuePreset scanAtPosition(World world, Vec3d probePos) {
        return scanVenue(world, probePos, new Vec3d(1, 0, 0)); // Default: face +X
    }
}
