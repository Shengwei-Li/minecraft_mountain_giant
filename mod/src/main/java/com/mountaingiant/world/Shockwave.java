package com.mountaingiant.world;

import com.mountaingiant.MountainGiantMod;
import com.mountaingiant.network.TremorPayload;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

/**
 * The Mountain Hammer's slam: three rings rolling outward from where it hits the ground.
 * Each ring hurts and throws what stands in it (the further out, the softer). Nothing is hit twice. No blocks break.
 */
@EventBusSubscriber(modid = MountainGiantMod.MODID)
public final class Shockwave {
    /** delay (ticks), inner radius, outer radius, damage */
    private static final double[][] RINGS = {
            {0, 0.0, 3.0, 12.0},
            {4, 3.0, 5.5, 9.0},
            {8, 5.5, 8.0, 6.0},
    };
    private static final double HEIGHT = 3.0;

    private static final List<Shockwave> ACTIVE = new ArrayList<>();

    private final ServerLevel level;
    private final LivingEntity user;
    private final Vec3 center;
    private final Set<LivingEntity> hit = new HashSet<>();
    private int age;

    private Shockwave(ServerLevel level, LivingEntity user) {
        this.level = level;
        this.user = user;
        this.center = user.position();
    }

    public static void start(ServerLevel level, LivingEntity user) {
        Shockwave wave = new Shockwave(level, user);
        ACTIVE.add(wave);
        wave.tick(); // the first ring goes off right away
        for (ServerPlayer player : level.players()) {
            double dist = player.distanceTo(user);
            if (dist < 24.0) {
                PacketDistributor.sendToPlayer(player, new TremorPayload((float) (0.6 * (1.0 - dist / 24.0))));
            }
        }
    }

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        Iterator<Shockwave> it = ACTIVE.iterator();
        while (it.hasNext()) {
            Shockwave wave = it.next();
            wave.age++;
            if (wave.tick()) {
                it.remove();
            }
        }
    }

    /** Fires the ring due this tick. Returns true when the whole wave is done. */
    private boolean tick() {
        for (double[] ring : RINGS) {
            if ((int) ring[0] == this.age) {
                fireRing(ring[1], ring[2], (float) ring[3], (int) ring[0] / 4);
            }
        }
        return this.age >= (int) RINGS[RINGS.length - 1][0];
    }

    private void fireRing(double inner, double outer, float damage, int index) {
        // dust and stone kicked up along the ring
        BlockState ground = this.level.getBlockState(BlockPos.containing(this.center.x, this.center.y - 0.5, this.center.z));
        int points = (int) (outer * 8);
        for (int i = 0; i < points; i++) {
            double a = i * Mth.TWO_PI / points;
            double x = this.center.x + Math.cos(a) * outer;
            double z = this.center.z + Math.sin(a) * outer;
            BlockState under = this.level.getBlockState(BlockPos.containing(x, this.center.y - 0.5, z));
            BlockState dust = under.isAir() ? ground : under;
            if (!dust.isAir()) {
                this.level.sendParticles(new BlockParticleOption(ParticleTypes.BLOCK, dust),
                        x, this.center.y + 0.1, z, 4, 0.2, 0.1, 0.2, 0.15);
            }
            if (i % 4 == 0) {
                this.level.sendParticles(ParticleTypes.POOF, x, this.center.y + 0.2, z, 1, 0.1, 0.05, 0.1, 0.02);
            }
        }
        if (index == 0) {
            this.level.sendParticles(ParticleTypes.EXPLOSION, this.center.x, this.center.y + 0.3, this.center.z, 1, 0, 0, 0, 0);
        }
        this.level.playSound(null, this.center.x, this.center.y, this.center.z, SoundEvents.GENERIC_EXPLODE.value(),
                SoundSource.PLAYERS, 1.6F - index * 0.4F, 0.55F + index * 0.15F);

        // everything standing in the ring
        DamageSource source = this.user instanceof Player player
                ? this.level.damageSources().playerAttack(player)
                : this.level.damageSources().mobAttack(this.user);
        AABB box = new AABB(this.center, this.center).inflate(outer, HEIGHT, outer);
        for (LivingEntity victim : this.level.getEntitiesOfClass(LivingEntity.class, box,
                e -> e != this.user && e.isAlive() && !this.hit.contains(e))) {
            double dx = victim.getX() - this.center.x;
            double dz = victim.getZ() - this.center.z;
            double dist = Math.sqrt(dx * dx + dz * dz);
            if (dist < inner || dist > outer) {
                continue;
            }
            this.hit.add(victim);
            if (victim.hurt(source, damage)) {
                Vec3 away = dist < 1.0E-3 ? Vec3.ZERO : new Vec3(dx / dist, 0.0, dz / dist);
                double strength = (1.0 - victim.getAttributeValue(Attributes.KNOCKBACK_RESISTANCE)) * (1.1 - index * 0.25);
                victim.push(away.x * strength, 0.45 * strength + 0.1, away.z * strength);
                victim.hurtMarked = true;
            }
        }
    }

    /** For tests: how many waves are still rolling. */
    public static int activeCount() {
        return ACTIVE.size();
    }
}
