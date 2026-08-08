package com.audiophilecraft.sound;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.minecraft.util.math.Vec3d;
import org.junit.jupiter.api.Test;

class ReverbBusAllocatorTest {
    @Test
    void identicalProfilesHaveZeroDistanceAndAreSimilar() {
        AcousticProfile profile = profile(0.8f, 0.08f, 0.2f, 20.0f, 20_000.0f, 1.5f);

        assertEquals(0.0, ReverbBusAllocator.profileDistance(profile, profile), 0.000001);
        assertTrue(ReverbBusAllocator.areSimilar(profile, profile));
    }

    @Test
    void profileDistanceIsSymmetric() {
        AcousticProfile first = profile(0.8f, 0.08f, 0.2f, 20.0f, 20_000.0f, 1.5f);
        AcousticProfile second = profile(0.6f, 0.18f, 0.4f, 40.0f, 80_000.0f, 3.0f);

        assertEquals(
                ReverbBusAllocator.profileDistance(first, second),
                ReverbBusAllocator.profileDistance(second, first),
                0.000001);
    }

    @Test
    void nearbyProfilesShareAReverbBus() {
        AcousticProfile first = profile(0.80f, 0.08f, 0.20f, 20.0f, 20_000.0f, 1.5f);
        AcousticProfile second = profile(0.76f, 0.10f, 0.24f, 22.0f, 24_000.0f, 1.7f);

        assertTrue(ReverbBusAllocator.areSimilar(first, second));
    }

    @Test
    void clearlyDifferentProfilesDoNotShareAReverbBus() {
        AcousticProfile smallRoom = profile(0.95f, 0.02f, 0.6f, 4.0f, 300.0f, 0.3f);
        AcousticProfile stadium = profile(0.45f, 0.20f, 0.05f, 100.0f, 2_000_000.0f, 8.0f);

        assertFalse(ReverbBusAllocator.areSimilar(smallRoom, stadium));
    }

    @Test
    void openAndEnclosedProfilesStaySeparateEvenWhenOtherMetricsMatch() {
        AcousticProfile enclosed = profile(0.8f, 0.10f, 0.2f, 20.0f, 20_000.0f, 1.5f);
        AcousticProfile open = profile(0.8f, 0.30f, 0.2f, 20.0f, 20_000.0f, 1.5f);

        assertFalse(ReverbBusAllocator.areSimilar(enclosed, open));
    }

    @Test
    void nullProfilesAreNeverSimilar() {
        AcousticProfile profile = profile(0.8f, 0.08f, 0.2f, 20.0f, 20_000.0f, 1.5f);

        assertFalse(ReverbBusAllocator.areSimilar(null, profile));
        assertFalse(ReverbBusAllocator.areSimilar(profile, null));
        assertFalse(ReverbBusAllocator.areSimilar(null, null));
    }

    @Test
    void profileDistanceRemainsNormalizedForExtremeDifferences() {
        AcousticProfile minimum = profile(0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f);
        AcousticProfile maximum = profile(1.0f, 1.0f, 1.0f, 1_000_000.0f, Float.MAX_VALUE, 100.0f);

        double distance = ReverbBusAllocator.profileDistance(minimum, maximum);

        assertTrue(Double.isFinite(distance));
        assertTrue(distance >= 0.0 && distance <= 1.0, "distance=" + distance);
    }

    @Test
    void changingOneAcousticMetricIncreasesDistance() {
        AcousticProfile base = profile(0.8f, 0.08f, 0.2f, 20.0f, 20_000.0f, 1.5f);
        AcousticProfile changed = profile(0.8f, 0.08f, 0.2f, 20.0f, 320_000.0f, 1.5f);

        assertTrue(ReverbBusAllocator.profileDistance(base, changed) > 0.0);
    }

    @Test
    void acousticProfileRejectsMissingRequiredParts() {
        AcousticProfile valid = profile(0.8f, 0.08f, 0.2f, 20.0f, 20_000.0f, 1.5f);

        assertThrows(NullPointerException.class, () -> new AcousticProfile(null, valid.preset()));
        assertThrows(NullPointerException.class, () -> new AcousticProfile(valid.descriptor(), null));
    }

    private static AcousticProfile profile(
            float enclosure, float openness, float absorption, float scale, float volume, float decay) {
        AdvancedAcousticScanner.VenueDescriptor descriptor = new AdvancedAcousticScanner.VenueDescriptor(
                enclosure,
                scale,
                1.0f - absorption,
                0.5f,
                openness,
                0.5f,
                0.5f,
                absorption,
                volume,
                Math.max(1.0f, volume / 4.0f));
        AdvancedAcousticScanner.VenuePreset preset = new AdvancedAcousticScanner.VenuePreset(
                decay,
                0.5f,
                0.5f,
                1.0f,
                0.2f,
                0.02f,
                0.3f,
                0.04f,
                0.1f,
                0.0f,
                1.0f,
                1.0f,
                0.8f,
                1.0f,
                0.99f,
                false,
                enclosure,
                Vec3d.ZERO,
                "TEST");
        return new AcousticProfile(descriptor, preset);
    }
}
