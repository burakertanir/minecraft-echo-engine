package com.audiophilecraft.sound;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.util.math.Vec3d;

/** Maps virtual emitter-group profiles onto the available physical room buses. */
final class ReverbBusAllocator {
    private static final double SIMILAR_PROFILE_THRESHOLD = 0.30;

    private final AudioEffectsController effects;

    ReverbBusAllocator(AudioEffectsController effects) {
        this.effects = effects;
    }

    void allocate(Collection<PlaybackSession> sessions, Vec3d listenerPosition) {
        List<Candidate> candidates = collectCandidates(sessions, listenerPosition);
        if (candidates.isEmpty()) return;

        candidates.sort(Comparator.comparingDouble(Candidate::score)
                .reversed()
                .thenComparingDouble(candidate -> candidate.group().center().x)
                .thenComparingDouble(candidate -> candidate.group().center().y)
                .thenComparingDouble(candidate -> candidate.group().center().z));
        AcousticProfile primaryProfile = candidates.get(0).profile();
        AcousticProfile secondaryProfile = findSecondaryProfile(candidates, primaryProfile);
        if (!effects.isRoomBusAvailable(1)) secondaryProfile = null;

        effects.assignRoomBusProfile(0, primaryProfile);
        effects.assignRoomBusProfile(1, secondaryProfile != null ? secondaryProfile : primaryProfile);

        for (Candidate candidate : candidates) {
            int busIndex = selectBus(candidate.profile(), primaryProfile, secondaryProfile);
            candidate.group().assignRoomBus(busIndex);
        }
    }

    private List<Candidate> collectCandidates(Collection<PlaybackSession> sessions, Vec3d listenerPosition) {
        Map<EmitterGroup, Integer> sourceCounts = new IdentityHashMap<>();
        for (PlaybackSession session : sessions) {
            for (StreamSource source : session.getStreamSources()) {
                if (source.isValid && source.getEmitterGroup() != null) {
                    sourceCounts.merge(source.getEmitterGroup(), 1, Integer::sum);
                }
            }
        }

        Vec3d listener = listenerPosition != null ? listenerPosition : Vec3d.ZERO;
        List<Candidate> candidates = new ArrayList<>();
        for (Map.Entry<EmitterGroup, Integer> entry : sourceCounts.entrySet()) {
            EmitterGroup group = entry.getKey();
            AcousticProfile profile = group.acousticProfile();
            if (profile == null) continue;

            double distance = Math.sqrt(group.center().squaredDistanceTo(listener));
            double score = Math.sqrt(entry.getValue()) / (1.0 + distance / 16.0);
            candidates.add(new Candidate(group, profile, score));
        }
        return candidates;
    }

    private AcousticProfile findSecondaryProfile(List<Candidate> candidates, AcousticProfile primaryProfile) {
        for (int i = 1; i < candidates.size(); i++) {
            AcousticProfile candidateProfile = candidates.get(i).profile();
            if (!areSimilar(primaryProfile, candidateProfile)) return candidateProfile;
        }
        return null;
    }

    private int selectBus(AcousticProfile profile, AcousticProfile primaryProfile, AcousticProfile secondaryProfile) {
        if (secondaryProfile == null || areSimilar(profile, primaryProfile)) return 0;
        if (areSimilar(profile, secondaryProfile)) return 1;
        return profileDistance(profile, primaryProfile) <= profileDistance(profile, secondaryProfile) ? 0 : 1;
    }

    static boolean areSimilar(AcousticProfile first, AcousticProfile second) {
        return profileDistance(first, second) <= SIMILAR_PROFILE_THRESHOLD;
    }

    static double profileDistance(AcousticProfile first, AcousticProfile second) {
        AdvancedAcousticScanner.VenueDescriptor a = first.descriptor();
        AdvancedAcousticScanner.VenueDescriptor b = second.descriptor();

        double distance = 0.20 * Math.abs(a.enclosure - b.enclosure);
        distance += 0.15 * Math.abs(a.openness - b.openness);
        distance += 0.10 * Math.abs(a.avgAbsorption - b.avgAbsorption);
        distance += 0.10 * Math.abs(a.diffusion - b.diffusion);
        distance += 0.15 * normalizedLogDifference(a.scale, b.scale, 4.0);
        distance += 0.15 * normalizedLogDifference(a.trueVolume, b.trueVolume, 16.0);
        distance += 0.15 * normalizedLogDifference(first.preset().decayTime, second.preset().decayTime, 4.0);
        return distance;
    }

    private static double normalizedLogDifference(double first, double second, double fullScaleRatio) {
        double ratio = (Math.max(0.0, first) + 1.0) / (Math.max(0.0, second) + 1.0);
        double difference = Math.abs(Math.log(ratio)) / Math.log(fullScaleRatio);
        return Math.min(1.0, difference);
    }

    private record Candidate(EmitterGroup group, AcousticProfile profile, double score) {}
}
