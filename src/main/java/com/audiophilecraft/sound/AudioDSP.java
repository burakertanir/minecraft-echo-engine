package com.audiophilecraft.sound;

public class AudioDSP {

    /**
     * Applies a Biquad filter to the audio data in-place.
     * Based on Robert Bristow-Johnson's Audio EQ Cookbook.
     */
    public static void applyFilter(short[] data, int sampleRate, FilterType type, float frequency, float Q,
            float dbGain) {
        double fs = sampleRate;
        double w0 = 2 * Math.PI * frequency / fs;
        double cosW0 = Math.cos(w0);
        double sinW0 = Math.sin(w0);
        double alpha = sinW0 / (2 * Q);
        double A = Math.pow(10, dbGain / 40); // For peaking/shelving

        double b0 = 0, b1 = 0, b2 = 0, a0 = 0, a1 = 0, a2 = 0;

        switch (type) {
            case LOW_PASS:
                b0 = (1 - cosW0) / 2;
                b1 = 1 - cosW0;
                b2 = (1 - cosW0) / 2;
                a0 = 1 + alpha;
                a1 = -2 * cosW0;
                a2 = 1 - alpha;
                break;
            case HIGH_PASS:
                b0 = (1 + cosW0) / 2;
                b1 = -(1 + cosW0);
                b2 = (1 + cosW0) / 2;
                a0 = 1 + alpha;
                a1 = -2 * cosW0;
                a2 = 1 - alpha;
                break;
            case BAND_PASS:
                b0 = alpha;
                b1 = 0;
                b2 = -alpha;
                a0 = 1 + alpha;
                a1 = -2 * cosW0;
                a2 = 1 - alpha;
                break;
            case PEAKING_EQ:
                b0 = 1 + alpha * A;
                b1 = -2 * cosW0;
                b2 = 1 - alpha * A;
                a0 = 1 + alpha / A;
                a1 = -2 * cosW0;
                a2 = 1 - alpha / A;
                break;
            case HIGH_SHELF:
                b0 = A * ((A + 1) + (A - 1) * cosW0 + 2 * Math.sqrt(A) * alpha);
                b1 = -2 * A * ((A - 1) + (A + 1) * cosW0);
                b2 = A * ((A + 1) + (A - 1) * cosW0 - 2 * Math.sqrt(A) * alpha);
                a0 = (A + 1) - (A - 1) * cosW0 + 2 * Math.sqrt(A) * alpha;
                a1 = 2 * ((A - 1) - (A + 1) * cosW0);
                a2 = (A + 1) - (A - 1) * cosW0 - 2 * Math.sqrt(A) * alpha;
                break;
            case LOW_SHELF: // Useful if we ever need it
                b0 = A * ((A + 1) - (A - 1) * cosW0 + 2 * Math.sqrt(A) * alpha);
                b1 = 2 * A * ((A - 1) - (A + 1) * cosW0);
                b2 = A * ((A + 1) - (A - 1) * cosW0 - 2 * Math.sqrt(A) * alpha);
                a0 = (A + 1) + (A - 1) * cosW0 + 2 * Math.sqrt(A) * alpha;
                a1 = -2 * ((A - 1) + (A + 1) * cosW0);
                a2 = (A + 1) + (A - 1) * cosW0 - 2 * Math.sqrt(A) * alpha;
                break;
        }

        // Normalize coefficients
        b0 /= a0;
        b1 /= a0;
        b2 /= a0;
        a1 /= a0;
        a2 /= a0;

        // Apply filter
        double x1 = 0, x2 = 0, y1 = 0, y2 = 0;
        for (int i = 0; i < data.length; i++) {
            double x0 = data[i];
            double y0 = b0 * x0 + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2;

            // Anti-Denormal: Flush microscopic values to zero to prevent extreme CPU
            // slowdown
            if (Math.abs(y0) < 1e-15) {
                y0 = 0.0;
            }

            // Hardware safety: Prevent IIR explosion cascades from NaN anomalies
            if (Double.isNaN(y0) || Double.isInfinite(y0)) {
                y0 = 0;
                x1 = 0;
                x2 = 0;
                y1 = 0;
                y2 = 0;
            }

            // Output must be clamped, but Internal Feedback Memory MUST NOT BE CLAMPED.
            // Clamping IIR history destroys pole stability and causes infinite crackling
            // limit-cycles.
            double out = y0;
            if (out > 32767)
                out = 32767;
            if (out < -32768)
                out = -32768;

            data[i] = (short) out;

            x2 = x1;
            x1 = x0;
            y2 = y1;
            y1 = y0; // TRUE continuous mathematical trajectory
        }
    }

