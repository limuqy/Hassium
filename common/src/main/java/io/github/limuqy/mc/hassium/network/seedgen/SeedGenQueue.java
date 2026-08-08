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
 */
public final class SeedGenQueue {

    /** 队列入队后超过该时长仍未出队 → 超时，回退全量请求（服务端直推兜底）。 */
    public static final long FALLBACK_TIMEOUT_MS = 8_000L;

    /** 入队条目（不可变快照）。 */
    public record Entry(ChunkPos pos, long contentHash, long[] sectionHashes, long enqueueTimeMs) {}

    private final Map<Long, Entry> pending = new ConcurrentHashMap<>();

    /** 入队。返回 true = 新条目；false = 已存在（hash 覆盖更新）。 */
    public boolean enqueue(ChunkPos pos, long contentHash, long[] sectionHashes) {
        Entry entry = new Entry(pos, contentHash, sectionHashes, System.currentTimeMillis());
        return pending.put(ChunkPos.asLong(pos.x, pos.z), entry) == null;
    }

    /** 取距玩家（区块坐标，曼哈顿距离）最近的未超时条目；队列空或全部超时返回 null。不移除。 */
    public Entry peekNearest(int playerChunkX, int playerChunkZ) {
        Entry best = null;
        int bestDist = Integer.MAX_VALUE;
        for (Entry e : pending.values()) {
            if (System.currentTimeMillis() - e.enqueueTimeMs() > FALLBACK_TIMEOUT_MS) {
                continue; // 超时条目由 expire() 统一回收
            }
            int dist = Math.abs(e.pos().x - playerChunkX) + Math.abs(e.pos().z - playerChunkZ);
            if (dist < bestDist) {
                bestDist = dist;
                best = e;
            }
        }
        return best;
    }

    /** 回收并返回全部超时条目（调用方负责回退全量请求）。 */
    public List<Entry> expire() {
        List<Entry> expired = new ArrayList<>();
        long now = System.currentTimeMillis();
        pending.entrySet().removeIf(e -> {
            if (now - e.getValue().enqueueTimeMs() > FALLBACK_TIMEOUT_MS) {
                expired.add(e.getValue());
                return true;
            }
            return false;
        });
        return expired;
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
