package com.audiophilecraft.client.screen;

import com.audiophilecraft.screen.AmplifierScreenHandler;
import com.audiophilecraft.sound.AudioEngine;
import com.audiophilecraft.sound.PeakMeter;
import java.util.List;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.client.gui.widget.ClickableWidget;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.text.Text;

/** Coordinates the tablet's playback, mixer and acoustic-map views. */
public class AmplifierScreen extends HandledScreen<AmplifierScreenHandler> {
    private final AmplifierTheme theme;
    private AmplifierPlaybackPanel playbackPanel;
    private AmplifierMixerPanel mixerPanel;
    private AmplifierAcousticMapPanel acousticMapPanel;

    private AmplifierTheme.ThemedButton mixerButton;
    private AmplifierTheme.ThemedButton mapButton;
    private boolean mixerOpen;
    private boolean mapOpen;
    private float currentPower;
    private float currentInputGain;

    public AmplifierScreen(AmplifierScreenHandler handler, PlayerInventory inventory, Text title) {
        super(handler, inventory, title);
        theme = new AmplifierTheme();
        currentPower = handler.getSpeakerPower();
        currentInputGain = handler.getInputGain();
    }

    @Override
    protected void init() {
        backgroundWidth = Math.min(640, (int) (width * 0.85f));
        backgroundHeight = Math.min(320, (int) (height * 0.85f));
        super.init();

        if (playbackPanel == null) {
            playbackPanel = new AmplifierPlaybackPanel(
                    theme,
                    textRenderer,
                    this::getHandOrdinal,
                    currentPower,
                    currentInputGain,
                    handler.getSelectedOwner());
        }
        if (mixerPanel == null) {
            mixerPanel = new AmplifierMixerPanel(theme, textRenderer);
        }
        if (acousticMapPanel == null) {
            acousticMapPanel = new AmplifierAcousticMapPanel(textRenderer);
        }

        if (client != null && client.player != null) {
            AudioEngine.getInstance().ensureActiveSession(client.player.getUuid());
        }

        int screenX = (width - backgroundWidth) / 2;
        int screenY = (height - backgroundHeight) / 2;
        int clusterY = screenY + (backgroundHeight - 165) / 2;
        int buttonStartX = screenX + (backgroundWidth - 104) / 2;

        playbackPanel.initialize(screenX, screenY, backgroundWidth, backgroundHeight, this::addScreenWidget);
        mixerPanel.initialize(screenX, screenY, backgroundWidth, this::addScreenWidget);
        mixerButton =
                theme.button(textRenderer, buttonStartX + 56, clusterY + 85, 20, 20, Text.literal("\u2630"), button -> {
                    mixerOpen = !mixerOpen;
                    if (mixerOpen) mapOpen = false;
                    updateWidgetVisibility();
                });
        mapButton =
                theme.button(textRenderer, buttonStartX + 84, clusterY + 85, 20, 20, Text.literal("\u25CE"), button -> {
                    mapOpen = !mapOpen;
                    if (mapOpen) mixerOpen = false;
                    updateWidgetVisibility();
                });
        addDrawableChild(mixerButton);
        addDrawableChild(mapButton);
        updateWidgetVisibility();
    }

    public void updateSpeakerPower(float power) {
        currentPower = power;
        if (playbackPanel != null) playbackPanel.updateSpeakerPower(power);
    }

    public void updateInputGain(float gain) {
        currentInputGain = gain;
        if (playbackPanel != null) playbackPanel.updateInputGain(gain);
    }

    public void updateSpeakerOwners(List<com.audiophilecraft.network.ModMessages.SpeakerOwner> owners) {
        if (playbackPanel != null) playbackPanel.updateSpeakerOwners(owners);
    }

    @Override
    protected void drawBackground(DrawContext context, float delta, int mouseX, int mouseY) {
        int screenX = (width - backgroundWidth) / 2;
        int screenY = (height - backgroundHeight) / 2;
        theme.renderBackground(context, delta, screenX, screenY, backgroundWidth, backgroundHeight);
    }

    @Override
    protected void drawForeground(DrawContext context, int mouseX, int mouseY) {}

    @Override
    protected void handledScreenTick() {
        super.handledScreenTick();
        playbackPanel.tick();
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        renderBackground(context);
        PeakMeter.getInstance().update();
        super.render(context, mouseX, mouseY, delta);

        int screenX = (width - backgroundWidth) / 2;
        int screenY = (height - backgroundHeight) / 2;
        if (mapOpen) {
            acousticMapPanel.render(context, screenX, screenY, backgroundWidth, backgroundHeight, mouseX, mouseY);
        } else if (mixerOpen) {
            mixerPanel.render(context, screenX, screenY, backgroundWidth);
        } else {
            playbackPanel.renderOverlay(context, mouseX, mouseY);
        }
        playbackPanel.renderLoadingIndicator(context);
        drawMouseoverTooltip(context, mouseX, mouseY);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (mapOpen) {
            int screenX = (width - backgroundWidth) / 2;
            int screenY = (height - backgroundHeight) / 2;
            if (button == 0 && acousticMapPanel.mouseClicked(mouseX, mouseY, screenX, screenY, backgroundWidth)) {
                return true;
            }
            return super.mouseClicked(mouseX, mouseY, button);
        }
        if (mixerOpen && button == 0) {
            if (mixerPanel.mouseClicked(mouseX, mouseY, button)) return true;
            if (mixerButton.visible && mixerButton.isMouseOver(mouseX, mouseY)) {
                mixerButton.onClick(mouseX, mouseY);
            }
            return true;
        }
        if (playbackPanel.mouseClicked(mouseX, mouseY)) return true;
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double deltaX, double deltaY) {
        if (mixerPanel.mouseDragged(mouseX, button)) return true;
        if (playbackPanel.mouseDragged(mouseX, mouseY, button, deltaX, deltaY)) return true;
        return super.mouseDragged(mouseX, mouseY, button, deltaX, deltaY);
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        if (mixerPanel.mouseReleased()) return true;
        if (playbackPanel.mouseReleased(mouseX, mouseY)) {
            setFocused(null);
            return true;
        }
        return super.mouseReleased(mouseX, mouseY, button);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (playbackPanel.keyPressed(keyCode, scanCode, modifiers)) return true;
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    private int getHandOrdinal() {
        return handler.getHand() != null ? handler.getHand().ordinal() : 0;
    }

    private void addScreenWidget(ClickableWidget widget) {
        addDrawableChild(widget);
    }

    private void updateWidgetVisibility() {
        boolean showMain = !mixerOpen && !mapOpen;
        playbackPanel.setVisible(showMain);
        mixerButton.visible = showMain || mixerOpen;
        mapButton.visible = showMain || mapOpen;

        if (mixerOpen) {
            mixerButton.setX((width - 20) / 2);
            mixerButton.setY((height + backgroundHeight) / 2 - 25);
        } else if (mapOpen) {
            mapButton.setX((width - 20) / 2);
            mapButton.setY((height + backgroundHeight) / 2 - 25);
        } else {
            int screenX = (width - backgroundWidth) / 2;
            int screenY = (height - backgroundHeight) / 2;
            int clusterY = screenY + (backgroundHeight - 165) / 2;
            int buttonStartX = screenX + (backgroundWidth - 104) / 2;
            mixerButton.setX(buttonStartX + 56);
            mixerButton.setY(clusterY + 85);
            mapButton.setX(buttonStartX + 84);
            mapButton.setY(clusterY + 85);
        }
        mixerPanel.setVisible(mixerOpen);
    }
}
