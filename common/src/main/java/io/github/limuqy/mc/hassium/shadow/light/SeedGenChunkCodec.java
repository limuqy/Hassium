package io.github.limuqy.mc.hassium.shadow.light;

import io.github.limuqy.mc.hassium.Constants;
import io.github.limuqy.mc.hassium.compat.ShadowServerCompat;
import net.minecraft.core.BlockPos;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.DataLayer;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.lighting.LevelLightEngine;

/**
 * Local generated/shadow chunk to online packet encoder.
 */
public final class SeedGenChunkCodec {

    private SeedGenChunkCodec() {}

    /** 使用原版区块包构造器生成 blocks + sky light + block light。 */
    public static ClientboundLevelChunkWithLightPacket buildPacket(LevelChunk chunk, ServerLevel level) {
        try {
            LevelLightEngine engine = level.getLightEngine();
            // 【2026-09-19】交付掩码一律走 vanilla 全柱枚举（null/null）。
            // 曾按「客户端已持有该柱」收窄掩码以规避空 section 被记成 emptyYMask → 客户端显式置 0
            // （2026-09-16 flyroundtrip：skyTop=0 采样 31 → 3）。该收窄是交付侧叠甲，根因是当时
            // 影子层半成品/空层被当成权威光下发；现影子端只推完整光（屏障完成后才打包），且
            // {@code 7d335166} 已修读盘柱光源表未填 → 整柱重交付不再出现「该亮却空」的层。
            // 代价：每柱交付不再扫 4096 格/层（原实测 ≈3.4e9 格读）。若要回退，需带 skyTop 采样的 A/B。
            ClientboundLevelChunkWithLightPacket packet =
                    new ClientboundLevelChunkWithLightPacket(chunk, engine, null, null);
            ShadowLightProbe.onReturnPacket(chunk, engine, packet);
            return packet;
        } catch (Exception e) {
            Constants.LOG.error("Hassium: SeedGen failed to build chunk packet {}", chunk.getPos(), e);
            return null;
        }
    }


    /**
     * 该层是否存在非 0 半字节（{@code get} 低 4 位 = vanilla 打包写入线格式的值，同口径）。
     * 无数组层走 {@code isDefinitelyHomogenous} 的 O(1) 分支——清光占位层正是这一形态，
     * 既快又不会调 {@code getData()} 去 materialize 共享实例（有副作用）。
     * <p>
     * 【2026-09-19】唯一调用点是 {@code ShadowSeedServer.isColumnSurfaceLightReady}
     * （柱地表光就绪判定）；曾经的第二个调用点 {@code wireLightMask}（交付掩码收窄）已随
     * 光桥删除。原先挂在本方法上的 {@code probeWireLightScan} 扫描计数随之移除。
     */
    public static boolean hasWireLight(DataLayer layer) {
        if (layer == null) {
            return false;
        }
        if (layer.isDefinitelyHomogenous()) {
            return (layer.get(0, 0, 0) & 15) != 0;
        }
        for (int y = 15; y >= 0; y--) {
            for (int z = 0; z < 16; z++) {
                for (int x = 0; x < 16; x++) {
                    if ((layer.get(x, y, z) & 15) != 0) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    public static byte[] encode(ClientboundLevelChunkWithLightPacket chunkPacket,
                                 net.minecraft.core.RegistryAccess registryAccess) {
        return ShadowServerCompat.encodeLevelChunkPacket(chunkPacket, registryAccess);
    }
}
