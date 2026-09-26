package com.mountaingiant.entity;

import com.mountaingiant.MountainGiantMod;
import com.mountaingiant.client.ClientEffects;
import com.mountaingiant.world.GiantWorldData;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerBossEvent;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.TagKey;
import net.minecraft.util.Mth;
import net.minecraft.world.BossEvent;
import net.minecraft.world.DifficultyInstance;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.entity.SpawnGroupData;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.goal.LookAtPlayerGoal;
import net.minecraft.world.entity.ai.goal.RandomLookAroundGoal;
import net.minecraft.world.entity.item.FallingBlockEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.ServerLevelAccessor;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.entity.PartEntity;
import net.neoforged.neoforge.event.EventHooks;
import org.jetbrains.annotations.Nullable;
import software.bernie.geckolib.animatable.GeoEntity;
import software.bernie.geckolib.animatable.instance.AnimatableInstanceCache;
import software.bernie.geckolib.animation.AnimatableManager;
import software.bernie.geckolib.animation.AnimationController;
import software.bernie.geckolib.animation.PlayState;
import software.bernie.geckolib.animation.RawAnimation;
import software.bernie.geckolib.util.GeckoLibUtil;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * A lone, neutral giant. At night it roams its plains in straight lines, trampling whatever is in the way
 * (it follows gentle undulations, carves a pass through real hills, smashes houses), turns back at the plains'
 * edge or at lakes, and only fights back when attacked. Every 10% of health lost knocks ore off its body.
 * At dawn it walks off to the edge of the plains and vanishes into fog.
 */
public class MountainGiant extends Monster implements GeoEntity {
    private static final RawAnimation IDLE = RawAnimation.begin().thenLoop("animation.mountain_giant.idle");
    private static final RawAnimation WALK = RawAnimation.begin().thenLoop("animation.mountain_giant.walk");
    private static final RawAnimation SMASH = RawAnimation.begin().thenPlay("animation.mountain_giant.smash");
    private static final RawAnimation ROAR = RawAnimation.begin().thenPlay("animation.mountain_giant.roar");
    private static final RawAnimation DEATH = RawAnimation.begin().thenPlayAndHold("animation.mountain_giant.death");

    /** Biomes the giant stays in (plains by default, data-driven tag). */
    public static final TagKey<Biome> ROAMING_GROUNDS =
            TagKey.create(Registries.BIOME, ResourceLocation.fromNamespaceAndPath(MountainGiantMod.MODID, "roaming_grounds"));
    /** Biomes it may cross but won't settle in (rivers running through its plains). */
    public static final TagKey<Biome> PASSABLE_GROUNDS =
            TagKey.create(Registries.BIOME, ResourceLocation.fromNamespaceAndPath(MountainGiantMod.MODID, "passable_grounds"));
    /** Natural ground: may be kept and climbed when it is one block above the feet. Everything else in the way breaks. */
    public static final TagKey<Block> TERRAIN =
            TagKey.create(Registries.BLOCK, ResourceLocation.fromNamespaceAndPath(MountainGiantMod.MODID, "giant_terrain"));

    /**
     * Model bones knocked off at each 10%-of-health tier (index = tier). The chest core goes with the diamonds on death.
     */
    public static final String[][] ORE_TIER_BONES = {
            {},
            {"ore_copper_r_forearm", "ore_copper_l_forearm", "ore_copper_hump"},
            {"ore_iron_r_shoulder", "ore_iron_l_shoulder"},
            {"ore_iron_back"},
            {"ore_gold_r_shin"},
            {"ore_gold_l_thigh"},
            {"ore_coal_l_shin"},
            {"ore_coal_chest"},
            {},
            {},
    };
    private static final int MAX_ORE_TIER = ORE_TIER_BONES.length - 1;

    /** Current ground speed in blocks/tick, drives the walk animation on clients. */
    private static final EntityDataAccessor<Float> DATA_GAIT =
            SynchedEntityData.defineId(MountainGiant.class, EntityDataSerializers.FLOAT);
    /** 0..1: mist around the giant; above 0.5 the giant itself fades out. */
    private static final EntityDataAccessor<Float> DATA_FOG =
            SynchedEntityData.defineId(MountainGiant.class, EntityDataSerializers.FLOAT);
    /** How many 10%-of-health ore tiers have been knocked off. */
    private static final EntityDataAccessor<Integer> DATA_ORE_TIER =
            SynchedEntityData.defineId(MountainGiant.class, EntityDataSerializers.INT);

    // ---- timings (ticks) ----
    private static final int DEATH_TICKS = 70;          // death animation 3.2s
    private static final int ROAR_TICKS = 56;           // roar animation 2.8s
    private static final int SMASH_TICKS = 48;          // smash animation 2.4s
    private static final int SMASH_IMPACT_TICK = 28;    // fists hit the ground at 1.4s
    private static final int EDGE_PAUSE_TICKS = 50;     // standing still at the plains' edge
    private static final int ATTACK_COOLDOWN = 50;
    private static final int LEAVE_TIMEOUT = 2400;      // vanishes where it is if no edge is reached in 2 min
    private static final float FOG_LEAVING = 0.35F;     // mist while it walks off at dawn
    private static final float VANISH_RATE = 1.0F / 160.0F; // 8s from full mist to gone
    private static final int EMERGE_TICKS = 160;        // a natural giant rises out of the mist over 8s
    private static final float EMERGE_FOG = 0.9F;

    // ---- movement (blocks/tick) ----
    private static final double WALK_SPEED = 0.085;     // ~1.7 blocks/s
    private static final double TURN_SPEED = 0.05;      // while swinging round
    private static final double SHUFFLE_SPEED = 0.015;  // turning on the spot while the way ahead is still blocked
    private static final double CHASE_SPEED = 0.13;     // ~2.6 blocks/s, a walking player still gets away
    private static final double VANISH_SPEED = 0.05;
    private static final double ACCELERATION = 0.003;
    private static final double DECELERATION = 0.005;
    private static final float TURN_RATE = 1.2F;        // degrees/tick
    private static final float CHASE_TURN_RATE = 2.5F;

    /** Walk animation: legs swing +-16 deg on 8.5-block legs -> ~4.7 blocks per step, two steps per 3.2s cycle. */
    private static final double STEP_LENGTH = 4.7;
    private static final double ANIM_WALK_SPEED = STEP_LENGTH * 2.0 / 64.0;

