package com.audiophilecraft.sound;

import static org.lwjgl.openal.AL10.*;

import net.minecraft.client.MinecraftClient;
import net.minecraft.util.math.Vec3d;

/**
 * Manages OpenAL listener position, orientation, and environmental state.
 * Decoupled from AudioEngine so multiple playback sessions share one listener.
 *
 * Thread safety: volatile fields are written by render thread, read by audio thread.
 */
public class ListenerController {
    private static ListenerController INSTANCE;

    // Listener state (volatile: written by render thread, read by audio thread)
    private volatile Vec3d position = Vec3d.ZERO;
    private volatile Vec3d smoothedPosition = Vec3d.ZERO;
    private volatile float yaw = 0;
    private volatile float pitch = 0;

    // Underwater State (for global HF filtering)
    private volatile boolean isUnderwater = false;
    private volatile float underwaterHFGain = 1.0f; // 1.0 = normal, 0.08 = deep underwater

    private ListenerController() {}

    public static synchronized ListenerController getInstance() {
        if (INSTANCE == null) {
            INSTANCE = new ListenerController();
        }
        return INSTANCE;
    }

    // --- Getters for audio thread (atomic volatile reads) ---

    public Vec3d getPosition() {
        return position;
    }

    public Vec3d getSmoothedPosition() {
        return smoothedPosition;
    }

    public float getYaw() {
        return yaw;
    }

    public float getPitch() {
        return pitch;
    }

    public float getUnderwaterHFGain() {
        return underwaterHFGain;
    }

    // --- Setters (called from render thread via AudioEngine.updateListener) ---

    /**
     * Updates listener position and orientation. Called every render frame.
     */
    public void update(Vec3d pos, float yaw, float pitch, float flattenedY) {
        this.position = pos;
        this.yaw = yaw;
        this.pitch = pitch;

        alListener3f(AL_POSITION, (float) pos.x, flattenedY, (float) pos.z);
        alListener3f(AL_VELOCITY, 0f, 0f, 0f);

        // Orientation calculation
        float pitchRad = (float) Math.toRadians(pitch);
        float yawRad = (float) Math.toRadians(-yaw);
        float cosPitch = (float) Math.cos(pitchRad);
        float sinPitch = (float) Math.sin(pitchRad);
        float cosYaw = (float) Math.cos(yawRad);
        float sinYaw = (float) Math.sin(yawRad);

        float atX = sinYaw * cosPitch;
        float atY = -sinPitch;
        float atZ = cosYaw * cosPitch;

        float upX, upY, upZ;
        if (Math.abs(pitch) > 89.9f) {
            float sign = Math.signum(pitch);
            upX = sinYaw * sign;
            upY = 0.0f;
            upZ = cosYaw * sign;
        } else {
            float rightX = atY * 0 - atZ * 1;
            float rightY = atZ * 0 - atX * 0;
            float rightZ = atX * 1 - atY * 0;
            float rightLen = (float) Math.sqrt(rightX * rightX + rightY * rightY + rightZ * rightZ);
            rightX /= rightLen;
            rightY /= rightLen;
            rightZ /= rightLen;

            upX = rightY * atZ - rightZ * atY;
            upY = rightZ * atX - rightX * atZ;
            upZ = rightX * atY - rightY * atX;
            float upLen = (float) Math.sqrt(upX * upX + upY * upY + upZ * upZ);
            upX /= upLen;
            upY /= upLen;
            upZ /= upLen;
        }

        alListenerfv(AL_ORIENTATION, new float[] {atX, atY, atZ, upX, upY, upZ});

        // Underwater detection
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.gameRenderer != null && mc.gameRenderer.getCamera() != null) {
            isUnderwater = mc.gameRenderer.getCamera().getSubmersionType()
                    == net.minecraft.client.render.CameraSubmersionType.WATER;
        } else if (mc.player != null) {
            isUnderwater = mc.player.isSubmergedInWater();
        }
        float targetHF = isUnderwater ? 0.08f : 1.0f;
        underwaterHFGain += (targetHF - underwaterHFGain) * 0.15f;
    }
}
