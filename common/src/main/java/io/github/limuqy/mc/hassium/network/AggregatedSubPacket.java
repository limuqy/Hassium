package io.github.limuqy.mc.hassium.network;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;

/**
 * 聚合包中的子包包装
 * <p>
 * 存储包类型标识符和原始数据
 */
public class AggregatedSubPacket {
    private final ResourceLocation type;
    private final byte[] data;

    public AggregatedSubPacket(ResourceLocation type, byte[] data) {
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
        ResourceLocation type = new ResourceLocation(identifier);

        // 读取数据长度和数据
        int length = buf.readVarInt();
        byte[] data = new byte[length];
        buf.readBytes(data);

        return new AggregatedSubPacket(type, data);
    }

    public ResourceLocation getType() {
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
}
