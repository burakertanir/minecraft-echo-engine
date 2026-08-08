package com.audiophilecraft.network;

import com.audiophilecraft.sound.SpeakerPlaybackData;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;

/** Shared wire format for the multi-field playback and synchronized-seek packets. */
final class AudioPacketCodec {
    static final int MAX_SPEAKERS_PER_PACKET = 4096;
    static final int MAX_TRACK_ID_LENGTH = 256;
    static final int MAX_URL_LENGTH = 2048;

    private AudioPacketCodec() {}

    static PacketByteBuf createPlaybackBuffer(
            Identifier playbackDimension,
            UUID sessionUUID,
            String source,
            int maxSourceLength,
            List<SpeakerPlaybackData> speakers,
            float power,
            float inputGain) {
        PacketByteBuf buf = PacketByteBufs.create();
        buf.writeUuid(sessionUUID);
        buf.writeIdentifier(playbackDimension);
        buf.writeString(source, maxSourceLength);
        buf.writeFloat(power);
        buf.writeFloat(inputGain);
        buf.writeInt(speakers.size());
        for (SpeakerPlaybackData speaker : speakers) {
            buf.writeBlockPos(speaker.position());
            buf.writeString(speaker.speakerType(), 16);
            buf.writeInt(speaker.facing().getId());
            buf.writeInt(speaker.verticalTiltDeg());
            buf.writeInt(speaker.sampleShiftMs());
            buf.writeInt(speaker.channelMask());
        }
        return buf;
    }

    static PlaybackPacket readPlayback(PacketByteBuf buf, int maxSourceLength) {
        try {
            UUID sessionUUID = buf.readUuid();
            Identifier playbackDimension = buf.readIdentifier();
            String source = buf.readString(maxSourceLength);
            float power = buf.readFloat();
            float inputGain = buf.readFloat();
            List<SpeakerPlaybackData> speakers = readSpeakers(buf);
            if (speakers == null
                    || !ModMessages.isFiniteInRange(power, 0.1f, 10.0f)
                    || !ModMessages.isFiniteInRange(inputGain, 0.0f, 3.0f)) return null;
            return new PlaybackPacket(sessionUUID, playbackDimension, source, power, inputGain, speakers);
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    static PacketByteBuf createSeekPrepBuffer(UUID sessionUUID, Identifier playbackDimension, float targetTime) {
        PacketByteBuf buf = PacketByteBufs.create();
        buf.writeUuid(sessionUUID);
        buf.writeIdentifier(playbackDimension);
        buf.writeFloat(targetTime);
        return buf;
    }

    static SeekPrepPacket readSeekPrep(PacketByteBuf buf) {
        try {
            UUID sessionUUID = buf.readUuid();
            Identifier playbackDimension = buf.readIdentifier();
            float targetTime = buf.readFloat();
            if (!Float.isFinite(targetTime) || targetTime < 0.0f) return null;
            return new SeekPrepPacket(sessionUUID, playbackDimension, targetTime);
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private static List<SpeakerPlaybackData> readSpeakers(PacketByteBuf buf) {
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

    record PlaybackPacket(
            UUID sessionUUID,
            Identifier playbackDimension,
            String source,
            float power,
            float inputGain,
            List<SpeakerPlaybackData> speakers) {
        PlaybackPacket {
            speakers = List.copyOf(speakers);
        }
    }

    record SeekPrepPacket(UUID sessionUUID, Identifier playbackDimension, float targetTime) {}
}
