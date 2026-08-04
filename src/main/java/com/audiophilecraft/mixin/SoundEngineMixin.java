package com.audiophilecraft.mixin;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.IntBuffer;
import net.minecraft.client.sound.SoundEngine;
import net.minecraft.util.math.MathHelper;
import org.lwjgl.openal.ALC10;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Raises Minecraft's hardcoded 255 streaming-source ceiling to 1024.
 */
@Mixin(SoundEngine.class)
public abstract class SoundEngineMixin {

    /**
     * Redirects the alcCreateContext call to explicitly request 1024 sources.
     * By default, Minecraft passes null attributes, which causes OpenAL Soft
     * to default to 256 sources regardless of what's in alsoft.ini.
     */
    @Redirect(
            method = "init",
            at =
                    @At(
                            value = "INVOKE",
                            target = "Lorg/lwjgl/openal/ALC10;alcCreateContext(JLjava/nio/IntBuffer;)J",
                            remap = false))
    private long injectCustomContextAttributes(long device, IntBuffer attrList) {
        if (attrList == null) {
            // Must be a direct buffer for LWJGL
            attrList = ByteBuffer.allocateDirect(3 * Integer.BYTES)
                    .order(ByteOrder.nativeOrder())
                    .asIntBuffer();
            attrList.put(0x1010); // ALC_MONO_SOURCES
            attrList.put(1024);
            attrList.put(0); // Zero-terminated list
            attrList.flip();
        }
        return ALC10.alcCreateContext(device, attrList);
    }

    /**
     * Redirects the {@code MathHelper.clamp(int, int, int)} call used to cap
     * streaming sources inside {@link SoundEngine#init(String, boolean)}.
     */
    @Redirect(
            method = "init",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/util/math/MathHelper;clamp(III)I", ordinal = 1))
    private int raiseStreamingSourceCap(int value, int min, int max) {
        return MathHelper.clamp(value, min, 1024);
    }
}