    // ---- trampling ----
    private static final double HALF_WIDTH = 3.5;
    /** Half the width of the trampled corridor (~28 blocks). */
    private static final double CLEAR_HALF_WIDTH = 14.0;
    private static final double CLEAR_AHEAD = 3.0;
    /** How far ahead it looks when deciding to smash, so a wall is judged before trampling erodes it. */
    private static final double SMASH_LOOKAHEAD = 6.0;
    private static final double SMASH_REACH = 7.0;
    /** Cleared height above the feet: taller than the whole model, so nothing hangs over it. */
    private static final int CLEAR_HEIGHT = 32;
    /** Solid blocks (3+ above the feet, leaves excluded, body width) in the look-ahead zone that make it smash. */
    private static final int SMASH_THRESHOLD = 90;
    /**
     * A rise of this many blocks within the next ~13 blocks counts as a real hill: carve a pass instead of climbing.
     * (A 1-in-6 slope already rises 2-3 blocks over that distance and should just be walked up.)
     */
    private static final int HILL_RISE = 4;
    /** While carving a pass the road climbs at most one block per this many blocks walked. */
    private static final double PASS_GRADE = 8.0;
    private static final int BREAK_BUDGET_WALK = 96;
    private static final int BREAK_BUDGET_SMASH = 200;
    /** Built blocks (planks, cobble, glass...) right in front that make it smash a house instead of walking through it. */
    private static final int STRUCTURE_THRESHOLD = 16;
    /** After smashing a house it walks on for this long before another house can make it stop (no smash loops). */
    private static final int STRUCTURE_SMASH_GRACE = 40;
    /** Chance that a trampled block drops its item. */
    private static final float TRAMPLE_DROP_CHANCE = 0.07F;
    /** Built blocks and logs sometimes fly off as debris instead of just breaking. */
    private static final float DEBRIS_CHANCE = 0.2F;
    private static final int DEBRIS_PER_TICK = 5;
    /** Water deeper than this (blocks) is a lake or the sea: it turns back. Rivers and ponds are waded through. */
    private static final int DEEP_WATER = 6;
    /** Deep-water columns (of ~48 checked ahead) that make it turn back. */
    private static final int DEEP_WATER_COLUMNS = 12;
    /** A wall or house smash hurts bystanders at this share of a real attack. */
    private static final float COLLATERAL_SMASH = 0.4F;
    /** Standing by a foot when it comes down. */
    private static final float STOMP_DAMAGE = 4.0F;
    private static final double STOMP_RADIUS = 3.0;
    /** Summoned (not natural) giants fade into mist after this long in daylight without a fight. */
    private static final int DAY_CALM_TICKS = 2400;

    // ---- combat ----
    private static final double AGGRO_RANGE = 64.0;
    private static final int AGGRO_MEMORY = 900;        // forgets a target it hasn't been hit by for 45s...
    private static final double AGGRO_MEMORY_RANGE = 32.0; // ...unless the target is still close
    private static final double ATTACK_REACH = HALF_WIDTH + 7.0;
    private static final double SMASH_HIT_OFFSET = HALF_WIDTH + 4.0;
    private static final double SMASH_HIT_RADIUS = 5.0;
    /** Players this close see the boss bar. */
    private static final double BOSS_BAR_RANGE = 64.0;

    public static final byte EVENT_FOOTSTEP = 71;
    public static final byte EVENT_SMASH = 72;

    private final AnimatableInstanceCache cache = GeckoLibUtil.createInstanceCache(this);

    // extra hitboxes so the arms and head can be hit
    private final GiantPart rightArm;
    private final GiantPart leftArm;
    private final GiantPart headPart;
    private final GiantPart[] parts;

    /** Current walking direction (degrees, MC yaw) and the one it turns toward. */
    private float heading;
    private float desiredHeading;
    private double speed;
    private int pauseTicks;
    private int smashTicks;
    private boolean smashIsAttack;
    private int attackCooldown;
    private int nextDriftTick;
    private int nextAngryRoarTick;
    private int nextStructureSmashTick;
    private boolean turningBack;
    private double strideDistance;
    private boolean rightFootNext;
    private Vec3 stuckCheckPos = Vec3.ZERO;
    private int stuckTicks;
    private final LinkedHashSet<BlockPos> breakQueue = new LinkedHashSet<>();

    // carving a pass through hills
    private boolean carving;
    private double distanceSinceClimb = PASS_GRADE;
    private int lastFeetY = Integer.MIN_VALUE;

    private final ServerBossEvent bossEvent =
            new ServerBossEvent(getDisplayName(), BossEvent.BossBarColor.GREEN, BossEvent.BossBarOverlay.NOTCHED_10);
    private int debrisThisTick;
    /** Smashes done so far (walls, houses and attacks); handy for tests. */
    public int smashesPerformed;

    // natural giants: the one in the world record, rising out of the mist when they appear
    private int emergeTicks;

    // dawn departure
    private boolean naturalSpawn;
    private int dayCalmTicks;
    private boolean leaving;
    private boolean vanishing;
    private int leavingTicks;

    public MountainGiant(EntityType<? extends MountainGiant> type, Level level) {
        super(type, level);
        this.xpReward = 200;
        this.heading = this.desiredHeading = this.random.nextFloat() * 360.0F;
        this.rightArm = new GiantPart(this, "right_arm", 7.0F, 20.0F);
        this.leftArm = new GiantPart(this, "left_arm", 7.0F, 20.0F);
        this.headPart = new GiantPart(this, "head", 7.0F, 7.0F);
        this.parts = new GiantPart[]{this.rightArm, this.leftArm, this.headPart};
        // part ids must directly follow the giant's id (same trick as the Ender Dragon)
        setId(ENTITY_COUNTER.getAndAdd(this.parts.length + 1) + 1);
    }

    public static AttributeSupplier.Builder createAttributes() {
        return Monster.createMonsterAttributes()
                .add(Attributes.MAX_HEALTH, 600.0)
                .add(Attributes.ARMOR, 12.0)
                .add(Attributes.MOVEMENT_SPEED, WALK_SPEED) // informational: movement is driven directly
                .add(Attributes.KNOCKBACK_RESISTANCE, 1.0)
                .add(Attributes.ATTACK_DAMAGE, 20.0)
                .add(Attributes.ATTACK_KNOCKBACK, 2.0)
                .add(Attributes.FOLLOW_RANGE, AGGRO_RANGE)
                .add(Attributes.STEP_HEIGHT, 1.5);
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
        super.defineSynchedData(builder);
        builder.define(DATA_GAIT, 0.0F);
        builder.define(DATA_FOG, 0.0F);
        builder.define(DATA_ORE_TIER, 0);
    }

    public float getGait() {
        return this.entityData.get(DATA_GAIT);
    }

    public float getFog() {
        return this.entityData.get(DATA_FOG);
    }

    public int getOreTier() {
        return this.entityData.get(DATA_ORE_TIER);
    }

    /** 1 = solid, 0 = gone. The giant starts fading once the mist is half-way thick. */
    public float getModelAlpha() {
        return Mth.clamp(1.0F - (getFog() - 0.5F) * 2.0F, 0.0F, 1.0F);
    }

    public boolean isVanishing() {
        return this.vanishing;
    }

    @Override
    protected void registerGoals() {
        // Movement is driven by customServerAiStep; goals only move the head.
        this.goalSelector.addGoal(6, new LookAtPlayerGoal(this, Player.class, 48.0F));
        this.goalSelector.addGoal(7, new RandomLookAroundGoal(this));
    }

    @Override
    public SpawnGroupData finalizeSpawn(ServerLevelAccessor level, DifficultyInstance difficulty, MobSpawnType spawnType,
                                        @Nullable SpawnGroupData spawnGroupData) {
        SpawnGroupData data = super.finalizeSpawn(level, difficulty, spawnType, spawnGroupData);
        this.naturalSpawn = spawnType == MobSpawnType.NATURAL || spawnType == MobSpawnType.EVENT
                || spawnType == MobSpawnType.CHUNK_GENERATION;
        this.heading = this.desiredHeading = this.random.nextFloat() * 360.0F;
        setYRot(this.heading);
        setYBodyRot(this.heading);
        if (this.naturalSpawn) {
            // rises out of the mist first, roars when it is solid
            this.emergeTicks = EMERGE_TICKS;
            this.entityData.set(DATA_FOG, EMERGE_FOG);
        } else {
            roar();
        }
        return data;
    }

