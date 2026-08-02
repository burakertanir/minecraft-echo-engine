package com.audiophilecraft.sound;

import com.audiophilecraft.config.LiveTuningConfig;
import net.minecraft.util.math.Vec3d;

final class VenuePresetCalculator {
    AdvancedAcousticScanner.VenuePreset calculate(AdvancedAcousticScanner.VenueDescriptor descriptor, Vec3d probePos) {
        LiveTuningConfig cfg = LiveTuningConfig.get();
        float vAvgAbsorption = descriptor.avgAbsorption;
        float vMeanDist = descriptor.scale;
        float vEnclosure = descriptor.enclosure;
        float vOpenness = descriptor.openness;

        float openAirBlend = Math.max(0.0f, Math.min(1.0f, vOpenness));

        float opennessPenalty =
                (float) Math.pow(Math.max(0.0f, 1.0f - vOpenness), cfg.openAir_enclosure_penalty_exponent);
        float effectiveEnclosure = vEnclosure * opennessPenalty;
        effectiveEnclosure = Math.max(0.0f, Math.min(1.0f, effectiveEnclosure));

        float vVolume = descriptor.trueVolume;
        float surfaceArea = descriptor.trueSurfaceArea;
        float totalSabins = Math.max(0.01f, surfaceArea * vAvgAbsorption);
        float vDecay = 0.161f * vVolume / totalSabins;

        if (vDecay < 0.1f) {
            vDecay = 0.1f;
        }
        if (vDecay > 15.0f) {
            vDecay = 15.0f;
        }

        float baseEnclosureMultiplier = 0.5f + effectiveEnclosure * 0.5f;
        float vGain = baseEnclosureMultiplier * 0.65f;
        float vGainHF = 0.3f + (1.0f - vAvgAbsorption) * 0.6f;
        float vGainLF = 0.7f + effectiveEnclosure * 0.3f;

        float roomFactor = Math.min(vMeanDist / 30.0f, 1.0f);
        float vReflGain;
        float vReflDelay;
        float lateReverbMultiplier;
        float vLateGain;
        float vLateDelay;
        float vEchoTime;
        float vEchoDepth;

        float reflectionMaterialFactor = 1.0f - vAvgAbsorption;
        float effectiveVolume = vVolume * effectiveEnclosure;
        float effectiveMeanDist = vMeanDist * (float) Math.sqrt(effectiveEnclosure);

        boolean tier10 = effectiveVolume > cfg.tier10_volumeThreshold || effectiveMeanDist > cfg.tier10_distThreshold;
        boolean tier9 = effectiveVolume > cfg.tier9_volumeThreshold || effectiveMeanDist > cfg.tier9_distThreshold;
        boolean tier8 = effectiveVolume > cfg.tier8_volumeThreshold || effectiveMeanDist > cfg.tier8_distThreshold;
        boolean tier7 = effectiveVolume > cfg.tier7_volumeThreshold || effectiveMeanDist > cfg.tier7_distThreshold;

        float enclBlend = Math.max(0.0f, Math.min(1.0f, (effectiveEnclosure - 0.4f) / 0.4f));

        String tierName;
        if (tier10) {
            tierName = "TIER 10 (INFINITE CATHEDRAL / VOID)";
            vDecay *= cfg.tier10_decayMul;
            vGain = Math.max(cfg.tier10_minGain, baseEnclosureMultiplier * cfg.tier10_gainMul);
            vReflGain = Math.max(0.0f, reflectionMaterialFactor * cfg.tier10_reflGainMul);
            float maxLate10 =
                    lerp(cfg.tier10_maxLateMultiplier_lowEncl, cfg.tier10_maxLateMultiplier_highEncl, enclBlend);
            lateReverbMultiplier =
                    Math.min(cfg.tier10_lateReverbMul + roomFactor * cfg.tier10_lateReverbRoomScale, maxLate10);
            vGainHF *= cfg.tier10_hfMul;
            vGainLF *= cfg.tier10_lfMul;
        } else if (tier9) {
            tierName = "TIER 9 (MEGA COMPLEX / CITY BLOCK)";
            vDecay *= cfg.tier9_decayMul;
            vGain = Math.max(cfg.tier9_minGain, baseEnclosureMultiplier * cfg.tier9_gainMul);
            vReflGain = Math.max(0.0f, reflectionMaterialFactor * cfg.tier9_reflGainMul);
            float maxLate9 = lerp(cfg.tier9_maxLateMultiplier_lowEncl, cfg.tier9_maxLateMultiplier_highEncl, enclBlend);
            lateReverbMultiplier =
                    Math.min(cfg.tier9_lateReverbMul + roomFactor * cfg.tier9_lateReverbRoomScale, maxLate9);
            vGainHF *= cfg.tier9_hfMul;
            vGainLF *= cfg.tier9_lfMul;
        } else if (tier8) {
            tierName = "TIER 8 (COLOSSAL DOME / HANGAR)";
            vDecay *= cfg.tier8_decayMul;
            vGain = Math.max(cfg.tier8_minGain, baseEnclosureMultiplier * cfg.tier8_gainMul);
            vReflGain = Math.max(0.0f, reflectionMaterialFactor * cfg.tier8_reflGainMul);
            float maxLate8 = lerp(cfg.tier8_maxLateMultiplier_lowEncl, cfg.tier8_maxLateMultiplier_highEncl, enclBlend);
            lateReverbMultiplier =
                    Math.min(cfg.tier8_lateReverbMul + roomFactor * cfg.tier8_lateReverbRoomScale, maxLate8);
            vGainHF *= cfg.tier8_hfMul;
            vGainLF *= cfg.tier8_lfMul;
        } else if (tier7) {
            tierName = "TIER 7 (MASSIVE STADIUM)";
            vDecay *= cfg.tier7_decayMul;
            vGain = Math.max(cfg.tier7_minGain, baseEnclosureMultiplier * cfg.tier7_gainMul);
            vReflGain = Math.max(0.0f, reflectionMaterialFactor * cfg.tier7_reflGainMul);
            float maxLateMultiplier =
                    lerp(cfg.tier7_maxLateMultiplier_lowEncl, cfg.tier7_maxLateMultiplier_highEncl, enclBlend);
            lateReverbMultiplier =
                    Math.min(cfg.tier7_lateReverbMul + roomFactor * cfg.tier7_lateReverbRoomScale, maxLateMultiplier);
            vGainHF *= cfg.tier7_hfMul;
            vGainLF *= cfg.tier7_lfMul;
        } else if (effectiveVolume > cfg.tier6_volumeThreshold || effectiveMeanDist > cfg.tier6_distThreshold) {
            tierName = "TIER 6 (ARENA / CONCERT HALL)";
            vDecay *= cfg.tier6_decayMul;
            vGain = Math.max(cfg.tier6_minGain, baseEnclosureMultiplier * cfg.tier6_gainMul);
            vReflGain = Math.max(0.0f, reflectionMaterialFactor * cfg.tier6_reflGainMul);
            lateReverbMultiplier = cfg.tier6_lateReverbMul + roomFactor * cfg.tier6_lateReverbRoomScale;
            vGainHF *= cfg.tier6_hfMul;
            vGainLF *= cfg.tier6_lfMul;
        } else if (effectiveVolume > cfg.tier5_volumeThreshold || effectiveMeanDist > cfg.tier5_distThreshold) {
            tierName = "TIER 5 (LARGE CLUB / GYMNASIUM)";
            vDecay *= cfg.tier5_decayMul;
            vGain = Math.max(cfg.tier5_minGain, baseEnclosureMultiplier * cfg.tier5_gainMul);
            vReflGain = Math.max(0.0f, reflectionMaterialFactor * cfg.tier5_reflGainMul);
            lateReverbMultiplier = cfg.tier5_lateReverbMul + roomFactor * cfg.tier5_lateReverbRoomScale;
            vGainHF *= cfg.tier5_hfMul;
            vGainLF *= cfg.tier5_lfMul;
        } else if (effectiveVolume > cfg.tier4_volumeThreshold || effectiveMeanDist > cfg.tier4_distThreshold) {
            tierName = "TIER 4 (LARGE ROOM / SMALL HALL)";
            vDecay *= cfg.tier4_decayMul;
            vGain = Math.max(cfg.tier4_minGain, baseEnclosureMultiplier * cfg.tier4_gainMul);
            vReflGain = Math.max(0.0f, reflectionMaterialFactor * cfg.tier4_reflGainMul);
            lateReverbMultiplier = cfg.tier4_lateReverbMul + roomFactor * cfg.tier4_lateReverbRoomScale;
            vGainHF *= cfg.tier4_hfMul;
            vGainLF *= cfg.tier4_lfMul;
        } else if (effectiveVolume > cfg.tier3_volumeThreshold || effectiveMeanDist > cfg.tier3_distThreshold) {
            tierName = "TIER 3 (MEDIUM ROOM / STUDIO)";
            vDecay *= cfg.tier3_decayMul;
            vGain = Math.max(cfg.tier3_minGain, baseEnclosureMultiplier * cfg.tier3_gainMul);
            vReflGain = Math.max(0.0f, reflectionMaterialFactor * cfg.tier3_reflGainMul);
            lateReverbMultiplier = cfg.tier3_lateReverbMul;
            vGainHF *= cfg.tier3_hfMul;
            vGainLF *= cfg.tier3_lfMul;
        } else if (effectiveVolume > cfg.tier2_volumeThreshold || effectiveMeanDist > cfg.tier2_distThreshold) {
            tierName = "TIER 2 (SMALL ROOM)";
            vDecay *= cfg.tier2_decayMul;
            vGain = Math.max(cfg.tier2_minGain, baseEnclosureMultiplier * cfg.tier2_gainMul);
            vReflGain = Math.max(0.0f, reflectionMaterialFactor * cfg.tier2_reflGainMul);
            lateReverbMultiplier = cfg.tier2_lateReverbMul;
            vGainHF *= cfg.tier2_hfMul;
            vGainLF *= cfg.tier2_lfMul;
        } else {
            tierName = "TIER 1 (TINY SPACE / CLOSET)";
            vDecay *= cfg.tier1_decayMul;
            vGain = Math.max(cfg.tier1_minGain, baseEnclosureMultiplier * cfg.tier1_gainMul);
            vReflGain = Math.max(0.0f, reflectionMaterialFactor * cfg.tier1_reflGainMul);
            lateReverbMultiplier = cfg.tier1_lateReverbMul;
            vGainHF *= cfg.tier1_hfMul;
            vGainLF *= cfg.tier1_lfMul;
        }

        int opennessPct = (int) Math.min(100, vOpenness * 200.0f);
        if (vOpenness > 0.25f) {
            tierName += String.format(" [OPEN AIR: %d%%]", opennessPct);
        } else if (opennessPct > 2) {
            tierName += String.format(" [SEMI-OPEN: %d%%]", opennessPct);
        }

        float tailRetention = (float) Math.pow(effectiveEnclosure, cfg.openAir_enclosure_penalty_exponent);
        float openAirTailMultiplier =
                cfg.openAir_dynamic_lateReverbMul + tailRetention * (1.0f - cfg.openAir_dynamic_lateReverbMul);
        lateReverbMultiplier *= openAirTailMultiplier;
        vGainHF *= cfg.openAir_dynamic_hfMul;
        vGainLF *= cfg.openAir_dynamic_lfMul;

        float openAirGainMultiplier = lerp(1.0f, cfg.openAir_dynamic_gainMul, openAirBlend);
        vGain *= openAirGainMultiplier;

        float openAirReflMultiplier = lerp(1.0f, cfg.openAir_dynamic_reflGainMul, openAirBlend);
        vReflGain *= openAirReflMultiplier;

        if (vMeanDist > 10.0f) {
            float soften = tier7 ? 0.15f : 0.30f;
            vReflGain *= 1.0f - roomFactor * soften;
        }
        vReflDelay = Math.max(0.001f, Math.min(vMeanDist * 2.0f / 4000.0f, 0.3f));
        vLateGain = vReflGain * lateReverbMultiplier;
        vLateDelay = Math.min(vReflDelay + 0.02f, 0.1f);
        vEchoTime = Math.max(0.075f, Math.min(vMeanDist * 2.0f / 343.0f, 0.25f));
        vEchoDepth = distantEchoDepthForTier(tierName);

        float vDensity = (0.7f + effectiveEnclosure * 0.3f) - roomFactor * 0.15f;
        vDensity = Math.max(0.4f, Math.min(1.0f, vDensity));
        float vDiffusion = 0.3f + descriptor.diffusion * 0.7f;

        float tierDensity = -1.0f;
        float tierDiffusion = -1.0f;
        if (tierName.contains("TIER 10")) {
            tierDensity = cfg.tier10_density;
            tierDiffusion = cfg.tier10_diffusion;
        } else if (tierName.contains("TIER 9")) {
            tierDensity = cfg.tier9_density;
            tierDiffusion = cfg.tier9_diffusion;
        } else if (tierName.contains("TIER 8")) {
            tierDensity = cfg.tier8_density;
            tierDiffusion = cfg.tier8_diffusion;
        } else if (tierName.contains("TIER 7")) {
            tierDensity = cfg.tier7_density;
            tierDiffusion = cfg.tier7_diffusion;
        } else if (tierName.contains("TIER 6")) {
            tierDensity = cfg.tier6_density;
            tierDiffusion = cfg.tier6_diffusion;
        } else if (tierName.contains("TIER 5")) {
            tierDensity = cfg.tier5_density;
            tierDiffusion = cfg.tier5_diffusion;
        } else if (tierName.contains("TIER 4")) {
            tierDensity = cfg.tier4_density;
            tierDiffusion = cfg.tier4_diffusion;
        } else if (tierName.contains("TIER 3")) {
            tierDensity = cfg.tier3_density;
            tierDiffusion = cfg.tier3_diffusion;
        } else if (tierName.contains("TIER 2")) {
            tierDensity = cfg.tier2_density;
            tierDiffusion = cfg.tier2_diffusion;
        } else if (tierName.contains("TIER 1 ")) {
            tierDensity = cfg.tier1_density;
            tierDiffusion = cfg.tier1_diffusion;
        }

        if (tierDensity >= 0.0f) {
            vDensity = tierDensity;
        }
        if (tierDiffusion >= 0.0f) {
            vDiffusion = tierDiffusion;
        }

        float hfRatio = 0.80f - vAvgAbsorption * 0.45f - (1.0f - effectiveEnclosure) * 0.15f;
        float vHFRatio = Math.max(0.20f, Math.min(hfRatio, 0.80f));

        float volumeScale = Math.min(vVolume / 30000.0f, 1.0f);
        float openAirPenaltyLF = (1.0f - effectiveEnclosure) * 0.15f;
        float lfRatio = 1.20f - volumeScale * 0.15f - openAirPenaltyLF;
        float vLFRatio = Math.max(0.80f, Math.min(lfRatio, 1.20f));

        boolean vHFLimit = vAvgAbsorption < 0.2f;
        float vAirAbs = vMeanDist > 15.0f ? 0.95f : 0.994f;

        return new AdvancedAcousticScanner.VenuePreset(
                vDecay,
                vGain,
                vGainHF,
                vGainLF,
                vReflGain,
                vReflDelay,
                vLateGain,
                vLateDelay,
                vEchoTime,
                vEchoDepth,
                vDensity,
                vDiffusion,
                vHFRatio,
                vLFRatio,
                vAirAbs,
                vHFLimit,
                effectiveEnclosure,
                probePos,
                tierName);
    }

    private static float distantEchoDepthForTier(String tierName) {
        if (tierName.contains("TIER 10")) return 0.32f;
        if (tierName.contains("TIER 9")) return 0.28f;
        if (tierName.contains("TIER 8")) return 0.24f;
        if (tierName.contains("TIER 7")) return 0.18f;
        if (tierName.contains("TIER 6")) return 0.10f;
        if (tierName.contains("TIER 5")) return 0.04f;
        return 0.0f;
    }

    private static float lerp(float a, float b, float t) {
        return a + (b - a) * Math.max(0.0f, Math.min(1.0f, t));
    }
}
