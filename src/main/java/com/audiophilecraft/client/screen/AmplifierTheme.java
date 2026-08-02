package com.audiophilecraft.client.screen;

import com.audiophilecraft.client.util.YouTubeThumbnailCache;
import com.mojang.blaze3d.systems.RenderSystem;
import java.awt.Color;
import java.awt.image.BufferedImage;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.render.BufferBuilder;
import net.minecraft.client.render.GameRenderer;
import net.minecraft.client.render.Tessellator;
import net.minecraft.client.render.VertexFormat;
import net.minecraft.client.render.VertexFormats;
import net.minecraft.text.Text;
import org.joml.Matrix4f;

/** Owns the tablet's persistent thumbnail palette and adaptive rendering theme. */
final class AmplifierTheme {
    private static final int[] DEFAULT_COLORS = {0xFF333333, 0xFF555555, 0xFF444444, 0xFF333333};

    private static String currentVideoId;
    private static volatile boolean fetchingThumbnail;
    private static volatile long thumbnailFetchVersion;
    private static final int[] targetColors = DEFAULT_COLORS.clone();
    private static final int[] currentColors = DEFAULT_COLORS.clone();
    private static float timeOffset;
    private static float themeState;
    private static int adaptiveColor = 0xFFFFFFFF;

    int color() {
        return adaptiveColor;
    }

    boolean isDark() {
        return isTargetBackgroundDark();
    }

    boolean isFetchingThumbnail() {
        return fetchingThumbnail;
    }

    String videoId() {
        return currentVideoId;
    }

    int inputProtectionAlpha() {
        return (int) (themeState * 0x77) << 24;
    }

    void updateVideo(String videoId) {
        if (videoId != null && !videoId.equals(currentVideoId)) {
            currentVideoId = videoId;
            fetchThumbnailColorsAsync(videoId);
        } else if (videoId == null && currentVideoId != null) {
            currentVideoId = null;
            resetTargetColors();
        }
    }

    void selectVideo(String videoId) {
        currentVideoId = videoId;
        fetchThumbnailColorsAsync(videoId);
    }

    void renderBackground(DrawContext context, float delta, int startX, int startY, int width, int height) {
        timeOffset += delta * 0.05f;
        for (int i = 0; i < 3; i++) {
            currentColors[i] = lerpColor(currentColors[i], targetColors[i], 0.05f);
        }

        float cx1 = 0.5f + 0.6f * (float) Math.sin(timeOffset * 0.3f);
        float cy1 = 0.5f + 0.6f * (float) Math.cos(timeOffset * 0.2f);
        float cx2 = 0.5f + 0.6f * (float) Math.sin(timeOffset * 0.4f + 2.0f);
        float cy2 = 0.5f + 0.6f * (float) Math.cos(timeOffset * 0.5f + 1.0f);
        float cx3 = 0.5f + 0.6f * (float) Math.sin(timeOffset * 0.2f + 4.0f);
        float cy3 = 0.5f + 0.6f * (float) Math.cos(timeOffset * 0.4f + 5.0f);
        float cx4 = 0.5f + 0.6f * (float) Math.sin(timeOffset * 0.5f + 1.5f);
        float cy4 = 0.5f + 0.6f * (float) Math.cos(timeOffset * 0.3f + 3.0f);

        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.setShader(GameRenderer::getPositionColorProgram);
        Tessellator tessellator = Tessellator.getInstance();
        BufferBuilder bufferBuilder = tessellator.getBuffer();
        Matrix4f matrix = context.getMatrices().peek().getPositionMatrix();
        bufferBuilder.begin(VertexFormat.DrawMode.QUADS, VertexFormats.POSITION_COLOR);

        int pixelSize = 8;
        int gridWidth = width / pixelSize;
        int gridHeight = height / pixelSize;
        for (int gridY = 0; gridY <= gridHeight; gridY++) {
            float py = (float) gridY / gridHeight;
            for (int gridX = 0; gridX <= gridWidth; gridX++) {
                float px = (float) gridX / gridWidth;
                int color = calculateBackgroundColor(px, py, cx1, cy1, cx2, cy2, cx3, cy3, cx4, cy4);
                int drawX = startX + gridX * pixelSize;
                int drawY = startY + gridY * pixelSize;
                int drawWidth = Math.min(pixelSize, startX + width - drawX);
                int drawHeight = Math.min(pixelSize, startY + height - drawY);

                bufferBuilder.vertex(matrix, drawX, drawY, 0).color(color).next();
                bufferBuilder
                        .vertex(matrix, drawX, drawY + drawHeight, 0)
                        .color(color)
                        .next();
                bufferBuilder
                        .vertex(matrix, drawX + drawWidth, drawY + drawHeight, 0)
                        .color(color)
                        .next();
                bufferBuilder
                        .vertex(matrix, drawX + drawWidth, drawY, 0)
                        .color(color)
                        .next();
            }
        }

        tessellator.draw();
        RenderSystem.disableBlend();

        float targetThemeLuminance = isTargetBackgroundDark() ? 0.0f : 1.0f;
        themeState += (targetThemeLuminance - themeState) * 0.05f;
        adaptiveColor = lerpColor(0xFFFFFFFF, 0xFF141414, themeState);
        drawBorder(context, startX, startY, width, height);
    }

