package io.github.limuqy.mc.hassium.cache.client;

import io.github.limuqy.mc.hassium.Constants;
import io.github.limuqy.mc.hassium.cache.ChunkContentHashUtil;
import io.github.limuqy.mc.hassium.concurrent.HassiumTaskExecutor;
import io.github.limuqy.mc.hassium.concurrent.TaskCategory;
import io.github.limuqy.mc.hassium.network.ClientChunkHandler;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.ChunkPos;
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
 * 区块卸载时在主线程提交保存任务，后台线程执行序列化、压缩和持久化。
 * 避免在主线程执行耗时的 IO 操作导致卡顿。
 * <p>
 * <b>Live-Unload Snapshot 策略</b>（替代旧的「有权威 hash 则 skip 卸载」）：
 * 真实区块卸载时用 {@link LevelChunk} + {@link ChunkContentHashUtil#computeSectionHashes}
 * 重新计算 hash 与 NBT，覆盖磁盘上的推送快照。这保证「曾加载并收到更新」的块再进应 HIT。
 * renderOnly 区块卸载直接跳过（保留历史快照）。
 * <p>
 * 线程安全设计：
 * <ul>
 *   <li>enqueue() 在主线程调用，在此完成区块序列化（避免后台线程访问 Minecraft 对象）</li>
 *   <li>SaveTask 只保存 byte[] 快照，后台线程只处理字节数组</li>
 *   <li>后台线程通过 HassiumTaskExecutor 提交压缩+持久化任务</li>
 * </ul>
 */
public class CacheSaveQueue {

    private static final CacheSaveQueue INSTANCE = new CacheSaveQueue();

    /** 保存任务队列 */
    private final LinkedBlockingQueue<SaveTask> taskQueue = new LinkedBlockingQueue<>();

    /** 后台保存线程（专用 daemon 线程，用于消费队列） */
    private volatile Thread saveThread;

    /** 线程是否已启动 */
    private final AtomicBoolean initialized = new AtomicBoolean(false);

    /**
     * 保存任务：只持有 NBT 字节快照、contentHash 和 sectionHashes。
     * <p>
     * {@code serializedData} 语义为 {@link ChunkDiskCodec#nbtToBytes} 产出的 NBT 字节（含 magic 前缀）。
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

    /**
     * 初始化后台线程（懒加载，首次 enqueue 时触发）
     */
    private void ensureInitialized() {
        if (initialized.compareAndSet(false, true)) {
            saveThread = new Thread(this::processLoop, "Hassium-Cache-Saver");
            saveThread.setDaemon(true);
            saveThread.start();
            Constants.LOG.info("Hassium: CacheSaveQueue initialized");
        }
    }

    /**
     * 提交区块保存任务（主线程调用，Live-Unload 主路径）
     * <p>
     * 在主线程完成 LevelChunk → NBT 序列化与 sectionHashes 计算，后台只处理字节数组。
     * <p>
     * renderOnly 区块直接跳过（保留历史快照，避免覆盖推送时的权威数据）。
     * 已卸载/null 的 chunk 跳过。
     */
    public void enqueue(LevelChunk chunk) {
        if (chunk == null) return;
        ChunkPos pos = chunk.getPos();

        // renderOnly 区块卸载不写回缓存（OVD 历史快照保留）
        if (ViewDistanceExtensionService.getInstance().isRenderOnly(pos)) {
            Constants.LOG.debug("Hassium: [CACHE SAVE] Skip unload for render-only chunk {}", pos);
            return;
        }

        ClientLevel level = (ClientLevel) chunk.getLevel();
        if (level == null) {
            Constants.LOG.debug("Hassium: [CACHE SAVE] Skip chunk {} (level null)", pos);
            return;
        }

        ensureInitialized();

        // Live-Unload：从 LevelChunk 直接序列化为 NBT（不依赖 packet 路径）
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

        // sectionHashes 直接从 LevelChunk 计算（与服务端 ServerChunkPushManager 同算法）
        long[] sectionHashes;
        long contentHash;
        try {
            Map<Integer, Long> hashesMap = ChunkContentHashUtil.computeSectionHashes(chunk);
            sectionHashes = ChunkContentHashUtil.sectionHashesToArray(hashesMap);
            contentHash = ChunkContentHashUtil.combineSectionHashesFromArray(sectionHashes);
        } catch (Throwable t) {
            // hash 计算失败不阻断落盘（contentHash 用 0 表示无效，命中比对会回退全量）
            Constants.LOG.debug("Hassium: [CACHE SAVE] Failed to compute section hashes for {}", pos, t);
            sectionHashes = new long[0];
            contentHash = 0L;
        }

        SaveTask task = new SaveTask(pos, pos.x, pos.z, nbtBytes, contentHash, sectionHashes, true);
        taskQueue.offer(task);

        int sectionHashCount = sectionHashes != null ? sectionHashes.length : 0;
        Constants.LOG.debug("Hassium: [CACHE SAVE QUEUED] chunk {} ({} NBT bytes, hash={}, sections={}, queue: {})",
                pos, nbtBytes.length, Long.toHexString(contentHash), sectionHashCount, taskQueue.size());
    }

    /**
     * 后台处理循环
     * <p>
     * 从队列中取出任务，提交到 HassiumTaskExecutor 执行压缩+持久化。
     * 保持单消费者语义（避免并发写入同一 region 文件），同时复用统一线程池。
     */
    private void processLoop() {
        while (!Thread.currentThread().isInterrupted()) {
            try {
                SaveTask task = taskQueue.take();
                HassiumTaskExecutor executor = HassiumTaskExecutor.getClient();
                if (executor != null && executor.isRunning()) {
                    executor.submit(() -> processTask(task), TaskCategory.MISSION_CRITICAL);
                } else {
                    // 回退：直接在当前线程处理
                    processTask(task);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                Constants.LOG.error("Hassium: [CACHE SAVE] Error in save loop", e);
            }
        }
        Constants.LOG.debug("Hassium: CacheSaveQueue process loop exiting");
    }

    /**
     * 处理单个保存任务（后台线程执行）
     * <p>
     * 只处理字节数组，不访问任何 Minecraft 对象。
     */
    private void processTask(SaveTask task) {
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
            } else {
                Constants.LOG.warn("Hassium: [CACHE SAVE] Failed to persist chunk {}", task.pos());
            }

        } catch (Exception e) {
            Constants.LOG.error("Hassium: [CACHE SAVE] Error saving chunk {}", task.pos(), e);
        }
    }

    /**
     * 获取队列大小
     */
    public int getQueueSize() {
        return taskQueue.size();
    }

    /**
     * 异步刷新队列中所有待处理任务（S2 修复）
     * <p>
     * 将队列中所有待处理任务提交到 HassiumTaskExecutor，
     * 阻塞等待最多 timeoutMs 毫秒，超时则放弃剩余任务。
     * <p>
     * 在断开连接/维度切换时调用，确保所有待保存的区块都被持久化。
     *
     * @param timeoutMs 最大等待时间（毫秒），默认 3000
     */
    public void flushAsync(long timeoutMs) {
        // 停止后台线程接受新任务
        stopSaveThread();

        // 收集队列中所有剩余任务
        List<Runnable> remainingTasks = new ArrayList<>();
        SaveTask task;
        while ((task = taskQueue.poll()) != null) {
            final SaveTask t = task;
            remainingTasks.add(() -> processTask(t));
        }

        if (remainingTasks.isEmpty()) {
            Constants.LOG.debug("Hassium: [CACHE SAVE FLUSH] No pending tasks");
            return;
        }

        Constants.LOG.debug("Hassium: [CACHE SAVE FLUSH] Flushing {} remaining tasks (timeout={}ms)",
                remainingTasks.size(), timeoutMs);

        HassiumTaskExecutor executor = HassiumTaskExecutor.getClient();
        if (executor != null && executor.isRunning()) {
            // 提交到统一线程池，等待完成
            List<Future<?>> futures = new ArrayList<>();
            for (Runnable r : remainingTasks) {
                futures.add(executor.submit(() -> { r.run(); return null; }, TaskCategory.MISSION_CRITICAL));
            }

            long deadline = System.currentTimeMillis() + timeoutMs;
            int completed = 0;
            for (Future<?> future : futures) {
                long remaining = deadline - System.currentTimeMillis();
                if (remaining <= 0) {
                    Constants.LOG.warn("Hassium: [CACHE SAVE FLUSH] Timed out, {} tasks may be lost",
                            futures.size() - completed);
                    break;
                }
                try {
                    future.get(remaining, TimeUnit.MILLISECONDS);
                    completed++;
                } catch (TimeoutException e) {
                    Constants.LOG.warn("Hassium: [CACHE SAVE FLUSH] Individual task timed out");
                    break;
                } catch (Exception e) {
                    Constants.LOG.error("Hassium: [CACHE SAVE FLUSH] Task failed", e);
                    completed++;
                }
            }
        } else {
            // 回退：同步执行
            Constants.LOG.warn("Hassium: [CACHE SAVE FLUSH] No executor available, processing synchronously");
            for (Runnable r : remainingTasks) {
                try {
                    r.run();
                } catch (Exception e) {
                    Constants.LOG.error("Hassium: [CACHE SAVE FLUSH] Sync task failed", e);
                }
            }
        }
    }

    /**
     * 异步刷新（默认超时 3 秒）
     */
    public void flushAsync() {
        flushAsync(3000);
    }

    /**
     * 同步刷新（兼容旧调用者）
     * <p>
     * 内部委托给 flushAsync()。标记为 deprecated 以引导调用者迁移到 flushAsync()。
     */
    public void flush() {
        flushAsync(3000);
    }

    /**
     * 停止后台保存线程
     */
    private void stopSaveThread() {
        if (saveThread != null && saveThread.isAlive()) {
            saveThread.interrupt();
            try {
                saveThread.join(2000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            saveThread = null;
        }
    }

    /**
     * 清空队列并停止线程（断开连接时调用）
     * <p>
     * 注意：调用前应先调用 flush() 或 flushAsync() 保存待处理任务。
     */
    public void clear() {
        stopSaveThread();
        taskQueue.clear();
        initialized.set(false);
        Constants.LOG.debug("Hassium: Cleared cache save queue");
    }

    /**
     * 停止后台线程并标记初始化标志为 false（用于运行时可重置场景）
     */
    public void shutdown() {
        stopSaveThread();
        taskQueue.clear();
        initialized.set(false);
    }
}
