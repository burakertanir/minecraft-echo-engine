package com.audiophilecraft.registry;

import com.audiophilecraft.AudiophileCraft;
import net.fabricmc.fabric.api.itemgroup.v1.FabricItemGroup;
import net.minecraft.item.ItemGroup;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;

public class ModItemGroups {
    public static final ItemGroup AUDIOPHILE_GROUP = Registry.register(Registries.ITEM_GROUP,
            new Identifier(AudiophileCraft.MOD_ID, "audiophile_group"),
            FabricItemGroup.builder()
                    .displayName(Text.translatable("itemgroup.audiophilecraft"))
                    .icon(() -> new ItemStack(ModItems.AMPLIFIER_TABLET))
                    .entries((displayContext, entries) -> {
                        entries.add(ModItems.AMPLIFIER_TABLET);
                        entries.add(ModBlocks.SUBWOOFER);
                        entries.add(ModBlocks.MID_RANGE);
                        entries.add(ModBlocks.LINE_ARRAY);
                    }).build());

    public static void registerItemGroups() {
        AudiophileCraft.LOGGER.info("Registering Item Groups for " + AudiophileCraft.MOD_ID);
    }
}
