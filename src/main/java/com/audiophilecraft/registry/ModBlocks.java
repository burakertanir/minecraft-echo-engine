package com.audiophilecraft.registry;

import com.audiophilecraft.AudiophileCraft;
import com.audiophilecraft.block.LineArrayBlock;
import com.audiophilecraft.block.MidRangeBlock;
import com.audiophilecraft.block.SubwooferBlock;
import net.fabricmc.fabric.api.item.v1.FabricItemSettings;
import net.fabricmc.fabric.api.object.builder.v1.block.FabricBlockSettings;
import net.minecraft.block.Block;
import net.minecraft.block.Blocks;
import net.minecraft.item.BlockItem;
import net.minecraft.item.Item;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.util.Identifier;

public class ModBlocks {

    public static final Block SUBWOOFER =
            registerBlock("subwoofer", new SubwooferBlock(FabricBlockSettings.copyOf(Blocks.NOTE_BLOCK)));

    public static final Block MID_RANGE =
            registerBlock("mid_range", new MidRangeBlock(FabricBlockSettings.copyOf(Blocks.NOTE_BLOCK)));

    public static final Block LINE_ARRAY =
            registerBlock("line_array", new LineArrayBlock(FabricBlockSettings.copyOf(Blocks.NOTE_BLOCK)));

    private static Block registerBlock(String name, Block block) {
        registerBlockItem(name, block);
        return Registry.register(Registries.BLOCK, new Identifier("audiophilecraft", name), block);
    }

    private static Item registerBlockItem(String name, Block block) {
        return Registry.register(
                Registries.ITEM,
                new Identifier("audiophilecraft", name),
                new BlockItem(block, new FabricItemSettings()));
    }

    public static void registerModBlocks() {
        AudiophileCraft.LOGGER.debug("Registered AudiophileCraft blocks.");
    }
}
