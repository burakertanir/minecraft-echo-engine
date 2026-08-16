package com.audiophilecraft.network;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.minecraft.network.PacketByteBuf;

/** Bounded wire codec for the tablet's visible speaker-system list. */
final class SpeakerOwnerListCodec {
    static final int MAX_OWNERS = 64;
    static final int MAX_OWNER_NAME_LENGTH = 64;

    private SpeakerOwnerListCodec() {}

    static PacketByteBuf create(UUID placementOwner, List<ModMessages.SpeakerOwner> owners) {
        if (placementOwner == null) throw new IllegalArgumentException("placementOwner cannot be null");
        if (owners == null || owners.size() > MAX_OWNERS) {
            throw new IllegalArgumentException("Invalid speaker owner count");
        }
        for (ModMessages.SpeakerOwner owner : owners) {
            if (owner == null
                    || owner.uuid() == null
                    || owner.name() == null
                    || owner.name().isBlank()
                    || owner.name().length() > MAX_OWNER_NAME_LENGTH
                    || owner.speakerCount() < 0) {
                throw new IllegalArgumentException("Invalid speaker owner entry");
            }
        }

        PacketByteBuf buffer = PacketByteBufs.create();
        buffer.writeUuid(placementOwner);
        buffer.writeInt(owners.size());
        for (ModMessages.SpeakerOwner owner : owners) {
            buffer.writeUuid(owner.uuid());
            buffer.writeString(owner.name(), MAX_OWNER_NAME_LENGTH);
            buffer.writeInt(owner.speakerCount());
            buffer.writeBoolean(owner.shared());
        }
        return buffer;
    }

    static SpeakerOwnerList read(PacketByteBuf buffer) {
        try {
            if (!buffer.isReadable(20)) return null;
            UUID placementOwner = buffer.readUuid();
            int count = buffer.readInt();
            if (count < 0 || count > MAX_OWNERS) return null;

            List<ModMessages.SpeakerOwner> owners = new ArrayList<>(count);
            for (int index = 0; index < count; index++) {
                UUID uuid = buffer.readUuid();
                String name = buffer.readString(MAX_OWNER_NAME_LENGTH);
                int speakerCount = buffer.readInt();
                boolean shared = buffer.readBoolean();
                if (name.isBlank() || speakerCount < 0) return null;
                owners.add(new ModMessages.SpeakerOwner(uuid, name, speakerCount, shared));
            }
            return new SpeakerOwnerList(placementOwner, owners);
        } catch (RuntimeException exception) {
            return null;
        }
    }

    record SpeakerOwnerList(UUID placementOwner, List<ModMessages.SpeakerOwner> owners) {
        SpeakerOwnerList {
            owners = List.copyOf(owners);
        }
    }
}
