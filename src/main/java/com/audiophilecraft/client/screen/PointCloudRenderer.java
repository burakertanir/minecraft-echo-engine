package com.audiophilecraft.client.screen;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.render.BufferBuilder;
import net.minecraft.client.render.GameRenderer;
import net.minecraft.client.render.Tessellator;
import net.minecraft.client.render.VertexFormat;
import net.minecraft.client.render.VertexFormats;
import net.minecraft.util.math.Vec3d;
import com.mojang.blaze3d.systems.RenderSystem;
import org.joml.Matrix4f;
import org.lwjgl.opengl.GL11;

import java.util.List;

/**
 * Renders reverb scan data as a LiDAR-style 3D point cloud.
 * Each ray hit is a small glowing dot with height-based spectral coloring.
 * Uses raw Vec3d positions for sub-block precision — smooth, organic look.
 */
public class PointCloudRenderer {

    // Cached projected points: [screenX, screenY, depth, color]
    private static float[][] cachedProjected = null;
    private static int cachedCount = -1;
    private static double cachedCx, cachedCy, cachedCz, cachedMaxSize;

    private static final double COS_Y = Math.cos(Math.toRadians(45));
    private static final double SIN_Y = Math.sin(Math.toRadians(45));
    private static final double COS_X = Math.cos(Math.toRadians(30));
    private static final double SIN_X = Math.sin(Math.toRadians(30));

    public static void invalidateCache() {
        cachedProjected = null;
        cachedCount = -1;
    }

    public static void render(DrawContext context, int x, int y, int width, int height,
                              List<Vec3d> pointCloud, List<net.minecraft.util.math.BlockPos> speakers) {

        if (pointCloud == null || pointCloud.isEmpty()) return;

        if (cachedProjected == null || cachedCount != pointCloud.size()) {
            rebuildCache(pointCloud);
        }
        if (cachedProjected == null || cachedProjected.length == 0) return;

        float scale = (float)((Math.min(width, height) * 0.95) / cachedMaxSize);
        float centerX = x + width / 2.0f;
        float centerY = y + height / 2.0f;

        // Adaptive point size: proportional to scale so large venues don't overlap
        // Small room (scale~5) → dotSize~1.5px, Stadium (scale~0.5) → dotSize~0.3px  
        float dotSize = Math.max(0.3f, scale * 0.3f);
        float glowSize = dotSize * 1.8f;

        // Scissor clipping (DrawContext handles GUI-scale transform internally)
        context.enableScissor(x + 10, y + 30, x + 10 + width - 20, y + 30 + height - 60);

        RenderSystem.enableBlend();
        RenderSystem.setShader(GameRenderer::getPositionColorProgram);

        Tessellator tessellator = Tessellator.getInstance();
        BufferBuilder buffer;
        Matrix4f matrix = context.getMatrices().peek().getPositionMatrix();

        // ─── Pass 1: Glow layer (additive, larger, dimmer) ──────────
        RenderSystem.blendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE); // additive
        buffer = tessellator.getBuffer();
        buffer.begin(VertexFormat.DrawMode.QUADS, VertexFormats.POSITION_COLOR);

        for (float[] p : cachedProjected) {
            float sx = centerX + p[0] * scale;
            float sy = centerY + p[1] * scale;
            float hr = p[4];
            // Top points fade: bottom=full glow, top=minimal glow
            int glowAlpha = (int)(0x18 * (1.0f - hr * 0.7f));
            int color = Float.floatToRawIntBits(p[3]);
            int glowColor = (glowAlpha << 24) | (color & 0x00FFFFFF);

            buffer.vertex(matrix, sx - glowSize, sy - glowSize, 0).color(glowColor).next();
            buffer.vertex(matrix, sx - glowSize, sy + glowSize, 0).color(glowColor).next();
            buffer.vertex(matrix, sx + glowSize, sy + glowSize, 0).color(glowColor).next();
            buffer.vertex(matrix, sx + glowSize, sy - glowSize, 0).color(glowColor).next();
        }
        tessellator.draw();

        // ─── Pass 2: Core dots (normal blend, sharp) ────────────────
        RenderSystem.defaultBlendFunc(); // normal alpha blend
        buffer = tessellator.getBuffer();
        buffer.begin(VertexFormat.DrawMode.QUADS, VertexFormats.POSITION_COLOR);

        for (float[] p : cachedProjected) {
            float sx = centerX + p[0] * scale;
            float sy = centerY + p[1] * scale;
            float hr = p[4];
            // Top points fade: bottom=100% alpha, top=30% alpha
            int coreAlpha = (int)(255 * (1.0f - hr * 0.7f));
            int color = Float.floatToRawIntBits(p[3]);
            int fullColor = (coreAlpha << 24) | (color & 0x00FFFFFF);

            buffer.vertex(matrix, sx - dotSize, sy - dotSize, 0).color(fullColor).next();
            buffer.vertex(matrix, sx - dotSize, sy + dotSize, 0).color(fullColor).next();
            buffer.vertex(matrix, sx + dotSize, sy + dotSize, 0).color(fullColor).next();
            buffer.vertex(matrix, sx + dotSize, sy - dotSize, 0).color(fullColor).next();
        }
        tessellator.draw();

