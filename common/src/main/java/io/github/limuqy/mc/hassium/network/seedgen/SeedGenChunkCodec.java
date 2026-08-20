package io.github.limuqy.mc.hassium.network.seedgen;

import io.github.limuqy.mc.hassium.Constants;
import io.github.limuqy.mc.hassium.compat.LevelHeightCompat;
import io.github.limuqy.mc.hassium.network.ChunkCompressionHandler;
import java.util.BitSet;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.SectionPos;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.chunk.DataLayer;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.lighting.LevelLightEngine;

/**
 * 本地生成区块 → 线上数据编码（与 ServerChunkPushManager 编码链等价的独立副本，
 * 避免把该管理器的 private 方法改造成公共 API）。
 * <p>
 * 与直推路径的差异：不剥光（本地生成自带完整光照，直接下发）→ 客户端零重算。
 */
public final class SeedGenChunkCodec {

    private SeedGenChunkCodec() {}

    /**
     * 构建区块包。天空光：非空层照常带上；源之上的空层仍打 empty 掩码；
     * 源之下的空层省略（避免 emptySkyYMask 把尚未被邻柱 increase 写入的屋檐钉成 0）。
     */
    public static ClientboundLevelChunkWithLightPacket buildPacket(LevelChunk chunk, ServerLevel level) {
        try {
            LevelLightEngine engine = level.getLightEngine();
            BitSet sky = skySectionsToPack(chunk, level, engine);
            BitSet block = blockSectionsToPack(chunk, engine);
            return new ClientboundLevelChunkWithLightPacket(chunk, engine, sky, block);
        } catch (Exception e) {
            Constants.LOG.error("Hassium: SeedGen failed to build chunk packet {}", chunk.getPos(), e);
            return null;
        }
    }

    private static BitSet skySectionsToPack(LevelChunk chunk, ServerLevel level, LevelLightEngine engine) {
        BitSet bits = new BitSet();
        net.minecraft.world.level.ChunkPos pos = chunk.getPos();
        int min = engine.getMinLightSection();
        int count = engine.getLightSectionCount();
        boolean hasSky = level.dimensionType().hasSkyLight();
        net.minecraft.world.level.lighting.ChunkSkyLightSources sources =
                hasSky ? chunk.getSkyLightSources() : null;
        int minBlockY = LevelHeightCompat.getMinBlockY(level);
        for (int i = 0; i < count; i++) {
            DataLayer sky = engine.getLayerListener(LightLayer.SKY)
                    .getDataLayerData(SectionPos.of(pos, min + i));
            boolean atOrAbove = sources != null
                    && ShadowSeedServer.sectionAtOrAboveAnySkySource(sources, min + i, minBlockY);
            if (ShadowLightCompute.shouldIncludeSkySectionInPacket(
                    sky != null, sky != null && sky.isEmpty(), atOrAbove)) {
                bits.set(i);
            }
        }
        return bits;
    }

    private static BitSet blockSectionsToPack(LevelChunk chunk, LevelLightEngine engine) {
        BitSet bits = new BitSet();
        net.minecraft.world.level.ChunkPos pos = chunk.getPos();
        int min = engine.getMinLightSection();
        int count = engine.getLightSectionCount();
        for (int i = 0; i < count; i++) {
            if (engine.getLayerListener(LightLayer.BLOCK)
                    .getDataLayerData(SectionPos.of(pos, min + i)) != null) {
                bits.set(i);
            }
        }
        return bits;
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
