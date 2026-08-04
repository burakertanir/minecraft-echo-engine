package com.audiophilecraft.item;

import com.audiophilecraft.screen.AmplifierScreenHandler;
import net.fabricmc.fabric.api.screenhandler.v1.ExtendedScreenHandlerFactory;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.ScreenHandlerContext;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.Hand;
import net.minecraft.util.TypedActionResult;
import net.minecraft.world.World;

public class AmplifierTabletItem extends Item {
    public static final int SCAN_RADIUS = 500;

    public AmplifierTabletItem(Settings settings) {
        super(settings);
    }

    @Override
    public TypedActionResult<ItemStack> use(World world, PlayerEntity user, Hand hand) {
        ItemStack stack = user.getStackInHand(hand);
        if (!world.isClient) {
            user.openHandledScreen(new ExtendedScreenHandlerFactory() {
                @Override
                public Text getDisplayName() {
                    return Text.translatable("item.audiophilecraft.amplifier_tablet");
                }

                @Override
                public ScreenHandler createMenu(int syncId, PlayerInventory playerInventory, PlayerEntity player) {
                    return new AmplifierScreenHandler(
                            syncId, playerInventory, ScreenHandlerContext.create(world, player.getBlockPos()));
                }

                @Override
                public void writeScreenOpeningData(ServerPlayerEntity player, PacketByteBuf buf) {
                    buf.writeInt(hand.ordinal());
                    NbtCompound nbt = stack.getNbt();
                    float inputGain = 1.0f;
                    float speakerPower = 1.0f;
                    if (nbt != null) {
                        if (nbt.contains("InputGain")) inputGain = nbt.getFloat("InputGain");
                        if (nbt.contains("SpeakerPower")) speakerPower = nbt.getFloat("SpeakerPower");
                    }
                    buf.writeFloat(inputGain);
                    buf.writeFloat(speakerPower);
                }
            });
        }
        return TypedActionResult.success(stack, world.isClient);
    }

    // --- Static NBT Helpers ---

    public static float getSpeakerPower(ItemStack stack) {
        NbtCompound nbt = stack.getNbt();
        if (nbt != null && nbt.contains("SpeakerPower")) {
            return nbt.getFloat("SpeakerPower");
        }
        return 1.0f;
    }

    public static void setSpeakerPower(ItemStack stack, float power) {
        NbtCompound nbt = stack.getOrCreateNbt();
        nbt.putFloat("SpeakerPower", Math.max(0.1f, Math.min(power, 10.0f)));
    }

    public static float getInputGain(ItemStack stack) {
        NbtCompound nbt = stack.getNbt();
        if (nbt != null && nbt.contains("InputGain")) {
            return nbt.getFloat("InputGain");
        }
        return 1.0f;
    }

    public static void setInputGain(ItemStack stack, float gain) {
        NbtCompound nbt = stack.getOrCreateNbt();
        nbt.putFloat("InputGain", Math.max(0.0f, Math.min(gain, 3.0f)));
    }

    // --- Mixer Gains (persisted across songs and server restarts) ---

    public static float getMixerGain(ItemStack stack, String speakerType) {
        NbtCompound nbt = stack.getNbt();
        String key = "Mixer_" + speakerType;
        if (nbt != null && nbt.contains(key)) return nbt.getFloat(key);
        return 1.0f;
    }

    public static void setMixerGain(ItemStack stack, String speakerType, float gain) {
        NbtCompound nbt = stack.getOrCreateNbt();
        nbt.putFloat("Mixer_" + speakerType, Math.max(0.0f, Math.min(gain, 1.0f)));
    }

    // --- EQ dB (persisted across songs and server restarts) ---

    public static float getEqDb(ItemStack stack, String speakerType, int band) {
        NbtCompound nbt = stack.getNbt();
        String key = "EqDb_" + speakerType + "_" + band;
        if (nbt != null && nbt.contains(key)) return nbt.getFloat(key);
        return 0f;
    }

    public static void setEqDb(ItemStack stack, String speakerType, int band, float db) {
        NbtCompound nbt = stack.getOrCreateNbt();
        nbt.putFloat("EqDb_" + speakerType + "_" + band, Math.max(-9f, Math.min(db, 9f)));
    }

    // --- EQ Q (bandwidth, persisted across songs and server restarts) ---

    public static float getEqQ(ItemStack stack, String speakerType, int band) {
        NbtCompound nbt = stack.getNbt();
        String key = "EqQ_" + speakerType + "_" + band;
        if (nbt != null && nbt.contains(key)) return nbt.getFloat(key);
        return 1f;
    }

    public static void setEqQ(ItemStack stack, String speakerType, int band, float q) {
        NbtCompound nbt = stack.getOrCreateNbt();
        nbt.putFloat("EqQ_" + speakerType + "_" + band, Math.max(0.1f, Math.min(q, 10f)));
    }
}