    public static void applyGain(short[] data, float gain) {
        for (int i = 0; i < data.length; i++) {
            int val = (int) (data[i] * gain);
            if (val > 32767)
                val = 32767;
            if (val < -32768)
                val = -32768;
            data[i] = (short) val;
        }
    }

    /**
     * Soft Clipping (tanh Saturation)
     * Simulates speaker distortion when driven too hard.
     * drive: 1.0 = clean, 2.0+ = noticeable distortion, 4.0+ = heavy crunch
     */
    public static void applySoftClip(short[] data, float drive) {
        if (drive < 1.0f)
            drive = 1.0f;
        for (int i = 0; i < data.length; i++) {
            // Normalize to -1.0 ... 1.0
            double sample = data[i] / 32767.0;
            // Apply drive (amplify before clipping)
            sample *= drive;
            // tanh saturation — smoothly squashes peaks, adds harmonics
            sample = Math.tanh(sample);
            // Back to short range
            data[i] = (short) (sample * 32767);
        }
    }

    public static void applyPeakLimiter(short[] data, float threshold) {
        if (threshold <= 0.0f)
            return;
        if (threshold > 1.0f)
            threshold = 1.0f;

        int peak = 0;
        for (int i = 0; i < data.length; i++) {
            int v = data[i];
            if (v < 0)
                v = -v;
            if (v > peak)
                peak = v;
        }

        int limit = Math.round(threshold * 32767.0f);
        if (peak <= limit)
            return;

        float gain = (float) limit / (float) peak;
        applyGain(data, gain);
    }

    /**
     * Dynamic Range Compression
     * Makes quiet parts louder and loud parts clip — "Wall of Sound" effect.
     * threshold: 0.0-1.0 (signal level above which compression kicks in, e.g. 0.3)
     * ratio: compression ratio (e.g. 4.0 means 4:1 compression)
     * makeupGain: gain applied after compression to bring overall level up (e.g.
     * 1.5)
     */
    public static void applyCompression(short[] data, float threshold, float ratio, float makeupGain) {
        if (ratio <= 1.0f)
            return;
        double threshLin = threshold; // threshold in linear (0.0-1.0)
        for (int i = 0; i < data.length; i++) {
            double sample = data[i] / 32767.0;
            double sign = sample >= 0 ? 1.0 : -1.0;
            double abs = Math.abs(sample);

            if (abs > threshLin) {
                // Compress the amount above threshold
                double excess = abs - threshLin;
                double compressed = threshLin + excess / ratio;
                abs = compressed;
            }

            // Apply makeup gain
            abs *= makeupGain;

            // Soft clip the result to prevent harsh digital clipping
            sample = sign * Math.tanh(abs);

            data[i] = (short) (sample * 32767);
        }
    }

    public enum FilterType {
        LOW_PASS, HIGH_PASS, BAND_PASS, PEAKING_EQ, HIGH_SHELF, LOW_SHELF
    }

    public static class BiquadFilter {
        private double b0, b1, b2, a1, a2;
        private double x1 = 0, x2 = 0, y1 = 0, y2 = 0;

