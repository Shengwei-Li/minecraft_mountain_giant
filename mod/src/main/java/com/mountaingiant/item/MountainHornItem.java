package com.mountaingiant.item;

import com.mountaingiant.world.GiantSpawner;
import com.mountaingiant.world.GiantWorldData;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.UseAnim;
import net.minecraft.world.level.Level;

import java.util.List;

/**
 * The Mountain Horn. Blown in the dead of night, the ground answers: the same tremors and footsteps as a natural
 * visit, then the giant rises out of the mist some 80 blocks away - and leaves again at dawn.
 * Never used up; after calling a giant it needs a whole day's rest.
 */
public class MountainHornItem extends Item {
    /** How long the horn is held to the lips (2 seconds). */
    private static final int BLOW_TICKS = 40;
    /** After a giant has answered: one Minecraft day. */
    public static final int ANSWERED_COOLDOWN = 24000;
    /** After a call nobody answered. */
    private static final int UNANSWERED_COOLDOWN = 60;

    public MountainHornItem(Properties properties) {
        super(properties);
    }

    @Override
    public UseAnim getUseAnimation(ItemStack stack) {
        return UseAnim.TOOT_HORN;
    }

    @Override
    public int getUseDuration(ItemStack stack, LivingEntity entity) {
        return BLOW_TICKS;
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        player.startUsingItem(hand);
        if (level instanceof ServerLevel server) {
            // a deep, far-carrying call
            server.playSound(null, player.getX(), player.getY(), player.getZ(), SoundEvents.RAID_HORN,
                    SoundSource.PLAYERS, 16.0F, 0.75F);
            boolean answered = call(server, player);
            player.getCooldowns().addCooldown(this, answered ? ANSWERED_COOLDOWN : UNANSWERED_COOLDOWN);
        }
        return InteractionResultHolder.consume(stack);
    }

    /** Asks the ground for a giant. Tells the player why not if it doesn't come. */
    public static boolean call(ServerLevel level, Player player) {
        String refusal = null;
        if (level.dimension() != Level.OVERWORLD) {
            refusal = "far";
        } else if (GiantWorldData.get(level).hasGiant(level.getGameTime()) || GiantSpawner.isOmenActive()) {
            refusal = "already";
        } else if (!GiantSpawner.isGiantHour(level)) {
            refusal = "asleep";
        } else if (GiantSpawner.answerHorn(level, player.blockPosition(), level.random) == null) {
            refusal = "no_ground";
        }
        if (refusal != null) {
            player.displayClientMessage(Component.translatable("message.mountain_giant.horn." + refusal)
                    .withStyle(ChatFormatting.GRAY), true);
            return false;
        }
        return true;
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> tooltip, TooltipFlag flag) {
        tooltip.add(Component.translatable("item.mountain_giant.mountain_horn.desc").withStyle(ChatFormatting.GRAY));
        tooltip.add(Component.translatable("item.mountain_giant.mountain_horn.when").withStyle(ChatFormatting.GOLD));
        tooltip.add(Component.translatable("item.mountain_giant.mountain_horn.rest").withStyle(ChatFormatting.DARK_GRAY));
    }
}
