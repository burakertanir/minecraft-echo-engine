package com.audiophilecraft.client.screen;

import com.audiophilecraft.sound.AdvancedAcousticScanner;
import com.audiophilecraft.sound.AudioEngine;
import java.util.List;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

/** Renders the acoustic heatmap and its local-only zone selector. */
final class AmplifierAcousticMapPanel {
    private static final int ZONES_PER_PAGE = 4;
    private static final int CONTROL_HEIGHT = 15;
    private static final int AUTO_WIDTH = 34;
    private static final int ALL_WIDTH = 30;
    private static final int ZONE_WIDTH = 22;
    private static final int ARROW_WIDTH = 16;
    private static final int CONTROL_GAP = 3;
    private static final int SELECTION_AUTO = -1;
    private static final int SELECTION_ALL = -2;

    private final TextRenderer textRenderer;
    private int zonePageStart;
    private int previousZoneCount = -1;
    private int previousSelectedZoneIndex = Integer.MIN_VALUE;

    AmplifierAcousticMapPanel(TextRenderer textRenderer) {
        this.textRenderer = textRenderer;
    }

    void render(
            DrawContext context,
            int screenX,
            int screenY,
            int backgroundWidth,
            int backgroundHeight,
            int mouseX,
            int mouseY) {
        List<Vec3d> pointCloud = AdvancedAcousticScanner.getLastPointCloud();
        if (pointCloud == null || pointCloud.isEmpty()) {
            context.drawText(
                    textRenderer,
                    "NO SCAN DATA. PLAY A TRACK FIRST.",
                    screenX + 20,
                    screenY + backgroundHeight / 2,
                    0xFFFF5555,
                    false);
            return;
        }

        context.fill(
                screenX + 10,
                screenY + 30,
                screenX + backgroundWidth - 10,
                screenY + backgroundHeight - 30,
                0xCC000000);
        List<BlockPos> speakers = AdvancedAcousticScanner.getLastSpeakers();
        PointCloudRenderer.render(context, screenX, screenY, backgroundWidth, backgroundHeight, pointCloud, speakers);
        context.drawText(textRenderer, "REVERB HEATMAP", screenX + 15, screenY + 35, 0xFF00FF88, false);
        context.drawText(textRenderer, "RAYS: " + pointCloud.size(), screenX + 15, screenY + 45, 0xFFFFFFFF, false);

        renderZoneSelector(context, screenX, screenY, backgroundWidth, mouseX, mouseY);
        renderVenueDetails(context, screenX, screenY, backgroundWidth, backgroundHeight);
    }

    boolean mouseClicked(double mouseX, double mouseY, int screenX, int screenY, int backgroundWidth) {
        if (AdvancedAcousticScanner.getLastPointCloud().isEmpty()) return false;
        AudioEngine engine = AudioEngine.getInstance();
        int zoneCount = engine.getAcousticDebugZoneCount();
        normalizePage(zoneCount, engine.getSelectedAcousticDebugZoneIndex());
        ZoneSelectorLayout layout = createLayout(screenX, screenY, backgroundWidth, zoneCount);
        int cursorX = layout.startX() + textRenderer.getWidth(layout.label()) + CONTROL_GAP;

        if (contains(mouseX, mouseY, cursorX, layout.y(), AUTO_WIDTH, CONTROL_HEIGHT)) {
            engine.selectAcousticDebugZone(SELECTION_AUTO);
            return true;
        }
        cursorX += AUTO_WIDTH + CONTROL_GAP;

        if (contains(mouseX, mouseY, cursorX, layout.y(), ALL_WIDTH, CONTROL_HEIGHT)) {
            engine.selectAcousticDebugZone(SELECTION_ALL);
            return true;
        }
        cursorX += ALL_WIDTH;

        if (layout.paging()) {
            cursorX += CONTROL_GAP;
            if (contains(mouseX, mouseY, cursorX, layout.y(), ARROW_WIDTH, CONTROL_HEIGHT)) {
                if (zonePageStart > 0) zonePageStart = Math.max(0, zonePageStart - ZONES_PER_PAGE);
                return true;
            }
            cursorX += ARROW_WIDTH;
        }

        if (layout.visibleZoneCount() > 0) cursorX += CONTROL_GAP;
        for (int offset = 0; offset < layout.visibleZoneCount(); offset++) {
            if (contains(mouseX, mouseY, cursorX, layout.y(), ZONE_WIDTH, CONTROL_HEIGHT)) {
                engine.selectAcousticDebugZone(zonePageStart + offset);
                return true;
            }
            cursorX += ZONE_WIDTH;
        }

        if (layout.paging()) {
            cursorX += CONTROL_GAP;
            if (contains(mouseX, mouseY, cursorX, layout.y(), ARROW_WIDTH, CONTROL_HEIGHT)) {
                int maxPageStart = maximumPageStart(zoneCount);
                if (zonePageStart < maxPageStart) {
                    zonePageStart = Math.min(maxPageStart, zonePageStart + ZONES_PER_PAGE);
                }
                return true;
            }
        }
        return false;
    }

