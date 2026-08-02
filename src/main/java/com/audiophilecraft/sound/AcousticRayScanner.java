package com.audiophilecraft.sound;

import com.audiophilecraft.block.SpeakerBlock;
import java.util.List;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.LeavesBlock;
import net.minecraft.block.PlantBlock;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;

final class AcousticRayScanner {
    static final int RAY_COUNT = 1000;

    private static final int MAX_RAY_DIST = 256;
    private static final float[][] RAY_DIRS_NORM = createRayDirections();

    static boolean hasClearPath(AdvancedAcousticScanner.ProbeResult probeResult, Vec3d from, Vec3d to) {
        if (probeResult == null || probeResult.distances == null || from == null || to == null) return false;

        double deltaX = to.x - from.x;
        double deltaY = to.y - from.y;
        double deltaZ = to.z - from.z;
        double targetDistance = Math.sqrt(deltaX * deltaX + deltaY * deltaY + deltaZ * deltaZ);
        if (targetDistance <= 2.0) return true;

        double directionX = deltaX / targetDistance;
        double directionY = deltaY / targetDistance;
        double directionZ = deltaZ / targetDistance;
        double minimumDot = Math.cos(Math.toRadians(8.0));
        double bestDot = -1.0;
        float bestRayDistance = 0.0f;
        int rayCount = Math.min(probeResult.distances.length, RAY_DIRS_NORM.length);
        for (int index = 0; index < rayCount; index++) {
            float[] ray = RAY_DIRS_NORM[index];
            double dot = ray[0] * directionX + ray[1] * directionY + ray[2] * directionZ;
            if (dot > bestDot) {
                bestDot = dot;
                bestRayDistance = probeResult.distances[index];
            }
            if (dot >= minimumDot && probeResult.distances[index] + 2.0f >= targetDistance) return true;
        }
        return bestRayDistance + 2.0f >= targetDistance;
    }

    AdvancedAcousticScanner.ProbeResult scan(World world, Vec3d probePos, List<Vec3d> outPointCloud) {
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

            double startX = probePos.x;
            double startY = probePos.y;
            double startZ = probePos.z;
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
                        hitAbsorption = AcousticMaterialTable.getAbsorption(state.getBlock());
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
                } else if (tMaxY < tMaxZ) {
                    y += stepY;
                    t = tMaxY;
                    tMaxY += tDeltaY;
                } else {
                    z += stepZ;
                    t = tMaxZ;
                    tMaxZ += tDeltaZ;
                }
            }
            distances[i] = hitDist;
            absorptions[i] = hitAbsorption;
        }

        if (outPointCloud != null) {
            for (int i = 0; i < RAY_COUNT; i++) {
                if (distances[i] >= MAX_RAY_DIST) {
                    continue;
                }

                outPointCloud.add(new Vec3d(
                        probePos.x + RAY_DIRS_NORM[i][0] * distances[i],
                        probePos.y + RAY_DIRS_NORM[i][1] * distances[i],
                        probePos.z + RAY_DIRS_NORM[i][2] * distances[i]));
            }
        }

        int nearHits = 0;
        int midHits = 0;
        int farHits = 0;
        int skyEscapes = 0;
        float totalAbsorption = 0;
        float totalDist = 0;
        int wallsHit = 0;

        for (int i = 0; i < RAY_COUNT; i++) {
            totalAbsorption += absorptions[i];
            if (distances[i] < MAX_RAY_DIST) {
                wallsHit++;
                totalDist += distances[i];
                if (distances[i] <= 5.0f) {
                    nearHits++;
                } else if (distances[i] <= 15.0f) {
                    midHits++;
                } else {
                    farHits++;
                }
            } else {
                skyEscapes++;
            }
        }

        float avgAbsorption = totalAbsorption / RAY_COUNT;
        float meanDist = wallsHit > 0 ? totalDist / wallsHit : 10.0f;
        float enclosure = wallsHit / (float) RAY_COUNT;

        float sumSqDiff = 0;
        for (int i = 0; i < RAY_COUNT; i++) {
            if (distances[i] < MAX_RAY_DIST) {
                float diff = distances[i] - meanDist;
                sumSqDiff += diff * diff;
            }
        }
        float variance = wallsHit > 0 ? (float) Math.sqrt(sumSqDiff / wallsHit) : 0.0f;

        float sumCubeDist = 0;
        float sumSqDist = 0;
        int validVolumeRays = 0;

        for (float dist : distances) {
            if (dist < MAX_RAY_DIST) {
                sumCubeDist += dist * dist * dist;
                sumSqDist += dist * dist;
                validVolumeRays++;
            }
        }

        if (validVolumeRays == 0) {
            validVolumeRays = 1;
        }

        float averageCubeDist = sumCubeDist / validVolumeRays;
        float trueVolume = (4.0f * (float) Math.PI / 3.0f) * averageCubeDist;
        float averageSqDist = sumSqDist / validVolumeRays;
        float trueSurfaceArea = 4.0f * (float) Math.PI * averageSqDist * 1.2f;

        return new AdvancedAcousticScanner.ProbeResult(
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

    private static float[][] createRayDirections() {
        float[][] directions = new float[RAY_COUNT][3];
        float phi = (float) (Math.PI * (3.0 - Math.sqrt(5.0)));

        for (int i = 0; i < RAY_COUNT; i++) {
            float y = 1 - (i / (float) (RAY_COUNT - 1)) * 2;
            float radius = (float) Math.sqrt(1 - y * y);
            float theta = phi * i;

            directions[i][0] = (float) Math.cos(theta) * radius;
            directions[i][1] = y;
            directions[i][2] = (float) Math.sin(theta) * radius;
        }

        return directions;
    }

    private static boolean isAcousticObstacle(BlockState state, World world, BlockPos pos) {
        if (state.isAir()) {
            return false;
        }

        Block block = state.getBlock();
        if (block instanceof LeavesBlock || block instanceof PlantBlock || block instanceof SpeakerBlock) {
            return false;
        }

        return !state.getCollisionShape(world, pos).isEmpty();
    }
}
