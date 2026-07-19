package com.audiophilecraft.sound;

import static org.lwjgl.openal.AL10.*;
import static org.lwjgl.openal.AL11.*;
import static org.lwjgl.openal.EXTEfx.*;

import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import net.minecraft.client.MinecraftClient;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;
import org.lwjgl.openal.ALC10;
import org.lwjgl.openal.ALCCapabilities;
import org.lwjgl.openal.SOFTHRTF;

public class AudioEngine {
    private static AudioEngine INSTANCE;

    // Active playback session
    private final java.util.Map<java.util.UUID, PlaybackSession> sessions =
            new java.util.concurrent.ConcurrentHashMap<>();
    private java.util.UUID activeSessionId = null;

    // Listener state (delegated to ListenerController)
    private final ListenerController listener = ListenerController.getInstance();
    // (kept for gradual migration)
    private volatile Vec3d listenerPos = Vec3d.ZERO;
    private volatile Vec3d smoothedListenerPos = Vec3d.ZERO;
    // Global Pause State

    // Seek Atomicity Guard â€” prevents audio thread from feeding sources mid-seek
    // getActiveSession().isSeeking() in PlaybackSession

    private final AudioEffectsController effects = new AudioEffectsController();
    private final ReverbBusAllocator reverbBusAllocator = new ReverbBusAllocator(effects);
    private final AudioPlaybackController playback = new AudioPlaybackController(this, sessions, effects);

    // Streaming System

    // Time Tracking
    // read by audio thread
    static final double BUFFER_LOOKAHEAD = 0.5; // Shared by playback preparation and runtime feeding.
    // 1024 = 9216 samples (~0.19s) PLUS delay headroom

    // Background Audio Thread (pre-computes PCM buffers off main thread)
    private ScheduledExecutorService audioThread;

    // Direct buffer allocation caching (Prevents native memory JVM GC thrashing in
    // hot loop)
    private final java.nio.IntBuffer reusableRestartBuffer = org.lwjgl.BufferUtils.createIntBuffer(1024);

    private AudioEngine() {
        // Private constructor for singleton
    }

    public PlaybackSession getActiveSession() {
        net.minecraft.client.MinecraftClient client = net.minecraft.client.MinecraftClient.getInstance();
        if (client.player != null) {
            return sessions.computeIfAbsent(client.player.getUuid(), k -> new PlaybackSession(this));
        }
        return null;
    }

    public void ensureActiveSession(java.util.UUID id) {
        sessions.computeIfAbsent(id, k -> new PlaybackSession(this));
    }

    public static synchronized AudioEngine getInstance() {
        if (INSTANCE == null) {
            INSTANCE = new AudioEngine();
        }
        return INSTANCE;
    }

    /**
     * Enable HRTF (Head-Related Transfer Function) for binaural 3D audio,
     * AND simultaneously request an expanded source pool (256 mono sources).
     *
     * OpenAL Soft's default source limit (typically 32) is far too low for
     * large PA systems with 4+ speaker clusters. Without raising this limit,
     * alGenSources() returns AL_INVALID_OPERATION after the first ~20 sources,
     * causing the outermost speaker clusters to be silently skipped.
     *
     * alcResetDeviceSOFT is the only way to change ALC attributes (like
     * ALC_MONO_SOURCES) on an existing context without destroying it.
     * We piggyback this request onto the HRTF reset so it happens in one call.
     */
    private void enableHrtf() {
        try {
            long context = ALC10.alcGetCurrentContext();
            if (context == 0L) {
                System.err.println("[enableHrtf] No current context, aborting.");
                return;
            }
            long device = ALC10.alcGetContextsDevice(context);
            if (device == 0L) {
                System.err.println("[enableHrtf] No device for context, aborting.");
                return;
            }

            // LOG: Current state before reset
            int preResetSources = ALC10.alcGetInteger(device, org.lwjgl.openal.ALC11.ALC_MONO_SOURCES);
            int preResetStereo = ALC10.alcGetInteger(device, org.lwjgl.openal.ALC11.ALC_STEREO_SOURCES);
            int preResetError = ALC10.alcGetError(device);
            System.out.println("[enableHrtf] PRE-RESET: monoSources=" + preResetSources + " stereoSources="
                    + preResetStereo + " alcError=0x" + Integer.toHexString(preResetError));

            ALCCapabilities alcCaps = org.lwjgl.openal.ALC.createCapabilities(device);
            System.out.println("[enableHrtf] ALC_SOFT_HRTF=" + alcCaps.ALC_SOFT_HRTF);

            if (alcCaps.ALC_SOFT_HRTF) {
                int numHrtf = ALC10.alcGetInteger(device, SOFTHRTF.ALC_NUM_HRTF_SPECIFIERS_SOFT);
                System.out.println("[enableHrtf] HRTF profiles found: " + numHrtf);

                if (numHrtf > 0) {
                    int[] attrs = {
                        SOFTHRTF.ALC_HRTF_SOFT, ALC10.ALC_TRUE, org.lwjgl.openal.ALC11.ALC_MONO_SOURCES, 1024, 0
                    };
                    System.out.println("[enableHrtf] Calling alcResetDeviceSOFT(HRTF=TRUE, MONO=1024)...");
                    boolean success = SOFTHRTF.alcResetDeviceSOFT(device, attrs);
                    int postResetError = ALC10.alcGetError(device);
                    System.out.println("[enableHrtf] alcResetDeviceSOFT returned: " + success + " alcError=0x"
                            + Integer.toHexString(postResetError));

                    if (success) {
                        int hrtfStatus = ALC10.alcGetInteger(device, SOFTHRTF.ALC_HRTF_STATUS_SOFT);
                        int actualSources = ALC10.alcGetInteger(device, org.lwjgl.openal.ALC11.ALC_MONO_SOURCES);
                        int actualStereo = ALC10.alcGetInteger(device, org.lwjgl.openal.ALC11.ALC_STEREO_SOURCES);
                        int postAlError = org.lwjgl.openal.AL10.alGetError();
                        System.out.println("[enableHrtf] POST-RESET: hrtfStatus=" + hrtfStatus
                                + " actualMonoSources=" + actualSources + " actualStereoSources=" + actualStereo
                                + " alError=0x" + Integer.toHexString(postAlError));
                    } else {
                        System.err.println("[enableHrtf] alcResetDeviceSOFT FAILED! alcError=0x"
                                + Integer.toHexString(postResetError));
                        System.err.println("[enableHrtf] Trying sources-only fallback...");
                        int[] attrsNoHrtf = {org.lwjgl.openal.ALC11.ALC_MONO_SOURCES, 1024, 0};
                        boolean fallback = SOFTHRTF.alcResetDeviceSOFT(device, attrsNoHrtf);
                        int fallbackError = ALC10.alcGetError(device);
                        System.out.println("[enableHrtf] Fallback reset returned: " + fallback + " alcError=0x"
                                + Integer.toHexString(fallbackError));
                    }
                } else {
                    System.out.println("[enableHrtf] No HRTF profiles, trying sources-only...");
                    int[] attrs = {org.lwjgl.openal.ALC11.ALC_MONO_SOURCES, 1024, 0};
                    boolean success = SOFTHRTF.alcResetDeviceSOFT(device, attrs);
                    int postError = ALC10.alcGetError(device);
                    System.out.println("[enableHrtf] Sources-only reset returned: " + success + " alcError=0x"
                            + Integer.toHexString(postError));
                }
            } else {
                System.out.println("[enableHrtf] ALC_SOFT_HRTF not available, skipping.");
            }
        } catch (Exception e) {
            System.err.println("[enableHrtf] EXCEPTION: " + e.getMessage());
            e.printStackTrace();
        }
    }

