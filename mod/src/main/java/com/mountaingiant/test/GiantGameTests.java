package com.mountaingiant.test;

import com.mojang.authlib.GameProfile;
import com.mojang.logging.LogUtils;
import com.mountaingiant.MountainGiantMod;
import com.mountaingiant.entity.MountainGiant;
import com.mountaingiant.registry.ModEntities;
import com.mountaingiant.registry.ModItems;
import com.mountaingiant.world.GiantSpawner;
import com.mountaingiant.world.GiantWorldData;
import com.mountaingiant.world.Shockwave;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.entity.item.FallingBlockEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.monster.Husk;
import net.minecraft.world.entity.monster.Zombie;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.util.FakePlayerFactory;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import org.slf4j.Logger;

import java.util.UUID;
import java.util.function.Function;

/** Run with: gradlew runGameTestServer */
@GameTestHolder(MountainGiantMod.MODID)
@PrefixGameTestTemplate(false)
public class GiantGameTests {
    private static final Logger LOG = LogUtils.getLogger();

    /** Builds a stone floor (and clears the air above it), keeping the chunks around it ticking. */
    private static BlockPos prepareArena(GameTestHelper helper, int radius) {
        return prepareArena(helper, radius, 0);
    }

    /** Same, with the floor raised {@code lift} blocks so there is room to dig water basins under it. */
    private static BlockPos prepareArena(GameTestHelper helper, int radius, int lift) {
        ServerLevel level = helper.getLevel();
        BlockPos origin = helper.absolutePos(new BlockPos(0, 1 + lift, 0));
        // the test world only ticks the test's own chunk; keep the whole walking area loaded
        int cx = origin.getX() >> 4, cz = origin.getZ() >> 4;
        int chunks = (radius >> 4) + 1;
        for (int x = cx - chunks; x <= cx + chunks; x++) {
            for (int z = cz - chunks; z <= cz + chunks; z++) {
                level.setChunkForced(x, z, true);
            }
        }
        // giants from earlier tests keep wandering the test world and would trample this arena
        for (MountainGiant old : level.getEntities(ModEntities.MOUNTAIN_GIANT.get(), e -> true)) {
            old.discard();
        }
        // leftovers of earlier tests on the same spot (loot, debris)
        level.getEntitiesOfClass(ItemEntity.class, new AABB(origin).inflate(radius + 16)).forEach(Entity::discard);
        level.getEntitiesOfClass(FallingBlockEntity.class, new AABB(origin).inflate(radius + 16)).forEach(Entity::discard);
        for (int x = -radius; x <= radius; x++) {
            for (int z = -radius; z <= radius; z++) {
                // a raised arena stands on solid ground, like the real world (water basins can't drain out of it)
                for (int y = -lift - 1; y <= -1; y++) {
                    level.setBlock(origin.offset(x, y, z), Blocks.STONE.defaultBlockState(), 2);
                }
                for (int y = 0; y < 26; y++) {
                    level.setBlock(origin.offset(x, y, z), Blocks.AIR.defaultBlockState(), 2);
                }
            }
        }
        return origin;
    }

    private static MountainGiant spawnGiant(GameTestHelper helper, BlockPos at) {
        ServerLevel level = helper.getLevel();
        MountainGiant giant = ModEntities.MOUNTAIN_GIANT.get().create(level);
        giant.moveTo(at.getX() + 0.5, at.getY(), at.getZ() + 0.5, 0.0F, 0.0F);
        giant.finalizeSpawn(level, level.getCurrentDifficultyAt(at), MobSpawnType.COMMAND, null);
        level.addFreshEntity(giant);
        return giant;
    }

    /** Tracks the farthest horizontal distance the giant gets from where it started. */
    private static double[] trackReach(GameTestHelper helper, MountainGiant giant) {
        Vec3 start = giant.position();
        double[] reach = {0.0};
        helper.onEachTick(() -> reach[0] = Math.max(reach[0],
                Math.sqrt(Math.pow(giant.getX() - start.x, 2) + Math.pow(giant.getZ() - start.z, 2))));
        return reach;
    }

    @GameTest(template = "empty", timeoutTicks = 400, batch = "walk") // separate batches: the arenas would overlap
    public static void giantWalksOnFlatGround(GameTestHelper helper) {
        BlockPos origin = prepareArena(helper, 48);
        MountainGiant giant = spawnGiant(helper, origin);
        Vec3 start = giant.position();
        helper.runAfterDelay(256, () -> {
            // 56 ticks of roaring, then 200 ticks at ~0.085 blocks/tick (after speeding up)
            double moved = giant.position().distanceTo(start);
            LOG.info("[giant-test] walk: moved {} blocks in 200 walking ticks", moved);
            if (moved < 10.0 || moved > 25.0) {
                helper.fail("unexpected walking distance " + moved);
            } else {
                helper.succeed();
            }
        });
    }

