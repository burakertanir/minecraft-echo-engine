package com.audiophilecraft.network;

import com.audiophilecraft.AudiophileCraft;
import com.audiophilecraft.item.AmplifierTabletItem;
import com.audiophilecraft.registry.SpeakerRegistry;
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

    /** Helper: get the tablet ItemStack from the player's hand ordinal */
    private static ItemStack getTabletStack(net.minecraft.server.network.ServerPlayerEntity player, int handOrdinal) {
        Hand hand = Hand.values()[handOrdinal];
        return player.getStackInHand(hand);
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
                            List<BlockPos> speakers = SpeakerRegistry.findSpeakersByOwner(
                                    player.getWorld().getRegistryKey(), ownerUUID);
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
            server.execute(() -> {
                ItemStack stack = getTabletStack(player, handOrdinal);
                if (stack.getItem() instanceof AmplifierTabletItem) {
                    UUID ownerUUID = player.getUuid();
                    // Only find speakers owned by this player, in their dimension
                    List<BlockPos> speakers = SpeakerRegistry.findSpeakersByOwner(
                            player.getWorld().getRegistryKey(), ownerUUID);
                    float power = AmplifierTabletItem.getSpeakerPower(stack);
                    float inputGain = AmplifierTabletItem.getInputGain(stack);
                    // Broadcast to all online players so everyone hears the music
                    for (net.minecraft.server.network.ServerPlayerEntity nearby :
                            server.getPlayerManager().getPlayerList()) {
                        sendPlayUrl(nearby, ownerUUID, url, speakers, power, inputGain);
                    }
                }
            });
        });

        // Update speaker power — synced to all players
        ServerPlayNetworking.registerGlobalReceiver(
                C2S_UPDATE_POWER, (server, player, handler, buf, responseSender) -> {
                    int handOrdinal = buf.readInt();
                    float power = buf.readFloat();
                    server.execute(() -> {
                        ItemStack stack = getTabletStack(player, handOrdinal);
                        if (stack.getItem() instanceof AmplifierTabletItem) {
                            AmplifierTabletItem.setSpeakerPower(stack, power);
                            UUID senderUUID = player.getUuid();
                            broadcastToAll(server, S2C_SYNC_POWER, syncBuf -> {
                                syncBuf.writeUuid(senderUUID);
                                syncBuf.writeInt(handOrdinal);
                                syncBuf.writeFloat(power);
                            });
                        }
                    });
                });

        // Update input gain — synced to all players
        ServerPlayNetworking.registerGlobalReceiver(
                C2S_UPDATE_INPUT_GAIN, (server, player, handler, buf, responseSender) -> {
                    int handOrdinal = buf.readInt();
                    float gain = buf.readFloat();
                    server.execute(() -> {
                        ItemStack stack = getTabletStack(player, handOrdinal);
                        if (stack.getItem() instanceof AmplifierTabletItem) {
                            AmplifierTabletItem.setInputGain(stack, gain);
                            UUID senderUUID = player.getUuid();
                            broadcastToAll(server, S2C_SYNC_INPUT_GAIN, syncBuf -> {
                                syncBuf.writeUuid(senderUUID);
                                syncBuf.writeInt(handOrdinal);
                                syncBuf.writeFloat(gain);
                            });
                        }
                    });
                });

        // EQ update — synced to all players
        ServerPlayNetworking.registerGlobalReceiver(C2S_UPDATE_EQ, (server, player, handler, buf, responseSender) -> {
            String speakerType = buf.readString();
            int band = buf.readInt();
            float db = buf.readFloat();
            server.execute(() -> {
                UUID ownerUUID = player.getUuid();
                broadcastToAll(server, S2C_SYNC_EQ, syncBuf -> {
                    syncBuf.writeUuid(ownerUUID);
                    syncBuf.writeString(speakerType);
                    syncBuf.writeInt(band);
                    syncBuf.writeFloat(db);
                });
            });
        });

        // EQ Q (bandwidth) update — synced to all players
        ServerPlayNetworking.registerGlobalReceiver(C2S_UPDATE_EQ_Q, (server, player, handler, buf, responseSender) -> {
            String speakerType = buf.readString();
            int band = buf.readInt();
            float q = buf.readFloat();
            server.execute(() -> {
                UUID ownerUUID = player.getUuid();
                broadcastToAll(server, S2C_SYNC_EQ_Q, syncBuf -> {
                    syncBuf.writeUuid(ownerUUID);
                    syncBuf.writeString(speakerType);
                    syncBuf.writeInt(band);
                    syncBuf.writeFloat(q);
                });
            });
        });

        // Mixer Gain (volume fader per speaker type) — synced to all players
        ServerPlayNetworking.registerGlobalReceiver(
                C2S_UPDATE_MIXER_GAIN, (server, player, handler, buf, responseSender) -> {
                    String speakerType = buf.readString();
                    float gain = buf.readFloat();
                    server.execute(() -> {
                        UUID ownerUUID = player.getUuid();
                        broadcastToAll(server, S2C_SYNC_MIXER_GAIN, syncBuf -> {
                            syncBuf.writeUuid(ownerUUID);
                            syncBuf.writeString(speakerType);
                            syncBuf.writeFloat(gain);
                        });
                    });
                });

        // Speaker shift — OWNERSHIP PROTECTED
        ServerPlayNetworking.registerGlobalReceiver(
                C2S_UPDATE_SPEAKER_SHIFT, (server, player, handler, buf, responseSender) -> {
                    BlockPos pos = buf.readBlockPos();
                    int shift = buf.readInt();
                    server.execute(() -> {
                        // Ownership check: only the speaker owner can modify it
                        UUID speakerOwner = SpeakerRegistry.getOwner(pos);
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
            server.execute(() -> {
                // Ownership check: only the speaker owner can modify it
                UUID speakerOwner = SpeakerRegistry.getOwner(pos);
                if (speakerOwner != null && !speakerOwner.equals(player.getUuid())) {
                    return; // Not the owner — reject silently
                }
                net.minecraft.block.entity.BlockEntity be = player.getWorld().getBlockEntity(pos);
                if (be instanceof com.audiophilecraft.block.entity.SpeakerBlockEntity speaker) {
                    speaker.setVerticalTilt(tilt);
                }
            });
        });

        // Track Timeline Seek Sync — with tablet validation
        ServerPlayNetworking.registerGlobalReceiver(C2S_SEEK_TRACK, (server, player, handler, buf, responseSender) -> {
            float targetTime = buf.readFloat();
            server.execute(() -> {
                // Validate player is holding an amplifier tablet
                ItemStack mainStack = player.getMainHandStack();
                ItemStack offStack = player.getOffHandStack();
                if (!(mainStack.getItem() instanceof AmplifierTabletItem)
                        && !(offStack.getItem() instanceof AmplifierTabletItem)) {
                    return; // Ignore if not holding tablet
                }
                UUID senderUUID = player.getUuid();
                // Echo to all online players so everyone's seek stays in sync
                broadcastToAll(server, S2C_SEEK_TRACK, syncBuf -> {
                    syncBuf.writeUuid(senderUUID);
                    syncBuf.writeFloat(targetTime);
                });
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
    }

    public static void registerS2CPackets() {
        ClientPlayNetworking.registerGlobalReceiver(S2C_PLAY_TRACK, (client, handler, buf, responseSender) -> {
            UUID sessionUUID = buf.readUuid();
            String trackId = buf.readString();
            float power = buf.readFloat();
            float inputGain = buf.readFloat();
            int count = buf.readInt();
            List<BlockPos> speakers = new ArrayList<>();
            for (int i = 0; i < count; i++) {
                speakers.add(buf.readBlockPos());
            }
            client.execute(() -> {
                com.audiophilecraft.sound.AudioEngine engine = com.audiophilecraft.sound.AudioEngine.getInstance();
                engine.playTrack(sessionUUID, trackId, speakers, power, inputGain);
            });
        });

        ClientPlayNetworking.registerGlobalReceiver(S2C_PLAY_URL, (client, handler, buf, responseSender) -> {
            UUID sessionUUID = buf.readUuid();
            String url = buf.readString(2048);
            float power = buf.readFloat();
            float inputGain = buf.readFloat();
            int count = buf.readInt();
            List<BlockPos> speakers = new ArrayList<>();
            for (int i = 0; i < count; i++) {
                speakers.add(buf.readBlockPos());
            }
            client.execute(() -> {
                com.audiophilecraft.sound.AudioEngine engine = com.audiophilecraft.sound.AudioEngine.getInstance();
                engine.playFromUrl(sessionUUID, url, speakers, power, inputGain);
            });
        });

        ClientPlayNetworking.registerGlobalReceiver(S2C_SYNC_INPUT_GAIN, (client, handler, buf, responseSender) -> {
            UUID sessionUUID = buf.readUuid();
            int handOrdinal = buf.readInt();
            float gain = buf.readFloat();
            client.execute(() -> {
                if (client.player != null && sessionUUID.equals(client.player.getUuid())) {
                    if (client.currentScreen instanceof com.audiophilecraft.client.screen.AmplifierScreen screen) {
                        screen.updateInputGain(gain);
                    }
                }
                com.audiophilecraft.sound.AudioEngine.getInstance().updateInputGainForSession(sessionUUID, gain);
            });
        });

        ClientPlayNetworking.registerGlobalReceiver(S2C_SYNC_POWER, (client, handler, buf, responseSender) -> {
            UUID sessionUUID = buf.readUuid();
            int handOrdinal = buf.readInt();
            float power = buf.readFloat();
            client.execute(() -> {
                if (client.player != null && sessionUUID.equals(client.player.getUuid())) {
                    if (client.currentScreen instanceof com.audiophilecraft.client.screen.AmplifierScreen screen) {
                        screen.updateSpeakerPower(power);
                    }
                }
                com.audiophilecraft.sound.AudioEngine.getInstance().updatePowerForSession(sessionUUID, power);
            });
        });

        // Track Timeline Seek Sync
        ClientPlayNetworking.registerGlobalReceiver(S2C_SEEK_TRACK, (client, handler, buf, responseSender) -> {
            UUID sessionUUID = buf.readUuid();
            float targetTime = buf.readFloat();
            client.execute(() -> {
                com.audiophilecraft.sound.AudioEngine engine = com.audiophilecraft.sound.AudioEngine.getInstance();
                engine.seekForSession(sessionUUID, targetTime);
            });
        });

        // EQ Sync — scoped to session UUID
        ClientPlayNetworking.registerGlobalReceiver(S2C_SYNC_EQ, (client, handler, buf, responseSender) -> {
            UUID sessionUUID = buf.readUuid();
            String speakerType = buf.readString();
            int band = buf.readInt();
            float db = buf.readFloat();
            client.execute(() -> {
                com.audiophilecraft.sound.AudioEngine engine = com.audiophilecraft.sound.AudioEngine.getInstance();
                engine.setEqDbForSession(sessionUUID, speakerType, band, db);
            });
        });

        // EQ Q Sync — scoped to session UUID
        ClientPlayNetworking.registerGlobalReceiver(S2C_SYNC_EQ_Q, (client, handler, buf, responseSender) -> {
            UUID sessionUUID = buf.readUuid();
            String speakerType = buf.readString();
            int band = buf.readInt();
            float q = buf.readFloat();
            client.execute(() -> {
                com.audiophilecraft.sound.AudioEngine engine = com.audiophilecraft.sound.AudioEngine.getInstance();
                engine.setEqQForSession(sessionUUID, speakerType, band, q);
            });
        });

        // Mixer Gain Sync — scoped to session UUID
        ClientPlayNetworking.registerGlobalReceiver(S2C_SYNC_MIXER_GAIN, (client, handler, buf, responseSender) -> {
            UUID sessionUUID = buf.readUuid();
            String speakerType = buf.readString();
            float gain = buf.readFloat();
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
    }

    public static void sendPlayTrack(
            net.minecraft.server.network.ServerPlayerEntity player,
            UUID ownerUUID,
            String trackId,
            List<BlockPos> speakers,
            float power,
            float inputGain) {
        PacketByteBuf buf = PacketByteBufs.create();
        buf.writeUuid(ownerUUID);
        buf.writeString(trackId);
        buf.writeFloat(power);
        buf.writeFloat(inputGain);
        buf.writeInt(speakers.size());
        for (BlockPos p : speakers) {
            buf.writeBlockPos(p);
        }
        ServerPlayNetworking.send(player, S2C_PLAY_TRACK, buf);
    }

    public static void sendPlayUrl(
            net.minecraft.server.network.ServerPlayerEntity player,
            UUID ownerUUID,
            String url,
            List<BlockPos> speakers,
            float power,
            float inputGain) {
        PacketByteBuf buf = PacketByteBufs.create();
        buf.writeUuid(ownerUUID);
        buf.writeString(url);
        buf.writeFloat(power);
        buf.writeFloat(inputGain);
        buf.writeInt(speakers.size());
        for (BlockPos p : speakers) {
            buf.writeBlockPos(p);
        }
        ServerPlayNetworking.send(player, S2C_PLAY_URL, buf);
    }
}
