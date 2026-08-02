package com.audiophilecraft.sound;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

/** Resolves physical emitter groups into conservative, geometry-backed acoustic zones. */
final class AcousticZoneResolver {
    private static final double MAX_PROFILE_DISTANCE = 0.42;
    private static final double OPEN_AIR_THRESHOLD = 0.25;
    private static final double ENCLOSED_OPENNESS_THRESHOLD = 0.12;
    private static final double MIN_BOUNDS_OVERLAP = 0.08;
    private static final int MIN_SHARED_VENUE_BLOCKS = 4;
    private static final int MAX_DEBUG_POINTS = 12_000;
    private final Map<EmitterGroup, CachedBounds> boundsCache = new IdentityHashMap<>();

    List<AcousticZone> resolve(Collection<PlaybackSession> sessions) {
        List<EmitterGroup> allGroups = collectGroups(sessions);
        Set<EmitterGroup> currentGroups = Collections.newSetFromMap(new IdentityHashMap<>());
        currentGroups.addAll(allGroups);
        boundsCache.keySet().removeIf(group -> !currentGroups.contains(group));
        for (EmitterGroup group : allGroups) {
            group.assignAcousticZone(EmitterGroup.NO_ACOUSTIC_ZONE);
        }

        List<EmitterGroup> scannedGroups = new ArrayList<>();
        for (EmitterGroup group : allGroups) {
            if (group.acousticScanResult() != null) scannedGroups.add(group);
        }
        scannedGroups.sort(Comparator.comparingLong(AcousticZoneResolver::canonicalSpeakerPosition));
        if (scannedGroups.isEmpty()) return List.of();

        UnionFind unionFind = new UnionFind(scannedGroups.size());
        Map<EmitterGroup, ScanBounds> bounds = new IdentityHashMap<>();
        for (EmitterGroup group : scannedGroups) {
            bounds.put(group, boundsFor(group));
        }

        for (int firstIndex = 0; firstIndex < scannedGroups.size(); firstIndex++) {
            EmitterGroup first = scannedGroups.get(firstIndex);
            for (int secondIndex = firstIndex + 1; secondIndex < scannedGroups.size(); secondIndex++) {
                EmitterGroup second = scannedGroups.get(secondIndex);
                if (shouldShareZone(first, second, bounds.get(first), bounds.get(second))) {
                    unionFind.union(firstIndex, secondIndex);
                }
            }
        }

        Map<Integer, List<EmitterGroup>> components = new HashMap<>();
        for (int index = 0; index < scannedGroups.size(); index++) {
            components
                    .computeIfAbsent(unionFind.find(index), ignored -> new ArrayList<>())
                    .add(scannedGroups.get(index));
        }

        List<AcousticZone> zones = new ArrayList<>(components.size());
        for (List<EmitterGroup> component : components.values()) {
            component.sort(Comparator.comparingLong(AcousticZoneResolver::canonicalSpeakerPosition));
            AcousticZone zone = createZone(component);
            zones.add(zone);
            for (EmitterGroup group : component) {
                group.assignAcousticZone(zone.id());
            }
        }
        zones.sort(Comparator.comparingLong(AcousticZone::id));
        return List.copyOf(zones);
    }

    AcousticZone selectDebugZone(List<AcousticZone> zones, PlaybackSession activeSession, Vec3d listenerPosition) {
        if (zones == null || zones.isEmpty()) return null;
        Vec3d listener = listenerPosition != null ? listenerPosition : Vec3d.ZERO;
        Set<EmitterGroup> activeGroups = Collections.newSetFromMap(new IdentityHashMap<>());
        if (activeSession != null) activeGroups.addAll(activeSession.getEmitterGroups());

        AcousticZone selected = null;
        double selectedDistance = Double.MAX_VALUE;
        for (AcousticZone zone : zones) {
            if (!activeGroups.isEmpty() && !zone.containsAny(activeGroups)) continue;
            double distance = zone.nearestDistanceSquared(listener);
            if (distance < selectedDistance) {
                selected = zone;
                selectedDistance = distance;
            }
        }
        if (selected != null || activeGroups.isEmpty()) {
            return selected != null ? selected : nearestZone(zones, listener);
        }
        return nearestZone(zones, listener);
    }

