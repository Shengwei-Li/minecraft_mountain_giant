package com.mountaingiant.client;

import com.mountaingiant.MountainGiantMod;
import com.mountaingiant.entity.MountainGiant;
import net.minecraft.resources.ResourceLocation;
import software.bernie.geckolib.animation.AnimationState;
import software.bernie.geckolib.model.DefaultedEntityGeoModel;

/** Default model + hides the ore clusters that have already been knocked off the giant. */
public class MountainGiantModel extends DefaultedEntityGeoModel<MountainGiant> {
    public MountainGiantModel() {
        super(ResourceLocation.fromNamespaceAndPath(MountainGiantMod.MODID, "mountain_giant"), true);
    }

    @Override
    public void setCustomAnimations(MountainGiant giant, long instanceId, AnimationState<MountainGiant> animationState) {
        super.setCustomAnimations(giant, instanceId, animationState);
        int knockedOff = giant.getOreTier();
        for (int tier = 1; tier < MountainGiant.ORE_TIER_BONES.length; tier++) {
            boolean hidden = tier <= knockedOff;
            for (String bone : MountainGiant.ORE_TIER_BONES[tier]) {
                getBone(bone).ifPresent(b -> b.setHidden(hidden));
            }
        }
    }
}
