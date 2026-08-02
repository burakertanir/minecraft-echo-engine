package com.audiophilecraft.client.screen;

import com.audiophilecraft.client.util.YouTubeThumbnailCache;
import com.audiophilecraft.network.ModMessages;
import com.audiophilecraft.sound.AudioEngine;
import com.audiophilecraft.sound.PlaybackSession;
import com.audiophilecraft.util.YouTubeSearcher;
import com.mojang.blaze3d.systems.RenderSystem;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.IntSupplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.widget.ClickableWidget;
import net.minecraft.client.gui.widget.SliderWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import net.minecraft.util.Util;

/** Owns search, track metadata and the tablet's primary playback controls. */
final class AmplifierPlaybackPanel {
    private static final Pattern YOUTUBE_ID_PATTERN =
            Pattern.compile("(?<=v=|v\\/|vi=|vi\\/|youtu.be\\/|\\/v\\/|embed\\/)([a-zA-Z0-9_-]{11})");

    private static String activePlayUrl = "";
    private static String activeDisplayTitle = "";
    private static String activeChannelName = "";

    private final AmplifierTheme theme;
    private final TextRenderer textRenderer;
    private final IntSupplier handOrdinal;

    private TextFieldWidget urlField;
    private AmplifierTheme.ThemedButton playButton;
    private AmplifierTheme.ThemedButton stopButton;
    private PowerSlider powerSlider;
    private InputGainSlider inputGainSlider;
    private SeekBarWidget seekBar;

    private float currentPower;
    private float currentInputGain;
    private long lastInteractionTime;
    private long lastTypedTime;
    private String lastSearchedQuery = "";
    private boolean dropdownOpen;
    private volatile boolean searching;
    private volatile List<YouTubeSearcher.SearchResult> searchResults = new ArrayList<>();

    AmplifierPlaybackPanel(
            AmplifierTheme theme,
            TextRenderer textRenderer,
            IntSupplier handOrdinal,
            float initialPower,
            float initialInputGain) {
        this.theme = theme;
        this.textRenderer = textRenderer;
        this.handOrdinal = handOrdinal;
        this.currentPower = initialPower;
        this.currentInputGain = initialInputGain;
    }

    void initialize(
            int screenX, int screenY, int backgroundWidth, int backgroundHeight, Consumer<ClickableWidget> addWidget) {
        int clusterY = screenY + (backgroundHeight - 165) / 2;
        urlField = createUrlField(screenX, clusterY, backgroundWidth);
        int buttonStartX = screenX + (backgroundWidth - 104) / 2;
        playButton = theme.button(
                textRenderer,
                buttonStartX,
                clusterY + 85,
                20,
                20,
                Text.literal("\u25B6"),
                button -> attemptStartPlaying());
        stopButton = theme.button(
                textRenderer,
                buttonStartX + 28,
                clusterY + 85,
                20,
                20,
                Text.literal("\u25A0"),
                button -> ClientPlayNetworking.send(ModMessages.C2S_STOP_AUDIO, PacketByteBufs.create()));
        seekBar = new SeekBarWidget(screenX + 50, clusterY + 110, backgroundWidth - 100, 6);

        double initialGainValue = currentInputGain / 3.0f;
        inputGainSlider = new InputGainSlider(screenX + 40, clusterY + 130, backgroundWidth - 80, 20, initialGainValue);
        double initialPowerValue = (currentPower - 0.1f) / 9.9f;
        powerSlider = new PowerSlider(screenX + 40, clusterY + 155, backgroundWidth - 80, 20, initialPowerValue);

        addWidget.accept(urlField);
        addWidget.accept(playButton);
        addWidget.accept(stopButton);
        addWidget.accept(seekBar);
        addWidget.accept(inputGainSlider);
        addWidget.accept(powerSlider);
    }

    void setVisible(boolean visible) {
        if (urlField != null) urlField.visible = visible;
        if (seekBar != null) seekBar.visible = visible;
        if (inputGainSlider != null) inputGainSlider.visible = visible;
        if (powerSlider != null) powerSlider.visible = visible;
        if (playButton != null) playButton.visible = visible;
        if (stopButton != null) stopButton.visible = visible;
    }

