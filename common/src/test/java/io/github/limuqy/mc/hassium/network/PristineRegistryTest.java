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
 *   <li>{@link PristineRegistry#onBlockModified} 修改即置墓碑（永不重登记，幂等）</li>
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

        // 三主维度：overworld/nether/end 均可登记命中（门控放宽，REQ 明细5）
        ResourceKey<Level> nether = ResourceKey.create(Registries.DIMENSION,
                ResourceLocationCompat.create("minecraft:the_nether"));
        ResourceKey<Level> end = ResourceKey.create(Registries.DIMENSION,
                ResourceLocationCompat.create("minecraft:the_end"));
        PristineRegistry.markPristineForTest(nether, pos);
        PristineRegistry.markPristineForTest(end, pos);
        assertTrue(PristineRegistry.isPristine(nether, pos));
        assertTrue(PristineRegistry.isPristine(end, pos));

        // 改动（setBlockState → onBlockModified）→ 移除登记，且幂等
        PristineRegistry.onBlockModified(PristineRegistry.OVERWORLD_KEY, pos);
        assertFalse(PristineRegistry.isPristine(PristineRegistry.OVERWORLD_KEY, pos));
        PristineRegistry.onBlockModified(PristineRegistry.OVERWORLD_KEY, pos);
        assertFalse(PristineRegistry.isPristine(PristineRegistry.OVERWORLD_KEY, pos));

        PristineRegistry.clear();
    }


    @Test
    void tombstoneShouldPreventReregistration() {
        // review-fix: T3-49：墓碑语义——修改后同会话内再次推送（resync/客户端重请求
        // 触发重推）不得重新登记；否则再发 SeedRef → 客户端重生成原始地形覆盖玩家修改
        ChunkPos pos = new ChunkPos(5, 6);
        PristineRegistry.clear();
        PristineRegistry.markPristineForTest(PristineRegistry.OVERWORLD_KEY, pos);
        assertTrue(PristineRegistry.isPristine(PristineRegistry.OVERWORLD_KEY, pos));

        PristineRegistry.onBlockModified(PristineRegistry.OVERWORLD_KEY, pos);
        assertFalse(PristineRegistry.isPristine(PristineRegistry.OVERWORLD_KEY, pos));

        // 墓碑存在：重推触发再次登记（markPristineForTest 与实现同源，putIfAbsent）→ 不得复活
        PristineRegistry.markPristineForTest(PristineRegistry.OVERWORLD_KEY, pos);
        assertFalse(PristineRegistry.isPristine(PristineRegistry.OVERWORLD_KEY, pos));

        PristineRegistry.clear();
    }

    @Test
    void customDimensionShouldNeverRegisterOrHit() {
        // 自定义维度：白名单外恒不命中；且不污染三主维度同坐标登记
        ChunkPos pos = new ChunkPos(9, -2);
        PristineRegistry.clear();
        ResourceKey<Level> custom = ResourceKey.create(Registries.DIMENSION,
                ResourceLocationCompat.create("somemod:custom_dim"));
        PristineRegistry.markPristineForTest(custom, pos);
        assertFalse(PristineRegistry.isPristine(custom, pos));

        // 同坐标 overworld 登记不受自定义维度影响（复合键维度隔离）
        PristineRegistry.markPristineForTest(PristineRegistry.OVERWORLD_KEY, pos);
        assertTrue(PristineRegistry.isPristine(PristineRegistry.OVERWORLD_KEY, pos));
        assertFalse(PristineRegistry.isPristine(custom, pos));

        // 自定义维度修改置墓碑也不影响其他维度
        PristineRegistry.onBlockModified(custom, pos);
        assertFalse(PristineRegistry.isPristine(custom, pos));
        assertTrue(PristineRegistry.isPristine(PristineRegistry.OVERWORLD_KEY, pos));

        PristineRegistry.clear();
    }

    @Test
    void crossDimensionSamePosShouldBeIsolated() {
        // 跨维同坐标：nether 修改墓碑不影响 end 的 pristine 登记（复合键隔离）
        ChunkPos pos = new ChunkPos(4, 4);
        PristineRegistry.clear();
        ResourceKey<Level> nether = ResourceKey.create(Registries.DIMENSION,
                ResourceLocationCompat.create("minecraft:the_nether"));
        ResourceKey<Level> end = ResourceKey.create(Registries.DIMENSION,
                ResourceLocationCompat.create("minecraft:the_end"));
        PristineRegistry.markPristineForTest(nether, pos);
        PristineRegistry.markPristineForTest(end, pos);
        PristineRegistry.onBlockModified(nether, pos);
        assertFalse(PristineRegistry.isPristine(nether, pos));
        assertTrue(PristineRegistry.isPristine(end, pos));

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
