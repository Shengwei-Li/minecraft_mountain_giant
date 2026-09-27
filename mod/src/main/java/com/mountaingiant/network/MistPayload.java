package com.mountaingiant.network;

import com.mountaingiant.MountainGiantMod;
import com.mountaingiant.client.ClientEffects;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * Tells a client to thicken the mist to {@code level} (0 clear, 1 thickest) and keep it there for
 * {@code holdTicks}; after that it slowly lifts. Sent while the giant is on its way and when it rises.
 */
public record MistPayload(float level, int holdTicks) implements CustomPacketPayload {
    public static final Type<MistPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(MountainGiantMod.MODID, "mist"));
    public static final StreamCodec<ByteBuf, MistPayload> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.FLOAT, MistPayload::level, ByteBufCodecs.VAR_INT, MistPayload::holdTicks, MistPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    static void handle(MistPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> ClientEffects.mist(payload.level(), payload.holdTicks()));
    }
}