    private static AcousticZone nearestZone(List<AcousticZone> zones, Vec3d listener) {
        AcousticZone nearest = null;
        double nearestDistance = Double.MAX_VALUE;
        for (AcousticZone zone : zones) {
            double distance = zone.nearestDistanceSquared(listener);
            if (distance < nearestDistance) {
                nearest = zone;
                nearestDistance = distance;
            }
        }
        return nearest;
    }

    private static List<EmitterGroup> collectGroups(Collection<PlaybackSession> sessions) {
        Set<EmitterGroup> uniqueGroups = Collections.newSetFromMap(new IdentityHashMap<>());
        if (sessions != null) {
            for (PlaybackSession session : sessions) {
                uniqueGroups.addAll(session.getEmitterGroups());
            }
        }
        return new ArrayList<>(uniqueGroups);
    }

    private static boolean shouldShareZone(
            EmitterGroup first, EmitterGroup second, ScanBounds firstBounds, ScanBounds secondBounds) {
        AcousticProfile firstProfile = first.acousticProfile();
        AcousticProfile secondProfile = second.acousticProfile();
        if (!profilesAreCompatible(firstProfile, secondProfile)) return false;

        AcousticScanResult firstScan = first.acousticScanResult();
        AcousticScanResult secondScan = second.acousticScanResult();
        double centerDistance = Math.sqrt(first.center().squaredDistanceTo(second.center()));
        double acousticScale = Math.max(firstProfile.descriptor().scale, secondProfile.descriptor().scale);
        double closeDistance = Math.max(8.0, Math.min(48.0, acousticScale * 0.75));
        boolean geometryMatches = firstBounds.overlapRatio(secondBounds) >= MIN_BOUNDS_OVERLAP
                || sharedVenueBlockCount(firstScan.venueBlocks(), secondScan.venueBlocks()) >= MIN_SHARED_VENUE_BLOCKS
                || (centerDistance <= closeDistance
                        && (firstBounds.contains(second.center(), 2.0) || secondBounds.contains(first.center(), 2.0)));
        if (!geometryMatches) return false;

        return AcousticRayScanner.hasClearPath(firstScan.probeResult(), first.center(), second.center())
                && AcousticRayScanner.hasClearPath(secondScan.probeResult(), second.center(), first.center());
    }

    private ScanBounds boundsFor(EmitterGroup group) {
        AcousticScanResult scanResult = group.acousticScanResult();
        CachedBounds cached = boundsCache.get(group);
        if (cached != null && cached.scanResult() == scanResult) return cached.bounds();
        ScanBounds bounds = ScanBounds.from(scanResult.pointCloud(), group.center());
        boundsCache.put(group, new CachedBounds(scanResult, bounds));
        return bounds;
    }

    private static boolean profilesAreCompatible(AcousticProfile first, AcousticProfile second) {
        if (first == null || second == null) return false;
        double firstOpenness = first.descriptor().openness;
        double secondOpenness = second.descriptor().openness;
        boolean firstOpenSecondEnclosed =
                firstOpenness >= OPEN_AIR_THRESHOLD && secondOpenness <= ENCLOSED_OPENNESS_THRESHOLD;
        boolean secondOpenFirstEnclosed =
                secondOpenness >= OPEN_AIR_THRESHOLD && firstOpenness <= ENCLOSED_OPENNESS_THRESHOLD;
        if (firstOpenSecondEnclosed || secondOpenFirstEnclosed) return false;
        return ReverbBusAllocator.profileDistance(first, second) <= MAX_PROFILE_DISTANCE;
    }

    private static int sharedVenueBlockCount(Set<BlockPos> first, Set<BlockPos> second) {
        if (first.isEmpty() || second.isEmpty()) return 0;
        Set<BlockPos> smaller = first.size() <= second.size() ? first : second;
        Set<BlockPos> larger = smaller == first ? second : first;
        int shared = 0;
        for (BlockPos position : smaller) {
            if (larger.contains(position) && ++shared >= MIN_SHARED_VENUE_BLOCKS) return shared;
        }
        return shared;
    }

