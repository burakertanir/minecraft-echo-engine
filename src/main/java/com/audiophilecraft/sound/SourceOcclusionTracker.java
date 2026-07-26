package com.audiophilecraft.sound;

import com.audiophilecraft.config.LiveTuningConfig;
import net.minecraft.block.BlockState;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.ChunkStatus;
import net.minecraft.world.chunk.EmptyChunk;

/** Tracks cached raycast occlusion and its asymmetric smoothing state. */
final class SourceOcclusionTracker {
    private static final float RAY_STEP_SIZE = 0.25f;

    private float cachedTarget = 1.0f;
    private float target = 1.0f;
    private float current = 1.0f;
    private long lastCalculationTick = -1;
    private Vec3d lastListenerPosition;

    /**
     * Updates the tracker and returns the target used for smoothing this tick.
     *
     * <p>The cached target is sampled before a new raycast, matching the original
     * one-tick transition behavior in StreamSource.
     */
    float update(
            World world,
            BlockPos sourcePosition,
            Vec3d listenerPosition,
            double distance,
            boolean subwoofer,
            LiveTuningConfig config) {
        float smoothingTarget = cachedTarget;

        if (world != null && distance > 1.5) {
            long nowTick = world.getTime();
            boolean movedEnough =
                    lastListenerPosition == null || lastListenerPosition.squaredDistanceTo(listenerPosition) > 0.04;
            int recalculationInterval = recalculationInterval(distance);
            boolean timeToRecalculate =
                    lastCalculationTick < 0 || nowTick - lastCalculationTick >= recalculationInterval;

            if (movedEnough || timeToRecalculate) {
                float transmission = calculateTransmission(world, sourcePosition, listenerPosition, subwoofer, config);
                if (transmission >= 0.0f) {
                    float newTarget = Math.max(0.002f, transmission);
                    cachedTarget = newTarget;
                    target = newTarget;
                }
                lastCalculationTick = nowTick;
                lastListenerPosition = listenerPosition;
            }
        }

        float lerp = smoothingTarget < current ? config.occ_lerpIn : config.occ_lerpOut;
        current += (smoothingTarget - current) * lerp;
        return smoothingTarget;
    }

    float target() {
        return target;
    }

    float current() {
        return current;
    }

    private static int recalculationInterval(double distance) {
        if (distance < 30.0) return 2;
        if (distance < 100.0) return 20;
        return 40;
    }

    /**
     * Returns -1 when unloaded chunk data prevents a reliable result.
     */
    private static float calculateTransmission(
            World world, BlockPos sourcePosition, Vec3d listenerPosition, boolean subwoofer, LiveTuningConfig config) {
        double originX = sourcePosition.getX() + 0.5;
        double originY = sourcePosition.getY() + 0.5;
        double originZ = sourcePosition.getZ() + 0.5;

        double directionX = listenerPosition.x - originX;
        double directionY = listenerPosition.y - originY;
        double directionZ = listenerPosition.z - originZ;
        double rayLength = Math.sqrt(directionX * directionX + directionY * directionY + directionZ * directionZ);
        if (rayLength < 0.001) rayLength = 0.001;
        directionX /= rayLength;
        directionY /= rayLength;
        directionZ /= rayLength;

        BlockPos.Mutable checkPosition = new BlockPos.Mutable();
        int solidStepCount = 0;
        float minimumTransmission = 1.0f;
        int lastChunkX = Integer.MAX_VALUE;
        int lastChunkZ = Integer.MAX_VALUE;
        Chunk currentChunk = null;

        for (float travelled = RAY_STEP_SIZE; travelled < rayLength; travelled += RAY_STEP_SIZE) {
            int blockX = (int) Math.floor(originX + directionX * travelled);
            int blockY = (int) Math.floor(originY + directionY * travelled);
            int blockZ = (int) Math.floor(originZ + directionZ * travelled);

            if (blockX == sourcePosition.getX() && blockY == sourcePosition.getY() && blockZ == sourcePosition.getZ()) {
                continue;
            }

            checkPosition.set(blockX, blockY, blockZ);
            int chunkX = blockX >> 4;
            int chunkZ = blockZ >> 4;
            if (chunkX != lastChunkX || chunkZ != lastChunkZ) {
                currentChunk = world.getChunk(chunkX, chunkZ, ChunkStatus.FULL, false);
                lastChunkX = chunkX;
                lastChunkZ = chunkZ;
            }

            if (currentChunk == null || currentChunk instanceof EmptyChunk) {
                return -1.0f;
            }

            BlockState state = currentChunk.getBlockState(checkPosition);
            if (!state.isAir()) {
                float blockTransmission = AdvancedAcousticScanner.getBlockTransmission(state, subwoofer);
                if (blockTransmission < 1.0f) {
                    solidStepCount++;
                    if (blockTransmission < minimumTransmission) {
                        minimumTransmission = blockTransmission;
                    }
                }
            }
        }

        if (solidStepCount == 0) return 1.0f;

        float solidDistance = solidStepCount * RAY_STEP_SIZE;
        int blockThickness = (int) Math.max(1, Math.ceil(solidDistance - config.occ_raycast_flexOffset));
        float transmission = minimumTransmission * (float) Math.pow(config.occ_thicknessDecay, blockThickness - 1);
        return Math.max(0.001f, transmission);
    }
}
