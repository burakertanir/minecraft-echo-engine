package com.audiophilecraft.sound;

import static org.lwjgl.openal.AL10.*;

import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.nio.ShortBuffer;
import org.lwjgl.stb.STBVorbis;
import org.lwjgl.stb.STBVorbisInfo;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.system.libc.LibCStdlib;

public class OggDecoder {

    public static class RawTrackData {
        public java.nio.ShortBuffer pcmData; // Interleaved stereo: [L,R,L,R,...] or [M,M,M,M,...] for mono
        public int channels;
        public int sampleRate;
        public int format;
    }

    /**
     * Streaming OGG decoder — decodes in chunks instead of all at once.
     * Usage:
     * 1. open() → get totalSamples, sampleRate
     * 2. decodeChunk() repeatedly → fills output array incrementally
     * 3. close() when done
     */
    public static class StreamingDecoder implements AutoCloseable {
        private long handle;
        private ByteBuffer fileData; // Must stay alive while decoder is open
        public final int sampleRate;
        public final int channels;
        public final int totalSamples;
        private boolean finished = false;

        private StreamingDecoder(long handle, ByteBuffer fileData, int sampleRate, int channels, int totalSamples) {
            this.handle = handle;
            this.fileData = fileData;
            this.sampleRate = sampleRate;
            this.channels = channels;
            this.totalSamples = totalSamples;
        }

        /**
         * Decode the next chunk of audio into the output array.
         *
         * @param output     Target array (mono samples)
         * @param offset     Start index in output
         * @param maxSamples Maximum samples to decode
         * @return Number of mono samples actually decoded (0 = EOF)
         */
        public synchronized int decodeChunk(short[] output, int offset, int maxSamples) {
            if (finished || handle == 0) return 0;

            // Use a temporary interleaved buffer for decoding
            ShortBuffer tempBuf = MemoryUtil.memAllocShort(maxSamples * channels);
            try {
                int samplesPerChannel = STBVorbis.stb_vorbis_get_samples_short_interleaved(handle, channels, tempBuf);

                if (samplesPerChannel == 0) {
                    finished = true;
                    return 0;
                }

                // Downmix to mono if stereo
                if (channels == 2) {
                    for (int i = 0; i < samplesPerChannel; i++) {
                        short left = tempBuf.get(i * 2);
                        short right = tempBuf.get(i * 2 + 1);
                        int mono = Math.round(((int) left + (int) right) * 0.5f);
                        if (mono > 32767) mono = 32767;
                        if (mono < -32768) mono = -32768;
                        output[offset + i] = (short) mono;
                    }
                } else {
                    // Mono — direct copy
                    tempBuf.position(0);
                    tempBuf.get(output, offset, samplesPerChannel);
                }

                return samplesPerChannel;
            } finally {
                MemoryUtil.memFree(tempBuf);
            }
        }

        public boolean isFinished() {
            return finished;
        }

        @Override
        public synchronized void close() {
            long handleToClose = handle;
            ByteBuffer dataToFree = fileData;
            handle = 0;
            fileData = null;
            finished = true;

            try {
                if (handleToClose != 0) {
                    STBVorbis.stb_vorbis_close(handleToClose);
                }
            } finally {
                if (dataToFree != null) {
                    MemoryUtil.memFree(dataToFree);
                }
            }
        }
    }