    public boolean isNaturalSpawn() {
        return this.naturalSpawn;
    }

    // =================================================================================
    // Multipart hitboxes
    // =================================================================================

    @Override
    public void setId(int id) {
        super.setId(id);
        for (int i = 0; i < this.parts.length; i++) {
            this.parts[i].setId(id + i + 1);
        }
    }

    @Override
    public boolean isMultipartEntity() {
        return true;
    }

    @Override
    public PartEntity<?>[] getParts() {
        return this.parts;
    }

    /** Arms hang ~11.5 blocks to each side, slightly forward; the head juts ~10 blocks forward. */
    private void updateParts() {
        float bodyRad = this.yBodyRot * Mth.DEG_TO_RAD;
        placePart(this.rightArm, bodyRad, -11.5, 2.5, 0.0);
        placePart(this.leftArm, bodyRad, 11.5, 2.5, 0.0);
        placePart(this.headPart, this.yHeadRot * Mth.DEG_TO_RAD, 0.0, 10.0, 12.5);
    }

    private void placePart(GiantPart part, float yawRad, double side, double forward, double up) {
        double fx = -Mth.sin(yawRad), fz = Mth.cos(yawRad);
        double x = getX() + fx * forward - fz * side;
        double z = getZ() + fz * forward + fx * side;
        part.xo = part.getX();
        part.yo = part.getY();
        part.zo = part.getZ();
        part.xOld = part.getX();
        part.yOld = part.getY();
        part.zOld = part.getZ();
        part.setPos(x, getY() + up, z);
    }

    // =================================================================================
    // Server brain
    // =================================================================================

    @Override
    protected void customServerAiStep() {
        super.customServerAiStep();
        if (isDeadOrDying()) {
            return;
        }
        if (this.attackCooldown > 0) {
            this.attackCooldown--;
        }
        if (!keepWorldRecord()) {
            return;
        }
        updateBossBar();
        if (this.emergeTicks > 0) {
            emergeStep();
            return;
        }
        watchForDawn();
        updateTarget();

        boolean afterImpact = this.smashTicks > 0 && this.smashTicks <= SMASH_TICKS - SMASH_IMPACT_TICK;
        if (this.smashTicks == 0 || afterImpact) {
            processBreakQueue(afterImpact ? BREAK_BUDGET_SMASH : BREAK_BUDGET_WALK);
        }

        double targetSpeed = this.vanishing ? vanishStep() : decideMovement();
        approachSpeed(targetSpeed);
        applyMovement();
        faceTarget();
    }

    /** Runs the current activity and returns the ground speed it wants. */
    private double decideMovement() {
        if (this.smashTicks > 0) {
            this.smashTicks--;
            if (this.smashTicks == SMASH_TICKS - SMASH_IMPACT_TICK) {
                smashImpact();
            }
            return 0.0;
        }
        if (this.pauseTicks > 0) {
            this.pauseTicks--;
            return 0.0;
        }

        LivingEntity target = getTarget();
        if (target != null) {
            this.turningBack = false;
            if (deepWaterAhead()) {
                loseTarget();
                turnBack();
                return 0.0;
            }
            this.desiredHeading = yawTowards(target.position());
            if (canHit(target) && this.attackCooldown == 0) {
                startSmash(true);
                return 0.0;
            }
        } else if (this.leaving) {
            setFog(Math.min(FOG_LEAVING, getFog() + 0.005F));
            if (++this.leavingTicks > LEAVE_TIMEOUT || !isHomeGround(position())
                    || !isHomeGround(aheadPoint()) || deepWaterAhead()) {
                startVanishing();
                return VANISH_SPEED;
            }
            drift();
        } else if (checkEdge()) {
            return 0.0;
        } else {
            drift();
        }

        if (EventHooks.canEntityGrief(level(), this) && this.tickCount % 4 == 0) {
            this.carving = riseAhead() >= HILL_RISE;
            // a big wall or a house gets smashed with the fists; anything smaller is simply trampled
            boolean house = this.tickCount >= this.nextStructureSmashTick && countStructure() >= STRUCTURE_THRESHOLD;
            if (house || countWall() >= SMASH_THRESHOLD) {
                startSmash(false);
                if (house) {
                    this.nextStructureSmashTick = this.tickCount + SMASH_TICKS + STRUCTURE_SMASH_GRACE;
                }
                return 0.0;
            }
            // the front of the corridor every time; the whole corridor now and then (it may have turned)
            double from = this.tickCount % 20 == 0 ? -HALF_WIDTH : HALF_WIDTH - 1.0;
            trample(from, HALF_WIDTH + CLEAR_AHEAD);
        }

        float turn = turnTowardDesired(target != null ? CHASE_TURN_RATE : TURN_RATE);
        checkStuck();
        if (this.turningBack && blockedAhead()) {
            return SHUFFLE_SPEED; // shuffle round on the spot until it faces away from the edge
        }
        if (Math.abs(turn) > 45.0F) {
            return TURN_SPEED; // swing round in a wide, slow arc instead of spinning in place
        }
        if (target != null) {
            return CHASE_SPEED;
        }
        return this.turningBack ? TURN_SPEED : WALK_SPEED;
    }

    private void approachSpeed(double targetSpeed) {
        if (this.speed < targetSpeed) {
            this.speed = Math.min(targetSpeed, this.speed + ACCELERATION);
        } else {
            this.speed = Math.max(targetSpeed, this.speed - DECELERATION);
        }
        // quantised so the synced value doesn't change every single tick
        float gait = Math.round(this.speed * 400.0) / 400.0F;
        if (gait != getGait()) {
            this.entityData.set(DATA_GAIT, gait);
        }
    }

    /** Moves exactly {@link #speed} blocks per tick along the heading (collisions and gravity still apply). */
    private void applyMovement() {
        setYRot(this.heading);
        setYBodyRot(this.heading);
        Vec3 fwd = forward();
        setDeltaMovement(fwd.x * this.speed, getDeltaMovement().y, fwd.z * this.speed);

        int feetY = feetY();
        if (feetY > this.lastFeetY) {
            this.distanceSinceClimb = 0.0;
        }
        this.lastFeetY = feetY;
        this.distanceSinceClimb += this.speed;

        this.strideDistance += this.speed;
        if (this.speed > 0.01 && this.strideDistance >= STEP_LENGTH) {
            this.strideDistance -= STEP_LENGTH;
            footstep();
        } else if (this.speed <= 0.01) {
            // the walk cycle restarts from the beginning; first foot lands half a step in
            this.strideDistance = STEP_LENGTH * 0.5;
            this.rightFootNext = true;
        }
    }

    /** Returns how many degrees are left to turn. */
    private float turnTowardDesired(float rate) {
        float diff = Mth.wrapDegrees(this.desiredHeading - this.heading);
        this.heading = Mth.wrapDegrees(this.heading + Mth.clamp(diff, -rate, rate));
        if (Math.abs(diff) < 5.0F) {
            this.turningBack = false;
        }
        return diff;
    }

