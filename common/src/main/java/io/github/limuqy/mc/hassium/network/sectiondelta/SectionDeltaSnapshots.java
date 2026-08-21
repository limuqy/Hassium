package io.github.limuqy.mc.hassium.network.sectiondelta;

import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunk;

/**
 * 影子端平面综合征 memo。服务端不缓存；活体更新走 {@link #invalidate} 失效重算。
 */
public final class SectionDeltaSnapshots {

    private static final ConcurrentHashMap<Long, SectionDeltaSnapshot> MEMO = new ConcurrentHashMap<>();

    private SectionDeltaSnapshots() {}

    public static SectionDeltaSnapshot getOrCapture(ChunkPos pos, LevelChunk chunk) {
        long key = ChunkPos.asLong(pos.x, pos.z);
        SectionDeltaSnapshot existing = MEMO.get(key);
        if (existing != null) {
            return existing;
        }
        SectionDeltaSnapshot captured = SectionDeltaSnapshot.capture(chunk);
        SectionDeltaSnapshot raced = MEMO.putIfAbsent(key, captured);
        return raced != null ? raced : captured;
    }

    public static void put(ChunkPos pos, SectionDeltaSnapshot snapshot) {
        if (pos == null || snapshot == null) {
            return;
        }
        MEMO.put(ChunkPos.asLong(pos.x, pos.z), snapshot);
    }

    public static void invalidate(ChunkPos pos) {
        if (pos != null) {
            MEMO.remove(ChunkPos.asLong(pos.x, pos.z));
        }
    }

    public static void invalidate(long chunkKey) {
        MEMO.remove(chunkKey);
    }

    public static void clear() {
        MEMO.clear();
    }
}