    @GameTest(template = "empty", timeoutTicks = 900, batch = "wall")
    public static void giantBreaksThroughWall(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos origin = prepareArena(helper, 40);
        // a 10-high, 2-thick stone ring around the giant: whichever way it walks, it hits the wall
        for (int x = -20; x <= 20; x++) {
            for (int z = -20; z <= 20; z++) {
                double d = Math.sqrt(x * x + z * z);
                if (d >= 13.0 && d < 15.0) {
                    for (int y = 0; y < 10; y++) {
                        level.setBlock(origin.offset(x, y, z), Blocks.STONE.defaultBlockState(), 2);
                    }
                }
            }
        }
        MountainGiant giant = spawnGiant(helper, origin);
        Vec3 start = giant.position();
        for (int t = 100; t <= 800; t += 100) {
            int tick = t;
            helper.runAfterDelay(t, () -> LOG.info("[giant-test] wall t={} dist={}", tick, giant.position().distanceTo(start)));
        }
        helper.runAfterDelay(850, () -> {
            double moved = giant.position().distanceTo(start);
            LOG.info("[giant-test] wall: ended {} blocks from start", moved);
            if (moved < 20.0) {
                helper.fail("giant did not get through the wall, only " + moved + " blocks from start");
            } else {
                helper.succeed();
            }
        });
    }

    @GameTest(template = "empty", timeoutTicks = 600, batch = "chase")
    public static void giantChasesAndSmashesAttacker(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos origin = prepareArena(helper, 40);
        MountainGiant giant = spawnGiant(helper, origin);
        // a harmless stand-in for a player, 20 blocks away
        Zombie attacker = EntityType.ZOMBIE.create(level);
        attacker.moveTo(origin.getX() + 20.5, origin.getY(), origin.getZ() + 0.5, 0.0F, 0.0F);
        attacker.setNoAi(true);
        attacker.setPersistenceRequired();
        level.addFreshEntity(attacker);
        float startHealth = attacker.getHealth();
        helper.runAfterDelay(60, () -> giant.hurt(level.damageSources().mobAttack(attacker), 1.0F));
        helper.runAfterDelay(80, () -> {
            if (giant.getTarget() != attacker) {
                helper.fail("giant did not target its attacker");
            }
        });
        helper.runAfterDelay(560, () -> {
            LOG.info("[giant-test] chase: attacker health {} -> {} (alive={}), giant {} blocks away",
                    startHealth, attacker.getHealth(), attacker.isAlive(), giant.distanceTo(attacker));
            if (attacker.isAlive() && attacker.getHealth() >= startHealth) {
                helper.fail("giant never hit its attacker");
            } else {
                helper.succeed();
            }
        });
    }

    /** Result of walking out of a round valley whose sides rise 1 block every {@code run} blocks. */
    private record ValleyResult(double maxRise, double reach, int kept, int total) {
    }

    /** Only the inner part of the valley is counted: at the rim the giant steps off the arena. */
    private static final int COUNTED_RADIUS = 30;

    private static void valleyTest(GameTestHelper helper, int run, int maxHeight, Function<ValleyResult, String> check) {
        ServerLevel level = helper.getLevel();
        BlockPos origin = prepareArena(helper, 44);
        int placed = 0;
        for (int x = -40; x <= 40; x++) {
            for (int z = -40; z <= 40; z++) {
                int h = Math.min(maxHeight, Math.max(0, (int) ((Math.sqrt(x * x + z * z) - 8.0) / run)));
                for (int y = 0; y < h; y++) {
                    level.setBlock(origin.offset(x, y, z),
                            (y == h - 1 ? Blocks.GRASS_BLOCK : Blocks.DIRT).defaultBlockState(), 2);
                    if (x * x + z * z <= COUNTED_RADIUS * COUNTED_RADIUS) {
                        placed++;
                    }
                }
            }
        }
        int total = placed;
        MountainGiant giant = spawnGiant(helper, origin);
        double startY = giant.getY();
        Vec3 start = giant.position();
        double[] maxRise = {0.0};
        double[] reach = {0.0};
        helper.onEachTick(() -> {
            double r = Math.sqrt(Math.pow(giant.getX() - start.x, 2) + Math.pow(giant.getZ() - start.z, 2));
            if (r < 38.0) { // ignore stepping off the arena edge
                maxRise[0] = Math.max(maxRise[0], giant.getY() - startY);
            }
            reach[0] = Math.max(reach[0], r);
        });
        helper.runAfterDelay(760, () -> {
            int left = 0;
            for (int x = -40; x <= 40; x++) {
                for (int z = -40; z <= 40; z++) {
                    if (x * x + z * z > COUNTED_RADIUS * COUNTED_RADIUS) {
                        continue;
                    }
                    for (int y = 0; y < maxHeight; y++) {
                        if (!level.getBlockState(origin.offset(x, y, z)).isAir()) {
                            left++;
                        }
                    }
                }
            }
            ValleyResult result = new ValleyResult(maxRise[0], reach[0], left, total);
            LOG.info("[giant-test] valley 1/{}: {}", run, result);
            String error = check.apply(result);
            if (error != null) {
                helper.fail(error);
            } else {
                helper.succeed();
            }
        });
    }

    @GameTest(template = "empty", timeoutTicks = 800, batch = "climb")
    public static void giantClimbsGentleSlopes(GameTestHelper helper) {
        // 1 block every 6: gentle enough to just walk up, leaving the ground as it is
        valleyTest(helper, 6, 4, r -> r.maxRise() < 3.0 ? "did not climb the gentle slope, rose only " + r.maxRise()
                : r.kept() < r.total() * 0.95 ? "dug into the gentle slope: kept " + r.kept() + "/" + r.total()
                : null);
    }

