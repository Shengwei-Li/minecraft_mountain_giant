package com.mountaingiant.test;

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
        ServerLevel level = helper.getLevel();
        BlockPos origin = helper.absolutePos(new BlockPos(0, 1, 0));
        // the test world only ticks the test's own chunk; keep the whole walking area loaded
        int cx = origin.getX() >> 4, cz = origin.getZ() >> 4;
        int chunks = (radius >> 4) + 1;
        for (int x = cx - chunks; x <= cx + chunks; x++) {
            for (int z = cz - chunks; z <= cz + chunks; z++) {
                level.setChunkForced(x, z, true);
            }
        }
        // leftovers of earlier tests on the same spot (loot, debris)
        level.getEntitiesOfClass(ItemEntity.class, new AABB(origin).inflate(radius + 16)).forEach(Entity::discard);
        level.getEntitiesOfClass(FallingBlockEntity.class, new AABB(origin).inflate(radius + 16)).forEach(Entity::discard);
        for (int x = -radius; x <= radius; x++) {
            for (int z = -radius; z <= radius; z++) {
                level.setBlock(origin.offset(x, -1, z), Blocks.STONE.defaultBlockState(), 2);
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
                    placed++;
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

    @GameTest(template = "empty", timeoutTicks = 800, batch = "water")
    public static void giantTurnsBackAtWater(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos origin = prepareArena(helper, 40);
        // a moat all around, 14 to 24 blocks out
        for (int x = -24; x <= 24; x++) {
            for (int z = -24; z <= 24; z++) {
                double d = Math.sqrt(x * x + z * z);
                if (d >= 14.0 && d < 24.0) {
                    level.setBlock(origin.offset(x, -2, z), Blocks.STONE.defaultBlockState(), 2);
                    level.setBlock(origin.offset(x, -1, z), Blocks.WATER.defaultBlockState(), 2);
                }
            }
        }
        MountainGiant giant = spawnGiant(helper, origin);
        Vec3 start = giant.position();
        double[] farthest = {0.0};
        helper.onEachTick(() -> farthest[0] = Math.max(farthest[0], giant.position().distanceTo(start)));
        helper.runAfterDelay(760, () -> {
            LOG.info("[giant-test] water: farthest {} blocks from start (moat at 14), in water: {}",
                    farthest[0], giant.isInWater());
            if (farthest[0] > 12.0 || giant.isInWater()) {
                helper.fail("giant walked into the water");
            } else if (farthest[0] < 4.0) {
                helper.fail("giant did not walk at all");
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
            for (ItemEntity item : level.getEntitiesOfClass(ItemEntity.class, new AABB(origin).inflate(40))) {
                if (item.getItem().is(ModItems.MOUNTAIN_HEART.get())) {
                    hearts += item.getItem().getCount();
                }
                if (item.getItem().is(Items.DIAMOND)) {
                    diamonds += item.getItem().getCount();
                }
            }
            LOG.info("[giant-test] spawn: after death recorded={} hearts={} diamonds={}",
                    data.hasGiant(level.getGameTime()), hearts, diamonds);
            if (data.hasGiant(level.getGameTime())) {
                helper.fail("dead giant still blocks new ones");
            } else if (hearts != 1 || diamonds != 10) {
                helper.fail("expected 1 heart and 10 diamonds, got " + hearts + " / " + diamonds);
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
}
