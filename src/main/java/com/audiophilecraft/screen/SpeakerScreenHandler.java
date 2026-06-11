package com.audiophilecraft.screen;

import com.audiophilecraft.registry.ModScreenHandlers;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.util.math.BlockPos;

public class SpeakerScreenHandler extends ScreenHandler {
    private final BlockPos pos;
    private int sampleShift;
    private int verticalTiltDeg;

    // Server Constructor
    public SpeakerScreenHandler(
            int syncId, PlayerInventory playerInventory, BlockPos pos, int sampleShift, int verticalTiltDeg) {
        super(ModScreenHandlers.SPEAKER_SCREEN_HANDLER, syncId);
        this.pos = pos;
        this.sampleShift = sampleShift;
        this.verticalTiltDeg = verticalTiltDeg;
    }

    // Client Constructor
    public SpeakerScreenHandler(int syncId, PlayerInventory playerInventory, PacketByteBuf buf) {
        this(syncId, playerInventory, buf.readBlockPos(), buf.readInt(), buf.readInt());
    }

    public int getSampleShift() {
        return sampleShift;
    }

    public void setSampleShift(int shift) {
        this.sampleShift = shift;
    }

    public int getVerticalTiltDeg() {
        return verticalTiltDeg;
    }

    public void setVerticalTiltDeg(int deg) {
        this.verticalTiltDeg = deg;
    }

    @Override
    public ItemStack quickMove(PlayerEntity player, int invSlot) {
        return ItemStack.EMPTY;
    }

    @Override
    public boolean canUse(PlayerEntity player) {
        return true;
    }

    public BlockPos getPos() {
        return pos;
    }
}