    @GameTest(template = "empty", timeoutTicks = 800, batch = "carve")
    public static void giantCarvesPassThroughHill(GameTestHelper helper) {
        // 1 block every 2, up to 12 high: a real hill, it should cut a gently rising pass instead of climbing it
        valleyTest(helper, 2, 12, r -> r.reach() < 30.0 ? "got stuck in the hill, reached only " + r.reach()
                : r.maxRise() > 6.0 ? "climbed the hill instead of carving through, rose " + r.maxRise()
                : r.kept() > r.total() - 50 ? "did not carve anything"
                : null);
    }

    /** A ring of water {@code depth} deep between the two radii, in a stone basin under the arena floor. */
    private static void buildMoat(ServerLevel level, BlockPos origin, double inner, double outer, int depth) {
        int r = (int) outer + 2;
        for (int x = -r; x <= r; x++) {
            for (int z = -r; z <= r; z++) {
                double d = Math.sqrt(x * x + z * z);
                if (d >= inner - 1.0 && d < outer + 1.0) {
                    for (int y = -depth - 1; y <= -1; y++) {
                        level.setBlock(origin.offset(x, y, z), Blocks.STONE.defaultBlockState(), 2);
                    }
                }
            }
        }
        for (int x = -r; x <= r; x++) {
            for (int z = -r; z <= r; z++) {
                double d = Math.sqrt(x * x + z * z);
                if (d >= inner && d < outer) {
                    for (int y = -depth; y <= -1; y++) {
                        level.setBlock(origin.offset(x, y, z), Blocks.WATER.defaultBlockState(), 2);
                    }
                }
            }
        }
    }

    @GameTest(template = "empty", timeoutTicks = 800, batch = "water")
    public static void giantTurnsBackAtDeepWater(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos origin = prepareArena(helper, 40, 12);
        buildMoat(level, origin, 14.0, 24.0, 8); // a lake: deeper than it wades
        MountainGiant giant = spawnGiant(helper, origin);
        Vec3 start = giant.position();
        double[] farthest = {0.0};
        helper.onEachTick(() -> farthest[0] = Math.max(farthest[0], giant.position().distanceTo(start)));
        helper.runAfterDelay(760, () -> {
            LOG.info("[giant-test] deep water: farthest {} blocks from start (lake at 14), in water: {}",
                    farthest[0], giant.isInWater());
            if (farthest[0] > 12.0 || giant.isInWater()) {
                helper.fail("giant walked into the deep water");
            } else if (farthest[0] < 4.0) {
                helper.fail("giant did not walk at all");
            } else {
                helper.succeed();
            }
        });
    }

    @GameTest(template = "empty", timeoutTicks = 900, batch = "wade")
    public static void giantWadesThroughShallowWater(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos origin = prepareArena(helper, 44, 6);
        // a river sixteen blocks wide and three deep (a narrower one it simply steps across)
        buildMoat(level, origin, 14.0, 30.0, 3);
        MountainGiant giant = spawnGiant(helper, origin);
        double[] reach = trackReach(helper, giant);
        boolean[] wet = {false};
        helper.onEachTick(() -> wet[0] |= giant.isInWater());
        helper.runAfterDelay(860, () -> {
            LOG.info("[giant-test] wade: reach {} (river from 14 to 30), got wet {}", reach[0], wet[0]);
            if (!wet[0] || reach[0] < 34.0) {
                helper.fail("giant should wade across the shallow river, reached " + reach[0]);
            } else {
                helper.succeed();
            }
        });
    }

    @GameTest(template = "empty", timeoutTicks = 300, batch = "vanish")
    public static void giantVanishesIntoFog(GameTestHelper helper) {
        BlockPos origin = prepareArena(helper, 20);
        MountainGiant giant = spawnGiant(helper, origin);
        helper.runAfterDelay(20, giant::startVanishing);
        helper.runAfterDelay(100, () -> LOG.info("[giant-test] vanish: fog {} alpha {}", giant.getFog(), giant.getModelAlpha()));
        helper.runAfterDelay(240, () -> {
            LOG.info("[giant-test] vanish: removed={}", giant.isRemoved());
            if (!giant.isRemoved()) {
                helper.fail("giant is still there, fog " + giant.getFog());
            } else {
                helper.succeed();
            }
        });
    }

    @GameTest(template = "empty", timeoutTicks = 200, batch = "parts")
    public static void hittingAnArmHurtsTheGiant(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos origin = prepareArena(helper, 20);
        MountainGiant giant = spawnGiant(helper, origin);
        Zombie attacker = EntityType.ZOMBIE.create(level);
        attacker.moveTo(origin.getX() + 15.5, origin.getY(), origin.getZ() + 0.5, 0.0F, 0.0F);
        attacker.setNoAi(true);
        level.addFreshEntity(attacker);
        helper.runAfterDelay(20, () -> {
            float before = giant.getHealth();
            Entity arm = giant.getParts()[0];
            boolean inWorld = level.getEntities(null, arm.getBoundingBox().inflate(0.1)).contains(arm);
            arm.hurt(level.damageSources().mobAttack(attacker), 10.0F);
            LOG.info("[giant-test] parts: arm at {} (findable in world: {}), health {} -> {}, target {}",
                    arm.position(), inWorld, before, giant.getHealth(), giant.getTarget());
            if (!inWorld) {
                helper.fail("arm hitbox is not visible to hit detection");
            } else if (giant.getHealth() >= before || giant.getTarget() != attacker) {
                helper.fail("hitting the arm did not hurt / anger the giant");
            } else {
                helper.succeed();
            }
        });
    }

