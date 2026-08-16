package com.audiophilecraft.network;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.minecraft.network.PacketByteBuf;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class SpeakerOwnerListCodecTest {
    @Test
    void roundTripsPlacementOwnerAndBothAccessModes() {
        UUID placementOwner = UUID.randomUUID();
        List<ModMessages.SpeakerOwner> owners = List.of(
                new ModMessages.SpeakerOwner(UUID.randomUUID(), "Private Owner", 0, false),
                new ModMessages.SpeakerOwner(placementOwner, "Shared Owner", 42, true));
        PacketByteBuf buffer = SpeakerOwnerListCodec.create(placementOwner, owners);

        try {
            SpeakerOwnerListCodec.SpeakerOwnerList decoded = SpeakerOwnerListCodec.read(buffer);

            assertEquals(placementOwner, decoded.placementOwner());
            assertEquals(owners, decoded.owners());
            assertEquals(0, buffer.readableBytes());
        } finally {
            buffer.release();
        }
    }

    @Test
    void decodedOwnerListIsDefensivelyCopied() {
        ArrayList<ModMessages.SpeakerOwner> source = new ArrayList<>();
        source.add(new ModMessages.SpeakerOwner(UUID.randomUUID(), "Owner", 1, true));

        SpeakerOwnerListCodec.SpeakerOwnerList ownerList =
                new SpeakerOwnerListCodec.SpeakerOwnerList(UUID.randomUUID(), source);
        source.clear();

        assertEquals(1, ownerList.owners().size());
        assertNotSame(source, ownerList.owners());
        assertThrows(
                UnsupportedOperationException.class, () -> ownerList.owners().clear());
    }

    @ParameterizedTest
    @ValueSource(ints = {-1, 65})
    void rejectsOutOfRangeOwnerCounts(int ownerCount) {
        PacketByteBuf buffer = PacketByteBufs.create();
        buffer.writeUuid(UUID.randomUUID());
        buffer.writeInt(ownerCount);

        try {
            assertNull(SpeakerOwnerListCodec.read(buffer));
        } finally {
            buffer.release();
        }
    }

    @Test
    void rejectsBlankNamesAndNegativeSpeakerCounts() {
        assertNull(readSingleOwner(" ", 1));
        assertNull(readSingleOwner("Owner", -1));
    }

    @Test
    void rejectsTruncatedOwnerEntriesWithoutThrowing() {
        PacketByteBuf buffer = PacketByteBufs.create();
        buffer.writeUuid(UUID.randomUUID());
        buffer.writeInt(1);
        buffer.writeUuid(UUID.randomUUID());

        try {
            assertNull(SpeakerOwnerListCodec.read(buffer));
        } finally {
            buffer.release();
        }
    }

    @Test
    void rejectsNamesLongerThanTheProtocolLimit() {
        PacketByteBuf buffer = PacketByteBufs.create();
        buffer.writeUuid(UUID.randomUUID());
        buffer.writeInt(1);
        buffer.writeUuid(UUID.randomUUID());
        buffer.writeString("x".repeat(SpeakerOwnerListCodec.MAX_OWNER_NAME_LENGTH + 1), 128);
        buffer.writeInt(1);
        buffer.writeBoolean(true);

        try {
            assertNull(SpeakerOwnerListCodec.read(buffer));
        } finally {
            buffer.release();
        }
    }

    @Test
    void writerRejectsNullsAndOversizedOwnerLists() {
        UUID placementOwner = UUID.randomUUID();
        ModMessages.SpeakerOwner owner = new ModMessages.SpeakerOwner(UUID.randomUUID(), "Owner", 1, true);

        assertThrows(IllegalArgumentException.class, () -> SpeakerOwnerListCodec.create(null, List.of(owner)));
        assertThrows(IllegalArgumentException.class, () -> SpeakerOwnerListCodec.create(placementOwner, null));
        assertThrows(
                IllegalArgumentException.class,
                () -> SpeakerOwnerListCodec.create(
                        placementOwner, java.util.Collections.nCopies(SpeakerOwnerListCodec.MAX_OWNERS + 1, owner)));
    }

    @Test
    void writerRejectsNamesLongerThanTheProtocolLimit() {
        ModMessages.SpeakerOwner owner = new ModMessages.SpeakerOwner(
                UUID.randomUUID(), "x".repeat(SpeakerOwnerListCodec.MAX_OWNER_NAME_LENGTH + 1), 1, true);

        assertThrows(
                IllegalArgumentException.class, () -> SpeakerOwnerListCodec.create(UUID.randomUUID(), List.of(owner)));
    }

    private static SpeakerOwnerListCodec.SpeakerOwnerList readSingleOwner(String name, int speakerCount) {
        PacketByteBuf buffer = PacketByteBufs.create();
        buffer.writeUuid(UUID.randomUUID());
        buffer.writeInt(1);
        buffer.writeUuid(UUID.randomUUID());
        buffer.writeString(name, 64);
        buffer.writeInt(speakerCount);
        buffer.writeBoolean(false);
        try {
            return SpeakerOwnerListCodec.read(buffer);
        } finally {
            buffer.release();
        }
    }
}
