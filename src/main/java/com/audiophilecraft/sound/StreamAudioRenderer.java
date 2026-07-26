package com.audiophilecraft.sound;

import static org.lwjgl.openal.AL10.AL_BUFFER;
import static org.lwjgl.openal.AL10.AL_BUFFERS_PROCESSED;
import static org.lwjgl.openal.AL10.AL_BUFFERS_QUEUED;
import static org.lwjgl.openal.AL10.AL_FORMAT_MONO16;
import static org.lwjgl.openal.AL10.AL_SOURCE_STATE;
import static org.lwjgl.openal.AL10.AL_STOPPED;
import static org.lwjgl.openal.AL10.alBufferData;
import static org.lwjgl.openal.AL10.alDeleteBuffers;
import static org.lwjgl.openal.AL10.alGenBuffers;
import static org.lwjgl.openal.AL10.alGetSourcei;
import static org.lwjgl.openal.AL10.alSourceQueueBuffers;
import static org.lwjgl.openal.AL10.alSourceStop;
import static org.lwjgl.openal.AL10.alSourceUnqueueBuffers;
import static org.lwjgl.openal.AL10.alSourcei;

import com.audiophilecraft.config.LiveTuningConfig;
import java.nio.IntBuffer;
import java.nio.ShortBuffer;
import java.util.Arrays;
import org.lwjgl.BufferUtils;
import org.lwjgl.system.MemoryUtil;

/**
 * Owns source-level PCM rendering and the OpenAL streaming-buffer queue.
 *
 * <p>Queue and lifecycle mutations are called while the owning
 * {@link StreamSource} is locked. Keeping one lock preserves seek, refill and
 * cleanup ordering across the main and audio threads.
 */
final class StreamAudioRenderer {
    private static final int BUFFER_COUNT = 6;
    private static final int STREAM_BUFFER_SIZE = 1024;

    private final int sourceId;
    private final AudioStreamBuffer streamBuffer;
    private final PlaybackSession session;
    private final String speakerType;
    private final int sampleShiftMs;
    private final StreamDSPPipeline dspPipeline;
    private final IntBuffer buffers;
    private volatile int channelMask;

    private ShortBuffer reusablePcmBuffer;
    private short[] reusableRawAudio;
    private ShortBuffer audioThreadPcmBuffer;
    private short[] audioThreadRawAudio;

    private double outputCursor;
    private double lastRenderedDelaySamples = -1.0;
    private double prevTargetDelaySamples = -1.0;
    private double delayVelocity;

    StreamAudioRenderer(
            PlaybackSession session,
            int sourceId,
            AudioStreamBuffer streamBuffer,
            String speakerType,
            int sampleShiftMs,
            float initialDelayDistance,
            int initialChannelMask,
            float initialInputGain) {
        this.session = session;
        this.sourceId = sourceId;
        this.streamBuffer = streamBuffer;
        this.speakerType = speakerType;
        this.sampleShiftMs = sampleShiftMs;
        this.channelMask = initialChannelMask;

        float sampleRate =
                (streamBuffer != null && streamBuffer.sampleRate > 0) ? (float) streamBuffer.sampleRate : 44100f;
        this.dspPipeline = new StreamDSPPipeline(session, speakerType, sampleRate);

        this.reusablePcmBuffer = MemoryUtil.memAllocShort(STREAM_BUFFER_SIZE);
        this.reusableRawAudio = new short[STREAM_BUFFER_SIZE];
        this.audioThreadPcmBuffer = MemoryUtil.memAllocShort(STREAM_BUFFER_SIZE);
        this.audioThreadRawAudio = new short[STREAM_BUFFER_SIZE];

        this.buffers = BufferUtils.createIntBuffer(BUFFER_COUNT);
        alGenBuffers(this.buffers);
        primeQueue(initialDelayDistance, initialInputGain);
    }

    AudioStreamBuffer getStreamBuffer() {
        return streamBuffer;
    }

    double getOutputCursor() {
        return outputCursor;
    }

    void setChannelMask(int channelMask) {
        this.channelMask = channelMask;
    }

    int getChannelMask() {
        return channelMask;
    }

    boolean seekToTime(double timeSeconds, float delayDistance, float smoothedInputGain) {
        dspPipeline.reset();
        resetDelaySmoothing();

        alSourceStop(sourceId);
        alSourcei(sourceId, AL_BUFFER, 0);

        boolean finished = false;
        double seekStartSample = timeSeconds * streamBuffer.sampleRate;
        for (int i = 0; i < BUFFER_COUNT; i++) {
            double bufferStartSample = seekStartSample + (i * STREAM_BUFFER_SIZE);
            finished |= generatePcmBlock(reusableRawAudio, bufferStartSample, delayDistance, smoothedInputGain);
            uploadPcm(buffers.get(i), reusablePcmBuffer, reusableRawAudio);
        }
        outputCursor = seekStartSample + (BUFFER_COUNT * STREAM_BUFFER_SIZE);
        alSourceQueueBuffers(sourceId, buffers);
        return finished;
    }