    @GameTest(template = "empty", timeoutTicks = 200, batch = "ore")
    public static void oreFallsOffEveryTenPercent(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos origin = prepareArena(helper, 24);
        MountainGiant giant = spawnGiant(helper, origin);
        helper.runAfterDelay(10, () -> {
            giant.setHealth(giant.getMaxHealth() * 0.70F + 3.0F); // just short of 30% lost
            giant.hurt(level.damageSources().generic(), 20.0F);    // tips it over 30% (armour absorbs some)
        });
        helper.runAfterDelay(60, () -> {
            int copper = 0;
            int iron = 0;
            for (ItemEntity item : level.getEntitiesOfClass(ItemEntity.class, new AABB(origin).inflate(40))) {
                if (item.getItem().is(Items.RAW_COPPER)) {
                    copper += item.getItem().getCount();
                }
                if (item.getItem().is(Items.RAW_IRON)) {
                    iron += item.getItem().getCount();
                }
            }
            LOG.info("[giant-test] ore: tier {}, dropped {} raw copper, {} raw iron", giant.getOreTier(), copper, iron);
            if (giant.getOreTier() != 3 || copper != 30 || iron != 30) {
                helper.fail("expected tier 3 with 30 copper + 30 iron, got tier " + giant.getOreTier()
                        + ", " + copper + " copper, " + iron + " iron");
            } else {
                helper.succeed();
            }
        });
    }

    @GameTest(template = "empty", timeoutTicks = 700, batch = "perf")
    public static void tramplingAForestPerformance(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos origin = prepareArena(helper, 44);
        // dense oak forest: a tree every 5 blocks
        int trees = 0;
        BlockState leaves = Blocks.OAK_LEAVES.defaultBlockState().setValue(LeavesBlock.PERSISTENT, true);
        for (int x = -40; x <= 40; x += 5) {
            for (int z = -40; z <= 40; z += 5) {
                if (Math.abs(x) < 8 && Math.abs(z) < 8) {
                    continue;
                }
                trees++;
                for (int y = 0; y < 6; y++) {
                    level.setBlock(origin.offset(x, y, z), Blocks.OAK_LOG.defaultBlockState(), 2);
                }
                for (int dx = -2; dx <= 2; dx++) {
                    for (int dz = -2; dz <= 2; dz++) {
                        for (int y = 4; y < 8; y++) {
                            BlockPos p = origin.offset(x + dx, y, z + dz);
                            if ((dx != 0 || dz != 0) && level.getBlockState(p).isAir()) {
                                level.setBlock(p, leaves, 2);
                            }
                        }
                    }
                }
            }
        }
        int forest = trees;
        MinecraftServer server = level.getServer();
        long[] baseline = {0};
        long[] worst = {0};
        long[] last = {System.nanoTime()};
        helper.runAfterDelay(40, () -> baseline[0] = server.getAverageTickTimeNanos());
        MountainGiant giant = spawnGiant(helper, origin);
        Vec3 start = giant.position();
        helper.onEachTick(() -> {
            long now = System.nanoTime();
            worst[0] = Math.max(worst[0], now - last[0]);
            last[0] = now;
        });
        helper.runAfterDelay(640, () -> {
            double avgMs = server.getAverageTickTimeNanos() / 1.0E6;
            LOG.info("[giant-test] perf: {} trees, giant walked {} blocks; avg server tick {} ms (before {} ms), "
                            + "worst tick-to-tick gap {} ms (50 = normal)",
                    forest, String.format("%.1f", giant.position().distanceTo(start)), String.format("%.2f", avgMs),
                    String.format("%.2f", baseline[0] / 1.0E6), String.format("%.1f", worst[0] / 1.0E6));
            if (avgMs > 25.0) {
                helper.fail("trampling a forest is too slow: " + avgMs + " ms per tick");
            } else {
                helper.succeed();
            }
        });
    }

    @GameTest(template = "empty", timeoutTicks = 700, batch = "house")
    public static void giantSmashesHouses(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos origin = prepareArena(helper, 40);
        // a ring of low plank "houses" (5 high) around it: too small to count as a wall of rock
        for (int x = -16; x <= 16; x++) {
            for (int z = -16; z <= 16; z++) {
                double d = Math.sqrt(x * x + z * z);
                if (d >= 12.0 && d < 14.0) {
                    for (int y = 0; y < 5; y++) {
                        level.setBlock(origin.offset(x, y, z), Blocks.OAK_PLANKS.defaultBlockState(), 2);
                    }
                }
            }
        }
        MountainGiant giant = spawnGiant(helper, origin);
        double[] reach = trackReach(helper, giant);
        helper.runAfterDelay(660, () -> {
            LOG.info("[giant-test] house: smashes {}, reach {}", giant.smashesPerformed, reach[0]);
            if (giant.smashesPerformed < 1) {
                helper.fail("walked through the houses without smashing them");
            } else if (reach[0] < 20.0) {
                helper.fail("did not get through the houses, reach " + reach[0]);
            } else {
                helper.succeed();
            }
        });
    }

