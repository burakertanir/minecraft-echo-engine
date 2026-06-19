package com.audiophilecraft.client.screen;

import com.audiophilecraft.block.LineArrayBlock;
import com.audiophilecraft.block.MidRangeBlock;
import com.audiophilecraft.network.ModMessages;
import com.audiophilecraft.screen.SpeakerScreenHandler;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.SliderWidget;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;
import org.joml.Matrix4f;

public class SpeakerScreen extends HandledScreen<SpeakerScreenHandler> {
    private ShiftSlider shiftSlider;
    private TiltSlider tiltSlider;
    private boolean allowsTilt;

    private float timeOffset = 0f;
    private static final int[] currentColors = new int[] {0xFF333333, 0xFF555555, 0xFF444444, 0xFF333333};
    private static final int currentAdaptiveThemeColor = 0xFFFFFFFF;

    private static final String[] CHANNEL_LABELS = {"BOTH", "L", "R"};

    public SpeakerScreen(SpeakerScreenHandler handler, PlayerInventory inventory, Text title) {
        super(handler, inventory, title);
    }

    @Override
    protected void init() {
        this.backgroundWidth = 300;
        this.backgroundHeight = 140;
        super.init();

        int x = (width - backgroundWidth) / 2;
        int y = (height - backgroundHeight) / 2;

        BlockPos pos = handler.getPos();
        if (pos != null && MinecraftClient.getInstance().world != null) {
            net.minecraft.block.Block block =
                    MinecraftClient.getInstance().world.getBlockState(pos).getBlock();
            allowsTilt = block instanceof LineArrayBlock || block instanceof MidRangeBlock;
        }

        int sliderWidth = 220;
        int startX = x + (backgroundWidth - sliderWidth) / 2;
        int startY = y + 45;

        double initialShift = handler.getSampleShift() / 30.0;
        shiftSlider = new ShiftSlider(
                startX,
                startY,
                sliderWidth,
                20,
                Text.literal("Sample Shift: " + handler.getSampleShift() + " ms"),
                initialShift);
        addDrawableChild(shiftSlider);

        if (allowsTilt) {
            double initialTilt = (handler.getVerticalTiltDeg() + 70.0) / 140.0;
            tiltSlider = new TiltSlider(
                    startX,
                    startY + 35,
                    sliderWidth,
                    20,
                    Text.literal("Vertical Tilt: " + handler.getVerticalTiltDeg() + "°"),
                    initialTilt);
            addDrawableChild(tiltSlider);
        }

        // Channel toggle buttons (Mid + Line only)
        boolean allowsChannel = pos != null
                && MinecraftClient.getInstance().world != null
                && (MinecraftClient.getInstance().world.getBlockState(pos).getBlock() instanceof MidRangeBlock
                        || MinecraftClient.getInstance()
                                        .world
                                        .getBlockState(pos)
                                        .getBlock()
                                instanceof LineArrayBlock);
        if (allowsChannel) {
            int btnY = startY + (allowsTilt ? 70 : 35);
            int btnWidth = 40;
            int totalBtnWidth = btnWidth * 3 + 8;
            int btnX = x + (backgroundWidth - totalBtnWidth) / 2;

            int currentMask = handler.getChannelMask();
            for (int i = 0; i < 3; i++) {
                final int mask = i;
                boolean isSelected = (currentMask == mask);
                Text label = isSelected ? Text.literal("[" + CHANNEL_LABELS[i] + "]") : Text.literal(CHANNEL_LABELS[i]);
                ButtonWidget btn = ButtonWidget.builder(label, b -> {
                            handler.setChannelMask(mask);
                            updateClientBlockEntity(mask);
                            sendChannelMaskUpdate(mask);
                            applyChannelMaskToRunningSources(mask);
                            clearChildren();
                            init();
                        })
                        .dimensions(btnX + i * (btnWidth + 4), btnY, btnWidth, 20)
                        .build();
                addDrawableChild(btn);
            }
        }
    }

    @Override
    protected void handledScreenTick() {
        super.handledScreenTick();
        timeOffset += 0.05f;
    }

    private void sendShiftUpdate(int shift) {
        if (handler.getPos() != null) {
            PacketByteBuf buf = PacketByteBufs.create();
            buf.writeBlockPos(handler.getPos());
            buf.writeInt(shift);
            ClientPlayNetworking.send(ModMessages.C2S_UPDATE_SPEAKER_SHIFT, buf);
        }
    }

