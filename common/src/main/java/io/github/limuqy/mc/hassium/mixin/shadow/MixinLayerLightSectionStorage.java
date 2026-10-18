package io.github.limuqy.mc.hassium.mixin.shadow;

import net.minecraft.world.level.chunk.DataLayer;
import net.minecraft.world.level.lighting.LayerLightSectionStorage;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Final;
import net.minecraft.world.level.chunk.LightChunkGetter;
import net.minecraft.server.level.ServerLevel;
import io.github.limuqy.mc.hassium.shadow.server.ShadowSeedServer;
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
 * 仅当 chunkSource.getLevel() 属于 ShadowSeedServer 时替换 null；客户端世界、
 * 真实内置服及专用服保持原值。保留影子传播的历史 2048 哨兵和 Lomka 优先级，
 * 不改变邻域齐套门、isLightCorrect 判据或交付域。
 */
// Lomka 0.7.0 overwrites setStoredLevel at priority 1000. A higher injector priority
// allows our null guard to decorate that replacement instead of failing injection.
@Mixin(value = LayerLightSectionStorage.class, priority = 1100)
public abstract class MixinLayerLightSectionStorage {
    @Shadow @Final protected LightChunkGetter chunkSource;

    @Unique
    private boolean hassium$isShadowStorage() {
        return chunkSource != null && chunkSource.getLevel() instanceof ServerLevel level
                && level.getServer() instanceof ShadowSeedServer;
    }


    /** 只读空光层：getStoredLevel 读路径 null 兜底。共享只读，绝不被写。
     *  参数 2048 是刻意的非 0 默认 nibble 值（{@code DataLayer(int)} 收的是默认光照值，
     *  不是字节数）：非 0 会让传播认为该 section 已满、不再排任务，光照管线按此工作量调优；
     *  改成 0 会让 lightTasks 积压 → 重注入路径主线程 5s 忙等（2026-09-16 实测卡死）。 */
    @Unique
    private static final DataLayer hassium$EMPTY_DATA_LAYER = new DataLayer(2048);

    /** getStoredLevel（读路径）：null → 共享空层（保留影子传播哨兵值 2048）。 */
    @ModifyVariable(method = "getStoredLevel", at = @At("STORE"), ordinal = 0)
    private DataLayer hassium$nullSafeGetStoredLevel(DataLayer layer) {
        return layer == null && hassium$isShadowStorage() ? hassium$EMPTY_DATA_LAYER : layer;
    }

    /** setStoredLevel（写路径）：null → 新空层（写安全；该 section 正在被并发清光，
     *  本次写入本就是过期数据，丢弃无碍；绝不写共享 EMPTY）。 */
    @ModifyVariable(method = "setStoredLevel", at = @At("STORE"), ordinal = 0)
    private DataLayer hassium$nullSafeSetStoredLevel(DataLayer layer) {
        return layer == null && hassium$isShadowStorage() ? new DataLayer(2048) : layer;
    }
}
