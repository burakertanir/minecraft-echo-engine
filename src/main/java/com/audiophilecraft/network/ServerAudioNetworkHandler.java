package com.audiophilecraft.network;

import static com.audiophilecraft.network.ModMessages.C2S_CHANNEL_MASK;
import static com.audiophilecraft.network.ModMessages.C2S_GET_SPEAKER_OWNERS;
import static com.audiophilecraft.network.ModMessages.C2S_PLAYBACK_READY;
import static com.audiophilecraft.network.ModMessages.C2S_PLAY_URL;
import static com.audiophilecraft.network.ModMessages.C2S_SEEK_READY;
import static com.audiophilecraft.network.ModMessages.C2S_SEEK_TRACK;
import static com.audiophilecraft.network.ModMessages.C2S_STOP_AUDIO;
import static com.audiophilecraft.network.ModMessages.C2S_TOGGLE_PAUSE;
import static com.audiophilecraft.network.ModMessages.C2S_UPDATE_EQ;
import static com.audiophilecraft.network.ModMessages.C2S_UPDATE_EQ_Q;
import static com.audiophilecraft.network.ModMessages.C2S_UPDATE_INPUT_GAIN;
import static com.audiophilecraft.network.ModMessages.C2S_UPDATE_MIXER_GAIN;
import static com.audiophilecraft.network.ModMessages.C2S_UPDATE_PLACEMENT_OWNER;
import static com.audiophilecraft.network.ModMessages.C2S_UPDATE_POWER;
import static com.audiophilecraft.network.ModMessages.C2S_UPDATE_SELECTED_OWNER;
import static com.audiophilecraft.network.ModMessages.C2S_UPDATE_SPEAKER_ACCESS;
import static com.audiophilecraft.network.ModMessages.C2S_UPDATE_SPEAKER_SHIFT;
import static com.audiophilecraft.network.ModMessages.C2S_UPDATE_TILT;
import static com.audiophilecraft.network.ModMessages.S2C_PLAY_TRACK;
import static com.audiophilecraft.network.ModMessages.S2C_PLAY_URL;
import static com.audiophilecraft.network.ModMessages.S2C_PREP_SEEK;
import static com.audiophilecraft.network.ModMessages.S2C_SPEAKER_OWNERS;
import static com.audiophilecraft.network.ModMessages.S2C_START_PLAYBACK;
import static com.audiophilecraft.network.ModMessages.S2C_STOP_AUDIO;
import static com.audiophilecraft.network.ModMessages.S2C_SYNC_CHANNEL_MASK;
import static com.audiophilecraft.network.ModMessages.S2C_SYNC_EQ;
import static com.audiophilecraft.network.ModMessages.S2C_SYNC_EQ_Q;
import static com.audiophilecraft.network.ModMessages.S2C_SYNC_INPUT_GAIN;
import static com.audiophilecraft.network.ModMessages.S2C_SYNC_MIXER_GAIN;
import static com.audiophilecraft.network.ModMessages.S2C_SYNC_POWER;
import static com.audiophilecraft.network.ModMessages.S2C_SYNC_SEEK;
import static com.audiophilecraft.network.ModMessages.S2C_TOGGLE_PAUSE;

import com.audiophilecraft.AudiophileCraft;
import com.audiophilecraft.block.entity.SpeakerBlockEntity;
import com.audiophilecraft.item.AmplifierTabletItem;
import com.audiophilecraft.registry.SpeakerAccessState;
import com.audiophilecraft.registry.SpeakerRegistry;
import com.audiophilecraft.screen.SpeakerScreenHandler;
import com.audiophilecraft.sound.SpeakerClusterer;
import com.audiophilecraft.sound.SpeakerPlaybackData;
import com.mojang.authlib.GameProfile;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.Hand;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;

/** Owns C2S packet handling, server-side persistence, broadcasts and ready handshakes. */
final class ServerAudioNetworkHandler {
    static final long SYNC_TIMEOUT_MS = 30_000L;
    private static final int SYNC_MAINTENANCE_INTERVAL_TICKS = 20;
    private static final long OWNER_LIST_REQUEST_INTERVAL_MS = 2_000L;

    private static final ConcurrentHashMap<UUID, PendingSync> pendingPlayReady = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<UUID, PendingSync> pendingSeekReady = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<UUID, OwnerListCache> ownerListCaches = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<UUID, SessionClaim> activeSessionSpeakers = new ConcurrentHashMap<>();
    private static int syncMaintenanceTicks;

    private ServerAudioNetworkHandler() {}

