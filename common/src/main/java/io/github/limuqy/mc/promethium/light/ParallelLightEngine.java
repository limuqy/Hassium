package io.github.limuqy.mc.promethium.light;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.ChunkPos;

/**
 * 并行光照引擎消费面 API。
 * <p>
 * Hassium 是唯一消费者：客户端缓存读回 / chunk apply / tick 尾预算 / 断连清理
 * 全部通过本接口访问引擎，不直接触碰实现类。
 * <p>
 * 线程约定：
 * <ul>
 *   <li>{@link #submitRecompute} / {@link #clear} / {@link #onChunkDataReplaced}：
 *       任意线程可调，引擎内部做线程归属与合并。</li>
 *   <li>{@link #drainCompletions}：客户端 tick 尾调用，预算内捕获 + 落地，不得阻塞。</li>
 *   <li>{@link #configure}：幂等装配，重复调用无害。</li>
 * </ul>
 */
public interface ParallelLightEngine {

    /**
     * 提交一个区块列的光照重算。
     *
     * @param corePos   核心区块坐标（重算域中心柱）
     * @param cachedNbt 该柱的缓存 NBT（可为 null；引擎内部会做壳光种子兜底读取）
     */
    void submitRecompute(ChunkPos corePos, CompoundTag cachedNbt);

    /**
     * chunk apply 完成后失效旧快照（新数据已落地，旧 capture 不得覆盖）。
     */
    void onChunkDataReplaced(ClientLevel level, ChunkPos pos);

    /**
     * 客户端 tick 尾调用，预算内捕获 + 落地已完成的重算结果。
     *
     * @param deadlineNs 帧预算截止（{@link System#nanoTime()} 时间轴）
     */
    void drainCompletions(long deadlineNs);

    /**
     * 断连清理，任意线程可调。清空内部队列；不触碰装配状态。
     */
    void clear();

    /**
     * 装配注入（线程数 / 指标 / 官方引擎原语与缓存读写钩子）。
     * 幂等：重复调用仅覆盖字段。
     */
    void configure(LightEngineConfig config, LightEngineStats stats, LightEngineHooks hooks);

    /**
     * 返回引擎进程级单例。
     */
    static ParallelLightEngine getInstance() {
        return ParallelLightEngineImplHolder.INSTANCE;
    }

    /** 延迟加载 holder，避免接口静态块触发实现类初始化。 */
    final class ParallelLightEngineImplHolder {
        private static final ParallelLightEngine INSTANCE =
                io.github.limuqy.mc.promethium.light.impl.ParallelLightEngineImpl.getInstance();
        private ParallelLightEngineImplHolder() {}
    }
}