    private void renderZoneSelector(
            DrawContext context, int screenX, int screenY, int backgroundWidth, int mouseX, int mouseY) {
        AudioEngine engine = AudioEngine.getInstance();
        int zoneCount = engine.getAcousticDebugZoneCount();
        int selectedZoneIndex = engine.getSelectedAcousticDebugZoneIndex();
        normalizePage(zoneCount, selectedZoneIndex);
        ZoneSelectorLayout layout = createLayout(screenX, screenY, backgroundWidth, zoneCount);

        context.drawText(textRenderer, layout.label(), layout.startX(), layout.y() + 3, 0xFF00FF88, false);
        int cursorX = layout.startX() + textRenderer.getWidth(layout.label()) + CONTROL_GAP;
        drawSegment(
                context,
                cursorX,
                layout.y(),
                AUTO_WIDTH,
                "AUTO",
                selectedZoneIndex == SELECTION_AUTO,
                true,
                contains(mouseX, mouseY, cursorX, layout.y(), AUTO_WIDTH, CONTROL_HEIGHT));
        cursorX += AUTO_WIDTH + CONTROL_GAP;

        drawSegment(
                context,
                cursorX,
                layout.y(),
                ALL_WIDTH,
                "ALL",
                selectedZoneIndex == SELECTION_ALL,
                true,
                contains(mouseX, mouseY, cursorX, layout.y(), ALL_WIDTH, CONTROL_HEIGHT));
        cursorX += ALL_WIDTH;

        if (layout.paging()) {
            cursorX += CONTROL_GAP;
            drawSegment(
                    context,
                    cursorX,
                    layout.y(),
                    ARROW_WIDTH,
                    "<",
                    false,
                    zonePageStart > 0,
                    contains(mouseX, mouseY, cursorX, layout.y(), ARROW_WIDTH, CONTROL_HEIGHT));
            cursorX += ARROW_WIDTH;
        }

        if (layout.visibleZoneCount() > 0) cursorX += CONTROL_GAP;
        for (int offset = 0; offset < layout.visibleZoneCount(); offset++) {
            int zoneIndex = zonePageStart + offset;
            drawSegment(
                    context,
                    cursorX,
                    layout.y(),
                    ZONE_WIDTH,
                    Integer.toString(zoneIndex + 1),
                    selectedZoneIndex == zoneIndex,
                    true,
                    contains(mouseX, mouseY, cursorX, layout.y(), ZONE_WIDTH, CONTROL_HEIGHT));
            cursorX += ZONE_WIDTH;
        }

        if (layout.paging()) {
            cursorX += CONTROL_GAP;
            drawSegment(
                    context,
                    cursorX,
                    layout.y(),
                    ARROW_WIDTH,
                    ">",
                    false,
                    zonePageStart < maximumPageStart(zoneCount),
                    contains(mouseX, mouseY, cursorX, layout.y(), ARROW_WIDTH, CONTROL_HEIGHT));
        }
    }