    static void register() {
        registerPlaybackPackets();
        registerTabletControlPackets();
        registerSpeakerControlPackets();
        registerTransportPackets();

        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
            cleanupDisconnectedPlayer(handler.getPlayer().getUuid(), server);
        });
    }

    static void tickPendingSyncs(MinecraftServer server) {
        syncMaintenanceTicks++;
        if (syncMaintenanceTicks < SYNC_MAINTENANCE_INTERVAL_TICKS) return;
        syncMaintenanceTicks = 0;

        long now = System.currentTimeMillis();
        maintainPendingSyncs(server, pendingPlayReady, S2C_START_PLAYBACK, "Playback", now);
        maintainPendingSyncs(server, pendingSeekReady, S2C_SYNC_SEEK, "Seek", now);
    }

    static void clearPendingSyncs() {
        pendingPlayReady.clear();
        pendingSeekReady.clear();
        ownerListCaches.clear();
        activeSessionSpeakers.clear();
        syncMaintenanceTicks = 0;
    }

    static void cleanupDisconnectedPlayer(UUID playerUUID, MinecraftServer server) {
        for (PendingSync sync : pendingPlayReady.values()) sync.waitingPlayers().remove(playerUUID);
        for (PendingSync sync : pendingSeekReady.values()) sync.waitingPlayers().remove(playerUUID);
        ownerListCaches.remove(playerUUID);
        if (activeSessionSpeakers.containsKey(playerUUID)
                || pendingPlayReady.containsKey(playerUUID)
                || pendingSeekReady.containsKey(playerUUID)) {
            stopSession(server, playerUUID);
        }

        long now = System.currentTimeMillis();
        completeDueSyncs(server, pendingPlayReady, S2C_START_PLAYBACK, "Playback", now);
        completeDueSyncs(server, pendingSeekReady, S2C_SYNC_SEEK, "Seek", now);
    }

    static void sendPlayTrack(
            ServerPlayerEntity player,
            Identifier playbackDimension,
            UUID ownerUUID,
            String trackId,
            List<SpeakerPlaybackData> speakers,
            float power,
            float inputGain) {
        PacketByteBuf buf = AudioPacketCodec.createPlaybackBuffer(
                playbackDimension,
                ownerUUID,
                trackId,
                AudioPacketCodec.MAX_TRACK_ID_LENGTH,
                speakers,
                power,
                inputGain);
        ServerPlayNetworking.send(player, S2C_PLAY_TRACK, buf);
    }

    static void sendPlayUrl(
            ServerPlayerEntity player,
            Identifier playbackDimension,
            UUID ownerUUID,
            String url,
            List<SpeakerPlaybackData> speakers,
            float power,
            float inputGain) {
        PacketByteBuf buf = AudioPacketCodec.createPlaybackBuffer(
                playbackDimension, ownerUUID, url, AudioPacketCodec.MAX_URL_LENGTH, speakers, power, inputGain);
        ServerPlayNetworking.send(player, S2C_PLAY_URL, buf);
    }

    private static void registerPlaybackPackets() {
        ServerPlayNetworking.registerGlobalReceiver(C2S_PLAY_URL, (server, player, handler, buf, responseSender) -> {
            if (!buf.isReadable(20)) return;
            int handOrdinal;
            UUID selectedOwner;
            String url;
            try {
                handOrdinal = buf.readInt();
                selectedOwner = buf.readUuid();
                url = buf.readString(2048);
            } catch (RuntimeException exception) {
                return;
            }
            if (!ModMessages.isValidHandOrdinal(handOrdinal) || !ModMessages.isValidAudioUrl(url)) return;
            server.execute(() -> {
                ItemStack stack = getTabletStack(player, handOrdinal);
                if (!(stack.getItem() instanceof AmplifierTabletItem)) return;

                UUID sessionUUID = player.getUuid();
                SpeakerAccessState accessState = SpeakerAccessState.get(server);
                if (!accessState.canAccess(player.getUuid(), selectedOwner)) {
                    sendSpeakerOwners(server, player, true);
                    return;
                }
                UUID speakerOwner = selectedOwner;
                Identifier playbackDimension =
                        player.getWorld().getRegistryKey().getValue();
                List<SpeakerPlaybackData> speakers =
                        SpeakerRegistry.findPlaybackDataByOwner(player.getWorld(), speakerOwner);
                if (speakers.isEmpty()) return;
                stopOverlappingSessions(server, sessionUUID, speakerOwner, speakers, playbackDimension);
                float power = AmplifierTabletItem.getSpeakerPower(stack);
                float inputGain = AmplifierTabletItem.getInputGain(stack);
                Set<UUID> waitingPlayers = ConcurrentHashMap.newKeySet();
                for (ServerPlayerEntity onlinePlayer : server.getPlayerManager().getPlayerList()) {
                    if (!isPlayerInDimension(onlinePlayer, playbackDimension)) continue;
                    sendPlayUrl(onlinePlayer, playbackDimension, sessionUUID, url, speakers, power, inputGain);
                    waitingPlayers.add(onlinePlayer.getUuid());
                }
                pendingPlayReady.put(
                        sessionUUID, new PendingSync(waitingPlayers, System.currentTimeMillis(), playbackDimension));
            });
        });

        ServerPlayNetworking.registerGlobalReceiver(
                C2S_PLAYBACK_READY, (server, player, handler, buf, responseSender) -> {
                    if (!buf.isReadable(16)) return;
                    UUID sessionUUID = buf.readUuid();
                    server.execute(() -> completePendingSync(
                            pendingPlayReady, sessionUUID, player.getUuid(), server, S2C_START_PLAYBACK));
                });

        ServerPlayNetworking.registerGlobalReceiver(
                C2S_GET_SPEAKER_OWNERS, (server, player, handler, buf, responseSender) -> {
                    server.execute(() -> sendSpeakerOwners(server, player, false));
                });

        ServerPlayNetworking.registerGlobalReceiver(
                C2S_UPDATE_SELECTED_OWNER, (server, player, handler, buf, responseSender) -> {
                    if (!buf.isReadable(20)) return;
                    int handOrdinal = buf.readInt();
                    UUID selectedOwner = buf.readUuid();
                    if (!ModMessages.isValidHandOrdinal(handOrdinal)) return;
                    server.execute(() -> {
                        ItemStack stack = getTabletStack(player, handOrdinal);
                        if (!(stack.getItem() instanceof AmplifierTabletItem)) return;
                        SpeakerAccessState accessState = SpeakerAccessState.get(server);
                        if (!accessState.canAccess(player.getUuid(), selectedOwner)) {
                            AmplifierTabletItem.setSelectedOwner(stack, player.getUuid());
                            sendSpeakerOwners(server, player, true);
                            return;
                        }
                        AmplifierTabletItem.setSelectedOwner(stack, selectedOwner);
                    });
                });

        ServerPlayNetworking.registerGlobalReceiver(
                C2S_UPDATE_SPEAKER_ACCESS, (server, player, handler, buf, responseSender) -> {
                    if (!buf.isReadable(5)) return;
                    int handOrdinal = buf.readInt();
                    boolean shared = buf.readBoolean();
                    if (!ModMessages.isValidHandOrdinal(handOrdinal)) return;
                    server.execute(() -> {
                        ItemStack stack = getTabletStack(player, handOrdinal);
                        if (!(stack.getItem() instanceof AmplifierTabletItem)) return;

                        SpeakerAccessState.get(server).setShared(player.getUuid(), shared);
                        if (!shared) {
                            stopUnauthorizedSessionsForOwner(server, player.getUuid());
                            closeUnauthorizedSpeakerScreens(server, player.getUuid());
                        }
                        broadcastSpeakerOwners(server);
                    });
                });

        ServerPlayNetworking.registerGlobalReceiver(
                C2S_UPDATE_PLACEMENT_OWNER, (server, player, handler, buf, responseSender) -> {
                    if (!buf.isReadable(20)) return;
                    int handOrdinal = buf.readInt();
                    UUID requestedOwner = buf.readUuid();
                    if (!ModMessages.isValidHandOrdinal(handOrdinal)) return;
                    server.execute(() -> {
                        ItemStack stack = getTabletStack(player, handOrdinal);
                        if (!(stack.getItem() instanceof AmplifierTabletItem)) return;

                        SpeakerAccessState.get(server).setPlacementTarget(player.getUuid(), requestedOwner);
                        sendSpeakerOwners(server, player, true);
                    });
                });
    }

    private static void stopOverlappingSessions(
            MinecraftServer server,
            UUID newSessionUUID,
            UUID speakerOwner,
            List<SpeakerPlaybackData> speakers,
            Identifier playbackDimension) {
        if (speakers == null || speakers.isEmpty()) return;

        SessionClaim previousClaim = activeSessionSpeakers.remove(newSessionUUID);
        if (previousClaim != null) {
            broadcastToDimension(
                    server, previousClaim.dimension(), S2C_STOP_AUDIO, buf -> buf.writeUuid(newSessionUUID));
        }
        pendingPlayReady.remove(newSessionUUID);
        pendingSeekReady.remove(newSessionUUID);

        Set<BlockPos> newPositions = ConcurrentHashMap.newKeySet();
        for (SpeakerPlaybackData speaker : speakers) newPositions.add(speaker.position());

        for (var entry : activeSessionSpeakers.entrySet()) {
            UUID existingSession = entry.getKey();
            if (existingSession.equals(newSessionUUID)) continue;
            SessionClaim existingClaim = entry.getValue();
            if (existingClaim == null
                    || !playbackDimension.equals(existingClaim.dimension())
                    || Collections.disjoint(existingClaim.positions(), newPositions)) {
                continue;
            }
            broadcastToDimension(server, playbackDimension, S2C_STOP_AUDIO, buf -> buf.writeUuid(existingSession));
            activeSessionSpeakers.remove(existingSession, existingClaim);
            pendingPlayReady.remove(existingSession);
            pendingSeekReady.remove(existingSession);
        }
        activeSessionSpeakers.put(newSessionUUID, new SessionClaim(playbackDimension, speakerOwner, newPositions));
    }

    private static List<ModMessages.SpeakerOwner> resolveSpeakerOwners(
            MinecraftServer server, ServerPlayerEntity player, SpeakerAccessState accessState) {
        Map<UUID, Integer> speakerCounts = new HashMap<>();
        for (SpeakerRegistry.OwnerEntry entry : SpeakerRegistry.findSpeakerOwners(player.getWorld())) {
            speakerCounts.put(entry.ownerUUID(), entry.speakerCount());
        }

        Set<UUID> candidates = new HashSet<>(accessState.getSharedOwners());
        candidates.addAll(speakerCounts.keySet());
        for (ServerPlayerEntity onlinePlayer : server.getPlayerManager().getPlayerList()) {
            candidates.add(onlinePlayer.getUuid());
        }
        candidates.add(player.getUuid());

        List<ModMessages.SpeakerOwner> owners = new ArrayList<>();
        for (UUID ownerUUID : candidates) {
            owners.add(new ModMessages.SpeakerOwner(
                    ownerUUID,
                    resolveOwnerName(server, ownerUUID),
                    speakerCounts.getOrDefault(ownerUUID, 0),
                    accessState.isShared(ownerUUID)));
        }
        owners.sort((first, second) -> {
            boolean firstIsSelf = first.uuid().equals(player.getUuid());
            boolean secondIsSelf = second.uuid().equals(player.getUuid());
            if (firstIsSelf != secondIsSelf) return firstIsSelf ? -1 : 1;
            if (first.shared() != second.shared()) return first.shared() ? -1 : 1;
            return first.name().compareToIgnoreCase(second.name());
        });
        return owners;
    }

    private static String resolveOwnerName(MinecraftServer server, UUID ownerUUID) {
        ServerPlayerEntity onlinePlayer = server.getPlayerManager().getPlayer(ownerUUID);
        if (onlinePlayer != null) return onlinePlayer.getGameProfile().getName();

        GameProfile cachedProfile = server.getUserCache().getByUuid(ownerUUID).orElse(null);
        if (cachedProfile != null && cachedProfile.getName() != null) return cachedProfile.getName();
        return "Player-" + ownerUUID.toString().substring(0, 8);
    }

    private static void sendSpeakerOwners(MinecraftServer server, ServerPlayerEntity player, boolean forceRefresh) {
        SpeakerAccessState accessState = SpeakerAccessState.get(server);
        UUID placementOwner = accessState.resolvePlacementOwner(player.getUuid());
        Identifier dimension = player.getWorld().getRegistryKey().getValue();
        long now = System.currentTimeMillis();
        OwnerListCache cached = ownerListCaches.get(player.getUuid());
        if (!forceRefresh
                && cached != null
                && now - cached.createdAtMs() < OWNER_LIST_REQUEST_INTERVAL_MS
                && dimension.equals(cached.dimension())
                && placementOwner.equals(cached.placementOwner())) {
            ServerPlayNetworking.send(
                    player, S2C_SPEAKER_OWNERS, SpeakerOwnerListCodec.create(cached.placementOwner(), cached.owners()));
            return;
        }

        List<ModMessages.SpeakerOwner> owners = resolveSpeakerOwners(server, player, accessState);
        if (!placementOwner.equals(player.getUuid())) {
            for (int index = 1; index < owners.size(); index++) {
                if (!placementOwner.equals(owners.get(index).uuid())) continue;
                ModMessages.SpeakerOwner activePlacementOwner = owners.remove(index);
                owners.add(Math.min(1, owners.size()), activePlacementOwner);
                break;
            }
        }
        if (owners.size() > SpeakerOwnerListCodec.MAX_OWNERS) {
            owners = new ArrayList<>(owners.subList(0, SpeakerOwnerListCodec.MAX_OWNERS));
        }
        OwnerListCache fresh = new OwnerListCache(dimension, now, placementOwner, List.copyOf(owners));
        ownerListCaches.put(player.getUuid(), fresh);
        ServerPlayNetworking.send(player, S2C_SPEAKER_OWNERS, SpeakerOwnerListCodec.create(placementOwner, owners));
    }

    private static void broadcastSpeakerOwners(MinecraftServer server) {
        ownerListCaches.clear();
        for (ServerPlayerEntity onlinePlayer : server.getPlayerManager().getPlayerList()) {
            sendSpeakerOwners(server, onlinePlayer, true);
        }
    }

    private static void registerTabletControlPackets() {
        ServerPlayNetworking.registerGlobalReceiver(
                C2S_UPDATE_POWER, (server, player, handler, buf, responseSender) -> {
                    int handOrdinal = buf.readInt();
                    float power = buf.readFloat();
                    if (!Float.isFinite(power)) return;
                    float sanitizedPower = clamp(power, 0.1f, 10.0f);
                    server.execute(() -> {
                        ItemStack stack = getTabletStack(player, handOrdinal);
                        if (!(stack.getItem() instanceof AmplifierTabletItem)) return;

                        AmplifierTabletItem.setSpeakerPower(stack, sanitizedPower);
                        broadcastToAll(server, S2C_SYNC_POWER, syncBuf -> {
                            syncBuf.writeUuid(player.getUuid());
                            syncBuf.writeInt(handOrdinal);
                            syncBuf.writeFloat(sanitizedPower);
                        });
                    });
                });

        ServerPlayNetworking.registerGlobalReceiver(
                C2S_UPDATE_INPUT_GAIN, (server, player, handler, buf, responseSender) -> {
                    int handOrdinal = buf.readInt();
                    float gain = buf.readFloat();
                    if (!Float.isFinite(gain)) return;
                    float sanitizedGain = clamp(gain, 0.0f, 3.0f);
                    server.execute(() -> {
                        ItemStack stack = getTabletStack(player, handOrdinal);
                        if (!(stack.getItem() instanceof AmplifierTabletItem)) return;

                        AmplifierTabletItem.setInputGain(stack, sanitizedGain);
                        broadcastToAll(server, S2C_SYNC_INPUT_GAIN, syncBuf -> {
                            syncBuf.writeUuid(player.getUuid());
                            syncBuf.writeInt(handOrdinal);
                            syncBuf.writeFloat(sanitizedGain);
                        });
                    });
                });

        ServerPlayNetworking.registerGlobalReceiver(C2S_UPDATE_EQ, (server, player, handler, buf, responseSender) -> {
            String speakerType = buf.readString(16);
            int band = buf.readInt();
            float db = buf.readFloat();
            if (!ModMessages.isValidSpeakerType(speakerType) || !ModMessages.isValidEqBand(band) || !Float.isFinite(db))
                return;
            float sanitizedDb = clamp(db, -9.0f, 9.0f);
            server.execute(() -> {
                ItemStack stack = getTabletStack(player);
                if (stack.getItem() instanceof AmplifierTabletItem) {
                    AmplifierTabletItem.setEqDb(stack, speakerType, band, sanitizedDb);
                }
                broadcastToAll(server, S2C_SYNC_EQ, syncBuf -> {
                    syncBuf.writeUuid(player.getUuid());
                    syncBuf.writeString(speakerType);
                    syncBuf.writeInt(band);
                    syncBuf.writeFloat(sanitizedDb);
                });
            });
        });

        ServerPlayNetworking.registerGlobalReceiver(C2S_UPDATE_EQ_Q, (server, player, handler, buf, responseSender) -> {
            String speakerType = buf.readString(16);
            int band = buf.readInt();
            float q = buf.readFloat();
            if (!ModMessages.isValidSpeakerType(speakerType) || !ModMessages.isValidEqBand(band) || !Float.isFinite(q))
                return;
            float sanitizedQ = clamp(q, 0.1f, 10.0f);
            server.execute(() -> {
                ItemStack stack = getTabletStack(player);
                if (stack.getItem() instanceof AmplifierTabletItem) {
                    AmplifierTabletItem.setEqQ(stack, speakerType, band, sanitizedQ);
                }
                broadcastToAll(server, S2C_SYNC_EQ_Q, syncBuf -> {
                    syncBuf.writeUuid(player.getUuid());
                    syncBuf.writeString(speakerType);
                    syncBuf.writeInt(band);
                    syncBuf.writeFloat(sanitizedQ);
                });
            });
        });

        ServerPlayNetworking.registerGlobalReceiver(
                C2S_UPDATE_MIXER_GAIN, (server, player, handler, buf, responseSender) -> {
                    String speakerType = buf.readString(16);
                    float gain = buf.readFloat();
                    if (!ModMessages.isValidSpeakerType(speakerType) || !Float.isFinite(gain)) return;
                    float sanitizedGain = clamp(gain, 0.0f, 1.0f);
                    server.execute(() -> {
                        ItemStack stack = getTabletStack(player);
                        if (stack.getItem() instanceof AmplifierTabletItem) {
                            AmplifierTabletItem.setMixerGain(stack, speakerType, sanitizedGain);
                        }
                        broadcastToAll(server, S2C_SYNC_MIXER_GAIN, syncBuf -> {
                            syncBuf.writeUuid(player.getUuid());
                            syncBuf.writeString(speakerType);
                            syncBuf.writeFloat(sanitizedGain);
                        });
                    });
                });
    }

    private static void registerSpeakerControlPackets() {
        ServerPlayNetworking.registerGlobalReceiver(
                C2S_UPDATE_SPEAKER_SHIFT, (server, player, handler, buf, responseSender) -> {
                    BlockPos position = buf.readBlockPos();
                    int shift = buf.readInt();
                    if (shift < 0 || shift > 30) return;
                    server.execute(() -> {
                        if (!canModifySpeaker(player, position)) return;
                        BlockEntity blockEntity = player.getWorld().getBlockEntity(position);
                        if (blockEntity instanceof SpeakerBlockEntity speaker) speaker.setSampleShift(shift);
                    });
                });

        ServerPlayNetworking.registerGlobalReceiver(C2S_UPDATE_TILT, (server, player, handler, buf, responseSender) -> {
            BlockPos position = buf.readBlockPos();
            int tilt = buf.readInt();
            if (tilt < -70 || tilt > 70) return;
            server.execute(() -> {
                if (!canModifySpeaker(player, position)) return;
                BlockEntity blockEntity = player.getWorld().getBlockEntity(position);
                if (blockEntity instanceof SpeakerBlockEntity speaker) speaker.setVerticalTilt(tilt);
            });
        });

        ServerPlayNetworking.registerGlobalReceiver(
                C2S_CHANNEL_MASK, (server, player, handler, buf, responseSender) -> {
                    BlockPos position = buf.readBlockPos();
                    int mask = buf.readInt();
                    if (mask < 0 || mask > 2) return;
                    server.execute(() -> updateClusterChannelMask(server, player, position, mask));
                });
    }

    private static void registerTransportPackets() {
        ServerPlayNetworking.registerGlobalReceiver(C2S_SEEK_TRACK, (server, player, handler, buf, responseSender) -> {
            float targetTime = buf.readFloat();
            if (!Float.isFinite(targetTime) || targetTime < 0.0f) return;
            server.execute(() -> {
                if (!(getTabletStack(player).getItem() instanceof AmplifierTabletItem)) return;

                UUID sessionUUID = player.getUuid();
                Identifier playbackDimension =
                        player.getWorld().getRegistryKey().getValue();
                Set<UUID> waitingPlayers = ConcurrentHashMap.newKeySet();
                for (ServerPlayerEntity onlinePlayer : server.getPlayerManager().getPlayerList()) {
                    if (!isPlayerInDimension(onlinePlayer, playbackDimension)) continue;
                    PacketByteBuf prepBuf =
                            AudioPacketCodec.createSeekPrepBuffer(sessionUUID, playbackDimension, targetTime);
                    ServerPlayNetworking.send(onlinePlayer, S2C_PREP_SEEK, prepBuf);
                    waitingPlayers.add(onlinePlayer.getUuid());
                }
                pendingSeekReady.put(
                        sessionUUID, new PendingSync(waitingPlayers, System.currentTimeMillis(), playbackDimension));
            });
        });

        ServerPlayNetworking.registerGlobalReceiver(C2S_SEEK_READY, (server, player, handler, buf, responseSender) -> {
            if (!buf.isReadable(16)) return;
            UUID sessionUUID = buf.readUuid();
            server.execute(
                    () -> completePendingSync(pendingSeekReady, sessionUUID, player.getUuid(), server, S2C_SYNC_SEEK));
        });

        ServerPlayNetworking.registerGlobalReceiver(C2S_STOP_AUDIO, (server, player, handler, buf, responseSender) -> {
            server.execute(() -> stopSession(server, player.getUuid()));
        });

        ServerPlayNetworking.registerGlobalReceiver(
                C2S_TOGGLE_PAUSE, (server, player, handler, buf, responseSender) -> {
                    server.execute(() -> {
                        if (!(getTabletStack(player).getItem() instanceof AmplifierTabletItem)) return;
                        SessionClaim claim = activeSessionSpeakers.get(player.getUuid());
                        if (claim == null) return;
                        broadcastToDimension(
                                server,
                                claim.dimension(),
                                S2C_TOGGLE_PAUSE,
                                syncBuf -> syncBuf.writeUuid(player.getUuid()));
                    });
                });
    }

    private static void stopSession(MinecraftServer server, UUID sessionUUID) {
        SessionClaim claim = activeSessionSpeakers.remove(sessionUUID);
        PendingSync pendingPlay = pendingPlayReady.remove(sessionUUID);
        PendingSync pendingSeek = pendingSeekReady.remove(sessionUUID);

        Identifier dimension = claim != null
                ? claim.dimension()
                : pendingPlay != null ? pendingPlay.dimension() : pendingSeek != null ? pendingSeek.dimension() : null;
        if (dimension != null) {
            broadcastToDimension(server, dimension, S2C_STOP_AUDIO, buf -> buf.writeUuid(sessionUUID));
        } else {
            broadcastToAll(server, S2C_STOP_AUDIO, buf -> buf.writeUuid(sessionUUID));
        }
    }

    private static void stopUnauthorizedSessionsForOwner(MinecraftServer server, UUID ownerUUID) {
        for (UUID sessionUUID : findUnauthorizedSessionsForOwner(activeSessionSpeakers, ownerUUID)) {
            stopSession(server, sessionUUID);
        }
    }

    static List<UUID> findUnauthorizedSessionsForOwner(Map<UUID, SessionClaim> sessionClaims, UUID ownerUUID) {
        List<UUID> unauthorizedSessions = new ArrayList<>();
        for (Map.Entry<UUID, SessionClaim> entry : sessionClaims.entrySet()) {
            if (ownerUUID.equals(entry.getValue().speakerOwner()) && !ownerUUID.equals(entry.getKey())) {
                unauthorizedSessions.add(entry.getKey());
            }
        }
        return unauthorizedSessions;
    }

    private static void closeUnauthorizedSpeakerScreens(MinecraftServer server, UUID ownerUUID) {
        SpeakerAccessState accessState = SpeakerAccessState.get(server);
        for (ServerPlayerEntity onlinePlayer : server.getPlayerManager().getPlayerList()) {
            if (accessState.canAccess(onlinePlayer.getUuid(), ownerUUID)) continue;
            if (!(onlinePlayer.currentScreenHandler instanceof SpeakerScreenHandler speakerScreen)) continue;
            UUID screenOwner =
                    SpeakerRegistry.getOwner(onlinePlayer.getWorld().getRegistryKey(), speakerScreen.getPos());
            if (ownerUUID.equals(screenOwner)) onlinePlayer.closeHandledScreen();
        }
    }

    private static void completePendingSync(
            ConcurrentHashMap<UUID, PendingSync> pendingSyncs,
            UUID sessionUUID,
            UUID playerUUID,
            MinecraftServer server,
            Identifier completionChannel) {
        PendingSync sync = pendingSyncs.get(sessionUUID);
        if (sync == null) return;

        if (markPlayerReady(sync, playerUUID) || isSyncTimedOut(sync)) {
            if (pendingSyncs.remove(sessionUUID, sync)) {
                broadcastToDimension(server, sync.dimension(), completionChannel, buf -> buf.writeUuid(sessionUUID));
            }
        }
    }

    private static void updateClusterChannelMask(
            MinecraftServer server, ServerPlayerEntity player, BlockPos position, int mask) {
        if (!canModifySpeaker(player, position)) return;

        UUID owner = SpeakerRegistry.getOwner(player.getWorld().getRegistryKey(), position);
        UUID clusterOwner = owner != null ? owner : player.getUuid();
        List<BlockPos> ownedSpeakers =
                SpeakerRegistry.findSpeakersByOwner(player.getWorld().getRegistryKey(), clusterOwner);
        for (List<BlockPos> cluster : SpeakerClusterer.clusterSpeakers(ownedSpeakers)) {
            if (!cluster.contains(position)) continue;

            for (BlockPos clusterPosition : cluster) {
                BlockEntity blockEntity = player.getWorld().getBlockEntity(clusterPosition);
                if (blockEntity instanceof SpeakerBlockEntity speaker) speaker.setChannelMask(mask);
            }
            broadcastToAll(server, S2C_SYNC_CHANNEL_MASK, syncBuf -> {
                syncBuf.writeUuid(player.getUuid());
                syncBuf.writeInt(mask);
                syncBuf.writeInt(cluster.size());
                for (BlockPos clusterPosition : cluster) syncBuf.writeBlockPos(clusterPosition);
            });
            return;
        }
    }

    private static boolean canModifySpeaker(ServerPlayerEntity player, BlockPos position) {
        UUID owner = SpeakerRegistry.getOwner(player.getWorld().getRegistryKey(), position);
        return owner == null || SpeakerAccessState.get(player.getServer()).canAccess(player.getUuid(), owner);
    }

    private static ItemStack getTabletStack(ServerPlayerEntity player, int handOrdinal) {
        if (!ModMessages.isValidHandOrdinal(handOrdinal)) return ItemStack.EMPTY;
        return player.getStackInHand(Hand.values()[handOrdinal]);
    }

    private static ItemStack getTabletStack(ServerPlayerEntity player) {
        ItemStack main = player.getMainHandStack();
        if (main.getItem() instanceof AmplifierTabletItem) return main;
        ItemStack off = player.getOffHandStack();
        return off.getItem() instanceof AmplifierTabletItem ? off : ItemStack.EMPTY;
    }

    private static boolean isSyncTimedOut(PendingSync sync) {
        return System.currentTimeMillis() - sync.startedAtMs() > SYNC_TIMEOUT_MS;
    }

    private static void maintainPendingSyncs(
            MinecraftServer server,
            ConcurrentHashMap<UUID, PendingSync> pendingSyncs,
            Identifier completionChannel,
            String syncName,
            long now) {
        for (PendingSync sync : pendingSyncs.values()) {
            prunePlayersOutsideDimension(sync, playerUUID -> playerDimension(server, playerUUID));
        }
        completeDueSyncs(server, pendingSyncs, completionChannel, syncName, now);
    }

    private static void completeDueSyncs(
            MinecraftServer server,
            ConcurrentHashMap<UUID, PendingSync> pendingSyncs,
            Identifier completionChannel,
            String syncName,
            long now) {
        for (DueSync due : removeDueSyncs(pendingSyncs, now)) {
            if (due.timedOut() && !due.state().waitingPlayers().isEmpty()) {
                AudiophileCraft.LOGGER.warn(
                        "{} sync timed out for session {}; continuing without {} client(s).",
                        syncName,
                        due.sessionUUID(),
                        due.state().waitingPlayers().size());
            }
            broadcastToDimension(
                    server, due.state().dimension(), completionChannel, buf -> buf.writeUuid(due.sessionUUID()));
        }
    }

    static List<DueSync> removeDueSyncs(ConcurrentHashMap<UUID, PendingSync> pendingSyncs, long now) {
        List<DueSync> dueSyncs = new java.util.ArrayList<>();
        for (var pending : pendingSyncs.entrySet()) {
            PendingSync state = pending.getValue();
            boolean timedOut = now - state.startedAtMs() > SYNC_TIMEOUT_MS;
            if (!timedOut && !state.waitingPlayers().isEmpty()) continue;
            if (pendingSyncs.remove(pending.getKey(), state)) {
                dueSyncs.add(new DueSync(pending.getKey(), state, timedOut));
            }
        }
        return dueSyncs;
    }

    static boolean markPlayerReady(PendingSync sync, UUID playerUUID) {
        sync.waitingPlayers().remove(playerUUID);
        return sync.waitingPlayers().isEmpty();
    }

    static int prunePlayersOutsideDimension(PendingSync sync, Function<UUID, Identifier> dimensionLookup) {
        int previousSize = sync.waitingPlayers().size();
        sync.waitingPlayers()
                .removeIf(playerUUID ->
                        !ModMessages.isMatchingDimension(dimensionLookup.apply(playerUUID), sync.dimension()));
        return previousSize - sync.waitingPlayers().size();
    }

    private static Identifier playerDimension(MinecraftServer server, UUID playerUUID) {
        ServerPlayerEntity player = server.getPlayerManager().getPlayer(playerUUID);
        return player == null ? null : player.getWorld().getRegistryKey().getValue();
    }

    private static boolean isPlayerInDimension(ServerPlayerEntity player, Identifier dimension) {
        return player.getWorld().getRegistryKey().getValue().equals(dimension);
    }

    private static float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }

    private static void broadcastToAll(MinecraftServer server, Identifier channel, BufWriter writer) {
        for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
            PacketByteBuf buf = PacketByteBufs.create();
            writer.write(buf);
            ServerPlayNetworking.send(player, channel, buf);
        }
    }

    private static void broadcastToDimension(
            MinecraftServer server, Identifier dimension, Identifier channel, BufWriter writer) {
        for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
            if (!isPlayerInDimension(player, dimension)) continue;
            PacketByteBuf buf = PacketByteBufs.create();
            writer.write(buf);
            ServerPlayNetworking.send(player, channel, buf);
        }
    }

    @FunctionalInterface
    private interface BufWriter {
        void write(PacketByteBuf buf);
    }

    record PendingSync(Set<UUID> waitingPlayers, long startedAtMs, Identifier dimension) {}

    record DueSync(UUID sessionUUID, PendingSync state, boolean timedOut) {}

    record SessionClaim(Identifier dimension, UUID speakerOwner, Set<BlockPos> positions) {}

    record OwnerListCache(
            Identifier dimension, long createdAtMs, UUID placementOwner, List<ModMessages.SpeakerOwner> owners) {}
}