    @GameTest(template = "empty", timeoutTicks = 700, batch = "pond")
    public static void giantWalksOverPonds(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos origin = prepareArena(helper, 44);
        // small 3x3 ponds scattered all around (village ponds, field ditches)
        for (int px = -30; px <= 30; px += 7) {
            for (int pz = -30; pz <= 30; pz += 7) {
                if (Math.abs(px) < 8 && Math.abs(pz) < 8) {
                    continue;
                }
                for (int dx = -1; dx <= 1; dx++) {
                    for (int dz = -1; dz <= 1; dz++) {
                        level.setBlock(origin.offset(px + dx, -2, pz + dz), Blocks.STONE.defaultBlockState(), 2);
                        level.setBlock(origin.offset(px + dx, -1, pz + dz), Blocks.WATER.defaultBlockState(), 2);
                    }
                }
            }
        }
        MountainGiant giant = spawnGiant(helper, origin);
        double[] reach = trackReach(helper, giant);
        helper.runAfterDelay(660, () -> {
            LOG.info("[giant-test] pond: reach {}", reach[0]);
            if (reach[0] < 30.0) {
                helper.fail("turned back at small ponds, reach only " + reach[0]);
            } else {
                helper.succeed();
            }
        });
    }

    @GameTest(template = "empty", timeoutTicks = 400, batch = "spawn")
    public static void naturalGiantIsUniqueAndEmergesFromMist(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos origin = prepareArena(helper, 30);
        GiantWorldData data = GiantWorldData.get(level);
        MountainGiant giant = GiantSpawner.spawnAt(level, origin);
        helper.runAfterDelay(5, () -> {
            boolean second = data.claim(UUID.randomUUID(), level.getGameTime());
            LOG.info("[giant-test] spawn: natural={} emerging={} fog={} recorded={} second giant allowed={}",
                    giant.isNaturalSpawn(), giant.isEmerging(), giant.getFog(), data.hasGiant(level.getGameTime()), second);
            if (!giant.isNaturalSpawn() || !giant.isEmerging() || giant.getFog() < 0.5F) {
                helper.fail("natural giant should start inside thick mist");
            } else if (!data.hasGiant(level.getGameTime()) || second) {
                helper.fail("world record does not keep it unique");
            }
        });
        helper.runAfterDelay(200, () -> {
            LOG.info("[giant-test] spawn: after emerging fog={} alpha={}", giant.getFog(), giant.getModelAlpha());
            if (giant.isEmerging() || giant.getModelAlpha() < 1.0F) {
                helper.fail("giant did not finish emerging");
            }
            giant.kill();
        });
        helper.runAfterDelay(300, () -> {
            int hearts = 0;
            int diamonds = 0;
            int plates = 0;
            for (ItemEntity item : level.getEntitiesOfClass(ItemEntity.class, new AABB(origin).inflate(40))) {
                if (item.getItem().is(ModItems.MOUNTAIN_HEART.get())) {
                    hearts += item.getItem().getCount();
                }
                if (item.getItem().is(Items.DIAMOND)) {
                    diamonds += item.getItem().getCount();
                }
                if (item.getItem().is(ModItems.MOUNTAIN_PLATE.get())) {
                    plates += item.getItem().getCount();
                }
            }
            LOG.info("[giant-test] spawn: after death recorded={} hearts={} diamonds={} plates={}",
                    data.hasGiant(level.getGameTime()), hearts, diamonds, plates);
            if (data.hasGiant(level.getGameTime())) {
                helper.fail("dead giant still blocks new ones");
            } else if (hearts != 1 || diamonds != 10 || plates != 2) {
                helper.fail("expected 1 heart, 10 diamonds, 2 plates, got " + hearts + " / " + diamonds + " / " + plates);
            } else {
                helper.succeed();
            }
        });
    }

    @GameTest(template = "empty", timeoutTicks = 100, batch = "hammer")
    public static void hammerSlamHitsThreeRings(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos origin = prepareArena(helper, 16);
        // the wielder, and a mob standing in each ring plus one out of reach
        // husks: zombies would burn in the test world's daylight and spoil the numbers
        Husk wielder = EntityType.HUSK.create(level);
        wielder.moveTo(origin.getX() + 0.5, origin.getY(), origin.getZ() + 0.5, 0.0F, 0.0F);
        wielder.setNoAi(true);
        level.addFreshEntity(wielder);
        double[] distances = {2.0, 4.5, 7.0, 11.0};
        Husk[] targets = new Husk[distances.length];
        for (int i = 0; i < distances.length; i++) {
            Husk z = EntityType.HUSK.create(level);
            z.moveTo(origin.getX() + 0.5 + distances[i], origin.getY(), origin.getZ() + 0.5, 0.0F, 0.0F);
            z.setNoAi(true);
            level.addFreshEntity(z);
            targets[i] = z;
        }
        helper.runAfterDelay(5, () -> Shockwave.start(level, wielder));
        helper.runAfterDelay(30, () -> {
            float[] lost = new float[targets.length];
            for (int i = 0; i < targets.length; i++) {
                lost[i] = targets[i].getMaxHealth() - targets[i].getHealth();
            }
            LOG.info("[giant-test] hammer: damage by distance 2 / 4.5 / 7 / 11 = {} / {} / {} / {}, wielder hurt {}",
                    lost[0], lost[1], lost[2], lost[3], wielder.getMaxHealth() - wielder.getHealth());
            if (!(lost[0] > lost[1] && lost[1] > lost[2] && lost[2] > 0 && lost[3] == 0)) {
                helper.fail("rings should hurt less further out and not reach 11 blocks");
            } else if (wielder.getHealth() < wielder.getMaxHealth()) {
                helper.fail("the slam hurt its own wielder");
            } else if (Shockwave.activeCount() != 0) {
                helper.fail("shockwave never finished");
            } else {
                helper.succeed();
            }
        });
    }

