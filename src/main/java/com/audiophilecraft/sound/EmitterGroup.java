package com.audiophilecraft.sound;

import java.util.List;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

/** Runtime grouping of nearby speakers that share one acoustic profile. */
public final class EmitterGroup {
    private final List<BlockPos> speakerPositions;
    private final Vec3d center;
    private volatile AcousticProfile acousticProfile;

    EmitterGroup(List<BlockPos> speakerPositions) {
        if (speakerPositions == null || speakerPositions.isEmpty()) {
            throw new IllegalArgumentException("speakerPositions must not be empty");
        }
        this.speakerPositions = List.copyOf(speakerPositions);
        this.center = calculateCenter(this.speakerPositions);
    }

    public List<BlockPos> speakerPositions() {
        return speakerPositions;
    }

    public Vec3d center() {
        return center;
    }

    public AcousticProfile acousticProfile() {
        return acousticProfile;
    }

    void applyAcousticProfile(AcousticProfile profile) {
        this.acousticProfile = profile;
    }

    private static Vec3d calculateCenter(List<BlockPos> positions) {
        double x = 0.0;
        double y = 0.0;
        double z = 0.0;
        for (BlockPos position : positions) {
            x += position.getX() + 0.5;
            y += position.getY() + 0.5;
            z += position.getZ() + 0.5;
        }
        double count = positions.size();
        return new Vec3d(x / count, y / count, z / count);
    }
}