    public int getSlapbackAuxSlotId() {
        return effects.getSlapbackAuxSlotId();
    }

    /** Per-source echo filter reads this to scale its send gain. */
    public float getSlapbackGain() {
        return effects.getSlapbackGain();
    }

    public int getAuxSlotId() {
        return effects.getAuxSlotId();
    }

    public int getAuxSlotId(EmitterGroup emitterGroup) {
        int busIndex = emitterGroup != null ? emitterGroup.roomBusIndex() : 0;
        int slotId = effects.getRoomAuxSlotId(busIndex);
        return slotId != 0 ? slotId : effects.getAuxSlotId();
    }

    void refreshReverbBusAssignments() {
        reverbBusAllocator.allocate(sessions.values(), listenerPos);
    }

    public void initEfx() {
        // HRTF and source-pool settings stay in alsoft.ini; EFX init must not reset the device.
        // Resetting the device here disrupts Minecraft's own sound engine.
        effects.initialize();
    }

    public AdvancedAcousticScanner.VenuePreset getVenuePreset() {
        return effects.getVenuePreset();
    }

    public AdvancedAcousticScanner.VenueDescriptor getStoredVenueDescriptor() {
        return effects.getStoredVenueDescriptor();
    }

    /**
     * Calculate stage-front direction from speaker facing vectors.
     * This remains in AudioEngine because it belongs to source layout, not shared effects.
     */
    private Vec3d calculateStageDirection(List<StreamSource> sources) {
        double totalDirX = 0, totalDirY = 0, totalDirZ = 0;
        for (StreamSource source : sources) {
            totalDirX += source.dirX;
            totalDirY += source.dirY;
            totalDirZ += source.dirZ;
        }
        double length = Math.sqrt(totalDirX * totalDirX + totalDirY * totalDirY + totalDirZ * totalDirZ);
        if (length < 0.001) {
            return new Vec3d(1, 0, 0);
        }
        return new Vec3d(totalDirX / length, totalDirY / length, totalDirZ / length);
    }

    /**
     * Updates the OpenAL listener position and orientation.
     * Called every render frame.
     */
    public void updateListener(Vec3d pos, float yaw, float pitch) {
        if (getActiveSession() == null) return;
        // Position - Update instantly
        // listenerPos stores the REAL position for physics/distance calculations
        this.listenerPos = pos;

        // â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•
        // HRTF Y-AXIS FLATTENING (Listener-Side)
        // â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•
        // HRTF uses the elevation angle between listener and source positions.
        // Adjusting source Y doesn't work well because HRTF is angle-based:
        // if source is directly above, scaling Y doesn't change the 90Â° angle.
        //
        // Instead, we shift the LISTENER Y that OpenAL sees toward the
        // weighted average Y of active sources. This directly changes the
        // elevation angle for ALL sources simultaneously.
        //
        // Factor 0.4 = listener Y moves 40% toward the average source Y.
        // Result: HRTF perceives sources as being much closer to ear level.
        // â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•
        float openAlListenerY = (float) pos.y;
        PlaybackSession session = getActiveSession();
        if (session != null && !session.getStreamSources().isEmpty()) {
            double avgSourceY = 0;
            int count = 0;
            for (StreamSource s : session.getStreamSources()) {
                if (s.isValid && !s.isFinished) {
                    avgSourceY += s.getPos().getY() + 0.5;
                    count++;
                }
            }
            if (count > 0) {
                avgSourceY /= count;
                openAlListenerY = (float) (avgSourceY
                        - (avgSourceY - pos.y) * com.audiophilecraft.config.LiveTuningConfig.get().hrtf_yFlatten);
            }
        }
        listener.update(pos, yaw, pitch, openAlListenerY);
    }

