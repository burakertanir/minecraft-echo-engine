package com.audiophilecraft.sound;

import com.audiophilecraft.block.LineArrayBlock;
import com.audiophilecraft.block.MidRangeBlock;
import com.audiophilecraft.block.SubwooferBlock;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

/**
 * Stateless utility for grouping speakers by proximity and detecting types.
 * Extracted from AudioEngine to reduce god-class size.
 */
public class SpeakerClusterer {

    /** Default proximity threshold for cluster membership (blocks). */
    public static final double CLUSTER_DISTANCE = 8.0;

    /**
     * Groups speaker positions into clusters based on proximity.
     * Speakers within {@link #CLUSTER_DISTANCE} blocks of any member join the same cluster.
     * Input is sorted for deterministic output (ConcurrentHashMap iteration is not guaranteed).
     */
    public static List<List<BlockPos>> clusterSpeakers(List<BlockPos> speakers) {
        List<BlockPos> sorted = new ArrayList<>(speakers);
        Collections.sort(sorted, Comparator.comparingLong(BlockPos::asLong));

        List<List<BlockPos>> clusters = new ArrayList<>();
        for (BlockPos pos : sorted) {
            boolean added = false;
            for (List<BlockPos> cluster : clusters) {
                for (BlockPos cPos : cluster) {
                    if (cPos.getSquaredDistance(pos) <= CLUSTER_DISTANCE) {
                        cluster.add(pos);
                        added = true;
                        break;
                    }
                }
                if (added) break;
            }
            if (!added) {
                List<BlockPos> newCluster = new ArrayList<>();
                newCluster.add(pos);
                clusters.add(newCluster);
            }
        }
        return clusters;
    }

    /**
     * Counts speakers by type.
     * @return int[4] = [subCount, midCount, lineCount, normalCount]
     */
    public static int[] countSpeakerTypes(List<BlockPos> speakers, World world) {
        int sub = 0, mid = 0, line = 0, normal = 0;
        if (world != null) {
            for (BlockPos pos : speakers) {
                var block = world.getBlockState(pos).getBlock();
                if (block instanceof SubwooferBlock) sub++;
                else if (block instanceof MidRangeBlock) mid++;
                else if (block instanceof LineArrayBlock) line++;
                else normal++;
            }
        }
        return new int[] {sub, mid, line, normal};
    }

    /** Holds speaker metadata extracted from world state at a position. */
    public static class SpeakerInfo {
        public final String type; // "sub", "mid", "line", "normal"
        public final float baseRefDist;
        public final float baseMaxDist;
        public final int sampleShiftMs;
        public final int speakerCount; // total count of this type across all clusters

        SpeakerInfo(String type, float baseRefDist, float baseMaxDist, int sampleShiftMs, int speakerCount) {
            this.type = type;
            this.baseRefDist = baseRefDist;
            this.baseMaxDist = baseMaxDist;
            this.sampleShiftMs = sampleShiftMs;
            this.speakerCount = speakerCount;
        }
    }

    /**
     * Reads speaker type, distance params, and sample shift from the world at a position.
     */
    public static SpeakerInfo detectSpeaker(BlockPos pos, int[] typeCounts, World world) {
        String type = "normal";
        float refDist = 3.0f;
        float maxDist = 64.0f;
        int sampleShift = 0;
        int count = 1;

        if (world != null) {
            var block = world.getBlockState(pos).getBlock();
            if (block instanceof SubwooferBlock) {
                type = "sub";
                refDist = 10.0f;
                maxDist = 85.0f;
                count = typeCounts[0];
            } else if (block instanceof MidRangeBlock) {
                type = "mid";
                refDist = 5.0f;
                maxDist = 60.0f;
                count = typeCounts[1];
            } else if (block instanceof LineArrayBlock) {
                type = "line";
                refDist = 3.0f;
                maxDist = 50.0f;
                count = typeCounts[2];
            } else {
                count = typeCounts[3];
            }

            var be = world.getBlockEntity(pos);
            if (be instanceof com.audiophilecraft.block.entity.SpeakerBlockEntity speakerBe) {
                sampleShift = speakerBe.getSampleShift();
            }
        }

        return new SpeakerInfo(type, refDist, maxDist, sampleShift, count);
    }
}
