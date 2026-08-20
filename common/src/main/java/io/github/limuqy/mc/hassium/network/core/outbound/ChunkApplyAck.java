package io.github.limuqy.mc.hassium.network.core.outbound;

import io.netty.buffer.ByteBuf;

import java.util.Objects;

/**
 * 客户端确认已 authoritative apply 的全量区块投递。
 *
 * <p>线格式：{@code varint count} 后接 {@code count} 个大端 {@code long deliveryId}。
 * 单帧必须完整且只包含 1–64 个正 id；未知和重复 id 由服务端 admission 层幂等处理。
 */
public record ChunkApplyAck(long[] deliveryIds) {

    public static final int MAX_DELIVERY_IDS = 64;

    public ChunkApplyAck {
        Objects.requireNonNull(deliveryIds, "deliveryIds");
        if (deliveryIds.length == 0 || deliveryIds.length > MAX_DELIVERY_IDS) {
            throw new IllegalArgumentException("ChunkApplyAck deliveryIds size must be in [1, 64]");
        }
        deliveryIds = deliveryIds.clone();
        for (long deliveryId : deliveryIds) {
            if (deliveryId <= 0) {
                throw new IllegalArgumentException("ChunkApplyAck deliveryId must be positive");
            }
        }
    }

    @Override
    public long[] deliveryIds() {
        return deliveryIds.clone();
    }

    public int size() {
        return deliveryIds.length;
    }

    public void encode(ByteBuf out) {
        ControlFrameCodec.writeVarInt(out, deliveryIds.length);
        for (long deliveryId : deliveryIds) {
            out.writeLong(deliveryId);
        }
    }

    /**
     * 解码完整 ACK payload；截断、非法值或尾随字节均拒绝，调用方不得部分应用。
     */
    public static ChunkApplyAck decode(ByteBuf in) {
        final int count;
        try {
            count = ControlFrameCodec.readVarInt(in);
        } catch (IndexOutOfBoundsException e) {
            throw new IllegalArgumentException("Truncated ChunkApplyAck payload", e);
        }
        if (count <= 0 || count > MAX_DELIVERY_IDS) {
            throw new IllegalArgumentException("ChunkApplyAck count must be in [1, 64]");
        }
        if (in.readableBytes() != (long) count * Long.BYTES) {
            throw new IllegalArgumentException("Malformed ChunkApplyAck payload");
        }
        long[] deliveryIds = new long[count];
        for (int i = 0; i < count; i++) {
            deliveryIds[i] = in.readLong();
        }
        return new ChunkApplyAck(deliveryIds);
    }
}
