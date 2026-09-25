package io.github.limuqy.mc.hassium.client;

import io.github.limuqy.mc.hassium.Constants;
import io.github.limuqy.mc.hassium.protocol.DictionaryManager;
import io.github.limuqy.mc.hassium.protocol.DictionarySnapshot;
import io.github.limuqy.mc.hassium.protocol.HassiumAggregationManager;
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
                HassiumConnectionRegistry.markEnabled(conn.getConnection());
                HassiumAggregationManager.init();
                DictionarySnapshot snapshot = DictionaryManager.getActiveSnapshot();
                Services.NETWORK_MANAGER.sendAggregationReady(
                        snapshot != null ? snapshot.epoch() : 0,
                        snapshot != null ? snapshot.id() : 0L);
                activationAckSent = true;
            }
            DebugLogger.debug(LogType.NETWORK, "Hassium: Received index sync ({} types), sent aggregation ready",
                    clientIndexManager != null ? clientIndexManager.size() : 0);
        } catch (Exception e) {
            Constants.LOG.error("Hassium: Failed to handle index sync", e);
        } finally {
            buf.release();
        }
    }

    /** 激活 ACK 是否已发出（字典 handler 据此决定热推 offer 是否立即回 ACK）。 */
    public static boolean isActivationAckSent() {
        return activationAckSent;
    }

    private static final long RESYNC_THROTTLE_MS = 5000;
    private static volatile long lastResyncRequestMs;

    /**
     * 未知 epoch 帧的恢复动作：请求服务端重发当前激活字典（节流 5s；幂等安装）。
     *
     * @return true = 本次真的发出了请求（调用方据此分流日志级别）
     */
    public static boolean requestDictionaryResync() {
        long now = System.currentTimeMillis();
        if (now - lastResyncRequestMs < RESYNC_THROTTLE_MS) {
            return false;
        }
        lastResyncRequestMs = now;
        Services.NETWORK_MANAGER.sendDictionaryResyncRequest();
        return true;
    }

    /** 断连清理：复位激活 ACK 标记（下个会话的字典 sync 在激活完成前不回 ACK）。 */
    public static void resetForDisconnect() {
        activationAckSent = false;
        lastResyncRequestMs = 0;
    }
}
