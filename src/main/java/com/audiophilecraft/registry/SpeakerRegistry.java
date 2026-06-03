package com.audiophilecraft.registry;

import net.minecraft.util.math.BlockPos;

import java.util.ArrayList;

import java.util.List;
import java.util.Set;

/**
 * Global registry of speaker positions.
 * Avoids O(N^3) block scanning by maintaining O(1) insert/remove sets.
 * Scan becomes O(speakers) instead of O(volume).
 */
public class SpeakerRegistry {
    private static final Set<BlockPos> speakers = java.util.concurrent.ConcurrentHashMap.newKeySet();

    // --- Speakers ---

    public static void registerSpeaker(BlockPos pos) {
        speakers.add(pos.toImmutable());
    }

    public static void unregisterSpeaker(BlockPos pos) {
        speakers.remove(pos);
    }

    /** Find all speakers within radius of a given position. */
    public static List<BlockPos> findSpeakersInRange(BlockPos center, double maxRange) {
        double maxRangeSq = maxRange * maxRange;
        List<BlockPos> result = new ArrayList<>();
        for (BlockPos sp : speakers) {
            if (center.getSquaredDistance(sp) <= maxRangeSq) {
                result.add(sp);
            }
        }
        return result;
    }

    /** Clear all registries (called on world unload). */
    public static void clear() {
        speakers.clear();
    }
}