    /**
     * Open a streaming OGG decoder from a resource path.
     * Returns null on failure.
     */
    public static StreamingDecoder openStreaming(String resourcePath) {
        ByteBuffer vorbisData = null;
        long handle = 0;
        boolean ownershipTransferred = false;
        try {
            var id = new net.minecraft.util.Identifier("audiophilecraft", resourcePath);
            var resource = net.minecraft.client.MinecraftClient.getInstance()
                    .getResourceManager()
                    .getResource(id);

            if (resource.isPresent()) {
                try (var stream = resource.get().getInputStream()) {
                    byte[] bytes = stream.readAllBytes();
                    vorbisData = MemoryUtil.memAlloc(bytes.length);
                    vorbisData.put(bytes);
                    vorbisData.flip();
                }
            } else {
                System.err.println("AudiophileCraft: Resource not found: " + id);
                return null;
            }

            try (MemoryStack stack = MemoryStack.stackPush()) {
                IntBuffer error = stack.mallocInt(1);
                handle = STBVorbis.stb_vorbis_open_memory(vorbisData, error, null);

                if (handle == 0) {
                    System.err.println("AudiophileCraft: Failed to open OGG stream: error=" + error.get(0));
                    return null;
                }

                STBVorbisInfo info = STBVorbisInfo.malloc(stack);
                STBVorbis.stb_vorbis_get_info(handle, info);

                int sampleRate = info.sample_rate();
                int channels = info.channels();
                if (channels != 1 && channels != 2) {
                    System.err.println("AudiophileCraft: Unsupported OGG stream channel count: " + channels);
                    return null;
                }
                int totalSamples = STBVorbis.stb_vorbis_stream_length_in_samples(handle);

                StreamingDecoder decoder = new StreamingDecoder(handle, vorbisData, sampleRate, channels, totalSamples);
                ownershipTransferred = true;
                return decoder;
            }
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        } finally {
            if (!ownershipTransferred) {
                if (handle != 0) {
                    STBVorbis.stb_vorbis_close(handle);
                }
                if (vorbisData != null) {
                    MemoryUtil.memFree(vorbisData);
                }
            }
        }
    }

    private static final boolean DEBUG_DECIBEL = false;

    public static RawTrackData loadOgg(String resourcePath) {
        ByteBuffer vorbisData = null;
        ShortBuffer rawAudio = null;
        ShortBuffer resultBuffer = null;
        boolean ownershipTransferred = false;

        try (MemoryStack stack = MemoryStack.stackPush()) {
            IntBuffer channels = stack.mallocInt(1);
            IntBuffer sampleRate = stack.mallocInt(1);

            var id = new net.minecraft.util.Identifier("audiophilecraft", resourcePath);
            var resource = net.minecraft.client.MinecraftClient.getInstance()
                    .getResourceManager()
                    .getResource(id);
            if (resource.isPresent()) {
                try (var stream = resource.get().getInputStream()) {
                    byte[] bytes = stream.readAllBytes();
                    vorbisData = MemoryUtil.memAlloc(bytes.length);
                    vorbisData.put(bytes);
                    vorbisData.flip();
                }
            } else {
                System.err.println("AudiophileCraft: Resource not found: " + id);
                return null;
            }

            rawAudio = STBVorbis.stb_vorbis_decode_memory(vorbisData, channels, sampleRate);
            if (rawAudio == null) {
                System.err.println("AudiophileCraft: Failed to decode Ogg: " + resourcePath);
                return null;
            }

            int channelCount = channels.get(0);
            int sampleRateVal = sampleRate.get(0);

            if (channelCount == 1) {
                // Mono: duplicate to pseudo-stereo [M,M,M,M,...]
                int frames = rawAudio.capacity();
                resultBuffer = MemoryUtil.memAllocShort(Math.multiplyExact(frames, 2));
                for (int i = 0; i < frames; i++) {
                    short s = rawAudio.get(i);
                    resultBuffer.put(s);
                    resultBuffer.put(s);
                }
                resultBuffer.flip();
            } else if (channelCount == 2) {
                // Already interleaved stereo - use as-is
                resultBuffer = MemoryUtil.memAllocShort(rawAudio.capacity());
                resultBuffer.put(rawAudio.duplicate());
                resultBuffer.flip();
            } else {
                System.err.println("AudiophileCraft: Unsupported channel count: " + channelCount);
                return null;
            }

            RawTrackData data = new RawTrackData();
            data.pcmData = resultBuffer;
            data.channels = channelCount;
            data.sampleRate = sampleRateVal;
            data.format = AL_FORMAT_MONO16;
            ownershipTransferred = true;
            return data;
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        } finally {
            if (!ownershipTransferred && resultBuffer != null) {
                MemoryUtil.memFree(resultBuffer);
            }
            if (rawAudio != null) {
                LibCStdlib.free(rawAudio);
            }
            if (vorbisData != null) {
                MemoryUtil.memFree(vorbisData);
            }
        }
    }
}
