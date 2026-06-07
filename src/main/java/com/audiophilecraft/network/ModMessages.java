package com.audiophilecraft.network;

import com.audiophilecraft.AudiophileCraft;
import com.audiophilecraft.item.AmplifierTabletItem;
import com.audiophilecraft.registry.SpeakerRegistry;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.item.ItemStack;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.util.Hand;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;

import java.util.ArrayList;
import java.util.List;

public class ModMessages {
    public static final Identifier C2S_REQUEST_PLAY = new Identifier(AudiophileCraft.MOD_ID, "c2s_request_play");
    public static final Identifier C2S_UPDATE_POWER = new Identifier(AudiophileCraft.MOD_ID, "c2s_update_power");
    public static final Identifier S2C_SYNC_POWER = new Identifier(AudiophileCraft.MOD_ID, "s2c_sync_power");
    public static final Identifier C2S_UPDATE_INPUT_GAIN = new Identifier(AudiophileCraft.MOD_ID,
            "c2s_update_input_gain");
    public static final Identifier S2C_SYNC_INPUT_GAIN = new Identifier(AudiophileCraft.MOD_ID, "s2c_sync_input_gain");
    public static final Identifier C2S_UPDATE_SPEAKER_SHIFT = new Identifier(AudiophileCraft.MOD_ID,
            "c2s_update_speaker_shift");
    public static final Identifier C2S_PLAY_URL = new Identifier(AudiophileCraft.MOD_ID, "c2s_play_url");
    public static final Identifier S2C_PLAY_URL = new Identifier(AudiophileCraft.MOD_ID, "s2c_play_url");
    public static final Identifier C2S_UPDATE_TILT = new Identifier(AudiophileCraft.MOD_ID, "c2s_update_tilt");
    public static final Identifier S2C_PLAY_TRACK = new Identifier(AudiophileCraft.MOD_ID, "s2c_play_track");
    public static final Identifier C2S_SEEK_TRACK = new Identifier(AudiophileCraft.MOD_ID, "c2s_seek_track");
    public static final Identifier S2C_SEEK_TRACK = new Identifier(AudiophileCraft.MOD_ID, "s2c_seek_track");

    /** Helper: get the tablet ItemStack from the player's hand ordinal */
    private static ItemStack getTabletStack(net.minecraft.server.network.ServerPlayerEntity player, int handOrdinal) {
        Hand hand = Hand.values()[handOrdinal];
        return player.getStackInHand(hand);
    }

