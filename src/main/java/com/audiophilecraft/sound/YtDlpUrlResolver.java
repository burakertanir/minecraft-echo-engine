package com.audiophilecraft.sound;

import com.audiophilecraft.AudiophileCraft;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import net.fabricmc.loader.api.FabricLoader;

/**
 * Resolves YouTube URLs to signed, directly streamable audio URLs using the
 * bundled {@code yt-dlp} executable. This bypasses lavaplayer's built-in
 * YouTube source manager, whose signature solver is frequently defeated by
 * YouTube's JS challenges (HTTP 403 on the stream URL).
 *
 * <p>
 * Configuration is read from {@code config/audiophilecraft_ytdlp.json}:
 * 
 * <pre>
 * {
 *   "ytDlpPath": "yt-dlp",
 *   "denoPath": "deno",
 *   "resolveTimeoutMs": 120000
 * }
 * </pre>
 * 
 * The {@code denoPath} is only used when the resolver is configured with
 * {@code useDeno=true}; it lets yt-dlp solve YouTube's JS challenges.
 */
public final class YtDlpUrlResolver {
    private static final String CONFIG_NAME = "audiophilecraft_ytdlp.json";
    private static final long DEFAULT_RESOLVE_TIMEOUT_MS = 120_000L;

    private volatile String ytDlpPath;
    private volatile String denoPath;
    private volatile boolean useDeno;
    private volatile long resolveTimeoutMs;

    public YtDlpUrlResolver() {
        reloadConfig();
    }

    public synchronized boolean reloadConfig() {
        Config config = Config.load();
        this.ytDlpPath = config.ytDlpPath;
        this.denoPath = config.denoPath;
        this.useDeno = config.useDeno;
        this.resolveTimeoutMs = config.resolveTimeoutMs;
        return isConfigured();
    }

    public boolean isConfigured() {
        return ytDlpPath != null && !ytDlpPath.isBlank();
    }

    public boolean isYoutubeUrl(String url) {
        if (url == null)
            return false;
        String lower = url.toLowerCase(java.util.Locale.ROOT);
        return lower.startsWith("https://www.youtube.com/")
                || lower.startsWith("https://youtube.com/")
                || lower.startsWith("https://youtu.be/")
                || lower.startsWith("https://m.youtube.com/")
                || lower.startsWith("https://music.youtube.com/")
                || lower.startsWith("http://www.youtube.com/")
                || lower.startsWith("http://youtube.com/")
                || lower.startsWith("http://youtu.be/");
    }

    /**
     * Resolves a YouTube URL to a streamable audio URL on a background
     * thread. The result is delivered to {@code onResult} (or {@code onError}).
     */
    public void resolveAsync(String youtubeUrl, Consumer<String> onResult, Consumer<String> onError) {
        Thread thread = new Thread(
                () -> {
                    try {
                        String resolved = resolve(youtubeUrl);
                        onResult.accept(resolved);
                    } catch (Exception e) {
                        onError.accept(e.getMessage());
                    }
                },
                "AudiophileCraft-YtDlpResolver");
        thread.setDaemon(true);
        thread.start();
    }

