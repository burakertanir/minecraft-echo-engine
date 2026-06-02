package com.audiophilecraft.screen;

import com.audiophilecraft.registry.ModScreenHandlers;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.ScreenHandlerContext;
import net.minecraft.util.Hand;

public class AmplifierScreenHandler extends ScreenHandler {
    private final ScreenHandlerContext context;
    private Hand hand;
    private float inputGain = 1.0f;
    private float speakerPower = 1.0f;

    // Client constructor (reads from PacketByteBuf)
    public AmplifierScreenHandler(int syncId, PlayerInventory inventory, PacketByteBuf buf) {
        this(syncId, inventory, ScreenHandlerContext.EMPTY);
        int handOrdinal = buf.readInt();
        this.hand = Hand.values()[handOrdinal];
        this.inputGain = buf.readFloat();
        this.speakerPower = buf.readFloat();
    }

    // Server constructor
    public AmplifierScreenHandler(int syncId, PlayerInventory inventory, ScreenHandlerContext context) {
        super(ModScreenHandlers.AMPLIFIER_SCREEN_HANDLER, syncId);
        this.context = context;
    }

    public Hand getHand() {
        return hand;
    }

    public float getInputGain() {
        return inputGain;
    }

    public void setInputGain(float gain) {
        this.inputGain = gain;
    }

    public float getSpeakerPower() {
        return speakerPower;
    }

    public void setSpeakerPower(float power) {
        this.speakerPower = power;
    }

    @Override
    public ItemStack quickMove(PlayerEntity player, int slot) {
        return ItemStack.EMPTY;
    }

    @Override
    public boolean canUse(PlayerEntity player) {
        return true;
    }
}
