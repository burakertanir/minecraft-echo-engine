package com.audiophilecraft.gametest;

import com.audiophilecraft.block.entity.SpeakerBlockEntity;
import com.audiophilecraft.item.AmplifierTabletItem;
import com.audiophilecraft.registry.ModBlocks;
import com.audiophilecraft.registry.ModItems;
import com.audiophilecraft.registry.SpeakerAccessState;
import com.audiophilecraft.registry.SpeakerRegistry;
import java.util.List;
import java.util.UUID;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.block.Block;
import net.minecraft.item.ItemStack;
import net.minecraft.server.network.ServerPlayerEntity;
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

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE, tickLimit = 20)
    public void sharedBuildTargetOwnsPlacedSpeakersAndPrivateModeRevokesIt(TestContext context) {
        ServerPlayerEntity owner = context.createMockCreativeServerPlayerInWorld();
        ServerPlayerEntity builder = context.createMockCreativeServerPlayerInWorld();
        SpeakerAccessState accessState =
                SpeakerAccessState.get(context.getWorld().getServer());
        accessState.setShared(owner.getUuid(), true);
        accessState.setPlacementTarget(builder.getUuid(), owner.getUuid());

        BlockPos sharedRelativePosition = new BlockPos(1, 1, 1);
        context.setBlockState(sharedRelativePosition, ModBlocks.LINE_ARRAY);
        BlockPos sharedAbsolutePosition = context.getAbsolutePos(sharedRelativePosition);
        ModBlocks.LINE_ARRAY.onPlaced(
                context.getWorld(),
                sharedAbsolutePosition,
                context.getWorld().getBlockState(sharedAbsolutePosition),
                builder,
                new ItemStack(ModBlocks.LINE_ARRAY));
        context.assertTrue(
                owner.getUuid()
                        .equals(SpeakerRegistry.getOwner(context.getWorld().getRegistryKey(), sharedAbsolutePosition)),
                "Shared placement did not assign the selected owner's UUID");

        accessState.setShared(owner.getUuid(), false);
        BlockPos privateRelativePosition = new BlockPos(2, 1, 1);
        context.setBlockState(privateRelativePosition, ModBlocks.LINE_ARRAY);
        BlockPos privateAbsolutePosition = context.getAbsolutePos(privateRelativePosition);
        ModBlocks.LINE_ARRAY.onPlaced(
                context.getWorld(),
                privateAbsolutePosition,
                context.getWorld().getBlockState(privateAbsolutePosition),
                builder,
                new ItemStack(ModBlocks.LINE_ARRAY));
        context.assertTrue(
                builder.getUuid()
                        .equals(SpeakerRegistry.getOwner(context.getWorld().getRegistryKey(), privateAbsolutePosition)),
                "Private access did not fall back to the builder's UUID");
        context.complete();
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE, tickLimit = 20)
    public void staleTabletOwnerSelectionCanBeCleared(TestContext context) {
        ItemStack tablet = new ItemStack(ModItems.AMPLIFIER_TABLET);
        UUID selectedOwner = UUID.randomUUID();
        AmplifierTabletItem.setSelectedOwner(tablet, selectedOwner);
        context.assertTrue(
                selectedOwner.equals(AmplifierTabletItem.getSelectedOwner(tablet)),
                "Tablet did not persist its selected speaker owner");

        AmplifierTabletItem.setSelectedOwner(tablet, null);

        context.assertTrue(
                AmplifierTabletItem.getSelectedOwner(tablet) == null,
                "Tablet kept a stale selected owner after access was revoked");
        context.complete();
    }
}