    /** Occasionally drifts a little off its line so it doesn't walk perfectly straight forever. */
    private void drift() {
        if (this.tickCount >= this.nextDriftTick) {
            this.desiredHeading = this.heading + (this.random.nextFloat() - 0.5F) * 70.0F;
            this.nextDriftTick = this.tickCount + 600 + this.random.nextInt(600);
        }
    }

    /**
     * Stops at the edge of the plains or at a lake, looks around, then turns back at a random angle.
     * Returns true when it just stopped.
     */
    private boolean checkEdge() {
        if (this.turningBack || this.tickCount % 10 != 0) {
            return false;
        }
        if (!blockedAhead()) {
            return false;
        }
        turnBack();
        return true;
    }

    /** Its ground ends (when it is on it) or there is deep water just ahead. */
    private boolean blockedAhead() {
        boolean edge = isHomeGround(position()) && !isHomeGround(aheadPoint());
        return edge || deepWaterAhead();
    }

    private void turnBack() {
        this.pauseTicks = EDGE_PAUSE_TICKS;
        this.turningBack = true;
        this.desiredHeading = this.heading + 180.0F + (this.random.nextFloat() - 0.5F) * 120.0F;
        this.nextDriftTick = this.tickCount + 600 + this.random.nextInt(600);
        playSound(SoundEvents.RAVAGER_AMBIENT, 4.0F, 0.4F);
    }

    private Vec3 aheadPoint() {
        return position().add(forward().scale(HALF_WIDTH + 6.0));
    }

    /** Where it roams (plains, snowy plains, meadows) plus what it may cross on the way (rivers). */
    private boolean isHomeGround(Vec3 pos) {
        var biome = level().getBiome(BlockPos.containing(pos));
        return biome.is(ROAMING_GROUNDS) || biome.is(PASSABLE_GROUNDS);
    }

    /**
     * A lake or the sea where the next steps would land. Rivers, ponds and ditches are waded through;
     * only a good share of water deeper than {@link #DEEP_WATER} across the ground ahead (8 wide, 6 deep) counts.
     */
    private boolean deepWaterAhead() {
        Vec3 fwd = forward();
        Vec3 right = new Vec3(-fwd.z, 0.0, fwd.x);
        int feetY = feetY();
        int deep = 0;
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (double along = HALF_WIDTH + 1.0; along <= HALF_WIDTH + 6.0; along += 1.0) {
            for (double side = -HALF_WIDTH; side <= HALF_WIDTH; side += 1.0) {
                Vec3 p = position().add(fwd.scale(along)).add(right.scale(side));
                pos.set(Mth.floor(p.x), feetY, Mth.floor(p.z));
                // the first non-air block from the feet down (a riverbank may drop a few blocks first)
                for (int dy = 0; dy < 5; dy++, pos.move(0, -1, 0)) {
                    BlockState state = level().getBlockState(pos);
                    if (!state.getFluidState().isEmpty()) {
                        if (waterDepth(pos) > DEEP_WATER && ++deep >= DEEP_WATER_COLUMNS) {
                            return true;
                        }
                        break;
                    }
                    if (!state.isAir()) {
                        break;
                    }
                }
            }
        }
        return false;
    }

    /** How many blocks of liquid there are from here down (stops counting a little past {@link #DEEP_WATER}). */
    private int waterDepth(BlockPos surface) {
        BlockPos.MutableBlockPos pos = surface.mutable();
        int depth = 0;
        while (depth <= DEEP_WATER && !level().getFluidState(pos).isEmpty()) {
            depth++;
            pos.move(0, -1, 0);
        }
        return depth;
    }

    /** If the giant barely moved for 3 seconds while trying to walk, turn around. */
    private void checkStuck() {
        if (++this.stuckTicks < 60) {
            return;
        }
        if (this.speed > 0.03 && position().distanceToSqr(this.stuckCheckPos) < 1.0) {
            this.desiredHeading = this.heading + 180.0F + (this.random.nextFloat() - 0.5F) * 90.0F;
            this.turningBack = true;
        }
        this.stuckCheckPos = position();
        this.stuckTicks = 0;
    }

    private int feetY() {
        return Mth.floor(getY() + 0.01);
    }

    private Vec3 forward() {
        float rad = this.heading * Mth.DEG_TO_RAD;
        return new Vec3(-Mth.sin(rad), 0.0, Mth.cos(rad));
    }

    private float yawTowards(Vec3 pos) {
        return (float) (Mth.atan2(pos.z - getZ(), pos.x - getX()) * Mth.RAD_TO_DEG) - 90.0F;
    }

    // =================================================================================
    // One per world, boss bar, emerging from the mist
    // =================================================================================

    /**
     * Natural giants keep the world record fresh. A second natural giant (e.g. an old one reloaded after a new one
     * appeared) fades away. Returns false if this giant is going away.
     */
    private boolean keepWorldRecord() {
        if (!this.naturalSpawn || this.tickCount % 100 != 0 || !(level() instanceof ServerLevel server)) {
            return true;
        }
        if (!GiantWorldData.get(server).claim(getUUID(), server.getGameTime())) {
            discard();
            return false;
        }
        return true;
    }

    @Override
    public void remove(RemovalReason reason) {
        super.remove(reason);
        this.bossEvent.removeAllPlayers();
        if (this.naturalSpawn && reason.shouldDestroy() && level() instanceof ServerLevel server) {
            GiantWorldData.get(server).release(getUUID()); // killed or vanished: the slot is free again
        }
    }

    /** Players near the giant see its health bar; ten notches, one per ore tier. */
    private void updateBossBar() {
        this.bossEvent.setProgress(getHealth() / getMaxHealth());
        if (this.tickCount % 10 != 0 || !(level() instanceof ServerLevel server)) {
            return;
        }
        boolean visible = !this.vanishing && getModelAlpha() > 0.5F;
        for (ServerPlayer player : List.copyOf(this.bossEvent.getPlayers())) {
            if (!visible || player.level() != server || player.distanceToSqr(this) > BOSS_BAR_RANGE * BOSS_BAR_RANGE) {
                this.bossEvent.removePlayer(player);
            }
        }
        if (visible) {
            for (ServerPlayer player : server.players()) {
                if (player.distanceToSqr(this) <= BOSS_BAR_RANGE * BOSS_BAR_RANGE) {
                    this.bossEvent.addPlayer(player);
                }
            }
        }
    }

    @Override
    public void stopSeenByPlayer(ServerPlayer player) {
        super.stopSeenByPlayer(player);
        this.bossEvent.removePlayer(player);
    }

    /** The mist thins until the giant stands there, solid - then it roars. */
    private void emergeStep() {
        this.emergeTicks--;
        setFog(EMERGE_FOG * this.emergeTicks / EMERGE_TICKS);
        approachSpeed(0.0);
        applyMovement();
        if (this.emergeTicks == 0) {
            roar();
        }
    }

    public boolean isEmerging() {
        return this.emergeTicks > 0;
    }

    // =================================================================================
    // Dawn: walk off into the mist and vanish
    // =================================================================================

    private static boolean isNight(Level level) {
        long time = level.getDayTime() % 24000L;
        return time >= 13000L && time < 23000L;
    }