    void syncListenerToCamera() {
        if (MinecraftClient.getInstance().cameraEntity == null) return;
        this.listenerPos = MinecraftClient.getInstance().cameraEntity.getPos();
        this.smoothedListenerPos = this.listenerPos;
    }

    /** Returns the smoothed underwater HF gain (0.08 = submerged, 1.0 = normal) */
    public float getUnderwaterHFGain() {
        return listener.getUnderwaterHFGain();
    }

    // --- MIXER STATE (Client-Side Only â€” No Network Required) ---

    // Mid/Side (Direct/Reverb) Mute States

    public boolean isMidMuted() {
        PlaybackSession session = getActiveSession();
        return session != null && session.isMidMuted();
    }

    public void setMidMuted(boolean muted) {
        PlaybackSession session = getActiveSession();
        if (session != null) session.setMidMuted(muted);
    }

    public boolean isSideMuted() {
        PlaybackSession session = getActiveSession();
        return session != null && session.isSideMuted();
    }

    public void setSideMuted(boolean muted) {
        PlaybackSession session = getActiveSession();
        if (session != null) session.setSideMuted(muted);
    }

    // 5-Band Parametric EQ per speaker type (dB, range: -12 to +12)

    /**
     * Get current mixer gain for a speaker type.
     * Reads from the player's amplifier tablet NBT for persistence across sessions
     * and world reloads.
     * Falls back to the active session for immediate runtime changes not yet saved
     * to NBT.
     */
    public float getMixerGain(String speakerType) {
        // First try tablet NBT for persistence across sessions
        float tabletVal = getTabletNbtValue("Mixer_" + speakerType);
        if (tabletVal != NBT_NOT_FOUND) return tabletVal;
        // Fallback to active session for runtime
        PlaybackSession session = getActiveSession();
        return session != null ? session.getMixerGain(speakerType) : 1.0f;
    }

    public void setMixerGain(String speakerType, float gain) {
        // Save to tablet NBT for persistence
        setTabletNbtValue("Mixer_" + speakerType, gain);
        // Also apply to running session
        PlaybackSession session = getActiveSession();
        if (session != null) session.setMixerGain(speakerType, gain);
    }

    /**
     * Get EQ dB for a speaker type and band (0 to 4).
     * Reads from the player's amplifier tablet NBT for persistence.
     */
    public synchronized float getEqDb(String speakerType, int band) {
        float tabletVal = getTabletNbtValue("EqDb_" + speakerType + "_" + band);
        if (tabletVal != NBT_NOT_FOUND) return tabletVal;
        PlaybackSession session = getActiveSession();
        return session != null ? session.getEqDb(speakerType, band) : 0f;
    }

    /** Set EQ dB for a speaker type and band (0 to 4). Range: -12 to +12 */
    public synchronized void setEqDb(String speakerType, int band, float db) {
        setTabletNbtValue("EqDb_" + speakerType + "_" + band, db);
        PlaybackSession session = getActiveSession();
        if (session != null) session.setEqDb(speakerType, band, db);
    }

    /**
     * Get EQ Q for a speaker type and band (0 to 4).
     * Reads from the player's amplifier tablet NBT for persistence.
     */
    public synchronized float getEqQ(String speakerType, int band) {
        float tabletVal = getTabletNbtValue("EqQ_" + speakerType + "_" + band);
        if (tabletVal != NBT_NOT_FOUND) return tabletVal;
        PlaybackSession session = getActiveSession();
        return session != null ? session.getEqQ(speakerType, band) : 1f;
    }

    /** Set EQ Q for a speaker type and band (0 to 4). Range: 0.1 to 10.0 */
    public synchronized void setEqQ(String speakerType, int band, float q) {
        setTabletNbtValue("EqQ_" + speakerType + "_" + band, q);
        PlaybackSession session = getActiveSession();
        if (session != null) session.setEqQ(speakerType, band, q);
    }

    // --- Tablet NBT helpers (reads/writes the amplifier tablet ItemStack NBT) ---

    private net.minecraft.item.ItemStack getTabletStack() {
        net.minecraft.client.MinecraftClient client = net.minecraft.client.MinecraftClient.getInstance();
        if (client.player == null) return net.minecraft.item.ItemStack.EMPTY;
        net.minecraft.item.ItemStack main = client.player.getMainHandStack();
        if (main.getItem() instanceof com.audiophilecraft.item.AmplifierTabletItem) return main;
        net.minecraft.item.ItemStack off = client.player.getOffHandStack();
        if (off.getItem() instanceof com.audiophilecraft.item.AmplifierTabletItem) return off;
        return net.minecraft.item.ItemStack.EMPTY;
    }

    private static final float NBT_NOT_FOUND = -13f;

    /** Returns NBT_NOT_FOUND if not found in NBT */
    private float getTabletNbtValue(String key) {
        net.minecraft.item.ItemStack stack = getTabletStack();
        if (stack.isEmpty()) return NBT_NOT_FOUND;
        net.minecraft.nbt.NbtCompound nbt = stack.getNbt();
        if (nbt != null && nbt.contains(key)) return nbt.getFloat(key);
        return NBT_NOT_FOUND;
    }

    private void setTabletNbtValue(String key, float value) {
        net.minecraft.item.ItemStack stack = getTabletStack();
        if (stack.isEmpty()) return;
        net.minecraft.nbt.NbtCompound nbt = stack.getOrCreateNbt();
        nbt.putFloat(key, value);
    }

    /**
     * Smooth gain interpolation placeholder.
     * Called every render frame.
     */
    public void updateGains() {
        if (getActiveSession() == null || this.listenerPos == null) return;

        // Ensure venue reverb is applied if a preset exists
        effects.ensureVenueReverb();

        // Note: Per-source physics (gain, occlusion, etc.) is now handled
        // in StreamSource.update() which runs every tick.
    }

