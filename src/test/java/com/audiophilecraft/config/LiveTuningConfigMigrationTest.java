package com.audiophilecraft.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
        assertEquals(1, result.config().config_version);
        assertEquals(0.9f, result.config().line_rearGain);
        assertEquals(0.14f, result.config().echo_maxGain);
        assertEquals(1.0f, result.config().tier6_diffusion);
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
        assertEquals(0.42f, result.config().line_rearGain);
        assertEquals(0.77f, result.config().echo_maxGain);
        assertEquals(0.63f, result.config().tier6_diffusion);
    }

    @Test
    void currentVersionIsLoadedWithoutBackupOrMigration() throws IOException {
        Path configPath = writeConfig(
                """
                {
                  "config_version": 1,
                  "line_rearGain": 0.51
                }
                """);

        LiveTuningConfig.MigrationResult result = LiveTuningConfig.readAndMigrate(configPath);

        assertFalse(result.migrated());
        assertEquals(1, result.sourceVersion());
        assertEquals(0.51f, result.config().line_rearGain);
        assertFalse(Files.exists(backupPath(configPath, 1)));
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

    private Path writeConfig(String content) throws IOException {
        Path configPath = tempDirectory.resolve("audiophilecraft_tuning.json");
        Files.writeString(configPath, content);
        return configPath;
    }

    private static Path backupPath(Path configPath, int version) {
        return configPath.resolveSibling(configPath.getFileName() + ".v" + version + ".bak");
    }
}
