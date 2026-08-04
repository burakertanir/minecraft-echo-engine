package com.audiophilecraft.network;

import static com.audiophilecraft.network.ModMessages.C2S_PLAYBACK_READY;
import static com.audiophilecraft.network.ModMessages.C2S_SEEK_READY;
import static com.audiophilecraft.network.ModMessages.S2C_PLAY_TRACK;
import static com.audiophilecraft.network.ModMessages.S2C_PLAY_URL;
import static com.audiophilecraft.network.ModMessages.S2C_PREP_SEEK;
import static com.audiophilecraft.network.ModMessages.S2C_SEEK_TRACK;
import static com.audiophilecraft.network.ModMessages.S2C_START_PLAYBACK;
import static com.audiophilecraft.network.ModMessages.S2C_STOP_AUDIO;
import static com.audiophilecraft.network.ModMessages.S2C_SYNC_CHANNEL_MASK;
import static com.audiophilecraft.network.ModMessages.S2C_SYNC_EQ;
import static com.audiophilecraft.network.ModMessages.S2C_SYNC_EQ_Q;
import static com.audiophilecraft.network.ModMessages.S2C_SYNC_INPUT_GAIN;
import static com.audiophilecraft.network.ModMessages.S2C_SYNC_MIXER_GAIN;
import static com.audiophilecraft.network.ModMessages.S2C_SYNC_POWER;
import static com.audiophilecraft.network.ModMessages.S2C_SYNC_SEEK;
import static com.audiophilecraft.network.ModMessages.S2C_TOGGLE_PAUSE;

import com.audiophilecraft.client.screen.AmplifierScreen;
import com.audiophilecraft.sound.AudioEngine;
import com.audiophilecraft.sound.SpeakerPlaybackData;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;

/** Owns S2C packet decoding and client-side AudioEngine or screen updates. */
final class ClientAudioNetworkHandler {
    private static final int MAX_SPEAKERS_PER_PACKET = 4096;

    private ClientAudioNetworkHandler() {}

    static void register() {
        registerPlaybackPackets();
        registerTransportPackets();
        registerControlSyncPackets();
    }

    private static void registerPlaybackPackets() {
        ClientPlayNetworking.registerGlobalReceiver(S2C_PLAY_TRACK, (client, handler, buf, responseSender) -> {
            UUID sessionUUID = buf.readUuid();
            String trackId = buf.readString(256);
            float power = buf.readFloat();
            float inputGain = buf.readFloat();
            List<SpeakerPlaybackData> speakers = readSpeakerPlaybackData(buf);
            if (speakers == null
                    || trackId.isBlank()
                    || !ModMessages.isFiniteInRange(power, 0.1f, 10.0f)
                    || !ModMessages.isFiniteInRange(inputGain, 0.0f, 3.0f)) return;

            client.execute(() -> AudioEngine.getInstance()
                    .playTrackWithSpeakerData(sessionUUID, trackId, speakers, power, inputGain));
        });

        ClientPlayNetworking.registerGlobalReceiver(S2C_PLAY_URL, (client, handler, buf, responseSender) -> {
            UUID sessionUUID = buf.readUuid();
            String url = buf.readString(2048);
            float power = buf.readFloat();
            float inputGain = buf.readFloat();
            List<SpeakerPlaybackData> speakers = readSpeakerPlaybackData(buf);
            if (speakers == null
                    || !ModMessages.isValidAudioUrl(url)
                    || !ModMessages.isFiniteInRange(power, 0.1f, 10.0f)
                    || !ModMessages.isFiniteInRange(inputGain, 0.0f, 3.0f)) return;

            client.execute(() -> AudioEngine.getInstance()
                    .playFromUrl(sessionUUID, url, speakers, power, inputGain, false, loadedUUID -> {
                        PacketByteBuf readyBuf = PacketByteBufs.create();
                        readyBuf.writeUuid(loadedUUID);
                        ClientPlayNetworking.send(C2S_PLAYBACK_READY, readyBuf);
                    }));
        });

        ClientPlayNetworking.registerGlobalReceiver(S2C_START_PLAYBACK, (client, handler, buf, responseSender) -> {
            UUID sessionUUID = buf.readUuid();
            client.execute(() -> AudioEngine.getInstance().startSessionPlayback(sessionUUID));
        });
    }