    private static AcousticZone createZone(List<EmitterGroup> groups) {
        long id = canonicalSpeakerPosition(groups.get(0));
        AcousticProfile representativeProfile = selectRepresentativeProfile(groups);
        List<BlockPos> speakers = mergeSpeakerPositions(groups);
        List<Vec3d> pointCloud = mergePointCloud(groups);
        Set<BlockPos> venueBlocks = mergeVenueBlocks(groups);
        AcousticScanResult debugResult = new AcousticScanResult(representativeProfile, pointCloud, venueBlocks);
        return new AcousticZone(id, groups, speakers, debugResult);
    }

    private static AcousticProfile selectRepresentativeProfile(List<EmitterGroup> groups) {
        EmitterGroup selected = groups.get(0);
        double selectedDistance = Double.MAX_VALUE;
        for (EmitterGroup candidate : groups) {
            double totalDistance = 0.0;
            for (EmitterGroup other : groups) {
                totalDistance +=
                        ReverbBusAllocator.profileDistance(candidate.acousticProfile(), other.acousticProfile());
            }
            if (totalDistance < selectedDistance) {
                selected = candidate;
                selectedDistance = totalDistance;
            }
        }
        return selected.acousticProfile();
    }

    private static List<BlockPos> mergeSpeakerPositions(List<EmitterGroup> groups) {
        Set<BlockPos> positions = new LinkedHashSet<>();
        for (EmitterGroup group : groups) positions.addAll(group.speakerPositions());
        List<BlockPos> sorted = new ArrayList<>(positions);
        sorted.sort(Comparator.comparingLong(BlockPos::asLong));
        return List.copyOf(sorted);
    }

    private static Set<BlockPos> mergeVenueBlocks(List<EmitterGroup> groups) {
        Set<BlockPos> blocks = new HashSet<>();
        for (EmitterGroup group : groups)
            blocks.addAll(group.acousticScanResult().venueBlocks());
        return Set.copyOf(blocks);
    }

    private static List<Vec3d> mergePointCloud(List<EmitterGroup> groups) {
        int totalPointCount = 0;
        for (EmitterGroup group : groups) {
            totalPointCount += group.acousticScanResult().pointCloud().size();
        }
        if (totalPointCount <= MAX_DEBUG_POINTS) {
            List<Vec3d> points = new ArrayList<>(totalPointCount);
            for (EmitterGroup group : groups)
                points.addAll(group.acousticScanResult().pointCloud());
            return List.copyOf(points);
        }

        List<Vec3d> points = new ArrayList<>(MAX_DEBUG_POINTS);
        int quota = Math.max(1, MAX_DEBUG_POINTS / groups.size());
        for (EmitterGroup group : groups) {
            List<Vec3d> groupPoints = group.acousticScanResult().pointCloud();
            int stride = Math.max(1, (int) Math.ceil((double) groupPoints.size() / quota));
            for (int index = 0; index < groupPoints.size() && points.size() < MAX_DEBUG_POINTS; index += stride) {
                points.add(groupPoints.get(index));
            }
        }
        return List.copyOf(points);
    }

    private static long canonicalSpeakerPosition(EmitterGroup group) {
        long canonical = Long.MAX_VALUE;
        for (BlockPos position : group.speakerPositions()) {
            canonical = Math.min(canonical, position.asLong());
        }
        return canonical;
    }

    static final class AcousticZone {
        private final long id;
        private final List<EmitterGroup> groups;
        private final List<BlockPos> speakerPositions;
        private final AcousticScanResult debugResult;

        private AcousticZone(
                long id, List<EmitterGroup> groups, List<BlockPos> speakerPositions, AcousticScanResult debugResult) {
            this.id = id;
            this.groups = List.copyOf(groups);
            this.speakerPositions = List.copyOf(speakerPositions);
            this.debugResult = debugResult;
        }

