package com.audiophilecraft.sound;

import java.nio.ShortBuffer;
import org.lwjgl.system.MemoryUtil;

/**
 * Manages PCM audio data with per-channel access for stereo.
 * Streaming mode: interleaved short[] (L,R,L,R,...).
 * Legacy mode: interleaved ShortBuffer (L,R,L,R,...).
 *
 * Ring buffer stores mono mix for BOTH channel (backward compat).
 * LEFT/RIGHT read directly from interleaved array at appropriate offset.
 */
public class AudioStreamBuffer {

    public final String trackId;
    public final int sampleRate;

    private static final int BUFFER_SIZE = 2097152;
    private static final int BUFFER_MASK = BUFFER_SIZE - 1;

    private final short[] ringBuffer;
    private volatile long globalWriteCursor = 0;

    // ── STREAMING MODE: interleaved stereo array ──
    // pcmInterleaved = [L0, R0, L1, R1, L2, R2, ...]
    // Number of frames = pcmInterleaved.length / 2
    private short[] pcmInterleaved;
    private volatile int decodedLength = 0;
    private volatile int totalExpectedSamples = 0;
    private volatile int readCursor = 0;

    // ── LEGACY MODE ──
    private ShortBuffer fullPcmData; // Interleaved stereo ShortBuffer

    public AudioStreamBuffer(String trackId, int sampleRate) {
        this.trackId = trackId;
        this.sampleRate = sampleRate;
        this.ringBuffer = new short[BUFFER_SIZE];
    }

    /** Init with interleaved stereo data: [L,R,L,R,...] */
    public void initStreaming(short[] interleaved, int initialDecodedLength, int totalExpected) {
        this.pcmInterleaved = interleaved;
        this.decodedLength = initialDecodedLength;
        this.totalExpectedSamples = totalExpected;
        this.readCursor = 0;
        this.fullPcmData = null;
    }

    public void updateDecodedLength(int newLength) {
        this.decodedLength = newLength;
    }

    /** Replace the metadata estimate with the real frame count once decoding reaches EOF. */
    public void completeStreaming(int actualDecodedFrames) {
        if (pcmInterleaved == null) return;
        int boundedLength = Math.max(0, Math.min(actualDecodedFrames, pcmInterleaved.length / 2));
        this.decodedLength = boundedLength;
        this.totalExpectedSamples = boundedLength;
    }

    public void setSourceData(ShortBuffer pcm) {
        this.fullPcmData = pcm;
        this.pcmInterleaved = null;
    }

    public double getTotalDurationSeconds() {
        if (pcmInterleaved != null && sampleRate > 0) return (double) totalExpectedSamples / sampleRate;
        if (fullPcmData != null && sampleRate > 0) return (double) (fullPcmData.capacity() / 2) / sampleRate;
        return 0.0;
    }

    public void seekToTime(double timeSeconds) {
        if (sampleRate <= 0) return;
        long targetCursor = (long) (timeSeconds * sampleRate);
        long maxSamples = getTotalSamples();
        if (targetCursor > maxSamples - 1) targetCursor = maxSamples - 1;
        if (targetCursor < 0) targetCursor = 0;

        if (pcmInterleaved != null) {
            this.readCursor = (int) targetCursor;
        } else if (fullPcmData != null) {
            fullPcmData.position((int) targetCursor * 2);
        }

        java.util.Arrays.fill(ringBuffer, (short) 0);
        this.globalWriteCursor = targetCursor;
    }

    /** Advance the ring buffer by writing mono mix (avg of L/R) from interleaved source. */
    public void advance(int samplesNeeded) {
        if (samplesNeeded <= 0) return;

        if (pcmInterleaved != null) {
            int currentDecoded = decodedLength;
            for (int i = 0; i < samplesNeeded; i++) {
                short sample = 0;
                if (readCursor < currentDecoded) {
                    int idx = readCursor * 2;
                    int left = pcmInterleaved[idx];
                    int right = pcmInterleaved[idx + 1];
                    sample = (short) ((left + right + 1) >> 1); // round average
                    readCursor++;
                } else if (readCursor < totalExpectedSamples) {
                    // Not yet decoded — silence, keep readCursor in place
                }
                ringBuffer[(int) (globalWriteCursor & BUFFER_MASK)] = sample;
                globalWriteCursor++;
            }
        } else if (fullPcmData != null) {
            for (int i = 0; i < samplesNeeded; i++) {
                short sample = 0;
                if (fullPcmData.hasRemaining()) {
                    // Read mono (avg of L+R)
                    int left = fullPcmData.get();
                    if (fullPcmData.hasRemaining()) {
                        int right = fullPcmData.get();
                        sample = (short) ((left + right + 1) >> 1);
                    } else {
                        sample = (short) left;
                    }
                }
                ringBuffer[(int) (globalWriteCursor & BUFFER_MASK)] = sample;
                globalWriteCursor++;
            }
        }
    }

