package com.audiophilecraft.network;

import com.audiophilecraft.AudiophileCraft;
import com.audiophilecraft.item.AmplifierTabletItem;
import com.audiophilecraft.registry.SpeakerRegistry;
import com.audiophilecraft.sound.SpeakerPlaybackData;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.item.ItemStack;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.util.Hand;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;

public class ModMessages {
    private static final int MAX_SPEAKERS_PER_PACKET = 4096;
    private static final int EQ_BAND_COUNT = 5;
    private static final java.util.Set<String> VALID_SPEAKER_TYPES = java.util.Set.of("normal", "sub", "mid", "line");

    public static final Identifier C2S_REQUEST_PLAY = new Identifier(AudiophileCraft.MOD_ID, "c2s_request_play");
    public static final Identifier C2S_UPDATE_POWER = new Identifier(AudiophileCraft.MOD_ID, "c2s_update_power");
    public static final Identifier S2C_SYNC_POWER = new Identifier(AudiophileCraft.MOD_ID, "s2c_sync_power");
    public static final Identifier C2S_UPDATE_INPUT_GAIN =
            new Identifier(AudiophileCraft.MOD_ID, "c2s_update_input_gain");
    public static final Identifier S2C_SYNC_INPUT_GAIN = new Identifier(AudiophileCraft.MOD_ID, "s2c_sync_input_gain");
    public static final Identifier C2S_UPDATE_SPEAKER_SHIFT =
            new Identifier(AudiophileCraft.MOD_ID, "c2s_update_speaker_shift");
    public static final Identifier C2S_PLAY_URL = new Identifier(AudiophileCraft.MOD_ID, "c2s_play_url");
    public static final Identifier S2C_PLAY_URL = new Identifier(AudiophileCraft.MOD_ID, "s2c_play_url");
    public static final Identifier C2S_UPDATE_TILT = new Identifier(AudiophileCraft.MOD_ID, "c2s_update_tilt");
    public static final Identifier S2C_PLAY_TRACK = new Identifier(AudiophileCraft.MOD_ID, "s2c_play_track");
    public static final Identifier C2S_SEEK_TRACK = new Identifier(AudiophileCraft.MOD_ID, "c2s_seek_track");
    public static final Identifier C2S_UPDATE_EQ = new Identifier(AudiophileCraft.MOD_ID, "c2s_update_eq");
    public static final Identifier S2C_SYNC_EQ = new Identifier(AudiophileCraft.MOD_ID, "s2c_sync_eq");
    public static final Identifier S2C_SEEK_TRACK = new Identifier(AudiophileCraft.MOD_ID, "s2c_seek_track");
    public static final Identifier C2S_UPDATE_EQ_Q = new Identifier(AudiophileCraft.MOD_ID, "c2s_update_eq_q");
    public static final Identifier S2C_SYNC_EQ_Q = new Identifier(AudiophileCraft.MOD_ID, "s2c_sync_eq_q");
    public static final Identifier C2S_UPDATE_MIXER_GAIN =
            new Identifier(AudiophileCraft.MOD_ID, "c2s_update_mixer_gain");
    public static final Identifier S2C_SYNC_MIXER_GAIN = new Identifier(AudiophileCraft.MOD_ID, "s2c_sync_mixer_gain");
    public static final Identifier C2S_STOP_AUDIO = new Identifier(AudiophileCraft.MOD_ID, "c2s_stop_audio");
    public static final Identifier S2C_STOP_AUDIO = new Identifier(AudiophileCraft.MOD_ID, "s2c_stop_audio");
    public static final Identifier C2S_TOGGLE_PAUSE = new Identifier(AudiophileCraft.MOD_ID, "c2s_toggle_pause");
    public static final Identifier S2C_TOGGLE_PAUSE = new Identifier(AudiophileCraft.MOD_ID, "s2c_toggle_pause");
    public static final Identifier C2S_CHANNEL_MASK = new Identifier(AudiophileCraft.MOD_ID, "c2s_channel_mask");
    public static final Identifier S2C_SYNC_CHANNEL_MASK =
            new Identifier(AudiophileCraft.MOD_ID, "s2c_sync_channel_mask");

    // --- Multiplayer Sync: all-players-must-be-ready handshake ---
    public static final Identifier C2S_PLAYBACK_READY = new Identifier(AudiophileCraft.MOD_ID, "c2s_playback_ready");
    public static final Identifier S2C_START_PLAYBACK = new Identifier(AudiophileCraft.MOD_ID, "s2c_start_playback");
    public static final Identifier S2C_PREP_SEEK = new Identifier(AudiophileCraft.MOD_ID, "s2c_prep_seek");
    public static final Identifier C2S_SEEK_READY = new Identifier(AudiophileCraft.MOD_ID, "c2s_seek_ready");
    public static final Identifier S2C_SYNC_SEEK = new Identifier(AudiophileCraft.MOD_ID, "s2c_sync_seek");