    @GameTest(template = "empty", timeoutTicks = 20, batch = "hammer")
    public static void hammerRecipeLoads(GameTestHelper helper) {
        var recipe = helper.getLevel().getRecipeManager().byKey(
                net.minecraft.resources.ResourceLocation.fromNamespaceAndPath(MountainGiantMod.MODID, "mountain_hammer"));
        LOG.info("[giant-test] hammer recipe loaded: {}", recipe.isPresent());
        if (recipe.isEmpty()) {
            helper.fail("hammer recipe missing");
        } else {
            helper.succeed();
        }
    }

    @GameTest(template = "empty", timeoutTicks = 40, batch = "mining")
    public static void hammerDigsThreeByThree(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos origin = prepareArena(helper, 12);
        ItemStack hammer = new ItemStack(ModItems.MOUNTAIN_HAMMER.get());

        // how it digs: faster than netherite on stone, can take obsidian, no help on dirt (no shovel work)
        float stoneSpeed = hammer.getDestroySpeed(Blocks.STONE.defaultBlockState());
        float dirtSpeed = hammer.getDestroySpeed(Blocks.DIRT.defaultBlockState());
        boolean obsidian = hammer.isCorrectToolForDrops(Blocks.OBSIDIAN.defaultBlockState());

        // two 3x3 stone walls facing the player (+Z); the first has an obsidian block in one corner
        BlockPos first = origin.offset(0, 2, 4);
        BlockPos second = origin.offset(6, 2, 4);
        for (int a = -1; a <= 1; a++) {
            for (int b = -1; b <= 1; b++) {
                level.setBlock(first.offset(a, b, 0), Blocks.STONE.defaultBlockState(), 3);
                level.setBlock(second.offset(a, b, 0), Blocks.STONE.defaultBlockState(), 3);
            }
        }
        level.setBlock(first.offset(1, 1, 0), Blocks.OBSIDIAN.defaultBlockState(), 3);

        // a fake player: unlike the vanilla mock player it never joins the world's player list,
        // which would wake up natural spawning and disturb the other tests
        ServerPlayer player = FakePlayerFactory.get(level, new GameProfile(UUID.randomUUID(), "hammer-test"));
        player.setItemInHand(InteractionHand.MAIN_HAND, hammer);
        // stand 3 blocks in front of each wall, eyes level with its centre, looking straight at it
        player.moveTo(first.getX() + 0.5, first.getY() + 0.5 - player.getEyeHeight(), first.getZ() - 2.5, 0.0F, 0.0F);
        player.gameMode.destroyBlock(first);
        int firstLeft = 0;
        for (int a = -1; a <= 1; a++) {
            for (int b = -1; b <= 1; b++) {
                if (!level.getBlockState(first.offset(a, b, 0)).isAir()) {
                    firstLeft++;
                }
            }
        }
        boolean obsidianKept = level.getBlockState(first.offset(1, 1, 0)).is(Blocks.OBSIDIAN);

        player.setShiftKeyDown(true);
        player.moveTo(second.getX() + 0.5, second.getY() + 0.5 - player.getEyeHeight(), second.getZ() - 2.5, 0.0F, 0.0F);
        player.gameMode.destroyBlock(second);
        int secondLeft = 0;
        for (int a = -1; a <= 1; a++) {
            for (int b = -1; b <= 1; b++) {
                if (!level.getBlockState(second.offset(a, b, 0)).isAir()) {
                    secondLeft++;
                }
            }
        }

        // with a shield in the off hand, plain right click is left to the shield
        player.setShiftKeyDown(false);
        player.setItemInHand(InteractionHand.OFF_HAND, new ItemStack(Items.SHIELD));
        boolean shieldFirst = !hammer.use(level, player, InteractionHand.MAIN_HAND).getResult().consumesAction();
        player.setShiftKeyDown(true);
        boolean sneakSlams = hammer.use(level, player, InteractionHand.MAIN_HAND).getResult().consumesAction();
        player.stopUsingItem();

        LOG.info("[giant-test] mining: speed stone {} dirt {}, obsidian ok {}, 3x3 left {} (obsidian kept {}), "
                        + "sneaking left {}, shield first {}, sneak slams {}",
                stoneSpeed, dirtSpeed, obsidian, firstLeft, obsidianKept, secondLeft, shieldFirst, sneakSlams);
        if (stoneSpeed < 12.0F || dirtSpeed > 1.0F || !obsidian) {
            helper.fail("wrong mining power");
        } else if (firstLeft != 1 || !obsidianKept) {
            helper.fail("3x3 should clear the stone and leave the harder obsidian, left " + firstLeft);
        } else if (secondLeft != 8) {
            helper.fail("sneaking should break a single block, left " + secondLeft);
        } else if (!shieldFirst || !sneakSlams) {
            helper.fail("shield / sneak right click handling is wrong");
        } else {
            helper.succeed();
        }
    }

