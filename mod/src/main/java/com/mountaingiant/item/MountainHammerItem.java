package com.mountaingiant.item;

import com.mountaingiant.world.Shockwave;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.EquipmentSlotGroup;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.UseAnim;
import net.minecraft.world.item.component.ItemAttributeModifiers;
import net.minecraft.world.item.component.Tool;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.common.ItemAbilities;
import net.neoforged.neoforge.common.ItemAbility;

import java.util.List;

/**
 * The Mountain Hammer, made from the giant's heart.
 * Left click: a heavy blow, and it digs like the best pickaxe there is, three by three (see {@code AreaMining}).
 * Hold right click to raise it overhead, release to slam the ground: a shockwave rolls out in three rings
 * (see {@link Shockwave}). With a shield in the off hand, right click blocks and sneak + right click slams.
 */
public class MountainHammerItem extends Item {
    /** Total attack damage 11 (1 base + 10), attack speed 1.0 (4 base - 3). */
    private static final double ATTACK_DAMAGE_BONUS = 10.0;
    private static final double ATTACK_SPEED_MODIFIER = -3.0;
    /** Netherite pickaxes dig at 9. */
    public static final float MINING_SPEED = 12.0F;
    /** Ticks it must be held up before a release slams (1 second). */
    public static final int MIN_CHARGE = 20;
    public static final int SLAM_COOLDOWN = 100;
    private static final int USE_DURATION = 72000;

    public MountainHammerItem(Properties properties) {
        super(properties);
    }

    public static ItemAttributeModifiers createAttributes() {
        return ItemAttributeModifiers.builder()
                .add(Attributes.ATTACK_DAMAGE, new AttributeModifier(BASE_ATTACK_DAMAGE_ID, ATTACK_DAMAGE_BONUS,
                        AttributeModifier.Operation.ADD_VALUE), EquipmentSlotGroup.MAINHAND)
                .add(Attributes.ATTACK_SPEED, new AttributeModifier(BASE_ATTACK_SPEED_ID, ATTACK_SPEED_MODIFIER,
                        AttributeModifier.Operation.ADD_VALUE), EquipmentSlotGroup.MAINHAND)
                .build();
    }

    /** Digs everything a netherite pickaxe can (with drops), faster. Shovel and axe blocks stay at hand speed. */
    public static Tool createTool() {
        return new Tool(List.of(
                Tool.Rule.deniesDrops(BlockTags.INCORRECT_FOR_NETHERITE_TOOL),
                Tool.Rule.minesAndDrops(BlockTags.MINEABLE_WITH_PICKAXE, MINING_SPEED)), 1.0F, 1);
    }

    @Override
    public boolean canPerformAction(ItemStack stack, ItemAbility ability) {
        return ItemAbilities.DEFAULT_PICKAXE_ACTIONS.contains(ability);
    }

    // ---- left click ----------------------------------------------------------------

    @Override
    public boolean hurtEnemy(ItemStack stack, LivingEntity target, LivingEntity attacker) {
        return true;
    }

    @Override
    public void postHurtEnemy(ItemStack stack, LivingEntity target, LivingEntity attacker) {
        stack.hurtAndBreak(1, attacker, EquipmentSlot.MAINHAND);
    }

    // ---- right click: charge and slam ---------------------------------------------

    @Override
    public UseAnim getUseAnimation(ItemStack stack) {
        return UseAnim.SPEAR; // raised overhead, like winding up a trident throw
    }

    @Override
    public int getUseDuration(ItemStack stack, LivingEntity entity) {
        return USE_DURATION;
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        // a shield in the other hand keeps plain right click for blocking; sneak to slam instead
        if (hand == InteractionHand.MAIN_HAND && !player.isShiftKeyDown()
                && player.getOffhandItem().canPerformAction(ItemAbilities.SHIELD_BLOCK)) {
            return InteractionResultHolder.pass(player.getItemInHand(hand));
        }
        player.startUsingItem(hand);
        return InteractionResultHolder.consume(player.getItemInHand(hand));
    }

    @Override
    public void onUseTick(Level level, LivingEntity user, ItemStack stack, int remainingUseDuration) {
        if (USE_DURATION - remainingUseDuration == MIN_CHARGE && !level.isClientSide()) {
            // a heavy "clunk": fully wound up
            level.playSound(null, user.getX(), user.getY(), user.getZ(), SoundEvents.ANVIL_PLACE,
                    SoundSource.PLAYERS, 0.5F, 0.6F);
        }
    }

    @Override
    public void releaseUsing(ItemStack stack, Level level, LivingEntity user, int timeLeft) {
        int charged = USE_DURATION - timeLeft;
        if (charged < MIN_CHARGE || !(level instanceof ServerLevel server)) {
            return;
        }
        Shockwave.start(server, user);
        user.swing(user.getUsedItemHand(), true);
        stack.hurtAndBreak(2, user, LivingEntity.getSlotForHand(user.getUsedItemHand()));
        if (user instanceof Player player) {
            player.getCooldowns().addCooldown(this, SLAM_COOLDOWN);
        }
    }

    // ---- misc ----------------------------------------------------------------------

    @Override
    public int getEnchantmentValue() {
        return 15;
    }

    @Override
    public boolean isValidRepairItem(ItemStack stack, ItemStack repairCandidate) {
        return repairCandidate.is(Items.IRON_BLOCK);
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> tooltip, TooltipFlag flag) {
        tooltip.add(Component.translatable("item.mountain_giant.mountain_hammer.desc").withStyle(ChatFormatting.GRAY));
        tooltip.add(Component.translatable("item.mountain_giant.mountain_hammer.mine").withStyle(ChatFormatting.GOLD));
        tooltip.add(Component.translatable("item.mountain_giant.mountain_hammer.slam").withStyle(ChatFormatting.GOLD));
        tooltip.add(Component.translatable("item.mountain_giant.mountain_hammer.shield").withStyle(ChatFormatting.DARK_GRAY));
    }
}
