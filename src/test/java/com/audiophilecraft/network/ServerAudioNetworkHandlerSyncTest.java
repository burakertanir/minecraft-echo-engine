package com.audiophilecraft.network;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import org.junit.jupiter.api.Test;

class ServerAudioNetworkHandlerSyncTest {
    @Test
    void activeSyncRemainsPendingBeforeTheTimeout() {
        ConcurrentHashMap<UUID, ServerAudioNetworkHandler.PendingSync> pending = new ConcurrentHashMap<>();
        UUID sessionUUID = UUID.randomUUID();
        ServerAudioNetworkHandler.PendingSync state = pendingSync(1_000L, Set.of(UUID.randomUUID()));
        pending.put(sessionUUID, state);

        var due = ServerAudioNetworkHandler.removeDueSyncs(pending, 1_000L + ServerAudioNetworkHandler.SYNC_TIMEOUT_MS);

        assertTrue(due.isEmpty());
        assertSame(state, pending.get(sessionUUID));
    }

    @Test
    void timedOutSyncIsRemovedAndMarkedForCompletion() {
        ConcurrentHashMap<UUID, ServerAudioNetworkHandler.PendingSync> pending = new ConcurrentHashMap<>();
        UUID sessionUUID = UUID.randomUUID();
        ServerAudioNetworkHandler.PendingSync state = pendingSync(1_000L, Set.of(UUID.randomUUID()));
        pending.put(sessionUUID, state);

        var due = ServerAudioNetworkHandler.removeDueSyncs(pending, 1_001L + ServerAudioNetworkHandler.SYNC_TIMEOUT_MS);

        assertTrue(pending.isEmpty());
        assertEquals(1, due.size());
        assertEquals(sessionUUID, due.get(0).sessionUUID());
        assertSame(state, due.get(0).state());
        assertTrue(due.get(0).timedOut());
        assertEquals(
                new Identifier("minecraft", "overworld"), due.get(0).state().dimension());
    }

    @Test
    void syncWithNoRemainingPlayersCompletesWithoutWaitingForTimeout() {
        ConcurrentHashMap<UUID, ServerAudioNetworkHandler.PendingSync> pending = new ConcurrentHashMap<>();
        UUID sessionUUID = UUID.randomUUID();
        pending.put(sessionUUID, pendingSync(10_000L, Set.of()));

        var due = ServerAudioNetworkHandler.removeDueSyncs(pending, 10_000L);

        assertTrue(pending.isEmpty());
        assertEquals(1, due.size());
        assertFalse(due.get(0).timedOut());
    }

    @Test
    void removingDueSyncsHandlesMultipleIndependentSessions() {
        ConcurrentHashMap<UUID, ServerAudioNetworkHandler.PendingSync> pending = new ConcurrentHashMap<>();
        UUID activeSession = UUID.randomUUID();
        UUID timedOutSession = UUID.randomUUID();
        UUID readySession = UUID.randomUUID();
        pending.put(activeSession, pendingSync(50_000L, Set.of(UUID.randomUUID())));
        pending.put(timedOutSession, pendingSync(1_000L, Set.of(UUID.randomUUID())));
        pending.put(readySession, pendingSync(50_000L, Set.of()));

        var due = ServerAudioNetworkHandler.removeDueSyncs(pending, 50_000L);

        assertEquals(Set.of(activeSession), pending.keySet());
        assertEquals(
                Set.of(timedOutSession, readySession),
                due.stream()
                        .map(ServerAudioNetworkHandler.DueSync::sessionUUID)
                        .collect(java.util.stream.Collectors.toSet()));
    }

    @Test
    void readyBarrierCompletesOnlyAfterEveryExpectedPlayerResponds() {
        UUID firstPlayer = UUID.randomUUID();
        UUID secondPlayer = UUID.randomUUID();
        ServerAudioNetworkHandler.PendingSync sync = pendingSync(1_000L, Set.of(firstPlayer, secondPlayer));

        assertFalse(ServerAudioNetworkHandler.markPlayerReady(sync, firstPlayer));
        assertEquals(Set.of(secondPlayer), sync.waitingPlayers());
        assertTrue(ServerAudioNetworkHandler.markPlayerReady(sync, secondPlayer));
        assertTrue(sync.waitingPlayers().isEmpty());
    }

