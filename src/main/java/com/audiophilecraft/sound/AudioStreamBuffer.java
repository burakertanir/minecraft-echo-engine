package com.audiophilecraft.sound;

import java.nio.ShortBuffer;
import org.lwjgl.system.MemoryUtil;

/**
 * Manages a global Ring Buffer for a specific audio track.
 * Supports both full-load and streaming (incremental decode) modes.
 * 
 * STREAMING MODE: Data is decoded in chunks on a background thread.
 * The ring buffer advance() reads from pcmArray up to decodedLength.
 * Samples beyond decodedLength return silence until decoded.
 */
public class AudioStreamBuffer {

    public final String trackId;
    public final int sampleRate;
    public final int channels;

    // The Ring Buffer (Stores ~47 seconds of audio)
    // 2097152 = 2^21 samples. At 44.1kHz -> ~47.5 seconds.
    // MUST BE A POWER OF TWO for bitwise masking.
    private final short[] ringBuffer;
    private final int bufferSize;
    private final int bufferMask; // Fast bitwise modulo

    // Heads
    private volatile long globalWriteCursor = 0; // Total samples written since start (volatile: read by audio thread)

    // ── STREAMING MODE FIELDS ──
    // Raw PCM array: entire track's worth of space, filled incrementally
    private short[] pcmArray;
    private volatile int decodedLength = 0; // How many mono samples have been decoded so far
    private int totalExpectedSamples = 0; // Total expected samples (from OGG header)
    private int readCursor = 0; // Sequential read position for advance()

    // Legacy mode (backward compatibility)
    private ShortBuffer fullPcmData; // Source data (entire track) — used by URL path

    public AudioStreamBuffer(String trackId, int sampleRate) {
        this.trackId = trackId;
        this.sampleRate = sampleRate;
        this.channels = 1;

        // 47 Seconds buffer for safety (max delay + jitter)
        this.bufferSize = 2097152;
        this.bufferMask = this.bufferSize - 1;
        this.ringBuffer = new short[bufferSize];
    }

    // ── STREAMING MODE SETUP ──

    /**
     * Initialize for streaming mode: allocates the full PCM array but marks
     * only initialLength samples as available. Background thread continues
     * filling via appendDecoded().
     */
    public void initStreaming(short[] data, int initialDecodedLength, int totalExpected) {
        this.pcmArray = data;
        this.decodedLength = initialDecodedLength;
        this.totalExpectedSamples = totalExpected;
        this.readCursor = 0;
        this.fullPcmData = null; // Disable legacy mode
    }

    /**
     * Update the decoded length (called from background decoder thread).
     * Thread-safe: only moves forward, audio thread reads up to this value.
     */
    public void updateDecodedLength(int newLength) {
        this.decodedLength = newLength;
    }

    // ── LEGACY MODE ──

    public void setSourceData(ShortBuffer pcm) {
        this.fullPcmData = pcm;
        this.pcmArray = null; // Disable streaming mode
    }

    // ── COMMON API ──

    public double getTotalDurationSeconds() {
        if (pcmArray != null && sampleRate > 0) {
            return (double) totalExpectedSamples / sampleRate;
        }
        if (fullPcmData != null && sampleRate > 0) {
            return (double) fullPcmData.capacity() / sampleRate;
        }
        return 0.0;
    }

    public void seekToTime(double timeSeconds) {
        if (sampleRate <= 0)
            return;

        long targetCursor = (long) (timeSeconds * sampleRate);

        // Ensure within bounds
        if (targetCursor < 0)
            targetCursor = 0;
        long maxSamples = getTotalSamples();
        if (targetCursor > maxSamples - 1)
            targetCursor = maxSamples - 1;

        // Reset read position
        if (pcmArray != null) {
            this.readCursor = (int) targetCursor;
        } else if (fullPcmData != null) {
            fullPcmData.position((int) targetCursor);
        }

        // Forcibly jump the stream cursor
        this.globalWriteCursor = targetCursor;

        // Flush ghost audio out of the ring buffer
        java.util.Arrays.fill(ringBuffer, (short) 0);
    }

