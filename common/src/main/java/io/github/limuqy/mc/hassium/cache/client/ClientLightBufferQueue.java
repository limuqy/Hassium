package io.github.limuqy.mc.hassium.cache.client;

import io.github.limuqy.mc.hassium.Constants;
import io.github.limuqy.mc.hassium.config.HassiumConfigService;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunk;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 客户端光照统一异步缓冲队列（官方引擎路径）。
 * <p>
 * 官方 {@code LevelLightEngine} 单线程、主线程独占：逐块同步重算直接挤占 chunk apply
 * 预算，造成帧时间锯齿。本队列把全部光照重算任务（chunk 空光照 / 缓存预提交 / delta
 * 预提交）从 apply 链路剥离：帧内收集、帧尾按预算消费，每帧只给部分预算
 * （{@link #FRAME_BUDGET_NS}，到点即停），剩余自然留帧消化——收集与消费解耦，
 * 即统一异步缓冲，不区分单帧/双帧模式。
 * <p>
 * 官方引擎增量队列（propagateLightSources → runLightUpdates）天然支持分批消费且跨块
 * 传播自动合并，本队列只是消费的驱动单位——每块消费 = 一次完整的
 * {@link ClientLightRecomputeService#applyLightEngine}（建层 + 入队种子 + 预算内传播 +
 * 缓存写回）。
 * <p>
 * 线程约定：{@link #enqueue} 任意线程可调（并发容器，与 Promethium 引擎 submit 同约定）；
 * {@link #drainFrame} / {@link #clear} 主线程独占。引擎状态只被消费方（主线程）触碰，
 * 入队方不碰 {@code LevelLightEngine}。
 */
public final class ClientLightBufferQueue {

    /**
     * 每帧消费预算：官方重算 ~1-3ms/块，5ms ≈ 2-3 块/帧；加载风暴（maxChunksPerFrame=12）
     * 下自然积压、多帧消化——「光照只占部分主线程预算」的落点，帧时间不会被单帧峰值
     * 击穿。预算耗尽剩余留帧，无阻塞。
     */
    private static final long FRAME_BUDGET_NS = 5_000_000L;

    private static final ClientLightBufferQueue INSTANCE = new ClientLightBufferQueue();

    /** 待消费任务（任意线程入队；主线程帧尾消费）。 */
    private final Map<Long, Entry> pending = new ConcurrentHashMap<>();

    /** 帧尾预算耗尽诊断限频计数（仅主线程访问）。 */
    private static int BUDGET_DIAG_COUNT = 0;

    public static ClientLightBufferQueue getInstance() {
        return INSTANCE;
    }

    private ClientLightBufferQueue() {}

    /**
     * 入队一个光照重算任务（任意线程）。
     *
     * @param cachedNbt 该柱缓存 NBT（可为 null）；重复入队保留先入 NBT
     *                  （预提交先入 = 内存版，比 TAIL 后到的磁盘读版更新鲜）
     */
    public void enqueue(ChunkPos pos, CompoundTag cachedNbt) {
        pending.putIfAbsent(pos.toLong(), new Entry(pos, cachedNbt));
    }

    /**
     * 帧尾消费（主线程；tick TAIL、渲染前调用）。
     * 预算内逐块处理，到点即停，剩余留帧。
     */
    public void drainFrame() {
        // 并行引擎路径不消费本队列（Promethium 引擎自带队列与帧预算落地）
        if (PromethiumLightBridge.isEnabled()) {
            return;
        }
        if (pending.isEmpty()) {
            return;
        }
        ClientLevel level = Minecraft.getInstance().level;
        if (level == null) {
            pending.clear();
            return;
        }
        long deadline = System.nanoTime() + FRAME_BUDGET_NS;
        int done = 0;
        var it = pending.entrySet().iterator();
        while (it.hasNext()) {
            if (System.nanoTime() > deadline) {
                break; // 预算到点：剩余留帧（下帧继续）
            }
            Entry e = it.next().getValue();
            LevelChunk chunk = level.getChunkSource().getChunkNow(e.pos.x, e.pos.z);
            if (chunk == null) {
                it.remove(); // 已卸载：丢弃（重连/缩窗场景，光由新包路径负责）
                continue;
            }
            ClientLightRecomputeService.applyLightEngine(level, chunk, e.pos);
            if (HassiumConfigService.getInstance().isLightCacheEnabled()) {
                // 重算后不立即写盘：此刻引擎光可能未收敛——加载风暴中传播域不完整时，
                // 海底 section 会被 sky 15 灌满（R1 写回即铁证），写回 = 落盘污染光，
                // R2 带光 apply 信任缓存光 → 「清澈见底」。只登记 dirty，等卸载/断连
                // dump 统一写引擎收敛光（用户方案：丢光可接受，R2 重算兜底）。
                ClientChunkDirtyTracker.markDirty(e.pos);
            }
            it.remove();
            done++;
        }
        if (done > 0 && BUDGET_DIAG_COUNT++ % 60 == 0) {
            Constants.LOG.info("[LIGHT-BUF] frame done={} remaining={}",
                    done, pending.size());
        }
    }

    /** 断连清理（主线程）：清空全部缓冲，未消费任务直接丢弃（重连后重新提交）。 */
    public void clear() {
        pending.clear();
    }

    /** 队列条目（nbt 仅消费时使用；enqueue 后不再变更）。 */
    private static final class Entry {
        final ChunkPos pos;
        final CompoundTag nbt;

        Entry(ChunkPos pos, CompoundTag nbt) {
            this.pos = pos;
            this.nbt = nbt;
        }
    }
}
