package com.audiophilecraft.sound;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.util.math.Vec3d;

/** Maps virtual emitter-group profiles onto the available physical room buses. */
final class ReverbBusAllocator {
    private static final double SIMILAR_PROFILE_THRESHOLD = 0.30;
    private static final double REPLACEMENT_SCORE_ADVANTAGE = 1.25;
    private static final double OPEN_AIR_THRESHOLD = 0.25;
    private static final double ENCLOSED_OPENNESS_THRESHOLD = 0.12;
    private static final long EVALUATION_INTERVAL_NANOS = 250_000_000L;
    private static final long CANDIDATE_STABILITY_NANOS = 750_000_000L;
    private static final long MINIMUM_BUS_HOLD_NANOS = 2_500_000_000L;
    private static final float FADE_OUT_SECONDS = 1.5f;
    private static final float FADE_IN_SECONDS = 2.0f;

    private final AudioEffectsController effects;
    private final AcousticProfile[] busProfiles = new AcousticProfile[2];

    private long lastUpdateNanos;
    private long lastEvaluationNanos;
    private long lastCompletedChangeNanos;
    private PendingReplacement pendingReplacement;
    private BusTransition transition;

    ReverbBusAllocator(AudioEffectsController effects) {
        this.effects = effects;
    }

    void allocate(Collection<PlaybackSession> sessions, Vec3d listenerPosition) {
        long nowNanos = System.nanoTime();
        List<Candidate> candidates = collectCandidates(sessions, listenerPosition);
        if (candidates.isEmpty()) return;

        if (busProfiles[0] == null) {
            initializeAssignments(candidates, nowNanos);
            return;
        }

        // A new playback pipeline may have refreshed the shared EFX state. Reapply
        // the allocator's stable profiles without changing any source routing.
        effects.assignRoomBusProfile(0, busProfiles[0]);
        effects.assignRoomBusProfile(1, busProfiles[1] != null ? busProfiles[1] : busProfiles[0]);
        if (transition == null) assignGroups(candidates, busProfiles[0], busProfiles[1]);
        lastEvaluationNanos = 0L;
    }

    void update(Collection<PlaybackSession> sessions, Vec3d listenerPosition) {
        long nowNanos = System.nanoTime();
        List<Candidate> candidates = collectCandidates(sessions, listenerPosition);

        float deltaSeconds = lastUpdateNanos == 0L
                ? 0.05f
                : Math.min(0.1f, Math.max(0.0f, (nowNanos - lastUpdateNanos) / 1_000_000_000.0f));
        lastUpdateNanos = nowNanos;

        Set<EmitterGroup> groupsToUpdate = newIdentitySet();
        for (Candidate candidate : candidates) groupsToUpdate.add(candidate.group());
        if (transition != null) groupsToUpdate.addAll(transition.affectedGroups);
        for (EmitterGroup group : groupsToUpdate) {
            group.updateRoomSendGain(deltaSeconds, FADE_OUT_SECONDS, FADE_IN_SECONDS);
        }

        if (candidates.isEmpty()) {
            pendingReplacement = null;
            return;
        }
        if (busProfiles[0] == null) {
            initializeAssignments(candidates, nowNanos);
            return;
        }

        if (transition != null) {
            advanceTransition(candidates, deltaSeconds, nowNanos);
            return;
        }

        assignGroups(candidates, busProfiles[0], busProfiles[1]);
        if (nowNanos - lastEvaluationNanos >= EVALUATION_INTERVAL_NANOS) {
            lastEvaluationNanos = nowNanos;
            evaluateReplacement(candidates, nowNanos);
        }
    }

    void reset() {
        busProfiles[0] = null;
        busProfiles[1] = null;
        pendingReplacement = null;
        transition = null;
        lastUpdateNanos = 0L;
        lastEvaluationNanos = 0L;
        lastCompletedChangeNanos = 0L;
        effects.setRoomBusMixGain(0, 1.0f);
        effects.setRoomBusMixGain(1, 1.0f);
    }

