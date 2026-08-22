package io.github.limuqy.mc.hassium.network;

import io.github.limuqy.mc.hassium.compat.PacketId;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;

/**
 * 聚合包中的子包包装。
 * <p>
 * 类型用稳定值 {@link PacketId}，不暴露 {@code ResourceLocation}/{@code Identifier}。
 */
public class AggregatedSubPacket {
    /** 单个子包数据上限 1MB（review-fix: T13-C1） */
    private static final int MAXIMUM_SUBPACKET_LENGTH = 1024 * 1024;

    private final PacketId type;
    private final byte[] data;

    public AggregatedSubPacket(PacketId type, byte[] data) {
        this.type = type;
        this.data = data;
    }

    /**
     * 编码子包到缓冲区
     * <p>
     * 格式：[identifier:CompactHeader] [length:VarInt] [data]
     */
    public void encode(FriendlyByteBuf buf, NamespaceIndexManager indexManager) {
        CompactHeaderCodec.writeIdentifier(type.fullId(), buf, indexManager);
        buf.writeVarInt(data.length);
        buf.writeBytes(data);
    }

    /**
     * 从缓冲区解码子包
     */
    public static AggregatedSubPacket decode(FriendlyByteBuf buf, NamespaceIndexManager indexManager) {
        String identifier = CompactHeaderCodec.readIdentifier(buf, indexManager);
        PacketId type = PacketId.parse(identifier);

        int length = buf.readVarInt();
        if (length < 0 || length > MAXIMUM_SUBPACKET_LENGTH || length > buf.readableBytes()) {
            throw new IllegalArgumentException(
                    "AggregatedSubPacket: invalid data length " + length
                            + " (remaining " + buf.readableBytes()
                            + ", max " + MAXIMUM_SUBPACKET_LENGTH + ")");
        }
        byte[] data = new byte[length];
        buf.readBytes(data);

        return new AggregatedSubPacket(type, data);
    }

    public PacketId getType() {
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
