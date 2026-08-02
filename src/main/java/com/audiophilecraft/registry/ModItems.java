package com.audiophilecraft.registry;

import com.audiophilecraft.AudiophileCraft;
import com.audiophilecraft.item.AmplifierTabletItem;
import net.fabricmc.fabric.api.item.v1.FabricItemSettings;
import net.minecraft.item.Item;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.util.Identifier;

public class ModItems {

    public static final Item AMPLIFIER_TABLET = Registry.register(
            Registries.ITEM,
            new Identifier("audiophilecraft", "amplifier_tablet"),
            new AmplifierTabletItem(new FabricItemSettings().maxCount(1)));

    public static void registerModItems() {
        AudiophileCraft.LOGGER.debug("Registered AudiophileCraft items.");
    }
}
