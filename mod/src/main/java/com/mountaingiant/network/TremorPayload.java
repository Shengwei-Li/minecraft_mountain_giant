package com.mountaingiant.network;

import com.mountaingiant.MountainGiantMod;
import com.mountaingiant.client.ClientEffects;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/** Tells a client to rumble its camera: the ground trembling before the giant appears. */
public record TremorPayload(float strength) implements CustomPacketPayload {
    public static final Type<TremorPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(MountainGiantMod.MODID, "tremor"));
    public static final StreamCodec<ByteBuf, TremorPayload> STREAM_CODEC =
            StreamCodec.composite(ByteBufCodecs.FLOAT, TremorPayload::strength, TremorPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void register(RegisterPayloadHandlersEvent event) {
        event.registrar("1").playToClient(TYPE, STREAM_CODEC, TremorPayload::handle);
    }

    private static void handle(TremorPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> ClientEffects.tremor(payload.strength()));
    }
}