    @GameTest(template = "empty", timeoutTicks = 500, batch = "stomp")
    public static void standingByItsFeetHurts(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos origin = prepareArena(helper, 30);
        // a ring of husks it has to walk through (husks: zombies would burn in the test world's daylight)
        java.util.List<Husk> bystanders = new java.util.ArrayList<>();
        for (int i = 0; i < 24; i++) {
            double a = i * Math.PI * 2.0 / 24.0;
            Husk husk = EntityType.HUSK.create(level);
            husk.moveTo(origin.getX() + 0.5 + Math.cos(a) * 8.0, origin.getY(), origin.getZ() + 0.5 + Math.sin(a) * 8.0,
                    0.0F, 0.0F);
            husk.setNoAi(true);
            level.addFreshEntity(husk);
            bystanders.add(husk);
        }
        MountainGiant giant = spawnGiant(helper, origin);
        helper.runAfterDelay(460, () -> {
            int hurt = 0;
            float worst = 0.0F;
            for (Husk husk : bystanders) {
                float lost = husk.getMaxHealth() - husk.getHealth();
                if (lost > 0.0F || !husk.isAlive()) {
                    hurt++;
                }
                worst = Math.max(worst, husk.isAlive() ? lost : husk.getMaxHealth());
            }
            LOG.info("[giant-test] stomp: {} of {} bystanders hurt, worst loss {}", hurt, bystanders.size(), worst);
            if (hurt == 0) {
                helper.fail("walking through a crowd hurt nobody");
            } else if (worst > 8.5F) {
                helper.fail("stomping is meant to be a light knock, worst loss " + worst);
            } else {
                helper.succeed();
            }
        });
    }

    @GameTest(template = "empty", timeoutTicks = 20, batch = "spot")
    public static void spawnSpotToleratesRollingGroundAndTrees(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos origin = prepareArena(helper, 20);
        // a tree right by the spot, and gentle bumps up to 5 high: still fine
        for (int y = 0; y < 7; y++) {
            level.setBlock(origin.offset(4, y, 0), Blocks.OAK_LOG.defaultBlockState(), 2);
        }
        for (int y = 0; y < 5; y++) {
            level.setBlock(origin.offset(0, y, 12), Blocks.DIRT.defaultBlockState(), 2);
        }
        boolean rolling = GiantSpawner.isGoodSpot(level, origin);
        // a cliff 9 high a dozen blocks away: too rough
        for (int y = 0; y < 9; y++) {
            level.setBlock(origin.offset(12, y, 0), Blocks.STONE.defaultBlockState(), 2);
        }
        boolean cliff = GiantSpawner.isGoodSpot(level, origin);
        LOG.info("[giant-test] spot: rolling ground with a tree ok={}, next to a cliff ok={} (biome {})", rolling, cliff,
                level.getBiome(origin).unwrapKey().map(k -> k.location().toString()).orElse("?"));
        if (!rolling) {
            helper.fail("gentle bumps and a tree should not rule the spot out");
        } else if (cliff) {
            helper.fail("a 9-high cliff should rule the spot out");
        } else {
            helper.succeed();
        }
    }

