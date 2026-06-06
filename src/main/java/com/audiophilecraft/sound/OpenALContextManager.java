package com.audiophilecraft.sound;

import static org.lwjgl.openal.AL10.*;
import static org.lwjgl.openal.AL11.*;
import static org.lwjgl.openal.EXTEfx.*;

import org.lwjgl.openal.ALC10;
import org.lwjgl.openal.ALCCapabilities;
import org.lwjgl.openal.SOFTHRTF;

/**
 * Manages OpenAL device-level initialization: HRTF, expanded source pool, EFX Reverb.
 * Singleton — one context shared across all playback sessions.
 */
public class OpenALContextManager {
    private static OpenALContextManager INSTANCE;

    private boolean initialized;

    private OpenALContextManager() {}

    public static synchronized OpenALContextManager getInstance() {
        if (INSTANCE == null) {
            INSTANCE = new OpenALContextManager();
        }
        return INSTANCE;
    }

    /**
     * Initialize HRTF + EFX reverb. Idempotent — safe to call multiple times.
     * Returns a result object with the created OpenAL IDs, or null on failure.
     */
    public synchronized InitResult initialize() {
        if (initialized)
            return new InitResult(0, 0); // Already done, return zero IDs
        initialized = true;

        enableHrtf();

        int reverbEffectId = 0;
        int auxSlotId = 0;

        try {
            reverbEffectId = alGenEffects();
            if (alGetError() != AL_NO_ERROR) {
                System.err.println("OpenALContextManager: Failed to create EFX effect");
                return null;
            }

            alEffecti(reverbEffectId, AL_EFFECT_TYPE, AL_EFFECT_EAXREVERB);
            if (alGetError() != AL_NO_ERROR) {
                alEffecti(reverbEffectId, AL_EFFECT_TYPE, AL_EFFECT_REVERB);
                if (alGetError() != AL_NO_ERROR) {
                    System.err.println("OpenALContextManager: No reverb support available");
                    alDeleteEffects(reverbEffectId);
                    return null;
                }
            }

            alDistanceModel(AL_NONE);

            alEffectf(reverbEffectId, AL_EAXREVERB_DECAY_TIME, 0.3f);
            alEffectf(reverbEffectId, AL_EAXREVERB_REFLECTIONS_GAIN, 0.3f);
            alEffectf(reverbEffectId, AL_EAXREVERB_REFLECTIONS_DELAY, 0.02f);
            alEffectf(reverbEffectId, AL_EAXREVERB_LATE_REVERB_GAIN, 0.1f);
            alEffectf(reverbEffectId, AL_EAXREVERB_LATE_REVERB_DELAY, 0.04f);
            alEffectf(reverbEffectId, AL_EAXREVERB_DIFFUSION, 0.7f);
            alEffectf(reverbEffectId, AL_EAXREVERB_DENSITY, 0.5f);
            alEffectf(reverbEffectId, AL_EAXREVERB_GAIN, 0.3f);
            alEffectf(reverbEffectId, AL_EAXREVERB_GAINHF, 0.6f);
            alEffectf(reverbEffectId, AL_EAXREVERB_GAINLF, 0.8f);
            alEffectf(reverbEffectId, AL_EAXREVERB_DECAY_HFRATIO, 0.5f);
            alEffectf(reverbEffectId, AL_EAXREVERB_DECAY_LFRATIO, 1.1f);
            alEffectf(reverbEffectId, AL_EAXREVERB_AIR_ABSORPTION_GAINHF, 0.994f);
            alEffecti(reverbEffectId, AL_EAXREVERB_DECAY_HFLIMIT, 1);

            auxSlotId = alGenAuxiliaryEffectSlots();
            if (alGetError() != AL_NO_ERROR) {
                System.err.println("OpenALContextManager: Failed to create aux slot");
                alDeleteEffects(reverbEffectId);
                return null;
            }

            alAuxiliaryEffectSloti(auxSlotId, AL_EFFECTSLOT_EFFECT, reverbEffectId);
        } catch (Exception e) {
            System.err.println("OpenALContextManager: EFX init failed: " + e.getMessage());
            return null;
        }

        return new InitResult(reverbEffectId, auxSlotId);
    }

    public void destroy(int reverbEffectId, int auxSlotId) {
        if (auxSlotId != 0) {
            alAuxiliaryEffectSloti(auxSlotId, AL_EFFECTSLOT_EFFECT, AL_EFFECT_NULL);
            alDeleteAuxiliaryEffectSlots(auxSlotId);
        }
        if (reverbEffectId != 0) {
            alDeleteEffects(reverbEffectId);
        }
        initialized = false;
    }

