package com.audiophilecraft.registry;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtElement;
import net.minecraft.nbt.NbtList;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.PersistentState;

/** Persistent, server-authoritative access and placement settings for speaker owners. */
public final class SpeakerAccessState extends PersistentState {
    private static final String STATE_ID = "audiophilecraft_speaker_access";
    private static final String SHARED_OWNERS_KEY = "SharedOwners";
    private static final String PLACEMENT_TARGETS_KEY = "PlacementTargets";
    private static final String OWNER_KEY = "Owner";
    private static final String BUILDER_KEY = "Builder";
    private static final String TARGET_KEY = "Target";

    private final Set<UUID> sharedOwners = new HashSet<>();
    private final Map<UUID, UUID> placementTargets = new HashMap<>();

    public static SpeakerAccessState get(MinecraftServer server) {
        return server.getOverworld()
                .getPersistentStateManager()
                .getOrCreate(SpeakerAccessState::fromNbt, SpeakerAccessState::new, STATE_ID);
    }

    public static SpeakerAccessState fromNbt(NbtCompound nbt) {
        SpeakerAccessState state = new SpeakerAccessState();

        NbtList sharedOwnerList = nbt.getList(SHARED_OWNERS_KEY, NbtElement.COMPOUND_TYPE);
        for (int index = 0; index < sharedOwnerList.size(); index++) {
            NbtCompound entry = sharedOwnerList.getCompound(index);
            if (entry.containsUuid(OWNER_KEY)) state.sharedOwners.add(entry.getUuid(OWNER_KEY));
        }

        NbtList placementTargetList = nbt.getList(PLACEMENT_TARGETS_KEY, NbtElement.COMPOUND_TYPE);
        for (int index = 0; index < placementTargetList.size(); index++) {
            NbtCompound entry = placementTargetList.getCompound(index);
            if (!entry.containsUuid(BUILDER_KEY) || !entry.containsUuid(TARGET_KEY)) continue;
            UUID builder = entry.getUuid(BUILDER_KEY);
            UUID target = entry.getUuid(TARGET_KEY);
            if (!builder.equals(target)) state.placementTargets.put(builder, target);
        }
        return state;
    }

    public boolean isShared(UUID owner) {
        return owner != null && sharedOwners.contains(owner);
    }

    public boolean canAccess(UUID actor, UUID owner) {
        return actor != null && owner != null && (actor.equals(owner) || isShared(owner));
    }

    public void setShared(UUID owner, boolean shared) {
        if (owner == null) return;

        boolean changed = shared ? sharedOwners.add(owner) : sharedOwners.remove(owner);
        if (!shared) changed |= placementTargets.entrySet().removeIf(entry -> owner.equals(entry.getValue()));
        if (changed) markDirty();
    }

    public Set<UUID> getSharedOwners() {
        return Set.copyOf(sharedOwners);
    }

    /**
     * Stores a builder's target only when it is currently accessible. Returning the builder UUID means
     * normal self-owned placement is active.
     */
    public UUID setPlacementTarget(UUID builder, UUID requestedOwner) {
        if (builder == null) return null;

        if (requestedOwner == null || builder.equals(requestedOwner) || !canAccess(builder, requestedOwner)) {
            if (placementTargets.remove(builder) != null) markDirty();
            return builder;
        }

        UUID previous = placementTargets.put(builder, requestedOwner);
        if (!requestedOwner.equals(previous)) markDirty();
        return requestedOwner;
    }

    /** Resolves and repairs the authoritative owner to use for the builder's next speaker placement. */
    public UUID resolvePlacementOwner(UUID builder) {
        if (builder == null) return null;

        UUID target = placementTargets.get(builder);
        if (target == null) return builder;
        if (canAccess(builder, target)) return target;

        placementTargets.remove(builder);
        markDirty();
        return builder;
    }

    public UUID getPlacementTarget(UUID builder) {
        return builder == null ? null : placementTargets.get(builder);
    }

    @Override
    public NbtCompound writeNbt(NbtCompound nbt) {
        NbtList sharedOwnerList = new NbtList();
        for (UUID owner : sharedOwners) {
            NbtCompound entry = new NbtCompound();
            entry.putUuid(OWNER_KEY, owner);
            sharedOwnerList.add(entry);
        }
        nbt.put(SHARED_OWNERS_KEY, sharedOwnerList);

        NbtList placementTargetList = new NbtList();
        for (Map.Entry<UUID, UUID> placementTarget : placementTargets.entrySet()) {
            NbtCompound entry = new NbtCompound();
            entry.putUuid(BUILDER_KEY, placementTarget.getKey());
            entry.putUuid(TARGET_KEY, placementTarget.getValue());
            placementTargetList.add(entry);
        }
        nbt.put(PLACEMENT_TARGETS_KEY, placementTargetList);
        return nbt;
    }
}
