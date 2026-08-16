package com.audiophilecraft.block;

import com.audiophilecraft.block.entity.SpeakerBlockEntity;
import com.audiophilecraft.registry.SpeakerAccessState;
import com.audiophilecraft.registry.SpeakerRegistry;
import java.util.UUID;
import net.minecraft.block.Block;
import net.minecraft.block.BlockEntityProvider;
import net.minecraft.block.BlockState;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemPlacementContext;
import net.minecraft.item.ItemStack;
import net.minecraft.screen.NamedScreenHandlerFactory;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.state.StateManager;
import net.minecraft.state.property.DirectionProperty;
import net.minecraft.state.property.Properties;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.world.World;
import org.jetbrains.annotations.Nullable;

public class SpeakerBlock extends Block implements BlockEntityProvider {
    public static final DirectionProperty FACING = Properties.HORIZONTAL_FACING;

    public SpeakerBlock(Settings settings) {
        super(settings);
        setDefaultState(getDefaultState().with(FACING, Direction.NORTH));
    }

    @Override
    protected void appendProperties(StateManager.Builder<net.minecraft.block.Block, BlockState> builder) {
        builder.add(FACING);
    }

    @Nullable
    @Override
    public BlockState getPlacementState(ItemPlacementContext ctx) {
        return getDefaultState().with(FACING, ctx.getHorizontalPlayerFacing().getOpposite());
    }

    @Nullable
    @Override
    public BlockEntity createBlockEntity(BlockPos pos, BlockState state) {
        return new SpeakerBlockEntity(pos, state);
    }

    @Override
    public void onPlaced(
            World world, BlockPos pos, BlockState state, @Nullable LivingEntity placer, ItemStack itemStack) {
        if (!world.isClient) {
            if (placer instanceof ServerPlayerEntity player) {
                UUID owner = SpeakerAccessState.get(player.getServer()).resolvePlacementOwner(player.getUuid());
                BlockEntity blockEntity = world.getBlockEntity(pos);
                if (blockEntity instanceof SpeakerBlockEntity speaker) {
                    speaker.setOwnerUUID(owner);
                }
                SpeakerRegistry.registerSpeaker(world, pos, owner);
            } else {
                SpeakerRegistry.registerSpeaker(world.getRegistryKey(), pos);
            }
        }
    }

    @SuppressWarnings("deprecation")
    @Override
    public void onStateReplaced(BlockState state, World world, BlockPos pos, BlockState newState, boolean moved) {
        if (!state.isOf(newState.getBlock())) {
            if (!world.isClient) {
                SpeakerRegistry.unregisterSpeaker(world, pos);
            }
            super.onStateReplaced(state, world, pos, newState, moved);
        }
    }

    @SuppressWarnings("deprecation")
    @Override
    public NamedScreenHandlerFactory createScreenHandlerFactory(BlockState state, World world, BlockPos pos) {
        BlockEntity blockEntity = world.getBlockEntity(pos);
        return blockEntity instanceof NamedScreenHandlerFactory ? (NamedScreenHandlerFactory) blockEntity : null;
    }

    @SuppressWarnings("deprecation")
    @Override
    public ActionResult onUse(
            BlockState state, World world, BlockPos pos, PlayerEntity player, Hand hand, BlockHitResult hit) {
        if (!world.isClient) {
            if (player instanceof ServerPlayerEntity serverPlayer) {
                UUID owner = SpeakerRegistry.getOwner(world.getRegistryKey(), pos);
                if (owner != null
                        && !SpeakerAccessState.get(serverPlayer.getServer()).canAccess(serverPlayer.getUuid(), owner)) {
                    serverPlayer.sendMessage(Text.literal("This speaker system is private."), true);
                    return ActionResult.CONSUME;
                }
            }
            NamedScreenHandlerFactory screenHandlerFactory = state.createScreenHandlerFactory(world, pos);

            if (screenHandlerFactory != null) {
                player.openHandledScreen(screenHandlerFactory);
            }
        }
        return ActionResult.SUCCESS;
    }
}
