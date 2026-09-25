package com.mountaingiant.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mountaingiant.entity.MountainGiant;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;
import software.bernie.geckolib.cache.object.BakedGeoModel;
import software.bernie.geckolib.renderer.GeoEntityRenderer;
import software.bernie.geckolib.renderer.layer.AutoGlowingGeoLayer;
import software.bernie.geckolib.util.Color;

public class MountainGiantRenderer extends GeoEntityRenderer<MountainGiant> {
    /** The model is built at 1/4 scale in Blockbench (~7 blocks), rendered 4x (~28 blocks). */
    private static final float MODEL_SCALE = 4.0F;

    public MountainGiantRenderer(EntityRendererProvider.Context context) {
        super(context, new MountainGiantModel());
        withScale(MODEL_SCALE);
        // eyes + diamond core (_glowmask texture); the glow goes out as the giant fades into the mist
        addRenderLayer(new AutoGlowingGeoLayer<>(this) {
            @Override
            public void render(PoseStack poseStack, MountainGiant animatable, BakedGeoModel bakedModel,
                               @Nullable RenderType renderType, MultiBufferSource bufferSource,
                               @Nullable VertexConsumer buffer, float partialTick, int packedLight, int packedOverlay) {
                if (animatable.getModelAlpha() > 0.6F) {
                    super.render(poseStack, animatable, bakedModel, renderType, bufferSource, buffer,
                            partialTick, packedLight, packedOverlay);
                }
            }
        });
        this.shadowRadius = 9.0F;
    }

    @Override
    public Color getRenderColor(MountainGiant animatable, float partialTick, int packedLight) {
        Color color = super.getRenderColor(animatable, partialTick, packedLight);
        float alpha = animatable.getModelAlpha();
        if (alpha >= 1.0F) {
            return color;
        }
        return Color.ofARGB((int) (color.getAlpha() * alpha), color.getRed(), color.getGreen(), color.getBlue());
    }

    @Nullable
    @Override
    public RenderType getRenderType(MountainGiant animatable, ResourceLocation texture,
                                    @Nullable MultiBufferSource bufferSource, float partialTick) {
        if (animatable.getModelAlpha() < 1.0F) {
            return RenderType.entityTranslucent(texture);
        }
        return super.getRenderType(animatable, texture, bufferSource, partialTick);
    }

    @Override
    protected float getDeathMaxRotation(MountainGiant animatable) {
        return 0.0F; // the death animation handles the fall
    }
}
