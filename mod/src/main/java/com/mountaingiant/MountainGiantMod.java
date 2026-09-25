package com.mountaingiant;

import com.mountaingiant.entity.MountainGiant;
import com.mountaingiant.network.TremorPayload;
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
        }
    }
}
