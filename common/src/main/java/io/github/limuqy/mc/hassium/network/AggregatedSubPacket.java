package io.github.limuqy.mc.hassium.network;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
#if MC_VER < MC_1_21_11
import net.minecraft.resources.ResourceLocation;
#else
import net.minecraft.resources.Identifier;
#endif
import io.github.limuqy.mc.hassium.compat.ResourceLocationCompat;

/**
 * 聚合包中的子包包装
 * <p>
 * 存储包类型标识符和原始数据
 */
public class AggregatedSubPacket {
    private final
#if MC_VER < MC_1_21_11
    ResourceLocation
#else
    Identifier
#endif
    type;
    private final byte[] data;

    public AggregatedSubPacket(
#if MC_VER < MC_1_21_11
            ResourceLocation
#else
            Identifier
#endif
            type, byte[] data) {
        this.type = type;
        this.data = data;
    }

    /**
     * 编码子包到缓冲区
     * <p>
     * 格式：[identifier:CompactHeader] [length:VarInt] [data]
     *
     * @param buf         输出缓冲区
     * @param indexManager 索引管理器（用于紧凑包头）
     */
    public void encode(FriendlyByteBuf buf, NamespaceIndexManager indexManager) {
        // 写入紧凑包头标识符
        CompactHeaderCodec.writeIdentifier(type.toString(), buf, indexManager);

        // 写入数据长度和数据
        buf.writeVarInt(data.length);
        buf.writeBytes(data);
    }

    /**
     * 从缓冲区解码子包
     *
     * @param buf          输入缓冲区
     * @param indexManager 索引管理器
     * @return 解码的子包
     */
    public static AggregatedSubPacket decode(FriendlyByteBuf buf, NamespaceIndexManager indexManager) {
        // 读取紧凑包头标识符
        String identifier = CompactHeaderCodec.readIdentifier(buf, indexManager);
#if MC_VER < MC_1_21_11
        ResourceLocation
#else
        Identifier
#endif
        type = ResourceLocationCompat.create(identifier);

        // 读取数据长度和数据
        int length = buf.readVarInt();
        byte[] data = new byte[length];
        buf.readBytes(data);

        return new AggregatedSubPacket(type, data);
    }

    public
#if MC_VER < MC_1_21_11
    ResourceLocation
#else
    Identifier
#endif
    getType() {
        return type;
    }

    public byte[] getData() {
        return data;
    }

    /**
     * 获取原始数据的 ByteBuf 视图
     */
    public ByteBuf getDataBuf() {
        return Unpooled.wrappedBuffer(data);
    }

    /**
     * 若走原版独立包：writeUtf(type) + VarInt(len) + data 的近似线大小。
     * 不含外层 Connection 帧；与紧凑头对比即可体现 compact header 节省。
     */
    static int estimateVanillaSubPacketBytes(AggregatedSubPacket sp) {
        FriendlyByteBuf tmp = new FriendlyByteBuf(Unpooled.buffer());
        try {
            tmp.writeUtf(sp.getType().toString());
            tmp.writeVarInt(sp.getData().length);
            return tmp.readableBytes() + sp.getData().length;
        } finally {
            tmp.release();
        }
    }
}
