package com.audiophilecraft.sound;

import com.audiophilecraft.block.LineArrayBlock;
import com.audiophilecraft.block.MidRangeBlock;
import com.audiophilecraft.block.SubwooferBlock;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

/**
 * Stateless utility for grouping speakers by physical contact and detecting types.
 * Extracted from AudioEngine to reduce god-class size.
 */
public class SpeakerClusterer {

    /**
     * Groups speaker positions into clusters based on physical contact.
     * Two speakers are connected when they touch: the difference in every axis is at most 1,
     * so face, edge and corner adjacency all count. A transitive chain of touching speakers
     * shares one cluster; any empty block between speakers separates clusters.
     * Input is sorted for deterministic output (ConcurrentHashMap iteration is not guaranteed).
     */
    public static List<List<BlockPos>> clusterSpeakers(List<BlockPos> speakers) {
        List<BlockPos> sorted = new ArrayList<>(speakers);
        Collections.sort(sorted, Comparator.comparingLong(BlockPos::asLong));

        List<List<BlockPos>> clusters = new ArrayList<>();
        boolean[] visited = new boolean[sorted.size()];
        for (int start = 0; start < sorted.size(); start++) {
            if (visited[start]) continue;

            List<BlockPos> cluster = new ArrayList<>();
            ArrayDeque<Integer> pending = new ArrayDeque<>();
            visited[start] = true;
            pending.add(start);

            while (!pending.isEmpty()) {
                int currentIndex = pending.removeFirst();
                BlockPos current = sorted.get(currentIndex);
                cluster.add(current);

                for (int candidateIndex = 0; candidateIndex < sorted.size(); candidateIndex++) {
                    if (visited[candidateIndex]) continue;
                    if (touches(current, sorted.get(candidateIndex))) {
                        visited[candidateIndex] = true;
                        pending.addLast(candidateIndex);
                    }
                }
            }
            cluster.sort(Comparator.comparingLong(BlockPos::asLong));
            clusters.add(cluster);
        }
        return clusters;
    }

    /** True when two positions touch: the axis difference is at most 1 in every direction. */
    private static boolean touches(BlockPos first, BlockPos second) {
        return Math.abs(first.getX() - second.getX()) <= 1
                && Math.abs(first.getY() - second.getY()) <= 1
                && Math.abs(first.getZ() - second.getZ()) <= 1;
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
}
