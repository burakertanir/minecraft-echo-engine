package com.audiophilecraft.sound;

import com.audiophilecraft.config.LiveTuningConfig;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;

/**
 * Calculates one source's spatial response and applies the resulting OpenAL
 * gain, filter and send values.
 */
final class SourceSpatialController {
    private final PlaybackSession session;
    private final OpenALSourceResources openAlResources;
    private final SourceOcclusionTracker occlusionTracker = new SourceOcclusionTracker();
    private final BlockPos position;
    private final String speakerType;
    private final float referenceDistance;
    private final float directionX;
    private final float directionY;
    private final float directionZ;
    private final int speakerCount;
    private final int clusterSize;
    private final EmitterGroup emitterGroup;

    private volatile float smoothedPower;
    private volatile float smoothedInputGain;
    private float smoothedGain;
    private float smoothedDirectPathGain = 1.0f;
    private float targetOcclusion = 1.0f;
    private float currentOcclusion = 1.0f;
    private float pendingEchoSendGain;
    private float pendingEchoHighFrequencyGain = 1.0f;
    private float pendingEchoContribution;
    private boolean pendingEchoSend;

    // Scratch state reused on every update to avoid per-source tick allocations.
    private float distance;
    private float directionalGain;
    private float directionalFocus;
    private double horizontalDot;
    private float effectiveReferenceDistance;
    private float dynamicMaxDistance;
    private float attenuation;
    private float proximityBoost;

    SourceSpatialController(
            PlaybackSession session,
            OpenALSourceResources openAlResources,
            BlockPos position,
            String speakerType,
            float referenceDistance,
            float directionX,
            float directionY,
            float directionZ,
            int speakerCount,
            int clusterSize,
            EmitterGroup emitterGroup,
            float initialPower,
            float initialInputGain) {
        this.session = session;
        this.openAlResources = openAlResources;
        this.position = position;
        this.speakerType = speakerType;
        this.referenceDistance = referenceDistance;
        this.directionX = directionX;
        this.directionY = directionY;
        this.directionZ = directionZ;
        this.speakerCount = speakerCount;
        this.clusterSize = clusterSize;
        this.emitterGroup = emitterGroup;
        this.smoothedPower = initialPower;
        this.smoothedInputGain = initialInputGain;
    }

    void update(
            World world,
            Vec3d listenerPosition,
            double sourceDistance,
            double deltaX,
            double deltaY,
            double deltaZ,
            float power,
            float inputGain) {
        LiveTuningConfig config = LiveTuningConfig.get();
        smoothControls(power, inputGain);

        distance = (float) sourceDistance;
        calculateDirectionality(sourceDistance, deltaX, deltaY, deltaZ, config);

        float smoothingTarget =
                occlusionTracker.update(world, position, listenerPosition, sourceDistance, isSubwoofer(), config);
        targetOcclusion = occlusionTracker.target();
        currentOcclusion = occlusionTracker.current();

        calculateDistanceResponse(config);
        openAlResources.updateSpatialPosition(position, listenerPosition, config.hrtf_yFlatten);
        openAlResources.setRadius(calculateSourceRadius(config));

        float gainOcclusion = calculateGainOcclusion(config);
        applyMainGain(config);
        applyFiltersAndSends(smoothingTarget, gainOcclusion, config);
    }

    float smoothedPower() {
        return smoothedPower;
    }

    float smoothedInputGain() {
        return smoothedInputGain;
    }

    float targetOcclusion() {
        return targetOcclusion;
    }

    float currentOcclusion() {
        return currentOcclusion;
    }

    float pendingEchoContribution() {
        return pendingEchoContribution;
    }

    void applyPendingEchoSend(float normalization) {
        if (!pendingEchoSend) return;

        float normalizedGain = pendingEchoSendGain * Math.max(0.0f, Math.min(1.0f, normalization));
        openAlResources.applyEchoSend(
                AudioEngine.getInstance().getSlapbackAuxSlotId(), normalizedGain, pendingEchoHighFrequencyGain);
    }