        public BiquadFilter(FilterType type, float sampleRate, float frequency, float Q, float dbGain) {
            double fs = sampleRate;
            double w0 = 2 * Math.PI * frequency / fs;
            double cosW0 = Math.cos(w0);
            double sinW0 = Math.sin(w0);
            double alpha = sinW0 / (2 * Q);
            double A = Math.pow(10, dbGain / 40);

            double a0 = 0;
            switch (type) {
                case LOW_PASS:
                    b0 = (1 - cosW0) / 2;
                    b1 = 1 - cosW0;
                    b2 = (1 - cosW0) / 2;
                    a0 = 1 + alpha;
                    a1 = -2 * cosW0;
                    a2 = 1 - alpha;
                    break;
                case HIGH_PASS:
                    b0 = (1 + cosW0) / 2;
                    b1 = -(1 + cosW0);
                    b2 = (1 + cosW0) / 2;
                    a0 = 1 + alpha;
                    a1 = -2 * cosW0;
                    a2 = 1 - alpha;
                    break;
                case BAND_PASS:
                    b0 = alpha;
                    b1 = 0;
                    b2 = -alpha;
                    a0 = 1 + alpha;
                    a1 = -2 * cosW0;
                    a2 = 1 - alpha;
                    break;
                case PEAKING_EQ:
                    b0 = 1 + alpha * A;
                    b1 = -2 * cosW0;
                    b2 = 1 - alpha * A;
                    a0 = 1 + alpha / A;
                    a1 = -2 * cosW0;
                    a2 = 1 - alpha / A;
                    break;
                case HIGH_SHELF:
                    b0 = A * ((A + 1) + (A - 1) * cosW0 + 2 * Math.sqrt(A) * alpha);
                    b1 = -2 * A * ((A - 1) + (A + 1) * cosW0);
                    b2 = A * ((A + 1) + (A - 1) * cosW0 - 2 * Math.sqrt(A) * alpha);
                    a0 = (A + 1) - (A - 1) * cosW0 + 2 * Math.sqrt(A) * alpha;
                    a1 = 2 * ((A - 1) - (A + 1) * cosW0);
                    a2 = (A + 1) - (A - 1) * cosW0 - 2 * Math.sqrt(A) * alpha;
                    break;
                case LOW_SHELF:
                    b0 = A * ((A + 1) - (A - 1) * cosW0 + 2 * Math.sqrt(A) * alpha);
                    b1 = 2 * A * ((A - 1) - (A + 1) * cosW0);
                    b2 = A * ((A + 1) - (A - 1) * cosW0 - 2 * Math.sqrt(A) * alpha);
                    a0 = (A + 1) + (A - 1) * cosW0 + 2 * Math.sqrt(A) * alpha;
                    a1 = -2 * ((A - 1) + (A + 1) * cosW0);
                    a2 = (A + 1) + (A - 1) * cosW0 - 2 * Math.sqrt(A) * alpha;
                    break;
            }
            this.b0 = b0 / a0;
            this.b1 = b1 / a0;
            this.b2 = b2 / a0;
            this.a1 = a1 / a0;
            this.a2 = a2 / a0;
        }

        public void reset() {
            x1 = 0;
            x2 = 0;
            y1 = 0;
            y2 = 0;
        }

        public void process(short[] data) {
            for (int i = 0; i < data.length; i++) {
                double x0 = data[i];
                double y0 = b0 * x0 + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2;

                // Anti-Denormal: Flush microscopic values to zero to prevent extreme CPU
                // slowdown
                if (Math.abs(y0) < 1e-15) {
                    y0 = 0.0;
                }

                // Hardware safety: Prevent IIR explosion cascades from NaN anomalies
                if (Double.isNaN(y0) || Double.isInfinite(y0)) {
                    y0 = 0;
                    x1 = 0;
                    x2 = 0;
                    y1 = 0;
                    y2 = 0;
                }

                // Output must be clamped, but Internal Feedback Memory MUST NOT BE CLAMPED.
                // Clamping IIR history destroys pole stability and causes infinite crackling
                // limit-cycles.
                double out = y0;
                if (out > 32767)
                    out = 32767;
                if (out < -32768)
                    out = -32768;

                data[i] = (short) out;
                x2 = x1;
                x1 = x0;
                y2 = y1;
                y1 = y0; // TRUE continuous mathematical trajectory
            }
        }
    }
}