    public void syncToTime(double timeSeconds) {
        long targetCursor = (long) (timeSeconds * sampleRate);
        long diff = targetCursor - globalWriteCursor;
        if (diff > sampleRate * 15.0 || diff < -sampleRate * 0.1) {
            seekToTime(timeSeconds);
            return;
        }
        if (diff > 0) advance((int) diff);
    }

    /**
     * Read a single sample at frame position {@code framePos} for the selected channel.
     * 0=BOTH (average of L+R), 1=LEFT, 2=RIGHT.
     * ALL channels read from the same interleaved source — zero async.
     * Returns 0 (silence) until the decoder reaches this position.
     */
    public short getSample(long framePos, int channelMask) {
        if (framePos < 0) return 0;

        // Streaming: interleaved source
        if (pcmInterleaved != null) {
            int pos = (int) framePos;
            if (pos >= 0 && pos < decodedLength) {
                int idx = pos * 2;
                short l = pcmInterleaved[idx];
                short r = pcmInterleaved[idx + 1];
                if (channelMask == 0) return (short) ((l + r + 1) >> 1);
                return channelMask == 1 ? l : r;
            }
            return 0;
        }

        // Legacy: pre-decoded ShortBuffer
        if (fullPcmData != null) {
            int maxFrames = fullPcmData.limit() / 2;
            int pos = (int) framePos;
            if (pos >= 0 && pos < maxFrames) {
                int l = fullPcmData.get(pos * 2);
                int r = fullPcmData.get(pos * 2 + 1);
                if (channelMask == 0) return (short) ((l + r + 1) >> 1);
                return (short) (channelMask == 1 ? l : r);
            }
            return 0;
        }

        return 0;
    }

    /** Legacy mono-only read (BOTH channel). */
    public short getSample(long framePos) {
        return readFromRing(framePos);
    }

    private short readFromRing(long absolutePosition) {
        if (absolutePosition < 0) return 0;
        long wc = globalWriteCursor;
        if (absolutePosition >= wc) {
            if (wc <= 0) return 0;
            absolutePosition = wc - 1;
        }
        if (absolutePosition < wc - BUFFER_SIZE) return 0;
        return ringBuffer[(int) (absolutePosition & BUFFER_MASK)];
    }

    public short getSampleLagrange(double absolutePosition) {
        return hermite(absolutePosition, 0);
    }

    public short getSampleLagrange(double absolutePosition, int channelMask) {
        return hermite(absolutePosition, channelMask);
    }

    private short hermite(double absolutePosition, int channelMask) {
        long idx = (long) Math.floor(absolutePosition);
        double f = absolutePosition - idx;
        double y0 = getSample(idx - 1, channelMask);
        double y1 = getSample(idx, channelMask);
        double y2 = getSample(idx + 1, channelMask);
        double y3 = getSample(idx + 2, channelMask);
        // Catmull-Rom spline: C1 continuous (tangent continuity), eliminates
        // the overshoot and metallic harmonics of Lagrange interpolation.
        double a = -0.5 * y0 + 1.5 * y1 - 1.5 * y2 + 0.5 * y3;
        double b = y0 - 2.5 * y1 + 2.0 * y2 - 0.5 * y3;
        double c = -0.5 * y0 + 0.5 * y2;
        double d = y1;
        double out = ((a * f + b) * f + c) * f + d;
        if (out > 32767.0) out = 32767.0;
        if (out < -32768.0) out = -32768.0;
        return (short) out;
    }

    public long getWriteCursor() {
        return globalWriteCursor;
    }

    public long getTotalSamples() {
        if (pcmInterleaved != null) return totalExpectedSamples;
        if (fullPcmData != null) return fullPcmData.limit() / 2;
        return 0;
    }

    public short[] getPcmArray() {
        return pcmInterleaved;
    }

    public void cleanup() {
        if (fullPcmData != null && fullPcmData.isDirect()) {
            try {
                MemoryUtil.memFree(fullPcmData);
            } catch (Exception e) {
                /* ignore */
            }
        }
        fullPcmData = null;
        pcmInterleaved = null;
    }
}
