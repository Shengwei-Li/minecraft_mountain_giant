package com.mountaingiant;

import com.mountaingiant.entity.MountainGiant;
import com.mountaingiant.network.TremorPayload;
import com.mountaingiant.registry.ModArmorMaterials;
import com.mountaingiant.registry.ModEntities;
import com.mountaingiant.registry.ModItems;
import net.minecraft.world.item.CreativeModeTabs;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.event.BuildCreativeModeTabContentsEvent;
import net.neoforged.neoforge.event.entity.EntityAttributeCreationEvent;

@Mod(MountainGiantMod.MODID)
public class MountainGiantMod {
    public static final String MODID = "mountain_giant";

    public MountainGiantMod(IEventBus modBus) {
        ModArmorMaterials.MATERIALS.register(modBus);
        ModEntities.ENTITIES.register(modBus);
        ModItems.ITEMS.register(modBus);
        modBus.addListener(this::registerAttributes);
        modBus.addListener(this::addCreativeTabItems);
        modBus.addListener(TremorPayload::register);
    }

    private void registerAttributes(EntityAttributeCreationEvent event) {
        event.put(ModEntities.MOUNTAIN_GIANT.get(), MountainGiant.createAttributes().build());
    }

    private void addCreativeTabItems(BuildCreativeModeTabContentsEvent event) {
        if (event.getTabKey() == CreativeModeTabs.SPAWN_EGGS) {
            event.accept(ModItems.MOUNTAIN_GIANT_SPAWN_EGG.get());
        } else if (event.getTabKey() == CreativeModeTabs.COMBAT) {
            event.accept(ModItems.MOUNTAIN_HAMMER.get());
            event.accept(ModItems.MOUNTAIN_HELMET.get());
            event.accept(ModItems.MOUNTAIN_CHESTPLATE.get());
            event.accept(ModItems.MOUNTAIN_LEGGINGS.get());
            event.accept(ModItems.MOUNTAIN_BOOTS.get());
        } else if (event.getTabKey() == CreativeModeTabs.INGREDIENTS) {
            event.accept(ModItems.MOUNTAIN_HEART.get());
            event.accept(ModItems.MOUNTAIN_PLATE.get());
        }
    }
}
