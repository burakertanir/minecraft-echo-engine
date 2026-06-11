package com.audiophilecraft.sound;

/**
 * Self-contained DSP pipeline: input gain → crossover → 5-band EQ → soft clip → limiter.
 * Extracted from StreamSource to allow per-session DSP configurations.
 */
public class StreamDSPPipeline {

    private final AudioDSP.BiquadFilter crossoverFilter1;
    private final AudioDSP.BiquadFilter crossoverFilter2;
    private final AudioDSP.BiquadFilter[] eqFilters = new AudioDSP.BiquadFilter[5];
    private final float[] lastEq = new float[5];
    private final float[] lastQ = new float[] {1f, 1f, 1f, 1f, 1f};
    private final float[] eqFrequencies;
    private final String speakerType;
    private final PlaybackSession session;

    public StreamDSPPipeline(PlaybackSession session, String speakerType, float sampleRate) {
        this.session = session;
        this.speakerType = speakerType;

        if ("sub".equals(speakerType)) {
            crossoverFilter1 =
                    new AudioDSP.BiquadFilter(AudioDSP.FilterType.LOW_PASS, sampleRate, 120.0f, 0.707f, 0.0f);
            crossoverFilter2 = null;
            eqFrequencies = new float[] {30f, 50f, 70f, 90f, 110f};
        } else if ("mid".equals(speakerType)) {
            crossoverFilter1 =
                    new AudioDSP.BiquadFilter(AudioDSP.FilterType.HIGH_PASS, sampleRate, 120.0f, 0.707f, 0.0f);
            crossoverFilter2 =
                    new AudioDSP.BiquadFilter(AudioDSP.FilterType.LOW_PASS, sampleRate, 4000.0f, 0.707f, 0.0f);
            eqFrequencies = new float[] {250f, 500f, 1000f, 2000f, 4000f};
        } else {
            crossoverFilter1 =
                    new AudioDSP.BiquadFilter(AudioDSP.FilterType.HIGH_PASS, sampleRate, 120.0f, 0.707f, 0.0f);
            crossoverFilter2 = null;
            eqFrequencies = new float[] {1000f, 3000f, 6000f, 10000f, 15000f};
        }
    }

    public void reset() {
        if (crossoverFilter1 != null) crossoverFilter1.reset();
        if (crossoverFilter2 != null) crossoverFilter2.reset();
        for (int i = 0; i < 5; i++) {
            if (eqFilters[i] != null) eqFilters[i].reset();
        }
    }

    /**
     * Runs the full DSP chain on the audio buffer.
     */
    public void process(short[] data, float sampleRate, float inputGain, float power) {
        AudioDSP.applyGain(data, inputGain);

        if (crossoverFilter1 != null) crossoverFilter1.process(data);
        if (crossoverFilter2 != null) crossoverFilter2.process(data);

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
            if (eqFilters[i] != null) eqFilters[i].process(data);
        }

        if (power > 5.0f) {
            float drive = 1.0f + ((power - 5.0f) * 0.1f);
            AudioDSP.applySoftClip(data, drive);
        }
        AudioDSP.applyPeakLimiter(data, 0.98f);
    }
}