    /**
     * Naturally spawned giants belong to the night: at daybreak they walk off to the edge of their ground.
     * Summoned ones (spawn egg, commands) fade into mist after two quiet minutes of daylight.
     */
    private void watchForDawn() {
        if (this.leaving || isNight(level())) {
            this.dayCalmTicks = 0;
            return;
        }
        if (this.naturalSpawn) {
            startLeaving();
        } else if (getTarget() != null) {
            this.dayCalmTicks = 0; // still fighting
        } else if (++this.dayCalmTicks >= DAY_CALM_TICKS) {
            startLeaving();
            this.leavingTicks = LEAVE_TIMEOUT - 200; // the mist rises for ten seconds, then it goes
        }
    }

    public void startLeaving() {
        this.leaving = true;
        this.leavingTicks = 0;
        this.turningBack = false;
        playSound(SoundEvents.RAVAGER_ROAR, 6.0F, 0.3F);
    }

    /** The last few seconds: keeps walking into thickening mist and fades out. */
    public void startVanishing() {
        if (this.vanishing) {
            return;
        }
        this.vanishing = true;
        this.leaving = true;
        loseTarget();
        this.smashTicks = 0;
        this.pauseTicks = 0;
        playSound(SoundEvents.AMBIENT_CAVE.value(), 6.0F, 0.5F);
    }

    private double vanishStep() {
        float fog = Math.min(1.0F, Math.max(getFog(), FOG_LEAVING) + VANISH_RATE);
        setFog(fog);
        if (fog >= 1.0F) {
            discard(); // gone without a trace (no drops)
        }
        return VANISH_SPEED;
    }

    private void setFog(float fog) {
        float q = Math.round(fog * 200.0F) / 200.0F;
        if (q != getFog()) {
            this.entityData.set(DATA_FOG, q);
        }
    }

    // =================================================================================
    // Anger: neutral until hit, then chases and smashes the attacker
    // =================================================================================

    @Override
    public boolean hurt(DamageSource source, float amount) {
        if (this.vanishing) {
            return false; // already half mist
        }
        boolean hurt = super.hurt(source, amount);
        if (!hurt || level().isClientSide()) {
            return hurt;
        }
        knockOffOre();
        if (!isDeadOrDying() && source.getEntity() instanceof LivingEntity attacker && isValidTarget(attacker)) {
            boolean newTarget = getTarget() != attacker;
            setTarget(attacker);
            if (newTarget && this.tickCount >= this.nextAngryRoarTick && this.smashTicks == 0) {
                this.nextAngryRoarTick = this.tickCount + 600;
                roar();
            }
        }
        return hurt;
    }

    private boolean isValidTarget(LivingEntity entity) {
        if (!entity.isAlive() || entity instanceof MountainGiant || entity.isRemoved()) {
            return false;
        }
        return !(entity instanceof Player player) || (!player.isCreative() && !player.isSpectator());
    }

    /** Gives up when the target escapes: too far, out of the plains, or not heard from in a while. */
    private void updateTarget() {
        LivingEntity target = getTarget();
        if (target == null) {
            return;
        }
        double dist = horizontalDistanceTo(target);
        boolean forgotten = this.tickCount - getLastHurtByMobTimestamp() > AGGRO_MEMORY && dist > AGGRO_MEMORY_RANGE;
        if (!isValidTarget(target) || target.level() != level() || dist > AGGRO_RANGE || forgotten
                || !isHomeGround(target.position())) {
            loseTarget();
            this.desiredHeading = this.heading;
            playSound(SoundEvents.RAVAGER_AMBIENT, 4.0F, 0.35F);
        }
    }

    private void loseTarget() {
        setTarget(null);
        setLastHurtByMob(null);
    }

    private void faceTarget() {
        LivingEntity target = getTarget();
        if (target != null) {
            getLookControl().setLookAt(target, 30.0F, 30.0F);
        }
    }

    private boolean canHit(LivingEntity target) {
        if (horizontalDistanceTo(target) > ATTACK_REACH || target.getY() > getY() + 12.0) {
            return false;
        }
        float off = Mth.wrapDegrees(yawTowards(target.position()) - this.heading);
        return Math.abs(off) < 35.0F;
    }

    private double horizontalDistanceTo(LivingEntity entity) {
        double dx = entity.getX() - getX();
        double dz = entity.getZ() - getZ();
        return Math.sqrt(dx * dx + dz * dz);
    }

    private void roar() {
        this.pauseTicks = Math.max(this.pauseTicks, ROAR_TICKS);
        triggerAnim("action", "roar");
        playSound(SoundEvents.RAVAGER_ROAR, 5.0F, 0.45F);
    }

    // =================================================================================
    // Ore knocked off every 10% of health
    // =================================================================================

    private void knockOffOre() {
        float lost = 1.0F - getHealth() / getMaxHealth();
        int reached = Math.min(MAX_ORE_TIER, Mth.floor(lost * 10.0F + 1.0E-4F));
        int tier = getOreTier();
        while (tier < reached) {
            tier++;
            dropOreTier(tier);
        }
        this.entityData.set(DATA_ORE_TIER, tier);
    }

    /** 10%: 30 raw copper, 20%: 20 raw iron, every further 10%: 10 raw iron. */
    private void dropOreTier(int tier) {
        if (!(level() instanceof ServerLevel server)) {
            return;
        }
        ItemStack loot = tier == 1 ? new ItemStack(Items.RAW_COPPER, 30)
                : new ItemStack(Items.RAW_IRON, tier == 2 ? 20 : 10);
        BlockState oreBlock = tier == 1 ? Blocks.COPPER_ORE.defaultBlockState() : Blocks.IRON_ORE.defaultBlockState();
        Vec3 burst = position().add(forward().scale(3.0)).add(0.0, 14.0, 0.0);

        // spray the loot in a few clumps
        while (!loot.isEmpty()) {
            ItemStack clump = loot.split(1 + this.random.nextInt(6));
            ItemEntity item = new ItemEntity(server, burst.x, burst.y, burst.z, clump);
            item.setDeltaMovement((this.random.nextDouble() - 0.5) * 0.6, 0.3 + this.random.nextDouble() * 0.3,
                    (this.random.nextDouble() - 0.5) * 0.6);
            item.setDefaultPickUpDelay();
            server.addFreshEntity(item);
        }
        server.sendParticles(new BlockParticleOption(ParticleTypes.BLOCK, oreBlock),
                burst.x, burst.y, burst.z, 80, 2.5, 3.0, 2.5, 0.25);
        server.playSound(null, burst.x, burst.y, burst.z, SoundEvents.ANCIENT_DEBRIS_BREAK, getSoundSource(), 4.0F, 0.6F);
        server.playSound(null, burst.x, burst.y, burst.z, SoundEvents.STONE_BREAK, getSoundSource(), 4.0F, 0.5F);
    }

    // =================================================================================
    // Smash (walls, houses and attacks)
    // =================================================================================

    private void startSmash(boolean attack) {
        this.smashesPerformed++;
        this.smashTicks = SMASH_TICKS;
        this.smashIsAttack = attack;
        triggerAnim("action", "smash");
        playSound(SoundEvents.RAVAGER_ROAR, 4.0F, 0.6F);
    }

