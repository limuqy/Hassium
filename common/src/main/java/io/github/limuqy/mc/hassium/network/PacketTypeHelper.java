package io.github.limuqy.mc.hassium.network;

import io.github.limuqy.mc.hassium.compat.PacketPayloadCompat;
import net.minecraft.network.protocol.Packet;
#if MC_VER < MC_1_21_11
import net.minecraft.resources.ResourceLocation;
#else
import net.minecraft.resources.Identifier;
#endif

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
    public static
#if MC_VER < MC_1_21_11
    ResourceLocation
#else
    Identifier
#endif
    getPacketType(Packet<?> packet) {
        if (PacketPayloadCompat.isCustomPayloadPacket(packet)) {
            return PacketPayloadCompat.getPayloadId(packet);
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
#if MC_VER < MC_1_21_11
        ResourceLocation
#else
        Identifier
#endif
        type = getPacketType(packet);
        return type != null && type.getNamespace().equals(MOD_ID)
                && type.getPath().equals("aggregation");
    }
}
