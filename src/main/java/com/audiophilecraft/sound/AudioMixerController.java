package com.audiophilecraft.sound;

import static org.lwjgl.openal.AL10.AL_DIRECTION;
import static org.lwjgl.openal.AL10.alSource3f;

import com.audiophilecraft.block.entity.SpeakerBlockEntity;
import com.audiophilecraft.item.AmplifierTabletItem;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;
import net.minecraft.block.BlockState;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.state.property.Properties;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3i;

/**
 * Owns player-facing mixer controls and their tablet persistence.
 *
 * <p>The controller also applies network-synchronized control changes to the
 * correct playback session and speaker cluster.
 */
final class AudioMixerController {
    private static final float NBT_NOT_FOUND = -13.0f;
    private static final int EQ_BAND_COUNT = 5;
    private static final String[] EQ_SPEAKER_TYPES = {
        AudioEngine.TYPE_NORMAL, AudioEngine.TYPE_SUB, AudioEngine.TYPE_MID, AudioEngine.TYPE_LINE
    };

    private final Map<UUID, PlaybackSession> sessions;
    private final Supplier<PlaybackSession> activeSessionSupplier;

    AudioMixerController(Map<UUID, PlaybackSession> sessions, Supplier<PlaybackSession> activeSessionSupplier) {
        this.sessions = sessions;
        this.activeSessionSupplier = activeSessionSupplier;
    }

    boolean isMidMuted() {
        PlaybackSession session = activeSession();
        return session != null && session.isMidMuted();
    }

    void setMidMuted(boolean muted) {
        PlaybackSession session = activeSession();
        if (session != null) session.setMidMuted(muted);
    }

    boolean isSideMuted() {
        PlaybackSession session = activeSession();
        return session != null && session.isSideMuted();
    }

    void setSideMuted(boolean muted) {
        PlaybackSession session = activeSession();
        if (session != null) session.setSideMuted(muted);
    }

    float getMixerGain(String speakerType) {
        float tabletValue = getTabletNbtValue("Mixer_" + speakerType);
        if (tabletValue != NBT_NOT_FOUND) return tabletValue;

        PlaybackSession session = activeSession();
        return session != null ? session.getMixerGain(speakerType) : 1.0f;
    }

    void setMixerGain(String speakerType, float gain) {
        setTabletNbtValue("Mixer_" + speakerType, gain);
        PlaybackSession session = activeSession();
        if (session != null) session.setMixerGain(speakerType, gain);
    }

    float getEqDb(String speakerType, int band) {
        float tabletValue = getTabletNbtValue("EqDb_" + speakerType + "_" + band);
        if (tabletValue != NBT_NOT_FOUND) return tabletValue;

        PlaybackSession session = activeSession();
        return session != null ? session.getEqDb(speakerType, band) : 0.0f;
    }

    void setEqDb(String speakerType, int band, float db) {
        setTabletNbtValue("EqDb_" + speakerType + "_" + band, db);
        PlaybackSession session = activeSession();
        if (session != null) session.setEqDb(speakerType, band, db);
    }

    float getEqQ(String speakerType, int band) {
        float tabletValue = getTabletNbtValue("EqQ_" + speakerType + "_" + band);
        if (tabletValue != NBT_NOT_FOUND) return tabletValue;

        PlaybackSession session = activeSession();
        return session != null ? session.getEqQ(speakerType, band) : 1.0f;
    }

    void setEqQ(String speakerType, int band, float q) {
        setTabletNbtValue("EqQ_" + speakerType + "_" + band, q);
        PlaybackSession session = activeSession();
        if (session != null) session.setEqQ(speakerType, band, q);
    }

