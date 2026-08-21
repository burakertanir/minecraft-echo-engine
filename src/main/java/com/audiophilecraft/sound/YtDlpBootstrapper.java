package com.audiophilecraft.sound;

import com.audiophilecraft.AudiophileCraft;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.text.Text;

/**
 * Automatically downloads and configures {@code yt-dlp} and {@code deno} binaries
 * on first use for clients that do not have them pre-installed.
 */
public final class YtDlpBootstrapper {
    private static final String YT_DLP_WIN_URL =
            "https://github.com/yt-dlp/yt-dlp/releases/latest/download/yt-dlp.exe";
    private static final String YT_DLP_LINUX_URL =
            "https://github.com/yt-dlp/yt-dlp/releases/latest/download/yt-dlp_linux";
    private static final String YT_DLP_MAC_URL =
            "https://github.com/yt-dlp/yt-dlp/releases/latest/download/yt-dlp_macos";

    private static final String DENO_WIN_X64_URL =
            "https://github.com/denoland/deno/releases/latest/download/deno-x86_64-pc-windows-msvc.zip";
    private static final String DENO_LINUX_X64_URL =
            "https://github.com/denoland/deno/releases/latest/download/deno-x86_64-unknown-linux-gnu.zip";
    private static final String DENO_LINUX_ARM64_URL =
            "https://github.com/denoland/deno/releases/latest/download/deno-aarch64-unknown-linux-gnu.zip";
    private static final String DENO_MAC_ARM64_URL =
            "https://github.com/denoland/deno/releases/latest/download/deno-aarch64-apple-darwin.zip";
    private static final String DENO_MAC_X64_URL =
            "https://github.com/denoland/deno/releases/latest/download/deno-x86_64-apple-darwin.zip";

    private static final Object LOCK = new Object();
    private static CompletableFuture<Boolean> currentBootstrapFuture = null;
    private static final AtomicBoolean bootstrapCompleted = new AtomicBoolean(false);

    private YtDlpBootstrapper() {}

    /**
     * Checks whether tools are already downloaded and present in the mod's tools directory.
     */
    public static boolean areToolsPresent() {
        try {
            Path toolsDir = getToolsDirectory();
            boolean isWin = isWindows();
            Path ytDlp = toolsDir.resolve(isWin ? "yt-dlp.exe" : "yt-dlp");
            Path deno = toolsDir.resolve(isWin ? "deno.exe" : "deno");
            return Files.isRegularFile(ytDlp) && Files.isRegularFile(deno);
        } catch (Exception e) {
            return false;
        }
    }

    public static Path getToolsDirectory() {
        return FabricLoader.getInstance().getGameDir().resolve("audiophilecraft").resolve("tools");
    }

    /**
     * Asynchronously ensures that yt-dlp and deno are installed.
     * If already installed or currently downloading, returns the existing future.
     */
    public static CompletableFuture<Boolean> ensureInstalledAsync() {
        synchronized (LOCK) {
            if (bootstrapCompleted.get() || areToolsPresent()) {
                bootstrapCompleted.set(true);
                return CompletableFuture.completedFuture(true);
            }
            if (currentBootstrapFuture != null && !currentBootstrapFuture.isDone()) {
                return currentBootstrapFuture;
            }

            currentBootstrapFuture = new CompletableFuture<>();
            Thread downloadThread = new Thread(
                    () -> {
                        try {
                            AudiophileCraft.LOGGER.info("Starting automatic download of yt-dlp and deno...");
                            notifyPlayer("§e[AudiophileCraft] ⏬ YouTube desteği için yt-dlp ve deno indiriliyor (ilk seferlik)...");

                            Path toolsDir = getToolsDirectory();
                            Files.createDirectories(toolsDir);

                            boolean isWin = isWindows();
                            String ytDlpFilename = isWin ? "yt-dlp.exe" : "yt-dlp";
                            String denoFilename = isWin ? "deno.exe" : "deno";

                            Path ytDlpTarget = toolsDir.resolve(ytDlpFilename);
                            Path denoTarget = toolsDir.resolve(denoFilename);

                            // 1. Download yt-dlp if needed
                            if (!Files.isRegularFile(ytDlpTarget)) {
                                String ytDlpUrl = getYtDlpDownloadUrl();
                                AudiophileCraft.LOGGER.info("Downloading yt-dlp from: {}", ytDlpUrl);
                                downloadHttpFile(ytDlpUrl, ytDlpTarget);
                                if (!isWin) {
                                    setExecutable(ytDlpTarget);
                                }
                                AudiophileCraft.LOGGER.info("yt-dlp download complete: {}", ytDlpTarget);
                            }

                            // 2. Download deno if needed
                            if (!Files.isRegularFile(denoTarget)) {
                                String denoUrl = getDenoDownloadUrl();
                                Path denoZip = toolsDir.resolve("deno_download.zip");
                                AudiophileCraft.LOGGER.info("Downloading deno from: {}", denoUrl);
                                downloadHttpFile(denoUrl, denoZip);
                                AudiophileCraft.LOGGER.info("Extracting deno to: {}", denoTarget);
                                extractZipEntry(denoZip, denoFilename, denoTarget);
                                if (!isWin) {
                                    setExecutable(denoTarget);
                                }
                                AudiophileCraft.LOGGER.info("deno setup complete: {}", denoTarget);
                            }

                            bootstrapCompleted.set(true);
                            notifyPlayer("§a[AudiophileCraft] ✅ yt-dlp ve deno başarıyla kuruldu! YouTube desteği hazır.");
                            AudiophileCraft.LOGGER.info("yt-dlp and deno bootstrapping succeeded.");
                            currentBootstrapFuture.complete(true);
                        } catch (Throwable t) {
                            AudiophileCraft.LOGGER.error("Failed to download yt-dlp or deno", t);
                            notifyPlayer("§c[AudiophileCraft] ❌ yt-dlp otomatik kurulumu başarısız: " + t.getMessage());
                            currentBootstrapFuture.complete(false);
                        }
                    },
                    "AudiophileCraft-YtDlpBootstrapper");

            downloadThread.setDaemon(true);
            downloadThread.start();
            return currentBootstrapFuture;
        }
    }

