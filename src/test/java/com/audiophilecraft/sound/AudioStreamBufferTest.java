package com.audiophilecraft.sound;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.nio.ShortBuffer;
import org.junit.jupiter.api.Test;

class AudioStreamBufferTest {
    @Test
    void readsLeftRightAndBothFromTheSameStereoFrame() {
        AudioStreamBuffer buffer = streamingBuffer(new short[] {100, 300, -400, 200}, 2, 2);

        assertEquals(100, buffer.getSample(0, 1));
        assertEquals(300, buffer.getSample(0, 2));
        assertEquals(200, buffer.getSample(0, 0));
        assertEquals(-100, buffer.getSample(1, 0));
    }

    @Test
    void returnsSilenceUntilAFrameHasBeenDecoded() {
        AudioStreamBuffer buffer = streamingBuffer(new short[] {10, 20, 30, 40}, 1, 2);

        assertEquals(0, buffer.getSample(1, 1));

        buffer.updateDecodedLength(2);
        assertEquals(30, buffer.getSample(1, 1));
        assertEquals(40, buffer.getSample(1, 2));
    }

    @Test
    void completedDecodeReplacesTheEstimatedDuration() {
        AudioStreamBuffer buffer = streamingBuffer(new short[] {10, 20, 30, 40}, 2, 100);

        buffer.completeStreaming(2);

        assertEquals(2, buffer.getTotalSamples());
        assertEquals(1.0, buffer.getTotalDurationSeconds(), 0.0001);
    }

    @Test
    void seekClampsToTheAvailableFrames() {
        AudioStreamBuffer buffer = streamingBuffer(new short[] {10, 20, 30, 40}, 2, 2);

        buffer.seekToTime(100.0);
        assertEquals(1, buffer.getWriteCursor());

        buffer.seekToTime(-10.0);
        assertEquals(0, buffer.getWriteCursor());
    }

    @Test
    void hugeStreamingFramePositionCannotWrapBackToTheStart() {
        AudioStreamBuffer buffer = streamingBuffer(new short[] {123, 456}, 1, 1);

        assertEquals(0, buffer.getSample(1L << 32, 1));
    }

    @Test
    void hugeLegacyFramePositionCannotWrapBackToTheStart() {
        AudioStreamBuffer buffer = new AudioStreamBuffer("legacy", 48_000);
        buffer.setSourceData(ShortBuffer.wrap(new short[] {123, 456}));

        assertEquals(0, buffer.getSample(1L << 32, 1));
    }

    @Test
    void completedDecodeLengthIsClampedToTheBackingArray() {
        AudioStreamBuffer buffer = streamingBuffer(new short[] {10, 20, 30, 40}, 2, 100);

        buffer.completeStreaming(-10);
        assertEquals(0, buffer.getTotalSamples());
        assertEquals(0.0, buffer.getTotalDurationSeconds(), 0.0001);

        buffer.initStreaming(new short[] {10, 20, 30, 40}, 2, 100);
        buffer.completeStreaming(1_000);
        assertEquals(2, buffer.getTotalSamples());
        assertEquals(1.0, buffer.getTotalDurationSeconds(), 0.0001);
    }

    @Test
    void decodedLengthCannotExposeFramesBeyondTheBackingArray() {
        AudioStreamBuffer buffer = streamingBuffer(new short[] {10, 20}, 1_000, 1_000);

        assertEquals(10, buffer.getSample(0, 1));
        assertEquals(0, buffer.getSample(1, 1));
    }

    @Test
    void advanceWaitsForAnUndecodedFrameWithoutSkippingIt() {
        AudioStreamBuffer buffer = streamingBuffer(new short[] {10, 20}, 0, 1);

        buffer.advance(1);
        assertEquals(0, buffer.getSample(0));

        buffer.updateDecodedLength(1);
        buffer.advance(1);
        assertEquals(15, buffer.getSample(1));
    }

    @Test
    void legacyStereoReadsAreAbsoluteAndPreserveBufferPosition() {
        ShortBuffer source = ShortBuffer.wrap(new short[] {100, 300, -400, 200});
        AudioStreamBuffer buffer = new AudioStreamBuffer("legacy", 2);
        buffer.setSourceData(source);

        assertEquals(100, buffer.getSample(0, 1));
        assertEquals(300, buffer.getSample(0, 2));
        assertEquals(-100, buffer.getSample(1, 0));
        assertEquals(0, source.position());
    }

    @Test
    void integerInterpolationReturnsTheOriginalChannelSample() {
        AudioStreamBuffer buffer = streamingBuffer(new short[] {0, 0, 1_000, 2_000, 3_000, 4_000}, 3, 3);

        assertEquals(1_000, buffer.getSampleLagrange(1.0, 1));
        assertEquals(2_000, buffer.getSampleLagrange(1.0, 2));
    }

    @Test
    void cleanupClearsStreamingMetadataAndData() {
        AudioStreamBuffer buffer = streamingBuffer(new short[] {10, 20}, 1, 1);

        buffer.cleanup();

        assertNull(buffer.getPcmArray());
        assertEquals(0, buffer.getTotalSamples());
        assertEquals(0.0, buffer.getTotalDurationSeconds(), 0.0001);
        assertEquals(0, buffer.getSample(0, 1));
    }

    private static AudioStreamBuffer streamingBuffer(short[] samples, int initialDecodedFrames, int expectedFrames) {
        AudioStreamBuffer buffer = new AudioStreamBuffer("test", 2);
        buffer.initStreaming(samples, initialDecodedFrames, expectedFrames);
        return buffer;
    }
}
