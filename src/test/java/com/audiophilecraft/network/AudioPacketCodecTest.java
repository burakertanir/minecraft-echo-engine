package com.audiophilecraft.network;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.audiophilecraft.sound.SpeakerPlaybackData;
import io.netty.handler.codec.EncoderException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;
import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

class AudioPacketCodecTest {
    private static final UUID SESSION_UUID = UUID.fromString("9ec859d0-af67-4434-aa22-1094f3172410");
    private static final Identifier OVERWORLD = new Identifier("minecraft", "overworld");

    @Test
    void playbackPacketRoundTripsEveryFieldAndSpeakerVariant() {
        List<SpeakerPlaybackData> speakers = List.of(
                new SpeakerPlaybackData(new BlockPos(-12, 64, 30), "normal", Direction.NORTH, -70, 0, 0),
                new SpeakerPlaybackData(new BlockPos(5, -20, -7), "sub", Direction.EAST, 15, 10, 1),
                new SpeakerPlaybackData(new BlockPos(99, 319, 101), "mid", Direction.SOUTH, 0, 20, 2),
                new SpeakerPlaybackData(new BlockPos(0, 0, 0), "line", Direction.WEST, 70, 30, 0));
        PacketByteBuf buf = AudioPacketCodec.createPlaybackBuffer(
                OVERWORLD,
                SESSION_UUID,
                "https://example.com/live.ogg",
                AudioPacketCodec.MAX_URL_LENGTH,
                speakers,
                10.0f,
                3.0f);

        try {
            AudioPacketCodec.PlaybackPacket decoded =
                    AudioPacketCodec.readPlayback(buf, AudioPacketCodec.MAX_URL_LENGTH);

            assertEquals(SESSION_UUID, decoded.sessionUUID());
            assertEquals(OVERWORLD, decoded.playbackDimension());
            assertEquals("https://example.com/live.ogg", decoded.source());
            assertEquals(10.0f, decoded.power());
            assertEquals(3.0f, decoded.inputGain());
            assertEquals(speakers, decoded.speakers());
            assertEquals(0, buf.readableBytes());
        } finally {
            buf.release();
        }
    }

    @Test
    void decodedSpeakerListCannotBeMutatedThroughTheSourceList() {
        ArrayList<SpeakerPlaybackData> speakers = new ArrayList<>();
        speakers.add(new SpeakerPlaybackData(BlockPos.ORIGIN, "normal", Direction.SOUTH, 0, 0, 0));
        AudioPacketCodec.PlaybackPacket packet =
                new AudioPacketCodec.PlaybackPacket(SESSION_UUID, OVERWORLD, "track", 1.0f, 1.0f, speakers);

        speakers.clear();

        assertEquals(1, packet.speakers().size());
        assertNotSame(speakers, packet.speakers());
        assertThrows(UnsupportedOperationException.class, () -> packet.speakers()
                .add(new SpeakerPlaybackData(BlockPos.ORIGIN, "sub", Direction.SOUTH, 0, 0, 0)));
    }

    @ParameterizedTest
    @ValueSource(ints = {-1, 4097})
    void rejectsInvalidSpeakerCounts(int speakerCount) {
        PacketByteBuf buf = playbackHeader(1.0f, 1.0f, speakerCount);
        try {
            assertNull(AudioPacketCodec.readPlayback(buf, AudioPacketCodec.MAX_TRACK_ID_LENGTH));
        } finally {
            buf.release();
        }
    }

    @ParameterizedTest(name = "rejects malformed speaker metadata: {0}")
    @MethodSource("invalidSpeakerMetadata")
    void rejectsMalformedSpeakerMetadata(
            String ignoredDescription,
            String speakerType,
            int directionId,
            int verticalTilt,
            int sampleShift,
            int channelMask) {
        PacketByteBuf buf = playbackHeader(1.0f, 1.0f, 1);
        buf.writeBlockPos(BlockPos.ORIGIN);
        buf.writeString(speakerType, 16);
        buf.writeInt(directionId);
        buf.writeInt(verticalTilt);
        buf.writeInt(sampleShift);
        buf.writeInt(channelMask);

        try {
            assertNull(AudioPacketCodec.readPlayback(buf, AudioPacketCodec.MAX_TRACK_ID_LENGTH));
        } finally {
            buf.release();
        }
    }

    @ParameterizedTest(name = "rejects invalid playback levels: {0}")
    @MethodSource("invalidPlaybackLevels")
    void rejectsInvalidPlaybackLevels(String ignoredDescription, float power, float inputGain) {
        PacketByteBuf buf = playbackHeader(power, inputGain, 0);
        try {
            assertNull(AudioPacketCodec.readPlayback(buf, AudioPacketCodec.MAX_TRACK_ID_LENGTH));
        } finally {
            buf.release();
        }
    }

    @Test
    void rejectsPlaybackPacketTruncatedInsideSpeakerData() {
        PacketByteBuf buf = playbackHeader(1.0f, 1.0f, 1);
        buf.writeBlockPos(BlockPos.ORIGIN);

        try {
            assertNull(AudioPacketCodec.readPlayback(buf, AudioPacketCodec.MAX_TRACK_ID_LENGTH));
        } finally {
            buf.release();
        }
    }

