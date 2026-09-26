package com.mountaingiant.registry;

import com.mountaingiant.MountainGiantMod;
import com.mountaingiant.item.MountainArmorItem;
import com.mountaingiant.item.MountainHammerItem;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.item.ArmorItem;
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
                    .attributes(MountainHammerItem.createAttributes())
                    .component(DataComponents.TOOL, MountainHammerItem.createTool())));

    /** Two per slain giant: a slab of its mossy, gold-veined hide. Upgrades netherite armour. */
    public static final DeferredItem<Item> MOUNTAIN_PLATE = ITEMS.register("mountain_plate", () -> new Item(
            new Item.Properties().rarity(Rarity.RARE).fireResistant()));

    public static final DeferredItem<MountainArmorItem> MOUNTAIN_HELMET = armor("mountain_helmet", ArmorItem.Type.HELMET);
    public static final DeferredItem<MountainArmorItem> MOUNTAIN_CHESTPLATE = armor("mountain_chestplate", ArmorItem.Type.CHESTPLATE);
    public static final DeferredItem<MountainArmorItem> MOUNTAIN_LEGGINGS = armor("mountain_leggings", ArmorItem.Type.LEGGINGS);
    public static final DeferredItem<MountainArmorItem> MOUNTAIN_BOOTS = armor("mountain_boots", ArmorItem.Type.BOOTS);

    private static DeferredItem<MountainArmorItem> armor(String name, ArmorItem.Type type) {
        return ITEMS.register(name, () -> new MountainArmorItem(type, new Item.Properties()
                .durability(type.getDurability(ModArmorMaterials.DURABILITY_MULTIPLIER))
                .rarity(Rarity.EPIC)
                .fireResistant()));
    }

    private ModItems() {
    }
}