    private static void setExecutable(Path path) {
        try {
            path.toFile().setExecutable(true, false);
            path.toFile().setReadable(true, false);
        } catch (Exception e) {
            AudiophileCraft.LOGGER.warn("Failed to set executable bit on {}", path, e);
        }
    }

    private static void notifyPlayer(String message) {
        try {
            MinecraftClient client = MinecraftClient.getInstance();
            if (client != null) {
                client.execute(() -> {
                    if (client.player != null) {
                        client.player.sendMessage(Text.literal(message), false);
                    }
                });
            }
        } catch (Throwable ignored) {
        }
    }

    private static void downloadHttpFile(String urlString, Path targetPath) throws IOException {
        String currentUrl = urlString;
        Path tempPath = targetPath.resolveSibling(targetPath.getFileName() + ".tmp");
        Files.createDirectories(targetPath.getParent());

        int redirects = 0;
        while (redirects < 10) {
            URL url = new URL(currentUrl);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setInstanceFollowRedirects(true);
            conn.setRequestProperty("User-Agent", "AudiophileCraft-Mod/1.0");
            conn.setConnectTimeout(20_000);
            conn.setReadTimeout(60_000);

            int status = conn.getResponseCode();
            if (status == HttpURLConnection.HTTP_MOVED_PERM
                    || status == HttpURLConnection.HTTP_MOVED_TEMP
                    || status == HttpURLConnection.HTTP_SEE_OTHER
                    || status == 307
                    || status == 308) {
                String redirectUrl = conn.getHeaderField("Location");
                conn.disconnect();
                if (redirectUrl == null || redirectUrl.isBlank()) {
                    throw new IOException("HTTP redirect received with no Location header");
                }
                currentUrl = redirectUrl;
                redirects++;
                continue;
            }

            if (status != HttpURLConnection.HTTP_OK) {
                conn.disconnect();
                throw new IOException("HTTP " + status + " while downloading " + currentUrl);
            }

            try (InputStream in = conn.getInputStream();
                    OutputStream out = Files.newOutputStream(tempPath)) {
                byte[] buffer = new byte[16384];
                int read;
                while ((read = in.read(buffer)) != -1) {
                    out.write(buffer, 0, read);
                }
            } finally {
                conn.disconnect();
            }

            Files.move(tempPath, targetPath, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            return;
        }

        throw new IOException("Too many redirects downloading " + urlString);
    }

    private static void extractZipEntry(Path zipPath, String expectedEntryName, Path targetFilePath)
            throws IOException {
        Path tempPath = targetFilePath.resolveSibling(targetFilePath.getFileName() + ".tmp");
        boolean found = false;

        try (ZipInputStream zis = new ZipInputStream(Files.newInputStream(zipPath))) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                String name = entry.getName();
                if (!entry.isDirectory()
                        && (name.equalsIgnoreCase(expectedEntryName) || name.endsWith("/" + expectedEntryName))) {
                    try (OutputStream out = Files.newOutputStream(tempPath)) {
                        byte[] buffer = new byte[16384];
                        int read;
                        while ((read = zis.read(buffer)) != -1) {
                            out.write(buffer, 0, read);
                        }
                    }
                    found = true;
                    break;
                }
            }
        } finally {
            try {
                Files.deleteIfExists(zipPath);
            } catch (Exception ignored) {
            }
        }

        if (!found) {
            throw new IOException("Entry '" + expectedEntryName + "' not found inside zip " + zipPath);
        }

        Files.move(tempPath, targetFilePath, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    private static String getYtDlpDownloadUrl() {
        if (isWindows()) return YT_DLP_WIN_URL;
        if (isMac()) return YT_DLP_MAC_URL;
        return YT_DLP_LINUX_URL;
    }

    private static String getDenoDownloadUrl() {
        if (isWindows()) return DENO_WIN_X64_URL;
        boolean arm = isArm();
        if (isMac()) {
            return arm ? DENO_MAC_ARM64_URL : DENO_MAC_X64_URL;
        }
        return arm ? DENO_LINUX_ARM64_URL : DENO_LINUX_X64_URL;
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }

    private static boolean isMac() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        return os.contains("mac") || os.contains("darwin");
    }

    private static boolean isArm() {
        String arch = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
        return arch.contains("aarch64") || arch.contains("arm");
    }
}
