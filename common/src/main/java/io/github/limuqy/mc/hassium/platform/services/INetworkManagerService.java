package io.github.limuqy.mc.hassium.platform.services;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;

/**
 * 网络管理器服务接口
 * 用于在 common 模块中访问平台特定的网络功能
 */
public interface INetworkManagerService {

    /**
     * 发送区块元数据包给客户端（服务端调用）
     */
    void sendMetadataPacket(ServerPlayer player, FriendlyByteBuf buf);

    /**
     * 发送区块数据请求到服务端（客户端调用）
     */
    void sendChunkDataRequest(FriendlyByteBuf buf);

    /**
     * 发送区块哈希广播到客户端（阶段一，服务端调用）
     */
    void sendChunkHashPacket(ServerPlayer player, FriendlyByteBuf buf);

    /**
     * 发送 section 哈希请求到服务端（阶段二，客户端调用）
     */
    void sendSectionHashRequest(FriendlyByteBuf buf);

    /**
     * 发送 section delta 响应到客户端（阶段二，服务端调用）
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
