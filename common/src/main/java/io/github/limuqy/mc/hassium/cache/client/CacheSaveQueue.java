package io.github.limuqy.mc.hassium.cache.client;

import io.github.limuqy.mc.hassium.Constants;
import io.github.limuqy.mc.hassium.cache.ChunkContentHashUtil;
import io.github.limuqy.mc.hassium.concurrent.HassiumTaskExecutor;
import io.github.limuqy.mc.hassium.concurrent.TaskCategory;
import io.github.limuqy.mc.hassium.mixin.ClientLevelAccessor;
import io.github.limuqy.mc.hassium.network.ClientChunkHandler;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientChunkCache;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.ChunkPos;
#if MC_VER < MC_1_20_5
import net.minecraft.world.level.chunk.ChunkStatus;
#else
import net.minecraft.world.level.chunk.status.ChunkStatus;
#endif
import net.minecraft.world.level.chunk.LevelChunk;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 异步缓存保存队列
 * <p>
 * 全量推送经 {@link #enqueueSerialized} 异步入库；Live-Unload / 断连仅处理脏块。
 * 后台单消费者顺序 persist，避免 region 文件并发写。
 */
public class CacheSaveQueue {

    private static final CacheSaveQueue INSTANCE = new CacheSaveQueue();

    /** 毒丸：唤醒 {@code take()}，避免 interrupt 打断正在进行的 NIO 写盘。 */
    private static final SaveTask POISON = new SaveTask(
            new ChunkPos(Integer.MAX_VALUE, Integer.MAX_VALUE),
            Integer.MAX_VALUE, Integer.MAX_VALUE,
            new byte[0], 0L, null, false);

    private final LinkedBlockingQueue<SaveTask> taskQueue = new LinkedBlockingQueue<>();

    private volatile Thread saveThread;

    private final AtomicBoolean initialized = new AtomicBoolean(false);
    private final AtomicBoolean stopping = new AtomicBoolean(false);
    /** 当前是否正在 persist（队列已空仍可能 in-flight）。 */
    private final AtomicBoolean inflight = new AtomicBoolean(false);
    private volatile ClientLevel trackedLevel;

    /**
     * {@code serializedData} 为 {@link ChunkDiskCodec#nbtToBytes} 产出的 NBT 字节（含 magic）。
     */
    public record SaveTask(
            ChunkPos pos,
            int chunkX,
            int chunkZ,
            byte[] serializedData,
            long contentHash,
            long[] sectionHashes,
            boolean fromLiveUnload
    ) {}

    private CacheSaveQueue() {}

    public static CacheSaveQueue getInstance() {
        return INSTANCE;
    }

    private void ensureInitialized() {
        if (initialized.compareAndSet(false, true)) {
            stopping.set(false);
            saveThread = new Thread(this::processLoop, "Hassium-Cache-Saver");
            saveThread.setDaemon(true);
            saveThread.start();
            Constants.LOG.info("Hassium: CacheSaveQueue initialized");
        }
    }

    public void enqueue(LevelChunk chunk) {
        enqueue(chunk, false);
    }

    /**
     * @param skipIfUnchanged 保留参数（断连安全网）；方块 hash 一致时走光照补丁而非跳过
     */
    public void enqueue(LevelChunk chunk, boolean skipIfUnchanged) {
        if (chunk == null) return;
        // PalettedContainer / LightEngine 受 ThreadingDetector 保护：必须在主线程序列化
        Minecraft mc = Minecraft.getInstance();
        if (mc != null && !mc.isSameThread()) {
            LevelChunk chunkRef = chunk;
            boolean skip = skipIfUnchanged;
            mc.execute(() -> enqueue(chunkRef, skip));
            return;
        }
        ChunkPos pos = chunk.getPos();
        Constants.LOG.debug("Hassium: [CACHE SAVE] enqueue called for chunk {} (skipIfUnchanged={})",
                pos, skipIfUnchanged);

        if (ViewDistanceExtensionService.getInstance().isRenderOnly(pos)) {
            Constants.LOG.debug("Hassium: [CACHE SAVE] Skip unload for render-only chunk {}", pos);
            return;
        }

        if (!ClientChunkDirtyTracker.isDirty(pos)) {
            Constants.LOG.debug("Hassium: [CACHE SAVE] Skip clean chunk {}", pos);
            return;
        }

        ClientLevel level = (ClientLevel) chunk.getLevel();
        if (level == null) {
            level = trackedLevel;
        }
        if (level == null) {
            Constants.LOG.debug("Hassium: [CACHE SAVE] Skip chunk {} (level null)", pos);
            return;
        }

        ensureInitialized();

        long[] sectionHashes;
        long contentHash;
        try {
            Map<Integer, Long> hashesMap = ChunkContentHashUtil.computeSectionHashes(chunk);
            sectionHashes = ChunkContentHashUtil.sectionHashesToArray(hashesMap);
            contentHash = ChunkContentHashUtil.combineSectionHashesFromArray(sectionHashes);
        } catch (Throwable t) {
            Constants.LOG.debug("Hassium: [CACHE SAVE] Failed to compute section hashes for {}", pos, t);
            sectionHashes = new long[0];
            contentHash = 0L;
        }

        ClientHassiumStorage storage = ClientChunkHandler.getClientStorage();
        long diskHash = storage != null ? storage.readChunkHash(pos) : 0L;

        // 方块未变（hash 一致）：只补光照，严禁用 LevelChunk 重算覆盖 MetadataTable。
        // 否则 Live-Unload 往返序列化会写出「不等于服务端」的 hash → 次回进服大批 MISMATCH，
        // 区块/光照命中率一起崩。
        if (storage != null && contentHash != 0L && diskHash != 0L && diskHash != 1L
                && diskHash == contentHash) {
            long[] diskSectionHashes = storage.readSectionHashes(pos);
            if (diskSectionHashes == null || diskSectionHashes.length == 0) {
                diskSectionHashes = sectionHashes;
            }
            CompoundTag nbt = null;
            byte[] diskBytes = storage.loadAndDecompress(pos);
            if (diskBytes != null) {
                nbt = ChunkDiskCodec.bytesToNbt(diskBytes);
            }
            if (nbt != null) {
                ChunkDiskCodec.copyLightEngineToNbt(nbt, pos, level);
            } else {
                nbt = ChunkDiskCodec.levelChunkToNbt(chunk, level);
            }
            if (nbt == null) {
                Constants.LOG.warn("Hassium: [CACHE SAVE] Light patch failed for {}, skipping", pos);
                return;
            }
            byte[] nbtBytes = ChunkDiskCodec.nbtToBytes(nbt);
            if (nbtBytes == null) {
                return;
            }
            taskQueue.offer(new SaveTask(pos, pos.x, pos.z, nbtBytes, diskHash, diskSectionHashes, true));
            Constants.LOG.debug("Hassium: [CACHE SAVE QUEUED] light-patch {} (hash={}, queue: {})",
                    pos, Long.toHexString(diskHash), taskQueue.size());
            return;
        }

        CompoundTag nbt = ChunkDiskCodec.levelChunkToNbt(chunk, level);
        if (nbt == null) {
            Constants.LOG.warn("Hassium: [CACHE SAVE] Failed to serialize chunk {} to NBT, skipping", pos);
            return;
        }
        byte[] nbtBytes = ChunkDiskCodec.nbtToBytes(nbt);
        if (nbtBytes == null) {
            Constants.LOG.warn("Hassium: [CACHE SAVE] Failed to encode NBT bytes for chunk {}, skipping", pos);
            return;
        }

        taskQueue.offer(new SaveTask(pos, pos.x, pos.z, nbtBytes, contentHash, sectionHashes, true));
        Constants.LOG.debug("Hassium: [CACHE SAVE QUEUED] chunk {} ({} NBT bytes, hash={}, queue: {})",
                pos, nbtBytes.length, Long.toHexString(contentHash), taskQueue.size());
    }

    /**
     * 全量推送异步入库：调用方已完成 NBT 序列化。
     */
    public void enqueueSerialized(ChunkPos pos, byte[] nbtBytes, long contentHash, long[] sectionHashes) {
        if (pos == null || nbtBytes == null) {
            return;
        }
        ensureInitialized();
        taskQueue.offer(new SaveTask(pos, pos.x, pos.z, nbtBytes, contentHash, sectionHashes, false));
        Constants.LOG.debug("Hassium: [CACHE SAVE] Enqueued serialized {} ({} bytes, hash={}, queue={})",
                pos, nbtBytes.length, Long.toHexString(contentHash), taskQueue.size());
    }

    /**
     * 断连安全网：只处理脏的已加载区块。
     */
    public void enqueueAllFromLevel(ClientLevel level) {
        if (level == null) {
            return;
        }
        int count = 0;
        int skippedClean = 0;
        try {
            ClientChunkCache cache = ((ClientLevelAccessor) level).hassium$getChunkSource();
            int radius = 33;
            for (int cx = -radius; cx <= radius; cx++) {
                for (int cz = -radius; cz <= radius; cz++) {
                    try {
                        LevelChunk chunk = (LevelChunk) cache.getChunk(cx, cz, ChunkStatus.FULL, false);
                        if (chunk == null) {
                            continue;
                        }
                        if (!ClientChunkDirtyTracker.isDirty(chunk.getPos())) {
                            skippedClean++;
                            continue;
                        }
                        int before = taskQueue.size();
                        enqueue(chunk, true);
                        if (taskQueue.size() > before) {
                            count++;
                        } else {
                            skippedClean++;
                        }
                    } catch (Exception ignored) {
                    }
                }
            }
        } catch (Exception e) {
            Constants.LOG.warn("Hassium: [CACHE SAVE] Failed to iterate loaded chunks", e);
        }
        if (count > 0 || skippedClean > 0) {
            Constants.LOG.info("Hassium: [CACHE SAVE] Disconnect dump: queued={}, skippedClean={}, dirtyLeft={}",
                    count, skippedClean, ClientChunkDirtyTracker.size());
        } else {
            Constants.LOG.info("Hassium: [CACHE SAVE] No loaded chunks found to enqueue before disconnect");
        }
    }

    private void processLoop() {
        while (!stopping.get()) {
            try {
                SaveTask task = taskQueue.take();
                if (task == POISON || stopping.get()) {
                    break;
                }
                inflight.set(true);
                try {
                    // 清除可能残留的 interrupt，避免 NIO write 抛 ClosedByInterruptException
                    Thread.interrupted();
                    processTask(task);
                } finally {
                    inflight.set(false);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                inflight.set(false);
                Constants.LOG.error("Hassium: [CACHE SAVE] Error in save loop", e);
            }
        }
        Constants.LOG.debug("Hassium: CacheSaveQueue process loop exiting");
    }

    private void processTask(SaveTask task) {
        if (stopping.get()) return;
        try {
            ClientHassiumStorage storage = ClientChunkHandler.getClientStorage();
            if (storage == null) {
                Constants.LOG.debug("Hassium: [CACHE SAVE] Storage not initialized, skipping chunk {}", task.pos());
                return;
            }

            boolean saved = storage.persist(task.pos(), task.serializedData(), task.contentHash(), task.sectionHashes());
            if (saved) {
                Constants.LOG.debug("Hassium: [CACHE SAVE] Saved chunk {} ({} bytes, hash={}, live={})",
                        task.pos(), task.serializedData().length, Long.toHexString(task.contentHash()),
                        task.fromLiveUnload());
                if (task.fromLiveUnload()) {
                    ClientChunkDirtyTracker.clear(task.pos());
                } else if (ChunkDiskCodec.isLightOn(ChunkDiskCodec.bytesToNbt(task.serializedData()))) {
                    ClientChunkDirtyTracker.clear(task.pos());
                }
                // 全量入库且 is_light_on=0：保持 dirty，等光照回写 clear
            } else {
                Constants.LOG.warn("Hassium: [CACHE SAVE] Failed to persist chunk {}", task.pos());
            }
        } catch (Exception e) {
            Constants.LOG.error("Hassium: [CACHE SAVE] Error saving chunk {}", task.pos(), e);
        }
    }

    public int getQueueSize() {
        return taskQueue.size();
    }

    private boolean isIdle() {
        return taskQueue.isEmpty() && !inflight.get();
    }

    public void flushAsync(long timeoutMs) {
        if (isIdle()) {
            Constants.LOG.debug("Hassium: [CACHE SAVE FLUSH] No pending tasks");
            stopSaveThread();
            return;
        }

        int pending = taskQueue.size() + (inflight.get() ? 1 : 0);
        Constants.LOG.debug("Hassium: [CACHE SAVE FLUSH] Waiting for ~{} tasks (timeout={}ms)",
                pending, timeoutMs);

        long deadline = System.currentTimeMillis() + timeoutMs;
        while (!isIdle() && System.currentTimeMillis() < deadline) {
            try {
                Thread.sleep(20);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }

        if (!isIdle()) {
            int dropped = taskQueue.size();
            Constants.LOG.warn("Hassium: [CACHE SAVE FLUSH] Timeout after {}ms, dropping {} queued (inflight={})",
                    timeoutMs, dropped, inflight.get());
        }

        stopSaveThread();
        taskQueue.clear();
        stopping.set(false);
    }

    public void drainRemaining(long timeoutMs) {
        List<Runnable> tasks = new ArrayList<>();
        SaveTask task;
        while ((task = taskQueue.poll()) != null) {
            final SaveTask t = task;
            tasks.add(() -> processTask(t));
        }
        if (tasks.isEmpty()) {
            return;
        }

        Constants.LOG.info("Hassium: [CACHE SAVE] Final drain - {} tasks (timeout={}ms)", tasks.size(), timeoutMs);

        HassiumTaskExecutor executor = HassiumTaskExecutor.getClient();
        if (executor != null && executor.isRunning()) {
            List<Future<?>> futures = new ArrayList<>();
            for (Runnable r : tasks) {
                futures.add(executor.submit(() -> {
                    r.run();
                    return null;
                }, TaskCategory.MISSION_CRITICAL));
            }
            long deadline = System.currentTimeMillis() + timeoutMs;
            int completed = 0;
            for (Future<?> future : futures) {
                long remaining = deadline - System.currentTimeMillis();
                if (remaining <= 0) {
                    Constants.LOG.warn("Hassium: [CACHE SAVE] Final drain timed out, {} tasks lost",
                            futures.size() - completed);
                    break;
                }
                try {
                    future.get(remaining, TimeUnit.MILLISECONDS);
                    completed++;
                } catch (TimeoutException e) {
                    break;
                } catch (Exception e) {
                    Constants.LOG.error("Hassium: [CACHE SAVE] Final drain task failed", e);
                    completed++;
                }
            }
            Constants.LOG.info("Hassium: [CACHE SAVE] Final drain complete: {}/{}", completed, tasks.size());
        } else {
            int completed = 0;
            for (Runnable r : tasks) {
                try {
                    r.run();
                    completed++;
                } catch (Exception e) {
                    Constants.LOG.error("Hassium: [CACHE SAVE] Final drain sync task failed", e);
                }
            }
            Constants.LOG.info("Hassium: [CACHE SAVE] Final drain sync complete: {}/{}", completed, tasks.size());
        }
    }

    public void flushAsync() {
        flushAsync(3000);
    }

    public void flush() {
        flushAsync(3000);
    }

    /**
     * 停止后台保存线程：毒丸唤醒 take()，等 in-flight persist 结束；仅超时才 interrupt。
     */
    private void stopSaveThread() {
        Thread thread = saveThread;
        if (thread != null && thread.isAlive()) {
            stopping.set(true);
            taskQueue.offer(POISON);
            try {
                // 给正在写盘的任务收尾时间（勿先 interrupt，否则 ClosedByInterruptException）
                thread.join(2000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            if (thread.isAlive()) {
                thread.interrupt();
                try {
                    thread.join(200);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            saveThread = null;
        }
        initialized.set(false);
        inflight.set(false);
    }

    public void clear() {
        stopSaveThread();
        taskQueue.clear();
        stopping.set(false);
        Constants.LOG.debug("Hassium: Cleared cache save queue");
    }

    public void shutdown() {
        stopSaveThread();
        taskQueue.clear();
        stopping.set(false);
        trackedLevel = null;
    }

    public void trackLevel(ClientLevel level) {
        if (level != null) {
            trackedLevel = level;
        }
    }

    public ClientLevel getTrackedLevel() {
        return trackedLevel;
    }

    public void clearTrackedLevel() {
        trackedLevel = null;
    }
}
