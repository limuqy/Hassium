package io.github.limuqy.mc.hassium.network.seedgen;

import io.github.limuqy.mc.hassium.Constants;
import io.github.limuqy.mc.hassium.network.ChunkCompressionHandler;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.lighting.LevelLightEngine;

/**
 * 本地生成 / 影子回传区块 → 线上数据编码（与 ServerChunkPushManager 编码链等价的独立副本，
 * 避免把该管理器的 private 方法改造成公共 API）。
 * <p>
 * 收敛首包与原版单人相同构造：{@code new ClientboundLevelChunkWithLightPacket(chunk, engine, null, null)}。
 */
public final class SeedGenChunkCodec {

    private SeedGenChunkCodec() {}

    /**
     * 构建区块包。{@code lightChunk} future 成功后与单人一致：两个 BitSet 为 null，
     * 由引擎自己决定打包哪些光层。禁止在默认路径上自研 sky/block 掩码。
     */
    public static ClientboundLevelChunkWithLightPacket buildPacket(LevelChunk chunk, ServerLevel level) {
        try {
            LevelLightEngine engine = level.getLightEngine();
            return new ClientboundLevelChunkWithLightPacket(chunk, engine, null, null);
        } catch (Exception e) {
            Constants.LOG.error("Hassium: SeedGen failed to build chunk packet {}", chunk.getPos(), e);
            return null;
        }
    }

    /**
     * 将区块包编码为线格式字节（RegistryAccess 只读，任意线程编码安全）。
     * 与 ServerChunkPushManager.encodeChunkPacket 同构。
     */
    @SuppressWarnings("deprecation") // NeoForge 1.21.11+: RegistryFriendlyByteBuf(2-param) deprecated; 3-param 需 ConnectionType.OTHER(仅 NeoForge)
    public static byte[] encode(ClientboundLevelChunkWithLightPacket chunkPacket, RegistryAccess registryAccess) {
#if MC_VER < MC_1_20_5
        io.netty.buffer.ByteBuf tempBuf = io.netty.buffer.Unpooled.buffer();
        try {
            net.minecraft.network.FriendlyByteBuf friendlyBuf = new net.minecraft.network.FriendlyByteBuf(tempBuf);
            chunkPacket.write(friendlyBuf);
            byte[] data = new byte[tempBuf.readableBytes()];
            tempBuf.getBytes(0, data);
            return data;
        } catch (Exception e) {
            Constants.LOG.error("Hassium: SeedGen failed to encode chunk packet", e);
            return null;
        } finally {
            tempBuf.release();
        }
#else
        net.minecraft.network.RegistryFriendlyByteBuf buf =
                new net.minecraft.network.RegistryFriendlyByteBuf(
                        io.netty.buffer.Unpooled.buffer(), registryAccess);
        try {
            ClientboundLevelChunkWithLightPacket.STREAM_CODEC.encode(buf, chunkPacket);
            byte[] data = new byte[buf.readableBytes()];
            buf.readBytes(data);
            return data;
        } catch (Exception e) {
            Constants.LOG.error("Hassium: SeedGen failed to encode chunk packet", e);
            return null;
        } finally {
            buf.release();
        }
#endif
    }

    /** 压缩为线上传输格式（ZSTD + LZ4 按既有链）；失败返回 null。 */
    public static byte[] compress(byte[] chunkData, int x, int z, long deliveryId) {
        ChunkCompressionHandler.CompressedChunkData compressed =
                ChunkCompressionHandler.compressChunkData(chunkData, x, z, deliveryId);
        return compressed == null ? null : compressed.encode();
    }
}
