package io.github.limuqy.mc.hassium.network.sectiondelta;

import io.github.limuqy.mc.hassium.utils.DimensionKey;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunk;

/**
 * 影子端平面综合征 memo。服务端不缓存；活体更新走 {@link #invalidate} 失效重算。
 * <p>
 * 键 = DimensionKey 复合键（维度+坐标）：nether/end 注入柱的快照不得与主世界同坐标
 * 互相覆盖。旧裸 pos 签名委托 OVERWORLD（过渡期兼容）。
 */
public final class SectionDeltaSnapshots {

    private static final ConcurrentHashMap<Long, SectionDeltaSnapshot> MEMO = new ConcurrentHashMap<>();

    private SectionDeltaSnapshots() {}

    public static SectionDeltaSnapshot getOrCapture(ChunkPos pos, LevelChunk chunk) {
        return getOrCapture(DimensionKey.OVERWORLD, pos, chunk);
    }

    /** 指定维度取用/捕获（requestSectionDeltas 调用；dimension 来自请求上下文）。 */
    public static SectionDeltaSnapshot getOrCapture(String dimension, ChunkPos pos, LevelChunk chunk) {
        long key = DimensionKey.key(dimension, pos.x, pos.z);
        SectionDeltaSnapshot existing = MEMO.get(key);
        if (existing != null) {
            return existing;
        }
        SectionDeltaSnapshot captured = SectionDeltaSnapshot.capture(chunk);
        SectionDeltaSnapshot raced = MEMO.putIfAbsent(key, captured);
        return raced != null ? raced : captured;
    }

    public static void put(ChunkPos pos, SectionDeltaSnapshot snapshot) {
        put(DimensionKey.OVERWORLD, pos, snapshot);
    }

    /** 指定维度登记（injectChunk / applySectionDelta / injectLoadedChunk 调用）。 */
    public static void put(String dimension, ChunkPos pos, SectionDeltaSnapshot snapshot) {
        if (pos == null || snapshot == null || dimension == null) {
            return;
        }
        MEMO.put(DimensionKey.key(dimension, pos.x, pos.z), snapshot);
    }

    public static void invalidate(ChunkPos pos) {
        if (pos != null) {
            MEMO.remove(DimensionKey.key(DimensionKey.OVERWORLD, pos.x, pos.z));
        }
    }

    /** 指定维度失效（applySectionDelta 失败路径 / 内容失效扫描调用）。 */
    public static void invalidate(String dimension, ChunkPos pos) {
        if (pos != null && dimension != null) {
            MEMO.remove(DimensionKey.key(dimension, pos.x, pos.z));
        }
    }

    public static void invalidate(long chunkKey) {
        MEMO.remove(chunkKey);
    }

    public static void clear() {
        MEMO.clear();
    }
}
