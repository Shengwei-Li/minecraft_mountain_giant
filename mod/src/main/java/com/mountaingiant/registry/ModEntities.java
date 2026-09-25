package com.mountaingiant.registry;

import com.mountaingiant.MountainGiantMod;
import com.mountaingiant.entity.MountainGiant;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

public final class ModEntities {
    public static final DeferredRegister<EntityType<?>> ENTITIES =
            DeferredRegister.create(Registries.ENTITY_TYPE, MountainGiantMod.MODID);

    // Hitbox covers the legs and torso; the arms and hump stick out visually (~28 blocks tall when rendered).
    public static final DeferredHolder<EntityType<?>, EntityType<MountainGiant>> MOUNTAIN_GIANT =
            ENTITIES.register("mountain_giant", () -> EntityType.Builder.of(MountainGiant::new, MobCategory.MONSTER)
                    .sized(7.0F, 24.0F)
                    .eyeHeight(20.0F)
                    .clientTrackingRange(16)
                    .updateInterval(1) // smooth motion for a huge, slow body
                    .fireImmune()
                    .build("mountain_giant"));

    private ModEntities() {
    }
}
