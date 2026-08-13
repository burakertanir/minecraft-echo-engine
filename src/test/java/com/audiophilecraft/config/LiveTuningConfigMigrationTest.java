package com.audiophilecraft.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LiveTuningConfigMigrationTest {
    @TempDir
    Path tempDirectory;

    @Test
    void upgradesOldDefaultsAndCreatesAnExactBackup() throws IOException {
        String original =
                """
                {
                  // Legacy defaults should follow the new built-in tuning.
                  "line_rearGain": 0.25,
                  "echo_maxGain": 0.1,
                  "tier6_diffusion": -1.0
                }
                """;
        Path configPath = writeConfig(original);

        LiveTuningConfig.MigrationResult result = LiveTuningConfig.readAndMigrate(configPath);

        assertTrue(result.migrated());
        assertEquals(0, result.sourceVersion());
        assertEquals(2, result.config().config_version);
        assertEquals(0.9f, result.config().line_rearGain);
        assertEquals(0.18f, result.config().echo_maxGain);
        assertEquals(-1.0f, result.config().tier6_diffusion);
        assertEquals(original, Files.readString(backupPath(configPath, 0)));
    }

    @Test
    void preservesUserCustomizedValuesDuringMigration() throws IOException {
        Path configPath = writeConfig(
                """
                {
                  "config_version": 0,
                  "line_rearGain": 0.42,
                  "echo_maxGain": 0.77,
                  "tier6_diffusion": 0.63
                }
                """);

        LiveTuningConfig.MigrationResult result = LiveTuningConfig.readAndMigrate(configPath);

        assertTrue(result.migrated());
        assertEquals(2, result.config().config_version);
        assertEquals(0.42f, result.config().line_rearGain);
        assertEquals(0.77f, result.config().echo_maxGain);
        assertEquals(0.63f, result.config().tier6_diffusion);
    }

    @Test
    void upgradesVersionOneOldDefaultsAndCreatesABackup() throws IOException {
        String original =
                """
                {
                  "config_version": 1,
                  "mid_refDist": 7.0,
                  "sub_rolloffExponent": 1.6,
                  "hf_line_behindFloor": 0.3,
                  "prox_other_maxBoost": 0.15
                }
                """;
        Path configPath = writeConfig(original);

        LiveTuningConfig.MigrationResult result = LiveTuningConfig.readAndMigrate(configPath);

        assertTrue(result.migrated());
        assertEquals(1, result.sourceVersion());
        assertEquals(2, result.config().config_version);
        assertEquals(8.0f, result.config().mid_refDist);
        assertEquals(1.0f, result.config().sub_rolloffExponent);
        assertEquals(0.1f, result.config().hf_line_behindFloor);
        assertEquals(0.0f, result.config().prox_other_maxBoost);
        assertEquals(original, Files.readString(backupPath(configPath, 1)));
    }

    @Test
    void preservesUserCustomizedValuesDuringVersionOneMigration() throws IOException {
        Path configPath = writeConfig(
                """
                {
                  "config_version": 1,
                  "sub_rolloffExponent": 1.3,
                  "line_rearGain": 0.95,
                  "mid_refDist": 9.0
                }
                """);

        LiveTuningConfig.MigrationResult result = LiveTuningConfig.readAndMigrate(configPath);

        assertTrue(result.migrated());
        assertEquals(2, result.config().config_version);
        assertEquals(1.3f, result.config().sub_rolloffExponent);
        assertEquals(0.95f, result.config().line_rearGain);
        assertEquals(9.0f, result.config().mid_refDist);
    }

    @Test
    void currentVersionIsLoadedWithoutBackupOrMigration() throws IOException {
        Path configPath = writeConfig(
                """
                {
                  "config_version": 2,
                  "line_rearGain": 0.51
                }
                """);

        LiveTuningConfig.MigrationResult result = LiveTuningConfig.readAndMigrate(configPath);

        assertFalse(result.migrated());
        assertEquals(2, result.sourceVersion());
        assertEquals(0.51f, result.config().line_rearGain);
        assertFalse(Files.exists(backupPath(configPath, 2)));
    }

    @Test
    void newerVersionIsPreservedWithoutBeingDowngraded() throws IOException {
        Path configPath = writeConfig(
                """
                {
                  "config_version": 99,
                  "line_rearGain": 0.61
                }
                """);

        LiveTuningConfig.MigrationResult result = LiveTuningConfig.readAndMigrate(configPath);

        assertFalse(result.migrated());
        assertEquals(99, result.config().config_version);
        assertEquals(0.61f, result.config().line_rearGain);
        assertFalse(Files.exists(backupPath(configPath, 99)));
    }

    @Test
    void negativeVersionIsTreatedAsLegacyVersionZero() throws IOException {
        Path configPath = writeConfig(
                """
                {
                  "config_version": -7,
                  "line_rearGain": 0.25
                }
                """);

        LiveTuningConfig.MigrationResult result = LiveTuningConfig.readAndMigrate(configPath);

        assertTrue(result.migrated());
        assertEquals(0, result.sourceVersion());
        assertEquals(2, result.config().config_version);
        assertEquals(0.9f, result.config().line_rearGain);
        assertTrue(Files.exists(backupPath(configPath, 0)));
    }

    @Test
    void commentMarkersInsideQuotedStringsArePreserved() throws IOException {
        Path configPath = writeConfig(
                """
                {
                  "config_version": 2,
                  "documentation_url": "https://example.com/audio//stream",
                  "line_rearGain": 0.37 // trailing comment
                }
                """);

        LiveTuningConfig.MigrationResult result = LiveTuningConfig.readAndMigrate(configPath);

        assertFalse(result.migrated());
        assertEquals(0.37f, result.config().line_rearGain);
    }

    @Test
    void existingBackupIsNeverOverwrittenByALaterMigrationAttempt() throws IOException {
        String original = "{\n  \"line_rearGain\": 0.25\n}\n";
        Path configPath = writeConfig(original);
        Path backup = backupPath(configPath, 0);

        LiveTuningConfig.readAndMigrate(configPath);
        Files.writeString(configPath, "{\n  \"line_rearGain\": 0.26\n}\n");
        LiveTuningConfig.readAndMigrate(configPath);

        assertEquals(original, Files.readString(backup));
    }

    @Test
    void malformedJsonFailsWithoutCreatingABackup() throws IOException {
        Path configPath = writeConfig("{ \"config_version\": 0, \"line_rearGain\": }");

        assertThrows(RuntimeException.class, () -> LiveTuningConfig.readAndMigrate(configPath));
        assertFalse(Files.exists(backupPath(configPath, 0)));
    }

    private Path writeConfig(String content) throws IOException {
        Path configPath = tempDirectory.resolve("audiophilecraft_tuning.json");
        Files.writeString(configPath, content);
        return configPath;
    }

    private static Path backupPath(Path configPath, int version) {
        return configPath.resolveSibling(configPath.getFileName() + ".v" + version + ".bak");
    }
}
