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
        public java.nio.ShortBuffer pcmData;
        public int channels;
        public int sampleRate;
        public int format;
    }

    /**
     * Streaming OGG decoder — decodes in chunks instead of all at once.
     * Usage:
     *   1. open() → get totalSamples, sampleRate
     *   2. decodeChunk() repeatedly → fills output array incrementally
     *   3. close() when done
     */
    public static class StreamingDecoder {
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
         * @param output   Target array (mono samples)
         * @param offset   Start index in output
         * @param maxSamples Maximum samples to decode
         * @return Number of mono samples actually decoded (0 = EOF)
         */
        public int decodeChunk(short[] output, int offset, int maxSamples) {
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

        public void close() {
            if (handle != 0) {
                STBVorbis.stb_vorbis_close(handle);
                handle = 0;
            }
            if (fileData != null) {
                MemoryUtil.memFree(fileData);
                fileData = null;
            }
        }
    }

    /**
     * Open a streaming OGG decoder from a resource path.
     * Returns null on failure.
     */
    public static StreamingDecoder openStreaming(String resourcePath) {
        ByteBuffer vorbisData = null;
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
        } catch (Exception e) {
            e.printStackTrace();
            if (vorbisData != null) MemoryUtil.memFree(vorbisData);
            return null;
        }

        try (MemoryStack stack = MemoryStack.stackPush()) {
            IntBuffer error = stack.mallocInt(1);
            long handle = STBVorbis.stb_vorbis_open_memory(vorbisData, error, null);

            if (handle == 0) {
                System.err.println("AudiophileCraft: Failed to open OGG stream: error=" + error.get(0));
                MemoryUtil.memFree(vorbisData);
                return null;
            }

            STBVorbisInfo info = STBVorbisInfo.malloc(stack);
            STBVorbis.stb_vorbis_get_info(handle, info);

            int sampleRate = info.sample_rate();
            int channels = info.channels();
            int totalSamples = STBVorbis.stb_vorbis_stream_length_in_samples(handle);

            return new StreamingDecoder(handle, vorbisData, sampleRate, channels, totalSamples);
        }
    }

    // Legacy full-decode method (kept for backward compatibility)
    public static RawTrackData loadOgg(String resourcePath) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            IntBuffer channels = stack.mallocInt(1);
            IntBuffer sampleRate = stack.mallocInt(1);

            // Read resource into ByteBuffer
            ByteBuffer vorbisData = null;
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
            } catch (Exception e) {
                e.printStackTrace();
                return null;
            }

            // Decode from memory
            java.nio.ShortBuffer rawAudio = STBVorbis.stb_vorbis_decode_memory(vorbisData, channels, sampleRate);

            // Clean up the raw file buffer as STB has decoded it
            MemoryUtil.memFree(vorbisData);

            if (rawAudio == null) {
                System.err.println("AudiophileCraft: Failed to decode Ogg: " + resourcePath);
                return null;
            }

            int channelCount = channels.get(0);
            int format = -1;
            java.nio.ShortBuffer resultBuffer = rawAudio;

            if (channelCount == 1) {
                format = AL_FORMAT_MONO16;
                // Mono: copy data to LWJGL-managed buffer so we can free the STB buffer
                java.nio.ShortBuffer copy = MemoryUtil.memAllocShort(rawAudio.remaining());
                copy.put(rawAudio);
                copy.flip();
                resultBuffer = copy;
            } else if (channelCount == 2) {
                // Downmix to Mono for 3D support
                java.nio.ShortBuffer monoAudio = MemoryUtil.memAllocShort(rawAudio.capacity() / 2);
                for (int i = 0; i < rawAudio.capacity(); i += 2) {
                    short left = rawAudio.get(i);
                    short right = rawAudio.get(i + 1);
                    int sum = (int) left + (int) right;
                    int mono = Math.round(sum * 0.5f);
                    if (mono > 32767) mono = 32767;
                    if (mono < -32768) mono = -32768;
                    monoAudio.put((short) mono);
                }
                monoAudio.flip();
                resultBuffer = monoAudio;
                format = AL_FORMAT_MONO16;
                channelCount = 1;
            } else {
                // Unknown channel count — wrap in LWJGL buffer to avoid double-free
                java.nio.ShortBuffer copy = MemoryUtil.memAllocShort(rawAudio.remaining());
                copy.put(rawAudio);
                copy.flip();
                resultBuffer = copy;
                System.err.println("AudiophileCraft: Unsupported Ogg channel count: " + channelCount);
            }

            // Free the STB-allocated buffer using C's free() (STB uses malloc internally)
            // MemoryUtil.memFree() uses LWJGL's allocator and causes Access Violation.
            LibCStdlib.free(rawAudio);

            RawTrackData data = new RawTrackData();
            data.pcmData = resultBuffer;
            data.channels = channelCount;
            data.sampleRate = sampleRate.get(0);
            data.format = format;

            return data;
        }
    }
}
