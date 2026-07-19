package com.audiophilecraft.sound;

import java.util.List;
import java.util.Objects;
import java.util.Set;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

/** Self-contained scanner output that can safely cross an asynchronous callback. */
public final class AcousticScanResult {
    private final AcousticProfile profile;
    private final List<Vec3d> pointCloud;
    private final Set<BlockPos> venueBlocks;

    AcousticScanResult(AcousticProfile profile, List<Vec3d> pointCloud, Set<BlockPos> venueBlocks) {
        this.profile = Objects.requireNonNull(profile, "profile");
        this.pointCloud = List.copyOf(pointCloud);
        this.venueBlocks = Set.copyOf(venueBlocks);
    }

    public AcousticProfile profile() {
        return profile;
    }

    public List<Vec3d> pointCloud() {
        return pointCloud;
    }

    public Set<BlockPos> venueBlocks() {
        return venueBlocks;
    }
}