    void loadPersistedEqIntoSession(PlaybackSession session, UUID sessionId) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null || !client.player.getUuid().equals(sessionId)) return;

        for (String speakerType : EQ_SPEAKER_TYPES) {
            for (int band = 0; band < EQ_BAND_COUNT; band++) {
                float db = getTabletNbtValue("EqDb_" + speakerType + "_" + band);
                if (db != NBT_NOT_FOUND) {
                    session.setEqDb(speakerType, band, db);
                }

                float q = getTabletNbtValue("EqQ_" + speakerType + "_" + band);
                if (q != NBT_NOT_FOUND) {
                    session.setEqQ(speakerType, band, q);
                }
            }

            float gain = getTabletNbtValue("Mixer_" + speakerType);
            if (gain != NBT_NOT_FOUND) {
                session.setMixerGain(speakerType, gain);
            }
        }
    }

    void updateInputGain(float gain) {
        PlaybackSession session = activeSession();
        if (session == null) return;

        for (StreamSource source : session.getStreamSources()) {
            source.inputGain = gain;
        }
    }

    void updatePower(float power) {
        PlaybackSession session = activeSession();
        if (session == null) return;

        for (StreamSource source : session.getStreamSources()) {
            source.power = power;
        }
    }

    void setEqDbForSession(UUID sessionId, String speakerType, int band, float db) {
        if (isLocalSession(sessionId)) {
            setTabletNbtValue("EqDb_" + speakerType + "_" + band, db);
        }
        PlaybackSession session = sessions.get(sessionId);
        if (session != null) session.setEqDb(speakerType, band, db);
    }

    void setEqQForSession(UUID sessionId, String speakerType, int band, float q) {
        if (isLocalSession(sessionId)) {
            setTabletNbtValue("EqQ_" + speakerType + "_" + band, q);
        }
        PlaybackSession session = sessions.get(sessionId);
        if (session != null) session.setEqQ(speakerType, band, q);
    }

    void setMixerGainForSession(UUID sessionId, String speakerType, float gain) {
        if (isLocalSession(sessionId)) {
            setTabletNbtValue("Mixer_" + speakerType, gain);
        }
        PlaybackSession session = sessions.get(sessionId);
        if (session != null) session.setMixerGain(speakerType, gain);
    }

    void updateInputGainForSession(UUID sessionId, float gain) {
        PlaybackSession session = sessions.get(sessionId);
        if (session == null) return;

        for (StreamSource source : session.getStreamSources()) {
            source.inputGain = gain;
        }
    }

    void updatePowerForSession(UUID sessionId, float power) {
        PlaybackSession session = sessions.get(sessionId);
        if (session == null) return;

        for (StreamSource source : session.getStreamSources()) {
            source.power = power;
        }
    }

    void updateSpeakerTilt(BlockPos speakerPosition, int tiltDegrees) {
        PlaybackSession session = activeSession();
        if (session == null) return;

        ClientWorld world = MinecraftClient.getInstance().world;
        if (world == null) return;

        BlockState state = world.getBlockState(speakerPosition);
        if (!state.contains(Properties.HORIZONTAL_FACING)) return;

        Direction facing = state.get(Properties.HORIZONTAL_FACING);
        Vec3i facingVector = facing.getVector();
        float tiltRadians = (float) Math.toRadians(tiltDegrees);
        float tiltCosine = (float) Math.cos(tiltRadians);
        float directionX = facingVector.getX() * tiltCosine;
        float directionY = (float) Math.sin(tiltRadians);
        float directionZ = facingVector.getZ() * tiltCosine;

        for (StreamSource source : session.getStreamSources()) {
            if (source.pos.equals(speakerPosition) && !AudioEngine.TYPE_SUB.equals(source.speakerType)) {
                alSource3f(source.sourceId, AL_DIRECTION, directionX, directionY, directionZ);
            }
        }
    }

    void applyChannelMaskToSpeaker(BlockPos speakerPosition, int mask) {
        for (PlaybackSession session : sessions.values()) {
            if (!session.isPlaying()) continue;

            List<BlockPos> positions = session.getStreamSources().stream()
                    .map(source -> source.pos)
                    .distinct()
                    .toList();
            List<BlockPos> targetCluster = findContainingCluster(positions, speakerPosition);
            if (targetCluster == null) {
                applyChannelMask(session, List.of(speakerPosition), mask);
            } else {
                applyChannelMask(session, targetCluster, mask);
            }
        }
    }

    private List<BlockPos> findContainingCluster(List<BlockPos> positions, BlockPos speakerPosition) {
        for (List<BlockPos> cluster : SpeakerClusterer.clusterSpeakers(positions)) {
            if (cluster.contains(speakerPosition)) {
                return cluster;
            }
        }
        return null;
    }

    private void applyChannelMask(PlaybackSession session, List<BlockPos> positions, int mask) {
        for (BlockPos position : positions) {
            updateClientBlockEntityChannel(position, mask);
            for (StreamSource source : session.getStreamSources()) {
                if (source.pos.equals(position)) {
                    source.setChannelMask(mask);
                }
            }
        }
    }

    private void updateClientBlockEntityChannel(BlockPos position, int mask) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.world == null) return;

        BlockEntity blockEntity = client.world.getBlockEntity(position);
        if (blockEntity instanceof SpeakerBlockEntity speaker) {
            speaker.setChannelMask(mask);
        }
    }

    private PlaybackSession activeSession() {
        return activeSessionSupplier.get();
    }

    private boolean isLocalSession(UUID sessionId) {
        MinecraftClient client = MinecraftClient.getInstance();
        return client.player != null && client.player.getUuid().equals(sessionId);
    }

    private ItemStack getTabletStack() {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null) return ItemStack.EMPTY;

        ItemStack mainHand = client.player.getMainHandStack();
        if (mainHand.getItem() instanceof AmplifierTabletItem) return mainHand;

        ItemStack offHand = client.player.getOffHandStack();
        if (offHand.getItem() instanceof AmplifierTabletItem) return offHand;
        return ItemStack.EMPTY;
    }

    private float getTabletNbtValue(String key) {
        ItemStack stack = getTabletStack();
        if (stack.isEmpty()) return NBT_NOT_FOUND;

        NbtCompound nbt = stack.getNbt();
        if (nbt != null && nbt.contains(key)) return nbt.getFloat(key);
        return NBT_NOT_FOUND;
    }

    private void setTabletNbtValue(String key, float value) {
        ItemStack stack = getTabletStack();
        if (stack.isEmpty()) return;
        stack.getOrCreateNbt().putFloat(key, value);
    }
}
