package com.audiophilecraft.sound;

/**
 * ═══════════════════════════════════════════════════════════════════════
 * LIVE PEAK METER — Zero-allocation, lock-free audio level monitor.
 * ═══════════════════════════════════════════════════════════════════════
 * Uses an Envelope Follower approach with Micro-Hold to prevent visual
 * flickering/vibration while perfectly tracking the audio peaks.
 */
public class PeakMeter {

    private static final PeakMeter INSTANCE = new PeakMeter();

    public static PeakMeter getInstance() {
        return INSTANCE;
    }

    // --- Per-channel raw peaks (written by audio thread) ---
    private volatile float rawPeakSub = 0f;
    private volatile float rawPeakMid = 0f;
    private volatile float rawPeakLine = 0f;

    private volatile long lastSubWrite = 0;
    private volatile long lastMidWrite = 0;
    private volatile long lastLineWrite = 0;

    // --- Smoothed display values (read/written by UI thread only) ---
    private float displayPeakSub = 0f;
    private float displayPeakMid = 0f;
    private float displayPeakLine = 0f;

    // Micro-hold timers to prevent inter-buffer vibrating
    private float decayDelaySub = 0f;
    private float decayDelayMid = 0f;
    private float decayDelayLine = 0f;
    private static final float MICRO_HOLD_SECONDS = 0.05f; // 50ms bridge

    // --- Peak hold (the little white line that slowly falls) ---
    private float holdPeakSub = 0f;
    private float holdPeakMid = 0f;
    private float holdPeakLine = 0f;
    private float holdTimerSub = 0f;
    private float holdTimerMid = 0f;
    private float holdTimerLine = 0f;

    private static final float HOLD_SECONDS = 1.5f;
    private static final float DECAY_PER_SECOND = 1.6f;
    private static final float HOLD_FALL_PER_SECOND = 0.4f;

    private long lastUpdateTime = System.nanoTime();

    private PeakMeter() {}

    /**
     * Feed the peak value from a processed PCM buffer.
     * Called on the audio thread after DSP processing.
     */
    public void feedPeak(String speakerType, short[] samples, int length) {
        int peak = 0;
        for (int i = 0; i < length; i++) {
            int abs = samples[i] < 0 ? -samples[i] : samples[i];
            if (abs > peak) peak = abs;
        }

        float linear = peak / 32767f;
        // Convert linear amplitude to Logarithmic dBFS for professional studio meter response
        // 0 dBFS = 1.0 (100%), -48 dBFS = 0.0 (0%)
        float db = linear > 0.001f ? (float) (20.0 * Math.log10(linear)) : -60f;
        float normalized = (db + 48.0f) / 48.0f;
        if (normalized < 0f) normalized = 0f;
        if (normalized > 1f) normalized = 1f;

        long now = System.currentTimeMillis();

        switch (speakerType) {
            case "sub":
                if (now - lastSubWrite > 30) {
                    rawPeakSub = normalized;
                    lastSubWrite = now;
                } else {
                    if (normalized > rawPeakSub) rawPeakSub = normalized;
                }
                break;
            case "mid":
                if (now - lastMidWrite > 30) {
                    rawPeakMid = normalized;
                    lastMidWrite = now;
                } else {
                    if (normalized > rawPeakMid) rawPeakMid = normalized;
                }
                break;
            case "line":
                if (now - lastLineWrite > 30) {
                    rawPeakLine = normalized;
                    lastLineWrite = now;
                } else {
                    if (normalized > rawPeakLine) rawPeakLine = normalized;
                }
                break;
        }
    }

