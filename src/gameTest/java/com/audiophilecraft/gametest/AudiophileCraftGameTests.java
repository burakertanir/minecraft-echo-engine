package com.audiophilecraft.gametest;

import com.audiophilecraft.block.entity.SpeakerBlockEntity;
import com.audiophilecraft.registry.ModBlocks;
import com.audiophilecraft.registry.SpeakerRegistry;
import java.util.List;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.block.Block;
import net.minecraft.test.GameTest;
import net.minecraft.test.TestContext;
import net.minecraft.util.math.BlockPos;

public final class AudiophileCraftGameTests implements FabricGameTest {
    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE, tickLimit = 20)
    public void allSpeakerBlocksCreateTheirServerBlockEntity(TestContext context) {
        List<Block> speakerBlocks = List.of(ModBlocks.SUBWOOFER, ModBlocks.MID_RANGE, ModBlocks.LINE_ARRAY);

        for (int index = 0; index < speakerBlocks.size(); index++) {
            BlockPos relativePosition = new BlockPos(index + 1, 1, 1);
            Block speakerBlock = speakerBlocks.get(index);
            context.setBlockState(relativePosition, speakerBlock);
            context.expectBlock(speakerBlock, relativePosition);
            context.assertTrue(
                    context.getBlockEntity(relativePosition) instanceof SpeakerBlockEntity,
                    "Speaker block did not create SpeakerBlockEntity: " + speakerBlock);
        }

        context.complete();
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE, tickLimit = 20)
    public void placingAndRemovingSpeakerUpdatesTheDimensionRegistry(TestContext context) {
        BlockPos relativePosition = new BlockPos(1, 1, 1);
        BlockPos absolutePosition = context.getAbsolutePos(relativePosition);
        var dimension = context.getWorld().getRegistryKey();
        SpeakerRegistry.clear(dimension);

        context.setBlockState(relativePosition, ModBlocks.LINE_ARRAY);

        context.assertTrue(
                SpeakerRegistry.findSpeakersInRange(dimension, absolutePosition, 0.0)
                        .contains(absolutePosition),
                "Placed speaker was not registered in its server dimension");

        context.removeBlock(relativePosition);

        context.assertTrue(
                !SpeakerRegistry.findSpeakersInRange(dimension, absolutePosition, 0.0)
                        .contains(absolutePosition),
                "Removed speaker remained in its server dimension registry");
        context.complete();
    }
}
