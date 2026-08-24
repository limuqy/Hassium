package io.github.limuqy.mc.hassium.network.seedgen;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.world.level.ChunkPos;

/**
 * SeedGen 待生成队列：按距玩家距离优先，同区块去重（新 SeedRef 覆盖旧 hash），超时回退。
 * <p>
 * 线程安全（ConcurrentHashMap + 不可变 Entry）。纯逻辑无 Minecraft 依赖之外的单测友好结构。
 * <p>
 * 超时语义（P1 修复）：回退超时 = 30s 基数 + 队列深度自适应。原 8s 固定值与服务端推送吞吐
 * （maxChunksPerTick=5 → 满刻 100/s）不匹配：1784 块全量尾部必然 >8s，到期风暴 + 客户端
 * 8s 级联重发 → 过期 1593（FINDINGS P1）。工作队列被 {@link SeedGenExecutor} 节流至
 * {@code MAX_WORK_DEPTH=96} 槽，串行生成实测 p90≈182ms/块 → 最坏在队等待 ≈17.5s，
 * 30s 基数提供 ≥1.7x 余量；深度因子兜底队列失控场景。
 */
public final class SeedGenQueue {

    /**
     * 回退超时基数：入队（进入有界工作队列）后超过该时长仍未生成 → 回退全量请求（服务端直推兜底）。
     */
    public static final long FALLBACK_TIMEOUT_BASE_MS = 30_000L;

    /** 深度自适应系数：每在队 1 块追加 25ms 等待预算（保守 40/s 服务率，实际满刻 ≈ 100/s）。 */
    public static final long FALLBACK_TIMEOUT_PER_ENTRY_MS = 25L;

    /** 深度自适应上限（最坏超时 = BASE + MAX_EXTRA ≈ 90s）。 */
    public static final long FALLBACK_TIMEOUT_MAX_EXTRA_MS = 60_000L;

    /**
     * 活体 SeedRef（deliveryId>0）回退硬截止：必须早于服务端 admission 投递预算
     * （ServerChunkPushManager.DELIVERY_TIMEOUT_NANOS = 8s）到期，携带原 deliveryId 的
     * 回退全量请求才能命中服务端 reservation 释放窗口（enqueueDataRequest 的
     * release+re-admit 原子转换）。晚于 8s 到达时服务端已 expire/requeue，旧 id 变
     * unknown → 预约槽泄漏 → inFlight 窗口被 SeedRef 重发循环钉死，full push 停摆
     * （T2-fabric-r1 第三层：R1 landed 崩至 175-231 实证）。留 1.5s 余量覆盖网关往返。
     */
    public static final long LIVE_SEEDREF_FALLBACK_DEADLINE_MS = 6_500L;

    /**
     * 按当前队列深度计算回退超时：深度越大尾部块等待越久，超时随之放大，
     * 避免深队尾块在正常生成进度下误回退（旧 8s 在 1784 深队下必然误触发风暴）。
     */
    public static long fallbackTimeoutMs(int queueDepth) {
        return FALLBACK_TIMEOUT_BASE_MS + Math.min(FALLBACK_TIMEOUT_MAX_EXTRA_MS,
                (long) queueDepth * FALLBACK_TIMEOUT_PER_ENTRY_MS);
    }

    /** 测试时钟覆盖（<0 = 用 System.currentTimeMillis() 正常路径）。包内可见，仅测试注入用。 */
    static volatile long clockOverrideMs = -1L;

    private static long nowMs() {
        long override = clockOverrideMs;
        return override >= 0L ? override : System.currentTimeMillis();
    }

    /**
     * 条目回退截止：活体 SeedRef（deliveryId>0）用硬截止（对齐服务端 8s 投递预算），
     * 其余（盲预生成等）用深度自适应公式。
     */
    static long entryDeadlineMs(Entry entry, int queueDepth) {
        return entry.deliveryId() > 0L
                ? LIVE_SEEDREF_FALLBACK_DEADLINE_MS
                : fallbackTimeoutMs(queueDepth);
    }

