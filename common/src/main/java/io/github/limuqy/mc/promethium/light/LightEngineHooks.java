package io.github.limuqy.mc.promethium.light;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.lighting.LevelLightEngine;

/**
 * 官方引擎原语 + 宿主缓存读写钩子。
 * <p>
 * 全部由宿主（Hassium）实现。原语实现留在宿主侧 hooks 类内——它们依赖
 * 宿主 mixin accessor（{@code LevelLightEngineAccessor} / {@code LightEngineAccessor}），
 * accessor 与 mixins.json 注册因此不随引擎迁移。
 */
public interface LightEngineHooks {

    /**
     * 在预算内运行官方光照更新，返回实际处理的任务数。
     * 宿主实现内含失败清队列逻辑。
     */
    int safeRunLightUpdates(LevelLightEngine lightEngine);

    /** 确保目标柱 section 数据层存在（官方 LayerLightEngine data 层懒分配）。 */
    void ensureColumnDataLayers(ClientLevel level, LevelLightEngine lightEngine,
                                ChunkPos chunkPos, int bottomSection, int topSection);

    /** 确保目标柱的已加载邻居 section 数据层存在。 */
    void ensureNeighborDataLayers(ClientLevel level, LevelLightEngine lightEngine,
                                  ChunkPos chunkPos, int bottomSection, int topSection);

    /** 从已加载邻居边缘拉光（官方 propagateFromNeighbor / 边缘注入）。 */
    void pullLightFromNeighborEdges(ClientLevel level, ChunkPos chunkPos,
                                    int bottomSection, int topSection);

    /** 用引擎当前光照数据更新宿主磁盘缓存（hash 一致时只补光 + 写回入队）。 */
    void updateCacheWithLightData(ClientLevel level, ChunkPos chunkPos, CompoundTag cachedNbt);

    /**
     * 从宿主磁盘缓存读取指定区块 NBT。
     *
     * @return 缓存 NBT，未命中返回 {@code null}
     */
    CompoundTag loadChunkNbtFromCache(ChunkPos pos);
}
