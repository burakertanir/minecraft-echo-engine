package com.audiophilecraft.client.screen;

import java.util.UUID;

/** Pure ownership-control rules shared by the tablet UI and its regression tests. */
final class SpeakerOwnershipUiState {
    private SpeakerOwnershipUiState() {}

    static boolean canEditAccess(UUID viewer, UUID selectedOwner) {
        return viewer != null && viewer.equals(selectedOwner);
    }

    /**
     * Returns the selected owner to send to the server, or {@code null} when the
     * button must be a no-op.
     */
    static UUID requestedPlacementOwner(
            UUID viewer, UUID selectedOwner, UUID currentPlacementOwner, boolean selectedOwnerIsAccessible) {
        if (viewer == null || selectedOwner == null || !selectedOwnerIsAccessible) return null;
        if (selectedOwner.equals(currentPlacementOwner)) return null;
        return selectedOwner;
    }
}