    private void smoothControls(float power, float inputGain) {
        smoothedPower += (power - smoothedPower) * 0.04f;
        float targetInputGain = inputGain * session.getMixerGain(speakerType);
        smoothedInputGain += (targetInputGain - smoothedInputGain) * 0.04f;
        if (targetInputGain < 0.001f) {
            smoothedInputGain = 0.0f;
        }
    }

    private void calculateDirectionality(
            double sourceDistance, double deltaX, double deltaY, double deltaZ, LiveTuningConfig config) {
        directionalGain = 1.0f;
        directionalFocus = 1.0f;
        horizontalDot = 1.0;
        if (isSubwoofer()) return;

        double toListenerX = -deltaX;
        double toListenerZ = -deltaZ;
        double horizontalDistance = Math.sqrt(toListenerX * toListenerX + toListenerZ * toListenerZ);
        double horizontalDirectionX = directionX;
        double horizontalDirectionZ = directionZ;
        double directionLength =
                Math.sqrt(horizontalDirectionX * horizontalDirectionX + horizontalDirectionZ * horizontalDirectionZ);

        horizontalDot = 0.0;
        double verticalDot = 0.0;
        if (horizontalDistance > 0.001 && directionLength > 0.001) {
            toListenerX /= horizontalDistance;
            toListenerZ /= horizontalDistance;
            horizontalDirectionX /= directionLength;
            horizontalDirectionZ /= directionLength;
            horizontalDot = horizontalDirectionX * toListenerX + horizontalDirectionZ * toListenerZ;
        }

        if (sourceDistance > 0.001) {
            double listenerVertical = Math.max(-1.0, Math.min(1.0, -deltaY / sourceDistance));
            double listenerPitch = Math.asin(listenerVertical);
            double speakerPitch = Math.asin(Math.max(-1.0, Math.min(1.0, directionY)));
            verticalDot = Math.cos(listenerPitch - speakerPitch);
        }

        double horizontalFactor = (horizontalDot + 1.0) / 2.0;
        double verticalFactor = (verticalDot + 1.0) / 2.0;
        double horizontalExponent;
        double verticalExponent;
        if (isLineArray()) {
            horizontalExponent = config.line_hzExp;
            verticalExponent = config.line_vtExpBase + Math.sqrt(clusterSize) * config.line_vtExpPerSpeaker;
        } else if (isMidRange()) {
            horizontalExponent = config.mid_hzExp;
            verticalExponent = config.mid_vtExp;
        } else {
            horizontalExponent = config.normal_hzExp;
            verticalExponent = config.normal_vtExp;
        }

        double combinedFocus =
                Math.pow(horizontalFactor, horizontalExponent) * Math.pow(verticalFactor, verticalExponent);
        if (horizontalDot >= 0.0) {
            directionalFocus = (float) (combinedFocus + (1.0 - combinedFocus) * 0.40 * horizontalDot);
        } else {
            directionalFocus = (float) combinedFocus;
        }

        if (isLineArray()) {
            directionalGain = calculateTunedAttenuation(config.line_rearGain, (float) combinedFocus);
        } else if (isMidRange()) {
            directionalGain = calculateTunedAttenuation(config.mid_rearGain, (float) combinedFocus);
        } else {
            directionalGain = calculateTunedAttenuation(config.normal_rearGain, (float) combinedFocus);
        }
    }