    void drawRoundedRect(DrawContext context, int x1, int y1, int x2, int y2, float radius, int color) {
        context.fill(x1 + (int) radius, y1, x2 - (int) radius, y2, color);
        context.fill(x1, y1 + (int) radius, x1 + (int) radius, y2 - (int) radius, color);
        context.fill(x2 - (int) radius, y1 + (int) radius, x2, y2 - (int) radius, color);

        for (int y = 0; y < (int) radius; y++) {
            for (int x = 0; x < (int) radius; x++) {
                if ((x - radius) * (x - radius) + (y - radius) * (y - radius) > radius * radius) continue;
                context.fill(x1 + x, y1 + y, x1 + x + 1, y1 + y + 1, color);
                context.fill(x2 - 1 - x, y1 + y, x2 - x, y1 + y + 1, color);
                context.fill(x1 + x, y2 - 1 - y, x1 + x + 1, y2 - y, color);
                context.fill(x2 - 1 - x, y2 - 1 - y, x2 - x, y2 - y, color);
            }
        }
    }

    ThemedButton button(
            TextRenderer textRenderer,
            int x,
            int y,
            int width,
            int height,
            Text message,
            ButtonWidget.PressAction action) {
        return new ThemedButton(textRenderer, x, y, width, height, message, action);
    }

    private void drawBorder(DrawContext context, int x, int y, int width, int height) {
        context.fill(x - 1, y - 1, x + width + 1, y, adaptiveColor);
        context.fill(x - 1, y + height, x + width + 1, y + height + 1, adaptiveColor);
        context.fill(x - 1, y, x, y + height, adaptiveColor);
        context.fill(x + width, y, x + width + 1, y + height, adaptiveColor);
    }

    private boolean isTargetBackgroundDark() {
        int red = 0;
        int green = 0;
        int blue = 0;
        for (int targetColor : targetColors) {
            red += (targetColor >> 16) & 0xFF;
            green += (targetColor >> 8) & 0xFF;
            blue += targetColor & 0xFF;
        }
        double luminance = (0.299 * red + 0.587 * green + 0.114 * blue) / (255.0 * targetColors.length);
        return luminance < 0.45;
    }