    FeedResult feed(
            double globalSampleTime,
            float delayDistance,
            float smoothedInputGain,
            boolean sourceFinished) {
        boolean finished = sourceFinished;
        int processed = alGetSourcei(sourceId, AL_BUFFERS_PROCESSED);

        while (processed > 0) {
            int bufferId = alSourceUnqueueBuffers(sourceId);

            if (!finished) {
                double bufferStartSample = outputCursor;
                long readEnd = (long) (bufferStartSample + STREAM_BUFFER_SIZE);

                if (readEnd <= streamBuffer.getWriteCursor()) {
                    finished |= generatePcmBlock(
                            audioThreadRawAudio, bufferStartSample, delayDistance, smoothedInputGain);
                } else {
                    Arrays.fill(audioThreadRawAudio, (short) 0);
                }

                uploadPcm(bufferId, audioThreadPcmBuffer, audioThreadRawAudio);
                alSourceQueueBuffers(sourceId, bufferId);
                outputCursor += STREAM_BUFFER_SIZE;
            }
            processed--;
        }

        int state = alGetSourcei(sourceId, AL_SOURCE_STATE);
        if (state == AL_STOPPED && !finished) {
            int queued = alGetSourcei(sourceId, AL_BUFFERS_QUEUED);
            if (queued > 0) {
                outputCursor = globalSampleTime + ((double) queued * STREAM_BUFFER_SIZE);
                return new FeedResult(true, false);
            }
        }

        return new FeedResult(false, finished);
    }

    void releaseNativeMemory() {
        if (reusablePcmBuffer != null) {
            try {
                MemoryUtil.memFree(reusablePcmBuffer);
            } catch (Exception ignored) {
                // Native memory may already be unavailable during context teardown.
            }
            reusablePcmBuffer = null;
        }

        if (audioThreadPcmBuffer != null) {
            try {
                MemoryUtil.memFree(audioThreadPcmBuffer);
            } catch (Exception ignored) {
                // Native memory may already be unavailable during context teardown.
            }
            audioThreadPcmBuffer = null;
        }
    }

    void deleteOpenAlBuffers() {
        alDeleteBuffers(buffers);
    }

    private void primeQueue(float delayDistance, float smoothedInputGain) {
        for (int i = 0; i < BUFFER_COUNT; i++) {
            double bufferStartSample = (double) (i * STREAM_BUFFER_SIZE);
            generatePcmBlock(reusableRawAudio, bufferStartSample, delayDistance, smoothedInputGain);
            uploadPcm(buffers.get(i), reusablePcmBuffer, reusableRawAudio);
        }
        outputCursor = (double) (BUFFER_COUNT * STREAM_BUFFER_SIZE);
        alSourceQueueBuffers(sourceId, buffers);
    }

    private void uploadPcm(int bufferId, ShortBuffer nativeBuffer, short[] samples) {
        nativeBuffer.clear();
        nativeBuffer.put(samples);
        nativeBuffer.flip();
        alBufferData(bufferId, AL_FORMAT_MONO16, nativeBuffer, (int) streamBuffer.sampleRate);
    }

    private boolean generatePcmBlock(
            short[] output,
            double bufferStartSample,
            float delayDistance,
            float smoothedInputGain) {
        double sampleRate = streamBuffer.sampleRate;
        if (sampleRate <= 0) return false;

        double speedOfSound = LiveTuningConfig.get().speedOfSound;
        double targetDelaySeconds = (delayDistance / speedOfSound) + (sampleShiftMs / 1000.0);
        double targetDelaySamples = targetDelaySeconds * sampleRate;

        if (lastRenderedDelaySamples < 0) {
            lastRenderedDelaySamples = targetDelaySamples;
        }
        if (prevTargetDelaySamples < 0) {
            prevTargetDelaySamples = targetDelaySamples;
        }

        boolean finished = false;
        int currentChannelMask = channelMask;
        double currentDelay = lastRenderedDelaySamples;
        double startTarget = prevTargetDelaySamples;
        double targetDelta = targetDelaySamples - startTarget;
        prevTargetDelaySamples = targetDelaySamples;

        double omega = 2.0 * Math.PI * 25.0 / sampleRate;
        double omegaSq = omega * omega;
        double twoOmega = 2.0 * omega;
        double maxDeltaPerSample = 0.015;
        double localVelocity = delayVelocity;

        for (int i = 0; i < STREAM_BUFFER_SIZE; i++) {
            double t = (double) (i + 1) / STREAM_BUFFER_SIZE;
            double interpolatedTarget = startTarget + targetDelta * t;
            double delta = interpolatedTarget - currentDelay;

            double acceleration = omegaSq * delta - twoOmega * localVelocity;
            localVelocity += acceleration;

            if (localVelocity > maxDeltaPerSample) localVelocity = maxDeltaPerSample;
            if (localVelocity < -maxDeltaPerSample) localVelocity = -maxDeltaPerSample;

            currentDelay += localVelocity;
            double readPos = (bufferStartSample + i) - currentDelay;

            if (readPos >= streamBuffer.getTotalSamples()) {
                finished = true;
                output[i] = 0;
            } else if (readPos < 0) {
                output[i] = 0;
            } else {
                output[i] = streamBuffer.getSampleLagrange(readPos, currentChannelMask);
            }
        }

        lastRenderedDelaySamples = currentDelay;
        delayVelocity = localVelocity;

        dspPipeline.process(output, (float) streamBuffer.sampleRate, smoothedInputGain);

        if (session == AudioEngine.getInstance().getActiveSession()) {
            PeakMeter.getInstance().feedPeak(speakerType, output, STREAM_BUFFER_SIZE);
        }

        return finished;
    }

    private void resetDelaySmoothing() {
        lastRenderedDelaySamples = -1.0;
        prevTargetDelaySamples = -1.0;
        delayVelocity = 0.0;
    }

    record FeedResult(boolean restartRequired, boolean finished) {}
}
