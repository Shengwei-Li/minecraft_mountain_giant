package com.mountaingiant.client;

import com.mountaingiant.MountainGiantMod;
import com.mountaingiant.entity.MountainGiant;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.ViewportEvent;

/**
 * Camera shake for footsteps and smashes, and fog: the mist that rolls in over the land when the giant comes
 * (sent by the server) and the fog the giant itself rises from and vanishes into. Only ever used on the client.
 */
@EventBusSubscriber(modid = MountainGiantMod.MODID, value = Dist.CLIENT)
public final class ClientEffects {
    /** Fog is full within this distance of a vanishing giant and fades out by FOG_RANGE. */
    private static final double FOG_FULL_RANGE = 32.0;
    private static final double FOG_RANGE = 128.0;
    /** How close the far fog plane comes in thickest fog (blocks). */
    private static final float FOG_THICK_DISTANCE = 12.0F;
    private static final float FOG_RED = 0.78F, FOG_GREEN = 0.80F, FOG_BLUE = 0.82F;

    /** Mist thickens this much per tick when the giant is coming (~7 s to full), and lifts this much per tick after. */
    private static final float MIST_RISE = 0.005F;
    private static final float MIST_LIFT = 0.0015F;
    /** Fog colour is scaled down by the darkness of the sky (never below this), so night mist stays dark. */
    private static final float MIN_FOG_BRIGHTNESS = 0.18F;

    private static float trauma;
    private static float fog;
    private static float mist;
    private static float mistTarget;
    private static int mistHold;

    private ClientEffects() {
    }

    /** Adds shake that fades out with distance from the source entity. */
    public static void shake(Entity source, float strength, double radius) {
        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null) {
            return;
        }
        double dist = player.distanceTo(source);
        if (dist >= radius) {
            return;
        }
        trauma = Math.min(1.5F, Math.max(trauma, strength * (float) (1.0 - dist / radius)));
    }

    /** Shake with no source entity (the omen before the giant appears). */
    public static void tremor(float strength) {
        trauma = Math.min(1.5F, Math.max(trauma, strength));
    }

    /** Mist over the land (the server says the giant is coming): thicken to {@code level}, hold, then lift. */
    public static void mist(float level, int holdTicks) {
        mistTarget = Math.max(mistTarget, level);
        mistHold = Math.max(mistHold, holdTicks);
    }

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        trauma = Math.max(0.0F, trauma - 0.06F);

        if (mistHold > 0) {
            mistHold--;
        } else {
            mistTarget = 0.0F;
        }
        mist = mistTarget > mist ? Math.min(mistTarget, mist + MIST_RISE) : Math.max(mistTarget, mist - MIST_LIFT);

        float target = 0.0F;
        Minecraft mc = Minecraft.getInstance();
        if (mc.level != null && mc.player != null) {
            for (MountainGiant giant : mc.level.getEntitiesOfClass(MountainGiant.class,
                    mc.player.getBoundingBox().inflate(FOG_RANGE))) {
                double dist = mc.player.distanceTo(giant);
                float falloff = (float) Mth.clamp(1.0 - (dist - FOG_FULL_RANGE) / (FOG_RANGE - FOG_FULL_RANGE), 0.0, 1.0);
                target = Math.max(target, giant.getFog() * falloff);
            }
        }
        // rolls in over a few seconds; lingers a while after the giant is gone
        fog = target > fog ? Math.min(target, fog + 0.02F) : Math.max(target, fog - 0.004F);
        if (mc.level == null) {
            mist = mistTarget = 0.0F;
            mistHold = 0;
        }
    }

    private static float thickness() {
        return Math.max(fog, mist);
    }

    @SubscribeEvent
    public static void onRenderFog(ViewportEvent.RenderFog event) {
        float t = thickness();
        if (t <= 0.01F) {
            return;
        }
        float f = t * t * (3.0F - 2.0F * t); // smoothstep
        event.setFarPlaneDistance(Mth.lerp(f, event.getFarPlaneDistance(), FOG_THICK_DISTANCE));
        event.setNearPlaneDistance(Mth.lerp(f, event.getNearPlaneDistance(), -4.0F));
        event.setCanceled(true); // apply the modified distances
    }

    @SubscribeEvent
    public static void onFogColor(ViewportEvent.ComputeFogColor event) {
        float t = thickness();
        if (t <= 0.01F) {
            return;
        }
        float f = Math.min(1.0F, t * 1.2F);
        // pale grey by day, a dim blue-grey at night
        ClientLevel level = Minecraft.getInstance().level;
        float light = level == null ? 1.0F
                : Mth.clamp(level.getSkyDarken((float) event.getPartialTick()), MIN_FOG_BRIGHTNESS, 1.0F);
        event.setRed(Mth.lerp(f, event.getRed(), FOG_RED * light));
        event.setGreen(Mth.lerp(f, event.getGreen(), FOG_GREEN * light));
        event.setBlue(Mth.lerp(f, event.getBlue(), FOG_BLUE * light));
    }

    @SubscribeEvent
    public static void onCameraAngles(ViewportEvent.ComputeCameraAngles event) {
        if (trauma <= 0.0F) {
            return;
        }
        float t = trauma * trauma;
        float time = (float) ((Minecraft.getInstance().level == null ? 0 : Minecraft.getInstance().level.getGameTime())
                + event.getPartialTick());
        event.setPitch(event.getPitch() + Mth.sin(time * 2.9F) * 2.2F * t);
        event.setYaw(event.getYaw() + Mth.sin(time * 2.3F + 1.3F) * 1.4F * t);
        event.setRoll(event.getRoll() + Mth.sin(time * 3.7F + 2.1F) * 2.0F * t);
    }
}
