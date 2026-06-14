package com.audiophilecraft.sound;

import java.util.HashMap;
import java.util.Map;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;

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
            Blocks.STONE,
            Blocks.COBBLESTONE,
            Blocks.MOSSY_COBBLESTONE,
            Blocks.STONE_BRICKS,
            Blocks.MOSSY_STONE_BRICKS,
            Blocks.CRACKED_STONE_BRICKS,
            Blocks.CHISELED_STONE_BRICKS,
            Blocks.SMOOTH_STONE,
            Blocks.POLISHED_ANDESITE,
            Blocks.POLISHED_DIORITE,
            Blocks.POLISHED_GRANITE,
            Blocks.ANDESITE,
            Blocks.DIORITE,
            Blocks.GRANITE,
            Blocks.DEEPSLATE,
            Blocks.POLISHED_DEEPSLATE,
            Blocks.DEEPSLATE_BRICKS,
            Blocks.DEEPSLATE_TILES,
            Blocks.TUFF,
            Blocks.CALCITE,
            Blocks.DRIPSTONE_BLOCK,
            Blocks.POINTED_DRIPSTONE,
            Blocks.BEDROCK
        }) ABSORPTION_MAP.put(b, 0.06f);
        for (Block b : new Block[] {
            Blocks.OBSIDIAN,
            Blocks.CRYING_OBSIDIAN,
            Blocks.END_STONE,
            Blocks.END_STONE_BRICKS,
            Blocks.IRON_BLOCK,
            Blocks.GOLD_BLOCK,
            Blocks.DIAMOND_BLOCK,
            Blocks.NETHERITE_BLOCK,
            Blocks.COPPER_BLOCK,
            Blocks.ANVIL,
            Blocks.ICE,
            Blocks.PACKED_ICE,
            Blocks.BLUE_ICE
        }) ABSORPTION_MAP.put(b, 0.02f);
        for (Block b : new Block[] {Blocks.BRICKS, Blocks.NETHER_BRICKS, Blocks.RED_NETHER_BRICKS})
            ABSORPTION_MAP.put(b, 0.04f);
        for (Block b : new Block[] {
            Blocks.WHITE_CONCRETE, Blocks.LIGHT_GRAY_CONCRETE, Blocks.GRAY_CONCRETE,
            Blocks.BLACK_CONCRETE, Blocks.RED_CONCRETE, Blocks.ORANGE_CONCRETE,
            Blocks.YELLOW_CONCRETE, Blocks.LIME_CONCRETE, Blocks.GREEN_CONCRETE,
            Blocks.CYAN_CONCRETE, Blocks.LIGHT_BLUE_CONCRETE, Blocks.BLUE_CONCRETE,
            Blocks.PURPLE_CONCRETE, Blocks.MAGENTA_CONCRETE, Blocks.PINK_CONCRETE,
            Blocks.BROWN_CONCRETE
        }) ABSORPTION_MAP.put(b, 0.05f);
        for (Block b : new Block[] {
            Blocks.TERRACOTTA, Blocks.WHITE_TERRACOTTA, Blocks.LIGHT_GRAY_TERRACOTTA,
            Blocks.GRAY_TERRACOTTA, Blocks.BLACK_TERRACOTTA, Blocks.RED_TERRACOTTA,
            Blocks.ORANGE_TERRACOTTA, Blocks.YELLOW_TERRACOTTA, Blocks.LIME_TERRACOTTA,
            Blocks.GREEN_TERRACOTTA, Blocks.CYAN_TERRACOTTA, Blocks.LIGHT_BLUE_TERRACOTTA,
            Blocks.BLUE_TERRACOTTA, Blocks.PURPLE_TERRACOTTA, Blocks.MAGENTA_TERRACOTTA,
            Blocks.PINK_TERRACOTTA, Blocks.BROWN_TERRACOTTA
        }) ABSORPTION_MAP.put(b, 0.06f);
        for (Block b : new Block[] {
            Blocks.GLASS,
            Blocks.GLASS_PANE,
            Blocks.WHITE_STAINED_GLASS,
            Blocks.WHITE_STAINED_GLASS_PANE,
            Blocks.TINTED_GLASS
        }) ABSORPTION_MAP.put(b, 0.10f);
        for (Block b : new Block[] {
            Blocks.PRISMARINE,
            Blocks.PRISMARINE_BRICKS,
            Blocks.DARK_PRISMARINE,
            Blocks.QUARTZ_BLOCK,
            Blocks.SMOOTH_QUARTZ,
            Blocks.QUARTZ_BRICKS,
            Blocks.QUARTZ_PILLAR
        }) ABSORPTION_MAP.put(b, 0.04f);
        // Medium Absorption (Wood, Dirt, Sand)
        for (Block b : new Block[] {
            Blocks.OAK_PLANKS,
            Blocks.SPRUCE_PLANKS,
            Blocks.BIRCH_PLANKS,
            Blocks.JUNGLE_PLANKS,
            Blocks.ACACIA_PLANKS,
            Blocks.DARK_OAK_PLANKS,
            Blocks.MANGROVE_PLANKS,
            Blocks.CHERRY_PLANKS,
            Blocks.BAMBOO_PLANKS,
            Blocks.CRIMSON_PLANKS,
            Blocks.WARPED_PLANKS,
            Blocks.OAK_LOG,
            Blocks.SPRUCE_LOG,
            Blocks.BIRCH_LOG,
            Blocks.JUNGLE_LOG,
            Blocks.ACACIA_LOG,
            Blocks.DARK_OAK_LOG,
            Blocks.MANGROVE_LOG,
            Blocks.CHERRY_LOG,
            Blocks.STRIPPED_OAK_LOG,
            Blocks.STRIPPED_SPRUCE_LOG,
            Blocks.STRIPPED_BIRCH_LOG,
            Blocks.STRIPPED_JUNGLE_LOG
        }) ABSORPTION_MAP.put(b, 0.15f);
        for (Block b : new Block[] {
            Blocks.DIRT, Blocks.COARSE_DIRT, Blocks.ROOTED_DIRT, Blocks.GRASS_BLOCK,
            Blocks.PODZOL, Blocks.MYCELIUM, Blocks.FARMLAND, Blocks.DIRT_PATH,
            Blocks.MUD, Blocks.MUDDY_MANGROVE_ROOTS
        }) ABSORPTION_MAP.put(b, 0.25f);
        for (Block b : new Block[] {
            Blocks.SAND,
            Blocks.RED_SAND,
            Blocks.SANDSTONE,
            Blocks.RED_SANDSTONE,
            Blocks.SMOOTH_SANDSTONE,
            Blocks.SMOOTH_RED_SANDSTONE,
            Blocks.GRAVEL,
            Blocks.CLAY,
            Blocks.SOUL_SAND,
            Blocks.SOUL_SOIL
        }) ABSORPTION_MAP.put(b, 0.20f);
        ABSORPTION_MAP.put(Blocks.BOOKSHELF, 0.35f);
        ABSORPTION_MAP.put(Blocks.CHISELED_BOOKSHELF, 0.35f);
        // High Absorption (Soft materials)
        for (Block b : new Block[] {
            Blocks.WHITE_WOOL, Blocks.LIGHT_GRAY_WOOL, Blocks.GRAY_WOOL, Blocks.BLACK_WOOL,
            Blocks.RED_WOOL, Blocks.ORANGE_WOOL, Blocks.YELLOW_WOOL, Blocks.LIME_WOOL,
            Blocks.GREEN_WOOL, Blocks.CYAN_WOOL, Blocks.LIGHT_BLUE_WOOL, Blocks.BLUE_WOOL,
            Blocks.PURPLE_WOOL, Blocks.MAGENTA_WOOL, Blocks.PINK_WOOL, Blocks.BROWN_WOOL
        }) ABSORPTION_MAP.put(b, 0.80f);
        for (Block b : new Block[] {
            Blocks.WHITE_CARPET, Blocks.LIGHT_GRAY_CARPET, Blocks.GRAY_CARPET, Blocks.BLACK_CARPET,
            Blocks.RED_CARPET, Blocks.ORANGE_CARPET, Blocks.YELLOW_CARPET, Blocks.LIME_CARPET,
            Blocks.GREEN_CARPET, Blocks.CYAN_CARPET, Blocks.LIGHT_BLUE_CARPET, Blocks.BLUE_CARPET,
            Blocks.PURPLE_CARPET, Blocks.MAGENTA_CARPET, Blocks.PINK_CARPET, Blocks.BROWN_CARPET,
            Blocks.MOSS_CARPET
        }) ABSORPTION_MAP.put(b, 0.70f);
        for (Block b : new Block[] {Blocks.SNOW_BLOCK, Blocks.SNOW, Blocks.POWDER_SNOW}) ABSORPTION_MAP.put(b, 0.65f);
        for (Block b : new Block[] {
            Blocks.OAK_LEAVES,
            Blocks.SPRUCE_LEAVES,
            Blocks.BIRCH_LEAVES,
            Blocks.JUNGLE_LEAVES,
            Blocks.ACACIA_LEAVES,
            Blocks.DARK_OAK_LEAVES,
            Blocks.MANGROVE_LEAVES,
            Blocks.CHERRY_LEAVES,
            Blocks.AZALEA_LEAVES,
            Blocks.FLOWERING_AZALEA_LEAVES
        }) ABSORPTION_MAP.put(b, 0.40f);
        ABSORPTION_MAP.put(Blocks.MOSS_BLOCK, 0.55f);
        ABSORPTION_MAP.put(Blocks.SPONGE, 0.75f);
        ABSORPTION_MAP.put(Blocks.WET_SPONGE, 0.75f);
        ABSORPTION_MAP.put(Blocks.HAY_BLOCK, 0.65f);
        for (Block b : new Block[] {
            Blocks.WHITE_BED, Blocks.LIGHT_GRAY_BED, Blocks.GRAY_BED, Blocks.BLACK_BED,
            Blocks.RED_BED, Blocks.ORANGE_BED, Blocks.YELLOW_BED, Blocks.LIME_BED,
            Blocks.GREEN_BED, Blocks.CYAN_BED, Blocks.LIGHT_BLUE_BED, Blocks.BLUE_BED,
            Blocks.PURPLE_BED, Blocks.MAGENTA_BED, Blocks.PINK_BED, Blocks.BROWN_BED
        }) ABSORPTION_MAP.put(b, 0.50f);
        // Nether
        for (Block b : new Block[] {
            Blocks.NETHERRACK,
            Blocks.NETHER_BRICKS,
            Blocks.BASALT,
            Blocks.SMOOTH_BASALT,
            Blocks.POLISHED_BASALT,
            Blocks.BLACKSTONE,
            Blocks.POLISHED_BLACKSTONE,
            Blocks.POLISHED_BLACKSTONE_BRICKS,
            Blocks.GILDED_BLACKSTONE
        }) ABSORPTION_MAP.put(b, 0.04f);
        for (Block b : new Block[] {Blocks.NETHER_WART_BLOCK, Blocks.WARPED_WART_BLOCK, Blocks.SHROOMLIGHT})
            ABSORPTION_MAP.put(b, 0.30f);
        for (Block b : new Block[] {Blocks.SCULK, Blocks.SCULK_CATALYST, Blocks.SCULK_VEIN})
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

        if (sound == net.minecraft.sound.BlockSoundGroup.STONE
                || sound == net.minecraft.sound.BlockSoundGroup.DEEPSLATE
                || sound == net.minecraft.sound.BlockSoundGroup.METAL
                || sound == net.minecraft.sound.BlockSoundGroup.GILDED_BLACKSTONE
                || sound == net.minecraft.sound.BlockSoundGroup.NETHERITE
                || sound == net.minecraft.sound.BlockSoundGroup.ANVIL
                || sound == net.minecraft.sound.BlockSoundGroup.LODESTONE
                || sound == net.minecraft.sound.BlockSoundGroup.COPPER) {
            return 0.08f;
        }

        if (sound == net.minecraft.sound.BlockSoundGroup.GLASS) {
            return 0.40f;
        }

        if (sound == net.minecraft.sound.BlockSoundGroup.WOOD
                || sound == net.minecraft.sound.BlockSoundGroup.BAMBOO_WOOD
                || sound == net.minecraft.sound.BlockSoundGroup.CHERRY_WOOD
                || sound == net.minecraft.sound.BlockSoundGroup.NETHER_WOOD) {
            return 0.20f;
        }

        if (sound == net.minecraft.sound.BlockSoundGroup.WOOL
                || sound == net.minecraft.sound.BlockSoundGroup.SNOW
                || sound == net.minecraft.sound.BlockSoundGroup.SLIME
                || sound == net.minecraft.sound.BlockSoundGroup.HONEY
                || sound == net.minecraft.sound.BlockSoundGroup.MOSS_BLOCK
                || sound == net.minecraft.sound.BlockSoundGroup.MOSS_CARPET
                || sound == net.minecraft.sound.BlockSoundGroup.GRASS) {
            return 0.35f;
        }

        if (sound == net.minecraft.sound.BlockSoundGroup.SAND
                || sound == net.minecraft.sound.BlockSoundGroup.GRAVEL
                || sound == net.minecraft.sound.BlockSoundGroup.MUD
                || sound == net.minecraft.sound.BlockSoundGroup.SOUL_SAND) {
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

        public VenuePreset(
                float decayTime,
                float gain,
                float gainHF,
                float gainLF,
                float reflectionsGain,
                float reflectionsDelay,
                float lateReverbGain,
                float lateReverbDelay,
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

    private boolean isAcousticObstacle(BlockState state, World world, BlockPos pos) {
        if (state.isAir()) return false;
        net.minecraft.block.Block block = state.getBlock();
        // Trees and plants scatter sound, they don't enclose spaces
        if (block instanceof net.minecraft.block.LeavesBlock) return false;
        if (block instanceof net.minecraft.block.PlantBlock) return false;
        // Speaker blocks emit sound — they shouldn't block venue scanning rays
        if (block instanceof com.audiophilecraft.block.SpeakerBlock) return false;
        // If the block has no physical collision (like signs, string, etc.), it's transparent
        if (state.getCollisionShape(world, pos).isEmpty()) return false;
        return true;
    }

    /**
     * Cast rays from a single probe position and extract raw metrics.
     * Optionally collects the hit coordinates into outPointCloud.
     */
    public ProbeResult scanProbe(World world, Vec3d probePos, java.util.List<Vec3d> outPointCloud) {
        float[] distances = new float[RAY_COUNT];
        float[] absorptions = new float[RAY_COUNT];
        BlockPos probeBlock = BlockPos.ofFloored(probePos.x, probePos.y, probePos.z);

        for (int i = 0; i < RAY_COUNT; i++) {
            float dirX = RAY_DIRS_NORM[i][0];
            float dirY = RAY_DIRS_NORM[i][1];
            float dirZ = RAY_DIRS_NORM[i][2];
            float hitDist = MAX_RAY_DIST;
            float hitAbsorption = 1.0f;

            BlockPos.Mutable checkPos = new BlockPos.Mutable();

            double startX = probePos.x, startY = probePos.y, startZ = probePos.z;
            int x = (int) Math.floor(startX);
            int y = (int) Math.floor(startY);
            int z = (int) Math.floor(startZ);

            int stepX = dirX > 0 ? 1 : (dirX < 0 ? -1 : 0);
            int stepY = dirY > 0 ? 1 : (dirY < 0 ? -1 : 0);
            int stepZ = dirZ > 0 ? 1 : (dirZ < 0 ? -1 : 0);

            double tMaxX = dirX != 0 ? ((dirX > 0 ? x + 1 : x) - startX) / dirX : Double.POSITIVE_INFINITY;
            double tMaxY = dirY != 0 ? ((dirY > 0 ? y + 1 : y) - startY) / dirY : Double.POSITIVE_INFINITY;
            double tMaxZ = dirZ != 0 ? ((dirZ > 0 ? z + 1 : z) - startZ) / dirZ : Double.POSITIVE_INFINITY;

            double tDeltaX = dirX != 0 ? Math.abs(1.0 / dirX) : Double.POSITIVE_INFINITY;
            double tDeltaY = dirY != 0 ? Math.abs(1.0 / dirY) : Double.POSITIVE_INFINITY;
            double tDeltaZ = dirZ != 0 ? Math.abs(1.0 / dirZ) : Double.POSITIVE_INFINITY;

            double t = 0;
            while (t < MAX_RAY_DIST) {
                checkPos.set(x, y, z);

                if (!checkPos.equals(probeBlock)) {
                    BlockState state = world.getBlockState(checkPos);
                    if (isAcousticObstacle(state, world, checkPos)) {
                        hitDist = (float) t;
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

                if (tMaxX < tMaxY) {
                    if (tMaxX < tMaxZ) {
                        x += stepX;
                        t = tMaxX;
                        tMaxX += tDeltaX;
                    } else {
                        z += stepZ;
                        t = tMaxZ;
                        tMaxZ += tDeltaZ;
                    }
                } else {
                    if (tMaxY < tMaxZ) {
                        y += stepY;
                        t = tMaxY;
                        tMaxY += tDeltaY;
                    } else {
                        z += stepZ;
                        t = tMaxZ;
                        tMaxZ += tDeltaZ;
                    }
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
                if (distances[i] <= 5.0f) nearHits++;
                else if (distances[i] <= 15.0f) midHits++;
                else farHits++;
            } else {
                skyEscapes++;
            }
        }

        float avgAbsorption = totalAbsorption / (float) RAY_COUNT;
        float meanDist = wallsHit > 0 ? totalDist / wallsHit : 10.0f;
        float enclosure = wallsHit / (float) RAY_COUNT;

        // Variance (std dev of ray distances)
        float sumSqDiff = 0;
        for (int i = 0; i < RAY_COUNT; i++) {
            float diff = distances[i] - meanDist;
            sumSqDiff += diff * diff;
        }
        float variance = (float) Math.sqrt(sumSqDiff / (float) RAY_COUNT);

        // ═══════════════════════════════════════════════════════════════
        // IQR OUTLIER CAPPING FOR VOLUME/SURFACE AREA
        // ═══════════════════════════════════════════════════════════════
        // Problem: A single ray escaping through a 1-block hole in a small room
        // travels 200 blocks and hits something far away. Because volume uses dist^3,
        // this ONE ray (200^3 = 8,000,000) dwarfs 999 rays at 5 blocks (5^3 = 125).
        //
        // Solution: Use IQR (Interquartile Range) to detect and cap outliers.
        // - Small room with hole: Q1=4, Q3=6, cap=9 → 200-block ray capped to 9
        // - Open air (Tomorrowland): Q1=80, Q3=200, cap=380 → nothing capped
        // This preserves open-air detection while preventing hole-leak inflation.

        // Collect only wall-hit distances (exclude sky escapes)
        int wallHitCount = 0;
        for (int i = 0; i < RAY_COUNT; i++) {
            if (distances[i] < MAX_RAY_DIST) wallHitCount++;
        }

        float volumeCap = MAX_RAY_DIST; // Default: no capping
        if (wallHitCount >= 4) { // Need at least 4 rays for meaningful IQR
            float[] sorted = new float[wallHitCount];
            int idx = 0;
            for (int i = 0; i < RAY_COUNT; i++) {
                if (distances[i] < MAX_RAY_DIST) sorted[idx++] = distances[i];
            }
            java.util.Arrays.sort(sorted);

            float q1 = sorted[wallHitCount / 4];
            float q3 = sorted[(wallHitCount * 3) / 4];
            float iqr = q3 - q1;
            volumeCap = q3 + 1.5f * iqr;
            // Safety floor: never cap below 10 blocks (prevents over-capping in
            // perfectly uniform tiny rooms where IQR ≈ 0)
            if (volumeCap < 10.0f) volumeCap = Math.max(10.0f, q3 * 2.0f);
        }

        float sumCubeDist = 0;
        float sumSqDist = 0;
        int validVolumeRays = 0;

        for (int i = 0; i < RAY_COUNT; i++) {
            float dist = distances[i];
            // Exclude sky escapes from volume geometry
            if (dist < MAX_RAY_DIST) {
                float capped = Math.min(dist, volumeCap);
                sumCubeDist += (capped * capped * capped);
                sumSqDist += (capped * capped);
                validVolumeRays++;
            }
        }

        if (validVolumeRays == 0) validVolumeRays = 1; // Failsafe

        // Monte Carlo True Geometric Volume: (4/3 * Pi) * average(r^3)
        float averageCubeDist = sumCubeDist / (float) validVolumeRays;
        float trueVolume = (4.0f * (float) Math.PI / 3.0f) * averageCubeDist;

        // Monte Carlo True Geometric Surface Area: 4 * Pi * average(r^2) * 1.2f
        // (roughness factor)
        float averageSqDist = sumSqDist / (float) validVolumeRays;
        float trueSurfaceArea = 4.0f * (float) Math.PI * averageSqDist * 1.2f;

        return new ProbeResult(
                nearHits / (float) RAY_COUNT,
                midHits / (float) RAY_COUNT,
                farHits / (float) RAY_COUNT,
                skyEscapes / (float) RAY_COUNT,
                avgAbsorption,
                meanDist,
                variance,
                enclosure,
                trueVolume,
                trueSurfaceArea,
                distances,
                absorptions);
    }

    private volatile VenueDescriptor lastDescriptor = null;
    private Vec3d lastProbePos = null;
    public static java.util.List<Vec3d> lastPointCloud =
            java.util.Collections.synchronizedList(new java.util.ArrayList<>());
    public static volatile java.util.Set<net.minecraft.util.math.BlockPos> lastVenueBlocks = new java.util.HashSet<>();
    public static java.util.List<net.minecraft.util.math.BlockPos> lastSpeakers =
            java.util.Collections.synchronizedList(new java.util.ArrayList<>());

    public VenueDescriptor getLastDescriptor() {
        return lastDescriptor;
    }

    public Vec3d getLastProbePos() {
        return lastProbePos;
    }

    public static java.util.List<Vec3d> getLastPointCloud() {
        return lastPointCloud;
    }

    public static java.util.Set<net.minecraft.util.math.BlockPos> getLastVenueBlocks() {
        return lastVenueBlocks;
    }

    public static java.util.List<net.minecraft.util.math.BlockPos> getLastSpeakers() {
        return lastSpeakers;
    }

    public VenueDescriptor mergeProbes(java.util.List<ProbeResult> probes) {
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
     * Main entry point: Pure Cluster-Based Venue Scan.
     *
     * @param world          The Minecraft world
     * @param clusterCenters List of physical cluster centers (where sound actually
     *                       emits)
     * @return Locked VenuePreset for the entire playback session
     */
    public VenuePreset scanVenue(World world, java.util.List<Vec3d> clusterCenters) {
        if (world == null || clusterCenters == null || clusterCenters.isEmpty()) return null;

        // ─── POINT CLOUD CACHE ──────────────────────────────
        java.util.List<Vec3d> currentCloud = new java.util.ArrayList<>();

        java.util.List<ProbeResult> probes = new java.util.ArrayList<>();

        // Limit the number of clusters we scan to prevent lag spikes if a user builds
        // 100 isolated speakers
        int maxClustersToScan = Math.min(clusterCenters.size(), 8);

        for (int i = 0; i < maxClustersToScan; i++) {
            Vec3d centerPos = clusterCenters.get(i);
            probes.add(scanProbe(world, centerPos, currentCloud));
        }

        // Compute bounding box volume from aggregated point cloud (stable, probe-position-independent)
        float bboxVolume = computeBoundingBoxVolume(currentCloud);

        // Save the massive aggregated point cloud for GUI rendering
        AdvancedAcousticScanner.lastPointCloud.clear();
        AdvancedAcousticScanner.lastPointCloud.addAll(currentCloud);

        // ─── BUILD VENUE MAP: ONLY blocks where reverb rays hit ─────
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

        // ─── Merge Probes → VenueDescriptor ──────────────
        VenueDescriptor desc = mergeProbes(probes);
        if (desc == null) return null;

        // Use the first cluster's position as the reference probe position for the
        // preset
        Vec3d referencePos = clusterCenters.get(0);

        // ─── Descriptor → VenuePreset ──────────────────────
        this.lastDescriptor = new VenueDescriptor(
                desc.enclosure,
                desc.scale,
                desc.reflectivity,
                desc.diffusion,
                desc.openness,
                desc.earlyDensity,
                desc.latePotential,
                desc.avgAbsorption,
                computeBoundingBoxVolume(currentCloud),
                desc.trueSurfaceArea);
        this.lastProbePos = referencePos;
        return descriptorToPreset(this.lastDescriptor, referencePos);
    }

    private float lerp(float a, float b, float t) {
        return a + (b - a) * Math.max(0.0f, Math.min(1.0f, t));
    }

    /**
     * Compute a probe-position-independent volume from the aggregated point cloud.
     * Uses axis-aligned bounding box: (maxX - minX) × (maxY - minY) × (maxZ - minZ).
     * The point cloud already captures all surfaces hit by rays across all probes.
     */
    private static float computeBoundingBoxVolume(java.util.List<Vec3d> pointCloud) {
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
        com.audiophilecraft.config.LiveTuningConfig cfg = com.audiophilecraft.config.LiveTuningConfig.get();
        float vAvgAbsorption = d.avgAbsorption;
        float vMeanDist = d.scale;
        float vEnclosure = d.enclosure;
        float vOpenness = d.openness;

        // The preset system now handles open-air dynamically via Sabine physics and
        // continuous exponential multipliers.

        // ═══════════════════════════════════════════════════════════════
        // CONTINUOUS OPEN AIR & ENCLOSURE PHYSICS
        // ═══════════════════════════════════════════════════════════════

        // Smoothly blend open-air properties based on vOpenness. 0% open = enclosed,
        // 25% open = fully open air.
        // Continuous open-air blending: raw openness determines effect linearly
        float openAirBlend = Math.max(0.0f, Math.min(1.0f, vOpenness));

        // Effective enclosure: powerfully penalize enclosure as openness increases
        // using an exponential curve.
        // Even 16% openness mathematically means the entire ceiling is gone, so
        // enclosure must drop sharply.
        float opennessPenalty =
                (float) Math.pow(Math.max(0.0f, 1.0f - vOpenness), cfg.openAir_enclosure_penalty_exponent);
        float effectiveEnclosure = vEnclosure * opennessPenalty;
        effectiveEnclosure = Math.max(0.0f, Math.min(1.0f, effectiveEnclosure));

        // Geometry (Sabine formula)
        float vVolume = d.trueVolume;
        // Use the mathematically correct true surface area integrated from the rays
        float surfaceArea = d.trueSurfaceArea;
        float totalSabins = Math.max(0.01f, surfaceArea * vAvgAbsorption);

        // Sabine RT60 (Mid Decay)
        float vDecay = 0.161f * vVolume / totalSabins;

        // Pure Sabine RT60 governs the decay without artificial enclosure curves.

        if (vDecay < 0.1f) vDecay = 0.1f;

        // Open-air decay hard caps removed; Sabine handles open-air volume dynamically.

        if (vDecay > 15.0f) vDecay = 15.0f;

        // ─── REVERB GAIN ────────────────────────────────────────────
        // Use effectiveEnclosure for gain calculation.
        float baseEnclosureMultiplier = 0.5f + (effectiveEnclosure * 0.5f);

        float vGain = baseEnclosureMultiplier * 0.65f;
        float vGainHF = 0.3f + (1.0f - vAvgAbsorption) * 0.6f;
        float vGainLF = 0.7f + effectiveEnclosure * 0.3f;

        // 6-Tier Preset System
        float roomFactor = Math.min(vMeanDist / 30.0f, 1.0f);
        float vReflGain, vReflDelay, lateReverbMultiplier, vLateGain, vLateDelay;

        // Reflection material factor: open air absorbs more (grass, dirt).
        float reflectionMaterialFactor = 1.0f - vAvgAbsorption;

        // Use EFFECTIVE enclosure for tier selection volume.
        // This prevents a stage building from pushing an open-air venue into Tier 5/6.
        float effectiveVolume = vVolume * effectiveEnclosure;
        float effectiveMeanDist = vMeanDist * (float) Math.sqrt(effectiveEnclosure);

        // Tier thresholds are naturally avoided in open air because effectiveVolume
        // drops exponentially.
        boolean tier10 = (effectiveVolume > cfg.tier10_volumeThreshold || effectiveMeanDist > cfg.tier10_distThreshold);
        boolean tier9 = (effectiveVolume > cfg.tier9_volumeThreshold || effectiveMeanDist > cfg.tier9_distThreshold);
        boolean tier8 = (effectiveVolume > cfg.tier8_volumeThreshold || effectiveMeanDist > cfg.tier8_distThreshold);
        boolean tier7 = (effectiveVolume > cfg.tier7_volumeThreshold || effectiveMeanDist > cfg.tier7_distThreshold);

        // Smooth enclosure factor for late multiplier scaling: 0.0 at lowEncl (0.4),
        // 1.0 at highEncl (0.8)
        float enclBlend = Math.max(0.0f, Math.min(1.0f, (effectiveEnclosure - 0.4f) / 0.4f));

        String tierName = "";
        if (tier10) {
            tierName = "TIER 10 (INFINITE CATHEDRAL / VOID)";
            vDecay *= cfg.tier10_decayMul;
            vGain = Math.max(cfg.tier10_minGain, baseEnclosureMultiplier * cfg.tier10_gainMul);
            vReflGain = Math.max(0.0f, reflectionMaterialFactor * cfg.tier10_reflGainMul);
            float maxLate10 =
                    lerp(cfg.tier10_maxLateMultiplier_lowEncl, cfg.tier10_maxLateMultiplier_highEncl, enclBlend);
            lateReverbMultiplier =
                    Math.min(cfg.tier10_lateReverbMul + (roomFactor * cfg.tier10_lateReverbRoomScale), maxLate10);
            vGainHF *= cfg.tier10_hfMul;
            vGainLF *= cfg.tier10_lfMul;
        } else if (tier9) {
            tierName = "TIER 9 (MEGA COMPLEX / CITY BLOCK)";
            vDecay *= cfg.tier9_decayMul;
            vGain = Math.max(cfg.tier9_minGain, baseEnclosureMultiplier * cfg.tier9_gainMul);
            vReflGain = Math.max(0.0f, reflectionMaterialFactor * cfg.tier9_reflGainMul);
            float maxLate9 = lerp(cfg.tier9_maxLateMultiplier_lowEncl, cfg.tier9_maxLateMultiplier_highEncl, enclBlend);
            lateReverbMultiplier =
                    Math.min(cfg.tier9_lateReverbMul + (roomFactor * cfg.tier9_lateReverbRoomScale), maxLate9);
            vGainHF *= cfg.tier9_hfMul;
            vGainLF *= cfg.tier9_lfMul;
        } else if (tier8) {
            tierName = "TIER 8 (COLOSSAL DOME / HANGAR)";
            vDecay *= cfg.tier8_decayMul;
            vGain = Math.max(cfg.tier8_minGain, baseEnclosureMultiplier * cfg.tier8_gainMul);
            vReflGain = Math.max(0.0f, reflectionMaterialFactor * cfg.tier8_reflGainMul);
            float maxLate8 = lerp(cfg.tier8_maxLateMultiplier_lowEncl, cfg.tier8_maxLateMultiplier_highEncl, enclBlend);
            lateReverbMultiplier =
                    Math.min(cfg.tier8_lateReverbMul + (roomFactor * cfg.tier8_lateReverbRoomScale), maxLate8);
            vGainHF *= cfg.tier8_hfMul;
            vGainLF *= cfg.tier8_lfMul;
        } else if (tier7) {
            tierName = "TIER 7 (MASSIVE STADIUM)";
            vDecay *= cfg.tier7_decayMul;
            vGain = Math.max(cfg.tier7_minGain, baseEnclosureMultiplier * cfg.tier7_gainMul);
            vReflGain = Math.max(0.0f, reflectionMaterialFactor * cfg.tier7_reflGainMul);
            float maxLateMultiplier =
                    lerp(cfg.tier7_maxLateMultiplier_lowEncl, cfg.tier7_maxLateMultiplier_highEncl, enclBlend);
            lateReverbMultiplier =
                    Math.min(cfg.tier7_lateReverbMul + (roomFactor * cfg.tier7_lateReverbRoomScale), maxLateMultiplier);
            vGainHF *= cfg.tier7_hfMul;
            vGainLF *= cfg.tier7_lfMul;
        } else if (effectiveVolume > cfg.tier6_volumeThreshold || effectiveMeanDist > cfg.tier6_distThreshold) {
            tierName = "TIER 6 (ARENA / CONCERT HALL)";
            vDecay *= cfg.tier6_decayMul;
            vGain = Math.max(cfg.tier6_minGain, baseEnclosureMultiplier * cfg.tier6_gainMul);
            vReflGain = Math.max(0.0f, reflectionMaterialFactor * cfg.tier6_reflGainMul);
            lateReverbMultiplier = cfg.tier6_lateReverbMul + (roomFactor * cfg.tier6_lateReverbRoomScale);
            vGainHF *= cfg.tier6_hfMul;
            vGainLF *= cfg.tier6_lfMul;
        } else if (effectiveVolume > cfg.tier5_volumeThreshold || effectiveMeanDist > cfg.tier5_distThreshold) {
            tierName = "TIER 5 (LARGE CLUB / GYMNASIUM)";
            // TIER 5: LARGE CLUB / GYMNASIUM (Live Tunable)
            vDecay *= cfg.tier5_decayMul;
            vGain = Math.max(cfg.tier5_minGain, baseEnclosureMultiplier * cfg.tier5_gainMul);
            vReflGain = Math.max(0.0f, reflectionMaterialFactor * cfg.tier5_reflGainMul);
            lateReverbMultiplier = cfg.tier5_lateReverbMul + (roomFactor * cfg.tier5_lateReverbRoomScale);
            vGainHF *= cfg.tier5_hfMul;
            vGainLF *= cfg.tier5_lfMul;
        } else if (effectiveVolume > cfg.tier4_volumeThreshold || effectiveMeanDist > cfg.tier4_distThreshold) {
            tierName = "TIER 4 (LARGE ROOM / SMALL HALL)";
            // TIER 4: LARGE ROOM / SMALL HALL (Live Tunable)
            vDecay *= cfg.tier4_decayMul;
            vGain = Math.max(cfg.tier4_minGain, baseEnclosureMultiplier * cfg.tier4_gainMul);
            vReflGain = Math.max(0.0f, reflectionMaterialFactor * cfg.tier4_reflGainMul);
            lateReverbMultiplier = cfg.tier4_lateReverbMul + (roomFactor * cfg.tier4_lateReverbRoomScale);
            vGainHF *= cfg.tier4_hfMul;
            vGainLF *= cfg.tier4_lfMul;
        } else if (effectiveVolume > cfg.tier3_volumeThreshold || effectiveMeanDist > cfg.tier3_distThreshold) {
            tierName = "TIER 3 (MEDIUM ROOM / STUDIO)";
            // TIER 3: MEDIUM ROOM / STUDIO (Live Tunable)
            vDecay *= cfg.tier3_decayMul;
            vGain = Math.max(cfg.tier3_minGain, baseEnclosureMultiplier * cfg.tier3_gainMul);
            vReflGain = Math.max(0.0f, reflectionMaterialFactor * cfg.tier3_reflGainMul);
            lateReverbMultiplier = cfg.tier3_lateReverbMul;
            vGainHF *= cfg.tier3_hfMul;
            vGainLF *= cfg.tier3_lfMul;
        } else if (effectiveVolume > cfg.tier2_volumeThreshold || effectiveMeanDist > cfg.tier2_distThreshold) {
            tierName = "TIER 2 (SMALL ROOM)";
            // TIER 2: SMALL ROOM (Live Tunable)
            vDecay *= cfg.tier2_decayMul;
            vGain = Math.max(cfg.tier2_minGain, baseEnclosureMultiplier * cfg.tier2_gainMul);
            vReflGain = Math.max(0.0f, reflectionMaterialFactor * cfg.tier2_reflGainMul);
            lateReverbMultiplier = cfg.tier2_lateReverbMul;
            vGainHF *= cfg.tier2_hfMul;
            vGainLF *= cfg.tier2_lfMul;
        } else {
            tierName = "TIER 1 (TINY SPACE / CLOSET)";
            // TIER 1: CLOSET / TINY SPACE (Live Tunable)
            vDecay *= cfg.tier1_decayMul;
            vGain = Math.max(cfg.tier1_minGain, baseEnclosureMultiplier * cfg.tier1_gainMul);
            vReflGain = Math.max(0.0f, reflectionMaterialFactor * cfg.tier1_reflGainMul);
            lateReverbMultiplier = cfg.tier1_lateReverbMul;
            vGainHF *= cfg.tier1_hfMul;
            vGainLF *= cfg.tier1_lfMul;
        }

        // Gökyüzüne giden ışınların %50'si toprağa çarptığı için, dümdüz bir ovada bile
        // açıklık max 0.5 olur.
        // Oyuncuya daha mantıklı (insan algısına uygun) gelmesi için bunu 2 ile çarpıp
        // 100 üzerinden gösteriyoruz.
        int opennessPct = (int) Math.min(100, vOpenness * 200.0f);
        if (vOpenness > 0.25f) {
            tierName += String.format(" [AÇIK HAVA: %%%d]", opennessPct);
        } else if (opennessPct > 2) {
            tierName += String.format(" [Yarı Açık: %%%d]", opennessPct);
        }

        // ─── OPEN AIR: LATE TAIL SUPPRESSION ────────────────────────
        // Sound escapes to the sky — late reverb tail dissipates.
        // Late reverb requires high-order multi-path reflections. In open spaces,
        // energy escapes before forming a tail, so it drops exponentially.
        float tailRetention = (float) Math.pow(effectiveEnclosure, cfg.openAir_enclosure_penalty_exponent);
        float openAirTailMultiplier =
                cfg.openAir_dynamic_lateReverbMul + (tailRetention * (1.0f - cfg.openAir_dynamic_lateReverbMul));
        lateReverbMultiplier *= openAirTailMultiplier;
        vGainHF *= cfg.openAir_dynamic_hfMul;
        vGainLF *= cfg.openAir_dynamic_lfMul;

        // ─── OPEN AIR: EXTRA GAIN SUPPRESSION ───────────────────────
        // General reverb gain drops as the space opens up, but early reflections
        // (vReflGain) remain strong because they bounce off the immediate ground/walls.
        float openAirGainMultiplier = lerp(1.0f, cfg.openAir_dynamic_gainMul, openAirBlend);
        vGain *= openAirGainMultiplier;

        float openAirReflMultiplier = lerp(1.0f, cfg.openAir_dynamic_reflGainMul, openAirBlend);
        vReflGain *= openAirReflMultiplier;

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

        // Apply Tier Overrides
        float tierDensity = -1.0f;
        float tierDiffusion = -1.0f;
        if (tierName.contains("TIER 10")) {
            tierDensity = cfg.tier10_density;
            tierDiffusion = cfg.tier10_diffusion;
        } else if (tierName.contains("TIER 9")) {
            tierDensity = cfg.tier9_density;
            tierDiffusion = cfg.tier9_diffusion;
        } else if (tierName.contains("TIER 8")) {
            tierDensity = cfg.tier8_density;
            tierDiffusion = cfg.tier8_diffusion;
        } else if (tierName.contains("TIER 7")) {
            tierDensity = cfg.tier7_density;
            tierDiffusion = cfg.tier7_diffusion;
        } else if (tierName.contains("TIER 6")) {
            tierDensity = cfg.tier6_density;
            tierDiffusion = cfg.tier6_diffusion;
        } else if (tierName.contains("TIER 5")) {
            tierDensity = cfg.tier5_density;
            tierDiffusion = cfg.tier5_diffusion;
        } else if (tierName.contains("TIER 4")) {
            tierDensity = cfg.tier4_density;
            tierDiffusion = cfg.tier4_diffusion;
        } else if (tierName.contains("TIER 3")) {
            tierDensity = cfg.tier3_density;
            tierDiffusion = cfg.tier3_diffusion;
        } else if (tierName.contains("TIER 2")) {
            tierDensity = cfg.tier2_density;
            tierDiffusion = cfg.tier2_diffusion;
        } else if (tierName.contains("TIER 1 ")) {
            tierDensity = cfg.tier1_density;
            tierDiffusion = cfg.tier1_diffusion;
        }

        if (tierDensity >= 0.0f) vDensity = tierDensity;
        if (tierDiffusion >= 0.0f) vDiffusion = tierDiffusion;

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

        return new VenuePreset(
                vDecay,
                vGain,
                vGainHF,
                vGainLF,
                vReflGain,
                vReflDelay,
                vLateGain,
                vLateDelay,
                vDensity,
                vDiffusion,
                vHFRatio,
                vLFRatio,
                vAirAbs,
                vHFLimit,
                effectiveEnclosure,
                probePos,
                tierName);
    }

    /**
     * Backward-compatible wrapper: single probe scan (used if no stage direction
     * available).
     */
    public VenuePreset scanAtPosition(World world, Vec3d probePos) {
        java.util.List<Vec3d> centers = new java.util.ArrayList<>();
        centers.add(probePos);
        return scanVenue(world, centers);
    }
}