    /**
     * Resolves synchronously. Blocks the calling thread until yt-dlp finishes
     * or the configured timeout elapses.
     *
     * @throws IllegalStateException when yt-dlp is not configured or the
     *                               process fails/times out.
     */
    public String resolve(String youtubeUrl) {
        if (!isConfigured()) {
            throw new IllegalStateException("yt-dlp is not configured (missing audiophilecraft_ytdlp.json)");
        }
        List<String> command = new ArrayList<>();
        command.add(ytDlpPath);
        command.add("--no-playlist");
        command.add("-f");
        command.add("bestaudio");
        command.add("--get-url");
        if (useDeno && denoPath != null && !denoPath.isBlank()) {
            command.add("--js-runtimes");
            command.add("deno:" + denoPath);
            command.add("--remote-components");
            command.add("ejs:github");
            command.add("--extractor-args");
            command.add("youtube:player_client=web_embedded");
        }
        command.add(youtubeUrl);

        AudiophileCraft.LOGGER.info("Resolving YouTube URL via yt-dlp: {}", youtubeUrl);
        Process process = null;
        try {
            process = new ProcessBuilder(command).redirectErrorStream(true).start();
            StringBuilder output = new StringBuilder();
            byte[] buffer = new byte[4096];
            int read;
            long deadline = System.currentTimeMillis() + resolveTimeoutMs;
            try (var input = process.getInputStream()) {
                while (System.currentTimeMillis() < deadline) {
                    if (input.available() > 0) {
                        read = input.read(buffer);
                        if (read < 0)
                            break;
                        output.append(new String(buffer, 0, read, StandardCharsets.UTF_8));
                    } else {
                        if (!process.isAlive() && input.available() == 0)
                            break;
                        Thread.sleep(50L);
                    }
                }
            }
            if (process.isAlive()) {
                process.destroyForcibly();
                throw new IllegalStateException("yt-dlp timed out after " + resolveTimeoutMs + "ms for: " + youtubeUrl);
            }
            int exitCode = process.waitFor();
            if (exitCode != 0) {
                throw new IllegalStateException(
                        "yt-dlp exited with code " + exitCode + ": " + trimOutput(output.toString()));
            }
            String result = extractUrl(output.toString());
            if (result == null) {
                throw new IllegalStateException("yt-dlp produced no stream URL: " + trimOutput(output.toString()));
            }
            AudiophileCraft.LOGGER.info("yt-dlp resolved stream URL ({} chars).", result.length());
            return result;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while resolving YouTube URL", e);
        } catch (java.io.IOException e) {
            throw new IllegalStateException("Failed to launch yt-dlp ('" + ytDlpPath + "'): " + e.getMessage(), e);
        } finally {
            if (process != null && process.isAlive()) {
                process.destroyForcibly();
            }
        }
    }

    private static String extractUrl(String output) {
        for (String line : output.split("\\R")) {
            String candidate = line.trim();
            if (candidate.startsWith("https://") && candidate.contains("googlevideo.com")) {
                return candidate;
            }
        }
        return null;
    }

    private static String trimOutput(String output) {
        String trimmed = output.trim();
        return trimmed.length() > 400 ? trimmed.substring(0, 400) : trimmed;
    }

    private static final class Config {
        String ytDlpPath;
        String denoPath;
        boolean useDeno;
        long resolveTimeoutMs = DEFAULT_RESOLVE_TIMEOUT_MS;

        static Config load() {
            Config config = null;
            boolean configFileFound = false;
            Path configFilePath = null;
            try {
                configFilePath = FabricLoader.getInstance().getConfigDir().resolve(CONFIG_NAME);
                if (!Files.exists(configFilePath)) {
                    // Development/test fallback: the test JVM's FabricLoader config
                    // directory differs from the dev run directory.
                    Path devConfig = Path.of("run", "config").resolve(CONFIG_NAME);
                    if (Files.exists(devConfig))
                        configFilePath = devConfig;
                }
                if (Files.exists(configFilePath)) {
                    String json = Files.readString(configFilePath, StandardCharsets.UTF_8);
                    config = new com.google.gson.Gson().fromJson(json, Config.class);
                    configFileFound = true;
                }
            } catch (Exception e) {
                AudiophileCraft.LOGGER.warn("Failed to read yt-dlp config, will try auto-detection.", e);
            }
            if (config == null)
                config = new Config();

            // If no config file was found, auto-detect yt-dlp and deno on the system.
            if (!configFileFound) {
                AudiophileCraft.LOGGER.info("No yt-dlp config file found. Auto-detecting yt-dlp and deno...");
                config.ytDlpPath = autoDetectBinary("yt-dlp");
                config.denoPath = autoDetectBinary("deno");
                config.useDeno = config.denoPath != null;

                if (config.ytDlpPath != null) {
                    AudiophileCraft.LOGGER.info(
                            "Auto-detected yt-dlp at: {}{}",
                            config.ytDlpPath,
                            config.denoPath != null ? " (deno at: " + config.denoPath + ")" : " (deno not found)");
                    // Persist the auto-detected config so subsequent launches are instant.
                    writeConfig(configFilePath, config);
                } else {
                    AudiophileCraft.LOGGER.info(
                            "yt-dlp not found on this system. YouTube playback will use the built-in source.");
                }
            }

            // Fill in defaults for configs that were loaded from a file but have null
            // fields.
            if (configFileFound) {
                if (config.ytDlpPath == null)
                    config.ytDlpPath = "yt-dlp";
                if (config.denoPath == null)
                    config.denoPath = "deno";
            }
            if (config.resolveTimeoutMs <= 0)
                config.resolveTimeoutMs = DEFAULT_RESOLVE_TIMEOUT_MS;

            // Environment overrides (useful for headless test runs).
            String envYtDlp = System.getenv("AUDIOPHILECRAFT_YTDLP_PATH");
            if (envYtDlp != null && !envYtDlp.isBlank())
                config.ytDlpPath = envYtDlp;
            String envDeno = System.getenv("AUDIOPHILECRAFT_DENO_PATH");
            if (envDeno != null && !envDeno.isBlank())
                config.denoPath = envDeno;
            String envUseDeno = System.getenv("AUDIOPHILECRAFT_YTDLP_USE_DENO");
            if (envUseDeno != null && !envUseDeno.isBlank())
                config.useDeno = Boolean.parseBoolean(envUseDeno);
            return config;
        }