    private void calculateDistanceResponse(LiveTuningConfig config) {
        float arrayMultiplier = 1.0f + 0.5f * (float) Math.log10(Math.max(1.0, speakerCount));
        effectiveReferenceDistance = referenceDistance;
        float baseMaxDistance = 60.0f;
        if (isSubwoofer()) {
            effectiveReferenceDistance = config.sub_refDist * arrayMultiplier;
            baseMaxDistance = config.sub_baseMaxDist * arrayMultiplier;
        } else if (isMidRange()) {
            effectiveReferenceDistance = config.mid_refDist * arrayMultiplier;
            baseMaxDistance = config.mid_baseMaxDist * arrayMultiplier;
        } else {
            effectiveReferenceDistance = config.line_refDist * arrayMultiplier;
            baseMaxDistance = config.line_baseMaxDist * arrayMultiplier;
        }

        effectiveReferenceDistance *= (float) Math.max(1.0, Math.sqrt(smoothedPower));
        dynamicMaxDistance = baseMaxDistance * (float) Math.max(0.2, Math.sqrt(smoothedPower));

        double rolloffExponent = config.mid_rolloffExponent;
        if (isSubwoofer()) rolloffExponent = config.sub_rolloffExponent;
        if (isLineArray()) rolloffExponent = config.line_rolloffExponent;

        if (distance <= effectiveReferenceDistance) {
            attenuation = 1.0f;
        } else if (distance > dynamicMaxDistance) {
            attenuation = 0.0f;
        } else {
            double inversePower = Math.pow(effectiveReferenceDistance / distance, rolloffExponent);
            double fadeStart = dynamicMaxDistance * config.fadeStartPercent;

            float kneeRatio = Math.max(0.0f, Math.min(2.0f, config.distance_softKneeRatio));
            double kneeEnd = Math.min(effectiveReferenceDistance * (1.0 + kneeRatio), fadeStart);
            if (kneeEnd > effectiveReferenceDistance && distance < kneeEnd) {
                float kneeProgress =
                        (float) ((distance - effectiveReferenceDistance) / (kneeEnd - effectiveReferenceDistance));
                float smoothProgress = kneeProgress * kneeProgress * (3.0f - 2.0f * kneeProgress);
                inversePower = 1.0 + (inversePower - 1.0) * smoothProgress;
            }

            if (distance > fadeStart) {
                double fadeRatio = (distance - fadeStart) / (dynamicMaxDistance - fadeStart);
                inversePower *= 0.5 * (1.0 + Math.cos(fadeRatio * Math.PI));
            }
            attenuation = (float) Math.max(0.0, Math.min(1.0, inversePower));
        }

        proximityBoost = 1.0f;
        if (distance < effectiveReferenceDistance) {
            float proximityFactor = 1.0f - distance / effectiveReferenceDistance;
            proximityFactor *= proximityFactor;
            float maximumBoost = isSubwoofer() ? config.prox_sub_maxBoost : config.prox_other_maxBoost;
            proximityBoost = 1.0f + proximityFactor * maximumBoost;
        }
    }

    private float calculateSourceRadius(LiveTuningConfig config) {
        if (isSubwoofer()) {
            return Math.max(0.5f, (float) Math.sqrt(speakerCount) * config.sourceRadius_sub);
        }
        if (isMidRange()) {
            return Math.max(0.3f, (float) Math.sqrt(speakerCount) * config.sourceRadius_mid);
        }
        return Math.max(0.15f, (float) Math.sqrt(speakerCount) * config.sourceRadius_line);
    }

    private float calculateGainOcclusion(LiveTuningConfig config) {
        if (isSubwoofer()) {
            return calculateTunedAttenuation(config.occ_sub_floor, currentOcclusion);
        }
        if (isMidRange()) {
            return calculateTunedAttenuation(config.occ_mid_floor, currentOcclusion);
        }
        if (isLineArray()) {
            return calculateTunedAttenuation(config.occ_line_floor, currentOcclusion);
        }
        return currentOcclusion;
    }

    private static float calculateTunedAttenuation(float minimumGain, float openness) {
        // The floor is now the final one-pass level; this keeps the old transition shape.
        float finalFloor = Math.max(0.0f, Math.min(1.0f, minimumGain));
        float clampedOpenness = Math.max(0.0f, Math.min(1.0f, openness));
        float curveFloor = (float) Math.sqrt(finalFloor);
        float curveGain = curveFloor + (1.0f - curveFloor) * clampedOpenness;
        return curveGain * curveGain;
    }

    private void applyMainGain(LiveTuningConfig config) {
        float targetGain = smoothedPower * attenuation * proximityBoost;
        targetGain = Math.max(0.0f, Math.min(2.0f, targetGain));

        float gainDelta = targetGain - smoothedGain;
        float gainLerp = config.gain_smoothing;
        float absoluteDelta = Math.abs(gainDelta);
        if (absoluteDelta > 0.60f) {
            gainLerp = 0.85f;
        } else if (absoluteDelta > 0.25f) {
            gainLerp = 0.65f;
        }
        smoothedGain += gainDelta * gainLerp;
        if (smoothedInputGain < 0.001f) {
            smoothedGain = 0.0f;
        }
        openAlResources.setGain(smoothedGain);
    }

