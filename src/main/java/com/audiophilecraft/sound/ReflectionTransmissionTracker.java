package com.audiophilecraft.sound;

import com.audiophilecraft.block.SpeakerBlock;
import com.audiophilecraft.config.LiveTuningConfig;
import java.util.List;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.LeavesBlock;
import net.minecraft.block.PlantBlock;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.ChunkStatus;
import net.minecraft.world.chunk.EmptyChunk;

/** Tracks low-cost, emitter-group-level visibility of scanned reflection surfaces. */
final class ReflectionTransmissionTracker {
    private static final int MOVING_UPDATE_INTERVAL_TICKS = 5;
    private static final int STATIONARY_UPDATE_INTERVAL_TICKS = 10;
    private static final double LISTENER_MOVE_THRESHOLD_SQ = 0.25;
    private static final double WALL_INSET_BLOCKS = 0.35;
    private static final double MAX_RAY_DISTANCE = 192.0;
    private static final int MAX_OCCLUDING_BLOCKS = 8;
    private static final float GAIN_ATTACK = 0.12f;
    private static final float GAIN_RELEASE = 0.06f;
    private static final float HIGH_FREQUENCY_FLOOR = 0.05f;

    private volatile float smoothedGain = 1.0f;
    private float targetGain = 1.0f;
    private long lastCalculationTick = Long.MIN_VALUE;
    private Vec3d lastListenerPosition;
    private boolean rescanRequested = true;

    void setReflectionPoints(List<Vec3d> reflectionPoints) {
        rescanRequested = true;
        if (reflectionPoints == null || reflectionPoints.isEmpty()) {
            targetGain = 1.0f;
        }
    }

    void update(World world, Vec3d emitterCenter, List<Vec3d> reflectionPoints, Vec3d listenerPosition) {
        if (world == null || emitterCenter == null || listenerPosition == null) return;

        if (reflectionPoints == null || reflectionPoints.isEmpty()) {
            targetGain = 1.0f;
            smooth();
            return;
        }

        long currentTick = world.getTime();
        boolean listenerMoved = lastListenerPosition == null
                || lastListenerPosition.squaredDistanceTo(listenerPosition) >= LISTENER_MOVE_THRESHOLD_SQ;
        int interval = listenerMoved ? MOVING_UPDATE_INTERVAL_TICKS : STATIONARY_UPDATE_INTERVAL_TICKS;
        if (rescanRequested || currentTick - lastCalculationTick >= interval) {
            float measuredTransmission = measureTransmission(world, emitterCenter, reflectionPoints, listenerPosition);
            if (measuredTransmission >= 0.0f) {
                targetGain = measuredTransmission;
            }
            // Unloaded or excessively distant paths retain the last stable value,
            // but still observe the interval so they cannot trigger every tick.
            lastCalculationTick = currentTick;
            lastListenerPosition = listenerPosition;
            rescanRequested = false;
        }

        smooth();
    }

    float gain() {
        return smoothedGain;
    }

    float highFrequencyGain() {
        float exponent = Math.max(0.1f, LiveTuningConfig.get().masterOcc_hfExponent);
        float filtered = (float) Math.pow(Math.max(0.0f, smoothedGain), exponent);
        return HIGH_FREQUENCY_FLOOR + (1.0f - HIGH_FREQUENCY_FLOOR) * filtered;
    }

    private void smooth() {
        float rate = targetGain < smoothedGain ? GAIN_ATTACK : GAIN_RELEASE;
        smoothedGain += (targetGain - smoothedGain) * rate;
        smoothedGain = clamp01(smoothedGain);
    }

    private static float measureTransmission(
            World world, Vec3d emitterCenter, List<Vec3d> reflectionPoints, Vec3d listenerPosition) {
        float squaredTransmissionSum = 0.0f;
        int validRayCount = 0;

        for (Vec3d reflectionPoint : reflectionPoints) {
            Vec3d rayOrigin = insetFromWall(reflectionPoint, emitterCenter);
            float transmission = traceTransmission(world, rayOrigin, listenerPosition);
            if (transmission < 0.0f) continue;

            squaredTransmissionSum += transmission * transmission;
            validRayCount++;
        }

        if (validRayCount == 0) return -1.0f;
        return clamp01((float) Math.sqrt(squaredTransmissionSum / validRayCount));
    }

    private static Vec3d insetFromWall(Vec3d reflectionPoint, Vec3d emitterCenter) {
        Vec3d towardEmitter = emitterCenter.subtract(reflectionPoint);
        double length = towardEmitter.length();
        if (length < 0.001) return reflectionPoint;
        return reflectionPoint.add(towardEmitter.multiply(WALL_INSET_BLOCKS / length));
    }

