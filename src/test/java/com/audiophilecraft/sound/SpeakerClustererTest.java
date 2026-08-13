package com.audiophilecraft.sound;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.util.math.BlockPos;
import org.junit.jupiter.api.Test;

class SpeakerClustererTest {
    @Test
    void emptyInputProducesNoClusters() {
        assertTrue(SpeakerClusterer.clusterSpeakers(List.of()).isEmpty());
    }

    @Test
    void includesFaceAdjacentSpeakersInOneCluster() {
        BlockPos first = new BlockPos(0, 0, 0);
        BlockPos second = new BlockPos(1, 0, 0);

        List<List<BlockPos>> clusters = SpeakerClusterer.clusterSpeakers(List.of(first, second));

        assertEquals(1, clusters.size());
        assertEquals(List.of(first, second), clusters.get(0));
    }

    @Test
    void includesCornerAdjacentSpeakersInOneCluster() {
        BlockPos first = new BlockPos(0, 0, 0);
        BlockPos second = new BlockPos(1, 1, 1);

        List<List<BlockPos>> clusters = SpeakerClusterer.clusterSpeakers(List.of(first, second));

        assertEquals(1, clusters.size());
        assertEquals(List.of(first, second), clusters.get(0));
    }

    @Test
    void separatesSpeakersWithAnEmptyBlockBetweenThem() {
        BlockPos first = new BlockPos(0, 0, 0);
        BlockPos second = new BlockPos(2, 0, 0);

        List<List<BlockPos>> clusters = SpeakerClusterer.clusterSpeakers(List.of(first, second));

        assertEquals(2, clusters.size());
    }

    @Test
    void mergesClustersConnectedByATouchingBridgeSpeaker() {
        BlockPos lower = new BlockPos(0, 0, 0);
        BlockPos upper = new BlockPos(0, 0, 2);
        BlockPos bridge = new BlockPos(1, 0, 1);

        List<List<BlockPos>> clusters = SpeakerClusterer.clusterSpeakers(List.of(bridge, upper, lower));

        assertEquals(1, clusters.size());
        assertEquals(3, clusters.get(0).size());
        assertTrue(clusters.get(0).containsAll(List.of(lower, upper, bridge)));
    }

    @Test
    void outputIsDeterministicAndInputIsNotMutated() {
        List<BlockPos> speakers =
                new ArrayList<>(List.of(new BlockPos(10, 0, 0), new BlockPos(0, 0, 0), new BlockPos(1, 0, 0)));
        List<BlockPos> originalOrder = List.copyOf(speakers);

        List<List<BlockPos>> firstResult = SpeakerClusterer.clusterSpeakers(speakers);
        List<List<BlockPos>> secondResult = SpeakerClusterer.clusterSpeakers(
                List.of(new BlockPos(1, 0, 0), new BlockPos(10, 0, 0), new BlockPos(0, 0, 0)));

        assertEquals(originalOrder, speakers);
        assertEquals(firstResult, secondResult);
    }

    @Test
    void singleSpeakerProducesOneSingleMemberCluster() {
        BlockPos speaker = new BlockPos(-5, 12, 30);

        assertEquals(List.of(List.of(speaker)), SpeakerClusterer.clusterSpeakers(List.of(speaker)));
    }

    @Test
    void longTouchingChainDoesNotSplitByEndpointDistance() {
        List<BlockPos> chain =
                List.of(new BlockPos(0, 0, 0), new BlockPos(1, 0, 0), new BlockPos(2, 0, 0), new BlockPos(3, 0, 0));

        List<List<BlockPos>> clusters = SpeakerClusterer.clusterSpeakers(chain);

        assertEquals(1, clusters.size());
        assertEquals(4, clusters.get(0).size());
        assertTrue(clusters.get(0).containsAll(chain));
    }

    @Test
    void negativeCoordinatesUseTheSameTouchingRule() {
        BlockPos first = new BlockPos(-10, -4, -3);
        BlockPos second = new BlockPos(-8, -2, -3);

        assertEquals(2, SpeakerClusterer.clusterSpeakers(List.of(first, second)).size());
    }
}
