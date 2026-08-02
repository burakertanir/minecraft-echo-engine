package com.audiophilecraft.network;

import static com.audiophilecraft.network.ModMessages.C2S_CHANNEL_MASK;
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
import static com.audiophilecraft.network.ModMessages.C2S_UPDATE_POWER;
import static com.audiophilecraft.network.ModMessages.C2S_UPDATE_SPEAKER_SHIFT;
import static com.audiophilecraft.network.ModMessages.C2S_UPDATE_TILT;
import static com.audiophilecraft.network.ModMessages.S2C_PLAY_TRACK;
import static com.audiophilecraft.network.ModMessages.S2C_PLAY_URL;
import static com.audiophilecraft.network.ModMessages.S2C_PREP_SEEK;
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
import com.audiophilecraft.registry.SpeakerRegistry;
import com.audiophilecraft.sound.SpeakerClusterer;
import com.audiophilecraft.sound.SpeakerPlaybackData;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
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
    private static final long SYNC_TIMEOUT_MS = 30_000L;
    private static final int SYNC_MAINTENANCE_INTERVAL_TICKS = 20;

    private static final ConcurrentHashMap<UUID, PendingSync> pendingPlayReady = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<UUID, PendingSync> pendingSeekReady = new ConcurrentHashMap<>();
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
        for (var pending : pendingPlayReady.entrySet()) {
            PendingSync state = pending.getValue();
            if (now - state.startedAtMs() <= SYNC_TIMEOUT_MS) continue;
            if (!pendingPlayReady.remove(pending.getKey(), state)) continue;

            AudiophileCraft.LOGGER.warn(
                    "Playback sync timed out for session {}; starting without {} client(s).",
                    pending.getKey(),
                    state.waitingPlayers().size());
            broadcastToAll(server, S2C_START_PLAYBACK, buf -> buf.writeUuid(pending.getKey()));
        }
    }

    static void clearPendingSyncs() {
        pendingPlayReady.clear();
        pendingSeekReady.clear();
        syncMaintenanceTicks = 0;
    }

    static void cleanupDisconnectedPlayer(UUID playerUUID, MinecraftServer server) {
        for (PendingSync sync : pendingPlayReady.values()) sync.waitingPlayers().remove(playerUUID);
        for (PendingSync sync : pendingSeekReady.values()) sync.waitingPlayers().remove(playerUUID);

        long now = System.currentTimeMillis();
        pendingPlayReady.entrySet().removeIf(entry -> {
            PendingSync sync = entry.getValue();
            if (sync.waitingPlayers().isEmpty() || now - sync.startedAtMs() > SYNC_TIMEOUT_MS) {
                broadcastToAll(server, S2C_START_PLAYBACK, buf -> buf.writeUuid(entry.getKey()));
                return true;
            }
            return false;
        });
        pendingSeekReady.entrySet().removeIf(entry -> {
            PendingSync sync = entry.getValue();
            if (sync.waitingPlayers().isEmpty() || now - sync.startedAtMs() > SYNC_TIMEOUT_MS) {
                broadcastToAll(server, S2C_SYNC_SEEK, buf -> buf.writeUuid(entry.getKey()));
                return true;
            }
            return false;
        });
    }

    static void sendPlayTrack(
            ServerPlayerEntity player,
            UUID ownerUUID,
            String trackId,
            List<SpeakerPlaybackData> speakers,
            float power,
            float inputGain) {
        PacketByteBuf buf = createPlaybackBuffer(ownerUUID, trackId, speakers, power, inputGain);
        ServerPlayNetworking.send(player, S2C_PLAY_TRACK, buf);
    }

    static void sendPlayUrl(
            ServerPlayerEntity player,
            UUID ownerUUID,
            String url,
            List<SpeakerPlaybackData> speakers,
            float power,
            float inputGain) {
        PacketByteBuf buf = createPlaybackBuffer(ownerUUID, url, speakers, power, inputGain);
        ServerPlayNetworking.send(player, S2C_PLAY_URL, buf);
    }

    private static void registerPlaybackPackets() {
        ServerPlayNetworking.registerGlobalReceiver(C2S_PLAY_URL, (server, player, handler, buf, responseSender) -> {
            int handOrdinal = buf.readInt();
            String url = buf.readString(2048);
            if (!ModMessages.isValidHandOrdinal(handOrdinal) || !ModMessages.isValidAudioUrl(url)) return;
            server.execute(() -> {
                ItemStack stack = getTabletStack(player, handOrdinal);
                if (!(stack.getItem() instanceof AmplifierTabletItem)) return;

                UUID ownerUUID = player.getUuid();
                List<SpeakerPlaybackData> speakers =
                        SpeakerRegistry.findPlaybackDataByOwner(player.getWorld(), ownerUUID);
                float power = AmplifierTabletItem.getSpeakerPower(stack);
                float inputGain = AmplifierTabletItem.getInputGain(stack);
                Set<UUID> waitingPlayers = ConcurrentHashMap.newKeySet();
                for (ServerPlayerEntity onlinePlayer : server.getPlayerManager().getPlayerList()) {
                    sendPlayUrl(onlinePlayer, ownerUUID, url, speakers, power, inputGain);
                    waitingPlayers.add(onlinePlayer.getUuid());
                }
                pendingPlayReady.put(ownerUUID, new PendingSync(waitingPlayers, System.currentTimeMillis()));
            });
        });

        ServerPlayNetworking.registerGlobalReceiver(
                C2S_PLAYBACK_READY, (server, player, handler, buf, responseSender) -> {
                    UUID sessionUUID = buf.readUuid();
                    server.execute(() -> completePendingSync(
                            pendingPlayReady, sessionUUID, player.getUuid(), server, S2C_START_PLAYBACK));
                });
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
            float sanitizedDb = clamp(db, -12.0f, 12.0f);
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
                Set<UUID> waitingPlayers = ConcurrentHashMap.newKeySet();
                for (ServerPlayerEntity onlinePlayer : server.getPlayerManager().getPlayerList()) {
                    PacketByteBuf prepBuf = PacketByteBufs.create();
                    prepBuf.writeUuid(sessionUUID);
                    prepBuf.writeFloat(targetTime);
                    ServerPlayNetworking.send(onlinePlayer, S2C_PREP_SEEK, prepBuf);
                    waitingPlayers.add(onlinePlayer.getUuid());
                }
                pendingSeekReady.put(sessionUUID, new PendingSync(waitingPlayers, System.currentTimeMillis()));
            });
        });

        ServerPlayNetworking.registerGlobalReceiver(C2S_SEEK_READY, (server, player, handler, buf, responseSender) -> {
            UUID sessionUUID = buf.readUuid();
            server.execute(
                    () -> completePendingSync(pendingSeekReady, sessionUUID, player.getUuid(), server, S2C_SYNC_SEEK));
        });

        ServerPlayNetworking.registerGlobalReceiver(C2S_STOP_AUDIO, (server, player, handler, buf, responseSender) -> {
            server.execute(
                    () -> broadcastToAll(server, S2C_STOP_AUDIO, syncBuf -> syncBuf.writeUuid(player.getUuid())));
        });

        ServerPlayNetworking.registerGlobalReceiver(
                C2S_TOGGLE_PAUSE, (server, player, handler, buf, responseSender) -> {
                    server.execute(() ->
                            broadcastToAll(server, S2C_TOGGLE_PAUSE, syncBuf -> syncBuf.writeUuid(player.getUuid())));
                });
    }

    private static void completePendingSync(
            ConcurrentHashMap<UUID, PendingSync> pendingSyncs,
            UUID sessionUUID,
            UUID playerUUID,
            MinecraftServer server,
            Identifier completionChannel) {
        PendingSync sync = pendingSyncs.get(sessionUUID);
        if (sync == null) return;

        sync.waitingPlayers().remove(playerUUID);
        if (sync.waitingPlayers().isEmpty() || isSyncTimedOut(sync)) {
            pendingSyncs.remove(sessionUUID);
            broadcastToAll(server, completionChannel, buf -> buf.writeUuid(sessionUUID));
        }
    }

    private static void updateClusterChannelMask(
            MinecraftServer server, ServerPlayerEntity player, BlockPos position, int mask) {
        if (!canModifySpeaker(player, position)) return;

        List<BlockPos> ownedSpeakers =
                SpeakerRegistry.findSpeakersByOwner(player.getWorld().getRegistryKey(), player.getUuid());
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
        return owner == null || owner.equals(player.getUuid());
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

    private static float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }

    private static PacketByteBuf createPlaybackBuffer(
            UUID ownerUUID, String trackOrUrl, List<SpeakerPlaybackData> speakers, float power, float inputGain) {
        PacketByteBuf buf = PacketByteBufs.create();
        buf.writeUuid(ownerUUID);
        buf.writeString(trackOrUrl);
        buf.writeFloat(power);
        buf.writeFloat(inputGain);
        buf.writeInt(speakers.size());
        for (SpeakerPlaybackData speaker : speakers) {
            buf.writeBlockPos(speaker.position());
            buf.writeString(speaker.speakerType(), 16);
            buf.writeInt(speaker.facing().getId());
            buf.writeInt(speaker.verticalTiltDeg());
            buf.writeInt(speaker.sampleShiftMs());
            buf.writeInt(speaker.channelMask());
        }
        return buf;
    }

    private static void broadcastToAll(MinecraftServer server, Identifier channel, BufWriter writer) {
        for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
            PacketByteBuf buf = PacketByteBufs.create();
            writer.write(buf);
            ServerPlayNetworking.send(player, channel, buf);
        }
    }

    @FunctionalInterface
    private interface BufWriter {
        void write(PacketByteBuf buf);
    }

    private record PendingSync(Set<UUID> waitingPlayers, long startedAtMs) {}
}