    void tick() {
        if (playButton != null) {
            PlaybackSession activeSession = AudioEngine.getInstance().getActiveSession();
            boolean playing = activeSession != null && activeSession.isPlaying() && !activeSession.isManuallyPaused();
            playButton.setMessage(Text.literal(playing ? "\u23F8" : "\u25B6"));
        }

        if (urlField == null) return;
        long now = System.currentTimeMillis();
        String currentText = urlField.getText().trim();
        if (dropdownOpen && currentText.length() >= 3 && now - lastTypedTime > 500) {
            if (!currentText.equals(lastSearchedQuery) && !searching) {
                lastSearchedQuery = currentText;
                searching = true;
                Thread thread = new Thread(
                        () -> {
                            searchResults = YouTubeSearcher.search(currentText);
                            searching = false;
                        },
                        "ECHO-TrackSearch");
                thread.setDaemon(true);
                thread.start();
            }
        }
    }

    void renderOverlay(DrawContext context, int mouseX, int mouseY) {
        renderTrackInfo(context);
        renderSearchDropdown(context, mouseX, mouseY);
    }

    void renderLoadingIndicator(DrawContext context) {
        if ((!searching && !theme.isFetchingThumbnail()) || urlField == null) return;
        int barWidth = 24;
        int rightX = urlField.getX() + urlField.getWidth() - 5 - barWidth;
        int barY = urlField.getY() + urlField.getHeight() / 2;
        float spin = (float) (Math.sin(System.currentTimeMillis() / 200.0) + 1.0) / 2.0f;
        int dashX = rightX + (int) (spin * (barWidth - 6));
        context.fill(rightX, barY, rightX + barWidth, barY + 1, (theme.color() & 0xFFFFFF) | 0x44000000);
        context.fill(dashX, barY, dashX + 6, barY + 1, theme.color());
    }

    boolean mouseClicked(double mouseX, double mouseY) {
        if (!dropdownOpen || urlField == null || !urlField.isFocused() || searchResults.isEmpty()) return false;
        int dropX = urlField.getX() - 5;
        int dropY = urlField.getY() + urlField.getHeight() + 1;
        int dropWidth = urlField.getWidth() + 10;
        int itemHeight = 28;
        if (mouseX >= dropX
                && mouseX <= dropX + dropWidth
                && mouseY >= dropY
                && mouseY <= dropY + searchResults.size() * itemHeight) {
            for (int index = 0; index < searchResults.size(); index++) {
                int itemY = dropY + index * itemHeight;
                if (mouseY >= itemY && mouseY < itemY + itemHeight) {
                    YouTubeSearcher.SearchResult result = searchResults.get(index);
                    activePlayUrl = "https://youtube.com/watch?v=" + result.videoId;
                    activeDisplayTitle = result.title;
                    activeChannelName = result.channel;
                    dropdownOpen = false;
                    urlField.setText(result.title);
                    urlField.setCursorToEnd();
                    theme.selectVideo(result.videoId);
                    attemptStartPlaying();
                    return true;
                }
            }
        } else if (mouseY > dropY) {
            dropdownOpen = false;
        }
        return false;
    }

    boolean mouseDragged(double mouseX, double mouseY, int button, double deltaX, double deltaY) {
        if (powerSlider != null && powerSlider.isFocused()) {
            powerSlider.mouseDragged(mouseX, mouseY, button, deltaX, deltaY);
            return true;
        }
        if (inputGainSlider != null && inputGainSlider.isFocused()) {
            inputGainSlider.mouseDragged(mouseX, mouseY, button, deltaX, deltaY);
            return true;
        }
        if (seekBar != null && seekBar.isFocused()) {
            seekBar.mouseDragged(mouseX, mouseY, button, deltaX, deltaY);
            return true;
        }
        return false;
    }