    private void initializeAssignments(List<Candidate> candidates, long nowNanos) {
        List<ProfileCandidate> rankedProfiles = rankProfiles(candidates);
        AcousticProfile primaryProfile = rankedProfiles.get(0).profile;
        AcousticProfile secondaryProfile = rankedProfiles.size() > 1 && effects.isRoomBusAvailable(1)
                ? rankedProfiles.get(1).profile
                : primaryProfile;

        busProfiles[0] = primaryProfile;
        busProfiles[1] = secondaryProfile;
        effects.assignRoomBusProfile(0, primaryProfile);
        effects.assignRoomBusProfile(1, secondaryProfile);
        effects.setRoomBusMixGain(0, 1.0f);
        effects.setRoomBusMixGain(1, 1.0f);
        assignGroups(candidates, primaryProfile, secondaryProfile);
        for (Candidate candidate : candidates) candidate.group().setRoomSendTarget(1.0f);
        lastCompletedChangeNanos = nowNanos;
        lastUpdateNanos = nowNanos;
    }

    private void evaluateReplacement(List<Candidate> candidates, long nowNanos) {
        if (!effects.isRoomBusAvailable(1) || nowNanos - lastCompletedChangeNanos < MINIMUM_BUS_HOLD_NANOS) {
            pendingReplacement = null;
            return;
        }

        List<ProfileCandidate> rankedProfiles = rankProfiles(candidates);
        ProfileCandidate missingProfile = null;
        int desiredCount = Math.min(2, rankedProfiles.size());
        for (int i = 0; i < desiredCount; i++) {
            ProfileCandidate candidate = rankedProfiles.get(i);
            if (!isRepresented(candidate.profile)) {
                missingProfile = candidate;
                break;
            }
        }
        if (missingProfile == null) {
            pendingReplacement = null;
            return;
        }

        int busToReplace = selectReplacementBus(rankedProfiles);
        double residentScore = scoreForProfile(rankedProfiles, busProfiles[busToReplace]);
        if (residentScore > 0.0 && missingProfile.score < residentScore * REPLACEMENT_SCORE_ADVANTAGE) {
            pendingReplacement = null;
            return;
        }

        if (pendingReplacement == null
                || pendingReplacement.busIndex != busToReplace
                || !areSimilar(pendingReplacement.targetProfile, missingProfile.profile)) {
            pendingReplacement = new PendingReplacement(busToReplace, missingProfile.profile, nowNanos);
            return;
        }

        pendingReplacement.targetProfile = missingProfile.profile;
        if (nowNanos - pendingReplacement.stableSinceNanos >= CANDIDATE_STABILITY_NANOS) {
            startTransition(candidates, pendingReplacement.busIndex, pendingReplacement.targetProfile);
            pendingReplacement = null;
        }
    }

    private boolean isRepresented(AcousticProfile profile) {
        return areSimilar(profile, busProfiles[0]) || areSimilar(profile, busProfiles[1]);
    }

    private int selectReplacementBus(List<ProfileCandidate> desiredProfiles) {
        if (areSimilar(busProfiles[0], busProfiles[1])) return 1;

        boolean keepPrimary = matchesDesiredProfile(busProfiles[0], desiredProfiles);
        boolean keepSecondary = matchesDesiredProfile(busProfiles[1], desiredProfiles);
        if (keepPrimary != keepSecondary) return keepPrimary ? 1 : 0;

        double primaryScore = scoreForProfile(desiredProfiles, busProfiles[0]);
        double secondaryScore = scoreForProfile(desiredProfiles, busProfiles[1]);
        return primaryScore <= secondaryScore ? 0 : 1;
    }

    private boolean matchesDesiredProfile(AcousticProfile profile, List<ProfileCandidate> desiredProfiles) {
        int desiredCount = Math.min(2, desiredProfiles.size());
        for (int i = 0; i < desiredCount; i++) {
            if (areSimilar(profile, desiredProfiles.get(i).profile)) return true;
        }
        return false;
    }

    private double scoreForProfile(List<ProfileCandidate> rankedProfiles, AcousticProfile profile) {
        if (profile == null) return 0.0;
        for (ProfileCandidate candidate : rankedProfiles) {
            if (areSimilar(profile, candidate.profile)) return candidate.score;
        }
        return 0.0;
    }

