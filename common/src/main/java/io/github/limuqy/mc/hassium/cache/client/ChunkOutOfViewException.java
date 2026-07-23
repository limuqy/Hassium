package io.github.limuqy.mc.hassium.cache.client;

import net.minecraft.world.level.ChunkPos;

/**
 * ClientChunkCache 在 storage.inRange 外静默丢弃 replaceWithPacketData 时抛出。
 * <p>
 * 常见于异步解压 apply / 主线程预算排队 / server 缩视距与 client 扩半径之间的窗口——
 * 属于预期竞态，不是数据损坏。调用方应降级日志（debug/warn，无堆栈），
 * 并对 renderOnly 走 onRenderOnlyMiss 重试。
 */
public class ChunkOutOfViewException extends RuntimeException {

    private final ChunkPos pos;

    public ChunkOutOfViewException(ChunkPos pos) {
        super("Chunk apply ignored by ClientChunkCache (out of view range): " + pos);
        this.pos = pos;
    }

    public ChunkPos getPos() {
        return pos;
    }

    /**
     * 预期竞态，无需堆栈填充。
     */
    @Override
    public synchronized Throwable fillInStackTrace() {
        return this;
    }
}
