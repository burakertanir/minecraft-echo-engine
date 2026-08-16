package com.audiophilecraft.network;

import static com.audiophilecraft.network.ModMessages.C2S_PLAYBACK_FAILED;
import static com.audiophilecraft.network.ModMessages.C2S_PLAYBACK_READY;
import static com.audiophilecraft.network.ModMessages.C2S_SEEK_READY;
import static com.audiophilecraft.network.ModMessages.S2C_PLAY_TRACK;
import static com.audiophilecraft.network.ModMessages.S2C_PLAY_URL;
import static com.audiophilecraft.network.ModMessages.S2C_PREP_SEEK;
import static com.audiophilecraft.network.ModMessages.S2C_SEEK_TRACK;
import static com.audiophilecraft.network.ModMessages.S2C_SPEAKER_OWNERS;
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
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;

/** Owns S2C packet decoding and client-side AudioEngine or screen updates. */
final class ClientAudioNetworkHandler {
    private ClientAudioNetworkHandler() {}

    static void register() {
        registerPlaybackPackets();
        registerTransportPackets();
        registerControlSyncPackets();
    }

    private static void registerPlaybackPackets() {
        ClientPlayNetworking.registerGlobalReceiver(S2C_PLAY_TRACK, (client, handler, buf, responseSender) -> {
            AudioPacketCodec.PlaybackPacket packet =
                    AudioPacketCodec.readPlayback(buf, AudioPacketCodec.MAX_TRACK_ID_LENGTH);
            if (packet == null || packet.source().isBlank()) return;

            client.execute(() -> {
                if (!isCurrentDimension(client, packet.playbackDimension())) return;
                AudioEngine.getInstance()
                        .playTrackWithSpeakerData(
                                packet.sessionUUID(),
                                packet.source(),
                                packet.speakers(),
                                packet.power(),
                                packet.inputGain());
            });
        });

        ClientPlayNetworking.registerGlobalReceiver(S2C_PLAY_URL, (client, handler, buf, responseSender) -> {
            AudioPacketCodec.PlaybackPacket packet =
                    AudioPacketCodec.readPlayback(buf, AudioPacketCodec.MAX_URL_LENGTH);
            if (packet == null || !ModMessages.isValidAudioUrl(packet.source())) return;

            client.execute(() -> {
                if (!isCurrentDimension(client, packet.playbackDimension())) {
                    sendPlaybackFailed(packet.sessionUUID());
                    return;
                }
                AudioEngine.getInstance()
                        .playFromUrl(
                                packet.sessionUUID(),
                                packet.source(),
                                packet.speakers(),
                                packet.power(),
                                packet.inputGain(),
                                false,
                                ClientAudioNetworkHandler::sendPlaybackReady,
                                ClientAudioNetworkHandler::sendPlaybackFailed);
            });
        });

        ClientPlayNetworking.registerGlobalReceiver(S2C_START_PLAYBACK, (client, handler, buf, responseSender) -> {
            UUID sessionUUID = buf.readUuid();
            client.execute(() -> AudioEngine.getInstance().startSessionPlayback(sessionUUID));
        });
    }

    private static void registerTransportPackets() {
        ClientPlayNetworking.registerGlobalReceiver(S2C_PREP_SEEK, (client, handler, buf, responseSender) -> {
            AudioPacketCodec.SeekPrepPacket packet = AudioPacketCodec.readSeekPrep(buf);
            if (packet == null) return;

            client.execute(() -> {
                if (isCurrentDimension(client, packet.playbackDimension())) {
                    AudioEngine.getInstance().seekForSession(packet.sessionUUID(), packet.targetTime());
                }
                PacketByteBuf readyBuf = PacketByteBufs.create();
                readyBuf.writeUuid(packet.sessionUUID());
                readyBuf.writeFloat(packet.targetTime());
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
        ClientPlayNetworking.registerGlobalReceiver(S2C_SPEAKER_OWNERS, (client, handler, buf, responseSender) -> {
            SpeakerOwnerListCodec.SpeakerOwnerList ownerList = SpeakerOwnerListCodec.read(buf);
            if (ownerList == null) return;
            client.execute(() -> {
                if (client.currentScreen instanceof AmplifierScreen screen) {
                    screen.updateSpeakerOwners(ownerList.owners(), ownerList.placementOwner());
                }
            });
        });

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

    private static boolean isCurrentDimension(
            net.minecraft.client.MinecraftClient client, Identifier playbackDimension) {
        return client.world != null
                && ModMessages.isMatchingDimension(client.world.getRegistryKey().getValue(), playbackDimension);
    }

    private static void sendPlaybackReady(UUID sessionUUID) {
        PacketByteBuf readyBuf = PacketByteBufs.create();
        readyBuf.writeUuid(sessionUUID);
        ClientPlayNetworking.send(C2S_PLAYBACK_READY, readyBuf);
    }

    private static void sendPlaybackFailed(UUID sessionUUID) {
        PacketByteBuf failedBuf = PacketByteBufs.create();
        failedBuf.writeUuid(sessionUUID);
        ClientPlayNetworking.send(C2S_PLAYBACK_FAILED, failedBuf);
    }

    private static List<BlockPos> readSpeakerPositions(PacketByteBuf buf) {
        int count = buf.readInt();
        if (count < 0 || count > AudioPacketCodec.MAX_SPEAKERS_PER_PACKET) return null;

        List<BlockPos> speakers = new ArrayList<>(count);
        for (int i = 0; i < count; i++) speakers.add(buf.readBlockPos());
        return speakers;
    }
}
