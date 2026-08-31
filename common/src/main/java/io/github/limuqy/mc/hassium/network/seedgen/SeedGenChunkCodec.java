package io.github.limuqy.mc.hassium.network.seedgen;

import io.github.limuqy.mc.hassium.Constants;
import io.github.limuqy.mc.hassium.compat.ShadowServerCompat;
import io.github.limuqy.mc.hassium.network.ChunkCompressionHandler;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.LevelChunk;

/**
 * Local generated/shadow chunk to online packet encoder.
 */
public final class SeedGenChunkCodec {

    private SeedGenChunkCodec() {}

    /** 使用原版区块包构造器生成 blocks + sky light + block light。 */
    public static ClientboundLevelChunkWithLightPacket buildPacket(LevelChunk chunk, ServerLevel level) {
        try {
            ClientboundLevelChunkWithLightPacket packet =
                    new ClientboundLevelChunkWithLightPacket(chunk, level.getLightEngine(), null, null);
            ShadowLightProbe.onReturnPacket(chunk, level.getLightEngine(), packet);
            return packet;
        } catch (Exception e) {
            Constants.LOG.error("Hassium: SeedGen failed to build chunk packet {}", chunk.getPos(), e);
            return null;
        }
    }

    public static byte[] encode(ClientboundLevelChunkWithLightPacket chunkPacket,
                                 net.minecraft.core.RegistryAccess registryAccess) {
        return ShadowServerCompat.encodeLevelChunkPacket(chunkPacket, registryAccess);
    }

    public static byte[] compress(byte[] chunkData, int x, int z) {
        ChunkCompressionHandler.CompressedChunkData compressed =
                ChunkCompressionHandler.compressChunkData(chunkData, x, z);
        return compressed == null ? null : compressed.encode();
    }
}
