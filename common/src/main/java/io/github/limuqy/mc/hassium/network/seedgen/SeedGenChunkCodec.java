package io.github.limuqy.mc.hassium.network.seedgen;

import io.github.limuqy.mc.hassium.Constants;
import io.github.limuqy.mc.hassium.network.ChunkCompressionHandler;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.LevelChunk;

/**
 * 本地生成区块 → 线上数据编码（与 ServerChunkPushManager 编码链等价的独立副本，
 * 避免把该管理器的 private 方法改造成公共 API）。
 * <p>
 * 与直推路径的差异：不剥光（本地生成自带完整光照，直接下发）→ 客户端零重算。
 */
public final class SeedGenChunkCodec {

    private SeedGenChunkCodec() {}

    /** 构建区块包（不剥光：全量 mask 由 vanilla 逻辑填充）。 */
    public static ClientboundLevelChunkWithLightPacket buildPacket(LevelChunk chunk, ServerLevel level) {
        try {
            return new ClientboundLevelChunkWithLightPacket(chunk, level.getLightEngine(), null, null);
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
    public static byte[] compress(byte[] chunkData, int x, int z) {
        ChunkCompressionHandler.CompressedChunkData compressed =
                ChunkCompressionHandler.compressChunkData(chunkData, x, z);
        return compressed == null ? null : compressed.encode();
    }
}