        long id() {
            return id;
        }

        List<BlockPos> speakerPositions() {
            return speakerPositions;
        }

        AcousticScanResult debugResult() {
            return debugResult;
        }

        boolean containsAny(Set<EmitterGroup> candidates) {
            for (EmitterGroup group : groups) {
                if (candidates.contains(group)) return true;
            }
            return false;
        }

        double nearestDistanceSquared(Vec3d position) {
            double nearest = Double.MAX_VALUE;
            for (EmitterGroup group : groups) {
                nearest = Math.min(nearest, group.center().squaredDistanceTo(position));
            }
            return nearest;
        }
    }

    private record ScanBounds(double minX, double minY, double minZ, double maxX, double maxY, double maxZ) {
        static ScanBounds from(List<Vec3d> points, Vec3d fallbackCenter) {
            if (points == null || points.isEmpty()) {
                return new ScanBounds(
                        fallbackCenter.x,
                        fallbackCenter.y,
                        fallbackCenter.z,
                        fallbackCenter.x,
                        fallbackCenter.y,
                        fallbackCenter.z);
            }

            double[] xValues = new double[points.size()];
            double[] yValues = new double[points.size()];
            double[] zValues = new double[points.size()];
            for (int index = 0; index < points.size(); index++) {
                Vec3d point = points.get(index);
                xValues[index] = point.x;
                yValues[index] = point.y;
                zValues[index] = point.z;
            }
            Arrays.sort(xValues);
            Arrays.sort(yValues);
            Arrays.sort(zValues);
            int lowerIndex = Math.min(points.size() - 1, (int) Math.floor(points.size() * 0.05));
            int upperIndex = Math.max(lowerIndex, (int) Math.ceil(points.size() * 0.95) - 1);
            return new ScanBounds(
                    xValues[lowerIndex],
                    yValues[lowerIndex],
                    zValues[lowerIndex],
                    xValues[upperIndex],
                    yValues[upperIndex],
                    zValues[upperIndex]);
        }

        double overlapRatio(ScanBounds other) {
            double overlapX = Math.max(0.0, Math.min(maxX, other.maxX) - Math.max(minX, other.minX));
            double overlapY = Math.max(0.0, Math.min(maxY, other.maxY) - Math.max(minY, other.minY));
            double overlapZ = Math.max(0.0, Math.min(maxZ, other.maxZ) - Math.max(minZ, other.minZ));
            double overlapVolume = overlapX * overlapY * overlapZ;
            double smallerVolume = Math.min(volume(), other.volume());
            return smallerVolume <= 0.0 ? 0.0 : overlapVolume / smallerVolume;
        }

        boolean contains(Vec3d point, double padding) {
            return point.x >= minX - padding
                    && point.x <= maxX + padding
                    && point.y >= minY - padding
                    && point.y <= maxY + padding
                    && point.z >= minZ - padding
                    && point.z <= maxZ + padding;
        }

        private double volume() {
            return Math.max(1.0, maxX - minX) * Math.max(1.0, maxY - minY) * Math.max(1.0, maxZ - minZ);
        }
    }

    private record CachedBounds(AcousticScanResult scanResult, ScanBounds bounds) {}

    private static final class UnionFind {
        private final int[] parent;
        private final byte[] rank;

        private UnionFind(int size) {
            parent = new int[size];
            rank = new byte[size];
            for (int index = 0; index < size; index++) parent[index] = index;
        }

        private int find(int value) {
            if (parent[value] != value) parent[value] = find(parent[value]);
            return parent[value];
        }

        private void union(int first, int second) {
            int firstRoot = find(first);
            int secondRoot = find(second);
            if (firstRoot == secondRoot) return;
            if (rank[firstRoot] < rank[secondRoot]) {
                parent[firstRoot] = secondRoot;
            } else if (rank[firstRoot] > rank[secondRoot]) {
                parent[secondRoot] = firstRoot;
            } else {
                parent[secondRoot] = firstRoot;
                rank[firstRoot]++;
            }
        }
    }
}
