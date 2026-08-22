package io.github.limuqy.mc.hassium.network;

import io.netty.handler.codec.DecoderException;
import net.minecraft.network.FriendlyByteBuf;

/**
 * 服务端 -> 客户端：SeedRef（SeedGen 区块引用，Phase 1 引入）。
 * <p>
 * 语义：该区块是 pristine（本会话生成且未修改），客户端若支持 SeedGen 应本地
 * 复算并按 hash 校验；不支持/校验失败时回退全量请求。替代 ChunkHashS2C 的
 * 数据任务（几十字节 vs 全量区块数据）。
 *
 * @param chunkX       区块 X 坐标
 * @param chunkZ       区块 Z 坐标
 * @param contentHash  chunk 级 hash（xxHash64，服务端生成完成时计算）
 * @param sectionHashes per-section hash 数组（与 ChunkHashS2CPacket bitmap 对应的完整数组）
 * @param deliveryId    full/SeedGen authoritative delivery 标识；0 仅限明确非 flow-controlled 路径
 */
public record SeedRefS2CPacket(
        int chunkX,
        int chunkZ,
        long contentHash,
        long[] sectionHashes,
        long deliveryId
) {
    /** review-fix: T3-53：恶意/损坏包 count 驱动 new long[count] 可 OOM 客户端；每 chunk section 数上限（1.18+ ≤ 24） */
    private static final int MAX_SECTION_HASHES = 512;

    public SeedRefS2CPacket {
        if (deliveryId < 0) {
            throw new IllegalArgumentException("deliveryId must be non-negative");
        }
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeVarInt(chunkX);
        buf.writeVarInt(chunkZ);
        buf.writeLong(contentHash);
        buf.writeVarInt(sectionHashes.length);
        for (long h : sectionHashes) {
            buf.writeLong(h);
        }
        buf.writeLong(deliveryId);
    }

    public static SeedRefS2CPacket decode(FriendlyByteBuf buf) {
        int chunkX = buf.readVarInt();
        int chunkZ = buf.readVarInt();
        long contentHash = buf.readLong();
        int count = buf.readVarInt();
        if (count < 0 || count > MAX_SECTION_HASHES) {
            throw new DecoderException("SeedRefS2CPacket section hash count too large: " + count);
        }
        long[] sectionHashes = new long[count];
        for (int i = 0; i < count; i++) {
            sectionHashes[i] = buf.readLong();
        }
        long deliveryId = buf.readLong();
        if (deliveryId < 0 || buf.isReadable()) {
            throw new DecoderException("Malformed SeedRefS2CPacket deliveryId");
        }
        return new SeedRefS2CPacket(chunkX, chunkZ, contentHash, sectionHashes, deliveryId);
    }
}
