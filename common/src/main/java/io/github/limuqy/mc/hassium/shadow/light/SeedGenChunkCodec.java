package io.github.limuqy.mc.hassium.shadow.light;

import io.github.limuqy.mc.hassium.shadow.server.ShadowSeedServer;

import io.github.limuqy.mc.hassium.Constants;
import io.github.limuqy.mc.hassium.compat.ShadowServerCompat;
import java.util.BitSet;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.LightLayer;
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
            BitSet[] masks = deliveryLightMasks(engine, chunk, level);
            ClientboundLevelChunkWithLightPacket packet = masks == null
                    ? new ClientboundLevelChunkWithLightPacket(chunk, engine, null, null)
                    : new ClientboundLevelChunkWithLightPacket(chunk, engine, masks[0], masks[1]);
            ShadowLightProbe.onReturnPacket(chunk, engine, packet);
            return packet;
        } catch (Exception e) {
            Constants.LOG.error("Hassium: SeedGen failed to build chunk packet {}", chunk.getPos(), e);
            return null;
        }
    }

    /**
     * 交付光掩码；{@code null} = 走 vanilla 全柱枚举。
     * <p>
     * <b>为什么需要它（2026-09-16 flyroundtrip 实测根因）</b>：vanilla
     * {@code ClientboundLightUpdatePacketData.prepareSectionData} 会把「层存在但线上全 0」的
     * section 记进 {@code emptyYMask}，客户端 {@code readSectionList} 据此**显式写全 0**
     * ——于是「客户端本来亮着的地表 section」在重交付（{@code shadow_memory_cache}，
     * apply#+1）时被打黑：实测 {@code skyTop=0} 而 {@code skyAir=15 topBlock=air}
     * （地表上一格无光、柱顶有光 = 黑板）。
     * <p>
     * 判据：**客户端已持有该柱**（{@code hasClientApplyEpoch}）时只下发「线上确有光」的 section，
     * 空 section 不进包 → 客户端**保留旧光**；初次交付仍走 vanilla 语义（客户端没有旧光可保）。
     * <p>
     * 试过的更强判据都**实测更差、已回退**，勿凭直觉加回来（2026-09-16 flyroundtrip 逐轮实测，
     * 每轮 1.21.1 fabric / MoveSeconds=20）：skyTop=0 采样 31（改前）→ 17（收敛门）→ 3（本方法
     * + 光桥 no-downgrade）→ 6（加「开天格必须 15」窄口径）→ 24（同判据改宽口径，保留空 section
     * 断言）→ 35（去掉「无 epoch → vanilla 全柱」分叉）。后两轮还分别出现 2 / 8 个「先亮后黑」
     * 回归柱，而本方法配置下为 0。
     * <p>
     * 任何会牵动 {@code isLightReusable} 的状态判据都会让缓存重交付退化成重算 → 光栅队列越水位 →
     * 重注入路径主线程 5s 忙等（实测整卡死）。本方法**纯交付侧过滤**，不改引擎状态、不触发重算。
     */
    static BitSet[] deliveryLightMasks(LevelLightEngine engine, LevelChunk chunk, ServerLevel level) {
        if (engine == null || chunk == null || level == null) {
            return null;
        }
        try {
            if (!level.dimensionType().hasSkyLight()) {
                return null;
            }
            if (!ShadowLightCompute.hasClientApplyEpoch(ShadowSeedServer.dimensionId(level), chunk.getPos())) {
                return null; // 初次交付：保持 vanilla 语义
            }
            // 两层都要收窄：{@code null} 掩码 = 让包构造器枚举**整列**，空 section 会被记成
            // emptyYMask/emptyBlockYMask → 客户端显式置 0。旧代码 block 传 null，等于每次
            // 「已落地柱整柱重交付」都把整列方块光（火把/岩浆/洞内照明）清成 0。
            return new BitSet[]{
                    wireLightMask(engine, chunk.getPos(), LightLayer.SKY),
                    wireLightMask(engine, chunk.getPos(), LightLayer.BLOCK)};
        } catch (Throwable t) {
            return null; // 探针异常不得改变交付（回退全柱枚举）
        }
    }

    /** 掩码：只收「该层线上确有非 0 半字节」的 section（位 = sectionY − minLightSection）。 */
    static BitSet wireLightMask(LevelLightEngine engine, net.minecraft.world.level.ChunkPos pos, LightLayer layer) {
        BitSet mask = new BitSet();
        int minLightSection = engine.getMinLightSection();
        for (int i = 0; i < engine.getLightSectionCount(); i++) {
            if (hasWireLight(engine.getLayerListener(layer)
                    .getDataLayerData(SectionPos.of(pos, minLightSection + i)))) {
                mask.set(i);
            }
        }
        return mask;
    }

    /**
     * 该层是否存在非 0 半字节（{@code get} 低 4 位 = vanilla 打包写入线格式的值，同口径）。
     * 无数组层走 {@code isDefinitelyHomogenous} 的 O(1) 分支——清光占位层正是这一形态，
     * 既快又不会调 {@code getData()} 去 materialize 共享实例（有副作用）。
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
