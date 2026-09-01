package io.github.limuqy.mc.hassium.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;

/**
 * 网络管理器接口
 * 用于注册和管理自定义网络通道
 */
public interface NetworkManager {

    /**
     * 注册所有 Hassium 网络通道
     */
    void registerChannels();

    /** 发送 shadowPullV1 区块请求到服务端（客户端调用）。 */
    default void sendShadowPullRequest(FriendlyByteBuf buf) {
        if (buf != null && buf.refCnt() > 0) {
            buf.release();
        }
    }

    /**
     * 发送压缩区块数据到客户端（服务端调用）。
     * <p>
     * 已退役（review-fix: T11-14）：全库无调用方，三端实现均为抛 UnsupportedOperationException 的
     * 死代码；实际发送走 ChunkSender → 平台专用 sendCompressedChunk 路径。保留 default no-op 仅
     * 兼容残留实现，待 fabric/forge 清理后整方法删除。
     */
    default void sendCompressedPayload(CompressedPayloadPacket packet) {
    }


    /**
     * 发送 SeedRef（SeedGen 区块引用）到客户端（服务端调用）
     */
    void sendSeedRef(ServerPlayer player, FriendlyByteBuf buf);


    /**
     * 发送 blockEntity 数据请求到服务端（客户端调用）
     */
    void sendBlockEntityRequest(FriendlyByteBuf buf);

    /**
     * 发送 blockEntity 数据响应到客户端（服务端调用）
     */
    void sendBlockEntityData(ServerPlayer player, FriendlyByteBuf buf);

    /**
     * 发送光照增量通知到客户端（服务端调用）。
     * 口径（2026-08-23 裁决，三端一致收口）：vanilla 通道 LightDelta 三端客户端均不消费，
     * 唯一消费在网关帧链路（GatewayPacketCodec LIGHT_DELTA → NetworkCore → ShadowLightCompute）；
     * 实现仅在网关路由未命中时消费 buf 所有权（release），不得再经 vanilla 通道发送。
     */
    void sendLightDeltaPacket(ServerPlayer player, FriendlyByteBuf buf);


}
