package io.github.limuqy.mc.hassium.shadow.track;

import io.github.limuqy.mc.hassium.shadow.server.ShadowSeedServer;
import io.github.limuqy.mc.hassium.shadow.server.ShadowServerRegistry;
import io.github.limuqy.mc.hassium.shadow.storage.ShadowStorageManager;

import io.github.limuqy.mc.hassium.utils.DebugLogger;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunk;

/**
 * S4：原版卸载事件 → type126 flush + 摘影子注入表。
 * <p>
 * 主路径：影子 {@code processUnloads} / reclaim。flushColumn 语义见
 * {@code ShadowStorageManager.flushColumn}（脏柱必须同步落盘成功才摘表）。
 */
public final class ShadowColumnStore {

    private ShadowColumnStore() {}

    public static boolean flushAndEvict(String dimension, ChunkPos pos, LevelChunk chunk) {
        if (dimension == null || pos == null || chunk == null) {
            return false;
        }
        ShadowSeedServer server = ShadowServerRegistry.getInstance().get();
        if (server == null) {
            return false;
        }
        boolean ok = server.unloadChunk(dimension, pos, chunk, false);
        if (ok) {
            VanillaAlignedChunkProvider.failAcquire(dimension, pos);
            DebugLogger.info(DebugLogger.LogType.NETWORK,
                    "[SHADOW_STORE] flushAndEvict ({}, {}) dim={}",
                    pos.x, pos.z, dimension);
        }
        return ok;
    }

    public static LevelChunk load(String dimension, ChunkPos pos) {
        ShadowSeedServer server = ShadowServerRegistry.getInstance().get();
        if (server == null || pos == null) {
            return null;
        }
        return server.loadFromDisk(dimension, pos);
    }
}
