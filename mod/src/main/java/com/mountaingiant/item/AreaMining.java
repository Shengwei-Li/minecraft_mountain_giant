package com.mountaingiant.item;

import com.mountaingiant.MountainGiantMod;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.level.BlockEvent;

/**
 * The Mountain Hammer breaks a 3x3 square across the face you hit. Sneak to break a single block.
 * Neighbours harder than the block you hit stay put (no chipping out obsidian by hitting stone).
 */
@EventBusSubscriber(modid = MountainGiantMod.MODID)
public final class AreaMining {
    /** Set while the extra blocks are being broken, so they don't set off 3x3s of their own. */
    private static boolean breakingArea;

    private AreaMining() {
    }

    @SubscribeEvent
    public static void onBreak(BlockEvent.BreakEvent event) {
        if (breakingArea || !(event.getPlayer() instanceof ServerPlayer player)
                || !(event.getLevel() instanceof ServerLevel level) || player.isShiftKeyDown()) {
            return;
        }
        ItemStack hammer = player.getMainHandItem();
        if (!(hammer.getItem() instanceof MountainHammerItem) || !hammer.isCorrectToolForDrops(event.getState())) {
            return;
        }
        BlockPos center = event.getPos();
        float hardness = event.getState().getDestroySpeed(level, center);
        Direction face = hitFace(player, center);

        breakingArea = true;
        try {
            for (int a = -1; a <= 1; a++) {
                for (int b = -1; b <= 1; b++) {
                    if (a == 0 && b == 0) {
                        continue;
                    }
                    BlockPos pos = offsetInPlane(center, face.getAxis(), a, b);
                    BlockState state = level.getBlockState(pos);
                    float h = state.getDestroySpeed(level, pos);
                    if (state.isAir() || h < 0.0F || h > hardness + 0.01F || !hammer.isCorrectToolForDrops(state)) {
                        continue;
                    }
                    if (hammer.isEmpty() || player.getMainHandItem() != hammer) {
                        break; // the hammer broke part way through
                    }
                    player.gameMode.destroyBlock(pos); // drops, enchantments, durability, protection: all vanilla
                }
            }
        } finally {
            breakingArea = false;
        }
    }

    /** The face of the block the player is looking at (falls back to the direction they face). */
    private static Direction hitFace(ServerPlayer player, BlockPos target) {
        Vec3 eye = player.getEyePosition();
        Vec3 reach = eye.add(player.getLookAngle().scale(player.blockInteractionRange() + 1.0));
        BlockHitResult hit = player.level().clip(new ClipContext(eye, reach, ClipContext.Block.OUTLINE,
                ClipContext.Fluid.NONE, player));
        if (hit.getType() == HitResult.Type.BLOCK && hit.getBlockPos().equals(target)) {
            return hit.getDirection();
        }
        return Direction.orderedByNearest(player)[0].getOpposite();
    }

    /** A block in the plane across the given axis: (a, b) steps along the two other axes. */
    private static BlockPos offsetInPlane(BlockPos center, Direction.Axis axis, int a, int b) {
        return switch (axis) {
            case X -> center.offset(0, a, b);
            case Y -> center.offset(a, 0, b);
            case Z -> center.offset(a, b, 0);
        };
    }
}
