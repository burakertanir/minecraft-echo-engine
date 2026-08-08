package com.audiophilecraft.sound;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.audiophilecraft.config.LiveTuningConfig;
import java.util.stream.Stream;
import net.minecraft.util.math.Vec3d;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class VenuePresetCalculatorTest {
    private final VenuePresetCalculator calculator = new VenuePresetCalculator();
    private final LiveTuningConfig config = LiveTuningConfig.createDefaults();

    @ParameterizedTest
    @MethodSource("volumeTierCases")
    void selectsTierFromEnclosedVenueVolume(float volume, String expectedTier) {
        AdvancedAcousticScanner.VenuePreset preset =
                calculator.calculate(descriptor(volume, 1.0f, 0.0f), Vec3d.ZERO, config);

        assertTrue(preset.tierName.startsWith(expectedTier), preset.tierName);
    }

    @Test
    void exactTierBoundaryStaysInTheLowerTier() {
        AdvancedAcousticScanner.VenuePreset preset =
                calculator.calculate(descriptor(config.tier7_volumeThreshold, 1.0f, 0.0f), Vec3d.ZERO, config);

        assertTrue(preset.tierName.startsWith("TIER 6"), preset.tierName);
    }

    @Test
    void distanceMustCrossRatherThanTouchTheTierBoundary() {
        AdvancedAcousticScanner.VenuePreset atBoundary =
                calculator.calculate(descriptor(100.0f, config.tier7_distThreshold, 0.0f), Vec3d.ZERO, config);
        AdvancedAcousticScanner.VenuePreset aboveBoundary =
                calculator.calculate(descriptor(100.0f, config.tier7_distThreshold + 0.01f, 0.0f), Vec3d.ZERO, config);

        assertTrue(atBoundary.tierName.startsWith("TIER 6"), atBoundary.tierName);
        assertTrue(aboveBoundary.tierName.startsWith("TIER 7"), aboveBoundary.tierName);
    }

    @Test
    void openAirPenaltyCanDemoteRawStadiumVolume() {
        AdvancedAcousticScanner.VenuePreset preset =
                calculator.calculate(descriptor(config.tier7_volumeThreshold + 1.0f, 1.0f, 0.5f), Vec3d.ZERO, config);

        assertFalse(preset.tierName.startsWith("TIER 7"), preset.tierName);
        assertTrue(preset.tierName.contains("OPEN AIR"), preset.tierName);
    }

    @ParameterizedTest
    @MethodSource("exactVolumeBoundaryCases")
    void exactVolumeBoundaryRemainsInTheLowerTier(float threshold, String expectedTier) {
        AdvancedAcousticScanner.VenuePreset preset =
                calculator.calculate(descriptor(threshold, 1.0f, 0.0f), Vec3d.ZERO, config);

        assertTrue(preset.tierName.startsWith(expectedTier), preset.tierName);
    }

    @ParameterizedTest
    @MethodSource("largeVenueEchoCases")
    void assignsExpectedDistantEchoDepthForLargeVenueTiers(float volume, float expectedEchoDepth) {
        AdvancedAcousticScanner.VenuePreset preset =
                calculator.calculate(descriptor(volume, 1.0f, 0.0f), Vec3d.ZERO, config);

        assertEquals(expectedEchoDepth, preset.echoDepth, 0.0001f, preset.tierName);
    }

    @Test
    void degenerateVenueStillProducesFiniteBoundedDefaults() {
        Vec3d probePosition = new Vec3d(3.0, 5.0, 7.0);
        AdvancedAcousticScanner.VenueDescriptor degenerate =
                new AdvancedAcousticScanner.VenueDescriptor(0.0f, 0.0f, 0.0f, 0.0f, 1.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f);

        AdvancedAcousticScanner.VenuePreset preset = calculator.calculate(degenerate, probePosition, config);

        assertEquals(probePosition, preset.probePosition);
        assertTrue(Float.isFinite(preset.decayTime) && preset.decayTime > 0.0f);
        assertTrue(Float.isFinite(preset.gain) && preset.gain >= 0.0f);
        assertTrue(Float.isFinite(preset.reflectionsGain) && preset.reflectionsGain >= 0.0f);
        assertTrue(preset.reflectionsDelay >= 0.001f && preset.reflectionsDelay <= 0.3f);
        assertTrue(preset.lateReverbDelay >= 0.0f && preset.lateReverbDelay <= 0.1f);
        assertTrue(preset.echoTime >= 0.075f && preset.echoTime <= 0.25f);
        assertTrue(preset.enclosure >= 0.0f && preset.enclosure <= 1.0f);
        assertTrue(preset.tierName.contains("OPEN AIR"), preset.tierName);
    }

    private static Stream<Arguments> volumeTierCases() {
        LiveTuningConfig cfg = LiveTuningConfig.createDefaults();
        return Stream.of(
                Arguments.of(100.0f, "TIER 1"),
                Arguments.of(cfg.tier2_volumeThreshold + 1.0f, "TIER 2"),
                Arguments.of(cfg.tier3_volumeThreshold + 1.0f, "TIER 3"),
                Arguments.of(cfg.tier4_volumeThreshold + 1.0f, "TIER 4"),
                Arguments.of(cfg.tier5_volumeThreshold + 1.0f, "TIER 5"),
                Arguments.of(cfg.tier6_volumeThreshold + 1.0f, "TIER 6"),
                Arguments.of(cfg.tier7_volumeThreshold + 1.0f, "TIER 7"),
                Arguments.of(cfg.tier8_volumeThreshold + 1.0f, "TIER 8"),
                Arguments.of(cfg.tier9_volumeThreshold + 1.0f, "TIER 9"),
                Arguments.of(cfg.tier10_volumeThreshold + 1.0f, "TIER 10"));
    }

    private static Stream<Arguments> exactVolumeBoundaryCases() {
        LiveTuningConfig cfg = LiveTuningConfig.createDefaults();
        return Stream.of(
                Arguments.of(cfg.tier2_volumeThreshold, "TIER 1"),
                Arguments.of(cfg.tier3_volumeThreshold, "TIER 2"),
                Arguments.of(cfg.tier4_volumeThreshold, "TIER 3"),
                Arguments.of(cfg.tier5_volumeThreshold, "TIER 4"),
                Arguments.of(cfg.tier6_volumeThreshold, "TIER 5"),
                Arguments.of(cfg.tier7_volumeThreshold, "TIER 6"),
                Arguments.of(cfg.tier8_volumeThreshold, "TIER 7"),
                Arguments.of(cfg.tier9_volumeThreshold, "TIER 8"),
                Arguments.of(cfg.tier10_volumeThreshold, "TIER 9"));
    }

    private static Stream<Arguments> largeVenueEchoCases() {
        LiveTuningConfig cfg = LiveTuningConfig.createDefaults();
        return Stream.of(
                Arguments.of(cfg.tier5_volumeThreshold + 1.0f, 0.04f),
                Arguments.of(cfg.tier6_volumeThreshold + 1.0f, 0.10f),
                Arguments.of(cfg.tier7_volumeThreshold + 1.0f, 0.18f),
                Arguments.of(cfg.tier8_volumeThreshold + 1.0f, 0.24f),
                Arguments.of(cfg.tier9_volumeThreshold + 1.0f, 0.28f),
                Arguments.of(cfg.tier10_volumeThreshold + 1.0f, 0.32f));
    }

    private static AdvancedAcousticScanner.VenueDescriptor descriptor(float volume, float scale, float openness) {
        return new AdvancedAcousticScanner.VenueDescriptor(
                1.0f, scale, 0.8f, 0.5f, openness, 0.5f, 0.5f, 0.2f, volume, Math.max(1.0f, volume / 4.0f));
    }
}
