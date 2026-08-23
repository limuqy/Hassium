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

    /**
     * 发送区块数据请求到服务端（客户端调用）
     */
    void sendChunkDataRequest(FriendlyByteBuf buf);

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
     * 发送区块哈希广播到客户端（阶段一，服务端调用）
     */
    void sendChunkHashPacket(ServerPlayer player, FriendlyByteBuf buf);

    /**
     * 发送 SeedRef（SeedGen 区块引用）到客户端（服务端调用）
     */
    void sendSeedRef(ServerPlayer player, FriendlyByteBuf buf);

    /**
     * 发送 section 哈希请求到服务端（阶段二，客户端调用）
     */
    void sendSectionHashRequest(FriendlyByteBuf buf);

    /**
     * 发送分段增量响应到客户端（阶段二，服务端调用）
     */
    void sendSectionDeltaPacket(ServerPlayer player, FriendlyByteBuf buf);

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

    /**
     * 发送客户端影子端存档 Bloom 位图同步包到服务端（客户端调用）。
     * default no-op：影子端 bloom 同步仅 fabric 1.20.1 闭环覆盖（Service 层
     * {@code INetworkManagerService#sendClientBloomSync} default 语义一致），
     * forge/neoforge 版本推广时补实现。
     */
    default void sendClientBloomSync(FriendlyByteBuf buf) {
    }

}
