package io.github.limuqy.mc.hassium.network;

import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundCustomPayloadPacket;
import net.minecraft.network.protocol.game.ServerboundCustomPayloadPacket;
import net.minecraft.resources.ResourceLocation;

/**
 * 包类型辅助工具
 * <p>
 * 从 Packet 对象中提取 ResourceLocation 标识符
 */
public class PacketTypeHelper {

    private static final String MOD_ID = "hassium";

    /**
     * 获取包的真实类型标识符
     * <p>
     * 自定义 Payload 包返回其通道标识符
     * 原版包返回 NamespaceIndexManager 分配的标识符
     *
     * @param packet 数据包
     * @return 包类型标识符，如果无法识别返回 null
     */
    public static ResourceLocation getPacketType(Packet<?> packet) {
        if (packet instanceof ClientboundCustomPayloadPacket customPayload) {
            return customPayload.getIdentifier();
        } else if (packet instanceof ServerboundCustomPayloadPacket customPayload) {
            return customPayload.getIdentifier();
        } else {
            // 原版包：从 IndexSyncManager 获取标识符
            IndexSyncManager indexSyncManager = IndexSyncManager.getInstance();
            NamespaceIndexManager indexManager = indexSyncManager.getServerIndexManager();
            return indexManager.getVanillaIdentifier(packet.getClass());
        }
    }

    /**
     * 检查包是否是聚合包（避免递归聚合）
     */
    public static boolean isAggregationPacket(Packet<?> packet) {
        ResourceLocation type = getPacketType(packet);
        return type != null && type.getNamespace().equals(MOD_ID)
                && type.getPath().equals("aggregation");
    }
}
