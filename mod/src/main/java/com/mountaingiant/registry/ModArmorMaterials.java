package com.mountaingiant.registry;

import com.mountaingiant.MountainGiantMod;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.item.ArmorItem;
import net.minecraft.world.item.ArmorMaterial;
import net.minecraft.world.item.crafting.Ingredient;
import net.neoforged.neoforge.registries.DeferredRegister;

import java.util.EnumMap;
import java.util.List;

public final class ModArmorMaterials {
    public static final DeferredRegister<ArmorMaterial> MATERIALS =
            DeferredRegister.create(Registries.ARMOR_MATERIAL, MountainGiantMod.MODID);

    /**
     * A notch above netherite: 4/9/7/4 armour (netherite 3/8/6/3), toughness 3.5 (3),
     * knockback resistance 0.15 per piece (0.1). Worn textures: textures/models/armor/mountain_layer_1/2.png.
     */
    public static final Holder<ArmorMaterial> MOUNTAIN = MATERIALS.register("mountain", () -> {
        EnumMap<ArmorItem.Type, Integer> defense = new EnumMap<>(ArmorItem.Type.class);
        defense.put(ArmorItem.Type.HELMET, 4);
        defense.put(ArmorItem.Type.CHESTPLATE, 9);
        defense.put(ArmorItem.Type.LEGGINGS, 7);
        defense.put(ArmorItem.Type.BOOTS, 4);
        defense.put(ArmorItem.Type.BODY, 13);
        return new ArmorMaterial(defense, 15, SoundEvents.ARMOR_EQUIP_NETHERITE,
                () -> Ingredient.of(ModItems.MOUNTAIN_PLATE.get()),
                List.of(new ArmorMaterial.Layer(ResourceLocation.fromNamespaceAndPath(MountainGiantMod.MODID, "mountain"))),
                3.5F, 0.15F);
    });

    /** Durability multiplier: netherite uses 37, so this is roughly 15% tougher. */
    public static final int DURABILITY_MULTIPLIER = 42;

    private ModArmorMaterials() {
    }
}