    // Server-side tracking: sessionUUID → (readySet, startTimeMs)
    private static final java.util.concurrent.ConcurrentHashMap<
                    java.util.UUID, java.util.AbstractMap.SimpleEntry<java.util.Set<java.util.UUID>, Long>>
            pendingPlayReady = new java.util.concurrent.ConcurrentHashMap<>();
    private static final java.util.concurrent.ConcurrentHashMap<
                    java.util.UUID, java.util.AbstractMap.SimpleEntry<java.util.Set<java.util.UUID>, Long>>
            pendingSeekReady = new java.util.concurrent.ConcurrentHashMap<>();

    private static boolean isValidHandOrdinal(int handOrdinal) {
        return handOrdinal >= 0 && handOrdinal < Hand.values().length;
    }

    private static boolean isFiniteInRange(float value, float min, float max) {
        return Float.isFinite(value) && value >= min && value <= max;
    }

    private static float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }

    private static boolean isValidSpeakerType(String speakerType) {
        return VALID_SPEAKER_TYPES.contains(speakerType);
    }

    private static boolean isValidEqBand(int band) {
        return band >= 0 && band < EQ_BAND_COUNT;
    }

    private static boolean isValidAudioUrl(String value) {
        if (value == null || value.isBlank() || value.length() > 2048) return false;
        try {
            java.net.URI uri = java.net.URI.create(value);
            String scheme = uri.getScheme();
            return uri.getHost() != null
                    && uri.getUserInfo() == null
                    && ("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme));
        } catch (IllegalArgumentException ignored) {
            return false;
        }
    }

    private static List<BlockPos> readSpeakerPositions(PacketByteBuf buf) {
        int count = buf.readInt();
        if (count < 0 || count > MAX_SPEAKERS_PER_PACKET) return null;

        List<BlockPos> speakers = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            speakers.add(buf.readBlockPos());
        }
        return speakers;
    }

    private static List<SpeakerPlaybackData> readSpeakerPlaybackData(PacketByteBuf buf) {
        int count = buf.readInt();
        if (count < 0 || count > MAX_SPEAKERS_PER_PACKET) return null;

        List<SpeakerPlaybackData> speakers = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            BlockPos position = buf.readBlockPos();
            String speakerType = buf.readString(16);
            int directionId = buf.readInt();
            int verticalTilt = buf.readInt();
            int sampleShift = buf.readInt();
            int channelMask = buf.readInt();
            if (!isValidSpeakerType(speakerType)
                    || directionId < 0
                    || directionId >= net.minecraft.util.math.Direction.values().length
                    || verticalTilt < -70
                    || verticalTilt > 70
                    || sampleShift < 0
                    || sampleShift > 30
                    || channelMask < 0
                    || channelMask > 2) return null;
            speakers.add(new SpeakerPlaybackData(
                    position,
                    speakerType,
                    net.minecraft.util.math.Direction.byId(directionId),
                    verticalTilt,
                    sampleShift,
                    channelMask));
        }
        return speakers;
    }
    /** Check if timeout has passed for a pending sync (30s) */
    private static boolean isSyncTimedOut(
            java.util.AbstractMap.SimpleEntry<java.util.Set<java.util.UUID>, Long> entry) {
        return System.currentTimeMillis() - entry.getValue() > 30_000;
    }

    /** Clean up pending sync entries when a player disconnects */
    public static void cleanupDisconnectedPlayer(
            java.util.UUID playerUUID, net.minecraft.server.MinecraftServer server) {
        // Remove disconnected player from all pending sets
        for (var entry : pendingPlayReady.entrySet()) entry.getValue().getKey().remove(playerUUID);
        for (var entry : pendingSeekReady.entrySet()) entry.getValue().getKey().remove(playerUUID);
        // If a set became empty or timed out, trigger immediately
        long now = System.currentTimeMillis();
        pendingPlayReady.entrySet().removeIf(e -> {
            if (e.getValue().getKey().isEmpty() || now - e.getValue().getValue() > 30_000) {
                broadcastToAll(server, S2C_START_PLAYBACK, buf -> buf.writeUuid(e.getKey()));
                return true;
            }
            return false;
        });
        pendingSeekReady.entrySet().removeIf(e -> {
            if (e.getValue().getKey().isEmpty() || now - e.getValue().getValue() > 30_000) {
                broadcastToAll(server, S2C_SYNC_SEEK, buf -> buf.writeUuid(e.getKey()));
                return true;
            }
            return false;
        });
    }

    /** Check if all online players have reported ready for a given session tracking map */
    private static boolean allPlayersReady(
            net.minecraft.server.MinecraftServer server, java.util.Set<java.util.UUID> readySet) {
        int online = 0;
        for (net.minecraft.server.network.ServerPlayerEntity p :
                server.getPlayerManager().getPlayerList()) {
            if (readySet.contains(p.getUuid())) online++;
        }
        return server.getPlayerManager().getPlayerList().size() <= readySet.size();
    }

    /** Helper: get the tablet ItemStack from the player's hand ordinal */
    private static ItemStack getTabletStack(net.minecraft.server.network.ServerPlayerEntity player, int handOrdinal) {
        if (!isValidHandOrdinal(handOrdinal)) return ItemStack.EMPTY;
        return player.getStackInHand(Hand.values()[handOrdinal]);
    }

    /** Helper: get the tablet ItemStack from either hand (for packets without hand ordinal) */
    private static ItemStack getTabletStack(net.minecraft.server.network.ServerPlayerEntity player) {
        ItemStack main = player.getMainHandStack();
        if (main.getItem() instanceof AmplifierTabletItem) return main;
        ItemStack off = player.getOffHandStack();
        if (off.getItem() instanceof AmplifierTabletItem) return off;
        return ItemStack.EMPTY;
    }

    /**
     * MULTIPLAYER-SAFE broadcast helper.
     * Creates a FRESH PacketByteBuf for each player to avoid Netty reader index
     * corruption when the same buffer is sent to multiple players asynchronously.
     */
    @FunctionalInterface
    private interface BufWriter {
        void write(PacketByteBuf buf);
    }

    private static void broadcastToAll(
            net.minecraft.server.MinecraftServer server, Identifier channel, BufWriter writer) {
        for (net.minecraft.server.network.ServerPlayerEntity nearby :
                server.getPlayerManager().getPlayerList()) {
            PacketByteBuf buf = PacketByteBufs.create();
            writer.write(buf);
            ServerPlayNetworking.send(nearby, channel, buf);
        }
    }

    public static void registerC2SPackets() {
        // Play test track — finds only the PLAYER'S OWN speakers
        ServerPlayNetworking.registerGlobalReceiver(
                C2S_REQUEST_PLAY, (server, player, handler, buf, responseSender) -> {
                    int handOrdinal = buf.readInt();
                    server.execute(() -> {
                        ItemStack stack = getTabletStack(player, handOrdinal);
                        if (stack.getItem() instanceof AmplifierTabletItem) {
                            String testTrackId = "music/test_track";
                            UUID ownerUUID = player.getUuid();
                            // Only find speakers owned by this player, in their dimension
                            List<SpeakerPlaybackData> speakers =
                                    SpeakerRegistry.findPlaybackDataByOwner(player.getWorld(), ownerUUID);
                            float power = AmplifierTabletItem.getSpeakerPower(stack);
                            float inputGain = AmplifierTabletItem.getInputGain(stack);
                            // Broadcast to all online players so everyone hears the music
                            for (net.minecraft.server.network.ServerPlayerEntity nearby :
                                    server.getPlayerManager().getPlayerList()) {
                                sendPlayTrack(nearby, ownerUUID, testTrackId, speakers, power, inputGain);
                            }
                        }
                    });
                });

        // URL-based play — finds only the PLAYER'S OWN speakers
        ServerPlayNetworking.registerGlobalReceiver(C2S_PLAY_URL, (server, player, handler, buf, responseSender) -> {
            int handOrdinal = buf.readInt();
            String url = buf.readString(2048);
            if (!isValidHandOrdinal(handOrdinal) || !isValidAudioUrl(url)) return;
            server.execute(() -> {
                ItemStack stack = getTabletStack(player, handOrdinal);
                if (stack.getItem() instanceof AmplifierTabletItem) {
                    UUID ownerUUID = player.getUuid();
                    List<SpeakerPlaybackData> speakers =
                            SpeakerRegistry.findPlaybackDataByOwner(player.getWorld(), ownerUUID);
                    float power = AmplifierTabletItem.getSpeakerPower(stack);
                    float inputGain = AmplifierTabletItem.getInputGain(stack);
                    // Init sync tracking for all online players
                    java.util.Set<UUID> readySet = java.util.concurrent.ConcurrentHashMap.newKeySet();
                    for (net.minecraft.server.network.ServerPlayerEntity p :
                            server.getPlayerManager().getPlayerList()) {
                        sendPlayUrl(p, ownerUUID, url, speakers, power, inputGain);
                        readySet.add(p.getUuid());
                    }
                    pendingPlayReady.put(
                            ownerUUID, new java.util.AbstractMap.SimpleEntry<>(readySet, System.currentTimeMillis()));
                }
            });
        });

        // Client confirms pre-buffer complete → check if all ready → broadcast start
        ServerPlayNetworking.registerGlobalReceiver(
                C2S_PLAYBACK_READY, (server, player, handler, buf, responseSender) -> {
                    UUID sessionUUID = buf.readUuid();
                    server.execute(() -> {
                        var entry = pendingPlayReady.get(sessionUUID);
                        if (entry == null) return;
                        java.util.Set<UUID> readySet = entry.getKey();
                        readySet.remove(player.getUuid());
                        if (readySet.isEmpty() || isSyncTimedOut(entry)) {
                            pendingPlayReady.remove(sessionUUID);
                            broadcastToAll(server, S2C_START_PLAYBACK, syncBuf -> {
                                syncBuf.writeUuid(sessionUUID);
                            });
                        }
                    });
                });

        // Update speaker power — synced to all players
        ServerPlayNetworking.registerGlobalReceiver(
                C2S_UPDATE_POWER, (server, player, handler, buf, responseSender) -> {
                    int handOrdinal = buf.readInt();
                    float power = buf.readFloat();
                    if (!Float.isFinite(power)) return;
                    float sanitizedPower = clamp(power, 0.1f, 10.0f);
                    server.execute(() -> {
                        ItemStack stack = getTabletStack(player, handOrdinal);
                        if (stack.getItem() instanceof AmplifierTabletItem) {
                            AmplifierTabletItem.setSpeakerPower(stack, sanitizedPower);
                            UUID senderUUID = player.getUuid();
                            broadcastToAll(server, S2C_SYNC_POWER, syncBuf -> {
                                syncBuf.writeUuid(senderUUID);
                                syncBuf.writeInt(handOrdinal);
                                syncBuf.writeFloat(sanitizedPower);
                            });
                        }
                    });
                });

        // Update input gain — synced to all players
        ServerPlayNetworking.registerGlobalReceiver(
                C2S_UPDATE_INPUT_GAIN, (server, player, handler, buf, responseSender) -> {
                    int handOrdinal = buf.readInt();
                    float gain = buf.readFloat();
                    if (!Float.isFinite(gain)) return;
                    float sanitizedGain = clamp(gain, 0.0f, 3.0f);
                    server.execute(() -> {
                        ItemStack stack = getTabletStack(player, handOrdinal);
                        if (stack.getItem() instanceof AmplifierTabletItem) {
                            AmplifierTabletItem.setInputGain(stack, sanitizedGain);
                            UUID senderUUID = player.getUuid();
                            broadcastToAll(server, S2C_SYNC_INPUT_GAIN, syncBuf -> {
                                syncBuf.writeUuid(senderUUID);
                                syncBuf.writeInt(handOrdinal);
                                syncBuf.writeFloat(sanitizedGain);
                            });
                        }
                    });
                });

        // EQ update — synced to all players AND persisted to tablet NBT
        ServerPlayNetworking.registerGlobalReceiver(C2S_UPDATE_EQ, (server, player, handler, buf, responseSender) -> {
            String speakerType = buf.readString(16);
            int band = buf.readInt();
            float db = buf.readFloat();
            if (!isValidSpeakerType(speakerType) || !isValidEqBand(band) || !Float.isFinite(db)) return;
            float sanitizedDb = clamp(db, -12.0f, 12.0f);
            server.execute(() -> {
                // Persist to tablet NBT
                ItemStack stack = getTabletStack(player);
                if (stack.getItem() instanceof AmplifierTabletItem) {
                    AmplifierTabletItem.setEqDb(stack, speakerType, band, sanitizedDb);
                }
                UUID ownerUUID = player.getUuid();
                broadcastToAll(server, S2C_SYNC_EQ, syncBuf -> {
                    syncBuf.writeUuid(ownerUUID);
                    syncBuf.writeString(speakerType);
                    syncBuf.writeInt(band);
                    syncBuf.writeFloat(sanitizedDb);
                });
            });
        });

        // EQ Q (bandwidth) update — synced to all players AND persisted to tablet NBT
        ServerPlayNetworking.registerGlobalReceiver(C2S_UPDATE_EQ_Q, (server, player, handler, buf, responseSender) -> {
            String speakerType = buf.readString(16);
            int band = buf.readInt();
            float q = buf.readFloat();
            if (!isValidSpeakerType(speakerType) || !isValidEqBand(band) || !Float.isFinite(q)) return;
            float sanitizedQ = clamp(q, 0.1f, 10.0f);
            server.execute(() -> {
                // Persist to tablet NBT
                ItemStack stack = getTabletStack(player);
                if (stack.getItem() instanceof AmplifierTabletItem) {
                    AmplifierTabletItem.setEqQ(stack, speakerType, band, sanitizedQ);
                }
                UUID ownerUUID = player.getUuid();
                broadcastToAll(server, S2C_SYNC_EQ_Q, syncBuf -> {
                    syncBuf.writeUuid(ownerUUID);
                    syncBuf.writeString(speakerType);
                    syncBuf.writeInt(band);
                    syncBuf.writeFloat(sanitizedQ);
                });
            });
        });

        // Mixer Gain (volume fader per speaker type) — synced to all players AND persisted to tablet NBT
        ServerPlayNetworking.registerGlobalReceiver(
                C2S_UPDATE_MIXER_GAIN, (server, player, handler, buf, responseSender) -> {
                    String speakerType = buf.readString(16);
                    float gain = buf.readFloat();
                    if (!isValidSpeakerType(speakerType) || !Float.isFinite(gain)) return;
                    float sanitizedGain = clamp(gain, 0.0f, 1.0f);
                    server.execute(() -> {
                        // Persist to tablet NBT
                        ItemStack stack = getTabletStack(player);
                        if (stack.getItem() instanceof AmplifierTabletItem) {
                            AmplifierTabletItem.setMixerGain(stack, speakerType, sanitizedGain);
                        }
                        UUID ownerUUID = player.getUuid();
                        broadcastToAll(server, S2C_SYNC_MIXER_GAIN, syncBuf -> {
                            syncBuf.writeUuid(ownerUUID);
                            syncBuf.writeString(speakerType);
                            syncBuf.writeFloat(sanitizedGain);
                        });
                    });
                });

        // Speaker shift — OWNERSHIP PROTECTED
        ServerPlayNetworking.registerGlobalReceiver(
                C2S_UPDATE_SPEAKER_SHIFT, (server, player, handler, buf, responseSender) -> {
                    BlockPos pos = buf.readBlockPos();
                    int shift = buf.readInt();
                    if (shift < 0 || shift > 30) return;
                    server.execute(() -> {
                        // Ownership check: only the speaker owner can modify it
                        UUID speakerOwner =
                                SpeakerRegistry.getOwner(player.getWorld().getRegistryKey(), pos);
                        if (speakerOwner != null && !speakerOwner.equals(player.getUuid())) {
                            return; // Not the owner — reject silently
                        }
                        net.minecraft.block.entity.BlockEntity be =
                                player.getWorld().getBlockEntity(pos);
                        if (be instanceof com.audiophilecraft.block.entity.SpeakerBlockEntity speaker) {
                            speaker.setSampleShift(shift);
                        }
                    });
                });

        // Speaker tilt — OWNERSHIP PROTECTED
        ServerPlayNetworking.registerGlobalReceiver(C2S_UPDATE_TILT, (server, player, handler, buf, responseSender) -> {
            BlockPos pos = buf.readBlockPos();
            int tilt = buf.readInt();
            if (tilt < -70 || tilt > 70) return;
            server.execute(() -> {
                // Ownership check: only the speaker owner can modify it
                UUID speakerOwner = SpeakerRegistry.getOwner(player.getWorld().getRegistryKey(), pos);
                if (speakerOwner != null && !speakerOwner.equals(player.getUuid())) {
                    return; // Not the owner — reject silently
                }
                net.minecraft.block.entity.BlockEntity be = player.getWorld().getBlockEntity(pos);
                if (be instanceof com.audiophilecraft.block.entity.SpeakerBlockEntity speaker) {
                    speaker.setVerticalTilt(tilt);
                }
            });
        });

        // Seek Track — broadcast prep-seek to all, init sync tracking
        ServerPlayNetworking.registerGlobalReceiver(C2S_SEEK_TRACK, (server, player, handler, buf, responseSender) -> {
            float targetTime = buf.readFloat();
            if (!Float.isFinite(targetTime) || targetTime < 0.0f) return;
            server.execute(() -> {
                ItemStack mainStack = player.getMainHandStack();
                ItemStack offStack = player.getOffHandStack();
                if (!(mainStack.getItem() instanceof AmplifierTabletItem)
                        && !(offStack.getItem() instanceof AmplifierTabletItem)) {
                    return;
                }
                UUID senderUUID = player.getUuid();
                // Init sync tracking
                java.util.Set<UUID> readySet = java.util.concurrent.ConcurrentHashMap.newKeySet();
                for (net.minecraft.server.network.ServerPlayerEntity p :
                        server.getPlayerManager().getPlayerList()) {
                    // Send PREP_SEEK instead of direct S2C_SEEK_TRACK
                    PacketByteBuf prepBuf = PacketByteBufs.create();
                    prepBuf.writeUuid(senderUUID);
                    prepBuf.writeFloat(targetTime);
                    ServerPlayNetworking.send(p, S2C_PREP_SEEK, prepBuf);
                    readySet.add(p.getUuid());
                }
                pendingSeekReady.put(
                        senderUUID, new java.util.AbstractMap.SimpleEntry<>(readySet, System.currentTimeMillis()));
            });
        });

        // Client confirms seek ready → check if all ready → broadcast sync seek
        ServerPlayNetworking.registerGlobalReceiver(C2S_SEEK_READY, (server, player, handler, buf, responseSender) -> {
            UUID sessionUUID = buf.readUuid();
            server.execute(() -> {
                var entry = pendingSeekReady.get(sessionUUID);
                if (entry == null) return;
                java.util.Set<UUID> readySet = entry.getKey();
                readySet.remove(player.getUuid());
                if (readySet.isEmpty() || isSyncTimedOut(entry)) {
                    pendingSeekReady.remove(sessionUUID);
                    broadcastToAll(server, S2C_SYNC_SEEK, syncBuf -> {
                        syncBuf.writeUuid(sessionUUID);
                    });
                }
            });
        });
        // Stop Audio - broadcast to all players
        ServerPlayNetworking.registerGlobalReceiver(C2S_STOP_AUDIO, (server, player, handler, buf, responseSender) -> {
            server.execute(() -> {
                UUID ownerUUID = player.getUuid();
                broadcastToAll(server, S2C_STOP_AUDIO, syncBuf -> {
                    syncBuf.writeUuid(ownerUUID);
                });
            });
        });

        // Channel mask update — OWNERSHIP PROTECTED, updates server-side BE + broadcasts to all players
        ServerPlayNetworking.registerGlobalReceiver(
                C2S_CHANNEL_MASK, (server, player, handler, buf, responseSender) -> {
                    BlockPos pos = buf.readBlockPos();
                    int mask = buf.readInt();
                    if (mask < 0 || mask > 2) return;
                    server.execute(() -> {
                        UUID speakerOwner =
                                SpeakerRegistry.getOwner(player.getWorld().getRegistryKey(), pos);
                        if (speakerOwner != null && !speakerOwner.equals(player.getUuid())) return;
                        // Find all speakers owned by this player in this dimension
                        java.util.List<BlockPos> allOwned = SpeakerRegistry.findSpeakersByOwner(
                                player.getWorld().getRegistryKey(), player.getUuid());
                        // Cluster them
                        java.util.List<java.util.List<BlockPos>> clusters =
                                com.audiophilecraft.sound.SpeakerClusterer.clusterSpeakers(allOwned);
                        // Find which cluster the clicked speaker belongs to
                        for (java.util.List<BlockPos> cluster : clusters) {
                            boolean found = false;
                            for (BlockPos p : cluster) {
                                if (p.equals(pos)) {
                                    found = true;
                                    break;
                                }
                            }
                            if (found) {
                                // Update ALL BlockEntities in this cluster
                                for (BlockPos p : cluster) {
                                    net.minecraft.block.entity.BlockEntity be =
                                            player.getWorld().getBlockEntity(p);
                                    if (be instanceof com.audiophilecraft.block.entity.SpeakerBlockEntity speaker) {
                                        speaker.setChannelMask(mask);
                                    }
                                }
                                // Broadcast channel mask change to ALL players (including sender)
                                // so StreamSource objects are updated on every client
                                java.util.List<BlockPos> finalCluster = cluster;
                                broadcastToAll(server, S2C_SYNC_CHANNEL_MASK, syncBuf -> {
                                    syncBuf.writeUuid(player.getUuid());
                                    syncBuf.writeInt(mask);
                                    syncBuf.writeInt(finalCluster.size());
                                    for (BlockPos cp : finalCluster) {
                                        syncBuf.writeBlockPos(cp);
                                    }
                                });
                                break;
                            }
                        }
                    });
                });

        // Toggle Pause Audio - broadcast to all players
        ServerPlayNetworking.registerGlobalReceiver(
                C2S_TOGGLE_PAUSE, (server, player, handler, buf, responseSender) -> {
                    server.execute(() -> {
                        UUID ownerUUID = player.getUuid();
                        broadcastToAll(server, S2C_TOGGLE_PAUSE, syncBuf -> {
                            syncBuf.writeUuid(ownerUUID);
                        });
                    });
                });
        // Disconnect cleanup: remove from any pending sync sets
        net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents.DISCONNECT.register((handler2, server2) -> {
            cleanupDisconnectedPlayer(handler2.getPlayer().getUuid(), server2);
        });
    }

    public static void registerS2CPackets() {
        ClientPlayNetworking.registerGlobalReceiver(S2C_PLAY_TRACK, (client, handler, buf, responseSender) -> {
            UUID sessionUUID = buf.readUuid();
            String trackId = buf.readString(256);
            float power = buf.readFloat();
            float inputGain = buf.readFloat();
            List<SpeakerPlaybackData> speakers = readSpeakerPlaybackData(buf);
            if (speakers == null
                    || trackId.isBlank()
                    || !isFiniteInRange(power, 0.1f, 10.0f)
                    || !isFiniteInRange(inputGain, 0.0f, 3.0f)) return;
            client.execute(() -> {
                com.audiophilecraft.sound.AudioEngine engine = com.audiophilecraft.sound.AudioEngine.getInstance();
                engine.playTrackWithSpeakerData(sessionUUID, trackId, speakers, power, inputGain);
            });
        });

        ClientPlayNetworking.registerGlobalReceiver(S2C_PLAY_URL, (client, handler, buf, responseSender) -> {
            UUID sessionUUID = buf.readUuid();
            String url = buf.readString(2048);
            float power = buf.readFloat();
            float inputGain = buf.readFloat();
            List<SpeakerPlaybackData> speakers = readSpeakerPlaybackData(buf);
            if (speakers == null
                    || !isValidAudioUrl(url)
                    || !isFiniteInRange(power, 0.1f, 10.0f)
                    || !isFiniteInRange(inputGain, 0.0f, 3.0f)) return;
            client.execute(() -> {
                com.audiophilecraft.sound.AudioEngine engine = com.audiophilecraft.sound.AudioEngine.getInstance();
                // Preload with sync: don't start playing yet, send ready when buffer complete
                engine.playFromUrl(sessionUUID, url, speakers, power, inputGain, false, (loadedUUID) -> {
                    // Pre-buffer done → tell server we're ready
                    PacketByteBuf readyBuf = PacketByteBufs.create();
                    readyBuf.writeUuid(loadedUUID);
                    ClientPlayNetworking.send(C2S_PLAYBACK_READY, readyBuf);
                });
            });
        });

        // S2C: All players ready → start playback simultaneously
        ClientPlayNetworking.registerGlobalReceiver(S2C_START_PLAYBACK, (client, handler, buf, responseSender) -> {
            UUID sessionUUID = buf.readUuid();
            client.execute(() -> {
                com.audiophilecraft.sound.AudioEngine engine = com.audiophilecraft.sound.AudioEngine.getInstance();
                engine.startSessionPlayback(sessionUUID);
            });
        });

        // S2C: Prepare seek (seek + pause, then wait for all-ready signal)
        ClientPlayNetworking.registerGlobalReceiver(S2C_PREP_SEEK, (client, handler, buf, responseSender) -> {
            UUID sessionUUID = buf.readUuid();
            float targetTime = buf.readFloat();
            if (!Float.isFinite(targetTime) || targetTime < 0.0f) return;
            client.execute(() -> {
                com.audiophilecraft.sound.AudioEngine engine = com.audiophilecraft.sound.AudioEngine.getInstance();
                engine.seekForSession(sessionUUID, targetTime);
                // Notify server we're done seeking
                PacketByteBuf readyBuf = PacketByteBufs.create();
                readyBuf.writeUuid(sessionUUID);
                readyBuf.writeFloat(targetTime);
                ClientPlayNetworking.send(C2S_SEEK_READY, readyBuf);
            });
        });

        // S2C: All players have seeked → resume (already seeked locally, this just syncs the tick)
        ClientPlayNetworking.registerGlobalReceiver(S2C_SYNC_SEEK, (client, handler, buf, responseSender) -> {
            // All players seeked at the same position — nothing extra to do here
            // (the seek was already applied in PREP_SEEK)
        });

        ClientPlayNetworking.registerGlobalReceiver(S2C_SYNC_INPUT_GAIN, (client, handler, buf, responseSender) -> {
            UUID sessionUUID = buf.readUuid();
            int handOrdinal = buf.readInt();
            float gain = buf.readFloat();
            if (!isValidHandOrdinal(handOrdinal) || !isFiniteInRange(gain, 0.0f, 3.0f)) return;
            client.execute(() -> {
                boolean isSelf = client.player != null && sessionUUID.equals(client.player.getUuid());
                if (isSelf) {
                    if (client.currentScreen instanceof com.audiophilecraft.client.screen.AmplifierScreen screen) {
                        screen.updateInputGain(gain);
                    }
                    return; // local slider already applied — skip to avoid echo race
                }
                com.audiophilecraft.sound.AudioEngine.getInstance().updateInputGainForSession(sessionUUID, gain);
            });
        });

        ClientPlayNetworking.registerGlobalReceiver(S2C_SYNC_POWER, (client, handler, buf, responseSender) -> {
            UUID sessionUUID = buf.readUuid();
            int handOrdinal = buf.readInt();
            float power = buf.readFloat();
            if (!isValidHandOrdinal(handOrdinal) || !isFiniteInRange(power, 0.1f, 10.0f)) return;
            client.execute(() -> {
                boolean isSelf = client.player != null && sessionUUID.equals(client.player.getUuid());
                if (isSelf) {
                    if (client.currentScreen instanceof com.audiophilecraft.client.screen.AmplifierScreen screen) {
                        screen.updateSpeakerPower(power);
                    }
                    return; // local slider already applied — skip to avoid echo race
                }
                com.audiophilecraft.sound.AudioEngine.getInstance().updatePowerForSession(sessionUUID, power);
            });
        });

        // Track Timeline Seek Sync
        ClientPlayNetworking.registerGlobalReceiver(S2C_SEEK_TRACK, (client, handler, buf, responseSender) -> {
            UUID sessionUUID = buf.readUuid();
            float targetTime = buf.readFloat();
            if (!Float.isFinite(targetTime) || targetTime < 0.0f) return;
            client.execute(() -> {
                com.audiophilecraft.sound.AudioEngine engine = com.audiophilecraft.sound.AudioEngine.getInstance();
                engine.seekForSession(sessionUUID, targetTime);
            });
        });

        // EQ Sync — scoped to session UUID
        ClientPlayNetworking.registerGlobalReceiver(S2C_SYNC_EQ, (client, handler, buf, responseSender) -> {
            UUID sessionUUID = buf.readUuid();
            String speakerType = buf.readString(16);
            int band = buf.readInt();
            float db = buf.readFloat();
            if (!isValidSpeakerType(speakerType) || !isValidEqBand(band) || !isFiniteInRange(db, -12.0f, 12.0f)) return;
            client.execute(() -> {
                com.audiophilecraft.sound.AudioEngine engine = com.audiophilecraft.sound.AudioEngine.getInstance();
                engine.setEqDbForSession(sessionUUID, speakerType, band, db);
            });
        });

        // EQ Q Sync — scoped to session UUID
        ClientPlayNetworking.registerGlobalReceiver(S2C_SYNC_EQ_Q, (client, handler, buf, responseSender) -> {
            UUID sessionUUID = buf.readUuid();
            String speakerType = buf.readString(16);
            int band = buf.readInt();
            float q = buf.readFloat();
            if (!isValidSpeakerType(speakerType) || !isValidEqBand(band) || !isFiniteInRange(q, 0.1f, 10.0f)) return;
            client.execute(() -> {
                com.audiophilecraft.sound.AudioEngine engine = com.audiophilecraft.sound.AudioEngine.getInstance();
                engine.setEqQForSession(sessionUUID, speakerType, band, q);
            });
        });

        // Mixer Gain Sync — scoped to session UUID
        ClientPlayNetworking.registerGlobalReceiver(S2C_SYNC_MIXER_GAIN, (client, handler, buf, responseSender) -> {
            UUID sessionUUID = buf.readUuid();
            String speakerType = buf.readString(16);
            float gain = buf.readFloat();
            if (!isValidSpeakerType(speakerType) || !isFiniteInRange(gain, 0.0f, 1.0f)) return;
            client.execute(() -> {
                com.audiophilecraft.sound.AudioEngine engine = com.audiophilecraft.sound.AudioEngine.getInstance();
                engine.setMixerGainForSession(sessionUUID, speakerType, gain);
            });
        });

        // Stop Audio Sync - scoped to session UUID
        ClientPlayNetworking.registerGlobalReceiver(S2C_STOP_AUDIO, (client, handler, buf, responseSender) -> {
            UUID sessionUUID = buf.readUuid();
            client.execute(() -> {
                com.audiophilecraft.sound.AudioEngine engine = com.audiophilecraft.sound.AudioEngine.getInstance();
                engine.stopSession(sessionUUID);
            });
        });

        // Toggle Pause Sync - scoped to session UUID
        ClientPlayNetworking.registerGlobalReceiver(S2C_TOGGLE_PAUSE, (client, handler, buf, responseSender) -> {
            UUID sessionUUID = buf.readUuid();
            client.execute(() -> {
                com.audiophilecraft.sound.AudioEngine engine = com.audiophilecraft.sound.AudioEngine.getInstance();
                engine.toggleManualPause(sessionUUID);
            });
        });

        // Channel Mask Sync — update StreamSource objects on all clients
        ClientPlayNetworking.registerGlobalReceiver(S2C_SYNC_CHANNEL_MASK, (client, handler, buf, responseSender) -> {
            UUID senderUUID = buf.readUuid();
            int mask = buf.readInt();
            java.util.List<BlockPos> cluster = readSpeakerPositions(buf);
            if (cluster == null || mask < 0 || mask > 2) return;
            client.execute(() -> {
                // Skip if sender is self (already applied locally via SpeakerScreen)
                if (client.player != null && senderUUID.equals(client.player.getUuid())) return;
                com.audiophilecraft.sound.AudioEngine engine = com.audiophilecraft.sound.AudioEngine.getInstance();
                for (BlockPos p : cluster) {
                    engine.applyChannelMaskToSpeaker(p, mask);
                }
            });
        });
    }

    public static void sendPlayTrack(
            net.minecraft.server.network.ServerPlayerEntity player,
            UUID ownerUUID,
            String trackId,
            List<SpeakerPlaybackData> speakers,
            float power,
            float inputGain) {
        PacketByteBuf buf = PacketByteBufs.create();
        buf.writeUuid(ownerUUID);
        buf.writeString(trackId);
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
        ServerPlayNetworking.send(player, S2C_PLAY_TRACK, buf);
    }

    public static void sendPlayUrl(
            net.minecraft.server.network.ServerPlayerEntity player,
            UUID ownerUUID,
            String url,
            List<SpeakerPlaybackData> speakers,
            float power,
            float inputGain) {
        PacketByteBuf buf = PacketByteBufs.create();
        buf.writeUuid(ownerUUID);
        buf.writeString(url);
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
        ServerPlayNetworking.send(player, S2C_PLAY_URL, buf);
    }
}