    private void smashImpact() {
        if (!(level() instanceof ServerLevel server)) {
            return;
        }
        Vec3 fwd = forward();
        Vec3 hit = position().add(fwd.scale(SMASH_HIT_OFFSET));
        server.playSound(null, hit.x, hit.y, hit.z, SoundEvents.GENERIC_EXPLODE.value(), getSoundSource(), 5.0F, 0.55F);
        server.playSound(null, hit.x, hit.y, hit.z, SoundEvents.ANVIL_LAND, getSoundSource(), 3.0F, 0.4F);
        server.sendParticles(ParticleTypes.EXPLOSION_EMITTER, hit.x, hit.y + 1.0, hit.z, 1, 0, 0, 0, 0);
        BlockState ground = server.getBlockState(BlockPos.containing(hit.x, getY() - 0.5, hit.z));
        if (!ground.isAir()) {
            server.sendParticles(new BlockParticleOption(ParticleTypes.BLOCK, ground),
                    hit.x, hit.y + 0.5, hit.z, 150, 3.0, 0.8, 3.0, 0.4);
        }
        // a real attack hits full force; bystanders of a wall or house smash get a lighter knock
        damageAround(server, hit, this.smashIsAttack ? 1.0F : COLLATERAL_SMASH);
        if (EventHooks.canEntityGrief(level(), this)) {
            trample(-HALF_WIDTH, HALF_WIDTH + SMASH_REACH);
        }
        level().broadcastEntityEvent(this, EVENT_SMASH);
        if (this.smashIsAttack) {
            this.attackCooldown = ATTACK_COOLDOWN;
        }
    }

    /** Everything near the fists gets hurt and thrown back; closer means harder. */
    private void damageAround(ServerLevel server, Vec3 hit, float scale) {
        float damage = (float) getAttributeValue(Attributes.ATTACK_DAMAGE) * scale;
        AABB area = new AABB(hit, hit).inflate(SMASH_HIT_RADIUS, 3.0, SMASH_HIT_RADIUS).expandTowards(0, 4.0, 0);
        for (LivingEntity victim : server.getEntitiesOfClass(LivingEntity.class, area, this::canBeCrushed)) {
            double dist = Math.sqrt(victim.distanceToSqr(hit.x, victim.getY(), hit.z));
            if (dist > SMASH_HIT_RADIUS) {
                continue;
            }
            float falloff = (float) (1.0 - dist / SMASH_HIT_RADIUS);
            if (victim.hurt(damageSources().mobAttack(this), damage * (0.4F + 0.6F * falloff))) {
                Vec3 away = victim.position().subtract(hit).multiply(1, 0, 1);
                away = away.lengthSqr() < 1.0E-4 ? forward() : away.normalize();
                double strength = 1.2 * (0.5 + falloff) * (1.0 - victim.getAttributeValue(Attributes.KNOCKBACK_RESISTANCE));
                victim.push(away.x * strength, 0.6 * strength + 0.2, away.z * strength);
                victim.hurtMarked = true;
            }
        }
    }

    // =================================================================================
    // Trampling
    // =================================================================================

    /**
     * How high natural ground rises in front of the giant (sampled over the next ~13 blocks, body width).
     * A few blocks or more means a real hill.
     */
    private int riseAhead() {
        Vec3 fwd = forward();
        Vec3 right = new Vec3(-fwd.z, 0.0, fwd.x);
        int feetY = feetY();
        int rise = 0;
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (double along = HALF_WIDTH + 1.0; along <= HALF_WIDTH + 10.0; along += 2.0) {
            for (double side = -HALF_WIDTH; side <= HALF_WIDTH; side += HALF_WIDTH) {
                Vec3 p = position().add(fwd.scale(along)).add(right.scale(side));
                pos.set(Mth.floor(p.x), feetY, Mth.floor(p.z));
                int h = 0;
                while (h < 8 && level().getBlockState(pos).is(TERRAIN)) {
                    h++;
                    pos.move(0, 1, 0);
                }
                rise = Math.max(rise, h);
            }
        }
        return rise;
    }

    /**
     * Natural ground one block above the feet is kept (the giant steps up onto it) - unless it is carving a pass
     * through a hill, where the road only climbs one block every {@link #PASS_GRADE} blocks.
     */
    private boolean keepsStep() {
        return !this.carving || this.distanceSinceClimb >= PASS_GRADE;
    }

    private boolean shouldBreak(BlockState state, BlockPos pos, int feetY, boolean keepStep) {
        if (!canFlatten(state, pos)) {
            return false;
        }
        return !(keepStep && pos.getY() == feetY && state.is(TERRAIN));
    }

    /** Solid blocks (not leaves) that would be broken 3+ blocks above the feet, right in front of the body. */
    private int countWall() {
        int feetY = feetY();
        boolean keepStep = keepsStep();
        int[] wall = {0};
        forEachCorridorBlock(HALF_WIDTH - 0.5, HALF_WIDTH + SMASH_LOOKAHEAD, HALF_WIDTH + 1.5, feetY + 3, (pos, state) -> {
            if (!state.is(BlockTags.LEAVES) && shouldBreak(state, pos, feetY, keepStep)) {
                wall[0]++;
            }
        });
        return wall[0];
    }

    /** Built things: blocks you collide with that are neither natural ground nor trees. */
    private static boolean isStructure(BlockState state) {
        return state.blocksMotion() && !state.is(TERRAIN) && !state.is(BlockTags.LOGS) && !state.is(BlockTags.LEAVES);
    }

    /** Built blocks right in front of the body (a house, a wall...), at any height above the feet. */
    private int countStructure() {
        int feetY = feetY();
        int[] count = {0};
        forEachCorridorBlock(HALF_WIDTH - 0.5, HALF_WIDTH + SMASH_LOOKAHEAD, HALF_WIDTH + 1.5, feetY, (pos, state) -> {
            if (isStructure(state) && canFlatten(state, pos)) {
                count[0]++;
            }
        });
        return count[0];
    }

    /**
     * Queues everything in the way inside the ~28 wide corridor between the two distances along the heading,
     * nearest first and top down, so things look pushed over rather than erased.
     */
    private void trample(double fromAlong, double toAlong) {
        int feetY = feetY();
        boolean keepStep = keepsStep();
        Vec3 fwd = forward();
        List<BlockPos> found = new ArrayList<>();
        forEachCorridorBlock(fromAlong, toAlong, CLEAR_HALF_WIDTH, feetY, (pos, state) -> {
            if (shouldBreak(state, pos, feetY, keepStep) && !this.breakQueue.contains(pos)) {
                found.add(pos.immutable());
            }
        });
        found.sort(Comparator.<BlockPos>comparingDouble(p -> Math.floor((p.getX() + 0.5 - getX()) * fwd.x
                        + (p.getZ() + 0.5 - getZ()) * fwd.z))
                .thenComparing(Comparator.<BlockPos>comparingInt(BlockPos::getY).reversed()));
        this.breakQueue.addAll(found);
    }

    private interface BlockVisitor {
        void visit(BlockPos pos, BlockState state);
    }

