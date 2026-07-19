package com.audiophilecraft.sound;

import static org.lwjgl.openal.AL10.*;
import static org.lwjgl.openal.EXTEfx.*;

/** Owns one physical OpenAL room-reverb effect and its auxiliary slot. */
final class RoomReverbBus {
    private int effectId;
    private int auxSlotId;
    private boolean usesEax;

    boolean initialize() {
        cleanup();

        effectId = alGenEffects();
        if (alGetError() != AL_NO_ERROR) {
            effectId = 0;
            return false;
        }

        alEffecti(effectId, AL_EFFECT_TYPE, AL_EFFECT_EAXREVERB);
        usesEax = alGetError() == AL_NO_ERROR;
        if (!usesEax) {
            alEffecti(effectId, AL_EFFECT_TYPE, AL_EFFECT_REVERB);
            if (alGetError() != AL_NO_ERROR) {
                cleanup();
                return false;
            }
        }

        configureDefaults();
        if (alGetError() != AL_NO_ERROR) {
            cleanup();
            return false;
        }

        auxSlotId = alGenAuxiliaryEffectSlots();
        if (alGetError() != AL_NO_ERROR) {
            auxSlotId = 0;
            cleanup();
            return false;
        }
        attachEffect();
        if (alGetError() != AL_NO_ERROR) {
            cleanup();
            return false;
        }
        return true;
    }

    boolean isAvailable() {
        return effectId != 0 && auxSlotId != 0;
    }

    int auxSlotId() {
        return auxSlotId;
    }

    void setFloat(int eaxParameter, int standardParameter, float value) {
        if (effectId == 0) return;
        int parameter = usesEax ? eaxParameter : standardParameter;
        if (parameter >= 0) alEffectf(effectId, parameter, value);
    }

    void setInt(int eaxParameter, int standardParameter, int value) {
        if (effectId == 0) return;
        int parameter = usesEax ? eaxParameter : standardParameter;
        if (parameter >= 0) alEffecti(effectId, parameter, value);
    }

    void setPan(int eaxParameter, float[] value) {
        if (effectId != 0 && usesEax) alEffectfv(effectId, eaxParameter, value);
    }

    void attachEffect() {
        if (isAvailable()) alAuxiliaryEffectSloti(auxSlotId, AL_EFFECTSLOT_EFFECT, effectId);
    }

    void setPaused(boolean paused) {
        if (isAvailable()) {
            alAuxiliaryEffectSloti(auxSlotId, AL_EFFECTSLOT_EFFECT, paused ? AL_EFFECT_NULL : effectId);
        }
    }

    void setSlotGain(float gain) {
        if (auxSlotId != 0) alAuxiliaryEffectSlotf(auxSlotId, AL_EFFECTSLOT_GAIN, gain);
    }

    void cleanup() {
        if (auxSlotId != 0) {
            alAuxiliaryEffectSloti(auxSlotId, AL_EFFECTSLOT_EFFECT, AL_EFFECT_NULL);
            alDeleteAuxiliaryEffectSlots(auxSlotId);
            auxSlotId = 0;
        }
        if (effectId != 0) {
            alDeleteEffects(effectId);
            effectId = 0;
        }
        usesEax = false;
    }

    private void configureDefaults() {
        setFloat(AL_EAXREVERB_DECAY_TIME, AL_REVERB_DECAY_TIME, 0.3f);
        setFloat(AL_EAXREVERB_REFLECTIONS_GAIN, AL_REVERB_REFLECTIONS_GAIN, 0.3f);
        setFloat(AL_EAXREVERB_REFLECTIONS_DELAY, AL_REVERB_REFLECTIONS_DELAY, 0.02f);
        setFloat(AL_EAXREVERB_LATE_REVERB_GAIN, AL_REVERB_LATE_REVERB_GAIN, 0.1f);
        setFloat(AL_EAXREVERB_LATE_REVERB_DELAY, AL_REVERB_LATE_REVERB_DELAY, 0.04f);
        setFloat(AL_EAXREVERB_DIFFUSION, AL_REVERB_DIFFUSION, 0.7f);
        setFloat(AL_EAXREVERB_DENSITY, AL_REVERB_DENSITY, 0.5f);
        setFloat(AL_EAXREVERB_GAIN, AL_REVERB_GAIN, 0.3f);
        setFloat(AL_EAXREVERB_GAINHF, AL_REVERB_GAINHF, 0.6f);
        setFloat(AL_EAXREVERB_GAINLF, -1, 0.8f);
        setFloat(AL_EAXREVERB_DECAY_HFRATIO, AL_REVERB_DECAY_HFRATIO, 0.5f);
        setFloat(AL_EAXREVERB_DECAY_LFRATIO, -1, 1.1f);
        setFloat(AL_EAXREVERB_AIR_ABSORPTION_GAINHF, AL_REVERB_AIR_ABSORPTION_GAINHF, 0.994f);
        setInt(AL_EAXREVERB_DECAY_HFLIMIT, AL_REVERB_DECAY_HFLIMIT, 1);
    }
}