    public static void registerC2SPackets() {
        // Play test track — AUTO-CONNECTS to all speakers in 500-block range
        ServerPlayNetworking.registerGlobalReceiver(C2S_REQUEST_PLAY,
                (server, player, handler, buf, responseSender) -> {
                    int handOrdinal = buf.readInt();
                    server.execute(() -> {
                        ItemStack stack = getTabletStack(player, handOrdinal);
                        if (stack.getItem() instanceof AmplifierTabletItem) {
                            String testTrackId = "music/test_track";
                            // Auto-find ALL speakers in range
                            List<BlockPos> speakers = SpeakerRegistry.findSpeakersInRange(
                                    player.getBlockPos(), AmplifierTabletItem.SCAN_RADIUS);
                            float power = AmplifierTabletItem.getSpeakerPower(stack);
                            float inputGain = AmplifierTabletItem.getInputGain(stack);
                            // Broadcast to all online players so everyone hears the music
                            for (net.minecraft.server.network.ServerPlayerEntity nearby : server.getPlayerManager().getPlayerList()) {
                                sendPlayTrack(nearby, testTrackId, speakers, power, inputGain);
                            }
                        }
                    });
                });

        // URL-based play — AUTO-CONNECTS to all speakers in 500-block range
        ServerPlayNetworking.registerGlobalReceiver(C2S_PLAY_URL,
                (server, player, handler, buf, responseSender) -> {
                    int handOrdinal = buf.readInt();
                    String url = buf.readString(2048);
                    server.execute(() -> {
                        ItemStack stack = getTabletStack(player, handOrdinal);
                        if (stack.getItem() instanceof AmplifierTabletItem) {
                            // Auto-find ALL speakers in range
                            List<BlockPos> speakers = SpeakerRegistry.findSpeakersInRange(
                                    player.getBlockPos(), AmplifierTabletItem.SCAN_RADIUS);
                            float power = AmplifierTabletItem.getSpeakerPower(stack);
                            float inputGain = AmplifierTabletItem.getInputGain(stack);
                            // Broadcast to all online players so everyone hears the music
                            for (net.minecraft.server.network.ServerPlayerEntity nearby : server.getPlayerManager().getPlayerList()) {
                                sendPlayUrl(nearby, url, speakers, power, inputGain);
                            }
                        }
                    });
                });

        // Update speaker power
        ServerPlayNetworking.registerGlobalReceiver(C2S_UPDATE_POWER,
                (server, player, handler, buf, responseSender) -> {
                    int handOrdinal = buf.readInt();
                    float power = buf.readFloat();
                    server.execute(() -> {
                        ItemStack stack = getTabletStack(player, handOrdinal);
                        if (stack.getItem() instanceof AmplifierTabletItem) {
                            AmplifierTabletItem.setSpeakerPower(stack, power);
                            PacketByteBuf syncBuf = PacketByteBufs.create();
                            syncBuf.writeInt(handOrdinal);
                            syncBuf.writeFloat(power);
                            for (net.minecraft.server.network.ServerPlayerEntity nearby : server.getPlayerManager().getPlayerList()) {
                            ServerPlayNetworking.send(nearby, S2C_SYNC_POWER, syncBuf);
                        }
                        }
                    });
                });

        // Update input gain
        ServerPlayNetworking.registerGlobalReceiver(C2S_UPDATE_INPUT_GAIN,
                (server, player, handler, buf, responseSender) -> {
                    int handOrdinal = buf.readInt();
                    float gain = buf.readFloat();
                    server.execute(() -> {
                        ItemStack stack = getTabletStack(player, handOrdinal);
                        if (stack.getItem() instanceof AmplifierTabletItem) {
                            AmplifierTabletItem.setInputGain(stack, gain);
                            PacketByteBuf syncBuf = PacketByteBufs.create();
                            syncBuf.writeInt(handOrdinal);
                            syncBuf.writeFloat(gain);
                            for (net.minecraft.server.network.ServerPlayerEntity nearby : server.getPlayerManager().getPlayerList()) {
                            ServerPlayNetworking.send(nearby, S2C_SYNC_INPUT_GAIN, syncBuf);
                        }
                        }
                    });
                });

        // Speaker shift (speaker block entity — unchanged)
        ServerPlayNetworking.registerGlobalReceiver(C2S_UPDATE_SPEAKER_SHIFT,
                (server, player, handler, buf, responseSender) -> {
                    BlockPos pos = buf.readBlockPos();
                    int shift = buf.readInt();
                    server.execute(() -> {
                        net.minecraft.block.entity.BlockEntity be = player.getWorld().getBlockEntity(pos);
                        if (be instanceof com.audiophilecraft.block.entity.SpeakerBlockEntity speaker) {
                            speaker.setSampleShift(shift);
                        }
                    });
                });

        // Speaker tilt (speaker block entity — unchanged)
        ServerPlayNetworking.registerGlobalReceiver(C2S_UPDATE_TILT,
                (server, player, handler, buf, responseSender) -> {
                    BlockPos pos = buf.readBlockPos();
                    int tilt = buf.readInt();
                    server.execute(() -> {
                        net.minecraft.block.entity.BlockEntity be = player.getWorld().getBlockEntity(pos);
                        if (be instanceof com.audiophilecraft.block.entity.SpeakerBlockEntity speaker) {
                            speaker.setVerticalTilt(tilt);
                        }
                    });
                });

        // Track Timeline Seek Sync — with tablet validation and proximity check
        ServerPlayNetworking.registerGlobalReceiver(C2S_SEEK_TRACK,
                (server, player, handler, buf, responseSender) -> {
                    float targetTime = buf.readFloat();
                    server.execute(() -> {
                        // Validate player is holding an amplifier tablet
                        ItemStack mainStack = player.getMainHandStack();
                        ItemStack offStack = player.getOffHandStack();
                        if (!(mainStack.getItem() instanceof AmplifierTabletItem) &&
                            !(offStack.getItem() instanceof AmplifierTabletItem)) {
                            return; // Ignore if not holding tablet
                        }
                        // Echo to all online players so everyone's seek stays in sync
                        PacketByteBuf syncBuf = PacketByteBufs.create();
                        syncBuf.writeFloat(targetTime);
                        for (net.minecraft.server.network.ServerPlayerEntity nearby : server.getPlayerManager().getPlayerList()) {
                            ServerPlayNetworking.send(nearby, S2C_SEEK_TRACK, syncBuf);
                        }
                    });
                });
    }

