package io.github.limuqy.mc.hassium.platform;

import io.github.limuqy.mc.hassium.compat.HassiumChannels;
import io.github.limuqy.mc.hassium.compat.PacketId;
import io.github.limuqy.mc.hassium.compat.ResourceLocationCompat;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
#if MC_VER >= MC_1_21_1
import io.github.limuqy.mc.hassium.network.FabricPayloadRegistry;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
#endif

/**
 * Fabric send 双形态收口点（T10）：
 * {@code <1.21.1} Fabric API 走 {@code send(channel, buf)}，
 * {@code >=1.21.1} 走 {@code send(CustomPacketPayload)}。
 *
 * <p>全模块唯一的 send 分段点：调用方一律传 {@link HassiumChannels} 常量（{@link PacketId}
 * 稳定值类型），单行调用；签名不暴露 ResourceLocation/Identifier 版本类型名，
 * {@code <1.21.1} 分支经 {@link ResourceLocationCompat#vanilla(PacketId)} 边界转换。</p>
 */
public final class FabricSendCompat {

    private FabricSendCompat() {
    }

    /** 客户端 → 服务端（C2S）。channel 传 HassiumChannels 常量。 */
    public static void sendToServer(PacketId channel, FriendlyByteBuf buf) {
#if MC_VER < MC_1_21_1
        ClientPlayNetworking.send(ResourceLocationCompat.vanilla(channel), buf);
#else
        ClientPlayNetworking.send(c2sPayload(channel, buf));
#endif
    }

    /** 服务端 → 玩家（S2C）。channel 传 HassiumChannels 常量。 */
    public static void sendToPlayer(ServerPlayer player, PacketId channel, FriendlyByteBuf buf) {
#if MC_VER < MC_1_21_1
        ServerPlayNetworking.send(player, ResourceLocationCompat.vanilla(channel), buf);
#else
        ServerPlayNetworking.send(player, s2cPayload(channel, buf));
#endif
    }

#if MC_VER >= MC_1_21_1
    private static CustomPacketPayload c2sPayload(PacketId channel, FriendlyByteBuf buf) {
        if (channel.equals(HassiumChannels.CHUNK_DATA_REQUEST_C2S)) {
            return FabricPayloadRegistry.toPayload(FabricPayloadRegistry.CHUNK_DATA_REQUEST_C2S_TYPE, buf);
        }
        if (channel.equals(HassiumChannels.SECTION_HASH_REQUEST_C2S)) {
            return FabricPayloadRegistry.toPayload(FabricPayloadRegistry.SECTION_HASH_REQUEST_C2S_TYPE, buf);
        }
        if (channel.equals(HassiumChannels.BLOCK_ENTITY_REQUEST_C2S)) {
            return FabricPayloadRegistry.toPayload(FabricPayloadRegistry.BLOCK_ENTITY_REQUEST_C2S_TYPE, buf);
        }
        if (channel.equals(HassiumChannels.CLIENT_BLOOM_SYNC_C2S)) {
            return FabricPayloadRegistry.toPayload(FabricPayloadRegistry.CLIENT_BLOOM_SYNC_C2S_TYPE, buf);
        }
        throw new IllegalArgumentException("Unknown Hassium C2S channel: " + channel);
    }

    private static CustomPacketPayload s2cPayload(PacketId channel, FriendlyByteBuf buf) {
        if (channel.equals(HassiumChannels.CHUNK_HASH_S2C)) {
            return FabricPayloadRegistry.toPayload(FabricPayloadRegistry.CHUNK_HASH_S2C_TYPE, buf);
        }
        if (channel.equals(HassiumChannels.SEED_REF_S2C)) {
            return FabricPayloadRegistry.toPayload(FabricPayloadRegistry.SEED_REF_S2C_TYPE, buf);
        }
        if (channel.equals(HassiumChannels.SECTION_DELTA_S2C)) {
            return FabricPayloadRegistry.toPayload(FabricPayloadRegistry.SECTION_DELTA_S2C_TYPE, buf);
        }
        if (channel.equals(HassiumChannels.BLOCK_ENTITY_DATA_S2C)) {
            return FabricPayloadRegistry.toPayload(FabricPayloadRegistry.BLOCK_ENTITY_DATA_S2C_TYPE, buf);
        }
        if (channel.equals(HassiumChannels.LIGHT_DELTA_S2C)) {
            return FabricPayloadRegistry.toPayload(FabricPayloadRegistry.LIGHT_DELTA_S2C_TYPE, buf);
        }
        throw new IllegalArgumentException("Unknown Hassium S2C channel: " + channel);
    }
#endif
}