    /** Visits non-air blocks from {@code minY} up to the clear height, in a rectangle aligned with the heading. */
    private void forEachCorridorBlock(double fromAlong, double toAlong, double halfWidth, int minY, BlockVisitor visitor) {
        Vec3 fwd = forward();
        Vec3 right = new Vec3(-fwd.z, 0.0, fwd.x);
        int maxY = feetY() + CLEAR_HEIGHT;
        // bounding box of the rotated rectangle
        double minX = Double.MAX_VALUE, maxX = -Double.MAX_VALUE, minZ = Double.MAX_VALUE, maxZ = -Double.MAX_VALUE;
        for (double a : new double[]{fromAlong, toAlong}) {
            for (double s : new double[]{-halfWidth, halfWidth}) {
                double x = getX() + fwd.x * a + right.x * s;
                double z = getZ() + fwd.z * a + right.z * s;
                minX = Math.min(minX, x);
                maxX = Math.max(maxX, x);
                minZ = Math.min(minZ, z);
                maxZ = Math.max(maxZ, z);
            }
        }
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int x = Mth.floor(minX); x <= Mth.floor(maxX); x++) {
            for (int z = Mth.floor(minZ); z <= Mth.floor(maxZ); z++) {
                double dx = x + 0.5 - getX();
                double dz = z + 0.5 - getZ();
                double along = dx * fwd.x + dz * fwd.z;
                double side = Math.abs(dx * right.x + dz * right.z);
                if (along < fromAlong || along > toAlong || side > halfWidth) {
                    continue;
                }
                pos.set(x, minY, z);
                if (!level().isLoaded(pos)) {
                    continue;
                }
                // nothing to do above the highest block of the column
                int top = Math.min(maxY, level().getHeight(Heightmap.Types.WORLD_SURFACE, x, z));
                for (int y = minY; y < top; y++) {
                    pos.setY(y);
                    BlockState state = level().getBlockState(pos);
                    if (!state.isAir()) {
                        visitor.visit(pos, state);
                    }
                }
            }
        }
    }

    private boolean canFlatten(BlockState state, BlockPos pos) {
        return !state.isAir()
                && state.getFluidState().isEmpty()
                && state.getDestroySpeed(level(), pos) >= 0.0F;
    }

    /** A block knocked loose: flung forward and aside, then gone when it lands (no pile-up, no drop). */
    private void flingDebris(ServerLevel server, BlockPos pos, BlockState state) {
        FallingBlockEntity debris = FallingBlockEntity.fall(server, pos, state);
        // "CancelDrop" has no setter: the block must not be placed again where it lands
        CompoundTag tag = new CompoundTag();
        debris.saveWithoutId(tag);
        tag.putBoolean("CancelDrop", true);
        tag.putBoolean("DropItem", false);
        debris.load(tag);
        Vec3 fwd = forward();
        Vec3 away = new Vec3(pos.getX() + 0.5 - getX(), 0.0, pos.getZ() + 0.5 - getZ());
        away = away.lengthSqr() < 1.0E-4 ? fwd : away.normalize();
        double push = 0.35 + this.random.nextDouble() * 0.4;
        debris.setDeltaMovement(fwd.x * push + away.x * 0.3 + (this.random.nextDouble() - 0.5) * 0.2,
                0.3 + this.random.nextDouble() * 0.4,
                fwd.z * push + away.z * 0.3 + (this.random.nextDouble() - 0.5) * 0.2);
        debris.hurtMarked = true;
    }

    private void processBreakQueue(int budget) {
        if (this.breakQueue.isEmpty() || !(level() instanceof ServerLevel server)) {
            return;
        }
        Iterator<BlockPos> it = this.breakQueue.iterator();
        int broken = 0;
        this.debrisThisTick = 0;
        while (it.hasNext() && broken < budget) {
            BlockPos pos = it.next();
            it.remove();
            BlockState state = server.getBlockState(pos);
            if (!canFlatten(state, pos)) {
                continue;
            }
            if (state.hasBlockEntity()) {
                server.destroyBlock(pos, true, this); // keep chest contents etc.
            } else if ((isStructure(state) || state.is(BlockTags.LOGS)) && this.debrisThisTick < DEBRIS_PER_TICK
                    && this.random.nextFloat() < DEBRIS_CHANCE) {
                this.debrisThisTick++;
                flingDebris(server, pos, state);
            } else {
                if (this.random.nextFloat() < TRAMPLE_DROP_CHANCE) {
                    Block.dropResources(state, server, pos, null, this, ItemStack.EMPTY);
                }
                server.setBlock(pos, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
                if (this.random.nextInt(3) == 0) {
                    server.sendParticles(new BlockParticleOption(ParticleTypes.BLOCK, state),
                            pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5, 6, 0.3, 0.3, 0.3, 0.15);
                }
                if (this.random.nextInt(10) == 0) {
                    SoundEvent sound = state.getSoundType(server, pos, this).getBreakSound();
                    server.playSound(null, pos, sound, getSoundSource(), 1.5F, 0.7F);
                }
            }
            broken++;
        }
    }

    private void footstep() {
        if (!(level() instanceof ServerLevel server)) {
            return;
        }
        Vec3 fwd = forward();
        Vec3 right = new Vec3(-fwd.z, 0.0, fwd.x);
        double side = this.rightFootNext ? -3.5 : 3.5;
        this.rightFootNext = !this.rightFootNext;
        Vec3 foot = position().add(right.scale(side)).add(fwd.scale(1.5));
        BlockState ground = server.getBlockState(BlockPos.containing(foot.x, getY() - 0.5, foot.z));
        float solidity = getModelAlpha();
        if (!ground.isAir() && solidity > 0.2F) {
            server.sendParticles(new BlockParticleOption(ParticleTypes.BLOCK, ground),
                    foot.x, getY() + 0.2, foot.z, 40, 1.6, 0.1, 1.6, 0.15);
        }
        server.playSound(null, foot.x, getY(), foot.z, SoundEvents.RAVAGER_STEP, getSoundSource(), 4.0F * solidity, 0.35F);
        server.playSound(null, foot.x, getY(), foot.z, SoundEvents.IRON_GOLEM_STEP, getSoundSource(), 3.0F * solidity, 0.5F);
        level().broadcastEntityEvent(this, EVENT_FOOTSTEP);
        if (solidity > 0.5F) {
            stomp(server, foot);
        }
    }

    /** Whatever stands by the foot as it comes down takes a knock; wading, it throws up spray. */
    private void stomp(ServerLevel server, Vec3 foot) {
        AABB area = new AABB(foot.x - STOMP_RADIUS, getY() - 1.0, foot.z - STOMP_RADIUS,
                foot.x + STOMP_RADIUS, getY() + 3.0, foot.z + STOMP_RADIUS);
        for (LivingEntity victim : server.getEntitiesOfClass(LivingEntity.class, area, this::canBeCrushed)) {
            double dx = victim.getX() - foot.x;
            double dz = victim.getZ() - foot.z;
            double dist = Math.sqrt(dx * dx + dz * dz);
            if (dist > STOMP_RADIUS) {
                continue;
            }
            if (victim.hurt(damageSources().mobAttack(this), STOMP_DAMAGE)) {
                double strength = 0.5 * (1.0 - victim.getAttributeValue(Attributes.KNOCKBACK_RESISTANCE));
                double nx = dist < 1.0E-3 ? 0.0 : dx / dist;
                double nz = dist < 1.0E-3 ? 0.0 : dz / dist;
                victim.push(nx * strength, 0.3 * strength + 0.1, nz * strength);
                victim.hurtMarked = true;
            }
        }
        BlockPos.MutableBlockPos water = BlockPos.containing(foot.x, getY() + 0.2, foot.z).mutable();
        if (!server.getFluidState(water).isEmpty()) {
            while (!server.getFluidState(water.above()).isEmpty() && water.getY() < getY() + DEEP_WATER + 2) {
                water.move(0, 1, 0);
            }
            double surface = water.getY() + 1.0;
            server.sendParticles(ParticleTypes.SPLASH, foot.x, surface, foot.z, 80, 2.0, 0.2, 2.0, 0.5);
            server.sendParticles(ParticleTypes.BUBBLE_POP, foot.x, surface, foot.z, 20, 1.5, 0.1, 1.5, 0.1);
            server.playSound(null, foot.x, surface, foot.z, SoundEvents.GENERIC_SPLASH, getSoundSource(), 2.5F, 0.5F);
        }
    }

    /** Anything alive near the giant can be crushed, except other giants and players in creative / spectator. */
    private boolean canBeCrushed(LivingEntity entity) {
        if (entity == this || !entity.isAlive() || entity instanceof MountainGiant) {
            return false;
        }
        return !(entity instanceof Player player) || (!player.isCreative() && !player.isSpectator());
    }

    // =================================================================================
    // Client + both sides
    // =================================================================================

    @Override
    public void tick() {
        super.tick();
        if (level().isClientSide()) {
            // body follows the (smoothly interpolated) server yaw instead of vanilla's movement guess
            this.yBodyRotO = this.yRotO;
            this.yBodyRot = getYRot();
            spawnMist();
        }
        updateParts();
    }

    /** Clouds rolling around the giant while it leaves or arrives. */
    private void spawnMist() {
        float fog = getFog();
        if (fog <= 0.02F) {
            return;
        }
        int count = Mth.ceil(fog * 8.0F);
        for (int i = 0; i < count; i++) {
            double x = getX() + (this.random.nextDouble() - 0.5) * 30.0;
            double z = getZ() + (this.random.nextDouble() - 0.5) * 30.0;
            double y = getY() + this.random.nextDouble() * 24.0 * fog;
            level().addParticle(ParticleTypes.CLOUD, x, y, z,
                    (this.random.nextDouble() - 0.5) * 0.05, 0.01, (this.random.nextDouble() - 0.5) * 0.05);
        }
    }

    @Override
    public int getMaxHeadYRot() {
        return 50; // stiff neck
    }

    @Override
    public void handleEntityEvent(byte id) {
        if (id == EVENT_FOOTSTEP) {
            ClientEffects.shake(this, 0.35F * getModelAlpha(), 32.0); // gentle rumble
        } else if (id == EVENT_SMASH) {
            ClientEffects.shake(this, 1.0F, 56.0);
        } else {
            super.handleEntityEvent(id);
        }
    }

    // =================================================================================
    // Sounds
    // =================================================================================

    @Override
    protected SoundEvent getAmbientSound() {
        return SoundEvents.RAVAGER_AMBIENT;
    }

    @Override
    protected SoundEvent getHurtSound(DamageSource source) {
        return SoundEvents.IRON_GOLEM_HURT;
    }

    @Override
    protected SoundEvent getDeathSound() {
        return SoundEvents.RAVAGER_DEATH;
    }

    @Override
    protected float getSoundVolume() {
        return 4.0F;
    }

    @Override
    public float getVoicePitch() {
        return 0.4F + this.random.nextFloat() * 0.1F;
    }

    @Override
    public int getAmbientSoundInterval() {
        return 240;
    }

    @Override
    protected void playStepSound(BlockPos pos, BlockState state) {
        // footsteps are handled in footstep()
    }

    // =================================================================================
    // Behaviour tweaks for a huge, unique boss
    // =================================================================================

    @Override
    public boolean removeWhenFarAway(double distanceToClosestPlayer) {
        return false;
    }

    @Override
    public boolean isPushable() {
        return false;
    }

    @Override
    public boolean causeFallDamage(float fallDistance, float multiplier, DamageSource source) {
        return false;
    }

    @Override
    public AABB getBoundingBoxForCulling() {
        // arms, hump and back spikes reach well outside the hitbox
        return getBoundingBox().inflate(14.0, 6.0, 14.0);
    }

    @Override
    protected void tickDeath() {
        ++this.deathTime;
        if (this.deathTime >= DEATH_TICKS && !level().isClientSide() && !isRemoved()) {
            level().broadcastEntityEvent(this, (byte) 60); // poof particles
            remove(RemovalReason.KILLED);
        }
    }

    @Override
    public void addAdditionalSaveData(CompoundTag tag) {
        super.addAdditionalSaveData(tag);
        tag.putFloat("Heading", this.heading);
        tag.putFloat("DesiredHeading", this.desiredHeading);
        tag.putBoolean("NaturalSpawn", this.naturalSpawn);
        tag.putBoolean("Leaving", this.leaving);
        tag.putBoolean("Vanishing", this.vanishing);
        tag.putInt("LeavingTicks", this.leavingTicks);
        tag.putFloat("Fog", getFog());
        tag.putInt("OreTier", getOreTier());
        tag.putInt("EmergeTicks", this.emergeTicks);
        tag.putInt("DayCalmTicks", this.dayCalmTicks);
    }

    @Override
    public void readAdditionalSaveData(CompoundTag tag) {
        super.readAdditionalSaveData(tag);
        if (tag.contains("Heading")) {
            this.heading = tag.getFloat("Heading");
            this.desiredHeading = tag.getFloat("DesiredHeading");
        }
        this.naturalSpawn = tag.getBoolean("NaturalSpawn");
        this.leaving = tag.getBoolean("Leaving");
        this.vanishing = tag.getBoolean("Vanishing");
        this.leavingTicks = tag.getInt("LeavingTicks");
        this.entityData.set(DATA_FOG, tag.getFloat("Fog"));
        this.entityData.set(DATA_ORE_TIER, tag.getInt("OreTier"));
        this.emergeTicks = tag.getInt("EmergeTicks");
        this.dayCalmTicks = tag.getInt("DayCalmTicks");
        if (hasCustomName()) {
            this.bossEvent.setName(getDisplayName());
        }
    }

    @Override
    public void setCustomName(@Nullable Component name) {
        super.setCustomName(name);
        this.bossEvent.setName(getDisplayName());
    }

    // =================================================================================
    // GeckoLib
    // =================================================================================

    @Override
    public void registerControllers(AnimatableManager.ControllerRegistrar controllers) {
        // locomotion: driven by the synced gait, never guessed from client-side movement
        controllers.add(new AnimationController<>(this, "move", 10, state -> {
            if (isDeadOrDying()) {
                return state.setAndContinue(DEATH);
            }
            return state.setAndContinue(getGait() > 0.01F ? WALK : IDLE);
        }).setAnimationSpeedHandler(MountainGiant::locomotionSpeed));

        // one-shot actions layered on top
        controllers.add(new AnimationController<>(this, "action", 5, state -> PlayState.STOP)
                .triggerableAnim("smash", SMASH)
                .triggerableAnim("roar", ROAR));
    }

    /** Plays the walk cycle exactly as fast as the giant moves, so the feet don't slide. */
    private static double locomotionSpeed(MountainGiant giant) {
        float gait = giant.getGait();
        if (gait <= 0.01F || giant.isDeadOrDying()) {
            return 1.0;
        }
        return Mth.clamp(gait / ANIM_WALK_SPEED, 0.3, 1.5);
    }

    @Override
    public AnimatableInstanceCache getAnimatableInstanceCache() {
        return this.cache;
    }
}
