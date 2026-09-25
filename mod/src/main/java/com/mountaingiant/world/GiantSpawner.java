package com.mountaingiant.world;

import com.mojang.brigadier.CommandDispatcher;
import com.mountaingiant.MountainGiantMod;
import com.mountaingiant.entity.MountainGiant;
import com.mountaingiant.network.TremorPayload;
import com.mountaingiant.registry.ModEntities;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.Difficulty;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.tick.LevelTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * Brings the one giant into the world: some nights (between late evening and the small hours) it appears
 * on flat plains near a player - first the ground trembles, then it rises out of the mist.
 */
@EventBusSubscriber(modid = MountainGiantMod.MODID)
public final class GiantSpawner {
    /** Chance that a given night has a giant at all. */
    public static final float NIGHTLY_CHANCE = 0.35F;
    /** Night-time window (day time ticks) in which it may appear: roughly 21:00 to 02:00. */
    public static final long WINDOW_START = 15000L;
    public static final long WINDOW_END = 20000L;
    private static final int ATTEMPT_INTERVAL = 200;
    private static final int OMEN_TICKS = 200;
    private static final double OMEN_RANGE = 160.0;
    private static final int MIN_DISTANCE = 48;
    private static final int MAX_DISTANCE = 96;
    /** Largest height difference allowed around the spawn point. */
    private static final int MAX_UNEVENNESS = 3;

    // the omen in progress (not saved: a restart simply cancels it)
    @Nullable
    private static BlockPos omenPos;
    private static int omenTicks;

    private GiantSpawner() {
    }

    // =================================================================================
    // Nightly spawning
    // =================================================================================

    @SubscribeEvent
    public static void onLevelTick(LevelTickEvent.Post event) {
        if (!(event.getLevel() instanceof ServerLevel level) || level.dimension() != Level.OVERWORLD) {
            return;
        }
        if (omenPos != null) {
            tickOmen(level);
            return;
        }
        if (level.getGameTime() % ATTEMPT_INTERVAL != 0 || !spawningAllowed(level)) {
            return;
        }
        long time = level.getDayTime() % 24000L;
        if (time < WINDOW_START || time >= WINDOW_END) {
            return;
        }
        GiantWorldData data = GiantWorldData.get(level);
        long day = level.getDayTime() / 24000L;
        if (data.getRolledDay() != day) {
            data.setRoll(day, level.random.nextFloat() < NIGHTLY_CHANCE);
        }
        if (!data.isSpawnTonight() || data.hasGiant(level.getGameTime())) {
            return;
        }
        List<ServerPlayer> players = level.players().stream().filter(p -> !p.isSpectator()).toList();
        if (players.isEmpty()) {
            return;
        }
        ServerPlayer player = players.get(level.random.nextInt(players.size()));
        BlockPos spot = findSpawnSpot(level, player.blockPosition(), level.random);
        if (spot != null) {
            startOmen(level, spot);
        }
    }

    private static boolean spawningAllowed(ServerLevel level) {
        return level.getDifficulty() != Difficulty.PEACEFUL && level.getGameRules().getBoolean(GameRules.RULE_DOMOBSPAWNING);
    }

    /** A flat, dry plains spot 48-96 blocks from the player, in loaded chunks. */
    @Nullable
    public static BlockPos findSpawnSpot(ServerLevel level, BlockPos near, RandomSource random) {
        for (int attempt = 0; attempt < 16; attempt++) {
            float angle = random.nextFloat() * Mth.TWO_PI;
            int dist = MIN_DISTANCE + random.nextInt(MAX_DISTANCE - MIN_DISTANCE + 1);
            int x = near.getX() + Mth.floor(Mth.cos(angle) * dist);
            int z = near.getZ() + Mth.floor(Mth.sin(angle) * dist);
            BlockPos spot = groundAt(level, x, z);
            if (spot != null && isGoodSpot(level, spot)) {
                return spot;
            }
        }
        return null;
    }

