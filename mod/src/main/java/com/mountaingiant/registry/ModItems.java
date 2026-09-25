package com.mountaingiant.registry;

import com.mountaingiant.MountainGiantMod;
import net.minecraft.world.item.Item;
import net.neoforged.neoforge.common.DeferredSpawnEggItem;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

public final class ModItems {
    public static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(MountainGiantMod.MODID);

    public static final DeferredItem<DeferredSpawnEggItem> MOUNTAIN_GIANT_SPAWN_EGG =
            ITEMS.register("mountain_giant_spawn_egg", () -> new DeferredSpawnEggItem(
                    ModEntities.MOUNTAIN_GIANT, 0x7A7A7A, 0x4E7A2F, new Item.Properties()));

    private ModItems() {
    }
}