    private void startTransition(List<Candidate> candidates, int busIndex, AcousticProfile targetProfile) {
        AcousticProfile futurePrimary = busIndex == 0 ? targetProfile : busProfiles[0];
        AcousticProfile futureSecondary = busIndex == 1 ? targetProfile : busProfiles[1];
        Set<EmitterGroup> affectedGroups = newIdentitySet();
        collectAffectedGroups(candidates, busIndex, futurePrimary, futureSecondary, affectedGroups);
        for (EmitterGroup group : affectedGroups) group.setRoomSendTarget(0.0f);
        transition = new BusTransition(busIndex, targetProfile, affectedGroups);
    }

    private void advanceTransition(List<Candidate> candidates, float deltaSeconds, long nowNanos) {
        transition.elapsedSeconds += deltaSeconds;
        if (transition.phase == TransitionPhase.FADING_OUT) {
            AcousticProfile futurePrimary = transition.busIndex == 0 ? transition.targetProfile : busProfiles[0];
            AcousticProfile futureSecondary = transition.busIndex == 1 ? transition.targetProfile : busProfiles[1];
            collectAffectedGroups(
                    candidates, transition.busIndex, futurePrimary, futureSecondary, transition.affectedGroups);
            for (EmitterGroup group : transition.affectedGroups) group.setRoomSendTarget(0.0f);

            float progress = Math.min(1.0f, transition.elapsedSeconds / FADE_OUT_SECONDS);
            effects.setRoomBusMixGain(transition.busIndex, 1.0f - smoothStep(progress));
            if (progress >= 1.0f && affectedGroupsAreSilent()) {
                busProfiles[transition.busIndex] = transition.targetProfile;
                effects.assignRoomBusProfile(transition.busIndex, transition.targetProfile);
                assignGroups(candidates, busProfiles[0], busProfiles[1]);
                for (EmitterGroup group : transition.affectedGroups) group.setRoomSendTarget(1.0f);
                transition.phase = TransitionPhase.FADING_IN;
                transition.elapsedSeconds = 0.0f;
            }
            return;
        }

        assignGroups(candidates, busProfiles[0], busProfiles[1]);
        float progress = Math.min(1.0f, transition.elapsedSeconds / FADE_IN_SECONDS);
        effects.setRoomBusMixGain(transition.busIndex, smoothStep(progress));
        if (progress >= 1.0f) {
            effects.setRoomBusMixGain(transition.busIndex, 1.0f);
            for (EmitterGroup group : transition.affectedGroups) group.setRoomSendTarget(1.0f);
            transition = null;
            lastCompletedChangeNanos = nowNanos;
        }
    }

    private boolean affectedGroupsAreSilent() {
        for (EmitterGroup group : transition.affectedGroups) {
            if (group.roomSendGain() > 0.01f) return false;
        }
        return true;
    }

    private void collectAffectedGroups(
            List<Candidate> candidates,
            int replacedBus,
            AcousticProfile futurePrimary,
            AcousticProfile futureSecondary,
            Set<EmitterGroup> affectedGroups) {
        for (Candidate candidate : candidates) {
            EmitterGroup group = candidate.group();
            int futureBus = selectBus(candidate.profile(), futurePrimary, futureSecondary, group.roomBusIndex());
            if (group.roomBusIndex() == replacedBus || futureBus != group.roomBusIndex()) affectedGroups.add(group);
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
            double sourceWeight = 1.0 + 0.35 * Math.min(1.0, Math.log(Math.max(1, entry.getValue())) / Math.log(16.0));
            double normalizedDistance = distance / 16.0;
            double score = sourceWeight / (1.0 + normalizedDistance * normalizedDistance);
            candidates.add(new Candidate(group, profile, score));
        }
        candidates.sort(candidateComparator());
        return candidates;
    }