    @Nullable
    private static BlockPos groundAt(ServerLevel level, int x, int z) {
        if (!level.hasChunk(x >> 4, z >> 4)) {
            return null;
        }
        return new BlockPos(x, level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z), z);
    }

    private static boolean isGoodSpot(ServerLevel level, BlockPos spot) {
        if (!level.getBiome(spot).is(MountainGiant.ROAMING_GROUNDS)) {
            return false;
        }
        int min = spot.getY();
        int max = spot.getY();
        for (int i = 0; i < 9; i++) {
            BlockPos p = i == 0 ? spot : groundAt(level,
                    spot.getX() + Mth.floor(Mth.cos(i * Mth.TWO_PI / 8) * 10.0),
                    spot.getZ() + Mth.floor(Mth.sin(i * Mth.TWO_PI / 8) * 10.0));
            if (p == null || !level.getFluidState(p.below()).isEmpty() || !level.getFluidState(p).isEmpty()) {
                return false;
            }
            min = Math.min(min, p.getY());
            max = Math.max(max, p.getY());
        }
        return max - min <= MAX_UNEVENNESS;
    }

    // =================================================================================
    // The omen: tremors and distant footsteps, then the giant rises out of the mist
    // =================================================================================

    private static void startOmen(ServerLevel level, BlockPos spot) {
        omenPos = spot;
        omenTicks = 0;
        for (ServerPlayer player : playersNear(level, spot)) {
            player.displayClientMessage(Component.translatable("message.mountain_giant.tremor"), true);
        }
    }

    private static void tickOmen(ServerLevel level) {
        BlockPos spot = omenPos;
        omenTicks++;
        float progress = omenTicks / (float) OMEN_TICKS;
        if (omenTicks % 25 == 0) {
            for (ServerPlayer player : playersNear(level, spot)) {
                // a heavy, far-off footfall from the direction the giant will come from
                Vec3 toward = Vec3.atCenterOf(spot).subtract(player.position()).normalize();
                Vec3 sound = player.position().add(toward.scale(20.0));
                level.playSound(null, sound.x, sound.y, sound.z, SoundEvents.RAVAGER_STEP, SoundSource.HOSTILE,
                        1.5F + 2.5F * progress, 0.3F);
                PacketDistributor.sendToPlayer(player, new TremorPayload(0.15F + 0.25F * progress));
            }
        }
        if (omenTicks >= OMEN_TICKS) {
            omenPos = null;
            GiantWorldData data = GiantWorldData.get(level);
            if (!data.hasGiant(level.getGameTime()) && isGoodSpot(level, spot)) {
                spawnAt(level, spot);
            }
        }
    }

    private static List<ServerPlayer> playersNear(ServerLevel level, BlockPos spot) {
        return level.players().stream()
                .filter(p -> p.distanceToSqr(Vec3.atCenterOf(spot)) < OMEN_RANGE * OMEN_RANGE)
                .toList();
    }

    /** Spawns the world's giant at the spot (it emerges from mist) and records it. */
    @Nullable
    public static MountainGiant spawnAt(ServerLevel level, BlockPos spot) {
        MountainGiant giant = ModEntities.MOUNTAIN_GIANT.get().create(level);
        if (giant == null) {
            return null;
        }
        giant.moveTo(spot.getX() + 0.5, spot.getY(), spot.getZ() + 0.5, level.random.nextFloat() * 360.0F, 0.0F);
        giant.finalizeSpawn(level, level.getCurrentDifficultyAt(spot), MobSpawnType.NATURAL, null);
        level.addFreshEntity(giant);
        GiantWorldData data = GiantWorldData.get(level);
        data.claim(giant.getUUID(), level.getGameTime());
        data.setRoll(level.getDayTime() / 24000L, false); // one visit per night
        return giant;
    }

    public static boolean isOmenActive() {
        return omenPos != null;
    }

    // =================================================================================
    // Commands: /mountaingiant omen | status (ops only, for testing)
    // =================================================================================

    @SubscribeEvent
    public static void onRegisterCommands(RegisterCommandsEvent event) {
        CommandDispatcher<CommandSourceStack> dispatcher = event.getDispatcher();
        dispatcher.register(Commands.literal("mountaingiant")
                .requires(source -> source.hasPermission(2))
                .then(Commands.literal("omen").executes(ctx -> {
                    ServerPlayer player = ctx.getSource().getPlayerOrException();
                    ServerLevel level = player.serverLevel();
                    if (level.dimension() != Level.OVERWORLD) {
                        ctx.getSource().sendFailure(Component.translatable("command.mountain_giant.not_overworld"));
                        return 0;
                    }
                    if (GiantWorldData.get(level).hasGiant(level.getGameTime()) || isOmenActive()) {
                        ctx.getSource().sendFailure(Component.translatable("command.mountain_giant.already"));
                        return 0;
                    }
                    BlockPos spot = findSpawnSpot(level, player.blockPosition(), level.random);
                    if (spot == null) {
                        ctx.getSource().sendFailure(Component.translatable("command.mountain_giant.no_spot"));
                        return 0;
                    }
                    startOmen(level, spot);
                    ctx.getSource().sendSuccess(() -> Component.translatable("command.mountain_giant.omen",
                            spot.getX(), spot.getY(), spot.getZ()), true);
                    return 1;
                }))
                .then(Commands.literal("status").executes(ctx -> {
                    ServerLevel level = ctx.getSource().getServer().overworld();
                    GiantWorldData data = GiantWorldData.get(level);
                    long day = level.getDayTime() / 24000L;
                    String tonight = data.getRolledDay() == day ? String.valueOf(data.isSpawnTonight()) : "?";
                    ctx.getSource().sendSuccess(() -> Component.translatable("command.mountain_giant.status",
                            data.hasGiant(level.getGameTime()) ? String.valueOf(data.getGiant()) : "-",
                            tonight, level.getDayTime() % 24000L), false);
                    return 1;
                })));
    }
}
