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
     * 发送握手请求到服务端
     */
    void sendHandshakeRequest();

    /**
     * 发送区块数据请求到服务端（客户端调用）
     */
    void sendChunkDataRequest(FriendlyByteBuf buf);

    /**
     * 发送压缩区块数据到客户端（服务端调用）
     */
    void sendCompressedPayload(CompressedPayloadPacket packet);

    /**
     * 发送区块哈希广播到客户端（阶段一，服务端调用）
     */
    void sendChunkHashPacket(ServerPlayer player, FriendlyByteBuf buf);

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
}