    private void fetchThumbnailColorsAsync(String videoId) {
        long requestVersion = ++thumbnailFetchVersion;
        Thread thread = new Thread(
                () -> {
                    fetchingThumbnail = true;
                    try {
                        if (videoId != null) {
                            BufferedImage image = YouTubeThumbnailCache.loadAndCache(videoId);
                            if (image != null && requestVersion == thumbnailFetchVersion) {
                                int[] dominantColors = getDominantColors(image);
                                targetColors[0] = 0xFF000000 | enhanceColor(dominantColors[0]);
                                targetColors[1] = 0xFF000000 | enhanceColor(dominantColors[1]);
                                targetColors[2] = 0xFF000000 | enhanceColor(dominantColors[2]);
                                targetColors[3] = 0xFF000000 | enhanceColor(dominantColors[0]);
                            }
                        } else if (requestVersion == thumbnailFetchVersion) {
                            resetTargetColors();
                        }
                    } catch (Exception exception) {
                        exception.printStackTrace();
                    } finally {
                        fetchingThumbnail = false;
                    }
                },
                "ECHO-ThumbnailPalette");
        thread.setDaemon(true);
        thread.start();
    }

    private static void resetTargetColors() {
        System.arraycopy(DEFAULT_COLORS, 0, targetColors, 0, DEFAULT_COLORS.length);
    }

    private static int[] getDominantColors(BufferedImage image) {
        int[] bins = new int[4096];
        long[] redSums = new long[4096];
        long[] greenSums = new long[4096];
        long[] blueSums = new long[4096];
        int step = Math.max(1, image.getWidth() / 32);
        for (int x = 0; x < image.getWidth(); x += step) {
            for (int y = 0; y < image.getHeight(); y += step) {
                int rgb = image.getRGB(x, y);
                int red = (rgb >> 16) & 0xFF;
                int green = (rgb >> 8) & 0xFF;
                int blue = rgb & 0xFF;
                int bin = ((red >> 4) << 8) | ((green >> 4) << 4) | (blue >> 4);
                bins[bin]++;
                redSums[bin] += red;
                greenSums[bin] += green;
                blueSums[bin] += blue;
            }
        }

        int primary = mostAbundantBin(bins, -1, -1, -1);
        int secondary = mostAbundantDistinctBin(bins, primary, -1);
        if (secondary == -1) secondary = mostAbundantBin(bins, primary, -1, -1);
        int tertiary = mostAbundantDistinctBin(bins, primary, secondary);
        if (tertiary == -1) tertiary = mostAbundantBin(bins, primary, secondary, -1);
        if (secondary == -1) secondary = primary;
        if (tertiary == -1) tertiary = secondary;

        return new int[] {
            averageBinColor(primary, bins, redSums, greenSums, blueSums, 0x222222),
            averageBinColor(secondary, bins, redSums, greenSums, blueSums, 0x222222),
            averageBinColor(tertiary, bins, redSums, greenSums, blueSums, 0x222222)
        };
    }

    private static int mostAbundantDistinctBin(int[] bins, int primary, int secondary) {
        int selected = -1;
        int maxCount = 0;
        for (int bin = 0; bin < bins.length; bin++) {
            if (bin == primary || bin == secondary) continue;
            if (!isDistinct(bin, primary) || (secondary >= 0 && !isDistinct(bin, secondary))) continue;
            if (bins[bin] > maxCount) {
                maxCount = bins[bin];
                selected = bin;
            }
        }
        return selected;
    }

    private static int mostAbundantBin(int[] bins, int excluded1, int excluded2, int excluded3) {
        int selected = -1;
        int maxCount = 0;
        for (int bin = 0; bin < bins.length; bin++) {
            if (bin == excluded1 || bin == excluded2 || bin == excluded3) continue;
            if (bins[bin] > maxCount) {
                maxCount = bins[bin];
                selected = bin;
            }
        }
        return selected;
    }

    private static boolean isDistinct(int first, int second) {
        if (second < 0) return true;
        int redDelta = (first >> 8) - (second >> 8);
        int greenDelta = ((first >> 4) & 0xF) - ((second >> 4) & 0xF);
        int blueDelta = (first & 0xF) - (second & 0xF);
        return redDelta * redDelta + greenDelta * greenDelta + blueDelta * blueDelta >= 16;
    }

    private static int averageBinColor(
            int bin, int[] counts, long[] redSums, long[] greenSums, long[] blueSums, int fallback) {
        if (bin < 0 || counts[bin] == 0) return fallback;
        return (int) (redSums[bin] / counts[bin]) << 16
                | (int) (greenSums[bin] / counts[bin]) << 8
                | (int) (blueSums[bin] / counts[bin]);
    }