    boolean mouseReleased(double mouseX, double mouseY) {
        if (powerSlider != null && powerSlider.isFocused()) {
            powerSlider.onRelease(mouseX, mouseY);
            return true;
        }
        if (inputGainSlider != null && inputGainSlider.isFocused()) {
            inputGainSlider.onRelease(mouseX, mouseY);
            return true;
        }
        if (seekBar != null && seekBar.isFocused()) {
            seekBar.onRelease(mouseX, mouseY);
            return true;
        }
        return false;
    }

    boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (urlField == null || !urlField.isFocused()) return false;
        if (keyCode == 256) {
            urlField.setFocused(false);
            return true;
        }
        urlField.keyPressed(keyCode, scanCode, modifiers);
        return true;
    }

    void updateSpeakerPower(float power) {
        if (System.currentTimeMillis() - lastInteractionTime < 500) return;
        currentPower = power;
        if (powerSlider != null) {
            double sliderValue = (power - 0.1f) / 9.9f;
            powerSlider.setSliderValue(Math.max(0.0, Math.min(sliderValue, 1.0)));
            powerSlider.setMessage(Text.literal("Power: " + String.format("%.1f", power)));
        }
    }

    void updateInputGain(float gain) {
        if (System.currentTimeMillis() - lastInteractionTime < 500) return;
        currentInputGain = gain;
        if (inputGainSlider != null) {
            inputGainSlider.setSliderValue(Math.max(0.0, Math.min(gain / 3.0f, 1.0)));
            inputGainSlider.setMessage(Text.literal("Input Gain: " + (int) (gain * 100) + "%"));
        }
    }

    private TextFieldWidget createUrlField(int screenX, int clusterY, int backgroundWidth) {
        TextFieldWidget field =
                new TextFieldWidget(
                        textRenderer, screenX + 44, clusterY, backgroundWidth - 88, 20, Text.literal("URL")) {
                    @Override
                    public void renderButton(DrawContext context, int mouseX, int mouseY, float delta) {
                        setEditableColor(0xFFFFFFFF);
                        setUneditableColor(0xFFDDDDDD);
                        int protectiveFillAlpha = theme.inputProtectionAlpha();
                        if (protectiveFillAlpha != 0) {
                            context.fill(
                                    getX() - 4,
                                    getY(),
                                    getX() + width + 4,
                                    getY() + height,
                                    protectiveFillAlpha | 0x000000);
                        }
                        context.fill(getX() - 5, getY() - 1, getX() + width + 5, getY(), theme.color());
                        context.fill(
                                getX() - 5, getY() + height, getX() + width + 5, getY() + height + 1, theme.color());
                        context.fill(getX() - 5, getY() - 1, getX() - 4, getY() + height + 1, theme.color());
                        context.fill(
                                getX() + width + 4, getY() - 1, getX() + width + 5, getY() + height + 1, theme.color());
                        context.getMatrices().push();
                        context.getMatrices().translate(0, (height - 8) / 2.0f, 0);
                        super.renderButton(context, mouseX, mouseY, delta);
                        context.getMatrices().pop();
                    }
                };
        field.setDrawsBackground(false);
        field.setMaxLength(2048);
        field.setPlaceholder(Text.literal("Search a song name or paste a track URL..."));
        if (!activeDisplayTitle.isEmpty()) {
            field.setText(activeDisplayTitle);
        } else if (!activePlayUrl.isEmpty()) {
            field.setText(activePlayUrl);
        }
        field.setChangedListener(this::onUrlChanged);
        return field;
    }

    private void onUrlChanged(String text) {
        lastTypedTime = System.currentTimeMillis();
        String trimmed = text.trim();
        if (!trimmed.equals(activeDisplayTitle) && !trimmed.equals(activePlayUrl)) {
            activePlayUrl = "";
            activeDisplayTitle = "";
            activeChannelName = "";
        }
        if (trimmed.equals(activeDisplayTitle) && !activePlayUrl.isEmpty()) {
            dropdownOpen = false;
            searchResults.clear();
            theme.updateVideo(extractYouTubeId(activePlayUrl));
            return;
        }
        if (extractYouTubeId(trimmed) != null || trimmed.startsWith("http")) {
            dropdownOpen = false;
            searchResults.clear();
            activePlayUrl = trimmed;
            String videoId = extractYouTubeId(trimmed);
            fetchTrackMetadata(trimmed, videoId);
            theme.updateVideo(videoId);
        } else if (trimmed.length() >= 3) {
            dropdownOpen = true;
        } else {
            dropdownOpen = false;
            searchResults.clear();
        }
    }

    private void fetchTrackMetadata(String lockedUrl, String videoId) {
        if (!activeDisplayTitle.isEmpty()) return;
        Thread thread = new Thread(
                () -> {
                    String query = videoId != null ? videoId : lockedUrl;
                    List<YouTubeSearcher.SearchResult> results = YouTubeSearcher.search(query);
                    if (!results.isEmpty() && activePlayUrl.equals(lockedUrl)) {
                        activeDisplayTitle = results.get(0).title;
                        activeChannelName = results.get(0).channel;
                    }
                },
                "ECHO-TrackMetadata");
        thread.setDaemon(true);
        thread.start();
    }

    private void attemptStartPlaying() {
        String url =
                !activePlayUrl.isEmpty() ? activePlayUrl : urlField.getText().trim();
        long lockTime = 2500;
        PlaybackSession activeSession = AudioEngine.getInstance().getActiveSession();
        boolean sameUrl = activeSession != null && !url.isEmpty() && url.equals(activeSession.getPlayUrl());

        if (sameUrl) {
            if (activeSession.isPlaying()) {
                PacketByteBuf buffer = PacketByteBufs.create();
                buffer.writeInt(handOrdinal.getAsInt());
                ClientPlayNetworking.send(ModMessages.C2S_TOGGLE_PAUSE, buffer);
                lockTime = 100;
            } else {
                lockTime = sendPlayRequest(url);
            }
        } else {
            if (activeSession != null) {
                PacketByteBuf stopBuffer = PacketByteBufs.create();
                stopBuffer.writeInt(handOrdinal.getAsInt());
                ClientPlayNetworking.send(ModMessages.C2S_STOP_AUDIO, stopBuffer);
            }
            lockTime = sendPlayRequest(url);
        }

        playButton.active = false;
        long finalLockTime = lockTime;
        Thread thread = new Thread(
                () -> {
                    try {
                        Thread.sleep(finalLockTime);
                    } catch (InterruptedException exception) {
                        Thread.currentThread().interrupt();
                    }
                    if (playButton != null) playButton.active = true;
                },
                "ECHO-PlayButtonUnlock");
        thread.setDaemon(true);
        thread.start();
    }

    private long sendPlayRequest(String url) {
        if (url.isEmpty()) return 0;
        PacketByteBuf buffer = PacketByteBufs.create();
        buffer.writeInt(handOrdinal.getAsInt());
        buffer.writeString(url);
        ClientPlayNetworking.send(ModMessages.C2S_PLAY_URL, buffer);
        return 2500;
    }

    private void renderTrackInfo(DrawContext context) {
        String videoId = theme.videoId();
        if (videoId == null || urlField == null) return;
        int panelX = urlField.getX();
        int panelY = urlField.getY() + 25;
        int panelWidth = urlField.getWidth();
        int thumbnailSize = 64;
        int thumbnailX = panelX;
        int thumbnailY = panelY;
        Identifier thumbnail = YouTubeThumbnailCache.getIdentifier(videoId);
        if (thumbnail != null) {
            RenderSystem.enableBlend();
            context.drawTexture(thumbnail, thumbnailX - 16, thumbnailY - 16, 96, 96, 0, 0, 270, 270, 270, 270);
            RenderSystem.disableBlend();
        } else {
            theme.drawRoundedRect(
                    context,
                    thumbnailX,
                    thumbnailY,
                    thumbnailX + thumbnailSize,
                    thumbnailY + thumbnailSize,
                    8.0f,
                    0xFF222222);
        }

        int textX = thumbnailX + thumbnailSize + 16;
        String displayTitle = !activeDisplayTitle.isEmpty()
                ? activeDisplayTitle
                : (!activePlayUrl.isEmpty() ? activePlayUrl : "Unknown Track");
        String channelName = !activeChannelName.isEmpty() ? activeChannelName : "YouTube";
        int maxTextWidth = panelWidth - (thumbnailSize + 24);
        int scaledTitleWidth = (int) (textRenderer.getWidth(displayTitle) * 1.6f);
        float titleScroll = 0.0f;
        int gap = 64;
        float fullCycle = scaledTitleWidth + gap;
        if (scaledTitleWidth > maxTextWidth) {
            float duration = fullCycle / 50.0f * 1000.0f;
            long cycleTime = Util.getMeasuringTimeMs() % (long) duration;
            titleScroll = cycleTime / duration * fullCycle;
        }

        context.enableScissor(textX, panelY, textX + maxTextWidth, panelY + thumbnailSize);
        context.getMatrices().push();
        context.getMatrices().translate(textX - titleScroll, panelY + 12, 0);
        context.getMatrices().scale(1.6f, 1.6f, 1.0f);
        context.drawText(textRenderer, displayTitle, 0, 0, 0xFFFFFFFF, true);
        if (scaledTitleWidth > maxTextWidth) {
            context.drawText(textRenderer, displayTitle, (int) (fullCycle / 1.6f), 0, 0xFFFFFFFF, true);
        }
        context.getMatrices().pop();
        context.disableScissor();

        context.getMatrices().push();
        context.getMatrices().scale(1.1f, 1.1f, 1.0f);
        String trimmedChannel = textRenderer.trimToWidth(channelName, (int) (maxTextWidth / 1.1f));
        context.drawText(
                textRenderer, trimmedChannel, (int) (textX / 1.1f), (int) ((panelY + 42) / 1.1f), 0xFFAAAAAA, false);
        context.getMatrices().pop();
    }

    private void renderSearchDropdown(DrawContext context, int mouseX, int mouseY) {
        if (!dropdownOpen || urlField == null || !urlField.isFocused()) return;
        context.getMatrices().push();
        context.getMatrices().translate(0.0f, 0.0f, 300.0f);
        int dropX = urlField.getX() - 5;
        int dropY = urlField.getY() + urlField.getHeight() + 1;
        int dropWidth = urlField.getWidth() + 10;
        int itemHeight = 28;
        if (searching) {
            context.fill(dropX, dropY, dropX + dropWidth, dropY + 20, 0xDD000000);
            context.drawText(textRenderer, "Searching...", dropX + 5, dropY + 6, 0xFFAAAAAA, false);
            drawDropdownBorder(context, dropX, dropY, dropWidth, 20);
        } else if (!searchResults.isEmpty()) {
            int totalHeight = searchResults.size() * itemHeight;
            context.fill(dropX, dropY, dropX + dropWidth, dropY + totalHeight, 0xFA050505);
            for (int index = 0; index < searchResults.size(); index++) {
                YouTubeSearcher.SearchResult result = searchResults.get(index);
                int itemY = dropY + index * itemHeight;
                if (mouseX >= dropX && mouseX <= dropX + dropWidth && mouseY >= itemY && mouseY < itemY + itemHeight) {
                    context.fill(dropX, itemY, dropX + dropWidth, itemY + itemHeight, 0x44FFFFFF);
                }
                String title = textRenderer.trimToWidth(result.title, dropWidth - 10);
                String subtitle =
                        textRenderer.trimToWidth(result.channel + " \u2022 " + result.duration, dropWidth - 10);
                context.drawText(textRenderer, title, dropX + 5, itemY + 4, 0xFFFFFFFF, false);
                context.drawText(textRenderer, subtitle, dropX + 5, itemY + 16, 0xFFAAAAAA, false);
            }
            drawDropdownBorder(context, dropX, dropY, dropWidth, totalHeight);
        }
        context.getMatrices().pop();
    }

    private void drawDropdownBorder(DrawContext context, int x, int y, int width, int height) {
        context.fill(x, y, x + width, y + 1, theme.color());
        context.fill(x, y + height, x + width, y + height + 1, theme.color());
        context.fill(x, y, x + 1, y + height, theme.color());
        context.fill(x + width - 1, y, x + width, y + height, theme.color());
    }

    private static String extractYouTubeId(String url) {
        if (url == null || url.isEmpty()) return null;
        Matcher matcher = YOUTUBE_ID_PATTERN.matcher(url);
        return matcher.find() ? matcher.group(1) : null;
    }

    private final class PowerSlider extends SliderWidget {
        private PowerSlider(int x, int y, int width, int height, double value) {
            super(x, y, width, height, Text.empty(), value);
            updateMessage();
        }

        @Override
        protected void updateMessage() {
            float power = 0.1f + (float) value * 9.9f;
            setMessage(Text.literal("Power: " + String.format("%.1f", power)));
        }

        @Override
        protected void applyValue() {
            lastInteractionTime = System.currentTimeMillis();
            currentPower = 0.1f + (float) value * 9.9f;
            AudioEngine.getInstance().updatePower(currentPower);
        }

        @Override
        public void onRelease(double mouseX, double mouseY) {
            super.onRelease(mouseX, mouseY);
            AudioEngine.getInstance().updatePower(currentPower);
            PacketByteBuf buffer = PacketByteBufs.create();
            buffer.writeInt(handOrdinal.getAsInt());
            buffer.writeFloat(currentPower);
            ClientPlayNetworking.send(ModMessages.C2S_UPDATE_POWER, buffer);
            setFocused(false);
        }

        @Override
        public boolean mouseDragged(double mouseX, double mouseY, int button, double deltaX, double deltaY) {
            value = clampSlider(mouseX, getX(), width, 4);
            applyValue();
            updateMessage();
            return true;
        }

        void setSliderValue(double sliderValue) {
            value = sliderValue;
            updateMessage();
        }

        @Override
        public void renderButton(DrawContext context, int mouseX, int mouseY, float delta) {
            renderMainSlider(context, this, value, 8);
        }
    }

    private final class InputGainSlider extends SliderWidget {
        private InputGainSlider(int x, int y, int width, int height, double value) {
            super(x, y, width, height, Text.empty(), value);
            updateMessage();
        }

        @Override
        protected void updateMessage() {
            setMessage(Text.literal("Input Gain: " + (int) (value * 300.0) + "%"));
        }

        @Override
        protected void applyValue() {
            lastInteractionTime = System.currentTimeMillis();
            currentInputGain = (float) value * 3.0f;
            AudioEngine.getInstance().updateInputGain(currentInputGain);
        }

        @Override
        public void onRelease(double mouseX, double mouseY) {
            super.onRelease(mouseX, mouseY);
            AudioEngine.getInstance().updateInputGain(currentInputGain);
            PacketByteBuf buffer = PacketByteBufs.create();
            buffer.writeInt(handOrdinal.getAsInt());
            buffer.writeFloat(currentInputGain);
            ClientPlayNetworking.send(ModMessages.C2S_UPDATE_INPUT_GAIN, buffer);
            setFocused(false);
        }

        @Override
        public boolean mouseDragged(double mouseX, double mouseY, int button, double deltaX, double deltaY) {
            value = clampSlider(mouseX, getX(), width, 4);
            applyValue();
            updateMessage();
            return true;
        }

        void setSliderValue(double sliderValue) {
            value = sliderValue;
            updateMessage();
        }

        @Override
        public void renderButton(DrawContext context, int mouseX, int mouseY, float delta) {
            renderMainSlider(context, this, value, 8);
        }
    }

    private final class SeekBarWidget extends SliderWidget {
        private SeekBarWidget(int x, int y, int width, int height) {
            super(x, y, width, height, Text.empty(), 0.0);
            updateMessage();
        }

        @Override
        protected void updateMessage() {
            double total = AudioEngine.getInstance().getTotalPlaybackDuration();
            if (total <= 0) {
                setMessage(Text.literal("00:00 / 00:00"));
                return;
            }
            double target = value * total;
            String current = String.format("%02d:%02d", (int) target / 60, (int) target % 60);
            String duration = String.format("%02d:%02d", (int) total / 60, (int) total % 60);
            setMessage(Text.literal(current + " / " + duration));
        }

        @Override
        protected void applyValue() {
            lastInteractionTime = System.currentTimeMillis();
        }

        @Override
        public void onRelease(double mouseX, double mouseY) {
            super.onRelease(mouseX, mouseY);
            double total = AudioEngine.getInstance().getTotalPlaybackDuration();
            if (total > 0) {
                float targetTime = (float) (value * total);
                AudioEngine.getInstance().seek(targetTime);
                PacketByteBuf buffer = PacketByteBufs.create();
                buffer.writeFloat(targetTime);
                ClientPlayNetworking.send(ModMessages.C2S_SEEK_TRACK, buffer);
            }
            setFocused(false);
        }

        @Override
        public boolean mouseDragged(double mouseX, double mouseY, int button, double deltaX, double deltaY) {
            value = clampSlider(mouseX, getX(), width, 4);
            applyValue();
            updateMessage();
            return true;
        }

        @Override
        public void renderButton(DrawContext context, int mouseX, int mouseY, float delta) {
            autoUpdate();
            context.fill(
                    getX(),
                    getY() + height / 2 - 1,
                    getX() + width,
                    getY() + height / 2 + 1,
                    theme.color() & 0x77FFFFFF);
            int knobWidth = 4;
            int knobX = getX() + (int) (value * (width - knobWidth));
            context.fill(getX(), getY() + height / 2 - 1, knobX, getY() + height / 2 + 1, theme.color());
            if (isHovered() || isFocused()) {
                context.fill(knobX - 2, getY() - 2, knobX + knobWidth + 2, getY() + height + 2, theme.color());
                context.fill(
                        knobX - 2,
                        getY() - 2,
                        knobX + knobWidth + 2,
                        getY() + height + 2,
                        theme.isDark() ? 0x66FFFFFF : 0x66000000);
            }
            int textWidth = textRenderer.getWidth(getMessage());
            context.drawText(
                    textRenderer,
                    getMessage(),
                    getX() + (width - textWidth) / 2,
                    getY() + height + 4,
                    theme.color(),
                    false);
        }

        private void autoUpdate() {
            if (isFocused()) return;
            double total = AudioEngine.getInstance().getTotalPlaybackDuration();
            double current = AudioEngine.getInstance().getCurrentPlaybackTime();
            if (total > 0) {
                value = Math.max(0.0, Math.min(current / total, 1.0));
                updateMessage();
            }
        }
    }

    private void renderMainSlider(DrawContext context, SliderWidget slider, double sliderValue, int knobWidth) {
        int x = slider.getX();
        int y = slider.getY();
        int width = slider.getWidth();
        int height = slider.getHeight();
        context.fill(x, y, x + width, y + 1, theme.color());
        context.fill(x, y + height - 1, x + width, y + height, theme.color());
        context.fill(x, y, x + 1, y + height, theme.color());
        context.fill(x + width - 1, y, x + width, y + height, theme.color());
        int knobX = x + (int) (sliderValue * (width - knobWidth));
        context.fill(knobX, y, knobX + knobWidth, y + height, theme.color());
        if (slider.isHovered()) {
            context.fill(knobX, y, knobX + knobWidth, y + height, theme.isDark() ? 0x66FFFFFF : 0x66000000);
        }
        int textWidth = textRenderer.getWidth(slider.getMessage());
        context.drawText(
                textRenderer,
                slider.getMessage(),
                x + (width - textWidth) / 2,
                y + (height - 8) / 2,
                theme.color(),
                false);
    }

    private static double clampSlider(double mouseX, int sliderX, int width, int padding) {
        double rawValue = (mouseX - (sliderX + (double) padding)) / (width - padding * 2.0);
        return Math.max(0.0, Math.min(rawValue, 1.0));
    }
}
