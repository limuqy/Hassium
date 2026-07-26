package io.github.limuqy.mc.hassium.network.dataplane;

import java.util.UUID;

/**
 * 数据面 façade —— histórico 接口保留；Task 3/4 UDP 切换后，本类仅作转发壳。
 *
 * <p>旧 PoC TCP 多通道 server（{@code ServerBootstrap} + {@code BindHandshakeHandler} +
 * {@code PlayerChannelBundle}）已在 Task 10b §2.1/§2.2 完成删除：实际 transport 是
 * {@link DataPlaneUdpServer} 的 KCP-over-UDP（NioDatagramChannel）；本类仅保留调用方契约
 * （{@link MixinMinecraftServer} 生命周期、{@code HassiumMod}/{@code FabricNetworkManager}
 * {@code tryRouteBulk}）所必需的转发方法。
 *
 * <p>调用方仍可继续使用本 façade 以减小迁移面，或直接走 {@link DataPlaneUdpServer} 的对应方法；
 * 两者的语义与线程边界由 UDP server 单点保证。
 */
public final class DataPlaneServer {

    private DataPlaneServer() {
    }

    /** 绑定所有数据面端点（委托 {@link DataPlaneUdpServer#bind()}）。 */
    public static void bind() {
        DataPlaneUdpServer.bind();
    }

    /** 关闭所有数据面端点（委托 {@link DataPlaneUdpServer#shutdown()}）。 */
    public static void shutdown() {
        DataPlaneUdpServer.shutdown();
    }

    /** @return 数据面是否已绑定（委托 {@link DataPlaneUdpServer#isBound()}）。 */
    public static boolean isBound() {
        return DataPlaneUdpServer.isBound();
    }

    /**
     * 主连接断开时调用（PoC 旧签名，仅以 UUID 触发 session 关闭）。
     *
     * <p>新 UDP 路径以 (playerId, epoch) 关键 session；本 façade 以 epoch=0 调 {@link
     * DataPlaneUdpServer#onPrimaryDisconnect(UUID, long, long)} 用于历史调用方，
     * 真实 epoch 由 mixin / failover handler 直接调用 {@code DataPlaneUdpServer}。
     *
     * @param playerId 玩家 UUID
     */
    public static void onPrimaryDisconnect(UUID playerId) {
        DataPlaneUdpServer.onPrimaryDisconnect(playerId, 0L, System.currentTimeMillis());
    }

    /**
     * 尝试把 bulk payload 路由到数据面。
     *
     * @param playerId  玩家 UUID
     * @param frameType {@link DataPlaneFrame} 帧类型（BULK_COMPRESSED_CHUNK / BULK_SECTION_DELTA）
     * @param payload   帧业务 payload
     * @return true = 已写入 UDP/KCP 或已丢弃（caller 不应再走 Primary）；false = 走 Primary
     */
    public static boolean tryRouteBulk(UUID playerId, int frameType, byte[] payload) {
        return DataPlaneUdpServer.tryRouteBulk(playerId, frameType, payload);
    }
}