    private void sendChannelMaskUpdate(int mask) {
        if (handler.getPos() != null) {
            PacketByteBuf buf = PacketByteBufs.create();
            buf.writeBlockPos(handler.getPos());
            buf.writeInt(mask);
            ClientPlayNetworking.send(ModMessages.C2S_CHANNEL_MASK, buf);
        }
    }

    private void applyChannelMaskToRunningSources(int mask) {
        BlockPos speakerPos = handler.getPos();
        if (speakerPos == null) return;
        com.audiophilecraft.sound.AudioEngine engine = com.audiophilecraft.sound.AudioEngine.getInstance();
        engine.applyChannelMaskToSpeaker(speakerPos, mask);
    }

    private void updateClientBlockEntity(int mask) {
        BlockPos pos = handler.getPos();
        if (pos == null || MinecraftClient.getInstance().world == null) return;
        net.minecraft.block.entity.BlockEntity be =
                MinecraftClient.getInstance().world.getBlockEntity(pos);
        if (be instanceof com.audiophilecraft.block.entity.SpeakerBlockEntity speaker) {
            speaker.setChannelMask(mask);
        }
    }

    private void sendTiltUpdate(int tilt) {
        if (handler.getPos() != null) {
            PacketByteBuf buf = PacketByteBufs.create();
            buf.writeBlockPos(handler.getPos());
            buf.writeInt(tilt);
            ClientPlayNetworking.send(ModMessages.C2S_UPDATE_TILT, buf);
        }
    }

    private int calculateBackgroundColor(
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
        float pxd1 = px - cx1;
        float pyd1 = py - cy1;
        float d1 = pxd1 * pxd1 + pyd1 * pyd1;
        float pxd2 = px - cx2;
        float pyd2 = py - cy2;
        float d2 = pxd2 * pxd2 + pyd2 * pyd2;
        float pxd3 = px - cx3;
        float pyd3 = py - cy3;
        float d3 = pxd3 * pxd3 + pyd3 * pyd3;
        float pxd4 = px - cx4;
        float pyd4 = py - cy4;
        float d4 = pxd4 * pxd4 + pyd4 * pyd4;
        float w1 = 1.0f / (1.0f + d1 * 8.0f);
        float w2 = 1.0f / (1.0f + d2 * 8.0f);
        float w3 = 1.0f / (1.0f + d3 * 8.0f);
        float w4 = 1.0f / (1.0f + d4 * 8.0f);
        float invSum = 1.0f / (w1 + w2 + w3 + w4);
        w1 *= invSum;
        w2 *= invSum;
        w3 *= invSum;
        w4 *= invSum;
        float r = ((currentColors[0] >> 16) & 0xFF) * w1
                + ((currentColors[1] >> 16) & 0xFF) * w2
                + ((currentColors[2] >> 16) & 0xFF) * w3
                + ((currentColors[3] >> 16) & 0xFF) * w4;
        float g = ((currentColors[0] >> 8) & 0xFF) * w1
                + ((currentColors[1] >> 8) & 0xFF) * w2
                + ((currentColors[2] >> 8) & 0xFF) * w3
                + ((currentColors[3] >> 8) & 0xFF) * w4;
        float b = (currentColors[0] & 0xFF) * w1
                + (currentColors[1] & 0xFF) * w2
                + (currentColors[2] & 0xFF) * w3
                + (currentColors[3] & 0xFF) * w4;
        return 0xFF000000 | ((int) r << 16) | ((int) g << 8) | (int) b;
    }

