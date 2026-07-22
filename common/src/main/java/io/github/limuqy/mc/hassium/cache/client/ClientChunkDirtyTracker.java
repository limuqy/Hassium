package io.github.limuqy.mc.hassium.cache.client;

import net.minecraft.world.level.ChunkPos;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 客户端会话内脏块表：磁盘相对内存可能过期的区块。
 * <p>
 * 服务端推送全量 / SectionDelta / LightDelta 时标脏；成功落盘（含光照回写）后标净。
 * 干净块 Live-Unload / 断连 dump 可跳过，避免退出时大批量重快照。
 */
public final class ClientChunkDirtyTracker {

    private static final Set<Long> DIRTY = ConcurrentHashMap.newKeySet();

    private ClientChunkDirtyTracker() {}

    public static void markDirty(ChunkPos pos) {
        if (pos != null) {
            DIRTY.add(ChunkPos.asLong(pos.x, pos.z));
        }
    }

    public static void markDirty(int chunkX, int chunkZ) {
        DIRTY.add(ChunkPos.asLong(chunkX, chunkZ));
    }

    public static boolean isDirty(ChunkPos pos) {
        return pos != null && DIRTY.contains(ChunkPos.asLong(pos.x, pos.z));
    }

    public static boolean isDirty(int chunkX, int chunkZ) {
        return DIRTY.contains(ChunkPos.asLong(chunkX, chunkZ));
    }

    public static void clear(ChunkPos pos) {
        if (pos != null) {
            DIRTY.remove(ChunkPos.asLong(pos.x, pos.z));
        }
    }

    public static void clear(int chunkX, int chunkZ) {
        DIRTY.remove(ChunkPos.asLong(chunkX, chunkZ));
    }

    public static void clearAll() {
        DIRTY.clear();
    }

    public static int size() {
        return DIRTY.size();
    }
}
