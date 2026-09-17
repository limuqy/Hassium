package io.github.limuqy.mc.hassium.shadow.track;

import java.util.concurrent.CompletableFuture;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunk;

/**
 * 影子专用服区块来源（S0：始终异步 + 始终 compare/FULL）。
 * <p>
 * {@code scheduleChunkLoad} 不得因「有盘/有注入」同步 Imposter 短路；
 * 原版读盘本就是 IOWorker future。盘仅作 materialize/比对基线。
 */
public interface ShadowChunkProvider {

    CompletableFuture<LevelChunk> acquire(String dimension, ChunkPos pos, AcquireReason reason);

    boolean hasInFlight(String dimension, ChunkPos pos);

    enum AcquireReason {
        TRACKING,
        RETRY,
        RELIGHT
    }
}
