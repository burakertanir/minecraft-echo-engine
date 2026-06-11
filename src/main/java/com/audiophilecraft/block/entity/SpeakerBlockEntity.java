package com.audiophilecraft.block.entity;

import com.audiophilecraft.registry.ModBlockEntities;
import com.audiophilecraft.screen.SpeakerScreenHandler;
import java.util.UUID;
import net.fabricmc.fabric.api.screenhandler.v1.ExtendedScreenHandlerFactory;
import net.minecraft.block.BlockState;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.network.listener.ClientPlayPacketListener;
import net.minecraft.network.packet.Packet;
import net.minecraft.network.packet.s2c.play.BlockEntityUpdateS2CPacket;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;
import org.jetbrains.annotations.Nullable;

public class SpeakerBlockEntity extends BlockEntity implements ExtendedScreenHandlerFactory {

    private int sampleShift = 0; // 0 to 30 ms

    private int verticalTiltDeg = 0; // -70 to +70 degrees (Line Array only)

    private UUID ownerUUID = null; // Who placed this speaker (for future multiplayer filtering)

    public SpeakerBlockEntity(BlockPos pos, BlockState state) {

        super(ModBlockEntities.SPEAKER_BE, pos, state);
    }

    public int getSampleShift() {

        return sampleShift;
    }

    public void setSampleShift(int sampleShift) {

        this.sampleShift = sampleShift;

        markDirty();

        if (world != null && !world.isClient) {

            world.updateListeners(pos, getCachedState(), getCachedState(), 3);
        }
    }

    public int getVerticalTilt() {

        return verticalTiltDeg;
    }

    public void setVerticalTilt(int deg) {

        this.verticalTiltDeg = Math.max(-70, Math.min(70, deg));

        markDirty();

        if (world != null && !world.isClient) {

            world.updateListeners(pos, getCachedState(), getCachedState(), 3);
        }
    }

    @Override
    public void writeScreenOpeningData(ServerPlayerEntity player, PacketByteBuf buf) {

        buf.writeBlockPos(pos);

        buf.writeInt(sampleShift);

        buf.writeInt(verticalTiltDeg);
    }

    @Override
    public Text getDisplayName() {

        return Text.literal("Speaker Settings");
    }

    @Nullable
    @Override
    public ScreenHandler createMenu(int syncId, PlayerInventory playerInventory, PlayerEntity player) {

        return new SpeakerScreenHandler(syncId, playerInventory, this.pos, this.sampleShift, this.verticalTiltDeg);
    }

    public UUID getOwnerUUID() {

        return ownerUUID;
    }

    public void setOwnerUUID(UUID uuid) {

        this.ownerUUID = uuid;

        markDirty();
    }

    @Override
    protected void writeNbt(NbtCompound nbt) {

        super.writeNbt(nbt);

        nbt.putInt("SampleShift", sampleShift);

        nbt.putInt("VerticalTilt", verticalTiltDeg);

        if (ownerUUID != null) {

            nbt.putUuid("OwnerUUID", ownerUUID);
        }
    }

    @Override
    public void readNbt(NbtCompound nbt) {

        super.readNbt(nbt);

        sampleShift = nbt.getInt("SampleShift");

        verticalTiltDeg = nbt.getInt("VerticalTilt");

        if (nbt.containsUuid("OwnerUUID")) {

            ownerUUID = nbt.getUuid("OwnerUUID");
        }

        // Register in global registry on world load

        com.audiophilecraft.registry.SpeakerRegistry.registerSpeaker(pos, ownerUUID);
    }

    @Nullable
    @Override
    public Packet<ClientPlayPacketListener> toUpdatePacket() {

        return BlockEntityUpdateS2CPacket.create(this);
    }

    @Override
    public NbtCompound toInitialChunkDataNbt() {

        return createNbt();
    }
}