    /**
     * Advances the write cursor and fills the ring buffer with new data.
     * Called every tick by the audio thread.
     */
    public void advance(int samplesNeeded) {
        if (samplesNeeded <= 0)
            return;

        if (pcmArray != null) {
            // ── STREAMING MODE ──
            int currentDecoded = decodedLength; // Snapshot volatile once
            for (int i = 0; i < samplesNeeded; i++) {
                short sample = 0;
                if (readCursor < currentDecoded) {
                    sample = pcmArray[readCursor++];
                } else if (readCursor < totalExpectedSamples) {
                    // Not yet decoded — output silence, don't advance read cursor
                    // This prevents skipping over not-yet-decoded audio
                    readCursor++;
                }

                int index = (int) (globalWriteCursor & bufferMask);
                ringBuffer[index] = sample;
                globalWriteCursor++;
            }
        } else if (fullPcmData != null) {
            // ── LEGACY MODE ──
            for (int i = 0; i < samplesNeeded; i++) {
                short sample = 0;
                if (fullPcmData.hasRemaining()) {
                    sample = fullPcmData.get();
                }

                int index = (int) (globalWriteCursor & bufferMask);
                ringBuffer[index] = sample;
                globalWriteCursor++;
            }
        }
    }

    /**
     * Synchronizes the buffer to a specific absolute time point in the track.
     * If we are behind, it fast-forwards.
     */
    public void syncToTime(double timeSeconds) {
        long targetCursor = (long) (timeSeconds * sampleRate);
        long diff = targetCursor - globalWriteCursor;

        // Prevent micro-jitter from violently scrubbing backwards
        if (diff > sampleRate * 15.0 || diff < -sampleRate * 0.1) {
            seekToTime(timeSeconds);
            return;
        }

        if (diff > 0) {
            advance((int) diff);
        }
    }

    /**
     * Reads a sample from the ring buffer at a specific absolute position.
     */
    public short getSample(long absolutePosition) {
        if (absolutePosition < 0)
            return 0;

        long writeCursor = globalWriteCursor; // snapshot volatile once

        if (absolutePosition >= writeCursor) {
            if (writeCursor <= 0)
                return 0;
            absolutePosition = writeCursor - 1;
        }

        // Check if too old (overwritten)
        if (absolutePosition < writeCursor - bufferSize) {
            return 0;
        }

        int index = (int) (absolutePosition & bufferMask);
        return ringBuffer[index];
    }

    /**
     * 4-Point Lagrange interpolation for fractional delay lines.
     */
    public short getSampleLagrange(double absolutePosition) {
        long idx = (long) Math.floor(absolutePosition);
        double f = absolutePosition - idx;

        double y0 = getSample(idx - 1);
        double y1 = getSample(idx);
        double y2 = getSample(idx + 1);
        double y3 = getSample(idx + 2);

        // Lagrange basis polynomials evaluated at f ∈ [0, 1)
        double out = y0 * (f * (f - 1) * (f - 2)) / (-6.0)
                + y1 * ((f + 1) * (f - 1) * (f - 2)) / (2.0)
                + y2 * ((f + 1) * f * (f - 2)) / (-2.0)
                + y3 * ((f + 1) * f * (f - 1)) / (6.0);

        if (out > 32767.0)
            out = 32767.0;
        if (out < -32768.0)
            out = -32768.0;
        return (short) out;
    }

    /**
     * Cubic Hermite interpolation for fractional positions
     */
    public short getSampleHermite(double absolutePosition) {
        long posInt = (long) absolutePosition;
        double input = absolutePosition - posInt;

        short y0 = getSample(posInt - 1);
        short y1 = getSample(posInt);
        short y2 = getSample(posInt + 1);
        short y3 = getSample(posInt + 2);

        double c0 = y1;
        double c1 = 0.5 * (y2 - y0);
        double c2 = y0 - 2.5 * y1 + 2 * y2 - 0.5 * y3;
        double c3 = 0.5 * (y3 - y0) + 1.5 * (y1 - y2);

        double out = (((c3 * input + c2) * input + c1) * input + c0);
        if (out > 32767.0)
            out = 32767.0;
        if (out < -32768.0)
            out = -32768.0;
        return (short) out;
    }

    public long getWriteCursor() {
        return globalWriteCursor;
    }

    public long getTotalSamples() {
        if (pcmArray != null)
            return totalExpectedSamples;
        return fullPcmData != null ? fullPcmData.limit() : 0;
    }

    /** Returns the raw PCM array (for streaming decode continuation). */
    public short[] getPcmArray() {
        return pcmArray;
    }

    public void cleanup() {
        if (fullPcmData != null && fullPcmData.isDirect()) {
            try {
                MemoryUtil.memFree(fullPcmData);
            } catch (Exception e) {
                // Ignore if not freed or not alloc'd by memAlloc
            }
        }
        fullPcmData = null;
        pcmArray = null;
    }
}