    private List<ProfileCandidate> rankProfiles(List<Candidate> candidates) {
        List<ProfileCandidate> profiles = new ArrayList<>();
        for (Candidate candidate : candidates) {
            ProfileCandidate matchingProfile = null;
            for (ProfileCandidate profile : profiles) {
                if (areSimilar(profile.profile, candidate.profile())) {
                    matchingProfile = profile;
                    break;
                }
            }
            if (matchingProfile == null) {
                profiles.add(new ProfileCandidate(candidate.profile(), candidate.score()));
            } else {
                matchingProfile.score += candidate.score();
            }
        }
        profiles.sort(Comparator.comparingDouble((ProfileCandidate candidate) -> candidate.score)
                .reversed());
        return profiles;
    }

    private void assignGroups(
            List<Candidate> candidates, AcousticProfile primaryProfile, AcousticProfile secondaryProfile) {
        for (Candidate candidate : candidates) {
            int busIndex = selectBus(
                    candidate.profile(),
                    primaryProfile,
                    secondaryProfile,
                    candidate.group().roomBusIndex());
            candidate.group().assignRoomBus(busIndex);
        }
    }

    private int selectBus(
            AcousticProfile profile,
            AcousticProfile primaryProfile,
            AcousticProfile secondaryProfile,
            int currentBusIndex) {
        if (secondaryProfile == null || areSimilar(primaryProfile, secondaryProfile)) return 0;
        boolean matchesPrimary = areSimilar(profile, primaryProfile);
        boolean matchesSecondary = areSimilar(profile, secondaryProfile);
        if (matchesPrimary && matchesSecondary) return currentBusIndex;
        if (matchesPrimary) return 0;
        if (matchesSecondary) return 1;
        return profileDistance(profile, primaryProfile) <= profileDistance(profile, secondaryProfile) ? 0 : 1;
    }

    private static Comparator<Candidate> candidateComparator() {
        return Comparator.comparingDouble(Candidate::score)
                .reversed()
                .thenComparingDouble(candidate -> candidate.group().center().x)
                .thenComparingDouble(candidate -> candidate.group().center().y)
                .thenComparingDouble(candidate -> candidate.group().center().z);
    }

    private static float smoothStep(float value) {
        float clamped = Math.max(0.0f, Math.min(1.0f, value));
        return clamped * clamped * (3.0f - 2.0f * clamped);
    }

    private static Set<EmitterGroup> newIdentitySet() {
        return java.util.Collections.newSetFromMap(new IdentityHashMap<>());
    }

    static boolean areSimilar(AcousticProfile first, AcousticProfile second) {
        if (first == null || second == null) return false;
        AdvancedAcousticScanner.VenueDescriptor a = first.descriptor();
        AdvancedAcousticScanner.VenueDescriptor b = second.descriptor();
        boolean firstOpenAndSecondEnclosed =
                a.openness >= OPEN_AIR_THRESHOLD && b.openness <= ENCLOSED_OPENNESS_THRESHOLD;
        boolean secondOpenAndFirstEnclosed =
                b.openness >= OPEN_AIR_THRESHOLD && a.openness <= ENCLOSED_OPENNESS_THRESHOLD;
        if (firstOpenAndSecondEnclosed || secondOpenAndFirstEnclosed) return false;
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

    private static final class ProfileCandidate {
        private final AcousticProfile profile;
        private double score;

        private ProfileCandidate(AcousticProfile profile, double score) {
            this.profile = profile;
            this.score = score;
        }
    }

    private static final class PendingReplacement {
        private final int busIndex;
        private AcousticProfile targetProfile;
        private final long stableSinceNanos;

        private PendingReplacement(int busIndex, AcousticProfile targetProfile, long stableSinceNanos) {
            this.busIndex = busIndex;
            this.targetProfile = targetProfile;
            this.stableSinceNanos = stableSinceNanos;
        }
    }

    private static final class BusTransition {
        private final int busIndex;
        private final AcousticProfile targetProfile;
        private final Set<EmitterGroup> affectedGroups;
        private TransitionPhase phase = TransitionPhase.FADING_OUT;
        private float elapsedSeconds;

        private BusTransition(int busIndex, AcousticProfile targetProfile, Set<EmitterGroup> affectedGroups) {
            this.busIndex = busIndex;
            this.targetProfile = targetProfile;
            this.affectedGroups = affectedGroups;
        }
    }

    private enum TransitionPhase {
        FADING_OUT,
        FADING_IN
    }
}
