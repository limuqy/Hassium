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
     * 发送网关 bootstrap 信息。默认走 vanilla CustomPayload；旧 NeoForge 需覆写为
     * SimpleChannel，以便在 loader 的频道协商中声明该 S2C 通道。
     */
    default void sendGatewayInfo(ServerPlayer player, byte[] data) {
        player.connection.send(io.github.limuqy.mc.hassium.compat.PacketPayloadCompat.createClientboundPayload(
                io.github.limuqy.mc.hassium.compat.PacketId.parse(
                        io.github.limuqy.mc.hassium.network.HassiumPacketIds.GATEWAY_INFO_S2C), data));
    }


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
     * 发送客户端握手请求到服务端（无网关拓扑：Play 阶段进入后由
     * {@code ClientHandshakeSender} 一次性调用；网关拓扑下不发送）。
     */
    default void sendClientHandshake(io.github.limuqy.mc.hassium.network.ClientHandshakeRequest request) {
    }

}
