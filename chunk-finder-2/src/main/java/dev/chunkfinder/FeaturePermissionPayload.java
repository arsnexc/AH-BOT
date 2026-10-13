package dev.chunkfinder;

import net.minecraft.resources.Identifier;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

public record FeaturePermissionPayload(boolean authorized) implements CustomPacketPayload {
    public static final Type<FeaturePermissionPayload> TYPE = new Type<>(
            Identifier.fromNamespaceAndPath("chunkfinder", "feature_permission"));
    public static final StreamCodec<RegistryFriendlyByteBuf, FeaturePermissionPayload> CODEC =
            StreamCodec.of((buffer, payload) -> buffer.writeBoolean(payload.authorized()),
                    buffer -> new FeaturePermissionPayload(buffer.readBoolean()));

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
