package com.audiophilecraft.client.screen;

import com.audiophilecraft.block.LineArrayBlock;
import com.audiophilecraft.block.MidRangeBlock;
import com.audiophilecraft.network.ModMessages;
import com.audiophilecraft.screen.SpeakerScreenHandler;
import com.mojang.blaze3d.systems.RenderSystem;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.client.gui.widget.SliderWidget;
import net.minecraft.client.render.GameRenderer;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;

public class SpeakerScreen extends HandledScreen<SpeakerScreenHandler> {
    private static final Identifier TEXTURE = new Identifier("audiophilecraft", "textures/gui/amplifier_gui.png");
    private ShiftSlider shiftSlider;
    private TiltSlider tiltSlider;
    private boolean allowsTilt;

    public SpeakerScreen(SpeakerScreenHandler handler, PlayerInventory inventory, Text title) {
        super(handler, inventory, title);
    }

    @Override
    protected void init() {
        super.init();
        int x = (width - backgroundWidth) / 2;
        int y = (height - backgroundHeight) / 2;

        // Check if this is a Line Array or Mid Range block (allows tilt)
        BlockPos pos = handler.getPos();
        if (pos != null && MinecraftClient.getInstance().world != null) {
            net.minecraft.block.Block block =
                    MinecraftClient.getInstance().world.getBlockState(pos).getBlock();
            allowsTilt = block instanceof LineArrayBlock || block instanceof MidRangeBlock;
        }

        // Sample Shift Slider (0 - 30 ms)
        double initialShift = handler.getSampleShift() / 30.0;
        shiftSlider = new ShiftSlider(
                x + 10,
                y + 30,
                156,
                20,
                Text.literal("Sample Shift: " + handler.getSampleShift() + " ms"),
                initialShift);
        addDrawableChild(shiftSlider);

        // Vertical Tilt Slider — for Line Array and Mid Range speakers
        if (allowsTilt) {
            // Map -70..+70 to 0..1
            double initialTilt = (handler.getVerticalTiltDeg() + 70.0) / 140.0;
            tiltSlider = new TiltSlider(
                    x + 10,
                    y + 58,
                    156,
                    20,
                    Text.literal("Vertical Tilt: " + handler.getVerticalTiltDeg() + "°"),
                    initialTilt);
            addDrawableChild(tiltSlider);
        }
    }

    private void sendShiftUpdate(int shift) {
        if (handler.getPos() != null) {
            PacketByteBuf buf = PacketByteBufs.create();
            buf.writeBlockPos(handler.getPos());
            buf.writeInt(shift);
            ClientPlayNetworking.send(ModMessages.C2S_UPDATE_SPEAKER_SHIFT, buf);
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

    @Override
    protected void drawBackground(DrawContext context, float delta, int mouseX, int mouseY) {
        RenderSystem.setShader(GameRenderer::getPositionTexProgram);
        RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);
        RenderSystem.setShaderTexture(0, TEXTURE);
        int x = (width - backgroundWidth) / 2;
        int y = (height - backgroundHeight) / 2;
        context.drawTexture(TEXTURE, x, y, 0, 0, backgroundWidth, backgroundHeight);
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        renderBackground(context);
        super.render(context, mouseX, mouseY, delta);
        drawMouseoverTooltip(context, mouseX, mouseY);
    }

    // ---- Sliders ----

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
            sendShiftUpdate(val);
        }
    }

    private class TiltSlider extends SliderWidget {
        public TiltSlider(int x, int y, int width, int height, Text text, double value) {
            super(x, y, width, height, text, value);
            updateMessage();
        }

        @Override
        protected void updateMessage() {
            // Map 0..1 to -70..+70
            int deg = (int) Math.round(this.value * 140.0 - 70.0);
            this.setMessage(Text.literal(
                    "Vertical Tilt: " + deg + "° " + (deg < 0 ? "(Aşağı)" : deg > 0 ? "(Yukarı)" : "(Düz)")));
        }

        @Override
        protected void applyValue() {
            int deg = (int) Math.round(this.value * 140.0 - 70.0);
            handler.setVerticalTiltDeg(deg);
            sendTiltUpdate(deg);
            // Live update: Push tilt change directly to AudioEngine for active streams
            if (handler.getPos() != null) {
                com.audiophilecraft.sound.AudioEngine.getInstance().updateSpeakerTilt(handler.getPos(), deg);
            }
        }
    }
}
