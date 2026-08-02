package com.audiophilecraft.sound;

import com.audiophilecraft.config.LiveTuningConfig;
import java.util.HashMap;
import java.util.Map;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.sound.BlockSoundGroup;

final class AcousticMaterialTable {
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

    private AcousticMaterialTable() {}

    static float getAbsorption(Block block) {
        return ABSORPTION_MAP.getOrDefault(block, 0.10f);
    }

    static float getBlockTransmission(BlockState state, boolean isSubwoofer) {
        LiveTuningConfig cfg = LiveTuningConfig.get();
        float transmission = getBaseTransmission(state);

        float standardWall = cfg.occ_standardWall;
        transmission = standardWall + (transmission - standardWall) * cfg.occ_varianceBlend;

        if (isSubwoofer) {
            transmission = (float) Math.sqrt(transmission);
        }

        return Math.max(0.01f, Math.min(1.0f, transmission));
    }

    private static float getBaseTransmission(BlockState state) {
        BlockSoundGroup sound = state.getSoundGroup();

        if (sound == BlockSoundGroup.STONE
                || sound == BlockSoundGroup.DEEPSLATE
                || sound == BlockSoundGroup.METAL
                || sound == BlockSoundGroup.GILDED_BLACKSTONE
                || sound == BlockSoundGroup.NETHERITE
                || sound == BlockSoundGroup.ANVIL
                || sound == BlockSoundGroup.LODESTONE
                || sound == BlockSoundGroup.COPPER) {
            return 0.08f;
        }

        if (sound == BlockSoundGroup.GLASS) {
            return 0.40f;
        }

        if (sound == BlockSoundGroup.WOOD
                || sound == BlockSoundGroup.BAMBOO_WOOD
                || sound == BlockSoundGroup.CHERRY_WOOD
                || sound == BlockSoundGroup.NETHER_WOOD) {
            return 0.20f;
        }

        if (sound == BlockSoundGroup.WOOL
                || sound == BlockSoundGroup.SNOW
                || sound == BlockSoundGroup.SLIME
                || sound == BlockSoundGroup.HONEY
                || sound == BlockSoundGroup.MOSS_BLOCK
                || sound == BlockSoundGroup.MOSS_CARPET
                || sound == BlockSoundGroup.GRASS) {
            return 0.35f;
        }

        if (sound == BlockSoundGroup.SAND
                || sound == BlockSoundGroup.GRAVEL
                || sound == BlockSoundGroup.MUD
                || sound == BlockSoundGroup.SOUL_SAND) {
            return 0.25f;
        }

        return 0.15f;
    }
}
