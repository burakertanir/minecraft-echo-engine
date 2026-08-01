package com.audiophilecraft.sound;

import java.util.List;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

/** Runtime grouping of nearby speakers that share one acoustic profile. */
public final class EmitterGroup {
    private final List<BlockPos> speakerPositions;
    private final Vec3d center;
    private final ReflectionTransmissionTracker reflectionTransmission = new ReflectionTransmissionTracker();
    private volatile AcousticProfile acousticProfile;
    private volatile List<Vec3d> reflectionPoints = List.of();
    private volatile int roomBusIndex;
    private volatile float roomSendGain;
    private volatile float targetRoomSendGain;

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

    void applyAcousticProfile(AcousticProfile profile, List<Vec3d> reflectionPoints) {
        if (profile == null) return;
        this.acousticProfile = profile;
        this.reflectionPoints = reflectionPoints == null ? List.of() : List.copyOf(reflectionPoints);
        reflectionTransmission.setReflectionPoints(this.reflectionPoints);
        this.targetRoomSendGain = 1.0f;
    }

    void updateReflectionTransmission(net.minecraft.world.World world, Vec3d listenerPosition) {
        reflectionTransmission.update(world, center, reflectionPoints, listenerPosition);
    }

    float reflectionTransmission() {
        return reflectionTransmission.gain();
    }

    float reflectionHighFrequencyTransmission() {
        return reflectionTransmission.highFrequencyGain();
    }

    void activateRoomSendImmediately() {
        this.roomSendGain = 1.0f;
        this.targetRoomSendGain = 1.0f;
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
