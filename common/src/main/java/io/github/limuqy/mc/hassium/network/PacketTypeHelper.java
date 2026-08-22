package io.github.limuqy.mc.hassium.network;

import io.github.limuqy.mc.hassium.compat.PacketId;
import io.github.limuqy.mc.hassium.compat.PacketPayloadCompat;
import io.github.limuqy.mc.hassium.compat.ResourceLocationCompat;
import net.minecraft.network.protocol.Packet;

/**
 * 包类型辅助工具：从 Packet 提取稳定 {@link PacketId}。
 */
public class PacketTypeHelper {

    /**
     * 获取包的真实类型标识符。
     * <p>
     * 自定义 Payload 返回其通道；原版包返回 NamespaceIndexManager 分配的标识符
     * （1.21.1+ 优先 {@code packet.type().id()}）。
     *
     * @return 包类型，无法识别时 {@code null}
     */
    public static PacketId getPacketType(Packet<?> packet) {
        if (PacketPayloadCompat.isCustomPayloadPacket(packet)) {
            return PacketPayloadCompat.getPayloadId(packet);
        }
#if MC_VER >= MC_1_21_1
        return ResourceLocationCompat.toPacketId(packet.type().id());
#else
        IndexSyncManager indexSyncManager = IndexSyncManager.getInstance();
        NamespaceIndexManager indexManager = indexSyncManager.getServerIndexManager();
        return indexManager.getVanillaIdentifier(packet.getClass());
#endif
    }

    /**
     * 检查包是否是聚合包（避免递归聚合）
     */
    public static boolean isAggregationPacket(Packet<?> packet) {
        PacketId type = getPacketType(packet);
        return type != null && HassiumPacketIds.AGGREGATION_S2C.equals(type.fullId());
    }
}
