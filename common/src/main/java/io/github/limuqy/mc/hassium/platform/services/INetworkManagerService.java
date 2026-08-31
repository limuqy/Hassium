package io.github.limuqy.mc.hassium.platform.services;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;

/**
 * 网络管理器服务接口
 * 用于在 common 模块中访问平台特定的网络功能
 */
public interface INetworkManagerService {

    /** 发送 shadowPullV1 区块请求到服务端（客户端调用）。 */
    default void sendShadowPullRequest(FriendlyByteBuf buf) {
        if (buf != null && buf.refCnt() > 0) {
            buf.release();
        }
    }


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
     * 发送光照增量通知到客户端（服务端调用）
     */
    void sendLightDeltaPacket(ServerPlayer player, FriendlyByteBuf buf);

    /**
     * 发送 SeedRef 到客户端（SeedGen：pristine 区块引用替代区块数据；服务端调用）
     */
    void sendSeedRef(ServerPlayer player, FriendlyByteBuf buf);

}
