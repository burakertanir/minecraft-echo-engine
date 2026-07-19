package com.audiophilecraft.sound;

import java.util.List;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

/** Runtime grouping of nearby speakers that share one acoustic profile. */
public final class EmitterGroup {
    private final List<BlockPos> speakerPositions;
    private final Vec3d center;
    private volatile AcousticProfile acousticProfile;
    private volatile int roomBusIndex;
    private volatile float roomSendGain = 1.0f;
    private volatile float targetRoomSendGain = 1.0f;

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

    public int roomBusIndex() {
        return roomBusIndex;
    }

    void assignRoomBus(int busIndex) {
        roomBusIndex = Math.max(0, Math.min(1, busIndex));
    }

    public float roomSendGain() {
        return roomSendGain;
    }

    void setRoomSendTarget(float targetGain) {
        targetRoomSendGain = clamp01(targetGain);
    }

    void updateRoomSendGain(float deltaSeconds, float fadeOutSeconds, float fadeInSeconds) {
        float target = targetRoomSendGain;
        float duration = target < roomSendGain ? fadeOutSeconds : fadeInSeconds;
        if (duration <= 0.0f) {
            roomSendGain = target;
            return;
        }

        float maximumStep = Math.max(0.0f, deltaSeconds) / duration;
        if (roomSendGain < target) {
            roomSendGain = Math.min(target, roomSendGain + maximumStep);
        } else if (roomSendGain > target) {
            roomSendGain = Math.max(target, roomSendGain - maximumStep);
        }
    }

    private static float clamp01(float value) {
        return Math.max(0.0f, Math.min(1.0f, value));
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
