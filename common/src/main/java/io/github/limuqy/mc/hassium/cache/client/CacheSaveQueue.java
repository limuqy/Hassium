package io.github.limuqy.mc.hassium.cache.client;

import io.github.limuqy.mc.hassium.Constants;
import io.github.limuqy.mc.hassium.cache.ChunkContentHashUtil;
import io.github.limuqy.mc.hassium.concurrent.HassiumTaskExecutor;
import io.github.limuqy.mc.hassium.concurrent.TaskCategory;
import io.github.limuqy.mc.hassium.network.ClientChunkHandler;
import io.github.limuqy.mc.hassium.compat.RegistryCompat;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunk;

import java.util.ArrayList;
import java.util.List;
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

    /** 保存任务：只持有序列化后的字节数组快照、contentHash 和 sectionHashes */
    public record SaveTask(
            ChunkPos pos,
            int chunkX,
            int chunkZ,
            byte[] serializedData,
            long contentHash,
            long[] sectionHashes
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
     * 提交区块保存任务（主线程调用）
     * <p>
     * 在主线程完成序列化与 contentHash 计算，后台只处理字节数组。
     * <p>
     * 若缓存中已有服务端下行写入的 sectionHashes，则跳过卸载重写，
     * 避免客户端 LevelChunk 再编码覆盖权威 packet/hash 导致假 MISMATCH。
     */
    public void enqueue(LevelChunk chunk) {
        ChunkPos pos = chunk.getPos();

        ClientHassiumStorage storage = ClientChunkHandler.getClientStorage();
        if (storage != null) {
            long[] existing = storage.readSectionHashes(pos);
            if (existing != null && existing.length > 0) {
                Constants.LOG.debug(
                        "Hassium: [CACHE SAVE] Skip unload rewrite for chunk {} (authoritative sectionHashes present)",
                        pos);
                return;
            }
        }

        ensureInitialized();

        ClientLevel level = (ClientLevel) chunk.getLevel();
        byte[] serializedData = serializeChunk(chunk, level);
        if (serializedData == null) {
            Constants.LOG.warn("Hassium: [CACHE SAVE] Failed to serialize chunk {}, skipping", pos);
            return;
        }

        // 必须与服务端 chunkHash（combine sectionHashes）一致，不能用含 BE/heightmap 的 compute()
        long[] sectionHashes = computeSectionHashesFromSerialized(serializedData);
        long contentHash;
        if (sectionHashes != null && sectionHashes.length > 0) {
            contentHash = ChunkContentHashUtil.combineSectionHashesFromArray(sectionHashes);
        } else {
            contentHash = ChunkContentHashUtil.compute(chunk, level.getLightEngine()).hash();
        }
        SaveTask task = new SaveTask(pos, pos.x, pos.z, serializedData, contentHash, sectionHashes);
        taskQueue.offer(task);

        int sectionHashCount = sectionHashes != null ? sectionHashes.length : 0;
        Constants.LOG.debug("Hassium: [CACHE SAVE QUEUED] chunk {} ({} bytes, hash={}, sections={}, queue size: {})",
                pos, serializedData.length, Long.toHexString(contentHash), sectionHashCount, taskQueue.size());
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
                Constants.LOG.debug("Hassium: [CACHE SAVE] Saved chunk {} ({} bytes, hash={})",
                        task.pos(), task.serializedData().length, Long.toHexString(task.contentHash()));
            } else {
                Constants.LOG.warn("Hassium: [CACHE SAVE] Failed to persist chunk {}", task.pos());
            }

        } catch (Exception e) {
            Constants.LOG.error("Hassium: [CACHE SAVE] Error saving chunk {}", task.pos(), e);
        }
    }

    /**
     * 序列化区块数据（主线程调用，空光照）
     * <p>
     * 使用 ClientboundLevelChunkWithLightPacket 序列化，光照剥离由配置控制。
     * 在主线程完成以避免访问 Minecraft 对象的数据竞争。
     */
    private byte[] serializeChunk(LevelChunk chunk, ClientLevel level) {
        try {
            if (level == null) {
                return null;
            }

            // 光照剥离与服务端配置一致
            boolean stripLight = io.github.limuqy.mc.hassium.config.HassiumConfigService.getInstance().isLightStripEnabled();
            java.util.BitSet lightMask = stripLight ? new java.util.BitSet() : null;
            ClientboundLevelChunkWithLightPacket packet =
                    new ClientboundLevelChunkWithLightPacket(
                            chunk, level.getLightEngine(), lightMask, lightMask);

#if MC_VER < MC_1_20_5
            FriendlyByteBuf buf = new FriendlyByteBuf(io.netty.buffer.Unpooled.buffer());
            try {
                packet.write(buf);
                byte[] data = new byte[buf.readableBytes()];
                buf.readBytes(data);
                return data;
            } finally {
                buf.release();
            }
#else
            // 1.20.5+: Packet.write() removed, use STREAM_CODEC with RegistryFriendlyByteBuf
            net.minecraft.network.RegistryFriendlyByteBuf buf =
                    new net.minecraft.network.RegistryFriendlyByteBuf(io.netty.buffer.Unpooled.buffer(), level.registryAccess());
            try {
                ClientboundLevelChunkWithLightPacket.STREAM_CODEC.encode(buf, packet);
                byte[] data = new byte[buf.readableBytes()];
                buf.readBytes(data);
                return data;
            } finally {
                buf.release();
            }
#endif

        } catch (Exception e) {
            Constants.LOG.error("Hassium: [CACHE SAVE] Serialization failed for chunk {}", chunk.getPos(), e);
            return null;
        }
    }

    /**
     * 从序列化的 packet 字节中计算 section 哈希（主线程调用）
     * <p>
     * 解析 packet 格式提取 sections 字节数组，委托 ChunkContentHashUtil 计算各 section 哈希。
     * 失败时返回 null（非致命，sectionHashes 为可选优化）。
     */
    private long[] computeSectionHashesFromSerialized(byte[] data) {
        try {
            FriendlyByteBuf buf = new FriendlyByteBuf(io.netty.buffer.Unpooled.wrappedBuffer(data));
            try {
                buf.readInt(); // chunkX
                buf.readInt(); // chunkZ
                io.github.limuqy.mc.hassium.compat.ChunkPacketDataCompat.skipHeightmaps(buf);
                int sectionsSize = buf.readVarInt();
                if (sectionsSize <= 0 || sectionsSize > buf.readableBytes()) return null;
                byte[] sectionsBytes = new byte[sectionsSize];
                buf.readBytes(sectionsBytes);
                ClientLevel clientLevel = net.minecraft.client.Minecraft.getInstance().level;
                if (clientLevel == null) return null;
                int sectionCount = clientLevel.getSectionsCount();
                java.util.Map<Integer, Long> hashes =
                        ChunkContentHashUtil.computeSectionHashesFromBytes(sectionsBytes, sectionCount, clientLevel.registryAccess());
                return ChunkContentHashUtil.sectionHashesToArray(hashes);
            } finally {
                buf.release();
            }
        } catch (Exception e) {
            Constants.LOG.debug("Hassium: [CACHE SAVE] Failed to compute section hashes", e);
            return null;
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
