package io.github.limuqy.mc.hassium.client;

import io.github.limuqy.mc.hassium.Constants;
import io.github.limuqy.mc.hassium.protocol.DictionaryManager;
import io.github.limuqy.mc.hassium.protocol.DictionarySnapshot;
import io.github.limuqy.mc.hassium.protocol.HassiumConnectionRegistry;
import io.github.limuqy.mc.hassium.protocol.IndexSyncManager;
import io.github.limuqy.mc.hassium.protocol.IndexSyncPacket;
import io.github.limuqy.mc.hassium.protocol.NamespaceIndexManager;
import io.github.limuqy.mc.hassium.platform.Services;
import io.github.limuqy.mc.hassium.utils.DebugLogger;
import io.github.limuqy.mc.hassium.utils.DebugLogger.LogType;
import net.minecraft.client.Minecraft;
import net.minecraft.network.FriendlyByteBuf;

/**
 * 客户端聚合激活统一入口：index_sync 到达后的「包索引登记 → registry markEnabled →
 * 聚合 init → aggregation_ready ACK」三端重复流程收敛点。
 * <p>
 * 必须在客户端主线程调用（loader receiver 经 execute/enqueueWork 派发）；ACK 回发
 * 经 SPI {@code Services.NETWORK_MANAGER.sendAggregationReady(epoch, id)}，loader 只实现发送。
 */
public final class ClientActivation {

    /**
     * 激活 ACK（首个 aggregation_ready）是否已发出。
     * <p>
     * 字典 ACK 语义 =「index + 字典均已就绪」：激活完成前 dictionary_sync 只安装不回 ACK
     * （过早 ACK 会令服务端在索引未装时 ENABLE+flush，缓冲帧解码失败）；激活完成后的
     * dictionary_sync 是热推 offer，立即回 ACK 参与 rollout 门控。
     */
    private static volatile boolean activationAckSent;
    private static volatile net.minecraft.network.Connection indexReadyConnection;
    private static boolean activationQueued;

    private ClientActivation() {
    }

    /**
     * 处理 index_sync 载荷（varint 长度前缀 + {@link IndexSyncPacket} 编码）并激活聚合。
     */
    public static void handleIndexSync(byte[] envelopeData) {
        FriendlyByteBuf buf = new FriendlyByteBuf(io.netty.buffer.Unpooled.wrappedBuffer(envelopeData));
        try {
            int dataLength = buf.readVarInt();
            byte[] packetData = new byte[dataLength];
            buf.readBytes(packetData);
            IndexSyncPacket syncPacket = IndexSyncPacket.decode(packetData);
            NamespaceIndexManager clientIndexManager =
                    IndexSyncManager.getInstance().handleSyncPacket("client", syncPacket);
            var conn = Minecraft.getInstance().getConnection();
            if (conn != null) {
                indexReadyConnection = conn.getConnection();
                tryActivate();
            }
            DebugLogger.debug(LogType.NETWORK, "Hassium: Received index sync ({} types), sent aggregation ready",
                    clientIndexManager != null ? clientIndexManager.size() : 0);
        } catch (Exception e) {
            Constants.LOG.error("Hassium: Failed to handle index sync", e);
            var connection = Minecraft.getInstance().getConnection();
            if (connection != null) connection.getConnection().disconnect(net.minecraft.network.chat.Component.literal(
                    "Hassium egress v3: invalid index sync; reconnect required."));
        } finally {
            buf.release();
        }
    }

    /** Index and play_init are separate controls; require both before installing the byte receiver and ACK. */
    public static void tryActivate() {
        var connection = indexReadyConnection;
        int caps = io.github.limuqy.mc.hassium.protocol.handshake.ClientLoginNegotiation.current();
        if (connection == null || activationQueued || !io.github.limuqy.mc.hassium.protocol.handshake.LoginCaps.has(caps,
                io.github.limuqy.mc.hassium.protocol.handshake.LoginCaps.EGRESS_V3)
                || !io.github.limuqy.mc.hassium.protocol.handshake.LoginCaps.has(caps,
                io.github.limuqy.mc.hassium.protocol.handshake.LoginCaps.AGGREGATION)) return;
        activationQueued = true;
        io.github.limuqy.mc.hassium.protocol.egress.EgressPipeline.activateClient(connection, () -> {
            if (indexReadyConnection != connection) return;
            DictionarySnapshot snapshot = DictionaryManager.getActiveSnapshot();
            activationAckSent = true;
            Services.NETWORK_MANAGER.sendAggregationReady(connection, snapshot != null ? snapshot.epoch() : 0,
                    snapshot != null ? snapshot.id() : 0L);
        });
    }

    /** 激活 ACK 是否已发出（字典 handler 据此决定热推 offer 是否立即回 ACK）。 */
    public static boolean isActivationAckSent() {
        return activationAckSent;
    }

    /** 断连清理：复位激活 ACK 标记（下个会话的字典 sync 在激活完成前不回 ACK）。 */
    public static void resetForDisconnect() {
        activationAckSent = false;
        activationQueued = false;
        indexReadyConnection = null;
    }
}
