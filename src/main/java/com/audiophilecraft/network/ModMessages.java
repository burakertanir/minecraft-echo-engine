package com.audiophilecraft.network;

import com.audiophilecraft.AudiophileCraft;
import com.audiophilecraft.sound.SpeakerPlaybackData;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.Hand;
import net.minecraft.util.Identifier;

/** Packet identifiers and the stable registration facade for audio networking. */
public final class ModMessages {
    private static final int EQ_BAND_COUNT = 5;
    private static final Set<String> VALID_SPEAKER_TYPES = Set.of("normal", "sub", "mid", "line");

    public static final Identifier C2S_UPDATE_POWER = id("c2s_update_power");
    public static final Identifier S2C_SYNC_POWER = id("s2c_sync_power");
    public static final Identifier C2S_UPDATE_INPUT_GAIN = id("c2s_update_input_gain");
    public static final Identifier S2C_SYNC_INPUT_GAIN = id("s2c_sync_input_gain");
    public static final Identifier C2S_UPDATE_SPEAKER_SHIFT = id("c2s_update_speaker_shift");
    public static final Identifier C2S_PLAY_URL = id("c2s_play_url");
    public static final Identifier S2C_PLAY_URL = id("s2c_play_url");
    public static final Identifier C2S_UPDATE_TILT = id("c2s_update_tilt");
    public static final Identifier S2C_PLAY_TRACK = id("s2c_play_track");
    public static final Identifier C2S_SEEK_TRACK = id("c2s_seek_track");
    public static final Identifier C2S_UPDATE_EQ = id("c2s_update_eq");
    public static final Identifier S2C_SYNC_EQ = id("s2c_sync_eq");
    public static final Identifier S2C_SEEK_TRACK = id("s2c_seek_track");
    public static final Identifier C2S_UPDATE_EQ_Q = id("c2s_update_eq_q");
    public static final Identifier S2C_SYNC_EQ_Q = id("s2c_sync_eq_q");
    public static final Identifier C2S_UPDATE_MIXER_GAIN = id("c2s_update_mixer_gain");
    public static final Identifier S2C_SYNC_MIXER_GAIN = id("s2c_sync_mixer_gain");
    public static final Identifier C2S_STOP_AUDIO = id("c2s_stop_audio");
    public static final Identifier S2C_STOP_AUDIO = id("s2c_stop_audio");
    public static final Identifier C2S_TOGGLE_PAUSE = id("c2s_toggle_pause");
    public static final Identifier S2C_TOGGLE_PAUSE = id("s2c_toggle_pause");
    public static final Identifier C2S_CHANNEL_MASK = id("c2s_channel_mask");
    public static final Identifier S2C_SYNC_CHANNEL_MASK = id("s2c_sync_channel_mask");

    public static final Identifier C2S_PLAYBACK_READY = id("c2s_playback_ready");
    public static final Identifier S2C_START_PLAYBACK = id("s2c_start_playback");
    public static final Identifier S2C_PREP_SEEK = id("s2c_prep_seek");
    public static final Identifier C2S_SEEK_READY = id("c2s_seek_ready");
    public static final Identifier S2C_SYNC_SEEK = id("s2c_sync_seek");

    private ModMessages() {}

    public static void registerC2SPackets() {
        ServerAudioNetworkHandler.register();
    }

    public static void registerS2CPackets() {
        ClientAudioNetworkHandler.register();
    }

    public static void tickPendingSyncs(MinecraftServer server) {
        ServerAudioNetworkHandler.tickPendingSyncs(server);
    }

    public static void clearPendingSyncs() {
        ServerAudioNetworkHandler.clearPendingSyncs();
    }

    public static void cleanupDisconnectedPlayer(UUID playerUUID, MinecraftServer server) {
        ServerAudioNetworkHandler.cleanupDisconnectedPlayer(playerUUID, server);
    }

    public static void sendPlayTrack(
            ServerPlayerEntity player,
            UUID ownerUUID,
            String trackId,
            List<SpeakerPlaybackData> speakers,
            float power,
            float inputGain) {
        ServerAudioNetworkHandler.sendPlayTrack(player, ownerUUID, trackId, speakers, power, inputGain);
    }

    public static void sendPlayUrl(
            ServerPlayerEntity player,
            UUID ownerUUID,
            String url,
            List<SpeakerPlaybackData> speakers,
            float power,
            float inputGain) {
        ServerAudioNetworkHandler.sendPlayUrl(player, ownerUUID, url, speakers, power, inputGain);
    }

    static boolean isValidHandOrdinal(int handOrdinal) {
        return handOrdinal >= 0 && handOrdinal < Hand.values().length;
    }

    static boolean isFiniteInRange(float value, float min, float max) {
        return Float.isFinite(value) && value >= min && value <= max;
    }

    static boolean isValidSpeakerType(String speakerType) {
        return speakerType != null && VALID_SPEAKER_TYPES.contains(speakerType);
    }

    static boolean isValidEqBand(int band) {
        return band >= 0 && band < EQ_BAND_COUNT;
    }

    static boolean isValidAudioUrl(String value) {
        if (value == null || value.isBlank() || value.length() > 2048) return false;
        try {
            java.net.URI uri = java.net.URI.create(value);
            String scheme = uri.getScheme();
            return uri.getHost() != null
                    && uri.getUserInfo() == null
                    && ("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme));
        } catch (IllegalArgumentException ignored) {
            return false;
        }
    }

    private static Identifier id(String path) {
        return new Identifier(AudiophileCraft.MOD_ID, path);
    }
}
