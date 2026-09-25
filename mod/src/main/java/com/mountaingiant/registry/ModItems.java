package com.mountaingiant.registry;

import com.mountaingiant.MountainGiantMod;
import com.mountaingiant.item.MountainHammerItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Rarity;
import net.neoforged.neoforge.common.DeferredSpawnEggItem;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

public final class ModItems {
    public static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(MountainGiantMod.MODID);

    public static final DeferredItem<DeferredSpawnEggItem> MOUNTAIN_GIANT_SPAWN_EGG =
            ITEMS.register("mountain_giant_spawn_egg", () -> new DeferredSpawnEggItem(
                    ModEntities.MOUNTAIN_GIANT, 0x7A7A7A, 0x4E7A2F, new Item.Properties()));

    /** Dropped once per slain giant: the stone-and-gold heart of the mountain. */
    public static final DeferredItem<Item> MOUNTAIN_HEART = ITEMS.register("mountain_heart", () -> new Item(
            new Item.Properties().rarity(Rarity.EPIC).fireResistant()));

    public static final DeferredItem<MountainHammerItem> MOUNTAIN_HAMMER = ITEMS.register("mountain_hammer",
            () -> new MountainHammerItem(new Item.Properties()
                    .stacksTo(1)
                    .durability(2031)
                    .rarity(Rarity.EPIC)
                    .fireResistant()
                    .attributes(MountainHammerItem.createAttributes())));

    private ModItems() {
    }
}
