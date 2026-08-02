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
    void includesTheExactDistanceBoundary() {
        BlockPos first = new BlockPos(0, 0, 0);
        BlockPos second = new BlockPos(2, 2, 0);

        List<List<BlockPos>> clusters = SpeakerClusterer.clusterSpeakers(List.of(first, second));

        assertEquals(1, clusters.size());
        assertEquals(List.of(first, second), clusters.get(0));
    }

    @Test
    void separatesSpeakersBeyondTheDistanceBoundary() {
        BlockPos first = new BlockPos(0, 0, 0);
        BlockPos second = new BlockPos(3, 0, 0);

        List<List<BlockPos>> clusters = SpeakerClusterer.clusterSpeakers(List.of(first, second));

        assertEquals(2, clusters.size());
    }

    @Test
    void mergesClustersConnectedByALaterBridgeSpeaker() {
        BlockPos lower = new BlockPos(0, 0, 0);
        BlockPos upper = new BlockPos(0, 4, 0);
        BlockPos bridge = new BlockPos(1, 2, 0);

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
}