    /**
     * Thread-safe wall-clock time since playback started.
     * Audio thread calls this to derive globalSampleTime for ALL sources.
     * Pause duration is already factored out via
     * getActiveSession().getStreamStartTime() offset.
     * Returns 0.0 if not playing.
     */
    public double getTimeSinceStart() {
        if (getActiveSession() == null
                || !getActiveSession().isPlaying()
                || getActiveSession().getStreamStartTime() == 0) return 0.0;
        return (System.nanoTime() - getActiveSession().getStreamStartTime()) / 1_000_000_000.0;
    }

    /**
     * Returns the sample rate of the currently active audio stream.
     * Used by the global master clock to convert wall-clock seconds to sample
     * position.
     */
    public int getSampleRateForClock() {
        PlaybackSession session = getActiveSession();
        if (session == null) return 48000;
        for (AudioStreamBuffer buffer : session.getStreamBuffers().values()) {
            if (buffer.sampleRate > 0) {
                return buffer.sampleRate;
            }
        }
        return 48000; // Safe fallback
    }

    private long lastTickTime = System.nanoTime();

    /**
     * Cleanup and logic update. Called every client tick (20Hz).
     */
    public void updateSourcesTick(World world) {
        MinecraftClient mc = MinecraftClient.getInstance();
        boolean gamePaused = mc.isPaused();
        boolean allPaused = true;

        for (PlaybackSession session : sessions.values()) {
            boolean shouldPause = gamePaused || session.isManuallyPaused();
            if (shouldPause != session.isPaused()) {
                if (shouldPause) {
                    session.setPaused(true);
                    session.setPauseStartTimestamp(System.nanoTime());
                    for (StreamSource sound : session.getStreamSources()) {
                        sound.pause();
                    }
                } else {
                    if (session.getPauseStartTimestamp() > 0 && session.getStreamStartTime() > 0) {
                        long pauseDuration = System.nanoTime() - session.getPauseStartTimestamp();
                        session.setStreamStartTime(session.getStreamStartTime() + pauseDuration);
                    }
                    session.setPaused(false);
                    for (StreamSource sound : session.getStreamSources()) {
                        sound.resume();
                    }
                }
            }
            if (!shouldPause) allPaused = false;
        }

        // Handle global reverb mute ONLY if the entire game is paused (ESC menu).
        // If manually paused via tablet, let the reverb tail ring out naturally.
        effects.setGamePaused(gamePaused);

        // Don't update heavy logic if the game itself is paused
        if (gamePaused) {
            lastTickTime = System.nanoTime(); // Reset delta tracking when paused
            return;
        }

        // OPTIMIZATION: Continuous environment analysis is disabled to save CPU.
        // We only care about the PA system, which does a one-time scan via
        // scanAtPosition() later.
        // analyzeEnvironment(world);

        // Update sources for ALL playing sessions
        boolean removedSession = false;
        java.util.Iterator<java.util.Map.Entry<java.util.UUID, PlaybackSession>> sessionIterator =
                sessions.entrySet().iterator();
        while (sessionIterator.hasNext()) {
            java.util.Map.Entry<java.util.UUID, PlaybackSession> entry = sessionIterator.next();
            PlaybackSession session = entry.getValue();
            if (session.isPlaying() && session.getStreamStartTime() != 0) {
                long now = System.nanoTime();
                double timeSinceStart = (now - session.getStreamStartTime()) / 1_000_000_000.0;

                java.util.List<StreamSource> toRemove = new java.util.ArrayList<>();
                for (StreamSource source : session.getStreamSources()) {
                    if (!source.update(world, this.listenerPos, timeSinceStart)) {
                        source.cleanup();
                        toRemove.add(source);
                    }
                }
                session.getStreamSources().removeAll(toRemove);

                // If all speakers for this session have been broken/removed, stop and remove it
                // entirely.
                // This prevents stale sessions from blocking future play requests with ghost
                // URL state.
                if (session.getStreamSources().isEmpty()) {
                    playback.cancelUrlRequest(entry.getKey());
                    synchronized (this) {
                        session.stopAll();
                        sessionIterator.remove();
                    }
                    removedSession = true;
                }
            }
        }
        if (removedSession) {
            refreshReverbBusAssignments();
            checkAndShutdownThread();
        }

        // Apply Listener-based Early Reflections dynamically every tick
        boolean anyPlaying = false;
        for (PlaybackSession s : sessions.values()) {
            if (s.isPlaying()) {
                anyPlaying = true;
                break;
            }
        }
        if (anyPlaying) {
            effects.updateListenerAcoustics(world, listenerPos);
        }

        // --- VENUE-LOCKED REVERB STATE FIX ---
        // If all sources finished naturally during ACTIVE playback, clear the venue
        // preset.
        // Otherwise, the player will be stuck with the stadium reverb forever.
        PlaybackSession activeSession = getActiveSession();
        if (activeSession != null
                && activeSession.isPlaying()
                && activeSession.getStreamSources().isEmpty()
                && effects.getVenuePreset() != null) {
            effects.clearVenuePreset();
        }

        // --- OCCLUSION CLUSTERING (REMOVED) ---
        // Clustering was sharing occlusion values between speakers up to 8 blocks
        // apart.
        // If one speaker peeks out from behind a wall, the *entire cluster* instantly
        // gets
        // unoccluded, causing a sudden pop in volume and treble even for speakers still
        // behind the wall.
        // Raycast performance is already throttled per-speaker in StreamSource.java.

        // If the player steps outside the building where the music is playing, the
        // overall
        // room reverb (the tail of the stadium or hall) should also be physically
        // muffled and blocked
        // by the walls. It should not hang in the player's ears like an artificial
        // overlay.
        float maxOcclusion = 0.0f;
        int activeSourceCount = 0;
        for (PlaybackSession session : sessions.values()) {
            for (StreamSource source : session.getStreamSources()) {
                activeSourceCount++;
                if (source.currentOcclusion > maxOcclusion) {
                    maxOcclusion = source.currentOcclusion;
                }
            }
        }

        // If not playing anything, keep maxOcclusion at 1.0 so ambient sound is normal
        if (activeSourceCount == 0) {
            maxOcclusion = 1.0f;
        }

        effects.updateMasterReverbOcclusion(maxOcclusion);

        // Ensure venue reverb is applied if preset exists
        effects.ensureVenueReverb();

        lastTickTime = System.nanoTime();
    }