    @Override
    protected void drawBackground(DrawContext context, float delta, int mouseX, int mouseY) {
        int startX = (width - backgroundWidth) / 2;
        int startY = (height - backgroundHeight) / 2;

        float cx1 = 0.5f + 0.6f * (float) Math.sin(timeOffset * 0.3f);
        float cy1 = 0.5f + 0.6f * (float) Math.cos(timeOffset * 0.2f);
        float cx2 = 0.5f + 0.6f * (float) Math.sin(timeOffset * 0.4f + 2.0f);
        float cy2 = 0.5f + 0.6f * (float) Math.cos(timeOffset * 0.5f + 1.0f);
        float cx3 = 0.5f + 0.6f * (float) Math.sin(timeOffset * 0.2f + 4.0f);
        float cy3 = 0.5f + 0.6f * (float) Math.cos(timeOffset * 0.4f + 5.0f);
        float cx4 = 0.5f + 0.6f * (float) Math.sin(timeOffset * 0.5f + 1.5f);
        float cy4 = 0.5f + 0.6f * (float) Math.cos(timeOffset * 0.3f + 3.0f);

        com.mojang.blaze3d.systems.RenderSystem.enableBlend();
        com.mojang.blaze3d.systems.RenderSystem.defaultBlendFunc();
        com.mojang.blaze3d.systems.RenderSystem.setShader(
                net.minecraft.client.render.GameRenderer::getPositionColorProgram);
        net.minecraft.client.render.Tessellator tessellator = net.minecraft.client.render.Tessellator.getInstance();
        net.minecraft.client.render.BufferBuilder bufferBuilder = tessellator.getBuffer();
        Matrix4f matrix = context.getMatrices().peek().getPositionMatrix();

        bufferBuilder.begin(
                net.minecraft.client.render.VertexFormat.DrawMode.QUADS,
                net.minecraft.client.render.VertexFormats.POSITION_COLOR);

        int pixelSize = 8;
        int gridW = backgroundWidth / pixelSize;
        int gridH = backgroundHeight / pixelSize;
        for (int gy = 0; gy <= gridH; gy++) {
            float py = (float) gy / gridH;
            for (int gx = 0; gx <= gridW; gx++) {
                float px = (float) gx / gridW;
                int color = calculateBackgroundColor(px, py, cx1, cy1, cx2, cy2, cx3, cy3, cx4, cy4);
                int drawX = startX + gx * pixelSize;
                int drawY = startY + gy * pixelSize;
                int dw = pixelSize;
                int dh = pixelSize;
                if (drawX + dw > startX + backgroundWidth) dw = startX + backgroundWidth - drawX;
                if (drawY + dh > startY + backgroundHeight) dh = startY + backgroundHeight - drawY;
                bufferBuilder.vertex(matrix, drawX, drawY, 0).color(color).next();
                bufferBuilder.vertex(matrix, drawX, drawY + dh, 0).color(color).next();
                bufferBuilder
                        .vertex(matrix, drawX + dw, drawY + dh, 0)
                        .color(color)
                        .next();
                bufferBuilder.vertex(matrix, drawX + dw, drawY, 0).color(color).next();
            }
        }
        tessellator.draw();
        com.mojang.blaze3d.systems.RenderSystem.disableBlend();

        context.fill(startX - 1, startY - 1, startX + backgroundWidth + 1, startY, currentAdaptiveThemeColor);
        context.fill(
                startX - 1,
                startY + backgroundHeight,
                startX + backgroundWidth + 1,
                startY + backgroundHeight + 1,
                currentAdaptiveThemeColor);
        context.fill(startX - 1, startY, startX, startY + backgroundHeight, currentAdaptiveThemeColor);
        context.fill(
                startX + backgroundWidth,
                startY,
                startX + backgroundWidth + 1,
                startY + backgroundHeight,
                currentAdaptiveThemeColor);
    }

    @Override
    protected void drawForeground(DrawContext context, int mouseX, int mouseY) {
        int titleWidth = textRenderer.getWidth(title);
        context.drawText(textRenderer, title, (backgroundWidth - titleWidth) / 2, 15, currentAdaptiveThemeColor, false);
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        renderBackground(context);
        super.render(context, mouseX, mouseY, delta);
        drawMouseoverTooltip(context, mouseX, mouseY);
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double deltaX, double deltaY) {
        if (shiftSlider != null && this.getFocused() == shiftSlider) {
            shiftSlider.mouseDragged(mouseX, mouseY, button, deltaX, deltaY);
            return true;
        }
        if (tiltSlider != null && this.getFocused() == tiltSlider) {
            tiltSlider.mouseDragged(mouseX, mouseY, button, deltaX, deltaY);
            return true;
        }
        return super.mouseDragged(mouseX, mouseY, button, deltaX, deltaY);
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        if (shiftSlider != null && this.getFocused() == shiftSlider) {
            shiftSlider.onRelease(mouseX, mouseY);
            this.setFocused(null);
            return true;
        }
        if (tiltSlider != null && this.getFocused() == tiltSlider) {
            tiltSlider.onRelease(mouseX, mouseY);
            this.setFocused(null);
            return true;
        }
        return super.mouseReleased(mouseX, mouseY, button);
    }

    private class ShiftSlider extends SliderWidget {
        public ShiftSlider(int x, int y, int width, int height, Text text, double value) {
            super(x, y, width, height, text, value);
            updateMessage();
        }

        @Override
        protected void updateMessage() {
            int val = (int) Math.round(this.value * 30.0);
            this.setMessage(Text.literal("Sample Shift: " + val + " ms"));
        }