    private void applyFiltersAndSends(float smoothingTarget, float gainOcclusion, LiveTuningConfig config) {
        float distanceHighFrequencyGain = calculateDistanceHighFrequencyGain();
        float occlusionHighFrequencyGain = calculateOcclusionHighFrequencyGain(smoothingTarget, config);
        float underwaterHighFrequencyGain = AudioEngine.getInstance().getUnderwaterHFGain();
        float directionHighFrequencyGain = calculateDirectionalHighFrequencyGain(config);
        float airHighFrequencyGain =
                isLineArray() ? (float) Math.pow(0.5, distance / config.hf_air_absorb_halving_dist) : 1.0f;

        float directHighFrequencyGain = distanceHighFrequencyGain
                * occlusionHighFrequencyGain
                * underwaterHighFrequencyGain
                * directionHighFrequencyGain
                * airHighFrequencyGain;
        float directGain = smoothDirectPathGain(directionalGain * gainOcclusion);
        float wetSpatialTransmission = (float) Math.sqrt(directGain);
        directHighFrequencyGain = Math.max(0.01f, directHighFrequencyGain);
        directHighFrequencyGain = Math.min(directHighFrequencyGain, wetSpatialTransmission);
        if (isSubwoofer()) {
            directHighFrequencyGain = Math.min(directHighFrequencyGain, 0.05f);
        }

        float reverbHighFrequencyGain = distanceHighFrequencyGain
                * occlusionHighFrequencyGain
                * underwaterHighFrequencyGain
                * airHighFrequencyGain;
        reverbHighFrequencyGain = Math.max(0.01f, Math.min(1.0f, reverbHighFrequencyGain));
        if (isSubwoofer()) {
            reverbHighFrequencyGain = Math.min(reverbHighFrequencyGain, 0.05f);
        }

        if (openAlResources.hasDirectFilter()) {
            if (AudioEngine.getInstance().isMidMuted()) {
                directGain = 0.0f;
                directHighFrequencyGain = 0.0f;
            }
            openAlResources.applyDirectFilter(directGain, directHighFrequencyGain);
        }

        applyRoomAndEchoSends(reverbHighFrequencyGain, wetSpatialTransmission, config);
    }

    private float calculateDistanceHighFrequencyGain() {
        float gain = 1.0f;
        float nearFieldNoAbsorption = effectiveReferenceDistance * 2.0f;
        float absorptionStart = dynamicMaxDistance * 0.65f;
        if (distance > absorptionStart && distance > nearFieldNoAbsorption) {
            float fadeRatio = (distance - absorptionStart) / (dynamicMaxDistance - absorptionStart);
            fadeRatio = Math.min(1.0f, fadeRatio);
            float smoothRatio = (float) (0.5 * (1.0 - Math.cos(fadeRatio * Math.PI)));
            gain = 1.0f - smoothRatio * 0.60f;
        }
        return Math.max(0.20f, gain);
    }

    private float calculateOcclusionHighFrequencyGain(float smoothingTarget, LiveTuningConfig config) {
        float exponent = config.occ_hfExp_occluding;
        if (smoothingTarget > currentOcclusion) {
            exponent = config.occ_hfExp_deoccluding;
        }
        if (distance < effectiveReferenceDistance * 1.5f) {
            exponent = Math.min(exponent, 1.05f);
        }
        float rawGain = (float) Math.pow(currentOcclusion, exponent);
        return 0.02f + 0.98f * rawGain;
    }

    private float calculateDirectionalHighFrequencyGain(LiveTuningConfig config) {
        if (isSubwoofer()) return 0.05f;

        double frontness = (horizontalDot + 1.0) / 2.0;
        double behindFloor;
        double frontFloor;
        if (isLineArray()) {
            behindFloor = config.hf_line_behindFloor;
            frontFloor = config.hf_line_frontFloor;
        } else if (isMidRange()) {
            behindFloor = config.hf_mid_behindFloor;
            frontFloor = config.hf_mid_frontFloor;
        } else {
            behindFloor = config.hf_normal_behindFloor;
            frontFloor = config.hf_normal_frontFloor;
        }
        double floor = behindFloor + (frontFloor - behindFloor) * frontness;
        return (float) (floor + (1.0 - floor) * directionalFocus);
    }