    public void pauseAll() {
        for (PlaybackSession session : sessions.values()) {
            for (StreamSource sound : session.getStreamSources()) {
                sound.pause();
            }
        }
        // Mute aux effect slots to kill reverb tails during pause
        effects.muteEffectSlots();
    }

    synchronized void stopSessionContents(PlaybackSession session) {
        if (session != null) session.stopAll();
    }

    /**
     * Stops a specific session immediately.
     */
    public synchronized void stopSession(java.util.UUID sessionUUID) {
        playback.cancelUrlRequest(sessionUUID);
        PlaybackSession session = sessions.remove(sessionUUID);
        if (session != null) {
            session.stopAll();
        }
        refreshReverbBusAssignments();
        checkAndShutdownThread();
    }

    /**
     * Stops all active audio sources across all sessions immediately.
     */
    public synchronized void stopAll() {
        playback.cancelAllUrlRequests();
        for (PlaybackSession session : sessions.values()) {
            session.stopAll();
        }
        sessions.clear();
        checkAndShutdownThread();
    }

    private synchronized void checkAndShutdownThread() {
        boolean anyPlaying = false;
        for (PlaybackSession s : sessions.values()) {
            if (s.isPlaying()) {
                anyPlaying = true;
                break;
            }
        }
        if (!anyPlaying) {
            effects.clearVenueState();
            if (audioThread != null) {
                audioThread.shutdownNow();
                try {
                    audioThread.awaitTermination(50, java.util.concurrent.TimeUnit.MILLISECONDS);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                }
                audioThread = null;
            }
        }
    }

    public void resumeAll() {
        for (PlaybackSession session : sessions.values()) {
            for (StreamSource sound : session.getStreamSources()) {
                sound.resume();
            }
        }
        // Restore aux effect slot gain before resuming sources
        effects.resumeEffectSlots();
    }

