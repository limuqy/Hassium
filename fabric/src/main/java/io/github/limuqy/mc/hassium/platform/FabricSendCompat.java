package io.github.limuqy.mc.hassium.platform;

import io.github.limuqy.mc.hassium.compat.HassiumChannels;
import io.github.limuqy.mc.hassium.compat.PacketId;
import io.github.limuqy.mc.hassium.compat.ResourceLocationCompat;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.network.FriendlyByteBuf;
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

#if MC_VER >= MC_1_21_1
    private static CustomPacketPayload c2sPayload(PacketId channel, FriendlyByteBuf buf) {
        if (channel.equals(HassiumChannels.SHADOW_PULL_REQUEST_C2S)) {
            return FabricPayloadRegistry.toPayload(FabricPayloadRegistry.SHADOW_PULL_REQUEST_C2S_TYPE, buf);
        }
        throw new IllegalArgumentException("Unknown Hassium C2S channel: " + channel);
    }
#endif
}