package io.github.limuqy.mc.hassium.client;

import io.github.limuqy.mc.hassium.Constants;
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
 * 经 SPI {@code Services.NETWORK_MANAGER.sendAggregationReady()}，loader 只实现发送。
 */
public final class ClientActivation {

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
                Services.NETWORK_MANAGER.sendAggregationReady();
            }
            DebugLogger.debug(LogType.NETWORK, "Hassium: Received index sync ({} types), sent aggregation ready",
                    clientIndexManager != null ? clientIndexManager.size() : 0);
        } catch (Exception e) {
            Constants.LOG.error("Hassium: Failed to handle index sync", e);
        } finally {
            buf.release();
        }
    }
}
