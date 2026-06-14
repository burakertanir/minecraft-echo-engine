package com.audiophilecraft.registry;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.registry.RegistryKey;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

/**
 * Global registry of speaker positions with dimension awareness and owner tracking.
 * Avoids O(N^3) block scanning by maintaining O(1) insert/remove sets.
 * Scan becomes O(speakers) instead of O(volume).
 *
 * All operations are thread-safe via ConcurrentHashMap.
 * Dimension-aware: speakers in different dimensions don't cross-contaminate.
 */
public class SpeakerRegistry {
    private static final Map<RegistryKey<World>, Set<BlockPos>> speakersByDim = new ConcurrentHashMap<>();
    private static final Map<RegistryKey<World>, Map<BlockPos, UUID>> ownersByDim = new ConcurrentHashMap<>();

    private static Set<BlockPos> getSet(RegistryKey<World> dim) {
        return speakersByDim.computeIfAbsent(dim, k -> ConcurrentHashMap.newKeySet());
    }

    private static Map<BlockPos, UUID> getOwnerMap(RegistryKey<World> dim) {
        return ownersByDim.computeIfAbsent(dim, k -> new ConcurrentHashMap<>());
    }

    // --- Speakers ---

    /** Register with dimension and optional owner. */
    public static void registerSpeaker(World world, BlockPos pos, UUID owner) {
        registerSpeaker(world.getRegistryKey(), pos, owner);
    }

    public static void registerSpeaker(RegistryKey<World> dimension, BlockPos pos, UUID owner) {
        BlockPos immutable = pos.toImmutable();
        getSet(dimension).add(immutable);
        if (owner != null) {
            getOwnerMap(dimension).put(immutable, owner);
        }
    }

    /** Register without owner (legacy). */
    public static void registerSpeaker(RegistryKey<World> dimension, BlockPos pos) {
        registerSpeaker(dimension, pos, null);
    }

    /** Overload without dimension (fallback for callers without world context). */
    public static void registerSpeaker(BlockPos pos, UUID owner) {
        registerSpeaker(World.OVERWORLD, pos, owner);
    }

    public static void registerSpeaker(BlockPos pos) {
        registerSpeaker(World.OVERWORLD, pos, null);
    }

    public static void unregisterSpeaker(World world, BlockPos pos) {
        unregisterSpeaker(world.getRegistryKey(), pos);
    }

    public static void unregisterSpeaker(RegistryKey<World> dimension, BlockPos pos) {
        getSet(dimension).remove(pos);
        getOwnerMap(dimension).remove(pos);
    }

    /** Overload without dimension. */
    public static void unregisterSpeaker(BlockPos pos) {
        // Scan all dimensions (slow path, used when dimension is unknown)
        for (Map.Entry<RegistryKey<World>, Set<BlockPos>> entry : speakersByDim.entrySet()) {
            if (entry.getValue().remove(pos)) {
                getOwnerMap(entry.getKey()).remove(pos);
                break;
            }
        }
    }

    /** Find all speakers within radius of a given position, scoped to the world's dimension. */
    public static List<BlockPos> findSpeakersInRange(World world, BlockPos center, double maxRange) {
        return findSpeakersInRange(world.getRegistryKey(), center, maxRange);
    }

    public static List<BlockPos> findSpeakersInRange(RegistryKey<World> dimension, BlockPos center, double maxRange) {
        double maxRangeSq = maxRange * maxRange;
        List<BlockPos> result = new ArrayList<>();
        for (BlockPos sp : getSet(dimension)) {
            if (center.getSquaredDistance(sp) <= maxRangeSq) {
                result.add(sp);
            }
        }
        return result;
    }

    /** Overload without dimension (legacy, scans all dimensions — not recommended). */
    public static List<BlockPos> findSpeakersInRange(BlockPos center, double maxRange) {
        double maxRangeSq = maxRange * maxRange;
        List<BlockPos> result = new ArrayList<>();
        for (Set<BlockPos> set : speakersByDim.values()) {
            for (BlockPos sp : set) {
                if (center.getSquaredDistance(sp) <= maxRangeSq) {
                    result.add(sp);
                }
            }
        }
        return result;
    }

    /** Find all speakers owned by a specific player, scoped to dimension. */
    public static List<BlockPos> findSpeakersByOwner(RegistryKey<World> dimension, UUID ownerUUID) {
        if (ownerUUID == null) return Collections.emptyList();
        List<BlockPos> result = new ArrayList<>();
        for (Map.Entry<BlockPos, UUID> entry : getOwnerMap(dimension).entrySet()) {
            if (ownerUUID.equals(entry.getValue())) {
                result.add(entry.getKey());
            }
        }
        return result;
    }

    /** Overload without dimension (legacy, scans all dimensions). */
    public static List<BlockPos> findSpeakersByOwner(UUID ownerUUID) {
        if (ownerUUID == null) return Collections.emptyList();
        List<BlockPos> result = new ArrayList<>();
        for (Map.Entry<RegistryKey<World>, Map<BlockPos, UUID>> dimEntry : ownersByDim.entrySet()) {
            for (Map.Entry<BlockPos, UUID> entry : dimEntry.getValue().entrySet()) {
                if (ownerUUID.equals(entry.getValue())) {
                    result.add(entry.getKey());
                }
            }
        }
        return result;
    }

    /** Get the owner UUID of a speaker, scoped to dimension. */
    public static UUID getOwner(BlockPos pos) {
        for (Map<BlockPos, UUID> map : ownersByDim.values()) {
            UUID owner = map.get(pos);
            if (owner != null) return owner;
        }
        return null;
    }

    /** Clear all registries for a specific dimension (called on world unload). */
    public static void clear(RegistryKey<World> dimension) {
        speakersByDim.remove(dimension);
        ownersByDim.remove(dimension);
    }

    /** Clear all registries (called on server stop). */
    public static void clear() {
        speakersByDim.clear();
        ownersByDim.clear();
    }
}