    private static int enhanceColor(int color) {
        float[] hsb = Color.RGBtoHSB((color >> 16) & 0xFF, (color >> 8) & 0xFF, color & 0xFF, null);
        hsb[1] = Math.min(1.0f, hsb[1] * 1.3f);
        hsb[2] = Math.max(0.25f, Math.min(0.85f, hsb[2] * 1.15f));
        return Color.HSBtoRGB(hsb[0], hsb[1], hsb[2]) & 0xFFFFFF;
    }

    private static int lerpColor(int first, int second, float amount) {
        int firstRed = (first >> 16) & 0xFF;
        int firstGreen = (first >> 8) & 0xFF;
        int firstBlue = first & 0xFF;
        int secondRed = (second >> 16) & 0xFF;
        int secondGreen = (second >> 8) & 0xFF;
        int secondBlue = second & 0xFF;
        return 0xFF000000
                | ((int) (firstRed + (secondRed - firstRed) * amount) << 16)
                | ((int) (firstGreen + (secondGreen - firstGreen) * amount) << 8)
                | (int) (firstBlue + (secondBlue - firstBlue) * amount);
    }

    private static int calculateBackgroundColor(
            float px,
            float py,
            float cx1,
            float cy1,
            float cx2,
            float cy2,
            float cx3,
            float cy3,
            float cx4,
            float cy4) {
        float[] distances = {
            squaredDistance(px, py, cx1, cy1),
            squaredDistance(px, py, cx2, cy2),
            squaredDistance(px, py, cx3, cy3),
            squaredDistance(px, py, cx4, cy4)
        };
        float[] weights = new float[4];
        float weightSum = 0.0f;
        for (int i = 0; i < weights.length; i++) {
            weights[i] = 1.0f / (1.0f + distances[i] * 8.0f);
            weightSum += weights[i];
        }

        float red = 0.0f;
        float green = 0.0f;
        float blue = 0.0f;
        for (int i = 0; i < weights.length; i++) {
            float weight = weights[i] / weightSum;
            red += ((currentColors[i] >> 16) & 0xFF) * weight;
            green += ((currentColors[i] >> 8) & 0xFF) * weight;
            blue += (currentColors[i] & 0xFF) * weight;
        }
        return 0xFF000000 | ((int) red << 16) | ((int) green << 8) | (int) blue;
    }

    private static float squaredDistance(float x1, float y1, float x2, float y2) {
        float deltaX = x1 - x2;
        float deltaY = y1 - y2;
        return deltaX * deltaX + deltaY * deltaY;
    }

    final class ThemedButton extends ButtonWidget {
        private final TextRenderer textRenderer;

        private ThemedButton(
                TextRenderer textRenderer, int x, int y, int width, int height, Text message, PressAction action) {
            super(x, y, width, height, message, action, supplier -> supplier.get());
            this.textRenderer = textRenderer;
        }

        @Override
        public void renderButton(DrawContext context, int mouseX, int mouseY, float delta) {
            int hoverFill = isHovered() ? (isDark() ? 0x44FFFFFF : 0x44000000) : 0;
            context.fill(getX(), getY(), getX() + width, getY() + 1, adaptiveColor);
            context.fill(getX(), getY() + height - 1, getX() + width, getY() + height, adaptiveColor);
            context.fill(getX(), getY(), getX() + 1, getY() + height, adaptiveColor);
            context.fill(getX() + width - 1, getY(), getX() + width, getY() + height, adaptiveColor);
            if (hoverFill != 0) {
                context.fill(getX() + 1, getY() + 1, getX() + width - 1, getY() + height - 1, hoverFill);
            }
            int textWidth = textRenderer.getWidth(getMessage());
            context.drawText(
                    textRenderer,
                    getMessage(),
                    getX() + (width - textWidth) / 2,
                    getY() + (height - 8) / 2,
                    adaptiveColor,
                    false);
        }
    }
}
