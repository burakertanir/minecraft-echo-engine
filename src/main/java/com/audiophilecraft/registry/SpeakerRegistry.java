package com.audiophilecraft.registry;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.util.math.BlockPos;

/**
 * Global registry of speaker positions, now tracking owner UUID per speaker.
 * Avoids O(N^3) block scanning by maintaining O(1) insert/remove sets.
 * Scan becomes O(speakers) instead of O(volume).
 */
public class SpeakerRegistry {
    private static final Set<BlockPos> speakers = ConcurrentHashMap.newKeySet();
    private static final Map<BlockPos, UUID> ownerMap = new ConcurrentHashMap<>();

    // --- Speakers ---

    public static void registerSpeaker(BlockPos pos, UUID owner) {
        BlockPos immutable = pos.toImmutable();
        speakers.add(immutable);
        if (owner != null) {
            ownerMap.put(immutable, owner);
        }
    }

    public static void registerSpeaker(BlockPos pos) {
        registerSpeaker(pos, null);
    }

    public static void unregisterSpeaker(BlockPos pos) {
        speakers.remove(pos);
        ownerMap.remove(pos);
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

    /** Find all speakers owned by a specific player. */
    public static List<BlockPos> findSpeakersByOwner(UUID ownerUUID) {
        if (ownerUUID == null) return Collections.emptyList();
        List<BlockPos> result = new ArrayList<>();
        for (Map.Entry<BlockPos, UUID> entry : ownerMap.entrySet()) {
            if (ownerUUID.equals(entry.getValue())) {
                result.add(entry.getKey());
            }
        }
        return result;
    }

    /** Get the owner UUID of a speaker, or null if not tracked. */
    public static UUID getOwner(BlockPos pos) {
        return ownerMap.get(pos);
    }

    /** Clear all registries (called on world unload). */
    public static void clear() {
        speakers.clear();
        ownerMap.clear();
    }
}