    private static void registerTransportPackets() {
        ClientPlayNetworking.registerGlobalReceiver(S2C_PREP_SEEK, (client, handler, buf, responseSender) -> {
            UUID sessionUUID = buf.readUuid();
            float targetTime = buf.readFloat();
            if (!Float.isFinite(targetTime) || targetTime < 0.0f) return;

            client.execute(() -> {
                AudioEngine.getInstance().seekForSession(sessionUUID, targetTime);
                PacketByteBuf readyBuf = PacketByteBufs.create();
                readyBuf.writeUuid(sessionUUID);
                readyBuf.writeFloat(targetTime);
                ClientPlayNetworking.send(C2S_SEEK_READY, readyBuf);
            });
        });

        ClientPlayNetworking.registerGlobalReceiver(S2C_SYNC_SEEK, (client, handler, buf, responseSender) -> {
            // PREP_SEEK already moved every client; this packet is the shared sync barrier.
        });

        ClientPlayNetworking.registerGlobalReceiver(S2C_SEEK_TRACK, (client, handler, buf, responseSender) -> {
            UUID sessionUUID = buf.readUuid();
            float targetTime = buf.readFloat();
            if (!Float.isFinite(targetTime) || targetTime < 0.0f) return;
            client.execute(() -> AudioEngine.getInstance().seekForSession(sessionUUID, targetTime));
        });

        ClientPlayNetworking.registerGlobalReceiver(S2C_STOP_AUDIO, (client, handler, buf, responseSender) -> {
            UUID sessionUUID = buf.readUuid();
            client.execute(() -> AudioEngine.getInstance().stopSession(sessionUUID));
        });

        ClientPlayNetworking.registerGlobalReceiver(S2C_TOGGLE_PAUSE, (client, handler, buf, responseSender) -> {
            UUID sessionUUID = buf.readUuid();
            client.execute(() -> AudioEngine.getInstance().toggleManualPause(sessionUUID));
        });
    }