    /**
     * Update the meter ballistics using real wall-clock delta time.
     * Call this from render() for butter-smooth 60+ FPS animation.
     */
    public void update() {
        long nowTime = System.nanoTime();
        float dt = (nowTime - lastUpdateTime) / 1_000_000_000f;
        lastUpdateTime = nowTime;

        if (dt > 0.1f) dt = 0.1f;

        long now = System.currentTimeMillis();

        // 1. If no audio fed for 100ms (e.g. playback stopped), force raw peaks to 0
        if (now - lastSubWrite > 100) rawPeakSub = 0f;
        if (now - lastMidWrite > 100) rawPeakMid = 0f;
        if (now - lastLineWrite > 100) rawPeakLine = 0f;

        // 2. Apply Envelope Follower with Micro-Hold
        float[] resSub = applyEnvelope(displayPeakSub, decayDelaySub, rawPeakSub, dt);
        displayPeakSub = resSub[0];
        decayDelaySub = resSub[1];

        float[] resMid = applyEnvelope(displayPeakMid, decayDelayMid, rawPeakMid, dt);
        displayPeakMid = resMid[0];
        decayDelayMid = resMid[1];

        float[] resLine = applyEnvelope(displayPeakLine, decayDelayLine, rawPeakLine, dt);
        displayPeakLine = resLine[0];
        decayDelayLine = resLine[1];

        // 3. Update main Peak Hold lines
        float[] holdRes;
        holdRes = updateHold(holdPeakSub, holdTimerSub, displayPeakSub, dt);
        holdPeakSub = holdRes[0];
        holdTimerSub = holdRes[1];

        holdRes = updateHold(holdPeakMid, holdTimerMid, displayPeakMid, dt);
        holdPeakMid = holdRes[0];
        holdTimerMid = holdRes[1];

        holdRes = updateHold(holdPeakLine, holdTimerLine, displayPeakLine, dt);
        holdPeakLine = holdRes[0];
        holdTimerLine = holdRes[1];
    }

    private float[] applyEnvelope(float current, float delayTimer, float incoming, float dt) {
        if (incoming >= current) {
            // Very fast exponential attack (silky smooth at high Hz instead of 1-frame instant snap)
            float lerpSpeed = 40.0f;
            float newCurrent = current + (incoming - current) * (1.0f - (float) Math.exp(-lerpSpeed * dt));
            if (incoming - newCurrent < 0.005f) newCurrent = incoming; // Snap if extremely close

            return new float[] {newCurrent, MICRO_HOLD_SECONDS};
        } else {
            // Incoming is lower. Check if micro-hold is active
            if (delayTimer > 0) {
                return new float[] {current, delayTimer - dt};
            } else {
                // Micro-hold expired, start smooth visual decay
                float newCurrent = Math.max(0f, current - DECAY_PER_SECOND * dt);
                return new float[] {newCurrent, 0f};
            }
        }
    }

    private float[] updateHold(float holdValue, float holdTimer, float currentPeak, float delta) {
        if (currentPeak >= holdValue) {
            return new float[] {currentPeak, HOLD_SECONDS};
        }
        if (holdTimer > 0) {
            return new float[] {holdValue, holdTimer - delta};
        }
        return new float[] {Math.max(0f, holdValue - HOLD_FALL_PER_SECOND * delta), 0f};
    }

    // --- GETTERS ---
    public float getDisplayPeak(String type) {
        switch (type) {
            case "sub":
                return displayPeakSub;
            case "mid":
                return displayPeakMid;
            case "line":
                return displayPeakLine;
            default:
                return 0f;
        }
    }

    public float getHoldPeak(String type) {
        switch (type) {
            case "sub":
                return holdPeakSub;
            case "mid":
                return holdPeakMid;
            case "line":
                return holdPeakLine;
            default:
                return 0f;
        }
    }

    public void reset() {
        rawPeakSub = 0f;
        rawPeakMid = 0f;
        rawPeakLine = 0f;
        displayPeakSub = 0f;
        displayPeakMid = 0f;
        displayPeakLine = 0f;
        holdPeakSub = 0f;
        holdPeakMid = 0f;
        holdPeakLine = 0f;
        decayDelaySub = 0f;
        decayDelayMid = 0f;
        decayDelayLine = 0f;
        holdTimerSub = 0f;
        holdTimerMid = 0f;
        holdTimerLine = 0f;
    }
}
