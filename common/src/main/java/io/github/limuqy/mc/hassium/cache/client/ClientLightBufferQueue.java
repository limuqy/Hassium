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
 * 预提交）从 apply 链路剥离：帧内收集、帧尾消费，收集与消费解耦。消费模式由
 * {@code clientCache.lightSyncMode} 决定：
 * <ul>
 *   <li>异步模式（{@code lightSyncMode=false}）：单缓冲，帧尾按预算消费（{@link #FRAME_BUDGET_NS}，到点即停），
 *       剩余自然留帧消化——加载风暴下黑块随重算落地逐帧消减。</li>
 *   <li>同步模式（默认，{@code lightSyncMode=true}）：双帧缓冲——本帧 chunk apply（已限流：
 *       maxChunksPerFrame + 时间预算）产生的无光照任务入「当前」缓冲，帧尾交换缓冲并对
 *       上一帧收集的队列<b>预算内消费</b>（{@link #SYNC_FRAME_BUDGET_NS} 封顶，剩余任务
 *       放回下一帧，不丢）；每块光照在 apply 后 1-3 帧内落地（黑块窗口 ≤2-3 帧），
 *       单帧只承担「apply」或「光照落地」之一类工作且落地量受帧预算约束——加载风暴期
 *       帧率不再被帧尾全量消费击穿。</li>
 * </ul>
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
     * 每帧消费预算：官方重算 ~1-3ms/块，5ms ≈ 2-3 块/帧；加载风暴（maxChunksPerFrame=6）
     * 下自然积压、多帧消化——「光照只占部分主线程预算」的落点，帧时间不会被单帧峰值
     * 击穿。预算耗尽剩余留帧，无阻塞。
     */
    private static final long FRAME_BUDGET_NS = 5_000_000L;

    /**
     * 同步模式每帧消费预算：官方重算 ~2.75-4.3ms/块，12ms ≈ 4-5 块/帧。加载风暴期
     * 帧尾全量消费会把主线程打满（R1 实测单核 100%、帧率 ~16fps）——预算化后每帧光照
     * 成本封顶，帧率恢复；剩余任务放回当前缓冲（不丢），黑块窗口由 ≤1 帧放宽到 ≤2-3 帧。
     */
    private static final long SYNC_FRAME_BUDGET_NS = 12_000_000L;

    private static final ClientLightBufferQueue INSTANCE = new ClientLightBufferQueue();

    /** 待消费任务（异步模式；任意线程入队，主线程帧尾消费）。 */
    private final Map<Long, Entry> pending = new ConcurrentHashMap<>();

    /** 同步模式「当前帧」收集缓冲（本帧 apply 入队；帧尾交换后成为下帧消费对象）。volatile：入队线程跨线程读。 */
    private volatile Map<Long, Entry> current = new ConcurrentHashMap<>();

    /** 同步模式「上一帧」收集缓冲（帧尾交换后预算内消费，剩余放回当前缓冲）。主线程独占写。 */
    private Map<Long, Entry> previous = new ConcurrentHashMap<>();

    /** 帧尾预算耗尽诊断限频计数（仅主线程访问）。 */
    private static int BUDGET_DIAG_COUNT = 0;

    public static ClientLightBufferQueue getInstance() {
        return INSTANCE;
    }

    /**
     * 全部缓冲（异步 pending / 同步双帧缓冲）是否已排空。
     * <p>
     * settle 写回判定用：只有光照队列排空（所有已入队重算都消费完）才认为
     * 引擎光照处于收敛态，允许把 dirty 块 light-patch 落盘。
     */
    public boolean isEmpty() {
        return pending.isEmpty() && current.isEmpty() && previous.isEmpty();
    }

    private ClientLightBufferQueue() {}

    /** 同步模式开关（配置 {@code clientCache.lightSyncMode}；与并行引擎同开时本项优先）。 */
    public static boolean isSyncMode() {
        return HassiumConfigService.getInstance().isLightSyncMode();
    }

    /**
     * 入队一个光照重算任务（任意线程）。
     *
     * @param cachedNbt 该柱缓存 NBT（可为 null）；重复入队保留先入 NBT
     *                  （预提交先入 = 内存版，比 TAIL 后到的磁盘读版更新鲜）
     */
    public void enqueue(ChunkPos pos, CompoundTag cachedNbt) {
        (isSyncMode() ? current : pending).putIfAbsent(pos.toLong(), new Entry(pos, cachedNbt));
    }

    /**
     * 帧尾消费（主线程；tick TAIL、渲染前调用）。
     * 异步模式：预算内逐块处理，到点即停，剩余留帧。
     * 同步模式：交换双帧缓冲，上一帧收集的队列预算内消费（SYNC_FRAME_BUDGET_NS 封顶，
     * 剩余任务放回当前缓冲，不丢）。
     */
    public void drainFrame() {
        if (isSyncMode()) {
            drainFrameSync();
            return;
        }
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
            it.remove();
            if (consume(level, e)) {
                done++;
            }
        }
        logBudgetConsumed(done, pending.size());
    }

    /**
     * 同步模式帧尾：交换缓冲（当前帧收集 → 下帧消费对象），对上一帧收集的队列
     * <b>阻塞全量消费</b>——chunk apply 已限流（maxChunksPerFrame + 时间预算），
     * 本帧落地量受其约束；每块光照在 apply 后至多 1 帧内落地（黑块窗口 ≤1 帧）。
     * <p>
     * 交换语义：入队线程与交换并发时最多晚一帧被消费（写入被换出缓冲 = 下一帧尾消费），
     * 不丢失；交换完成后写入旧缓冲的极端竞争（读旧引用后线程被挂起 ≥1 帧）会丢失该任务，
     * 由该块下次数据到达时重新入队自愈。
     */
    private void drainFrameSync() {
        ClientLevel level = Minecraft.getInstance().level;
        if (level == null) {
            clear();
            return;
        }
        Map<Long, Entry> toDrain = previous;
        previous = current;
        current = new ConcurrentHashMap<>();
        if (toDrain.isEmpty()) {
            return;
        }
        int done = 0;
        int leftover = 0;
        long deadline = System.nanoTime() + SYNC_FRAME_BUDGET_NS;
        for (Entry e : toDrain.values()) {
            if (System.nanoTime() > deadline) {
                // 预算到点：剩余任务放回当前缓冲（putIfAbsent 不覆盖本帧新入队任务），
                // 下一帧尾消费——不丢任务，黑块窗口由 ≤1 帧放宽到 ≤2-3 帧（加载风暴
                // 峰值帧可能再多 1-2 帧，消费速率 > 生产速率时队列自然排空）。
                current.putIfAbsent(e.pos.toLong(), e);
                leftover++;
                continue;
            }
            if (consume(level, e)) {
                done++;
            }
        }
        logBudgetConsumed(done, leftover);
    }

    /** 消费单条任务：chunk 已卸载则丢弃（重连/缩窗场景，光由新包路径负责）。 */
    private boolean consume(ClientLevel level, Entry e) {
        LevelChunk chunk = level.getChunkSource().getChunkNow(e.pos.x, e.pos.z);
        if (chunk == null) {
            return false;
        }
        ClientLightRecomputeService.applyLightEngine(level, chunk, e.pos);
        if (HassiumConfigService.getInstance().isLightCacheEnabled()) {
            // 重算后不立即写盘：此刻引擎光可能未收敛——加载风暴中传播域不完整时，
            // 海底 section 会被 sky 15 灌满（R1 写回即铁证），写回 = 落盘污染光，
            // R2 带光 apply 信任缓存光 → 「清澈见底」。只登记 dirty，等卸载/断连
            // dump 统一写引擎收敛光（用户方案：丢光可接受，R2 重算兜底）。
            ClientChunkDirtyTracker.markDirty(e.pos);
        }
        return true;
    }

    private static void logBudgetConsumed(int done, int remaining) {
        if (done > 0 && BUDGET_DIAG_COUNT++ % 60 == 0) {
            Constants.LOG.info("[LIGHT-BUF] frame done={} remaining={}",
                    done, remaining);
        }
    }

    /** 断连清理（主线程）：清空全部缓冲，未消费任务直接丢弃（重连后重新提交）。 */
    public void clear() {
        pending.clear();
        current.clear();
        previous.clear();
    }

    /**
     * 断连排空（主线程）：预算内全量消费 pending/current/previous 缓冲（含同步模式双帧
     * 缓冲），每块消费 = 引擎重算 + markDirty，随后由断连 dump 统一落盘。与
     * {@link #drainFrame} 的区别：无「剩余留帧」语义，一次性尽量消费；预算耗尽停止，
     * 残余放回当前缓冲（断连清理随后 {@link #clear()} 丢弃，重连后由数据包路径重新提交）。
     */
    public void drainAll(long maxTimeNs) {
        ClientLevel level = Minecraft.getInstance().level;
        if (level == null) {
            clear();
            return;
        }
        long deadline = System.nanoTime() + maxTimeNs;
        while (System.nanoTime() < deadline) {
            Map<Long, Entry> batch = new ConcurrentHashMap<>();
            batch.putAll(pending);
            pending.clear();
            batch.putAll(current);
            current.clear();
            batch.putAll(previous);
            previous.clear();
            if (batch.isEmpty()) {
                return;
            }
            for (Entry e : batch.values()) {
                if (System.nanoTime() > deadline) {
                    current.putIfAbsent(e.pos.toLong(), e);
                    continue;
                }
                consume(level, e);
            }
        }
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
