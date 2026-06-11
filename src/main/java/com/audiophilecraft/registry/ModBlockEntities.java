package com.audiophilecraft.registry;

import com.audiophilecraft.block.entity.SpeakerBlockEntity;
import net.fabricmc.fabric.api.object.builder.v1.block.entity.FabricBlockEntityTypeBuilder;
import net.minecraft.block.entity.BlockEntityType;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.util.Identifier;

public class ModBlockEntities {
    public static BlockEntityType<SpeakerBlockEntity> SPEAKER_BE;

    public static void register() {
        SPEAKER_BE = Registry.register(
                Registries.BLOCK_ENTITY_TYPE,
                new Identifier("audiophilecraft", "speaker_be"),
                FabricBlockEntityTypeBuilder.create(
                                SpeakerBlockEntity::new, ModBlocks.SUBWOOFER, ModBlocks.MID_RANGE, ModBlocks.LINE_ARRAY)
                        .build());
    }
}
