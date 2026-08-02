package com.audiophilecraft.sound;

import com.audiophilecraft.config.LiveTuningConfig;

/**
 * Self-contained DSP pipeline: input gain, crossover, 5-band EQ and speaker harmonics.
 * Extracted from StreamSource to allow per-session DSP configurations.
 */
public class StreamDSPPipeline {

    private final AudioDSP.BiquadFilter crossoverFilter1;
    private final AudioDSP.BiquadFilter crossoverFilter2;
    private final AudioDSP.BiquadFilter[] eqFilters = new AudioDSP.BiquadFilter[5];
    private final float[] lastEq = new float[5];
    private final float[] lastQ = new float[] { 1f, 1f, 1f, 1f, 1f };
    private final float[] eqFrequencies;
    private final String speakerType;
    private final PlaybackSession session;
    private final AudioDSP.HarmonicSaturator harmonicSaturator;

    public StreamDSPPipeline(PlaybackSession session, String speakerType, float sampleRate) {
        this.session = session;
        this.speakerType = speakerType;

        if ("sub".equals(speakerType)) {
            // 24dB/oct Butterworth LP at 120Hz — subwoofer only
            crossoverFilter1 = new AudioDSP.BiquadFilter(AudioDSP.FilterType.LOW_PASS, sampleRate, 120.0f, 0.707f,
                    0.0f);
            crossoverFilter2 = new AudioDSP.BiquadFilter(AudioDSP.FilterType.LOW_PASS, sampleRate, 120.0f, 0.707f,
                    0.0f);
            eqFrequencies = new float[] { 30f, 50f, 70f, 90f, 110f };
        } else if ("mid".equals(speakerType)) {
            // Yamaha HS8 full-range: gentle rolloff at 45Hz (-3dB noktasi)
            crossoverFilter1 = new AudioDSP.BiquadFilter(AudioDSP.FilterType.HIGH_PASS, sampleRate, 45.0f, 0.577f,
                    0.0f);
            crossoverFilter2 = null;
            eqFrequencies = new float[] { 100f, 400f, 1000f, 4000f, 10000f };
        } else if ("line".equals(speakerType)) {
            // 24dB/oct HP at 120Hz — sub ile eslesir
            crossoverFilter1 = new AudioDSP.BiquadFilter(AudioDSP.FilterType.HIGH_PASS, sampleRate, 120.0f, 0.707f,
                    0.0f);
            crossoverFilter2 = new AudioDSP.BiquadFilter(AudioDSP.FilterType.HIGH_PASS, sampleRate, 120.0f, 0.707f,
                    0.0f);
            eqFrequencies = new float[] { 2000f, 4000f, 6000f, 10000f, 15000f };
        } else { // normal — full range, no crossover
            crossoverFilter1 = null;
            crossoverFilter2 = null;
            eqFrequencies = new float[] { 250f, 500f, 1000f, 2000f, 4000f };
        }

        if ("sub".equals(speakerType)) {
            harmonicSaturator = new AudioDSP.HarmonicSaturator(sampleRate, 0.85f, 0.15f);
        } else if ("mid".equals(speakerType)) {
            harmonicSaturator = new AudioDSP.HarmonicSaturator(sampleRate, 0.50f, 0.35f);
        } else if ("line".equals(speakerType)) {
            harmonicSaturator = new AudioDSP.HarmonicSaturator(sampleRate, 0.20f, 0.55f);
        } else {
            harmonicSaturator = new AudioDSP.HarmonicSaturator(sampleRate, 0.45f, 0.40f);
        }
    }

    public void reset() {
        if (crossoverFilter1 != null)
            crossoverFilter1.reset();
        if (crossoverFilter2 != null)
            crossoverFilter2.reset();
        for (int i = 0; i < 5; i++) {
            if (eqFilters[i] != null)
                eqFilters[i].reset();
        }
        harmonicSaturator.reset();
    }

    /**
     * Runs the full DSP chain on the audio buffer.
     */
    public void process(short[] data, float sampleRate, float inputGain, float power) {
        AudioDSP.applyGain(data, inputGain);

        if (crossoverFilter1 != null)
            crossoverFilter1.process(data);
        if (crossoverFilter2 != null)
            crossoverFilter2.process(data);

        for (int i = 0; i < 5; i++) {
            float db = session.getEqDb(speakerType, i);
            float q = session.getEqQ(speakerType, i);
            if (db != lastEq[i] || q != lastQ[i] || eqFilters[i] == null) {
                lastEq[i] = db;
                lastQ[i] = q;
                if (Math.abs(db) > 0.1f) {
                    eqFilters[i] = new AudioDSP.BiquadFilter(
                            AudioDSP.FilterType.PEAKING_EQ, sampleRate, eqFrequencies[i], q, db);
                } else {
                    eqFilters[i] = null;
                }
            }
            if (eqFilters[i] != null)
                eqFilters[i].process(data);
        }

        LiveTuningConfig config = LiveTuningConfig.get();
        float amount =
                switch (speakerType) {
                    case "sub" -> config.harmonics_subAmount;
                    case "mid" -> config.harmonics_midAmount;
                    case "line" -> config.harmonics_lineAmount;
                    default -> config.harmonics_normalAmount;
                };
        float requestedAmount = amount * Math.max(0.0f, config.harmonics_master);
        harmonicSaturator.process(data, requestedAmount, power, config.harmonics_powerInfluence);
    }
}
