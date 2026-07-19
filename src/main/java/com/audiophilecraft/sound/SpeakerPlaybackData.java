package com.audiophilecraft.sound;

import com.audiophilecraft.block.LineArrayBlock;
import com.audiophilecraft.block.MidRangeBlock;
import com.audiophilecraft.block.SubwooferBlock;
import com.audiophilecraft.block.entity.SpeakerBlockEntity;
import net.minecraft.block.BlockState;
import net.minecraft.state.property.Properties;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.world.World;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.ChunkStatus;
import net.minecraft.world.chunk.EmptyChunk;

/** Stable speaker metadata used to create audio sources even when the client chunk is unloaded. */
public record SpeakerPlaybackData(
        BlockPos position,
        String speakerType,
        Direction facing,
        int verticalTiltDeg,
        int sampleShiftMs,
        int channelMask) {

    public SpeakerPlaybackData {
        position = position.toImmutable();
        speakerType = normalizeType(speakerType);
        facing = facing == null || facing == Direction.UP || facing == Direction.DOWN ? Direction.SOUTH : facing;
        verticalTiltDeg = Math.max(-70, Math.min(70, verticalTiltDeg));
        sampleShiftMs = Math.max(0, Math.min(30, sampleShiftMs));
        channelMask = Math.max(0, Math.min(2, channelMask));
    }

    public static SpeakerPlaybackData capture(World world, BlockPos position) {
        if (world == null || position == null) return unknown(position);

        BlockState state = world.getBlockState(position);
        String type = "normal";
        if (state.getBlock() instanceof SubwooferBlock) {
            type = "sub";
        } else if (state.getBlock() instanceof MidRangeBlock) {
            type = "mid";
        } else if (state.getBlock() instanceof LineArrayBlock) {
            type = "line";
        }

        Direction direction = state.contains(Properties.HORIZONTAL_FACING)
                ? state.get(Properties.HORIZONTAL_FACING)
                : Direction.SOUTH;
        int tilt = 0;
        int shift = 0;
        int mask = 0;
        if (world.getBlockEntity(position) instanceof SpeakerBlockEntity speaker) {
            tilt = speaker.getVerticalTilt();
            shift = speaker.getSampleShift();
            mask = speaker.getChannelMask();
        }
        return new SpeakerPlaybackData(position, type, direction, tilt, shift, mask);
    }

    public static SpeakerPlaybackData unknown(BlockPos position) {
        BlockPos safePosition = position != null ? position : BlockPos.ORIGIN;
        return new SpeakerPlaybackData(safePosition, "normal", Direction.SOUTH, 0, 0, 0);
    }

    public static boolean isChunkLoaded(World world, BlockPos position) {
        if (world == null || position == null) return false;
        Chunk chunk = world.getChunk(position.getX() >> 4, position.getZ() >> 4, ChunkStatus.FULL, false);
        return chunk != null && !(chunk instanceof EmptyChunk);
    }

    private static String normalizeType(String speakerType) {
        if ("sub".equals(speakerType) || "mid".equals(speakerType) || "line".equals(speakerType)) {
            return speakerType;
        }
        return "normal";
    }
}
