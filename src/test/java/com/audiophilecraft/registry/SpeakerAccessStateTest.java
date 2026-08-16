package com.audiophilecraft.registry;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtList;
import org.junit.jupiter.api.Test;

class SpeakerAccessStateTest {
    @Test
    void newOwnersArePrivateByDefault() {
        SpeakerAccessState state = new SpeakerAccessState();
        UUID owner = UUID.randomUUID();
        UUID stranger = UUID.randomUUID();

        assertFalse(state.isShared(owner));
        assertTrue(state.canAccess(owner, owner));
        assertFalse(state.canAccess(stranger, owner));
    }

    @Test
    void sharedOwnersAreAccessibleWhileOffline() {
        SpeakerAccessState state = new SpeakerAccessState();
        UUID owner = UUID.randomUUID();
        UUID builder = UUID.randomUUID();

        state.setShared(owner, true);

        assertTrue(state.isShared(owner));
        assertTrue(state.canAccess(builder, owner));
        assertEquals(owner, state.setPlacementTarget(builder, owner));
        assertEquals(owner, state.resolvePlacementOwner(builder));
    }

    @Test
    void privateTargetCannotBeSelectedByAnotherPlayer() {
        SpeakerAccessState state = new SpeakerAccessState();
        UUID owner = UUID.randomUUID();
        UUID builder = UUID.randomUUID();

        assertEquals(builder, state.setPlacementTarget(builder, owner));
        assertEquals(builder, state.resolvePlacementOwner(builder));
        assertNull(state.getPlacementTarget(builder));
    }

    @Test
    void selectingSelfDisablesForeignPlacementMode() {
        SpeakerAccessState state = new SpeakerAccessState();
        UUID owner = UUID.randomUUID();
        UUID builder = UUID.randomUUID();
        state.setShared(owner, true);
        state.setPlacementTarget(builder, owner);

        assertEquals(builder, state.setPlacementTarget(builder, builder));
        assertEquals(builder, state.resolvePlacementOwner(builder));
        assertNull(state.getPlacementTarget(builder));
    }

    @Test
    void makingOwnerPrivateRevokesEveryBuildersPlacementTarget() {
        SpeakerAccessState state = new SpeakerAccessState();
        UUID owner = UUID.randomUUID();
        UUID firstBuilder = UUID.randomUUID();
        UUID secondBuilder = UUID.randomUUID();
        state.setShared(owner, true);
        state.setPlacementTarget(firstBuilder, owner);
        state.setPlacementTarget(secondBuilder, owner);

        state.setShared(owner, false);

        assertFalse(state.isShared(owner));
        assertNull(state.getPlacementTarget(firstBuilder));
        assertNull(state.getPlacementTarget(secondBuilder));
        assertEquals(firstBuilder, state.resolvePlacementOwner(firstBuilder));
        assertEquals(secondBuilder, state.resolvePlacementOwner(secondBuilder));
    }

    @Test
    void sharedAccessAndPlacementTargetsSurviveNbtRoundTrip() {
        SpeakerAccessState original = new SpeakerAccessState();
        UUID owner = UUID.randomUUID();
        UUID builder = UUID.randomUUID();
        original.setShared(owner, true);
        original.setPlacementTarget(builder, owner);

        SpeakerAccessState restored = SpeakerAccessState.fromNbt(original.writeNbt(new NbtCompound()));

        assertTrue(restored.isShared(owner));
        assertEquals(owner, restored.getPlacementTarget(builder));
        assertEquals(owner, restored.resolvePlacementOwner(builder));
    }

    @Test
    void invalidOrIncompleteNbtEntriesAreIgnored() {
        NbtCompound nbt = new NbtCompound();
        NbtList sharedOwners = new NbtList();
        sharedOwners.add(new NbtCompound());
        nbt.put("SharedOwners", sharedOwners);

        NbtList placementTargets = new NbtList();
        NbtCompound incompleteTarget = new NbtCompound();
        incompleteTarget.putUuid("Builder", UUID.randomUUID());
        placementTargets.add(incompleteTarget);
        UUID samePlayer = UUID.randomUUID();
        NbtCompound selfTarget = new NbtCompound();
        selfTarget.putUuid("Builder", samePlayer);
        selfTarget.putUuid("Target", samePlayer);
        placementTargets.add(selfTarget);
        nbt.put("PlacementTargets", placementTargets);

        SpeakerAccessState restored = SpeakerAccessState.fromNbt(nbt);

        assertTrue(restored.getSharedOwners().isEmpty());
        assertNull(restored.getPlacementTarget(samePlayer));
    }

    @Test
    void exposedSharedOwnerSetCannotMutatePersistentState() {
        SpeakerAccessState state = new SpeakerAccessState();
        UUID owner = UUID.randomUUID();
        state.setShared(owner, true);

        assertThrows(UnsupportedOperationException.class, () -> state.getSharedOwners()
                .clear());
        assertTrue(state.isShared(owner));
    }

    @Test
    void nullIdentifiersAreAlwaysRejectedSafely() {
        SpeakerAccessState state = new SpeakerAccessState();
        UUID owner = UUID.randomUUID();

        assertFalse(state.canAccess(null, owner));
        assertFalse(state.canAccess(owner, null));
        assertNull(state.setPlacementTarget(null, owner));
        assertNull(state.resolvePlacementOwner(null));
    }

    @Test
    void privateTargetCannotBeSelectedThroughAnyTabletRequest() {
        SpeakerAccessState state = new SpeakerAccessState();
        UUID privateOwner = UUID.randomUUID();
        UUID builder = UUID.randomUUID();

        assertEquals(builder, state.setPlacementTarget(builder, privateOwner));
        assertEquals(builder, state.resolvePlacementOwner(builder));
        assertNull(state.getPlacementTarget(builder));
    }
}
