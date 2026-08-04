package com.audiophilecraft.client.screen;

import com.audiophilecraft.network.ModMessages;
import com.audiophilecraft.sound.AudioEngine;
import com.audiophilecraft.sound.PeakMeter;
import com.audiophilecraft.sound.StreamDSPPipeline;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.widget.ClickableWidget;
import net.minecraft.client.gui.widget.SliderWidget;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.text.Text;

/** Owns mixer controls, EQ/Q editing, meters and their pointer interactions. */
final class AmplifierMixerPanel {
    private static final String[] SPEAKER_TYPES = {"sub", "mid", "line"};
    private static final String[] COLUMN_LABELS = {"SUBWOOFER", "STUDIO MONITOR", "LINE ARRAY"};

    private final AmplifierTheme theme;
    private final TextRenderer textRenderer;
    private final List<MixerSliderWidget> mixerSliders = new ArrayList<>();
    private final List<QSliderWidget> qSliders = new ArrayList<>();

    private AmplifierTheme.ThemedButton midButton;
    private AmplifierTheme.ThemedButton sideButton;
    private MixerSliderWidget draggedMixerSlider;
    private QSliderWidget draggedQSlider;

    AmplifierMixerPanel(AmplifierTheme theme, TextRenderer textRenderer) {
        this.theme = theme;
        this.textRenderer = textRenderer;
    }

    void initialize(int screenX, int screenY, int backgroundWidth, Consumer<ClickableWidget> addWidget) {
        mixerSliders.clear();
        qSliders.clear();

        int columnWidth = (backgroundWidth - 40) / 3;
        int startX = screenX + 20;
        int startY = screenY + 40;
        for (int column = 0; column < SPEAKER_TYPES.length; column++) {
            int columnX = startX + column * columnWidth;
            for (int row = 0; row < 6; row++) {
                MixerSliderWidget slider;
                if (row > 0) {
                    slider = new MixerSliderWidget(
                            columnX + 10, startY + row * 22 + 15, columnWidth - 45, 14, SPEAKER_TYPES[column], row);
                    QSliderWidget qSlider = new QSliderWidget(
                            columnX + columnWidth - 30, startY + row * 22 + 15, 20, 14, SPEAKER_TYPES[column], row);
                    qSliders.add(qSlider);
                    addWidget.accept(qSlider);
                } else {
                    slider = new MixerSliderWidget(
                            columnX + 10, startY + row * 22 + 15, columnWidth - 20, 14, SPEAKER_TYPES[column], row);
                }
                mixerSliders.add(slider);
                addWidget.accept(slider);
            }
        }

        int midSideY = startY + 6 * 22 + 35;
        midButton = theme.button(
                textRenderer, screenX + backgroundWidth / 2 - 60, midSideY, 50, 20, midButtonText(), button -> {
                    AudioEngine engine = AudioEngine.getInstance();
                    engine.setMidMuted(!engine.isMidMuted());
                    button.setMessage(midButtonText());
                });
        sideButton = theme.button(
                textRenderer, screenX + backgroundWidth / 2 + 10, midSideY, 50, 20, sideButtonText(), button -> {
                    AudioEngine engine = AudioEngine.getInstance();
                    engine.setSideMuted(!engine.isSideMuted());
                    button.setMessage(sideButtonText());
                });
        addWidget.accept(midButton);
        addWidget.accept(sideButton);
        setVisible(false);
    }

    void setVisible(boolean visible) {
        for (MixerSliderWidget slider : mixerSliders) {
            slider.visible = visible;
        }
        for (QSliderWidget slider : qSliders) {
            slider.visible = visible;
        }
        if (midButton != null) midButton.visible = visible;
        if (sideButton != null) sideButton.visible = visible;
    }

    void render(DrawContext context, int screenX, int screenY, int backgroundWidth) {
        int startX = screenX + 20;
        int startY = screenY + 45;
        int columnWidth = (backgroundWidth - 40) / 3;

        context.getMatrices().push();
        context.getMatrices().scale(1.2f, 1.2f, 1.0f);
        for (int column = 0; column < COLUMN_LABELS.length; column++) {
            int columnX = startX + column * columnWidth;
            int textWidth = textRenderer.getWidth(COLUMN_LABELS[column]);
            int drawX = (int) ((columnX + (columnWidth - textWidth * 1.2f) / 2) / 1.2f);
            int drawY = (int) ((startY - 15) / 1.2f);
            context.drawText(textRenderer, COLUMN_LABELS[column], drawX, drawY, theme.color(), false);
        }
        context.getMatrices().pop();

        renderPeakMeters(context, startX, startY, columnWidth);
        for (int column = 1; column < 3; column++) {
            int columnX = startX + column * columnWidth;
            context.fill(columnX - 2, startY - 10, columnX - 1, startY + 110, theme.color() & 0x44FFFFFF);
        }
    }

    boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button != 0) return false;
        for (MixerSliderWidget slider : mixerSliders) {
            if (slider.visible && slider.isMouseOver(mouseX, mouseY)) {
                draggedMixerSlider = slider;
                slider.onClick(mouseX, mouseY);
                return true;
            }
        }
        for (QSliderWidget slider : qSliders) {
            if (slider.visible && slider.isMouseOver(mouseX, mouseY)) {
                draggedQSlider = slider;
                slider.onClick(mouseX, mouseY);
                return true;
            }
        }
        if (midButton != null && midButton.visible && midButton.isMouseOver(mouseX, mouseY)) {
            midButton.onClick(mouseX, mouseY);
            return true;
        }
        if (sideButton != null && sideButton.visible && sideButton.isMouseOver(mouseX, mouseY)) {
            sideButton.onClick(mouseX, mouseY);
            return true;
        }
        return false;
    }

    boolean mouseDragged(double mouseX, int button) {
        if (button != 0) return false;
        if (draggedMixerSlider != null) {
            draggedMixerSlider.setSliderValue(
                    (mouseX - (draggedMixerSlider.getX() + 4.0)) / (draggedMixerSlider.getWidth() - 8.0));
            return true;
        }
        if (draggedQSlider != null) {
            draggedQSlider.setSliderValue((mouseX - (draggedQSlider.getX() + 2.0)) / (draggedQSlider.getWidth() - 4.0));
            return true;
        }
        return false;
    }

    boolean mouseReleased() {
        if (draggedMixerSlider != null) {
            draggedMixerSlider = null;
            return true;
        }
        if (draggedQSlider != null) {
            draggedQSlider = null;
            return true;
        }
        return false;
    }

    private Text midButtonText() {
        return Text.literal(AudioEngine.getInstance().isMidMuted() ? "\u00A7cMID CUT" : "MID ON");
    }

    private Text sideButtonText() {
        return Text.literal(AudioEngine.getInstance().isSideMuted() ? "\u00A7cSIDE CUT" : "SIDE ON");
    }

    private void renderPeakMeters(DrawContext context, int startX, int startY, int columnWidth) {
        PeakMeter meter = PeakMeter.getInstance();
        int meterHeight = 6;
        int meterY = startY + 143;
        for (int column = 0; column < SPEAKER_TYPES.length; column++) {
            int columnX = startX + column * columnWidth + 10;
            int meterWidth = columnWidth - 20;
            float peak = meter.getDisplayPeak(SPEAKER_TYPES[column]);
            float hold = meter.getHoldPeak(SPEAKER_TYPES[column]);
            context.fill(columnX, meterY, columnX + meterWidth, meterY + meterHeight, 0xFF1A1A1A);

            int filledWidth = (int) (peak * meterWidth);
            int greenEnd = Math.min(filledWidth, (int) (meterWidth * 0.6f));
            if (greenEnd > 0) {
                context.fill(columnX, meterY, columnX + greenEnd, meterY + meterHeight, 0xFF00CC66);
            }
            if (filledWidth > (int) (meterWidth * 0.6f)) {
                int yellowStart = (int) (meterWidth * 0.6f);
                int yellowEnd = Math.min(filledWidth, (int) (meterWidth * 0.85f));
                if (yellowEnd > yellowStart) {
                    context.fill(columnX + yellowStart, meterY, columnX + yellowEnd, meterY + meterHeight, 0xFFFFCC00);
                }
            }
            if (filledWidth > (int) (meterWidth * 0.85f)) {
                int redStart = (int) (meterWidth * 0.85f);
                context.fill(columnX + redStart, meterY, columnX + filledWidth, meterY + meterHeight, 0xFFFF3333);
            }

            int holdX = (int) (hold * meterWidth);
            if (holdX > 0 && holdX < meterWidth) {
                int holdColor = hold > 0.85f ? 0xFFFF5555 : 0xFFFFFFFF;
                context.fill(columnX + holdX, meterY, columnX + holdX + 1, meterY + meterHeight, holdColor);
            }
            context.fill(columnX, meterY, columnX + meterWidth, meterY + 1, theme.color() & 0x33FFFFFF);
            context.fill(
                    columnX,
                    meterY + meterHeight - 1,
                    columnX + meterWidth,
                    meterY + meterHeight,
                    theme.color() & 0x33FFFFFF);
        }
    }

    private static UUID currentPlayerUuid() {
        MinecraftClient client = MinecraftClient.getInstance();
        return client.player != null ? client.player.getUuid() : UUID.randomUUID();
    }

    private static double gainToSlider(float linearGain) {
        return Math.max(0.0, Math.min(1.0, linearGain));
    }

    private static float sliderToGain(double sliderValue) {
        return (float) Math.max(0.0, Math.min(1.0, sliderValue));
    }

    private final class MixerSliderWidget extends SliderWidget {
        private final String speakerType;
        private final int typeIndex;
        private long lastClickTime;

        private MixerSliderWidget(int x, int y, int width, int height, String speakerType, int typeIndex) {
            super(x, y, width, height, Text.empty(), 0.5);
            this.speakerType = speakerType;
            this.typeIndex = typeIndex;
            if (typeIndex == 0) {
                value = gainToSlider(AudioEngine.getInstance().getMixerGain(speakerType));
            } else {
                float db = AudioEngine.getInstance().getEqDb(speakerType, typeIndex - 1);
                value = (db + 9.0f) / 18.0f;
            }
            updateMessage();
        }

        @Override
        protected void updateMessage() {
            String prefix = getPrefix();
            if (typeIndex == 0) {
                setMessage(Text.literal(prefix + (int) (sliderToGain(value) * 100) + "%"));
            } else {
                float db = (float) (value * 18.0 - 9.0);
                setMessage(Text.literal(prefix + String.format("%.1f dB", db)));
            }
        }

        @Override
        protected void applyValue() {
            if (typeIndex == 0) {
                float gain = sliderToGain(value);
                AudioEngine.getInstance().setMixerGainForSession(currentPlayerUuid(), speakerType, gain);
                PacketByteBuf buffer = PacketByteBufs.create();
                buffer.writeString(speakerType);
                buffer.writeFloat(gain);
                ClientPlayNetworking.send(ModMessages.C2S_UPDATE_MIXER_GAIN, buffer);
            } else {
                float db = (float) (value * 18.0 - 9.0);
                AudioEngine.getInstance().setEqDbForSession(currentPlayerUuid(), speakerType, typeIndex - 1, db);
                PacketByteBuf buffer = PacketByteBufs.create();
                buffer.writeString(speakerType);
                buffer.writeInt(typeIndex - 1);
                buffer.writeFloat(db);
                ClientPlayNetworking.send(ModMessages.C2S_UPDATE_EQ, buffer);
            }
        }

        void setSliderValue(double rawValue) {
            value = Math.max(0.0, Math.min(rawValue, 1.0));
            applyValue();
            updateMessage();
        }

        @Override
        public void onClick(double mouseX, double mouseY) {
            long now = System.currentTimeMillis();
            if (now - lastClickTime < 250) {
                value = typeIndex == 0 ? gainToSlider(1.0f) : 0.5;
                applyValue();
                updateMessage();
                lastClickTime = 0;
                return;
            }
            lastClickTime = now;
            super.onClick(mouseX, mouseY);
        }

        @Override
        public void onRelease(double mouseX, double mouseY) {
            super.onRelease(mouseX, mouseY);
            setFocused(false);
        }

        @Override
        public void renderButton(DrawContext context, int mouseX, int mouseY, float delta) {
            context.fill(
                    getX(),
                    getY() + height / 2 - 1,
                    getX() + width,
                    getY() + height / 2 + 1,
                    theme.color() & 0x77FFFFFF);
            if (typeIndex > 0) {
                context.fill(
                        getX() + width / 2,
                        getY(),
                        getX() + width / 2 + 1,
                        getY() + height,
                        theme.color() & 0x77FFFFFF);
            }
            int knobWidth = 6;
            int knobX = getX() + (int) (value * (width - knobWidth));
            context.fill(knobX, getY(), knobX + knobWidth, getY() + height, theme.color());
            if (isHovered() || isFocused()) {
                context.fill(
                        knobX - 1,
                        getY() - 1,
                        knobX + knobWidth + 1,
                        getY() + height + 1,
                        theme.isDark() ? 0x66FFFFFF : 0x66000000);
            }
            context.getMatrices().push();
            context.getMatrices().scale(0.85f, 0.85f, 1.0f);
            int textWidth = textRenderer.getWidth(getMessage());
            int textX = (int) ((getX() + (width - textWidth * 0.85f) / 2) / 0.85f);
            int textY = (int) ((getY() - 8) / 0.85f);
            context.drawText(textRenderer, getMessage(), textX, textY, theme.color(), false);
            context.getMatrices().pop();
        }

        private String getPrefix() {
            if (typeIndex == 0) return "Vol: ";
            float[] frequencies = StreamDSPPipeline.defaultEqFrequencies(speakerType);
            if (typeIndex - 1 >= frequencies.length) return "Band " + (typeIndex - 1) + ": ";
            return formatFrequency(frequencies[typeIndex - 1]) + ": ";
        }

        private static String formatFrequency(float frequency) {
            if (frequency >= 1000.0f) {
                int kHz = (int) (frequency / 1000.0f);
                return kHz + "kHz";
            }
            return (int) frequency + "Hz";
        }
    }

    private final class QSliderWidget extends SliderWidget {
        private final String speakerType;
        private final int typeIndex;
        private long lastClickTime;

        private QSliderWidget(int x, int y, int width, int height, String speakerType, int typeIndex) {
            super(x, y, width, height, Text.empty(), 0.5);
            this.speakerType = speakerType;
            this.typeIndex = typeIndex;
            float q = AudioEngine.getInstance().getEqQ(speakerType, typeIndex - 1);
            value = q <= 1.0f ? (q - 0.1f) / 1.8f : 0.5f + (q - 1.0f) / 18.0f;
            updateMessage();
        }

        @Override
        protected void updateMessage() {
            setMessage(Text.literal("Q:" + String.format("%.1f", sliderToQ(value))));
        }

        @Override
        protected void applyValue() {
            float q = sliderToQ(value);
            AudioEngine.getInstance().setEqQForSession(currentPlayerUuid(), speakerType, typeIndex - 1, q);
            PacketByteBuf buffer = PacketByteBufs.create();
            buffer.writeString(speakerType);
            buffer.writeInt(typeIndex - 1);
            buffer.writeFloat(q);
            ClientPlayNetworking.send(ModMessages.C2S_UPDATE_EQ_Q, buffer);
        }

        void setSliderValue(double rawValue) {
            value = Math.max(0.0, Math.min(rawValue, 1.0));
            applyValue();
            updateMessage();
        }

        @Override
        public void onClick(double mouseX, double mouseY) {
            long now = System.currentTimeMillis();
            if (now - lastClickTime < 250) {
                value = 0.5;
                applyValue();
                updateMessage();
                lastClickTime = 0;
                return;
            }
            lastClickTime = now;
            super.onClick(mouseX, mouseY);
        }

        @Override
        public void renderButton(DrawContext context, int mouseX, int mouseY, float delta) {
            context.fill(
                    getX(),
                    getY() + height / 2 - 1,
                    getX() + width,
                    getY() + height / 2 + 1,
                    theme.color() & 0x77FFFFFF);
            int knobWidth = 4;
            int knobX = getX() + (int) (value * (width - knobWidth));
            context.fill(knobX, getY() + 2, knobX + knobWidth, getY() + height - 2, theme.color());
            if (isHovered() || isFocused()) {
                context.fill(
                        knobX - 1,
                        getY() + 1,
                        knobX + knobWidth + 1,
                        getY() + height - 1,
                        theme.isDark() ? 0x66FFFFFF : 0x66000000);
            }
            context.getMatrices().push();
            context.getMatrices().scale(0.65f, 0.65f, 1.0f);
            int textWidth = textRenderer.getWidth(getMessage());
            int textX = (int) ((getX() + (width - textWidth * 0.65f) / 2) / 0.65f);
            int textY = (int) ((getY() - 6) / 0.65f);
            context.drawText(textRenderer, getMessage(), textX, textY, theme.color(), false);
            context.getMatrices().pop();
        }

        private float sliderToQ(double sliderValue) {
            return sliderValue <= 0.5
                    ? 0.1f + (float) (sliderValue * 1.8f)
                    : 1.0f + (float) ((sliderValue - 0.5) * 18.0f);
        }
    }
}