    @GameTest(template = "empty", timeoutTicks = 20, batch = "armor")
    public static void mountainArmorUpgradesAndStandsFirm(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos origin = prepareArena(helper, 8);

        // smithing table: plate (template slot) + netherite piece + gold ingot, enchantments carried over
        net.minecraft.world.item.ItemStack netheriteHelmet = new ItemStack(Items.NETHERITE_HELMET);
        netheriteHelmet.setDamageValue(17);
        var input = new net.minecraft.world.item.crafting.SmithingRecipeInput(
                new ItemStack(ModItems.MOUNTAIN_PLATE.get()), netheriteHelmet, new ItemStack(Items.GOLD_INGOT));
        var recipe = level.getRecipeManager().getRecipeFor(net.minecraft.world.item.crafting.RecipeType.SMITHING, input, level);
        ItemStack upgraded = recipe.map(r -> r.value().assemble(input, level.registryAccess())).orElse(ItemStack.EMPTY);

        int[] defense = {
                ((net.minecraft.world.item.ArmorItem) ModItems.MOUNTAIN_HELMET.get()).getDefense(),
                ((net.minecraft.world.item.ArmorItem) ModItems.MOUNTAIN_CHESTPLATE.get()).getDefense(),
                ((net.minecraft.world.item.ArmorItem) ModItems.MOUNTAIN_LEGGINGS.get()).getDefense(),
                ((net.minecraft.world.item.ArmorItem) ModItems.MOUNTAIN_BOOTS.get()).getDefense()};
        int total = defense[0] + defense[1] + defense[2] + defense[3];

        // one husk in the full set, one in plain clothes; both get the same shove
        Husk armoured = EntityType.HUSK.create(level);
        Husk plain = EntityType.HUSK.create(level);
        for (Husk husk : new Husk[]{armoured, plain}) {
            husk.moveTo(origin.getX() + 0.5, origin.getY(), origin.getZ() + 0.5, 0.0F, 0.0F);
            husk.setNoAi(true);
            level.addFreshEntity(husk);
        }
        armoured.setItemSlot(net.minecraft.world.entity.EquipmentSlot.HEAD, new ItemStack(ModItems.MOUNTAIN_HELMET.get()));
        armoured.setItemSlot(net.minecraft.world.entity.EquipmentSlot.CHEST, new ItemStack(ModItems.MOUNTAIN_CHESTPLATE.get()));
        armoured.setItemSlot(net.minecraft.world.entity.EquipmentSlot.LEGS, new ItemStack(ModItems.MOUNTAIN_LEGGINGS.get()));
        armoured.setItemSlot(net.minecraft.world.entity.EquipmentSlot.FEET, new ItemStack(ModItems.MOUNTAIN_BOOTS.get()));
        armoured.knockback(1.5, 1.0, 0.0);
        plain.knockback(1.5, 1.0, 0.0);
        double pushedArmoured = armoured.getDeltaMovement().horizontalDistance();
        double pushedPlain = plain.getDeltaMovement().horizontalDistance();

        // worn armour only counts once the entity has ticked with it on
        helper.runAfterDelay(3, () -> {
            LOG.info("[giant-test] armor: upgrade -> {} (damage {}), defense {}/{}/{}/{} = {}, armour value {}, "
                            + "knockback full set {} vs none {}",
                    upgraded.getItem(), upgraded.getDamageValue(), defense[0], defense[1], defense[2], defense[3], total,
                    armoured.getArmorValue() - plain.getArmorValue(), pushedArmoured, pushedPlain);
            if (!upgraded.is(ModItems.MOUNTAIN_HELMET.get()) || upgraded.getDamageValue() != 17) {
                helper.fail("netherite helmet + plate + gold should become a Mountain Helmet, keeping its wear");
            } else if (total != 24 || armoured.getArmorValue() - plain.getArmorValue() != 24) {
                // (husks have 2 armour of their own)
                helper.fail("the set should add 24 armour, got " + total + " / "
                        + (armoured.getArmorValue() - plain.getArmorValue()));
            } else if (pushedArmoured > 1.0E-6 || pushedPlain < 0.1) {
                helper.fail("the full set should stop knockback");
            } else {
                helper.succeed();
            }
        });
    }

    @GameTest(template = "empty", timeoutTicks = 20, batch = "horn")
    public static void hornCallsTheGiantOnlyAtNight(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos origin = prepareArena(helper, 8);
        // the horn looks ~80 blocks out: keep that ring of ground loaded
        int cx = origin.getX() >> 4, cz = origin.getZ() >> 4;
        for (int x = cx - 6; x <= cx + 6; x++) {
            for (int z = cz - 6; z <= cz + 6; z++) {
                level.setChunkForced(x, z, true);
            }
        }
        GiantSpawner.cancelOmen();
        long dayTime = level.getDayTime();
        ServerPlayer player = FakePlayerFactory.get(level, new GameProfile(UUID.randomUUID(), "horn-test"));
        player.moveTo(origin.getX() + 0.5, origin.getY(), origin.getZ() + 0.5, 0.0F, 0.0F);

        level.setDayTime(dayTime - dayTime % 24000L + 6000L); // noon
        boolean noon = com.mountaingiant.item.MountainHornItem.call(level, player);
        boolean noonOmen = GiantSpawner.isOmenActive();
        level.setDayTime(dayTime - dayTime % 24000L + 18000L); // midnight
        boolean night = com.mountaingiant.item.MountainHornItem.call(level, player);
        boolean nightOmen = GiantSpawner.isOmenActive();
        boolean again = com.mountaingiant.item.MountainHornItem.call(level, player);
        GiantSpawner.cancelOmen(); // don't let a giant rise into the other tests
        level.setDayTime(dayTime);

        LOG.info("[giant-test] horn: noon answered={} omen={}, midnight answered={} omen={}, second call answered={}",
                noon, noonOmen, night, nightOmen, again);
        if (noon || noonOmen) {
            helper.fail("the horn should not work at noon");
        } else if (!night || !nightOmen) {
            helper.fail("the horn should call a giant at midnight");
        } else if (again) {
            helper.fail("a second call while the first is on its way should be refused");
        } else {
            helper.succeed();
        }
    }

    @GameTest(template = "empty", timeoutTicks = 20, batch = "horn")
    public static void hornRecipe(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        ItemStack gold = new ItemStack(Items.GOLD_BLOCK);
        ItemStack moss = new ItemStack(Items.MOSSY_COBBLESTONE);
        var input = net.minecraft.world.item.crafting.CraftingInput.of(3, 3, java.util.List.of(
                gold, moss, gold, moss, new ItemStack(Items.GOAT_HORN), moss, gold, moss, gold));
        ItemStack result = level.getRecipeManager()
                .getRecipeFor(net.minecraft.world.item.crafting.RecipeType.CRAFTING, input, level)
                .map(r -> r.value().assemble(input, level.registryAccess())).orElse(ItemStack.EMPTY);
        if (result.is(ModItems.MOUNTAIN_HORN.get())) {
            helper.succeed();
        } else {
            helper.fail("gold blocks, mossy cobblestone and a goat horn should make a Mountain Horn, got " + result);
        }
    }
}