    private float smoothDirectPathGain(float targetGain) {
        float clampedTarget = Math.max(0.0f, Math.min(1.0f, targetGain));
        float delta = clampedTarget - smoothedDirectPathGain;
        float lerp = 0.40f;
        float absoluteDelta = Math.abs(delta);
        if (absoluteDelta > 0.60f) {
            lerp = 0.85f;
        } else if (absoluteDelta > 0.25f) {
            lerp = 0.65f;
        }
        smoothedDirectPathGain += delta * lerp;
        if (smoothedInputGain < 0.001f) {
            smoothedDirectPathGain = 0.0f;
        }
        return smoothedDirectPathGain;
    }

    private void applyRoomAndEchoSends(
            float reverbHighFrequencyGain, float wetSpatialTransmission, LiveTuningConfig config) {
        AudioEngine engine = AudioEngine.getInstance();
        pendingEchoSendGain = 0.0f;
        pendingEchoHighFrequencyGain = 1.0f;
        pendingEchoContribution = 0.0f;
        pendingEchoSend = false;
        if (!openAlResources.hasRoomSendFilter() || engine.getAuxSlotId() == 0) return;

        float reverbOcclusion = Math.max(0.15f, currentOcclusion);
        float baseReverbVolume = (config.reverb_send_near + config.reverb_send_far) * 0.5f;
        float powerScaledSend = baseReverbVolume * smoothedPower;

        float rangeFade = 1.0f;
        float fadeStart = dynamicMaxDistance * config.fadeStartPercent;
        if (distance >= dynamicMaxDistance || attenuation <= 0.0f) {
            rangeFade = 0.0f;
        } else if (distance > fadeStart) {
            float fadeRatio = (distance - fadeStart) / (dynamicMaxDistance - fadeStart);
            rangeFade = 0.5f * (1.0f + (float) Math.cos(fadeRatio * Math.PI));
        }

        float softDistanceFalloff = attenuation > 0.0f ? (float) Math.pow(attenuation, 0.15f) * rangeFade : 0.0f;
        float sendGain = reverbOcclusion * powerScaledSend * softDistanceFalloff;
        float wetFloor = 0.04f * reverbOcclusion * rangeFade;
        if (sendGain < wetFloor) sendGain = wetFloor;
        if (sendGain > 0.60f) sendGain = 0.60f;
        sendGain *= wetSpatialTransmission;
        if (engine.isSideMuted()) sendGain = 0.0f;

        float roomSendGain = sendGain / (float) Math.max(1.0, Math.sqrt(clusterSize));
        if (emitterGroup != null) {
            roomSendGain *= emitterGroup.roomSendGain();
        }
        openAlResources.applyRoomSend(engine.getAuxSlotId(emitterGroup), roomSendGain, reverbHighFrequencyGain);

        if (engine.getSlapbackAuxSlotId() != 0 && openAlResources.hasEchoSendFilter()) {
            float echoDistanceFalloff = (float) Math.pow(Math.max(0.001f, attenuation), 0.3f);
            pendingEchoSendGain = sendGain * engine.getSlapbackGain() * echoDistanceFalloff;
            pendingEchoHighFrequencyGain = reverbHighFrequencyGain * Math.max(0.01f, 1.0f - config.echo_damping);
            pendingEchoContribution =
                    pendingEchoSendGain * Math.max(0.0f, smoothedGain) * Math.max(0.0f, smoothedInputGain);
            pendingEchoSend = true;
        }
    }

    private boolean isSubwoofer() {
        return AudioEngine.TYPE_SUB.equals(speakerType);
    }

    private boolean isMidRange() {
        return AudioEngine.TYPE_MID.equals(speakerType);
    }

    private boolean isLineArray() {
        return AudioEngine.TYPE_LINE.equals(speakerType);
    }
}