    @Test
    void rejectsSourceLongerThanThePacketTypeAllows() {
        PacketByteBuf buf = PacketByteBufs.create();
        buf.writeUuid(SESSION_UUID);
        buf.writeIdentifier(OVERWORLD);
        buf.writeString("x".repeat(AudioPacketCodec.MAX_TRACK_ID_LENGTH + 1), AudioPacketCodec.MAX_URL_LENGTH);
        buf.writeFloat(1.0f);
        buf.writeFloat(1.0f);
        buf.writeInt(0);

        try {
            assertNull(AudioPacketCodec.readPlayback(buf, AudioPacketCodec.MAX_TRACK_ID_LENGTH));
        } finally {
            buf.release();
        }
    }

    @Test
    void writerRejectsSourceLongerThanItsDeclaredPacketLimit() {
        List<SpeakerPlaybackData> speakers = List.of();

        assertThrows(
                EncoderException.class,
                () -> AudioPacketCodec.createPlaybackBuffer(
                        OVERWORLD,
                        SESSION_UUID,
                        "x".repeat(AudioPacketCodec.MAX_TRACK_ID_LENGTH + 1),
                        AudioPacketCodec.MAX_TRACK_ID_LENGTH,
                        speakers,
                        1.0f,
                        1.0f));
    }

    @Test
    void seekPreparationPacketRoundTripsAtZeroAndPreservesDimension() {
        PacketByteBuf buf = AudioPacketCodec.createSeekPrepBuffer(SESSION_UUID, OVERWORLD, 0.0f);
        try {
            AudioPacketCodec.SeekPrepPacket decoded = AudioPacketCodec.readSeekPrep(buf);

            assertEquals(SESSION_UUID, decoded.sessionUUID());
            assertEquals(OVERWORLD, decoded.playbackDimension());
            assertEquals(0.0f, decoded.targetTime());
            assertEquals(0, buf.readableBytes());
        } finally {
            buf.release();
        }
    }

    @ParameterizedTest
    @ValueSource(floats = {-0.01f, Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY})
    void rejectsInvalidSeekTargets(float targetTime) {
        PacketByteBuf buf = AudioPacketCodec.createSeekPrepBuffer(SESSION_UUID, OVERWORLD, targetTime);
        try {
            assertNull(AudioPacketCodec.readSeekPrep(buf));
        } finally {
            buf.release();
        }
    }

    @Test
    void rejectsTruncatedSeekPreparationPacket() {
        PacketByteBuf buf = PacketByteBufs.create();
        buf.writeUuid(SESSION_UUID);
        buf.writeIdentifier(OVERWORLD);

        try {
            assertNull(AudioPacketCodec.readSeekPrep(buf));
        } finally {
            buf.release();
        }
    }

    private static PacketByteBuf playbackHeader(float power, float inputGain, int speakerCount) {
        PacketByteBuf buf = PacketByteBufs.create();
        buf.writeUuid(SESSION_UUID);
        buf.writeIdentifier(OVERWORLD);
        buf.writeString("test_track");
        buf.writeFloat(power);
        buf.writeFloat(inputGain);
        buf.writeInt(speakerCount);
        return buf;
    }

    private static Stream<Arguments> invalidSpeakerMetadata() {
        return Stream.of(
                Arguments.of("unknown type", "unknown", Direction.SOUTH.getId(), 0, 0, 0),
                Arguments.of("negative direction", "normal", -1, 0, 0, 0),
                Arguments.of("oversized direction", "normal", Direction.values().length, 0, 0, 0),
                Arguments.of("tilt below minimum", "normal", Direction.SOUTH.getId(), -71, 0, 0),
                Arguments.of("tilt above maximum", "normal", Direction.SOUTH.getId(), 71, 0, 0),
                Arguments.of("negative sample shift", "normal", Direction.SOUTH.getId(), 0, -1, 0),
                Arguments.of("sample shift above maximum", "normal", Direction.SOUTH.getId(), 0, 31, 0),
                Arguments.of("negative channel mask", "normal", Direction.SOUTH.getId(), 0, 0, -1),
                Arguments.of("channel mask above maximum", "normal", Direction.SOUTH.getId(), 0, 0, 3));
    }

    private static Stream<Arguments> invalidPlaybackLevels() {
        return Stream.of(
                Arguments.of("power below minimum", 0.09f, 1.0f),
                Arguments.of("power above maximum", 10.01f, 1.0f),
                Arguments.of("power NaN", Float.NaN, 1.0f),
                Arguments.of("power positive infinity", Float.POSITIVE_INFINITY, 1.0f),
                Arguments.of("input gain below minimum", 1.0f, -0.01f),
                Arguments.of("input gain above maximum", 1.0f, 3.01f),
                Arguments.of("input gain NaN", 1.0f, Float.NaN),
                Arguments.of("input gain positive infinity", 1.0f, Float.POSITIVE_INFINITY));
    }
}
