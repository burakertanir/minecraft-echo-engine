package com.audiophilecraft.client.screen;
import com.audiophilecraft.network.ModMessages;
import com.audiophilecraft.screen.AmplifierScreenHandler;
import com.mojang.blaze3d.systems.RenderSystem;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.SliderWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
public class AmplifierScreen extends HandledScreen<AmplifierScreenHandler> {
    private static final Identifier TEXTURE = new Identifier("audiophilecraft", "textures/gui/amplifier_gui.png");
    private ButtonWidget playButton;
    private ButtonWidget stopButton;
    private TextFieldWidget urlField;
    private PowerSlider powerSlider;
    private InputGainSlider inputGainSlider;
    private SeekBarWidget seekBar;
    private ButtonWidget mixerButton;
    private TransparentButton midButton;
    private TransparentButton sideButton;
    private TransparentButton mapButton;
    private boolean isMixerOpen = false;
    private boolean isMapOpen = false;
    private java.util.List<MixerSliderWidget> mixerSliders = new java.util.ArrayList<>();
    private java.util.List<QSliderWidget> qSliders = new java.util.ArrayList<>();
    private float currentPower = 1.0f;
    private float currentInputGain = 1.0f;
    private long lastInteractionTime = 0;
    // --- Dynamic Background Variables (Static for Tablet persistence) ---
    private static String currentParsedVideoId = null;
    private static volatile boolean isFetchingThumbnail = false; // volatile: written by background thread, read by render thread
    private static volatile long thumbnailFetchVersion = 0;
    private static int[] targetColors = new int[] { 0xFF333333, 0xFF555555, 0xFF444444, 0xFF333333 }; // Default gray
    private static int[] currentColors = new int[] { 0xFF333333, 0xFF555555, 0xFF444444, 0xFF333333 };
    private static float timeOffset = 0f;
    private static float themeState = 0.0f; // 0.0 = white theme, 1.0 = dark theme
    private static int currentAdaptiveThemeColor = 0xFFFFFFFF;
    // --- Search Integration Variables ---
    private long lastTypedTime = 0;
    private String lastSearchedQuery = "";
    private boolean isDropdownOpen = false;
    private volatile boolean isSearching = false; // volatile: written by background thread, read by render thread
    private volatile java.util.List<com.audiophilecraft.util.YouTubeSearcher.SearchResult> searchResults = new java.util.ArrayList<>(); // volatile: assigned from background thread, iterated on render thread
    // --- Decoupled Play variables (Static for persistence) ---
    private static String activePlayUrl = "";
    private static String activeDisplayTitle = "";
    private static String activeChannelName = "";
    // ------------------------------------