    @Test
    void duplicateOrUnexpectedReadySignalCannotCompleteBarrierEarly() {
        UUID expectedPlayer = UUID.randomUUID();
        ServerAudioNetworkHandler.PendingSync sync = pendingSync(1_000L, Set.of(expectedPlayer));

        assertFalse(ServerAudioNetworkHandler.markPlayerReady(sync, UUID.randomUUID()));
        assertFalse(ServerAudioNetworkHandler.markPlayerReady(sync, UUID.randomUUID()));
        assertEquals(Set.of(expectedPlayer), sync.waitingPlayers());
    }

    @Test
    void dimensionPruningRemovesDisconnectedAndDimensionChangedPlayers() {
        UUID sameDimension = UUID.randomUUID();
        UUID changedDimension = UUID.randomUUID();
        UUID disconnected = UUID.randomUUID();
        ServerAudioNetworkHandler.PendingSync sync =
                pendingSync(1_000L, Set.of(sameDimension, changedDimension, disconnected));
        Identifier nether = new Identifier("minecraft", "the_nether");

        int removed = ServerAudioNetworkHandler.prunePlayersOutsideDimension(sync, playerUUID -> {
            if (playerUUID.equals(sameDimension)) return new Identifier("minecraft", "overworld");
            if (playerUUID.equals(changedDimension)) return nether;
            return null;
        });

        assertEquals(2, removed);
        assertEquals(Set.of(sameDimension), sync.waitingPlayers());
    }

    @Test
    void dimensionPruningLeavesAllPlayersWaitingWhenTheyStillMatch() {
        UUID firstPlayer = UUID.randomUUID();
        UUID secondPlayer = UUID.randomUUID();
        ServerAudioNetworkHandler.PendingSync sync = pendingSync(1_000L, Set.of(firstPlayer, secondPlayer));

        int removed = ServerAudioNetworkHandler.prunePlayersOutsideDimension(
                sync, ignored -> new Identifier("minecraft", "overworld"));

        assertEquals(0, removed);
        assertEquals(Set.of(firstPlayer, secondPlayer), sync.waitingPlayers());
    }

    @Test
    void makingSystemPrivateTargetsOnlyForeignSessionsUsingThatOwnersSpeakers() {
        UUID owner = UUID.randomUUID();
        UUID foreignController = UUID.randomUUID();
        UUID unrelatedController = UUID.randomUUID();
        Identifier overworld = new Identifier("minecraft", "overworld");
        ConcurrentHashMap<UUID, ServerAudioNetworkHandler.SessionClaim> claims = new ConcurrentHashMap<>();
        claims.put(owner, new ServerAudioNetworkHandler.SessionClaim(overworld, owner, Set.of(new BlockPos(1, 2, 3))));
        claims.put(
                foreignController,
                new ServerAudioNetworkHandler.SessionClaim(overworld, owner, Set.of(new BlockPos(4, 5, 6))));
        claims.put(
                unrelatedController,
                new ServerAudioNetworkHandler.SessionClaim(
                        overworld, UUID.randomUUID(), Set.of(new BlockPos(7, 8, 9))));

        List<UUID> unauthorized = ServerAudioNetworkHandler.findUnauthorizedSessionsForOwner(claims, owner);

        assertEquals(List.of(foreignController), unauthorized);
    }

    private static ServerAudioNetworkHandler.PendingSync pendingSync(long startedAtMs, Set<UUID> waitingPlayers) {
        Set<UUID> concurrentWaitingPlayers = ConcurrentHashMap.newKeySet();
        concurrentWaitingPlayers.addAll(waitingPlayers);
        return new ServerAudioNetworkHandler.PendingSync(
                concurrentWaitingPlayers, startedAtMs, new Identifier("minecraft", "overworld"));
    }
}