        // ─── Pass 3: Speakers (Distinct points) ──────────────
        if (speakers != null && !speakers.isEmpty()) {
            buffer = tessellator.getBuffer();
            buffer.begin(VertexFormat.DrawMode.QUADS, VertexFormats.POSITION_COLOR);
            
            float speakerDotSize = dotSize * 4.0f; // Larger than LiDAR points
            int speakerColor = 0xFFFFFFFF; // Bright white

            for (net.minecraft.util.math.BlockPos sp : speakers) {
                // Project speaker position dynamically
                double lx = sp.getX() + 0.5 - cachedCx;
                double ly = sp.getY() + 0.5 - cachedCy;
                double lz = sp.getZ() + 0.5 - cachedCz;

                double rx = lx * COS_Y - lz * SIN_Y;
                double rz = lx * SIN_Y + lz * COS_Y;
                double ry = ly * COS_X - rz * SIN_X;

                float sx = centerX + (float)rx * scale;
                float sy = centerY - (float)ry * scale;

                buffer.vertex(matrix, sx - speakerDotSize, sy - speakerDotSize, 0).color(speakerColor).next();
                buffer.vertex(matrix, sx - speakerDotSize, sy + speakerDotSize, 0).color(speakerColor).next();
                buffer.vertex(matrix, sx + speakerDotSize, sy + speakerDotSize, 0).color(speakerColor).next();
                buffer.vertex(matrix, sx + speakerDotSize, sy - speakerDotSize, 0).color(speakerColor).next();
            }
            tessellator.draw();
        }

        RenderSystem.disableBlend();
        context.disableScissor();
    }

    private static void rebuildCache(List<Vec3d> pointCloud) {
        cachedCount = pointCloud.size();

        // Find bounds using raw positions
        double minX = Double.MAX_VALUE, minY = Double.MAX_VALUE, minZ = Double.MAX_VALUE;
        double maxX = -Double.MAX_VALUE, maxY = -Double.MAX_VALUE, maxZ = -Double.MAX_VALUE;
        for (Vec3d pt : pointCloud) {
            if (pt.x < minX) minX = pt.x;
            if (pt.y < minY) minY = pt.y;
            if (pt.z < minZ) minZ = pt.z;
            if (pt.x > maxX) maxX = pt.x;
            if (pt.y > maxY) maxY = pt.y;
            if (pt.z > maxZ) maxZ = pt.z;
        }

        cachedCx = (minX + maxX) / 2.0;
        cachedCy = (minY + maxY) / 2.0;
        cachedCz = (minZ + maxZ) / 2.0;
        cachedMaxSize = Math.max(Math.max(maxX - minX, maxZ - minZ), maxY - minY);
        if (cachedMaxSize < 5) cachedMaxSize = 5;

        // Project all points to 2D and sort back-to-front
        cachedProjected = new float[pointCloud.size()][5];
        for (int i = 0; i < pointCloud.size(); i++) {
            Vec3d pt = pointCloud.get(i);
            double lx = pt.x - cachedCx;
            double ly = pt.y - cachedCy;
            double lz = pt.z - cachedCz;

            double rx = lx * COS_Y - lz * SIN_Y;
            double rz = lx * SIN_Y + lz * COS_Y;
            double ry = ly * COS_X - rz * SIN_X;

            // Height ratio for coloring + opacity
            float hr = (float)((pt.y - minY) / Math.max(1, maxY - minY));

            cachedProjected[i][0] = (float) rx;           // normalized X
            cachedProjected[i][1] = (float) -ry;          // normalized Y (inverted)
            cachedProjected[i][2] = (float)(lx + lz - ly); // depth for sorting
            cachedProjected[i][3] = Float.intBitsToFloat(getLidarColor(hr)); // packed color
            cachedProjected[i][4] = hr;                   // height ratio for opacity
        }

        // Sort back-to-front
        java.util.Arrays.sort(cachedProjected, (a, b) -> Float.compare(a[2], b[2]));

        System.out.println("PointCloudRenderer: " + pointCloud.size() + " LiDAR points cached");
    }

    /**
     * LiDAR spectral color: blue → cyan → green → yellow → red
     * Classic height-based coloring used in professional LiDAR visualizations.
     */
    private static int getLidarColor(float t) {
        t = Math.max(0, Math.min(1, t));
        int r, g, b;
        if (t < 0.2f) {
            // Deep blue → Blue
            float f = t / 0.2f;
            r = 0; g = 0; b = (int)(128 + 127 * f);
        } else if (t < 0.4f) {
            // Blue → Cyan
            float f = (t - 0.2f) / 0.2f;
            r = 0; g = (int)(255 * f); b = 255;
        } else if (t < 0.6f) {
            // Cyan → Green
            float f = (t - 0.4f) / 0.2f;
            r = 0; g = 255; b = (int)(255 * (1 - f));
        } else if (t < 0.8f) {
            // Green → Yellow
            float f = (t - 0.6f) / 0.2f;
            r = (int)(255 * f); g = 255; b = 0;
        } else {
            // Yellow → Red
            float f = (t - 0.8f) / 0.2f;
            r = 255; g = (int)(255 * (1 - f)); b = 0;
        }
        return (r << 16) | (g << 8) | b;
    }
}