    /**
     * Start the background audio processing thread.
     * Runs every 5ms, pre-computing PCM buffers for all active StreamSources.
     */
    void startAudioThread() {
        if (audioThread != null && !audioThread.isShutdown()) {
            return; // Thread is already running and handling sessions
        }

        // Capture LWJGL OpenAL capabilities from the current (render) thread.
        // These must be propagated to the audio thread so it can make AL calls.
        final org.lwjgl.openal.ALCapabilities alCaps = org.lwjgl.openal.AL.getCapabilities();

        audioThread = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "AudiophileCraft-Audio");
            t.setDaemon(true);
            t.setPriority(Thread.MAX_PRIORITY); // Ensure real-time scheduling over game logic
            return t;
        });

        // One-shot init: set AL capabilities on the audio thread before any work
        audioThread.execute(() -> {
            try {
                org.lwjgl.openal.AL.setCurrentThread(alCaps);
            } catch (Exception e) {
                System.err.println("AudioEngine: Failed to propagate AL caps to audio thread: " + e.getMessage());
            }
        });

        audioThread.scheduleWithFixedDelay(this::processAudioBackground, 0, 5, TimeUnit.MILLISECONDS);
    }

    /**
     * Background audio processing loop.
     * Phase 1: Smooth listener position (kill FPS jitter).
     * Phase 2: Pre-computes PCM data for all active sources with up-to-date
     * distance.
     * Phase 3: Feeds the pre-computed data to OpenAL (unqueue â†’ fill â†’ queue).
     * All phases run entirely on this thread, making audio independent from the
     * render thread. Minecraft can freeze for seconds without audio dropping out.
     */
    private synchronized void processAudioBackground() {
        // Respect interrupt: executor shutdown will interrupt us
        if (Thread.interrupted()) {
            Thread.currentThread().interrupt();
            return;
        }
        try {
            double currentWallTime = 0.0;
            // Phase 0: Decode OGG on background thread
            // Snapshot sessions to avoid ConcurrentModification with stopAll()
            java.util.List<PlaybackSession> sessionSnapshot = new java.util.ArrayList<>(sessions.values());
            for (PlaybackSession session : sessionSnapshot) {
                if (!session.isPlaying() || session.isPaused() || session.isSeeking()) continue;
                currentWallTime = session.getStreamStartTime() > 0
                        ? (System.nanoTime() - session.getStreamStartTime()) / 1_000_000_000.0
                        : 0.0;
                // Snapshot stream buffers to avoid race with clear()
                java.util.List<AudioStreamBuffer> bufferSnapshot =
                        new java.util.ArrayList<>(session.getStreamBuffers().values());
                for (AudioStreamBuffer buffer : bufferSnapshot) {
                    if (buffer.sampleRate > 0) {
                        buffer.syncToTime(currentWallTime + BUFFER_LOOKAHEAD);
                    }
                }
            }

            // Phase 1: Smooth listener position
            Vec3d rawPos = this.listenerPos;
            if (rawPos != null) {
                Vec3d prev = this.smoothedListenerPos;
                if (prev == null) prev = rawPos;
                double alpha = 0.35;
                this.smoothedListenerPos = new Vec3d(
                        prev.x + (rawPos.x - prev.x) * alpha,
                        prev.y + (rawPos.y - prev.y) * alpha,
                        prev.z + (rawPos.z - prev.z) * alpha);
            }
            Vec3d currentPos = this.smoothedListenerPos;

            for (PlaybackSession session : sessionSnapshot) {
                if (!session.isPlaying() || session.isPaused() || session.isSeeking()) continue;
                double sessionWallTime = session.getStreamStartTime() > 0
                        ? (System.nanoTime() - session.getStreamStartTime()) / 1_000_000_000.0
                        : 0.0;
                int sampleRate = 48000;
                for (AudioStreamBuffer buffer : session.getStreamBuffers().values()) {
                    if (buffer.sampleRate > 0) {
                        sampleRate = buffer.sampleRate;
                        break;
                    }
                }
                double globalSampleTime = sessionWallTime * sampleRate;

                for (StreamSource source : session.getStreamSources()) {
                    if (source.feedOpenALFromAudioThread(globalSampleTime, currentPos)) {
                        if (reusableRestartBuffer.remaining() > 0) {
                            // Validate source still valid before adding to restart buffer
                            if (source.isValid) {
                                reusableRestartBuffer.put(source.sourceId);
                            }
                        }
                    }
                }
            }

            if (reusableRestartBuffer.position() > 0) {
                int count = reusableRestartBuffer.position();
                reusableRestartBuffer.flip();
                org.lwjgl.openal.AL10.alSourcePlayv(reusableRestartBuffer);
                reusableRestartBuffer.clear();
            }
        } catch (Exception e) {
            System.err.println("[AudioEngine] processAudioBackground failed: " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * Full cleanup including EFX resources.
     */
    /**
     * Free shared EFX resources (reverb effect + aux slots).
     * Does NOT stop sessions — those are managed per-player.
     */
    public void cleanupEfx() {
        effects.cleanup();
    }

    static final String TYPE_NORMAL = "normal";
    static final String TYPE_SUB = "sub";
    static final String TYPE_MID = "mid";
    static final String TYPE_LINE = "line";
    private static final String[] EQ_SPEAKER_TYPES = {TYPE_NORMAL, TYPE_SUB, TYPE_MID, TYPE_LINE};
    private static final int EQ_BAND_COUNT = 5;

    /**
     * Load persisted EQ/Mixer values from the local tablet NBT into a session.
     * Called when a session is created/reused so settings survive disconnect/reconnect.
     * Only loads if the local player owns the session.
     */
    void loadPersistedEqIntoSession(PlaybackSession session, java.util.UUID sessionUUID) {
        net.minecraft.client.MinecraftClient client = net.minecraft.client.MinecraftClient.getInstance();
        if (client.player == null || !client.player.getUuid().equals(sessionUUID)) return;
        for (String type : EQ_SPEAKER_TYPES) {
            // Load EQ dB
            for (int band = 0; band < EQ_BAND_COUNT; band++) {
                float db = getTabletNbtValue("EqDb_" + type + "_" + band);
                if (db != NBT_NOT_FOUND) {
                    session.setEqDb(type, band, db);
                }
                float q = getTabletNbtValue("EqQ_" + type + "_" + band);
                if (q != NBT_NOT_FOUND) {
                    session.setEqQ(type, band, q);
                }
            }
            // Load mixer gain
            float gain = getTabletNbtValue("Mixer_" + type);
            if (gain != NBT_NOT_FOUND) {
                session.setMixerGain(type, gain);
            }
        }
    }

    public void prepareStreamBuffers(PlaybackSession session, String trackId) {
        playback.prepareStreamBuffers(session, trackId);
    }

    public void applyDspForType(short[] audioData, int sampleRate, String speakerType) {
        playback.applyDspForType(audioData, sampleRate, speakerType);
    }

    public void toggleManualPause(java.util.UUID sessionUUID) {
        if (sessions.containsKey(sessionUUID)) {
            PlaybackSession session = sessions.get(sessionUUID);
            if (session.isPlaying()) {
                session.setManuallyPaused(!session.isManuallyPaused());
            }
        }
    }

    public void playTrack(
            java.util.UUID sessionUUID, String trackId, List<BlockPos> speakers, float power, float inputGain) {
        playback.playTrack(sessionUUID, trackId, speakers, power, inputGain);
    }

    public void updateInputGain(float gain) {
        if (getActiveSession() == null) return;
        for (StreamSource ss : getActiveSession().getStreamSources()) {
            ss.inputGain = gain;
        }
    }

    /**
     * Live update power for all active StreamSources.
     * Called when the power knob is changed in the GUI.
     * Smoothing is handled inside StreamSource.updatePhysics().
     */
    public void updatePower(float power) {
        if (getActiveSession() == null) return;
        for (StreamSource ss : getActiveSession().getStreamSources()) {
            ss.power = power;
        }
    }

    public void playFromUrl(
            java.util.UUID sessionUUID, String url, List<BlockPos> speakers, float power, float inputGain) {
        playback.playFromUrl(sessionUUID, url, speakers, power, inputGain);
    }

    public void playFromUrl(
            java.util.UUID sessionUUID,
            String url,
            List<BlockPos> speakers,
            float power,
            float inputGain,
            boolean startImmediately,
            java.util.function.Consumer<java.util.UUID> onReadyCallback) {
        playback.playFromUrl(sessionUUID, url, speakers, power, inputGain, startImmediately, onReadyCallback);
    }

    public void createSourcesFromClusters(
            PlaybackSession session,
            List<List<BlockPos>> clusters,
            int[] counts,
            World world,
            float power,
            float inputGain) {
        playback.createSourcesFromClusters(session, clusters, counts, world, power, inputGain);
    }

    public void startPlaybackWithVenueScan(
            PlaybackSession session, World world, List<BlockPos> speakers, boolean startAfterScan) {
        playback.startPlaybackWithVenueScan(session, world, speakers, startAfterScan);
    }

    public void playFromPcmData(
            java.util.UUID sessionUUID,
            short[] pcmData,
            int sampleRate,
            List<BlockPos> speakers,
            float power,
            float inputGain) {
        playback.playFromPcmData(sessionUUID, pcmData, sampleRate, speakers, power, inputGain);
    }

    /**
     * Updates the OpenAL direction vector for all active streams of a speaker
     * immediately. Called from the client UI or network packets.
     */
    public void updateSpeakerTilt(BlockPos speakerPos, int tiltDeg) {
        if (getActiveSession() == null) return;
        net.minecraft.client.world.ClientWorld world = net.minecraft.client.MinecraftClient.getInstance().world;
        if (world == null) return;
        net.minecraft.block.BlockState state = world.getBlockState(speakerPos);
        if (!state.contains(net.minecraft.state.property.Properties.HORIZONTAL_FACING)) return;

        net.minecraft.util.math.Direction facing = state.get(net.minecraft.state.property.Properties.HORIZONTAL_FACING);
        net.minecraft.util.math.Vec3i vec = facing.getVector();
        float tiltRad = (float) Math.toRadians(tiltDeg);
        float cosT = (float) Math.cos(tiltRad);
        float sinT = (float) Math.sin(tiltRad);
        float dirX = vec.getX() * cosT;
        float dirY = sinT;
        float dirZ = vec.getZ() * cosT;

        for (StreamSource ss : getActiveSession().getStreamSources()) {
            if (ss.pos.equals(speakerPos) && !TYPE_SUB.equals(ss.speakerType)) {
                alSource3f(ss.sourceId, AL_DIRECTION, dirX, dirY, dirZ);
            }
        }
    }

    /**
     * Fetch Total Duration in Seconds for the currently playing track.
     */
    public double getTotalPlaybackDuration() {
        return getTotalPlaybackDuration(getActiveSession());
    }

    public double getTotalPlaybackDuration(PlaybackSession session) {
        if (session == null
                || !session.isPlaying()
                || session.getStreamBuffers().isEmpty()) return 0.0;
        AudioStreamBuffer buf = session.getStreamBuffers().values().iterator().next();
        return buf != null ? buf.getTotalDurationSeconds() : 0.0;
    }

    /**
     * Fetch Current Playback Time in Seconds.
     */
    public double getCurrentPlaybackTime() {
        return getCurrentPlaybackTime(getActiveSession());
    }

    public double getCurrentPlaybackTime(PlaybackSession session) {
        if (session == null || !session.isPlaying() || session.getStreamStartTime() == 0) return 0.0;

        long now = System.nanoTime();
        if (session.isPaused() && session.getPauseStartTimestamp() > 0) {
            now = session.getPauseStartTimestamp();
        }

        double timeSinceStart = (now - session.getStreamStartTime()) / 1_000_000_000.0;
        return timeSinceStart;
    }

    /**
     * Globally Seek all playing channels to the designated timestamp.
     * Alters the base physical stream clock so all nodes fast-forward
     * homogeneously.
     */
    public synchronized void seek(double timeSeconds) {
        seek(getActiveSession(), timeSeconds);
    }

    public synchronized void seek(PlaybackSession session, double timeSeconds) {
        if (session == null || !session.isPlaying()) return;

        // Clamp to bounds
        double totalDuration = getTotalPlaybackDuration(session);
        if (timeSeconds < 0) timeSeconds = 0;
        if (totalDuration > 0 && timeSeconds > totalDuration) timeSeconds = totalDuration;

        // Echo Debouncing (Client-Side Prediction Defense)
        // If a local client jumped the track manually, the server echoes the packet
        // 200ms later.
        // We do not want to "jump back" 200ms to the exact same marker and cause a
        // track stutter.
        if (Math.abs(getCurrentPlaybackTime(session) - timeSeconds) < 0.5) {
            return; // Already within the target window realistically
        }

        // ATOMIC SEEK: Block the audio thread from feeding OpenAL sources
        // while we update the global clock and re-align every speaker.
        // Without this, processAudioBackground() can fire mid-seek and cause
        // underrun recovery to snap outputCursor to the wrong position,
        // resulting in 3-4 speakers playing asynchronously.
        session.setSeeking(true);
        try {
            // Nothing to seek if all sources were cleaned up (song ended naturally)
            if (session.getStreamSources().isEmpty()) return;

            // Shift absolute temporal timeline baseline
            long now = System.nanoTime();

            // If the game is paused, adjust the pause tracker so it doesn't double-cancel
            // the seek on resume
            if (session.isPaused()) {
                session.setPauseStartTimestamp(now);
            }

            session.setStreamStartTime(now - (long) (timeSeconds * 1_000_000_000.0));

            // Force raw JLayer decoder index jumps
            for (AudioStreamBuffer buffer : session.getStreamBuffers().values()) {
                // Buffer up to 0.1s INTO THE PAST to cushion StreamSource physics delays.
                // When speakers simulate spatial distance, they read slightly backwards in the
                // ring buffer.
                // If the buffer starts exactly at timeSeconds, spatial delay forces a read of 0
                // (causing a crackle).
                buffer.seekToTime(timeSeconds - 0.1);

                // Pre-fill next half-second buffer window so StreamSource AL generators don't
                // read zeros
                buffer.syncToTime(timeSeconds + BUFFER_LOOKAHEAD);
            }

            // Broadcast snap offsets into ALL actively playing physical speakers locally
            // using atomic AL Source commands
            java.nio.IntBuffer sourceIds = org.lwjgl.BufferUtils.createIntBuffer(
                    session.getStreamSources().size());
            for (StreamSource source : session.getStreamSources()) {
                source.seekToTime(timeSeconds); // Aligns playhead and hardware queue internally (does NOT call
                // alSourcePlay)
                sourceIds.put(source.sourceId);
            }
            sourceIds.flip();
            // Only start playback if NOT paused — otherwise 1 buffer (~21ms) leaks through
            // before the next tick's pause check fires.
            if (!session.isPaused()) {
                org.lwjgl.openal.AL10.alSourcePlayv(sourceIds); // ATOMIC HARDWARE START
            }
        } finally {
            session.setSeeking(false); // Resume audio thread feeding
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // MULTIPLAYER SYNC METHODS
    // Called by S2C packet handlers to update running sessions remotely.
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Start playback for a session that was pre-loaded (multiplayer sync).
     * Called when all players confirm they've buffered enough.
     */
    public void startSessionPlayback(java.util.UUID sessionUUID) {
        PlaybackSession session = sessions.get(sessionUUID);
        if (session != null) {
            playback.startPreparedSession(session);
        }
    }

    public void seekForSession(java.util.UUID sessionUUID, float targetTime) {
        PlaybackSession session = sessions.get(sessionUUID);
        if (session != null) {
            seek(session, targetTime);
        }
    }

    public void setEqDbForSession(java.util.UUID sessionUUID, String speakerType, int band, float db) {
        net.minecraft.client.MinecraftClient client = net.minecraft.client.MinecraftClient.getInstance();
        if (client.player != null && client.player.getUuid().equals(sessionUUID)) {
            setTabletNbtValue("EqDb_" + speakerType + "_" + band, db);
        }
        PlaybackSession session = sessions.get(sessionUUID);
        if (session != null) session.setEqDb(speakerType, band, db);
    }

    public void setEqQForSession(java.util.UUID sessionUUID, String speakerType, int band, float q) {
        net.minecraft.client.MinecraftClient client = net.minecraft.client.MinecraftClient.getInstance();
        if (client.player != null && client.player.getUuid().equals(sessionUUID)) {
            setTabletNbtValue("EqQ_" + speakerType + "_" + band, q);
        }
        PlaybackSession session = sessions.get(sessionUUID);
        if (session != null) session.setEqQ(speakerType, band, q);
    }

    public void setMixerGainForSession(java.util.UUID sessionUUID, String speakerType, float gain) {
        net.minecraft.client.MinecraftClient client = net.minecraft.client.MinecraftClient.getInstance();
        if (client.player != null && client.player.getUuid().equals(sessionUUID)) {
            setTabletNbtValue("Mixer_" + speakerType, gain);
        }
        PlaybackSession session = sessions.get(sessionUUID);
        if (session != null) session.setMixerGain(speakerType, gain);
    }

    public void updateInputGainForSession(java.util.UUID sessionUUID, float gain) {
        PlaybackSession session = sessions.get(sessionUUID);
        if (session != null) {
            for (StreamSource source : session.getStreamSources()) {
                source.inputGain = gain;
            }
        }
    }

    public void updatePowerForSession(java.util.UUID sessionUUID, float power) {
        PlaybackSession session = sessions.get(sessionUUID);
        if (session != null) {
            for (StreamSource source : session.getStreamSources()) {
                source.power = power;
            }
        }
    }

    /**
     * Apply channel mask to the given speaker AND all nearby speakers in the same
     * cluster.
     * Also updates all client-side BlockEntities so settings persist across world
     * reloads.
     * 0=BOTH, 1=LEFT, 2=RIGHT. Takes effect on next buffer refill (~10ms).
     */
    public void applyChannelMaskToSpeaker(BlockPos speakerPos, int mask) {
        for (PlaybackSession session : sessions.values()) {
            if (!session.isPlaying()) continue;
            java.util.List<BlockPos> positions = session.getStreamSources().stream()
                    .map(s -> s.pos)
                    .distinct()
                    .toList();
            java.util.List<BlockPos> targetCluster = null;
            for (java.util.List<BlockPos> cluster : SpeakerClusterer.clusterSpeakers(positions)) {
                for (BlockPos p : cluster) {
                    if (p.equals(speakerPos)) {
                        targetCluster = cluster;
                        break;
                    }
                }
                if (targetCluster != null) break;
            }
            if (targetCluster != null) {
                for (BlockPos pos : targetCluster) {
                    // Update client-side BlockEntity for persistence
                    updateClientBlockEntityChannel(pos, mask);
                    for (StreamSource source : session.getStreamSources()) {
                        if (source.pos.equals(pos)) {
                            source.setChannelMask(mask);
                        }
                    }
                }
            } else {
                updateClientBlockEntityChannel(speakerPos, mask);
                for (StreamSource source : session.getStreamSources()) {
                    if (source.pos.equals(speakerPos)) {
                        source.setChannelMask(mask);
                    }
                }
            }
        }
    }

    private void updateClientBlockEntityChannel(BlockPos pos, int mask) {
        net.minecraft.client.MinecraftClient mc = net.minecraft.client.MinecraftClient.getInstance();
        if (mc.world != null) {
            net.minecraft.block.entity.BlockEntity be = mc.world.getBlockEntity(pos);
            if (be instanceof com.audiophilecraft.block.entity.SpeakerBlockEntity speaker) {
                speaker.setChannelMask(mask);
            }
        }
    }
}