    public AmplifierScreen(AmplifierScreenHandler handler, PlayerInventory inventory, Text title) {
        super(handler, inventory, title);
        this.currentInputGain = handler.getInputGain();
        this.currentPower = handler.getSpeakerPower();
    }
    private int getHandOrdinal() {
        return handler.getHand() != null ? handler.getHand().ordinal() : 0;
    }
    @Override
    protected void init() {
        // Tablet window fills 85% of screen but capped at 640x320 for maximum scaling
        // bounds.
        this.backgroundWidth = Math.min(640, (int) (width * 0.85f));
        this.backgroundHeight = Math.min(320, (int) (height * 0.85f));
        super.init();
        int x = (width - backgroundWidth) / 2;
        int y = (height - backgroundHeight) / 2;
        // Cluster Height is ~165px. Start Y centrally aligned within available physical
        // canvas height.
        int clusterY = y + (backgroundHeight - 165) / 2;
        // URL Input Field
        // Shift actual click bounds inwards by 4px on X axis so the custom bordering
        // acts as padding.
        urlField = new TextFieldWidget(textRenderer, x + 44, clusterY, backgroundWidth - 88, 20,
                Text.literal("URL")) {
            @Override
            public void renderButton(DrawContext context, int mouseX, int mouseY, float delta) {
                // Keeping text solid white prevents Minecraft's forced drop-shadow from turning
                // the typography muddy.
                this.setEditableColor(0xFFFFFFFF);
                this.setUneditableColor(0xFFDDDDDD);
                // Dynamically fade in a protective dark translucent pill background under the
                // text when the global theme is bright
                int protectiveFillAlpha = (int) (themeState * 0x77) << 24;
                if (protectiveFillAlpha != 0) {
                    context.fill(getX() - 4, getY(), getX() + width + 4, getY() + height,
                            protectiveFillAlpha | 0x000000);
                }
                // Border drawn around the padded bounds
                context.fill(getX() - 5, getY() - 1, getX() + width + 5, getY(), currentAdaptiveThemeColor);
                context.fill(getX() - 5, getY() + height, getX() + width + 5, getY() + height + 1,
                        currentAdaptiveThemeColor);
                context.fill(getX() - 5, getY() - 1, getX() - 4, getY() + height + 1, currentAdaptiveThemeColor);
                context.fill(getX() + width + 4, getY() - 1, getX() + width + 5, getY() + height + 1,
                        currentAdaptiveThemeColor);
                // Shift text rendering down vertically because setDrawsBackground(false) locks
                // text to absolute top limit (0 Y padding)
                context.getMatrices().push();
                context.getMatrices().translate(0, (height - 8) / 2.0f, 0);
                super.renderButton(context, mouseX, mouseY, delta);
                context.getMatrices().pop();
            }
        };
        urlField.setDrawsBackground(false); // Transparent background!
        urlField.setMaxLength(2048);
        urlField.setPlaceholder(Text.literal("Search a song name or paste a track URL..."));
        // Restore persistent search box state
        // For direct URLs, always show the raw URL (not the resolved title)
        if (!activePlayUrl.isEmpty() && activePlayUrl.startsWith("http")) {
            urlField.setText(activePlayUrl);
        } else if (!activeDisplayTitle.isEmpty()) {
            urlField.setText(activeDisplayTitle);
        } else if (!activePlayUrl.isEmpty()) {
            urlField.setText(activePlayUrl);
        }
        urlField.setChangedListener(text -> {
            lastTypedTime = System.currentTimeMillis();
            String trimmed = text.trim();
            // If user types to modify the clean title OR the locked url, unlock the backing URL
            // But do NOT wipe metadata if the field simply contains the locked URL (e.g. user clicked on it)
            if (!trimmed.equals(activeDisplayTitle) && !trimmed.equals(activePlayUrl)) {
                activePlayUrl = "";
                activeDisplayTitle = "";
                activeChannelName = "";
            }
            // A selection was natively injected
            if (trimmed.equals(activeDisplayTitle) && !activePlayUrl.isEmpty()) {
                isDropdownOpen = false;
                searchResults.clear();
                String videoId = extractYouTubeId(activePlayUrl);
                if (videoId != null && !videoId.equals(currentParsedVideoId)) {
                    currentParsedVideoId = videoId;
                    fetchThumbnailColorsAsync(videoId);
                }
                return;
            }
            // Bypass search for direct URLs
            if (extractYouTubeId(trimmed) != null || trimmed.startsWith("http")) {
                isDropdownOpen = false;
                searchResults.clear();
                activePlayUrl = trimmed; // Lock the typed URL naturally
                
                String videoId = extractYouTubeId(trimmed);
                // Fetch the title and channel for the info panel below (NOT for the field itself)
                // Only fetch if we don't already have metadata for this exact URL
                final String lockedUrl = trimmed;
                if (activeDisplayTitle.isEmpty()) {
                    Thread t = new Thread(() -> {
                        String query = videoId != null ? videoId : lockedUrl;
                        java.util.List<com.audiophilecraft.util.YouTubeSearcher.SearchResult> res = com.audiophilecraft.util.YouTubeSearcher.search(query);
                        if (!res.isEmpty() && activePlayUrl.equals(lockedUrl)) {
                            activeDisplayTitle = res.get(0).title;
                            activeChannelName = res.get(0).channel;
                            // Do NOT update the urlField text — user wants to keep the URL visible
                        }
                    });
                    t.setDaemon(true);
                    t.start();
                }
                if (videoId != null && !videoId.equals(currentParsedVideoId)) {
                    currentParsedVideoId = videoId;
                    fetchThumbnailColorsAsync(videoId);
                } else if (videoId == null && currentParsedVideoId != null) {
                    currentParsedVideoId = null; // Revert to defaults
                    targetColors[0] = 0xFF333333;
                    targetColors[1] = 0xFF555555;
                    targetColors[2] = 0xFF444444;
                    targetColors[3] = 0xFF333333;
                }
            } else {
                // Custom search query mode
                if (trimmed.length() >= 3) {
                    isDropdownOpen = true;
                } else {
                    isDropdownOpen = false;
                    searchResults.clear();
                }
            }
        });
        int buttonStartX = x + (backgroundWidth - 50) / 2;
        playButton = new TransparentButton(buttonStartX + 2, clusterY + 85, 20, 20, Text.literal("\u25B6"), button -> {
            attemptStartPlaying();
        });
        // Stop Button
        stopButton = new TransparentButton(buttonStartX + 30, clusterY + 85, 20, 20, Text.literal("\u25A0"), button -> {
            com.audiophilecraft.sound.AudioEngine.getInstance().stopAll();
        });
        // Mixer Button
        mixerButton = new TransparentButton(buttonStartX + 58, clusterY + 85, 20, 20, Text.literal("\uD83C\uDF9B"), button -> {
            isMixerOpen = !isMixerOpen;
            if (isMixerOpen) isMapOpen = false;
            updateWidgetVisibility();
        });
        // Map Button
        mapButton = new TransparentButton(buttonStartX + 86, clusterY + 85, 20, 20, Text.literal("\uD83D\uDDFA"), button -> { // Map icon
            isMapOpen = !isMapOpen;
            if (isMapOpen) isMixerOpen = false;
            updateWidgetVisibility();
        });
        addDrawableChild(urlField);
        addDrawableChild(playButton);
        addDrawableChild(stopButton);
        addDrawableChild(mixerButton);
        addDrawableChild(mapButton);
        // Seek Bar (Scrubber)
        seekBar = new SeekBarWidget(x + 50, clusterY + 110, backgroundWidth - 100, 6, Text.literal(""), 0);
        addDrawableChild(seekBar);
        // Input Gain Slider (0.0x - 3.0x -> 0% - 300%)
        double initialGainVal = currentInputGain / 3.0f;
        int initialPercentage = (int) (currentInputGain * 100);
        inputGainSlider = new InputGainSlider(x + 40, clusterY + 130, backgroundWidth - 80, 20,
                Text.literal("Input Gain: " + initialPercentage + "%"), initialGainVal);
        addDrawableChild(inputGainSlider);
        // Speaker Power Slider
        double initialSliderValue = (currentPower - 0.1f) / 9.9f;
        powerSlider = new PowerSlider(x + 40, clusterY + 155, backgroundWidth - 80, 20,
                Text.literal("Power: " + String.format("%.1f", currentPower)), initialSliderValue);
        addDrawableChild(powerSlider);
        // --- MIXER SLIDERS ---
        mixerSliders.clear();
        int colWidth = (backgroundWidth - 40) / 3;
        int startX = x + 20;
        int startY = y + 40;
        String[] types = {"sub", "mid", "line"};
        for (int col = 0; col < 3; col++) {
            int cx = startX + col * colWidth;
            // 6 sliders per column: Vol, EQ1, EQ2, EQ3, EQ4, EQ5
            for (int r = 0; r < 6; r++) {
                MixerSliderWidget slider;
                if (r > 0) {
                    slider = new MixerSliderWidget(cx + 10, startY + r * 22 + 15, colWidth - 45, 14, types[col], r);
                    QSliderWidget qSlider = new QSliderWidget(cx + 10 + colWidth - 40, startY + r * 22 + 15, 20, 14, types[col], r);
                    qSliders.add(qSlider);
                    addDrawableChild(qSlider);
                    qSlider.visible = false;
                } else {
                    slider = new MixerSliderWidget(cx + 10, startY + r * 22 + 15, colWidth - 20, 14, types[col], r);
                }
                mixerSliders.add(slider);
                addDrawableChild(slider);
                slider.visible = false;
            }
        }
        // Mid/Side Toggles
        int midSideY = startY + 6 * 22 + 20;
        midButton = new TransparentButton(x + (backgroundWidth / 2) - 60, midSideY, 50, 20, getMidButtonText(), button -> {
            boolean current = com.audiophilecraft.sound.AudioEngine.getInstance().isMidMuted();
            com.audiophilecraft.sound.AudioEngine.getInstance().setMidMuted(!current);
            button.setMessage(getMidButtonText());
        });
        sideButton = new TransparentButton(x + (backgroundWidth / 2) + 10, midSideY, 50, 20, getSideButtonText(), button -> {
            boolean current = com.audiophilecraft.sound.AudioEngine.getInstance().isSideMuted();
            com.audiophilecraft.sound.AudioEngine.getInstance().setSideMuted(!current);
            button.setMessage(getSideButtonText());
        });
        
        addDrawableChild(midButton);
        addDrawableChild(sideButton);
        midButton.visible = false;
        sideButton.visible = false;
        updateWidgetVisibility();
    }
    private Text getMidButtonText() {
        boolean muted = com.audiophilecraft.sound.AudioEngine.getInstance().isMidMuted();
        return Text.literal(muted ? "§cMID CUT" : "MID ON");
    }
    private Text getSideButtonText() {
        boolean muted = com.audiophilecraft.sound.AudioEngine.getInstance().isSideMuted();
        return Text.literal(muted ? "§cSIDE CUT" : "SIDE ON");
    }
    private void updateWidgetVisibility() {
        boolean showMain = !isMixerOpen && !isMapOpen;
        urlField.visible = showMain;
        seekBar.visible = showMain;
        inputGainSlider.visible = showMain;
        powerSlider.visible = showMain;
        playButton.visible = showMain;
        stopButton.visible = showMain;
        if (isMixerOpen || isMapOpen) {
            mixerButton.setX((width - 45) / 2);
            mixerButton.setY((height + backgroundHeight) / 2 - 25);
            mapButton.setX((width + 5) / 2);
            mapButton.setY((height + backgroundHeight) / 2 - 25);
        } else {
            // Restore origin position
            int x = (width - backgroundWidth) / 2;
            int y = (height - backgroundHeight) / 2;
            int clusterY = y + (backgroundHeight - 165) / 2;
            int buttonStartX = x + (backgroundWidth - 50) / 2;
            mixerButton.setX(buttonStartX + 58);
            mixerButton.setY(clusterY + 85);
            mapButton.setX(buttonStartX + 86);
            mapButton.setY(clusterY + 85);
        }
        for (MixerSliderWidget s : mixerSliders) {
            s.visible = isMixerOpen;
        }
        for (QSliderWidget s : qSliders) {
            s.visible = isMixerOpen;
        }
        if (midButton != null) midButton.visible = isMixerOpen;
        if (sideButton != null) sideButton.visible = isMixerOpen;
    }
    private MixerSliderWidget draggedMixerSlider = null;
    private QSliderWidget draggedQSlider = null;
    private void attemptStartPlaying() {
        String url = !activePlayUrl.isEmpty() ? activePlayUrl : urlField.getText().trim();
        PacketByteBuf buf = PacketByteBufs.create();
        if (!url.isEmpty()) {
            buf.writeInt(getHandOrdinal());
            buf.writeString(url);
            ClientPlayNetworking.send(ModMessages.C2S_PLAY_URL, buf);
        } else {
            buf.writeInt(getHandOrdinal());
            ClientPlayNetworking.send(ModMessages.C2S_REQUEST_PLAY, buf);
        }
        // Visual Loading Feedback Lock
        if (playButton != null)
            playButton.active = false;
        Thread t = new Thread(() -> {
            try {
                Thread.sleep(2500);
            } catch (Exception e) {
            }
            if (playButton != null) {
                playButton.active = true;
            }
        });
        t.setDaemon(true);
        t.start();
    }
    private void sendPowerUpdate(float power) {
        PacketByteBuf buf = PacketByteBufs.create();
        buf.writeInt(getHandOrdinal());
        buf.writeFloat(power);
        ClientPlayNetworking.send(ModMessages.C2S_UPDATE_POWER, buf);
    }
    private void sendInputGainUpdate(float gain) {
        PacketByteBuf buf = PacketByteBufs.create();
        buf.writeInt(getHandOrdinal());
        buf.writeFloat(gain);
        ClientPlayNetworking.send(ModMessages.C2S_UPDATE_INPUT_GAIN, buf);
    }
    public void updateSpeakerPower(float power) {
        if (System.currentTimeMillis() - lastInteractionTime < 500)
            return; // Block slider snap-backs
        currentPower = power;
        if (powerSlider != null) {
            double sliderVal = (power - 0.1f) / 9.9f;
            powerSlider.setSliderValue(Math.max(0.0, Math.min(sliderVal, 1.0)));
            powerSlider.setMessage(Text.literal("Power: " + String.format("%.1f", power)));
        }
    }
    public void updateInputGain(float gain) {
        if (System.currentTimeMillis() - lastInteractionTime < 500)
            return; // Block slider snap-backs
        currentInputGain = gain;
        if (inputGainSlider != null) {
            double sliderVal = gain / 3.0f;
            inputGainSlider.setSliderValue(Math.max(0.0, Math.min(sliderVal, 1.0)));
            int percentage = (int) (gain * 100);
            inputGainSlider.setMessage(Text.literal("Input Gain: " + percentage + "%"));
        }
    }
    @Override
    protected void drawBackground(DrawContext context, float delta, int mouseX, int mouseY) {
        // --- ANIMATE COLOR LERPING ---
        for (int i = 0; i < 3; i++) {
            currentColors[i] = lerpColor(currentColors[i], targetColors[i], 0.05f);
        }
        int startX = (width - backgroundWidth) / 2;
        int startY = (height - backgroundHeight) / 2;
        // --- DRAW PIXELATED MOVING GRADIENT (BATCHED FOR HIGH FPS) ---
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
        com.mojang.blaze3d.systems.RenderSystem.setShader(net.minecraft.client.render.GameRenderer::getPositionColorProgram);
        net.minecraft.client.render.Tessellator tessellator = net.minecraft.client.render.Tessellator.getInstance();
        net.minecraft.client.render.BufferBuilder bufferBuilder = tessellator.getBuffer();
        org.joml.Matrix4f matrix = context.getMatrices().peek().getPositionMatrix();
        // Start a single batch
        bufferBuilder.begin(net.minecraft.client.render.VertexFormat.DrawMode.QUADS, net.minecraft.client.render.VertexFormats.POSITION_COLOR);
        int pixelSize = 8; // User requested 8px pixelated look
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
                if (drawX + dw > startX + backgroundWidth)
                    dw = startX + backgroundWidth - drawX;
                if (drawY + dh > startY + backgroundHeight)
                    dh = startY + backgroundHeight - drawY;
                // Draw flat quad (same color for all 4 corners creates the sharp pixel look)
                bufferBuilder.vertex(matrix, drawX, drawY, 0).color(color).next();
                bufferBuilder.vertex(matrix, drawX, drawY + dh, 0).color(color).next();
                bufferBuilder.vertex(matrix, drawX + dw, drawY + dh, 0).color(color).next();
                bufferBuilder.vertex(matrix, drawX + dw, drawY, 0).color(color).next();
            }
        }
        
        // Draw all 3,200 pixels in ONE single draw call!
        tessellator.draw();
        com.mojang.blaze3d.systems.RenderSystem.disableBlend();
        // Synchronize adaptive theme shift IMMEDIATELY when the target colors change,
        // matching cross-fade speeds
        float targetThemeLuminance = isTargetBackgroundDark() ? 0.0f : 1.0f;
        themeState += (targetThemeLuminance - themeState) * 0.05f; // Match exactly with background currentColors 0.05f
                                                                   // speed!
        currentAdaptiveThemeColor = lerpColor(0xFFFFFFFF, 0xFF141414, themeState); // 0x141414 is an ultra-premium deep
                                                                                   // AMOLED dark
        // Düz dış çerçeve (adaptive, gölgesiz)
        context.fill(startX - 1, startY - 1, startX + backgroundWidth + 1, startY, currentAdaptiveThemeColor); // Üst
        context.fill(startX - 1, startY + backgroundHeight, startX + backgroundWidth + 1, startY + backgroundHeight + 1,
                currentAdaptiveThemeColor); // Alt
        context.fill(startX - 1, startY, startX, startY + backgroundHeight, currentAdaptiveThemeColor); // Sol
        context.fill(startX + backgroundWidth, startY, startX + backgroundWidth + 1, startY + backgroundHeight,
                currentAdaptiveThemeColor); // Sağ
    }
    @Override
    protected void drawForeground(DrawContext context, int mouseX, int mouseY) {
        // Envanter yazısını sildik (boş bıraktık)
    }
    private boolean isBackgroundDark() {
        return isTargetBackgroundDark();
    }
    private boolean isTargetBackgroundDark() {
        int r = 0, g = 0, b = 0;
        for (int i = 0; i < targetColors.length; i++) {
            r += (targetColors[i] >> 16) & 0xFF;
            g += (targetColors[i] >> 8) & 0xFF;
            b += targetColors[i] & 0xFF;
        }
        int avgR = r / targetColors.length;
        int avgG = g / targetColors.length;
        int avgB = b / targetColors.length;
        // Luminance formula
        double luminance = (0.299 * avgR + 0.587 * avgG + 0.114 * avgB) / 255.0;
        return luminance < 0.45; // slightly relaxed threshold
    }
    private String extractYouTubeId(String url) {
        if (url == null || url.isEmpty())
            return null;
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("(?<=v=|v\\/|vi=|vi\\/|youtu.be\\/|\\/v\\/|embed\\/)([a-zA-Z0-9_-]{11})").matcher(url);
        return m.find() ? m.group(1) : null;
    }
    private void fetchThumbnailColorsAsync(String videoId) {
        final long thisRequest = ++thumbnailFetchVersion;
        Thread t = new Thread(() -> {
            isFetchingThumbnail = true;
            try {
                if (videoId != null) {
                    java.awt.image.BufferedImage img = com.audiophilecraft.client.util.YouTubeThumbnailCache
                            .loadAndCache(videoId);
                    // Only apply colors if this is still the latest request (no newer song selected)
                    if (img != null && thisRequest == thumbnailFetchVersion) {
                        int[] dominants = getDominantColors(img);
                        targetColors[0] = 0xFF000000 | enhanceColor(dominants[0]);
                        targetColors[1] = 0xFF000000 | enhanceColor(dominants[1]);
                        targetColors[2] = 0xFF000000 | enhanceColor(dominants[2]);
                        targetColors[3] = 0xFF000000 | enhanceColor(dominants[0]);
                    }
                } else if (thisRequest == thumbnailFetchVersion) {
                    targetColors[0] = 0xFF333333;
                    targetColors[1] = 0xFF555555;
                    targetColors[2] = 0xFF444444;
                    targetColors[3] = 0xFF333333;
                }
            } catch (Exception e) {
                e.printStackTrace();
            } finally {
                isFetchingThumbnail = false;
            }
        });
        t.setDaemon(true);
        t.start();
    }
    private int[] getDominantColors(java.awt.image.BufferedImage img) {
        int[] bins = new int[4096]; // 16x16x16 RGB histogram
        long[] sumR = new long[4096];
        long[] sumG = new long[4096];
        long[] sumB = new long[4096];
        int step = Math.max(1, img.getWidth() / 32);
        for (int x = 0; x < img.getWidth(); x += step) {
            for (int y = 0; y < img.getHeight(); y += step) {
                int rgb = img.getRGB(x, y);
                int r = (rgb >> 16) & 0xFF;
                int g = (rgb >> 8) & 0xFF;
                int b = rgb & 0xFF;
                int rBin = r >> 4;
                int gBin = g >> 4;
                int bBin = b >> 4;
                int bin = (rBin << 8) | (gBin << 4) | bBin;
                bins[bin]++;
                sumR[bin] += r;
                sumG[bin] += g;
                sumB[bin] += b;
            }
        }
        int primaryBin = -1;
        int maxCount1 = 0;
        for (int i = 0; i < 4096; i++) {
            if (bins[i] > maxCount1) {
                maxCount1 = bins[i];
                primaryBin = i;
            }
        }
        int secondaryBin = -1;
        int maxCount2 = 0;
        for (int i = 0; i < 4096; i++) {
            if (i == primaryBin)
                continue;
            int dr = (i >> 8) - (primaryBin >> 8);
            int dg = ((i >> 4) & 0xF) - ((primaryBin >> 4) & 0xF);
            int db = (i & 0xF) - (primaryBin & 0xF);
            if (dr * dr + dg * dg + db * db < 16)
                continue; // Ensure distinct color
            if (bins[i] > maxCount2) {
                maxCount2 = bins[i];
                secondaryBin = i;
            }
        }
        // Strict distance fallback: if image is entirely monolithic color, relax
        // distance bounds to get best secondary tone
        if (secondaryBin == -1) {
            for (int i = 0; i < 4096; i++) {
                if (i == primaryBin)
                    continue;
                if (bins[i] > maxCount2) {
                    maxCount2 = bins[i];
                    secondaryBin = i;
                }
            }
        }
        int tertiaryBin = -1;
        int maxCount3 = 0;
        for (int i = 0; i < 4096; i++) {
            if (i == primaryBin || i == secondaryBin)
                continue;
            int dr1 = (i >> 8) - (primaryBin >> 8), dg1 = ((i >> 4) & 0xF) - ((primaryBin >> 4) & 0xF),
                    db1 = (i & 0xF) - (primaryBin & 0xF);
            int dr2 = secondaryBin != -1 ? (i >> 8) - (secondaryBin >> 8) : 0;
            int dg2 = secondaryBin != -1 ? ((i >> 4) & 0xF) - ((secondaryBin >> 4) & 0xF) : 0;
            int db2 = secondaryBin != -1 ? (i & 0xF) - (secondaryBin & 0xF) : 0;
            if (dr1 * dr1 + dg1 * dg1 + db1 * db1 < 16 || dr2 * dr2 + dg2 * dg2 + db2 * db2 < 16)
                continue;
            if (bins[i] > maxCount3) {
                maxCount3 = bins[i];
                tertiaryBin = i;
            }
        }
        // Final fallback: just get 3rd most abundant regardless of distance
        if (tertiaryBin == -1) {
            for (int i = 0; i < 4096; i++) {
                if (i == primaryBin || i == secondaryBin)
                    continue;
                if (bins[i] > maxCount3) {
                    maxCount3 = bins[i];
                    tertiaryBin = i;
                }
            }
        }
        if (secondaryBin == -1)
            secondaryBin = primaryBin;
        if (tertiaryBin == -1)
            tertiaryBin = secondaryBin;
        int c1 = primaryBin != -1 && bins[primaryBin] > 0
                ? (int) (sumR[primaryBin] / bins[primaryBin]) << 16 | (int) (sumG[primaryBin] / bins[primaryBin]) << 8
                        | (int) (sumB[primaryBin] / bins[primaryBin])
                : 0x222222;
        int c2 = secondaryBin != -1 && bins[secondaryBin] > 0 ? (int) (sumR[secondaryBin] / bins[secondaryBin]) << 16
                | (int) (sumG[secondaryBin] / bins[secondaryBin]) << 8 | (int) (sumB[secondaryBin] / bins[secondaryBin])
                : c1;
        int c3 = tertiaryBin != -1 && bins[tertiaryBin] > 0 ? (int) (sumR[tertiaryBin] / bins[tertiaryBin]) << 16
                | (int) (sumG[tertiaryBin] / bins[tertiaryBin]) << 8 | (int) (sumB[tertiaryBin] / bins[tertiaryBin])
                : c2;
        return new int[] { c1, c2, c3 };
    }
    private int enhanceColor(int c) {
        float[] hsb = java.awt.Color.RGBtoHSB((c >> 16) & 0xFF, (c >> 8) & 0xFF, c & 0xFF, null);
        // Spotify aesthetic: Keep natural hues and slightly boost them, don't fake neon
        // saturation.
        hsb[1] = Math.min(1.0f, hsb[1] * 1.3f);
        // Spotify backgrounds are moody. Deep rich colors instead of glowing blinding
        // light.
        hsb[2] = Math.max(0.25f, Math.min(0.85f, hsb[2] * 1.15f));
        return java.awt.Color.HSBtoRGB(hsb[0], hsb[1], hsb[2]) & 0xFFFFFF;
    }
    private int lerpColor(int c1, int c2, float t) {
        int r1 = (c1 >> 16) & 0xFF, g1 = (c1 >> 8) & 0xFF, b1 = c1 & 0xFF;
        int r2 = (c2 >> 16) & 0xFF, g2 = (c2 >> 8) & 0xFF, b2 = c2 & 0xFF;
        return 0xFF000000 | ((int) (r1 + (r2 - r1) * t) << 16) | ((int) (g1 + (g2 - g1) * t) << 8)
                | (int) (b1 + (b2 - b1) * t);
    }
    private int calculateBackgroundColor(float px, float py, float cx1, float cy1, float cx2, float cy2, float cx3,
            float cy3, float cx4, float cy4) {
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
        float r = ((currentColors[0] >> 16) & 0xFF) * w1 + ((currentColors[1] >> 16) & 0xFF) * w2
                + ((currentColors[2] >> 16) & 0xFF) * w3 + ((currentColors[3] >> 16) & 0xFF) * w4;
        float g = ((currentColors[0] >> 8) & 0xFF) * w1 + ((currentColors[1] >> 8) & 0xFF) * w2
                + ((currentColors[2] >> 8) & 0xFF) * w3 + ((currentColors[3] >> 8) & 0xFF) * w4;
        float b = (currentColors[0] & 0xFF) * w1 + (currentColors[1] & 0xFF) * w2 + (currentColors[2] & 0xFF) * w3
                + (currentColors[3] & 0xFF) * w4;
        return 0xFF000000 | ((int) r << 16) | ((int) g << 8) | (int) b;
    }
    private void drawRoundedRect(DrawContext context, int x1, int y1, int x2, int y2, float radius, int color) {
        // Inner fill
        context.fill(x1 + (int) radius, y1, x2 - (int) radius, y2, color);
        context.fill(x1, y1 + (int) radius, x1 + (int) radius, y2 - (int) radius, color);
        context.fill(x2 - (int) radius, y1 + (int) radius, x2, y2 - (int) radius, color);
        // Corners centers
        float cxTL = x1 + radius, cyTL = y1 + radius;
        float cxTR = x2 - radius, cyTR = y1 + radius;
        float cxBL = x1 + radius, cyBL = y2 - radius;
        float cxBR = x2 - radius, cyBR = y2 - radius;
        for (int y = 0; y < (int) radius; y++) {
            for (int x = 0; x < (int) radius; x++) {
                if ((x - radius) * (x - radius) + (y - radius) * (y - radius) <= radius * radius) {
                    context.fill(x1 + x, y1 + y, x1 + x + 1, y1 + y + 1, color); // TL
                    context.fill(x2 - 1 - x, y1 + y, x2 - x, y1 + y + 1, color); // TR
                    context.fill(x1 + x, y2 - 1 - y, x1 + x + 1, y2 - y, color); // BL
                    context.fill(x2 - 1 - x, y2 - 1 - y, x2 - x, y2 - y, color); // BR
                }
            }
        }
    }
    @Override
    protected void handledScreenTick() {
        super.handledScreenTick();
        if (urlField != null) {
            long now = System.currentTimeMillis();
            String currentText = urlField.getText().trim();
            if (isDropdownOpen && currentText.length() >= 3 && (now - lastTypedTime) > 500) {
                if (!currentText.equals(lastSearchedQuery) && !isSearching) {
                    lastSearchedQuery = currentText;
                    isSearching = true;
                    Thread t = new Thread(() -> {
                        java.util.List<com.audiophilecraft.util.YouTubeSearcher.SearchResult> res = com.audiophilecraft.util.YouTubeSearcher
                                .search(currentText);
                        searchResults = res;
                        isSearching = false;
                    });
                    t.setDaemon(true);
                    t.start();
                }
            }
        }
    }
    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        renderBackground(context);
        super.render(context, mouseX, mouseY, delta);
        if (isMapOpen) {
            int startX = (width - backgroundWidth) / 2;
            int startY = (height - backgroundHeight) / 2;
            java.util.List<net.minecraft.util.math.Vec3d> pc = com.audiophilecraft.sound.AdvancedAcousticScanner.getLastPointCloud();
            if (pc != null && !pc.isEmpty()) {
                // Background fill for the map area
                context.fill(startX + 10, startY + 30, startX + backgroundWidth - 10, startY + backgroundHeight - 30, 0xCC000000);
                java.util.List<net.minecraft.util.math.BlockPos> speakers = com.audiophilecraft.sound.AdvancedAcousticScanner.getLastSpeakers();
                PointCloudRenderer.render(context, startX, startY, backgroundWidth, backgroundHeight, pc, speakers);
                context.drawText(textRenderer, "REVERB HEATMAP", startX + 15, startY + 35, 0xFF00FF88, false);
                context.drawText(textRenderer, "RAYS: " + pc.size(), startX + 15, startY + 45, 0xFFFFFFFF, false);
                // Draw Tier info in the top right
                com.audiophilecraft.sound.AdvancedAcousticScanner.VenuePreset preset = com.audiophilecraft.sound.AudioEngine.getInstance().getVenuePreset();
                if (preset != null && preset.tierName != null) {
                    String tierStr = preset.tierName;
                    int textW = textRenderer.getWidth(tierStr);
                    context.drawText(textRenderer, tierStr, startX + backgroundWidth - 15 - textW, startY + 35, 0xFFFFDD00, false);
                }
                // Draw Sabine Volume in the bottom right
                com.audiophilecraft.sound.AdvancedAcousticScanner.VenueDescriptor desc = com.audiophilecraft.sound.AudioEngine.getInstance().getStoredVenueDescriptor();
                if (desc != null) {
                    String volStr = "VOLUME: " + (int) desc.trueVolume + " m3";
                    int volW = textRenderer.getWidth(volStr);
                    context.drawText(textRenderer, volStr, startX + backgroundWidth - 15 - volW, startY + backgroundHeight - 45, 0xFF00FFFF, false);
                }
            } else {
                context.drawText(textRenderer, "NO SCAN DATA. PLAY A TRACK FIRST.", startX + 20, startY + backgroundHeight / 2, 0xFFFF5555, false);
            }
        } else if (isMixerOpen) {
            // --- DRAW MIXER PANEL COLUMNS ---
            int startX = (width - backgroundWidth) / 2 + 20;
            int startY = (height - backgroundHeight) / 2 + 45;
            int colWidth = (backgroundWidth - 40) / 3;
            String[] labels = {"SUBWOOFER", "MID-RANGE", "LINE ARRAY"};
            
            context.getMatrices().push();
            context.getMatrices().scale(1.2f, 1.2f, 1.0f);
            for (int col = 0; col < 3; col++) {
                int cx = startX + col * colWidth;
                int textW = textRenderer.getWidth(labels[col]);
                int drawX = (int) ((cx + (colWidth - textW * 1.2f) / 2) / 1.2f);
                int drawY = (int) ((startY - 15) / 1.2f);
                context.drawText(textRenderer, labels[col], drawX, drawY, currentAdaptiveThemeColor, false);
            }
            context.getMatrices().pop();
            
            // Draw subtle column dividers
            for (int col = 1; col < 3; col++) {
                int cx = startX + col * colWidth;
                context.fill(cx - 2, startY - 10, cx - 1, startY + 110, currentAdaptiveThemeColor & 0x44FFFFFF);
            }
        } else {
            // --- DRAW TRACK INFO PANEL ---
            if (currentParsedVideoId != null) {
                int panelX = urlField.getX();
            int panelY = urlField.getY() + 25;
            int panelWidth = urlField.getWidth();
            // Thumbnail padding bounds
            int thumbSize = 64; // Increased from 48px to 64px for a much more prominent album cover
            int thumbX = panelX;
            int thumbY = panelY;
            Identifier thumbId = com.audiophilecraft.client.util.YouTubeThumbnailCache
                    .getIdentifier(currentParsedVideoId);
            if (thumbId != null) {
                // The loaded thumbnail cache is now pre-processed fully via Java2D
                // Its native size is 270x270. The 180x180 thumbnail sits strictly in the center
                // with 45px padding
                // At a scaled size of 64px, the padding is exactly 16px on all sides.
                RenderSystem.enableBlend();
                context.drawTexture(thumbId, thumbX - 16, thumbY - 16, 96, 96, 0, 0, 270, 270, 270, 270);
                RenderSystem.disableBlend();
            } else {
                // Loading fallback
                drawRoundedRect(context, thumbX, thumbY, thumbX + thumbSize, thumbY + thumbSize, 8.0f, 0xFF222222);
            }
            // Typography Rendering
            int textX = thumbX + thumbSize + 16;
            String displayTitle = !activeDisplayTitle.isEmpty() ? activeDisplayTitle
                    : (!activePlayUrl.isEmpty() ? activePlayUrl : "Unknown Track");
            String channelName = !activeChannelName.isEmpty() ? activeChannelName : "YouTube";
            int maxTextWidth = panelWidth - (thumbSize + 24);
            int scaledTitleWidthPx = (int) (textRenderer.getWidth(displayTitle) * 1.6f);
            
            // --- Marquee scroll logic (Continuous Loop) ---
            float titleScrollPx = 0f;
            int gapPx = 64; // Distance between the looping texts
            float fullCyclePx = scaledTitleWidthPx + gapPx;
            if (scaledTitleWidthPx > maxTextWidth) {
                // Move continuously at 50 pixels per second
                float scrollDurationMs = (fullCyclePx / 50.0f * 1000f);
                long timeInCycle = net.minecraft.util.Util.getMeasuringTimeMs() % (long) scrollDurationMs;
                titleScrollPx = (timeInCycle / scrollDurationMs) * fullCyclePx;
            }
            // --- End marquee ---
            // Title rendering (clipped + scrolled)
            int titleClipX2 = textX + maxTextWidth;
            context.enableScissor(textX, panelY, titleClipX2, panelY + thumbSize); // full height clipping
            
            context.getMatrices().push();
            // Smooth float sub-pixel translation to fix 30fps jitter
            context.getMatrices().translate(textX - titleScrollPx, panelY + 12, 0);
            context.getMatrices().scale(1.6f, 1.6f, 1.0f);
            
            context.drawText(textRenderer, displayTitle, 0, 0, 0xFFFFFFFF, true);
            if (scaledTitleWidthPx > maxTextWidth) {
                // Draw the second copy seamlessly looping
                context.drawText(textRenderer, displayTitle, (int)(fullCyclePx / 1.6f), 0, 0xFFFFFFFF, true);
            }
            
            context.getMatrices().pop();
            context.disableScissor();

            // Channel Name (static, clamped to width)
            context.getMatrices().push();
            context.getMatrices().scale(1.1f, 1.1f, 1.0f);
            String trimmedChannel = textRenderer.trimToWidth(channelName, (int) (maxTextWidth / 1.1f));
            int channelDrawX = (int) (textX / 1.1f);
            int channelDrawY = (int) ((panelY + 42) / 1.1f);
            context.drawText(textRenderer, trimmedChannel, channelDrawX, channelDrawY, 0xFFAAAAAA, false);
            context.getMatrices().pop();
            }
            // Draw Search Results Dropdown Overlay
            if (isDropdownOpen && urlField != null && urlField.isFocused()) {
                context.getMatrices().push();
            context.getMatrices().translate(0.0f, 0.0f, 300.0f); // Massive Z-index to cover track info completely
            int dropX = urlField.getX() - 5;
            int dropY = urlField.getY() + urlField.getHeight() + 1; // Anchor below input
            int dropWidth = urlField.getWidth() + 10;
            int itemHeight = 28;
            if (isSearching) {
                context.fill(dropX, dropY, dropX + dropWidth, dropY + 20, 0xDD000000);
                context.drawText(textRenderer, "Searching...", dropX + 5, dropY + 6, 0xFFAAAAAA, false);
                context.fill(dropX, dropY, dropX + dropWidth, dropY + 1, currentAdaptiveThemeColor);
                context.fill(dropX, dropY + 20, dropX + dropWidth, dropY + 21, currentAdaptiveThemeColor);
                context.fill(dropX, dropY, dropX + 1, dropY + 20, currentAdaptiveThemeColor);
                context.fill(dropX + dropWidth - 1, dropY, dropX + dropWidth, dropY + 20, currentAdaptiveThemeColor);
            } else if (!searchResults.isEmpty()) {
                int totalHeight = searchResults.size() * itemHeight;
                // Deeper background color for combobox (nearly opaque) allows no bleed
                context.fill(dropX, dropY, dropX + dropWidth, dropY + totalHeight, 0xFA050505);
                for (int i = 0; i < searchResults.size(); i++) {
                    com.audiophilecraft.util.YouTubeSearcher.SearchResult res = searchResults.get(i);
                    int itemY = dropY + i * itemHeight;
                    if (mouseX >= dropX && mouseX <= dropX + dropWidth && mouseY >= itemY
                            && mouseY < itemY + itemHeight) {
                        context.fill(dropX, itemY, dropX + dropWidth, itemY + itemHeight, 0x44FFFFFF); // Hover
                    }
                    String title = textRenderer.trimToWidth(res.title, dropWidth - 10);
                    context.drawText(textRenderer, title, dropX + 5, itemY + 4, 0xFFFFFFFF, false);
                    String subtitle = textRenderer.trimToWidth(res.channel + " • " + res.duration, dropWidth - 10);
                    context.drawText(textRenderer, subtitle, dropX + 5, itemY + 16, 0xFFAAAAAA, false);
                }
                // Adaptive Dropdown Border
                context.fill(dropX, dropY, dropX + dropWidth, dropY + 1, currentAdaptiveThemeColor);
                context.fill(dropX, dropY + totalHeight, dropX + dropWidth, dropY + totalHeight + 1,
                        currentAdaptiveThemeColor);
                context.fill(dropX, dropY, dropX + 1, dropY + totalHeight, currentAdaptiveThemeColor);
                context.fill(dropX + dropWidth - 1, dropY, dropX + dropWidth, dropY + totalHeight,
                        currentAdaptiveThemeColor);
            }
            context.getMatrices().pop();
            } // Close dropdown if
        } // Close if(!isMixerOpen) else block
        // --- DRAW LOADING BAR ANIAMTION --- Z-Index Fix: Drawn above dropdowns seamlessly
        if ((isSearching || isFetchingThumbnail) && urlField != null) {
            int barWidth = 24;
            int rightX = urlField.getX() + urlField.getWidth() - 5 - barWidth;
            // Place it slightly to the right of the search box floating cleanly
            int barY = urlField.getY() + (urlField.getHeight() / 2);
            float spin = (float) (Math.sin(System.currentTimeMillis() / 200.0) + 1.0) / 2.0f;
            int dashX = rightX + (int) (spin * (barWidth - 6));
            // Faded track
            context.fill(rightX, barY, rightX + barWidth, barY + 1,
                    (currentAdaptiveThemeColor & 0xFFFFFF) | 0x44000000);
            // Sliding head
            context.fill(dashX, barY, dashX + 6, barY + 1, currentAdaptiveThemeColor);
        }
        drawMouseoverTooltip(context, mouseX, mouseY);
    }
    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        // Mixer slider click handling
        if (isMixerOpen && button == 0) {
            for (MixerSliderWidget s : mixerSliders) {
                if (s.visible && s.isMouseOver(mouseX, mouseY)) {
                    draggedMixerSlider = s;
                    s.onClick(mouseX, mouseY);
                    return true;
                }
            }
            for (QSliderWidget s : qSliders) {
                if (s.visible && s.isMouseOver(mouseX, mouseY)) {
                    draggedQSlider = s;
                    s.onClick(mouseX, mouseY);
                    return true;
                }
            }
            if (mixerButton.visible && mixerButton.isMouseOver(mouseX, mouseY)) {
                mixerButton.onClick(mouseX, mouseY);
                return true;
            }
            if (midButton != null && midButton.visible && midButton.isMouseOver(mouseX, mouseY)) {
                midButton.onClick(mouseX, mouseY);
                return true;
            }
            if (sideButton != null && sideButton.visible && sideButton.isMouseOver(mouseX, mouseY)) {
                sideButton.onClick(mouseX, mouseY);
                return true;
            }
            return true;
        }
        if (isDropdownOpen && urlField != null && urlField.isFocused() && !searchResults.isEmpty()) {
            int dropX = urlField.getX() - 5;
            int dropY = urlField.getY() + urlField.getHeight() + 1;
            int dropWidth = urlField.getWidth() + 10;
            int itemHeight = 28;
            if (mouseX >= dropX && mouseX <= dropX + dropWidth && mouseY >= dropY
                    && mouseY <= dropY + searchResults.size() * itemHeight) {
                for (int i = 0; i < searchResults.size(); i++) {
                    int itemY = dropY + i * itemHeight;
                    if (mouseY >= itemY && mouseY < itemY + itemHeight) {
                        com.audiophilecraft.util.YouTubeSearcher.SearchResult res = searchResults.get(i);
                        activePlayUrl = "https://youtube.com/watch?v=" + res.videoId;
                        activeDisplayTitle = res.title;
                        activeChannelName = res.channel;
                        isDropdownOpen = false;
                        urlField.setText(res.title);
                        urlField.setCursorToEnd();
                        // Force asynchronous background color and thumbnail refresh for the newly selected track!
                        currentParsedVideoId = res.videoId;
                        fetchThumbnailColorsAsync(res.videoId);
                        attemptStartPlaying();
                        return true;
                    }
                }
            } else if (mouseY > dropY) {
                isDropdownOpen = false; // clicked completely outside results list
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }
    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double deltaX, double deltaY) {
        // Mixer slider drag
        if (draggedMixerSlider != null && button == 0) {
            draggedMixerSlider.setSliderValue(
                (mouseX - (double) (draggedMixerSlider.getX() + 4)) / (double) (draggedMixerSlider.getWidth() - 8)
            );
            return true;
        }
        if (draggedQSlider != null && button == 0) {
            draggedQSlider.setSliderValue(
                (mouseX - (double) (draggedQSlider.getX() + 2)) / (double) (draggedQSlider.getWidth() - 4)
            );
            return true;
        }
        if (powerSlider != null && this.getFocused() == powerSlider) {
            powerSlider.mouseDragged(mouseX, mouseY, button, deltaX, deltaY);
            return true;
        }
        if (inputGainSlider != null && this.getFocused() == inputGainSlider) {
            inputGainSlider.mouseDragged(mouseX, mouseY, button, deltaX, deltaY);
            return true;
        }
        if (seekBar != null && this.getFocused() == seekBar) {
            seekBar.mouseDragged(mouseX, mouseY, button, deltaX, deltaY);
            return true;
        }
        return super.mouseDragged(mouseX, mouseY, button, deltaX, deltaY);
    }
    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        if (draggedMixerSlider != null) {
            draggedMixerSlider = null;
            return true;
        }
        if (draggedQSlider != null) {
            draggedQSlider = null;
            return true;
        }
        if (powerSlider != null && this.getFocused() == powerSlider) {
            powerSlider.onRelease(mouseX, mouseY);
            this.setFocused(null);
            return true;
        }
        if (inputGainSlider != null && this.getFocused() == inputGainSlider) {
            inputGainSlider.onRelease(mouseX, mouseY);
            this.setFocused(null);
            return true;
        }
        if (seekBar != null && this.getFocused() == seekBar) {
            seekBar.onRelease(mouseX, mouseY);
            this.setFocused(null);
            return true;
        }
        return super.mouseReleased(mouseX, mouseY, button);
    }
    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (urlField != null && urlField.isFocused()) {
            if (keyCode == 256) { // ESC key
                urlField.setFocused(false);
                return true;
            }
            return urlField.keyPressed(keyCode, scanCode, modifiers);
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }
    private class TransparentButton extends net.minecraft.client.gui.widget.ButtonWidget {
        public TransparentButton(int x, int y, int width, int height, Text message, PressAction onPress) {
            super(x, y, width, height, message, onPress, supplier -> supplier.get());
        }
        @Override
        public void renderButton(DrawContext context, int mouseX, int mouseY, float delta) {
            int fillAlpha = isHovered() ? (isBackgroundDark() ? 0x44FFFFFF : 0x44000000) : 0x00000000;
            // Border
            context.fill(getX(), getY(), getX() + width, getY() + 1, currentAdaptiveThemeColor);
            context.fill(getX(), getY() + height - 1, getX() + width, getY() + height, currentAdaptiveThemeColor);
            context.fill(getX(), getY(), getX() + 1, getY() + height, currentAdaptiveThemeColor);
            context.fill(getX() + width - 1, getY(), getX() + width, getY() + height, currentAdaptiveThemeColor);
            // Hover fill
            if (fillAlpha != 0)
                context.fill(getX() + 1, getY() + 1, getX() + width - 1, getY() + height - 1, fillAlpha);
            // Text
            int textWidth = textRenderer.getWidth(getMessage());
            context.drawText(textRenderer, getMessage(), getX() + (width - textWidth) / 2, getY() + (height - 8) / 2,
                    currentAdaptiveThemeColor, false);
        }
    }
    private class PowerSlider extends SliderWidget {
        public PowerSlider(int x, int y, int width, int height, Text text, double value) {
            super(x, y, width, height, text, value);
            updateMessage();
        }
        @Override
        protected void updateMessage() {
            float val = 0.1f + (float) this.value * 9.9f;
            this.setMessage(Text.literal("Power: " + String.format("%.1f", val)));
        }
        @Override
        protected void applyValue() {
            lastInteractionTime = System.currentTimeMillis();
            currentPower = 0.1f + (float) this.value * 9.9f;
        }
        @Override
        public void onRelease(double mouseX, double mouseY) {
            super.onRelease(mouseX, mouseY);
            sendPowerUpdate(currentPower);
            this.setFocused(false); // Remove stuck active animation
        }
        @Override
        public boolean mouseDragged(double mouseX, double mouseY, int button, double deltaX, double deltaY) {
            double val = (mouseX - (double) (this.getX() + 4)) / (double) (this.width - 8);
            this.value = Math.max(0.0, Math.min(val, 1.0));
            this.applyValue();
            updateMessage();
            return true;
        }
        public void setSliderValue(double value) {
            this.value = value;
            updateMessage();
        }
        @Override
        public void renderButton(DrawContext context, int mouseX, int mouseY, float delta) {
            // Stroke track
            context.fill(getX(), getY(), getX() + width, getY() + 1, currentAdaptiveThemeColor);
            context.fill(getX(), getY() + height - 1, getX() + width, getY() + height, currentAdaptiveThemeColor);
            context.fill(getX(), getY(), getX() + 1, getY() + height, currentAdaptiveThemeColor);
            context.fill(getX() + width - 1, getY(), getX() + width, getY() + height, currentAdaptiveThemeColor);
            // Knob
            int knobWidth = 8;
            int knobX = getX() + (int) (this.value * (double) (width - knobWidth));
            context.fill(knobX, getY(), knobX + knobWidth, getY() + height, currentAdaptiveThemeColor);
            if (isHovered()) {
                context.fill(knobX, getY(), knobX + knobWidth, getY() + height,
                        isBackgroundDark() ? 0x66FFFFFF : 0x66000000);
            }
            int textWidth = textRenderer.getWidth(getMessage());
            context.drawText(textRenderer, getMessage(), getX() + (width - textWidth) / 2, getY() + (height - 8) / 2,
                    currentAdaptiveThemeColor, false);
        }
    }
    private class InputGainSlider extends SliderWidget {
        public InputGainSlider(int x, int y, int width, int height, Text text, double value) {
            super(x, y, width, height, text, value);
            updateMessage();
        }
        @Override
        protected void updateMessage() {
            float val = (float) this.value * 3.0f;
            int percentage = (int) (val * 100);
            this.setMessage(Text.literal("Input Gain: " + percentage + "%"));
        }
        @Override
        protected void applyValue() {
            lastInteractionTime = System.currentTimeMillis();
            currentInputGain = (float) this.value * 3.0f;
        }
        @Override
        public void onRelease(double mouseX, double mouseY) {
            super.onRelease(mouseX, mouseY);
            sendInputGainUpdate(currentInputGain);
            this.setFocused(false); // Remove stuck active animation
        }
        @Override
        public boolean mouseDragged(double mouseX, double mouseY, int button, double deltaX, double deltaY) {
            double val = (mouseX - (double) (this.getX() + 4)) / (double) (this.width - 8);
            this.value = Math.max(0.0, Math.min(val, 1.0));
            this.applyValue();
            updateMessage();
            return true;
        }
        public void setSliderValue(double value) {
            this.value = value;
            updateMessage();
        }
        @Override
        public void renderButton(DrawContext context, int mouseX, int mouseY, float delta) {
            // Stroke track
            context.fill(getX(), getY(), getX() + width, getY() + 1, currentAdaptiveThemeColor);
            context.fill(getX(), getY() + height - 1, getX() + width, getY() + height, currentAdaptiveThemeColor);
            context.fill(getX(), getY(), getX() + 1, getY() + height, currentAdaptiveThemeColor);
            context.fill(getX() + width - 1, getY(), getX() + width, getY() + height, currentAdaptiveThemeColor);
            // Knob
            int knobWidth = 8;
            int knobX = getX() + (int) (this.value * (double) (width - knobWidth));
            context.fill(knobX, getY(), knobX + knobWidth, getY() + height, currentAdaptiveThemeColor);
            if (isHovered()) {
                context.fill(knobX, getY(), knobX + knobWidth, getY() + height,
                        isBackgroundDark() ? 0x66FFFFFF : 0x66000000);
            }
            int textWidth = textRenderer.getWidth(getMessage());
            context.drawText(textRenderer, getMessage(), getX() + (width - textWidth) / 2, getY() + (height - 8) / 2,
                    currentAdaptiveThemeColor, false);
        }
    }
    private class SeekBarWidget extends net.minecraft.client.gui.widget.SliderWidget {
        public SeekBarWidget(int x, int y, int width, int height, Text text, double value) {
            super(x, y, width, height, text, value);
            updateMessage();
        }
        @Override
        protected void updateMessage() {
            double total = com.audiophilecraft.sound.AudioEngine.getInstance().getTotalPlaybackDuration();
            if (total <= 0) {
                this.setMessage(Text.literal("00:00 / 00:00"));
                return;
            }
            double target = this.value * total;
            String curStr = String.format("%02d:%02d", (int) target / 60, (int) target % 60);
            String totStr = String.format("%02d:%02d", (int) total / 60, (int) total % 60);
            this.setMessage(Text.literal(curStr + " / " + totStr));
        }
        @Override
        protected void applyValue() {
            // Value actively tracks UI knob, avoid spamming packets during drag.
            lastInteractionTime = System.currentTimeMillis();
        }
        @Override
        public void onRelease(double mouseX, double mouseY) {
            super.onRelease(mouseX, mouseY);
            double total = com.audiophilecraft.sound.AudioEngine.getInstance().getTotalPlaybackDuration();
            if (total > 0) {
                float targetTime = (float) (this.value * total);
                // CLIENT-SIDE PREDICTION: Instantly seek locally to achieve 0ms input latency
                // before suffering from the multiplayer server Network Round-Trip time (~150ms
                // delay/glitch).
                com.audiophilecraft.sound.AudioEngine.getInstance().seek(targetTime);
                net.minecraft.network.PacketByteBuf buf = net.fabricmc.fabric.api.networking.v1.PacketByteBufs.create();
                buf.writeFloat(targetTime);
                net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking
                        .send(com.audiophilecraft.network.ModMessages.C2S_SEEK_TRACK, buf);
            }
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
        public void autoUpdate() {
            if (AmplifierScreen.this.getFocused() != this) {
                double total = com.audiophilecraft.sound.AudioEngine.getInstance().getTotalPlaybackDuration();
                double current = com.audiophilecraft.sound.AudioEngine.getInstance().getCurrentPlaybackTime();
                if (total > 0) {
                    this.value = Math.max(0.0, Math.min(current / total, 1.0));
                    updateMessage();
                }
            }
        }
        @Override
        public void renderButton(net.minecraft.client.gui.DrawContext context, int mouseX, int mouseY, float delta) {
            autoUpdate();
            context.fill(getX(), getY() + height / 2 - 1, getX() + width, getY() + height / 2 + 1,
                    currentAdaptiveThemeColor & 0x77FFFFFF);
            int knobWidth = 4;
            int knobX = getX() + (int) (this.value * (double) (width - knobWidth));
            context.fill(getX(), getY() + height / 2 - 1, knobX, getY() + height / 2 + 1, currentAdaptiveThemeColor);
            if (isHovered() || AmplifierScreen.this.getFocused() == this) {
                context.fill(knobX - 2, getY() - 2, knobX + knobWidth + 2, getY() + height + 2,
                        currentAdaptiveThemeColor);
                context.fill(knobX - 2, getY() - 2, knobX + knobWidth + 2, getY() + height + 2,
                        isBackgroundDark() ? 0x66FFFFFF : 0x66000000);
            }
            int textWidth = textRenderer.getWidth(getMessage());
            context.drawText(textRenderer, getMessage(), getX() + (width - textWidth) / 2, getY() + height + 4,
                    currentAdaptiveThemeColor, false);
        }
    }
    private class MixerSliderWidget extends net.minecraft.client.gui.widget.SliderWidget {
        private final String speakerType;
        private final int typeIndex; // 0=Volume, 1..5=EQ Bands
        private long lastClickTime = 0;
        
        public MixerSliderWidget(int x, int y, int width, int height, String speakerType, int typeIndex) {
            super(x, y, width, height, net.minecraft.text.Text.empty(), 0.5);
            this.speakerType = speakerType;
            this.typeIndex = typeIndex;
            // Load current value from AudioEngine
            if (typeIndex == 0) {
                this.value = com.audiophilecraft.sound.AudioEngine.getInstance().getMixerGain(speakerType);
            } else {
                float db = com.audiophilecraft.sound.AudioEngine.getInstance().getEqDb(speakerType, typeIndex - 1);
                this.value = (db + 12.0f) / 24.0f; // map -12..12 -> 0..1
            }
            updateMessage();
        }
        private String getPrefix() {
            if (typeIndex == 0) return "Vol: ";
            if ("sub".equals(speakerType)) {
                if (typeIndex == 1) return "30Hz: ";
                if (typeIndex == 2) return "50Hz: ";
                if (typeIndex == 3) return "70Hz: ";
                if (typeIndex == 4) return "90Hz: ";
                if (typeIndex == 5) return "110Hz: ";
            } else if ("mid".equals(speakerType)) {
                if (typeIndex == 1) return "250Hz: ";
                if (typeIndex == 2) return "500Hz: ";
                if (typeIndex == 3) return "1kHz: ";
                if (typeIndex == 4) return "2kHz: ";
                if (typeIndex == 5) return "4kHz: ";
            } else if ("line".equals(speakerType)) {
                if (typeIndex == 1) return "250Hz: ";
                if (typeIndex == 2) return "1kHz: ";
                if (typeIndex == 3) return "4kHz: ";
                if (typeIndex == 4) return "8kHz: ";
                if (typeIndex == 5) return "14kHz: ";
            }
            return "EQ: ";
        }
        @Override
        protected void updateMessage() {
            String prefix = getPrefix();
            if (typeIndex == 0) {
                this.setMessage(net.minecraft.text.Text.literal(prefix + (int)(this.value * 100) + "%"));
            } else {
                float db = (float) (this.value * 24.0 - 12.0);
                this.setMessage(net.minecraft.text.Text.literal(prefix + String.format("%.1f dB", db)));
            }
        }
        @Override
        protected void applyValue() {
            if (typeIndex == 0) {
                com.audiophilecraft.sound.AudioEngine.getInstance().setMixerGain(speakerType, (float) this.value);
            } else {
                float db = (float) (this.value * 24.0 - 12.0);
                com.audiophilecraft.sound.AudioEngine.getInstance().setEqDb(speakerType, typeIndex - 1, db);
            }
        }
        public void setSliderValue(double raw) {
            this.value = Math.max(0.0, Math.min(raw, 1.0));
            this.applyValue();
            this.updateMessage();
        }
        // Double-click reset: any two rapid clicks on the slider resets to default
        @Override
        public void onClick(double mouseX, double mouseY) {
            long now = System.currentTimeMillis();
            if (now - lastClickTime < 250) {
                // Double click = reset to default
                this.value = (this.typeIndex == 0) ? 1.0 : 0.5;
                this.applyValue();
                this.updateMessage();
                lastClickTime = 0;
                return;
            }
            lastClickTime = now;
            super.onClick(mouseX, mouseY);
        }
        @Override
        public void onRelease(double mouseX, double mouseY) {
            super.onRelease(mouseX, mouseY);
            this.setFocused(false);
        }
        @Override
        public void renderButton(net.minecraft.client.gui.DrawContext context, int mouseX, int mouseY, float delta) {
            // Minimal, clean aesthetic for the mixer
            context.fill(getX(), getY() + height / 2 - 1, getX() + width, getY() + height / 2 + 1, currentAdaptiveThemeColor & 0x77FFFFFF);
            if (typeIndex > 0) { // EQ center line indicator
                context.fill(getX() + width / 2, getY(), getX() + width / 2 + 1, getY() + height, currentAdaptiveThemeColor & 0x77FFFFFF);
            }
            int knobWidth = 6; // Keep knob elegant
            int knobX = getX() + (int) (this.value * (double) (width - knobWidth));
            
            // Allow dragging visual feedback across entire click box height
            context.fill(knobX, getY(), knobX + knobWidth, getY() + height, currentAdaptiveThemeColor);
            
            if (isHovered() || this.isFocused()) {
                context.fill(knobX - 1, getY() - 1, knobX + knobWidth + 1, getY() + height + 1, isBackgroundDark() ? 0x66FFFFFF : 0x66000000);
            }
            context.getMatrices().push();
            context.getMatrices().scale(0.85f, 0.85f, 1.0f);
            int textWidth = textRenderer.getWidth(getMessage());
            int txtX = (int)((getX() + (width - (textWidth * 0.85f)) / 2) / 0.85f);
            int txtY = (int)((getY() - 8) / 0.85f);
            context.drawText(textRenderer, getMessage(), txtX, txtY, currentAdaptiveThemeColor, false);
            context.getMatrices().pop();
        }
    }
    private class QSliderWidget extends net.minecraft.client.gui.widget.SliderWidget {
        private final String speakerType;
        private final int typeIndex;
        private long lastClickTime = 0;
        public QSliderWidget(int x, int y, int width, int height, String speakerType, int typeIndex) {
            super(x, y, width, height, net.minecraft.text.Text.empty(), 0.5);
            this.speakerType = speakerType;
            this.typeIndex = typeIndex;
            float q = com.audiophilecraft.sound.AudioEngine.getInstance().getEqQ(speakerType, typeIndex - 1);
            // 0.0 - 0.5 Slider -> 0.1 - 1.0 Q
            // 0.5 - 1.0 Slider -> 1.0 - 10.0 Q
            if (q <= 1.0f) {
                this.value = (q - 0.1f) / 1.8f;
            } else {
                this.value = 0.5f + (q - 1.0f) / 18.0f;
            }
            updateMessage();
        }
        @Override
        protected void updateMessage() {
            float q;
            if (this.value <= 0.5) {
                q = 0.1f + (float) (this.value * 1.8f);
            } else {
                q = 1.0f + (float) ((this.value - 0.5) * 18.0f);
            }
            this.setMessage(net.minecraft.text.Text.literal("Q:" + String.format("%.1f", q)));
        }
        @Override
        protected void applyValue() {
            float q;
            if (this.value <= 0.5) {
                q = 0.1f + (float) (this.value * 1.8f);
            } else {
                q = 1.0f + (float) ((this.value - 0.5) * 18.0f);
            }
            com.audiophilecraft.sound.AudioEngine.getInstance().setEqQ(speakerType, typeIndex - 1, q);
        }
        public void setSliderValue(double raw) {
            this.value = Math.max(0.0, Math.min(raw, 1.0));
            this.applyValue();
            this.updateMessage();
        }
        @Override
        public void onClick(double mouseX, double mouseY) {
            long now = System.currentTimeMillis();
            if (now - lastClickTime < 250) {
                // Double click = reset to default 1.0 (which is exactly at value 0.5 now)
                this.value = 0.5;
                this.applyValue();
                this.updateMessage();
                lastClickTime = 0;
                return;
            }
            lastClickTime = now;
            super.onClick(mouseX, mouseY);
        }
        @Override
        public void renderButton(net.minecraft.client.gui.DrawContext context, int mouseX, int mouseY, float delta) {
            // Very compact style
            context.fill(getX(), getY() + height / 2 - 1, getX() + width, getY() + height / 2 + 1, currentAdaptiveThemeColor & 0x77FFFFFF);
            int knobWidth = 4;
            int knobX = getX() + (int) (this.value * (double) (width - knobWidth));
            context.fill(knobX, getY() + 2, knobX + knobWidth, getY() + height - 2, currentAdaptiveThemeColor);
            if (isHovered() || this.isFocused()) {
                context.fill(knobX - 1, getY() + 1, knobX + knobWidth + 1, getY() + height - 1, isBackgroundDark() ? 0x66FFFFFF : 0x66000000);
            }
            context.getMatrices().push();
            context.getMatrices().scale(0.65f, 0.65f, 1.0f);
            int textWidth = textRenderer.getWidth(getMessage());
            int txtX = (int)((getX() + (width - (textWidth * 0.65f)) / 2) / 0.65f);
            int txtY = (int)((getY() - 6) / 0.65f);
            context.drawText(textRenderer, getMessage(), txtX, txtY, currentAdaptiveThemeColor, false);
            context.getMatrices().pop();
        }
    }
}
