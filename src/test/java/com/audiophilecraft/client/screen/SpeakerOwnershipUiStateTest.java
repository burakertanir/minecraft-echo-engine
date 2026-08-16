package com.audiophilecraft.client.screen;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;
import org.junit.jupiter.api.Test;

class SpeakerOwnershipUiStateTest {
    @Test
    void onlyTheSelectedProfileOwnerCanEditItsAccessSetting() {
        UUID owner = UUID.randomUUID();
        UUID otherPlayer = UUID.randomUUID();

        assertTrue(SpeakerOwnershipUiState.canEditAccess(owner, owner));
        assertFalse(SpeakerOwnershipUiState.canEditAccess(otherPlayer, owner));
        assertFalse(SpeakerOwnershipUiState.canEditAccess(null, owner));
        assertFalse(SpeakerOwnershipUiState.canEditAccess(owner, null));
    }

    @Test
    void selectingAnotherAccessibleOwnerSetsThatExactBuildTarget() {
        UUID builder = UUID.randomUUID();
        UUID selectedOwner = UUID.randomUUID();

        assertEquals(
                selectedOwner, SpeakerOwnershipUiState.requestedPlacementOwner(builder, selectedOwner, builder, true));
    }

    @Test
    void selectingYourselfSwitchesBackFromAnotherBuildTarget() {
        UUID builder = UUID.randomUUID();
        UUID currentOwner = UUID.randomUUID();

        assertEquals(builder, SpeakerOwnershipUiState.requestedPlacementOwner(builder, builder, currentOwner, true));
    }

    @Test
    void activePrivateOrInvalidTargetsNeverProducePlacementRequests() {
        UUID builder = UUID.randomUUID();
        UUID selectedOwner = UUID.randomUUID();

        assertNull(SpeakerOwnershipUiState.requestedPlacementOwner(builder, selectedOwner, selectedOwner, true));
        assertNull(SpeakerOwnershipUiState.requestedPlacementOwner(builder, selectedOwner, builder, false));
        assertNull(SpeakerOwnershipUiState.requestedPlacementOwner(null, selectedOwner, builder, true));
        assertNull(SpeakerOwnershipUiState.requestedPlacementOwner(builder, null, builder, true));
    }
}
