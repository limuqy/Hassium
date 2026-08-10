package io.github.limuqy.mc.hassium.network;

import io.github.limuqy.mc.hassium.compat.ResourceLocationCompat;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * PristineRegistry 单测（SeedGen Phase 1）。
 * <p>
 * 边界：本测试族不引导 Minecraft；{@code markIfPristine}（需 Level/ChunkAccess）
 * 留联机验收。这里覆盖不依赖 MC ROI 的纯逻辑：
 * <ul>
 *   <li>{@link PristineRegistry#isPristineCandidate} 判定真值表（status/inhabitedTime/modified）</li>
 *   <li>{@link PristineRegistry#onBlockModified} 修改即移除登记（幂等）</li>
 *   <li>{@link PristineRegistry#clear} 清空（重启语义：全部视为非 pristine）</li>
 *   <li>{@link PristineRegistry#isPristine}（含非主世界不命中）/ {@code isEmpty} 状态查询</li>
 * </ul>
 */
class PristineRegistryTest {

    @Test
    void candidateShouldRequireFullStatus() {
        // status 未 FULL → 即使未修改、无居住时间也不登记
        assertFalse(PristineRegistry.isPristineCandidate(false, 0L, false));
    }

    @Test
    void candidateShouldRejectModifiedChunk() {
        // 已修改（登记表已有该块）→ 不登记
        assertFalse(PristineRegistry.isPristineCandidate(true, 0L, true));
    }

    @Test
    void candidateShouldRejectInhabitedChunk() {
        // inhabitedTime > 0 → 老区块，不登记（辅助防误判）
        assertFalse(PristineRegistry.isPristineCandidate(true, 1L, false));
    }

    @Test
    void candidateShouldAcceptFreshFullChunk() {
        // status FULL + inhabitedTime==0 + 未修改 → pristine 候选
        assertTrue(PristineRegistry.isPristineCandidate(true, 0L, false));
    }

    @Test
    void blockModificationShouldRemoveRegistration() {
        ChunkPos pos = new ChunkPos(1, 2);
        PristineRegistry.clear();
        assertTrue(PristineRegistry.isEmpty());

        // 模拟 markIfPristine 的登记效果：直接经候选判定置位（与实现同源）
        PristineRegistry.markPristineForTest(PristineRegistry.OVERWORLD_KEY, pos);
        assertTrue(PristineRegistry.isPristine(PristineRegistry.OVERWORLD_KEY, pos));
        assertFalse(PristineRegistry.isEmpty());

        // 非主世界维度：不命中 pristine（静默走全量）
        ResourceKey<Level> nether = ResourceKey.create(Registries.DIMENSION,
                ResourceLocationCompat.create("minecraft:the_nether"));
        assertFalse(PristineRegistry.isPristine(nether, pos));

        // 改动（setBlockState → onBlockModified）→ 移除登记，且幂等
        PristineRegistry.onBlockModified(PristineRegistry.OVERWORLD_KEY, pos);
        assertFalse(PristineRegistry.isPristine(PristineRegistry.OVERWORLD_KEY, pos));
        PristineRegistry.onBlockModified(PristineRegistry.OVERWORLD_KEY, pos);
        assertFalse(PristineRegistry.isPristine(PristineRegistry.OVERWORLD_KEY, pos));

        PristineRegistry.clear();
    }

    @Test
    void clearShouldResetAll() {
        PristineRegistry.markPristineForTest(PristineRegistry.OVERWORLD_KEY, new ChunkPos(-3, 7));
        PristineRegistry.markPristineForTest(PristineRegistry.OVERWORLD_KEY, new ChunkPos(0, 0));
        assertFalse(PristineRegistry.isEmpty());

        PristineRegistry.clear();
        assertTrue(PristineRegistry.isEmpty());
        assertFalse(PristineRegistry.isPristine(PristineRegistry.OVERWORLD_KEY, new ChunkPos(-3, 7)));
    }
}