    private void enableHrtf() {
        try {
            long context = ALC10.alcGetCurrentContext();
            if (context == 0L) {
                System.err.println("[enableHrtf] No current context, aborting.");
                return;
            }
            long device = ALC10.alcGetContextsDevice(context);
            if (device == 0L) {
                System.err.println("[enableHrtf] No device for context, aborting.");
                return;
            }

            int preResetSources = ALC10.alcGetInteger(device, org.lwjgl.openal.ALC11.ALC_MONO_SOURCES);
            int preResetStereo = ALC10.alcGetInteger(device, org.lwjgl.openal.ALC11.ALC_STEREO_SOURCES);
            int preResetError = ALC10.alcGetError(device);
            System.out.println("[enableHrtf] PRE-RESET: monoSources=" + preResetSources
                    + " stereoSources=" + preResetStereo + " alcError=0x" + Integer.toHexString(preResetError));

            ALCCapabilities alcCaps = org.lwjgl.openal.ALC.createCapabilities(device);
            System.out.println("[enableHrtf] ALC_SOFT_HRTF=" + alcCaps.ALC_SOFT_HRTF);

            if (alcCaps.ALC_SOFT_HRTF) {
                int numHrtf = ALC10.alcGetInteger(device, SOFTHRTF.ALC_NUM_HRTF_SPECIFIERS_SOFT);
                System.out.println("[enableHrtf] HRTF profiles found: " + numHrtf);

                if (numHrtf > 0) {
                    int[] attrs = {
                            SOFTHRTF.ALC_HRTF_SOFT, ALC10.ALC_TRUE,
                            org.lwjgl.openal.ALC11.ALC_MONO_SOURCES, 1024,
                            0
                    };
                    System.out.println("[enableHrtf] Calling alcResetDeviceSOFT(HRTF=TRUE, MONO=1024)...");
                    boolean success = SOFTHRTF.alcResetDeviceSOFT(device, attrs);
                    int postResetError = ALC10.alcGetError(device);
                    System.out.println("[enableHrtf] alcResetDeviceSOFT returned: " + success
                            + " alcError=0x" + Integer.toHexString(postResetError));

                    if (success) {
                        int hrtfStatus = ALC10.alcGetInteger(device, SOFTHRTF.ALC_HRTF_STATUS_SOFT);
                        int actualSources = ALC10.alcGetInteger(device, org.lwjgl.openal.ALC11.ALC_MONO_SOURCES);
                        int actualStereo = ALC10.alcGetInteger(device, org.lwjgl.openal.ALC11.ALC_STEREO_SOURCES);
                        int postAlError = org.lwjgl.openal.AL10.alGetError();
                        System.out.println("[enableHrtf] POST-RESET: hrtfStatus=" + hrtfStatus
                                + " actualMonoSources=" + actualSources + " actualStereoSources=" + actualStereo
                                + " alError=0x" + Integer.toHexString(postAlError));
                    } else {
                        System.err.println("[enableHrtf] alcResetDeviceSOFT FAILED! alcError=0x"
                                + Integer.toHexString(postResetError));
                        System.err.println("[enableHrtf] Trying sources-only fallback...");
                        int[] attrsNoHrtf = {
                                org.lwjgl.openal.ALC11.ALC_MONO_SOURCES, 1024,
                                0
                        };
                        boolean fallback = SOFTHRTF.alcResetDeviceSOFT(device, attrsNoHrtf);
                        int fallbackError = ALC10.alcGetError(device);
                        System.out.println("[enableHrtf] Fallback reset returned: " + fallback
                                + " alcError=0x" + Integer.toHexString(fallbackError));
                    }
                } else {
                    System.out.println("[enableHrtf] No HRTF profiles, trying sources-only...");
                    int[] attrs = {
                            org.lwjgl.openal.ALC11.ALC_MONO_SOURCES, 1024,
                            0
                    };
                    boolean success = SOFTHRTF.alcResetDeviceSOFT(device, attrs);
                    int postError = ALC10.alcGetError(device);
                    System.out.println("[enableHrtf] Sources-only reset returned: " + success
                            + " alcError=0x" + Integer.toHexString(postError));
                }
            } else {
                System.out.println("[enableHrtf] ALC_SOFT_HRTF not available, skipping.");
            }
        } catch (Exception e) {
            System.err.println("[enableHrtf] EXCEPTION: " + e.getMessage());
            e.printStackTrace();
        }
    }

    /** Result of EFX initialization — OpenAL IDs to store in the caller. */
    public static class InitResult {
        public final int reverbEffectId;
        public final int auxSlotId;

        InitResult(int reverbEffectId, int auxSlotId) {
            this.reverbEffectId = reverbEffectId;
            this.auxSlotId = auxSlotId;
        }
    }
}
