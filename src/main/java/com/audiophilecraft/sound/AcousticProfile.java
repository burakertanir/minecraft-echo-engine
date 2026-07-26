package com.audiophilecraft.sound;

import java.util.List;
import java.util.Objects;
import java.util.Set;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

/** Immutable acoustic characteristics calculated for one emitter group. */
public record AcousticProfile(
        AdvancedAcousticScanner.VenueDescriptor descriptor, AdvancedAcousticScanner.VenuePreset preset) {
    public AcousticProfile {
        Objects.requireNonNull(descriptor, "descriptor");
        Objects.requireNonNull(preset, "preset");
    }

    public Vec3d probePosition() {
        return preset.probePosition;
    }
}

/** Self-contained output produced for one acoustic profile scan. */
record AcousticScanResult(AcousticProfile profile, List<Vec3d> pointCloud, Set<BlockPos> venueBlocks) {
    AcousticScanResult {
        Objects.requireNonNull(profile, "profile");
        pointCloud = List.copyOf(pointCloud);
        venueBlocks = Set.copyOf(venueBlocks);
    }
}

/** Combined scene result plus the individual emitter-group profiles. */
record AcousticSceneScanResult(AcousticScanResult combinedResult, List<AcousticProfile> groupProfiles) {
    AcousticSceneScanResult {
        Objects.requireNonNull(combinedResult, "combinedResult");
        groupProfiles = List.copyOf(groupProfiles);
    }
}