        /**
         * Searches for a binary on the system PATH and in common install locations.
         * Returns the absolute path if found, or {@code null} if not found.
         */
        private static String autoDetectBinary(String binaryName) {
            boolean isWindows = System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("win");
            String exeName = isWindows ? binaryName + ".exe" : binaryName;

            // 1. Check common install locations.
            String userHome = System.getProperty("user.home", "");
            List<Path> candidates = new ArrayList<>();
            candidates.add(YtDlpBootstrapper.getToolsDirectory().resolve(exeName));
            if (isWindows) {
                candidates.add(Path.of(userHome, "yt-dlp-tools", exeName));
                candidates.add(Path.of(userHome, "AppData", "Local", "Microsoft", "WinGet", "Packages")
                        .resolve(exeName)); // winget installs
                candidates.add(Path.of(userHome, "scoop", "shims", exeName));
                candidates.add(Path.of("C:\\", "tools", exeName));
                candidates.add(Path.of("C:\\", "yt-dlp", exeName));
            } else {
                candidates.add(Path.of(userHome, ".local", "bin", exeName));
                candidates.add(Path.of("/usr/local/bin", exeName));
                candidates.add(Path.of("/usr/bin", exeName));
                candidates.add(Path.of(userHome, ".deno", "bin", exeName));
            }
            for (Path candidate : candidates) {
                try {
                    if (Files.isRegularFile(candidate) && Files.isExecutable(candidate)) {
                        return candidate.toAbsolutePath().toString();
                    }
                } catch (Exception ignored) {
                    // Permission errors, etc.
                }
            }

            // 2. Check system PATH by trying to run the binary.
            try {
                ProcessBuilder pb = new ProcessBuilder(exeName, "--version");
                pb.redirectErrorStream(true);
                Process proc = pb.start();
                byte[] output = proc.getInputStream().readNBytes(256);
                boolean exited = proc.waitFor(5, java.util.concurrent.TimeUnit.SECONDS);
                if (!exited)
                    proc.destroyForcibly();
                if (exited && proc.exitValue() == 0 && output.length > 0) {
                    return exeName; // Available on PATH
                }
            } catch (Exception ignored) {
                // Binary not on PATH
            }

            return null;
        }

        /** Persists the config so subsequent launches skip auto-detection. */
        private static void writeConfig(Path configFilePath, Config config) {
            if (configFilePath == null)
                return;
            try {
                String json = new com.google.gson.GsonBuilder().setPrettyPrinting().create().toJson(config);
                Files.createDirectories(configFilePath.getParent());
                Files.writeString(configFilePath, json, StandardCharsets.UTF_8);
                AudiophileCraft.LOGGER.info("Auto-created yt-dlp config at: {}", configFilePath);
            } catch (Exception e) {
                AudiophileCraft.LOGGER.warn("Failed to write auto-detected yt-dlp config.", e);
            }
        }
    }
}