    private static void registerControlSyncPackets() {
        ClientPlayNetworking.registerGlobalReceiver(S2C_SYNC_INPUT_GAIN, (client, handler, buf, responseSender) -> {
            UUID sessionUUID = buf.readUuid();
            int handOrdinal = buf.readInt();
            float gain = buf.readFloat();
            if (!ModMessages.isValidHandOrdinal(handOrdinal) || !ModMessages.isFiniteInRange(gain, 0.0f, 3.0f)) return;

            client.execute(() -> {
                boolean isSelf = client.player != null && sessionUUID.equals(client.player.getUuid());
                if (isSelf) {
                    if (client.currentScreen instanceof AmplifierScreen screen) screen.updateInputGain(gain);
                    return;
                }
                AudioEngine.getInstance().updateInputGainForSession(sessionUUID, gain);
            });
        });

        ClientPlayNetworking.registerGlobalReceiver(S2C_SYNC_POWER, (client, handler, buf, responseSender) -> {
            UUID sessionUUID = buf.readUuid();
            int handOrdinal = buf.readInt();
            float power = buf.readFloat();
            if (!ModMessages.isValidHandOrdinal(handOrdinal) || !ModMessages.isFiniteInRange(power, 0.1f, 10.0f))
                return;

            client.execute(() -> {
                boolean isSelf = client.player != null && sessionUUID.equals(client.player.getUuid());
                if (isSelf) {
                    if (client.currentScreen instanceof AmplifierScreen screen) screen.updateSpeakerPower(power);
                    return;
                }
                AudioEngine.getInstance().updatePowerForSession(sessionUUID, power);
            });
        });

        ClientPlayNetworking.registerGlobalReceiver(S2C_SYNC_EQ, (client, handler, buf, responseSender) -> {
            UUID sessionUUID = buf.readUuid();
            String speakerType = buf.readString(16);
            int band = buf.readInt();
            float db = buf.readFloat();
            if (!ModMessages.isValidSpeakerType(speakerType)
                    || !ModMessages.isValidEqBand(band)
                    || !ModMessages.isFiniteInRange(db, -9.0f, 9.0f)) return;
            client.execute(() -> AudioEngine.getInstance().setEqDbForSession(sessionUUID, speakerType, band, db));
        });

        ClientPlayNetworking.registerGlobalReceiver(S2C_SYNC_EQ_Q, (client, handler, buf, responseSender) -> {
            UUID sessionUUID = buf.readUuid();
            String speakerType = buf.readString(16);
            int band = buf.readInt();
            float q = buf.readFloat();
            if (!ModMessages.isValidSpeakerType(speakerType)
                    || !ModMessages.isValidEqBand(band)
                    || !ModMessages.isFiniteInRange(q, 0.1f, 10.0f)) return;
            client.execute(() -> AudioEngine.getInstance().setEqQForSession(sessionUUID, speakerType, band, q));
        });

        ClientPlayNetworking.registerGlobalReceiver(S2C_SYNC_MIXER_GAIN, (client, handler, buf, responseSender) -> {
            UUID sessionUUID = buf.readUuid();
            String speakerType = buf.readString(16);
            float gain = buf.readFloat();
            if (!ModMessages.isValidSpeakerType(speakerType) || !ModMessages.isFiniteInRange(gain, 0.0f, 1.0f)) return;
            client.execute(() -> AudioEngine.getInstance().setMixerGainForSession(sessionUUID, speakerType, gain));
        });

        ClientPlayNetworking.registerGlobalReceiver(S2C_SYNC_CHANNEL_MASK, (client, handler, buf, responseSender) -> {
            UUID senderUUID = buf.readUuid();
            int mask = buf.readInt();
            List<BlockPos> cluster = readSpeakerPositions(buf);
            if (cluster == null || mask < 0 || mask > 2) return;

            client.execute(() -> {
                if (client.player != null && senderUUID.equals(client.player.getUuid())) return;
                AudioEngine engine = AudioEngine.getInstance();
                for (BlockPos position : cluster) engine.applyChannelMaskToSpeaker(position, mask);
            });
        });
    }

    private static List<BlockPos> readSpeakerPositions(PacketByteBuf buf) {
        int count = buf.readInt();
        if (count < 0 || count > MAX_SPEAKERS_PER_PACKET) return null;

        List<BlockPos> speakers = new ArrayList<>(count);
        for (int i = 0; i < count; i++) speakers.add(buf.readBlockPos());
        return speakers;
    }

    private static List<SpeakerPlaybackData> readSpeakerPlaybackData(PacketByteBuf buf) {
        int count = buf.readInt();
        if (count < 0 || count > MAX_SPEAKERS_PER_PACKET) return null;

        List<SpeakerPlaybackData> speakers = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            BlockPos position = buf.readBlockPos();
            String speakerType = buf.readString(16);
            int directionId = buf.readInt();
            int verticalTilt = buf.readInt();
            int sampleShift = buf.readInt();
            int channelMask = buf.readInt();
            if (!ModMessages.isValidSpeakerType(speakerType)
                    || directionId < 0
                    || directionId >= Direction.values().length
                    || verticalTilt < -70
                    || verticalTilt > 70
                    || sampleShift < 0
                    || sampleShift > 30
                    || channelMask < 0
                    || channelMask > 2) return null;
            speakers.add(new SpeakerPlaybackData(
                    position, speakerType, Direction.byId(directionId), verticalTilt, sampleShift, channelMask));
        }
        return speakers;
    }
}
