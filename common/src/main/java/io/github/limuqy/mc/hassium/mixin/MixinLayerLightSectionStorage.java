package io.github.limuqy.mc.hassium.mixin;

import net.minecraft.world.level.chunk.DataLayer;
import net.minecraft.world.level.lighting.LayerLightSectionStorage;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/**
 * 影子端光照引擎 null 安全（2026-08-14 1.20.1 定位）。
 * <p>
 * {@code LayerLightSectionStorage.getStoredLevel} 对调用方入队的传播节点直接解引用
 * {@code getDataLayer(sectionPos, true)} 的结果——原版服务端保证节点所属 section 必
 * 有 DataLayer（lightChunk 按 ChunkStatus 递进、邻居 FULL 后才算光，同 chunk 任务
 * 串行）。影子端批量注入相邻柱时，{@code ThreadedLevelLightEngine} 任务经
 * ChunkTaskPriorityQueueSorter 在多个 mailbox/ForkJoinPool（Worker-Main-*）<b>并行</b>
 * 执行：A 柱传播（{@code propagateIncreases}）读邻居 B 柱 DataLayer 恰逢 B 柱清光
 * （{@code queueSectionData(null)} → removeSection → swapSectionMap 物理删除）→ null →
 * NPE。调用方锁（{@code ShadowLightCompute.LIGHT_ENGINE_MUTEX}）只能串行化<b>投递</b>，
 * 无法串行化<b>执行</b>，故在读取层兜底。
 * <p>
 * 兜底语义：null 视为该 section 光级 0（传播少算一点），绝不崩溃；任务不中断 → 传播
 * 队列无残留 → {@code isLightConverged} 不再被 NPE 卡死（此前 NPE 后队列残留 → 全批
 * 超时 → 欠光推送黑块）。被清光柱随后由自身 lightChunk/重算收敛，光最终正确。只对
 * 「并发删除窗口」生效，原版正常路径（非 null）行为完全不变。
 * <p>
 * 实现：@ModifyVariable 在 {@code getStoredLevel}/{@code setStoredLevel} 的局部变量
 * 存储点（STORE）把 null DataLayer 替换为兜底层——不触碰 protected 方法/字段，零
 * 跨版本签名风险（1.20.1~1.21.11 两方法均只含一个 DataLayer 局部变量）。
 */
@Mixin(LayerLightSectionStorage.class)
public abstract class MixinLayerLightSectionStorage {

    /** 只读空光层（全 0）：getStoredLevel 读路径 null 兜底。共享只读，绝不被写。 */
    @Unique
    private static final DataLayer hassium$EMPTY_DATA_LAYER = new DataLayer(2048);

    /** getStoredLevel（读路径）：null → 共享空层（get 返回 0）。 */
    @ModifyVariable(method = "getStoredLevel", at = @At("STORE"), ordinal = 0)
    private DataLayer hassium$nullSafeGetStoredLevel(DataLayer layer) {
        return layer != null ? layer : hassium$EMPTY_DATA_LAYER;
    }

    /** setStoredLevel（写路径）：null → 新空层（写安全；该 section 正在被并发清光，
     *  本次写入本就是过期数据，丢弃无碍；绝不写共享 EMPTY）。 */
    @ModifyVariable(method = "setStoredLevel", at = @At("STORE"), ordinal = 0)
    private DataLayer hassium$nullSafeSetStoredLevel(DataLayer layer) {
        return layer != null ? layer : new DataLayer(2048);
    }
}