    /** 该条目是否已过回退截止。 */
    static boolean isExpired(Entry entry, long nowMs, int queueDepth) {
        return entry.contentHash() != 0L && nowMs - entry.enqueueTimeMs() > entryDeadlineMs(entry, queueDepth);
    }


    /** 入队条目（不可变快照）。 */
    public record Entry(ChunkPos pos, long contentHash, long[] sectionHashes, long deliveryId, long enqueueTimeMs) {}

    private final Map<Long, Entry> pending = new ConcurrentHashMap<>();

    /** 兼容盲预生成与不需 ACK 的旧调用。 */
    public boolean enqueue(ChunkPos pos, long contentHash, long[] sectionHashes) {
        return enqueue(pos, contentHash, sectionHashes, 0L);
    }

    /** 入队。正 deliveryId 必须随同本次 SeedRef 生成直到 authoritative apply。 */
    public boolean enqueue(ChunkPos pos, long contentHash, long[] sectionHashes, long deliveryId) {
        if (deliveryId < 0L) {
            throw new IllegalArgumentException("deliveryId must be non-negative");
        }
        Entry entry = new Entry(pos, contentHash, sectionHashes, deliveryId, nowMs());
        long key = ChunkPos.asLong(pos.x, pos.z);
        if (contentHash == 0L) {
            return pending.putIfAbsent(key, entry) == null;
        }
        return pending.put(key, entry) == null;
    }

    /** 取距玩家（区块坐标，曼哈顿距离）最近的未超时条目；队列空或全部超时返回 null。不移除。
     *  同距时优先 SeedRef 条目（contentHash≠0）——盲预生成条目（hash=0）不挤占校验条目，
     *  防 SeedRef 深队超时回退（预生成 441 块 × 25ms 深度因子会拖垮 SeedRef 的 30s 预算）。 */
    public Entry peekNearest(int playerChunkX, int playerChunkZ) {
        Entry best = null;
        int bestDist = Integer.MAX_VALUE;
        for (Entry e : pending.values()) {
            if (isExpired(e, nowMs(), pending.size())) {
                continue; // 超时条目由 expire() 统一回收
            }
            int dist = Math.abs(e.pos().x - playerChunkX) + Math.abs(e.pos().z - playerChunkZ);
            if (best == null || dist < bestDist
                    || (dist == bestDist && e.contentHash() != 0L && best.contentHash() == 0L)) {
                bestDist = dist;
                best = e;
            }
        }
        return best;
    }

    /** 回收并返回全部超时条目（调用方负责回退全量请求）。盲预生成条目（contentHash==0）永不超时。 */
    public List<Entry> expire() {
        List<Entry> expired = new ArrayList<>();
        long now = nowMs();
        int depth = pending.size();
        pending.entrySet().removeIf(e -> {
            Entry entry = e.getValue();
            if (isExpired(entry, now, depth)) {
                expired.add(entry);
                return true;
            }
            return false;
        });
        return expired;
    }

    /** 原子取出指定不可变快照；同坐标新 SeedRef 已替换时旧 worker 必须让位。 */
    public boolean tryTake(Entry entry) {
        return entry != null && pending.remove(ChunkPos.asLong(entry.pos().x, entry.pos().z), entry);
    }

    /** 原子取出（仅当条目仍 pending）：多 worker 并行生成时防重复接管同一条目。
     *  返回 true = 本 worker 取得所有权；false = 已被其他 worker 取走/超时回收。 */
    public boolean tryTake(ChunkPos pos) {
        return pending.remove(ChunkPos.asLong(pos.x, pos.z)) != null;
    }

    /** 生成完成后移除。 */
    public void remove(ChunkPos pos) {
        pending.remove(ChunkPos.asLong(pos.x, pos.z));
    }

    /** 该区块是否仍在队列（含超时未回收）。 */
    public boolean isPending(ChunkPos pos) {
        return pending.containsKey(ChunkPos.asLong(pos.x, pos.z));
    }

    public int size() {
        return pending.size();
    }

    public boolean isEmpty() {
        return pending.isEmpty();
    }

    /** 清空（断连）。 */
    public void clear() {
        pending.clear();
    }
}