    private void renderVenueDetails(
            DrawContext context, int screenX, int screenY, int backgroundWidth, int backgroundHeight) {
        AdvancedAcousticScanner.VenuePreset preset = AdvancedAcousticScanner.getLastDebugPreset();
        if (preset == null) preset = AudioEngine.getInstance().getVenuePreset();
        if (preset != null && preset.tierName != null) {
            String tier = preset.tierName;
            context.drawText(
                    textRenderer,
                    tier,
                    screenX + backgroundWidth - 15 - textRenderer.getWidth(tier),
                    screenY + 35,
                    0xFFFFDD00,
                    false);
        }

        AdvancedAcousticScanner.VenueDescriptor descriptor = AdvancedAcousticScanner.getLastDebugDescriptor();
        if (descriptor == null) descriptor = AudioEngine.getInstance().getStoredVenueDescriptor();
        if (descriptor != null) {
            String volume = "VOLUME: " + (int) descriptor.trueVolume + " m3";
            context.drawText(
                    textRenderer,
                    volume,
                    screenX + backgroundWidth - 15 - textRenderer.getWidth(volume),
                    screenY + backgroundHeight - 45,
                    0xFF00FFFF,
                    false);
        }
    }

    private void drawSegment(
            DrawContext context,
            int x,
            int y,
            int width,
            String label,
            boolean selected,
            boolean enabled,
            boolean hovered) {
        int backgroundColor;
        int foregroundColor;
        if (!enabled) {
            backgroundColor = 0x66000000;
            foregroundColor = 0xFF666666;
        } else if (selected) {
            backgroundColor = 0xDD007A4A;
            foregroundColor = 0xFFFFFFFF;
        } else if (hovered) {
            backgroundColor = 0xCC23483C;
            foregroundColor = 0xFFFFFFFF;
        } else {
            backgroundColor = 0xAA111111;
            foregroundColor = 0xFFB8C7C2;
        }

        context.fill(x, y, x + width, y + CONTROL_HEIGHT, backgroundColor);
        if (selected) {
            context.fill(x, y + CONTROL_HEIGHT - 1, x + width, y + CONTROL_HEIGHT, 0xFF00FF88);
        }
        int textX = x + (width - textRenderer.getWidth(label)) / 2;
        context.drawText(textRenderer, label, textX, y + 3, foregroundColor, false);
    }

    private ZoneSelectorLayout createLayout(int screenX, int screenY, int backgroundWidth, int zoneCount) {
        int visibleZoneCount = Math.min(ZONES_PER_PAGE, Math.max(0, zoneCount - zonePageStart));
        boolean paging = zoneCount > ZONES_PER_PAGE;
        String label = "ACOUSTIC ZONE";
        int totalWidth = selectorWidth(label, visibleZoneCount, paging);
        if (totalWidth > backgroundWidth - 30) {
            label = "ZONE";
            totalWidth = selectorWidth(label, visibleZoneCount, paging);
        }
        return new ZoneSelectorLayout(
                screenX + (backgroundWidth - totalWidth) / 2, screenY + 56, label, visibleZoneCount, paging);
    }

    private int selectorWidth(String label, int visibleZoneCount, boolean paging) {
        int width = textRenderer.getWidth(label) + CONTROL_GAP + AUTO_WIDTH + CONTROL_GAP + ALL_WIDTH;
        if (paging) width += CONTROL_GAP + ARROW_WIDTH + CONTROL_GAP + ARROW_WIDTH;
        if (visibleZoneCount > 0) width += CONTROL_GAP + visibleZoneCount * ZONE_WIDTH;
        return width;
    }

    private void normalizePage(int zoneCount, int selectedZoneIndex) {
        boolean zoneListChanged = zoneCount != previousZoneCount;
        boolean selectionChanged = selectedZoneIndex != previousSelectedZoneIndex;
        zonePageStart = Math.min(zonePageStart, maximumPageStart(zoneCount));
        if ((zoneListChanged || selectionChanged)
                && selectedZoneIndex >= 0
                && (selectedZoneIndex < zonePageStart || selectedZoneIndex >= zonePageStart + ZONES_PER_PAGE)) {
            zonePageStart = (selectedZoneIndex / ZONES_PER_PAGE) * ZONES_PER_PAGE;
        }
        previousZoneCount = zoneCount;
        previousSelectedZoneIndex = selectedZoneIndex;
    }

    private static int maximumPageStart(int zoneCount) {
        return zoneCount > 0 ? ((zoneCount - 1) / ZONES_PER_PAGE) * ZONES_PER_PAGE : 0;
    }

    private static boolean contains(double mouseX, double mouseY, int x, int y, int width, int height) {
        return mouseX >= x && mouseX < x + width && mouseY >= y && mouseY < y + height;
    }

    private record ZoneSelectorLayout(int startX, int y, String label, int visibleZoneCount, boolean paging) {}
}
