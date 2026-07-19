package com.audiophilecraft.sound;

import java.util.Objects;
import net.minecraft.util.math.Vec3d;

/** Immutable acoustic characteristics calculated for one emitter group. */
public final class AcousticProfile {
    private final AdvancedAcousticScanner.VenueDescriptor descriptor;
    private final AdvancedAcousticScanner.VenuePreset preset;

    AcousticProfile(AdvancedAcousticScanner.VenueDescriptor descriptor, AdvancedAcousticScanner.VenuePreset preset) {
        this.descriptor = Objects.requireNonNull(descriptor, "descriptor");
        this.preset = Objects.requireNonNull(preset, "preset");
    }

    public AdvancedAcousticScanner.VenueDescriptor descriptor() {
        return descriptor;
    }

    public AdvancedAcousticScanner.VenuePreset preset() {
        return preset;
    }

    public Vec3d probePosition() {
        return preset.probePosition;
    }
}
