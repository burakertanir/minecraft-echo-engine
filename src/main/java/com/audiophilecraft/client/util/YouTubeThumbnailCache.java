package com.audiophilecraft.client.util;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.texture.NativeImage;
import net.minecraft.client.texture.NativeImageBackedTexture;
import net.minecraft.util.Identifier;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.HashMap;
import java.util.Map;
import java.awt.image.BufferedImage;
import javax.imageio.ImageIO;

public class YouTubeThumbnailCache {
    private static final Map<String, Identifier> cache = new HashMap<>();

    public static Identifier getIdentifier(String videoId) {
        return cache.get(videoId);
    }

    public static BufferedImage loadAndCache(String videoId) {
        if (videoId == null || cache.containsKey(videoId)) return null;

        try {
            URL imageUrl = new URL("https://img.youtube.com/vi/" + videoId + "/mqdefault.jpg");
            HttpURLConnection conn = (HttpURLConnection) imageUrl.openConnection();
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64)");
            
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            try (InputStream in = conn.getInputStream()) {
                byte[] buffer = new byte[8192];
                int read;
                while ((read = in.read(buffer)) != -1) {
                    baos.write(buffer, 0, read);
                }
            }
            byte[] rawBytes = baos.toByteArray();
            BufferedImage original = ImageIO.read(new ByteArrayInputStream(rawBytes));
            if (original == null) return null;

            // Crop to 180x180 center square (original is 320x180)
            int cropX = 70;
            int cropSize = 180;
            if(original.getWidth() < 320) {
                 cropX = (original.getWidth() - original.getHeight()) / 2;
                 cropSize = original.getHeight();
            }
            BufferedImage cropped = original.getSubimage(cropX, 0, cropSize, cropSize);

            // Pre-process High-Quality Rounded Corners and Gaussian Shadow using Java 2D
            int shadowPadding = 45; // Expanded mathematically to yield an exact 12px scaled padding
            int canvasSize = cropSize + shadowPadding * 2;
            int cornerRadius = 24; // Smooth rounded corners
            
            BufferedImage processed = new BufferedImage(canvasSize, canvasSize, BufferedImage.TYPE_INT_ARGB);
            
            // 1. Procedural True Gaussian Drop Shadow (25% Opacity max)
            int shadowYOffset = 6;
            float maxOpacity = 0.25f;
            float blurRadius = 30.0f; // Extremely Soft Gaussian spread
            
            for (int y = 0; y < canvasSize; y++) {
                for (int x = 0; x < canvasSize; x++) {
                    float px = x + 0.5f;
                    float py = y + 0.5f;
                    
                    float boxX = shadowPadding + cornerRadius;
                    float boxY = shadowPadding + shadowYOffset + cornerRadius;
                    float boxW = cropSize - cornerRadius * 2;
                    float boxH = cropSize - cornerRadius * 2;
                    
                    float dx = Math.max(0, Math.max(boxX - px, px - (boxX + boxW)));
                    float dy = Math.max(0, Math.max(boxY - py, py - (boxY + boxH)));
                    float dist = (float)Math.sqrt(dx*dx + dy*dy);
                    
                    if (dist < cornerRadius + blurRadius) {
                        if (dist > cornerRadius) {
                            float d = dist - cornerRadius;
                            float sigma = blurRadius / 2.0f;
                            float val = (float)Math.exp(-(d*d) / (2 * sigma * sigma));
                            int alpha = (int)(val * maxOpacity * 255);
                            processed.setRGB(x, y, (alpha << 24) | 0x000000);
                        } else {
                            int alpha = (int)(maxOpacity * 255);
                            processed.setRGB(x, y, (alpha << 24) | 0x000000);
                        }
                    }
                }
            }
            
            // 2. Perfectly Anti-Aliased Thumbnail using AlphaComposite Masking
            BufferedImage thumbnailRounded = new BufferedImage(cropSize, cropSize, BufferedImage.TYPE_INT_ARGB);
            java.awt.Graphics2D gThumb = thumbnailRounded.createGraphics();
            gThumb.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING, java.awt.RenderingHints.VALUE_ANTIALIAS_ON);
            
            gThumb.setColor(java.awt.Color.WHITE);
            gThumb.fillRoundRect(0, 0, cropSize, cropSize, cornerRadius, cornerRadius);
            
            gThumb.setComposite(java.awt.AlphaComposite.SrcIn);
            gThumb.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION, java.awt.RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            gThumb.drawImage(cropped, 0, 0, null);
            gThumb.dispose();
            
            // 3. Erase the exact thumbnail footprint from the shadow to prevent dark-edge bleeding
            java.awt.Graphics2D g2d = processed.createGraphics();
            g2d.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING, java.awt.RenderingHints.VALUE_ANTIALIAS_ON);
            g2d.setComposite(java.awt.AlphaComposite.DstOut);
            g2d.setColor(java.awt.Color.BLACK);
            g2d.fillRoundRect(shadowPadding, shadowPadding, cropSize, cropSize, cornerRadius, cornerRadius);
            
            // 4. Draw the masked thumbnail over the cleared area
            g2d.setComposite(java.awt.AlphaComposite.SrcOver);
            g2d.drawImage(thumbnailRounded, shadowPadding, shadowPadding, null);
            g2d.dispose();

            // Re-encode to transparent PNG payload for Minecraft NativeImage
            ByteArrayOutputStream pngOut = new ByteArrayOutputStream();
            ImageIO.write(processed, "png", pngOut);
            byte[] processedBytes = pngOut.toByteArray();

            // OpenGL Texture Registration (Must trigger on Main Minecraft engine Thread)
            MinecraftClient.getInstance().execute(() -> {
                try {
                    NativeImage nativeImage = NativeImage.read(new ByteArrayInputStream(processedBytes));
                    NativeImageBackedTexture texture = new NativeImageBackedTexture(nativeImage);
                    String safeId = videoId.toLowerCase().replaceAll("[^a-z0-9/._-]", "_");
                    Identifier id = new Identifier("audiophilecraft", "yt_thumb_" + safeId);
                    MinecraftClient.getInstance().getTextureManager().registerTexture(id, texture);
                    cache.put(videoId, id);
                } catch (Exception e) {
                    e.printStackTrace();
                }
            });

            // Return original uncropped image for background color extraction to preserve existing logic
            return original;

        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }
}
