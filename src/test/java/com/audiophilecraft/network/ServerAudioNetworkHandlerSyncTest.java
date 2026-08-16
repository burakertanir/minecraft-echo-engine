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
    void ownerListIncludesOnlyOnlinePlayersOrOwnersWithSpeakers() {
        UUID requestingPlayer = UUID.randomUUID();
        UUID offlineSpeakerOwner = UUID.randomUUID();
        UUID onlinePlayerWithoutSpeakers = UUID.randomUUID();
        UUID offlinePlayerWithoutSpeakers = UUID.randomUUID();

        Set<UUID> visibleOwners = ServerAudioNetworkHandler.visibleOwnerIds(
                requestingPlayer, Set.of(offlineSpeakerOwner), Set.of(onlinePlayerWithoutSpeakers));

        assertEquals(Set.of(requestingPlayer, offlineSpeakerOwner, onlinePlayerWithoutSpeakers), visibleOwners);
        assertFalse(visibleOwners.contains(offlinePlayerWithoutSpeakers));
    }

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
    void playbackBarrierCompletesAfterEveryPlayerReportsReadyOrFailed() {
        UUID readyPlayer = UUID.randomUUID();
        UUID failedPlayer = UUID.randomUUID();
        ServerAudioNetworkHandler.PendingPlaybackSync sync =
                pendingPlaybackSync(1_000L, Set.of(readyPlayer, failedPlayer));

        assertFalse(ServerAudioNetworkHandler.markPlaybackResult(sync, readyPlayer, true));
        assertEquals(Set.of(readyPlayer), sync.readyPlayers());
        assertEquals(Set.of(failedPlayer), sync.waitingPlayers());

        assertTrue(ServerAudioNetworkHandler.markPlaybackResult(sync, failedPlayer, false));
        assertTrue(sync.waitingPlayers().isEmpty());
        assertEquals(Set.of(readyPlayer), sync.readyPlayers());
        assertEquals(Set.of(failedPlayer), sync.failedPlayers());
    }

    @Test
    void duplicateOrUnexpectedPlaybackResultCannotChangeTheTerminalOutcome() {
        UUID expectedPlayer = UUID.randomUUID();
        UUID unexpectedPlayer = UUID.randomUUID();
        ServerAudioNetworkHandler.PendingPlaybackSync sync = pendingPlaybackSync(1_000L, Set.of(expectedPlayer));

        assertFalse(ServerAudioNetworkHandler.markPlaybackResult(sync, unexpectedPlayer, true));
        assertTrue(sync.readyPlayers().isEmpty());
        assertTrue(sync.failedPlayers().isEmpty());

        assertTrue(ServerAudioNetworkHandler.markPlaybackResult(sync, expectedPlayer, false));
        assertFalse(ServerAudioNetworkHandler.markPlaybackResult(sync, expectedPlayer, true));
        assertTrue(sync.readyPlayers().isEmpty());
        assertEquals(Set.of(expectedPlayer), sync.failedPlayers());
    }

    @Test
    void allFailedPlaybackHasNoReadyClients() {
        UUID firstPlayer = UUID.randomUUID();
        UUID secondPlayer = UUID.randomUUID();
        ServerAudioNetworkHandler.PendingPlaybackSync sync =
                pendingPlaybackSync(1_000L, Set.of(firstPlayer, secondPlayer));

        assertFalse(ServerAudioNetworkHandler.markPlaybackResult(sync, firstPlayer, false));
        assertTrue(ServerAudioNetworkHandler.markPlaybackResult(sync, secondPlayer, false));

        assertTrue(sync.waitingPlayers().isEmpty());
        assertTrue(sync.readyPlayers().isEmpty());
        assertEquals(Set.of(firstPlayer, secondPlayer), sync.failedPlayers());
    }

    @Test
    void pendingPlaybackUsesTheLongerRetryAwareTimeoutBoundary() {
        assertEquals(30_000L, ServerAudioNetworkHandler.SYNC_TIMEOUT_MS);
        assertEquals(60_000L, ServerAudioNetworkHandler.PLAYBACK_PREPARE_TIMEOUT_MS);

        ConcurrentHashMap<UUID, ServerAudioNetworkHandler.PendingPlaybackSync> pending = new ConcurrentHashMap<>();
        UUID sessionUUID = UUID.randomUUID();
        ServerAudioNetworkHandler.PendingPlaybackSync sync = pendingPlaybackSync(1_000L, Set.of(UUID.randomUUID()));
        pending.put(sessionUUID, sync);

        assertTrue(ServerAudioNetworkHandler.removeDuePlaybackSyncs(
                        pending, 1_000L + ServerAudioNetworkHandler.PLAYBACK_PREPARE_TIMEOUT_MS)
                .isEmpty());
        var due = ServerAudioNetworkHandler.removeDuePlaybackSyncs(
                pending, 1_001L + ServerAudioNetworkHandler.PLAYBACK_PREPARE_TIMEOUT_MS);

        assertEquals(1, due.size());
        assertSame(sync, due.get(0).state());
        assertTrue(due.get(0).timedOut());
        assertTrue(pending.isEmpty());
    }

    @Test
    void playbackDimensionPruningRemovesWaitingReadyAndFailedClients() {
        UUID waitingPlayer = UUID.randomUUID();
        UUID readyPlayer = UUID.randomUUID();
        UUID failedPlayer = UUID.randomUUID();
        UUID retainedPlayer = UUID.randomUUID();
        ServerAudioNetworkHandler.PendingPlaybackSync sync =
                pendingPlaybackSync(1_000L, Set.of(waitingPlayer, readyPlayer, failedPlayer, retainedPlayer));
        assertFalse(ServerAudioNetworkHandler.markPlaybackResult(sync, readyPlayer, true));
        assertFalse(ServerAudioNetworkHandler.markPlaybackResult(sync, failedPlayer, false));

        int removed = ServerAudioNetworkHandler.prunePlaybackPlayersOutsideDimension(sync, playerUUID -> {
            if (playerUUID.equals(retainedPlayer)) return new Identifier("minecraft", "overworld");
            return null;
        });

        assertEquals(3, removed);
        assertEquals(Set.of(retainedPlayer), sync.waitingPlayers());
        assertTrue(sync.readyPlayers().isEmpty());
        assertTrue(sync.failedPlayers().isEmpty());
    }

    @Test
    void playbackWithNoRemainingWaitersBecomesDueWithoutBeingTimedOut() {
        ConcurrentHashMap<UUID, ServerAudioNetworkHandler.PendingPlaybackSync> pending = new ConcurrentHashMap<>();
        UUID sessionUUID = UUID.randomUUID();
        UUID readyPlayer = UUID.randomUUID();
        ServerAudioNetworkHandler.PendingPlaybackSync sync = pendingPlaybackSync(10_000L, Set.of(readyPlayer));
        assertTrue(ServerAudioNetworkHandler.markPlaybackResult(sync, readyPlayer, true));
        pending.put(sessionUUID, sync);

        var due = ServerAudioNetworkHandler.removeDuePlaybackSyncs(pending, 10_000L);

        assertEquals(1, due.size());
        assertFalse(due.get(0).timedOut());
        assertEquals(Set.of(readyPlayer), due.get(0).state().readyPlayers());
        assertTrue(pending.isEmpty());
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

    private static ServerAudioNetworkHandler.PendingPlaybackSync pendingPlaybackSync(
            long startedAtMs, Set<UUID> waitingPlayers) {
        Set<UUID> concurrentWaitingPlayers = ConcurrentHashMap.newKeySet();
        concurrentWaitingPlayers.addAll(waitingPlayers);
        return new ServerAudioNetworkHandler.PendingPlaybackSync(
                concurrentWaitingPlayers,
                ConcurrentHashMap.newKeySet(),
                ConcurrentHashMap.newKeySet(),
                startedAtMs,
                new Identifier("minecraft", "overworld"));
    }
}