    /**
     * Voxel DDA visits each crossed block once. This is substantially cheaper
     * than the quarter-block stepping used by direct source occlusion.
     */
    private static float traceTransmission(World world, Vec3d origin, Vec3d target) {
        double directionX = target.x - origin.x;
        double directionY = target.y - origin.y;
        double directionZ = target.z - origin.z;
        double rayLength = Math.sqrt(directionX * directionX + directionY * directionY + directionZ * directionZ);
        if (rayLength < 0.001) return 1.0f;
        if (rayLength > MAX_RAY_DISTANCE) return -1.0f;

        directionX /= rayLength;
        directionY /= rayLength;
        directionZ /= rayLength;

        int x = (int) Math.floor(origin.x);
        int y = (int) Math.floor(origin.y);
        int z = (int) Math.floor(origin.z);
        int endX = (int) Math.floor(target.x);
        int endY = (int) Math.floor(target.y);
        int endZ = (int) Math.floor(target.z);
        int startX = x;
        int startY = y;
        int startZ = z;

        int stepX = directionX > 0.0 ? 1 : directionX < 0.0 ? -1 : 0;
        int stepY = directionY > 0.0 ? 1 : directionY < 0.0 ? -1 : 0;
        int stepZ = directionZ > 0.0 ? 1 : directionZ < 0.0 ? -1 : 0;

        double nextBoundaryX = directionX > 0.0 ? x + 1.0 : x;
        double nextBoundaryY = directionY > 0.0 ? y + 1.0 : y;
        double nextBoundaryZ = directionZ > 0.0 ? z + 1.0 : z;
        double tMaxX = directionX != 0.0 ? (nextBoundaryX - origin.x) / directionX : Double.POSITIVE_INFINITY;
        double tMaxY = directionY != 0.0 ? (nextBoundaryY - origin.y) / directionY : Double.POSITIVE_INFINITY;
        double tMaxZ = directionZ != 0.0 ? (nextBoundaryZ - origin.z) / directionZ : Double.POSITIVE_INFINITY;
        double tDeltaX = directionX != 0.0 ? Math.abs(1.0 / directionX) : Double.POSITIVE_INFINITY;
        double tDeltaY = directionY != 0.0 ? Math.abs(1.0 / directionY) : Double.POSITIVE_INFINITY;
        double tDeltaZ = directionZ != 0.0 ? Math.abs(1.0 / directionZ) : Double.POSITIVE_INFINITY;

        BlockPos.Mutable checkPosition = new BlockPos.Mutable();
        Chunk currentChunk = null;
        int currentChunkX = Integer.MAX_VALUE;
        int currentChunkZ = Integer.MAX_VALUE;
        int solidBlockCount = 0;
        float minimumTransmission = 1.0f;
        double travelled = 0.0;

        while (travelled <= rayLength) {
            boolean isStartCell = x == startX && y == startY && z == startZ;
            boolean isEndCell = x == endX && y == endY && z == endZ;
            if (!isStartCell && !isEndCell) {
                int chunkX = x >> 4;
                int chunkZ = z >> 4;
                if (chunkX != currentChunkX || chunkZ != currentChunkZ) {
                    currentChunk = world.getChunk(chunkX, chunkZ, ChunkStatus.FULL, false);
                    currentChunkX = chunkX;
                    currentChunkZ = chunkZ;
                }
                if (currentChunk == null || currentChunk instanceof EmptyChunk) return -1.0f;

                checkPosition.set(x, y, z);
                BlockState state = currentChunk.getBlockState(checkPosition);
                if (isAcousticObstacle(state, world, checkPosition)) {
                    solidBlockCount++;
                    minimumTransmission = Math.min(
                            minimumTransmission, AcousticMaterialTable.getBlockTransmission(state, false));
                    if (solidBlockCount >= MAX_OCCLUDING_BLOCKS) {
                        return applyThicknessDecay(minimumTransmission, solidBlockCount);
                    }
                }
            }

            if (isEndCell) break;
            if (tMaxX < tMaxY) {
                if (tMaxX < tMaxZ) {
                    x += stepX;
                    travelled = tMaxX;
                    tMaxX += tDeltaX;
                } else {
                    z += stepZ;
                    travelled = tMaxZ;
                    tMaxZ += tDeltaZ;
                }
            } else if (tMaxY < tMaxZ) {
                y += stepY;
                travelled = tMaxY;
                tMaxY += tDeltaY;
            } else {
                z += stepZ;
                travelled = tMaxZ;
                tMaxZ += tDeltaZ;
            }
        }

        if (solidBlockCount == 0) return 1.0f;
        return applyThicknessDecay(minimumTransmission, solidBlockCount);
    }

    private static float applyThicknessDecay(float minimumTransmission, int solidBlockCount) {
        float thicknessDecay = clamp01(LiveTuningConfig.get().occ_thicknessDecay);
        float transmission = minimumTransmission * (float) Math.pow(thicknessDecay, solidBlockCount - 1);
        return Math.max(0.001f, transmission);
    }

    private static boolean isAcousticObstacle(BlockState state, World world, BlockPos position) {
        if (state.isAir()) return false;
        Block block = state.getBlock();
        if (block instanceof LeavesBlock || block instanceof PlantBlock || block instanceof SpeakerBlock) return false;
        return !state.getCollisionShape(world, position).isEmpty();
    }

    private static float clamp01(float value) {
        return Math.max(0.0f, Math.min(1.0f, value));
    }
}
