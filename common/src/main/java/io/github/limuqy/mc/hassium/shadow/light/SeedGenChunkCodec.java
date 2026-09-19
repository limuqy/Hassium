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
            writeForeignLight(packet, chunk, engine);
            ShadowLightProbe.onReturnPacket(chunk, engine, packet);
            return packet;
        } catch (Exception e) {
            Constants.LOG.error("Hassium: SeedGen failed to build chunk packet {}", chunk.getPos(), e);
            return null;
        }
    }

    /**
     * 外部光照引擎（Starlight / ScalableLux）下用**交付柱自己的 nibble** 重写包的 light 掩码/载荷。
     * <p>
     * <b>为什么原版构造器在这里拿不到光</b>：原版 {@code prepareSectionData} 走
     * {@code engine.getLayerListener(layer).getDataLayerData(sp)}。原版引擎的光照存在**引擎自己的
     * SectionPos 索引存储**里，与该柱实例无关；Starlight 血缘把它换成「反查关卡里那一份柱」的 reader
     * （{@code ServerWorldMixin.getAnyChunkImmediately → chunkMap.getVisibleChunkIfPresent}），
     * 影子端注入柱大多不是「可见 holder」→ reader 恒返回 null → 26 段全部「省略」→ 客户端保持 NULL 态
     * → {@code StarLightInterface.getSkyLightValue} 读 15 → 地下全亮。
     * <p>
     * 实测（1.21.1 fabric + ScalableLux，1400 柱）：交付柱上确实有真数据
     * （{@code avgUninit=8 avgInit=2}），而同一引擎同一批 SectionPos 的 reader
     * {@code readerNonNull=0}；本函数生效后 1400/1400 包带光、客户端 1500 柱全部有真实光数据。
     * <p>
     * 本函数按**原版 {@code prepareSectionData} 同语义**重打包：{@code toVanillaNibble() == null}
     * （NULL/HIDDEN）→ 两个掩码都不置位；{@code isEmpty()} → empty 掩码；否则 data 掩码 + 载荷
     * （{@code copy().getData()}）。掩码位升序 ↔ 载荷列表一一对应（客户端 {@code readSectionList}
     * 按 {@code nextSetBit} 顺序配对）。非 Starlight 血缘（拿不到 nibble）直接返回，原版路径不变。
     */
    private static void writeForeignLight(ClientboundLevelChunkWithLightPacket packet,
                                          LevelChunk chunk, LevelLightEngine engine) {
        try {
            Object[] sky = io.github.limuqy.mc.hassium.compat.mods.ForeignLightEngine.nibbles(chunk, true);
            Object[] block = io.github.limuqy.mc.hassium.compat.mods.ForeignLightEngine.nibbles(chunk, false);
            if (sky == null && block == null) {
                return;
            }
            net.minecraft.network.protocol.game.ClientboundLightUpdatePacketData ld = packet.getLightData();
            int sections = engine.getLightSectionCount();
            if (sky != null) {
                fillLight(ld.getSkyYMask(), ld.getEmptySkyYMask(), ld.getSkyUpdates(), sky, sections);
            }
            if (block != null) {
                fillLight(ld.getBlockYMask(), ld.getEmptyBlockYMask(), ld.getBlockUpdates(), block, sections);
            }
        } catch (Throwable t) {
            Constants.LOG.warn("Hassium: writeForeignLight failed {}", chunk.getPos(), t);
        }
    }

    private static void fillLight(java.util.BitSet dataMask, java.util.BitSet emptyMask,
                                  java.util.List<byte[]> updates, Object[] nibbles, int sections) {
        dataMask.clear();
        emptyMask.clear();
        updates.clear();
        int limit = Math.min(sections, nibbles.length);
        for (int i = 0; i < limit; i++) {
            DataLayer dl = io.github.limuqy.mc.hassium.compat.mods.ForeignLightEngine
                    .toVanillaNibble(nibbles[i]);
            if (dl == null) {
                continue;
            }
            if (dl.isEmpty()) {
                emptyMask.set(i);
            } else {
                dataMask.set(i);
                updates.add(dl.copy().getData());
            }
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
