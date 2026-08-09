package io.github.limuqy.mc.hassium.platform.services;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;

/**
 * 网络管理器服务接口
 * 用于在 common 模块中访问平台特定的网络功能
 */
public interface INetworkManagerService {

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

    /**
     * 客户端配置阶段发送预握手（1.20.5+）。default 空实现：仅 forge 覆盖
     * （fabric 走 C2SConfigurationChannelEvents.REGISTER，neoforge 服务端
     * 只收不发）。
     *
     * @param connection 当前连接（forge 需要；fabric/neoforge 忽略）
     */
    default void sendPreHandshake(net.minecraft.network.Connection connection) {
    }

    /**
     * 客户端上报影子端存档布隆位图（C2S，full=true 覆盖旧层）。default 空实现：
     * 仅 fabric 1.20.1 闭环覆盖；neoforge/forge 版本推广时补实现。
     */
    default void sendClientBloomSync(FriendlyByteBuf buf) {
        if (buf != null && buf.refCnt() > 0) {
            buf.release();
        }
    }
}
