package com.mountaingiant.item;

import com.mountaingiant.MountainGiantMod;
import com.mountaingiant.registry.ModArmorMaterials;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ArmorItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.living.LivingKnockBackEvent;

import java.util.List;

/** A piece of Mountain armour. Wearing all four, nothing knocks you back: you stand like the giant does. */
@EventBusSubscriber(modid = MountainGiantMod.MODID)
public class MountainArmorItem extends ArmorItem {
    private static final EquipmentSlot[] SLOTS = {
            EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET};

    public MountainArmorItem(Type type, Properties properties) {
        super(ModArmorMaterials.MOUNTAIN, type, properties);
    }

    public static boolean wearsFullSet(LivingEntity entity) {
        for (EquipmentSlot slot : SLOTS) {
            if (!(entity.getItemBySlot(slot).getItem() instanceof MountainArmorItem)) {
                return false;
            }
        }
        return true;
    }

    @SubscribeEvent
    public static void onKnockBack(LivingKnockBackEvent event) {
        if (wearsFullSet(event.getEntity())) {
            event.setCanceled(true);
        }
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> tooltip, TooltipFlag flag) {
        tooltip.add(Component.translatable("item.mountain_giant.mountain_armor.set").withStyle(ChatFormatting.GOLD));
    }
}