    public static void registerS2CPackets() {
        ClientPlayNetworking.registerGlobalReceiver(S2C_PLAY_TRACK, (client, handler, buf, responseSender) -> {
            String trackId = buf.readString();
            float power = buf.readFloat();
            float inputGain = buf.readFloat();
            int count = buf.readInt();
            List<BlockPos> speakers = new ArrayList<>();
            for (int i = 0; i < count; i++) {
                speakers.add(buf.readBlockPos());
            }
            client.execute(() -> {
                com.audiophilecraft.sound.AudioEngine.getInstance().playTrack(trackId, speakers, power, inputGain);
            });
        });

        ClientPlayNetworking.registerGlobalReceiver(S2C_PLAY_URL, (client, handler, buf, responseSender) -> {
            String url = buf.readString(2048);
            float power = buf.readFloat();
            float inputGain = buf.readFloat();
            int count = buf.readInt();
            List<BlockPos> speakers = new ArrayList<>();
            for (int i = 0; i < count; i++) {
                speakers.add(buf.readBlockPos());
            }
            client.execute(() -> {
                com.audiophilecraft.sound.AudioEngine.getInstance().playFromUrl(url, speakers, power, inputGain);
            });
        });

        ClientPlayNetworking.registerGlobalReceiver(S2C_SYNC_INPUT_GAIN, (client, handler, buf, responseSender) -> {
            int handOrdinal = buf.readInt();
            float gain = buf.readFloat();
            client.execute(() -> {
                if (client.currentScreen instanceof com.audiophilecraft.client.screen.AmplifierScreen screen) {
                    screen.updateInputGain(gain);
                }
                com.audiophilecraft.sound.AudioEngine.getInstance().updateInputGain(gain);
            });
        });

        ClientPlayNetworking.registerGlobalReceiver(S2C_SYNC_POWER, (client, handler, buf, responseSender) -> {
            int handOrdinal = buf.readInt();
            float power = buf.readFloat();
            client.execute(() -> {
                if (client.currentScreen instanceof com.audiophilecraft.client.screen.AmplifierScreen screen) {
                    screen.updateSpeakerPower(power);
                }
                com.audiophilecraft.sound.AudioEngine.getInstance().updatePower(power);
            });
        });

        // Track Timeline Seek Sync
        ClientPlayNetworking.registerGlobalReceiver(S2C_SEEK_TRACK, (client, handler, buf, responseSender) -> {
            float targetTime = buf.readFloat();
            client.execute(() -> {
                com.audiophilecraft.sound.AudioEngine.getInstance().seek(targetTime);
            });
        });
    }

    public static void sendPlayTrack(net.minecraft.server.network.ServerPlayerEntity player, String trackId,
            List<BlockPos> speakers, float power, float inputGain) {
        PacketByteBuf buf = PacketByteBufs.create();
        buf.writeString(trackId);
        buf.writeFloat(power);
        buf.writeFloat(inputGain);
        buf.writeInt(speakers.size());
        for (BlockPos p : speakers) {
            buf.writeBlockPos(p);
        }
        ServerPlayNetworking.send(player, S2C_PLAY_TRACK, buf);
    }

    public static void sendPlayUrl(net.minecraft.server.network.ServerPlayerEntity player, String url,
            List<BlockPos> speakers, float power, float inputGain) {
        PacketByteBuf buf = PacketByteBufs.create();
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
