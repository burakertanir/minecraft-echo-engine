package com.audiophilecraft.sound;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.audiophilecraft.config.LiveTuningConfig;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import net.minecraft.util.math.Vec3d;
import org.junit.jupiter.api.Test;

class AdvancedAcousticScannerVolumeTest {
    @Test
    void sparseDistantRayHitsDoNotInflateVenueTier() {
        List<Vec3d> pointCloud = boxCorners(1_000, 10.0, 5.0, 4.0);
        for (int index = 0; index < 5; index++) {
            pointCloud.add(new Vec3d(200.0 + index, 2.5, 2.0));
        }

        float volume = AdvancedAcousticScanner.computeBoundingBoxVolume(pointCloud);
        AdvancedAcousticScanner.VenuePreset preset = new VenuePresetCalculator()
                .calculate(descriptor(volume), Vec3d.ZERO, LiveTuningConfig.createDefaults());

        assertEquals(200.0f, volume, 0.001f);
        assertTrue(preset.tierName.startsWith("TIER 1"), preset.tierName);
    }

    @Test
    void distantWallSeenByEnoughRaysStillExpandsVenue() {
        List<Vec3d> pointCloud = boxCorners(1_000, 10.0, 5.0, 4.0);
        for (int index = 0; index < 25; index++) {
            pointCloud.add(new Vec3d(100.0, index % 2 == 0 ? 0.0 : 5.0, index % 3 == 0 ? 0.0 : 4.0));
        }

        assertEquals(2_000.0f, AdvancedAcousticScanner.computeBoundingBoxVolume(pointCloud), 0.001f);
    }

    @Test
    void robustBoundsAreIndependentOfPointOrder() {
        List<Vec3d> pointCloud = boxCorners(1_000, 12.0, 6.0, 3.0);
        pointCloud.add(new Vec3d(-150.0, 3.0, 1.5));
        pointCloud.add(new Vec3d(180.0, 3.0, 1.5));
        float expected = AdvancedAcousticScanner.computeBoundingBoxVolume(pointCloud);

        Collections.reverse(pointCloud);

        assertEquals(expected, AdvancedAcousticScanner.computeBoundingBoxVolume(pointCloud), 0.0f);
        assertEquals(216.0f, expected, 0.001f);
    }

    @Test
    void smallPointCloudKeepsExactBoundsInsteadOfDiscardingEvidence() {
        List<Vec3d> pointCloud = boxCorners(8, 10.0, 5.0, 4.0);
        pointCloud.add(new Vec3d(20.0, 5.0, 4.0));

        assertEquals(400.0f, AdvancedAcousticScanner.computeBoundingBoxVolume(pointCloud), 0.001f);
    }

    private static List<Vec3d> boxCorners(int count, double width, double height, double depth) {
        List<Vec3d> points = new ArrayList<>(count);
        for (int index = 0; index < count; index++) {
            points.add(new Vec3d(
                    (index & 1) == 0 ? 0.0 : width, (index & 2) == 0 ? 0.0 : height, (index & 4) == 0 ? 0.0 : depth));
        }
        return points;
    }

    private static AdvancedAcousticScanner.VenueDescriptor descriptor(float volume) {
        return new AdvancedAcousticScanner.VenueDescriptor(
                1.0f, 1.0f, 0.8f, 0.5f, 0.0f, 0.5f, 0.5f, 0.2f, volume, Math.max(1.0f, volume / 4.0f));
    }
}