        @Override
        protected void applyValue() {
            int val = (int) Math.round(this.value * 30.0);
            handler.setSampleShift(val);
        }

        @Override
        public void onRelease(double mouseX, double mouseY) {
            super.onRelease(mouseX, mouseY);
            int val = (int) Math.round(this.value * 30.0);
            sendShiftUpdate(val);
            this.setFocused(false);
        }

        @Override
        public boolean mouseDragged(double mouseX, double mouseY, int button, double deltaX, double deltaY) {
            double val = (mouseX - (double) (this.getX() + 4)) / (double) (this.width - 8);
            this.value = Math.max(0.0, Math.min(val, 1.0));
            this.applyValue();
            updateMessage();
            return true;
        }

        @Override
        public void renderButton(DrawContext context, int mouseX, int mouseY, float delta) {
            context.fill(getX(), getY(), getX() + width, getY() + 1, currentAdaptiveThemeColor);
            context.fill(getX(), getY() + height - 1, getX() + width, getY() + height, currentAdaptiveThemeColor);
            context.fill(getX(), getY(), getX() + 1, getY() + height, currentAdaptiveThemeColor);
            context.fill(getX() + width - 1, getY(), getX() + width, getY() + height, currentAdaptiveThemeColor);
            int knobWidth = 8;
            int knobX = getX() + (int) (this.value * (double) (width - knobWidth));
            context.fill(knobX, getY(), knobX + knobWidth, getY() + height, currentAdaptiveThemeColor);
            if (isHovered()) {
                context.fill(knobX, getY(), knobX + knobWidth, getY() + height, 0x66FFFFFF);
            }
            int textWidth = textRenderer.getWidth(getMessage());
            context.drawText(
                    textRenderer,
                    getMessage(),
                    getX() + (width - textWidth) / 2,
                    getY() + (height - 8) / 2,
                    currentAdaptiveThemeColor,
                    false);
        }
    }

    private class TiltSlider extends SliderWidget {
        public TiltSlider(int x, int y, int width, int height, Text text, double value) {
            super(x, y, width, height, text, value);
            updateMessage();
        }

        @Override
        protected void updateMessage() {
            int deg = (int) Math.round(this.value * 140.0 - 70.0);
            this.setMessage(Text.literal(
                    "Vertical Tilt: " + deg + "° " + (deg < 0 ? "(Aşağı)" : deg > 0 ? "(Yukarı)" : "(Düz)")));
        }

        @Override
        protected void applyValue() {
            int deg = (int) Math.round(this.value * 140.0 - 70.0);
            handler.setVerticalTiltDeg(deg);
            if (handler.getPos() != null) {
                com.audiophilecraft.sound.AudioEngine.getInstance().updateSpeakerTilt(handler.getPos(), deg);
            }
        }

        @Override
        public void onRelease(double mouseX, double mouseY) {
            super.onRelease(mouseX, mouseY);
            int deg = (int) Math.round(this.value * 140.0 - 70.0);
            sendTiltUpdate(deg);
            this.setFocused(false);
        }

        @Override
        public boolean mouseDragged(double mouseX, double mouseY, int button, double deltaX, double deltaY) {
            double val = (mouseX - (double) (this.getX() + 4)) / (double) (this.width - 8);
            this.value = Math.max(0.0, Math.min(val, 1.0));
            this.applyValue();
            updateMessage();
            return true;
        }

        @Override
        public void renderButton(DrawContext context, int mouseX, int mouseY, float delta) {
            context.fill(getX(), getY(), getX() + width, getY() + 1, currentAdaptiveThemeColor);
            context.fill(getX(), getY() + height - 1, getX() + width, getY() + height, currentAdaptiveThemeColor);
            context.fill(getX(), getY(), getX() + 1, getY() + height, currentAdaptiveThemeColor);
            context.fill(getX() + width - 1, getY(), getX() + width, getY() + height, currentAdaptiveThemeColor);
            int knobWidth = 8;
            int knobX = getX() + (int) (this.value * (double) (width - knobWidth));
            context.fill(knobX, getY(), knobX + knobWidth, getY() + height, currentAdaptiveThemeColor);
            if (isHovered()) {
                context.fill(knobX, getY(), knobX + knobWidth, getY() + height, 0x66FFFFFF);
            }
            int textWidth = textRenderer.getWidth(getMessage());
            context.drawText(
                    textRenderer,
                    getMessage(),
                    getX() + (width - textWidth) / 2,
                    getY() + (height - 8) / 2,
                    currentAdaptiveThemeColor,
                    false);
        }
    }
}
