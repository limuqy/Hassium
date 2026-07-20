package io.github.limuqy.mc.hassium.cache.client;

import io.github.limuqy.mc.hassium.Constants;
import io.github.limuqy.mc.hassium.concurrent.HassiumTaskExecutor;
import io.github.limuqy.mc.hassium.concurrent.TaskCategory;
import io.github.limuqy.mc.hassium.network.ChunkDataRequestC2SPacket;
import io.github.limuqy.mc.hassium.network.ClientChunkHandler;
import io.github.limuqy.mc.hassium.platform.Services;
import io.github.limuqy.mc.hassium.utils.DebugLogger;
import io.github.limuqy.mc.hassium.utils.DebugLogger.LogType;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.level.ChunkPos;

import java.util.List;
import java.util.concurrent.PriorityBlockingQueue;

/**
 * 客户端缓存加载队列
 * <p>
 * 异步加载缓存命中区块，按距离优先级排序；主线程按时间预算 apply，避免 FPS 负反馈。
 * Phase 6: 后台加载任务通过 HassiumTaskExecutor 提交，不再维护独立的线程池。
 */
public class ClientCacheLoadQueue {

    private static final ClientCacheLoadQueue INSTANCE = new ClientCacheLoadQueue();

    /** 待加载任务（缓存命中） */
    private final PriorityBlockingQueue<LoadTask> pendingTasks =
            new PriorityBlockingQueue<>(100, (a, b) -> Double.compare(a.priority, b.priority));

    /** 就绪队列（后台线程加载完成后放入，主线程取出应用） */
    private final PriorityBlockingQueue<ReadyChunk> readyQueue =
            new PriorityBlockingQueue<>(100, (a, b) -> Double.compare(a.priority, b.priority));

    /** 加载任务 */
    private record LoadTask(ChunkPos pos, double priority, boolean renderOnly) {}

    /** 已加载完成的区块（data 为 packet 字节：后台重组完成；或 NBT 字节：level 未就绪回退路径） */
    public record ReadyChunk(ChunkPos pos, byte[] data, double priority, boolean renderOnly) {}

    private ClientCacheLoadQueue() {}

    public static ClientCacheLoadQueue getInstance() {
        return INSTANCE;
    }

    /**
     * 添加缓存命中区块到加载队列（需要从磁盘加载）
     *
     * @param pos        区块坐标
     * @param priority   优先级（越小越优先，通常是距离）
     * @param renderOnly 是否仅渲染
     */
    public void enqueue(ChunkPos pos, double priority, boolean renderOnly) {
        DebugLogger.info(LogType.CACHE, "[CACHE_LOAD_QUEUE] Enqueuing chunk {} (priority={}, renderOnly={}, pendingSize={})",
                pos, String.format("%.1f", priority), renderOnly, pendingTasks.size());
        pendingTasks.offer(new LoadTask(pos, priority, renderOnly));
        // 提交加载任务到统一后台执行器（Phase 6）
        submitNextTask();
    }

    /**
     * 添加缓存命中区块到加载队列（默认非 renderOnly）
     */
    public void enqueue(ChunkPos pos, double priority) {
        enqueue(pos, priority, false);
    }

    /**
     * 批量添加缓存命中区块到加载队列
     *
     * @param positions  区块坐标列表
     * @param priorities 对应的优先级列表
     */
    public void enqueueBatch(List<ChunkPos> positions, List<Double> priorities) {
        if (positions.size() != priorities.size()) {
            throw new IllegalArgumentException("Positions and priorities must have the same size");
        }

        for (int i = 0; i < positions.size(); i++) {
            pendingTasks.offer(new LoadTask(positions.get(i), priorities.get(i), false));
        }

        // 批量提交后，提交多个加载任务
        int batchSize = Math.min(positions.size(), 5);
        for (int i = 0; i < batchSize; i++) {
            submitNextTask();
        }
    }

    /**
     * 添加已有数据的区块到就绪队列（服务端推送的数据，已解压）
     *
     * @param pos      区块坐标
     * @param data     已解压的区块数据
     * @param priority 优先级
     */
    public void enqueueWithData(ChunkPos pos, byte[] data, double priority) {
        enqueueWithData(pos, data, priority, false);
    }

    /**
     * 添加已有数据的区块到就绪队列（含 renderOnly / 超视渲染即时替换路径）。
     *
     * @param pos        区块坐标
     * @param data       NBT 字节（HBT1）或 packet 字节
     * @param priority   优先级（越小越优先）
     * @param renderOnly 是否仅渲染
     */
    public void enqueueWithData(ChunkPos pos, byte[] data, double priority, boolean renderOnly) {
        if (pos == null || data == null) {
            return;
        }
        readyQueue.offer(new ReadyChunk(pos, data, priority, renderOnly));
        DebugLogger.info(LogType.CACHE,
                "[CACHE_LOAD_QUEUE] Enqueued with data {} (priority={}, renderOnly={}, readySize={})",
                pos, String.format("%.1f", priority), renderOnly, readyQueue.size());
    }

    /**
     * 提交下一个待加载任务到 HassiumTaskExecutor（Phase 6）
     */
    private void submitNextTask() {
        HassiumTaskExecutor executor = HassiumTaskExecutor.getClient();
        if (executor != null && executor.isRunning()) {
            executor.submit(this::processNextTask, TaskCategory.SAFE_TO_CANCEL);
        } else {
            // 回退：直接在当前线程处理（HassiumTaskExecutor 未初始化时）
            processNextTask();
        }
    }

    /**
     * 处理下一个待加载任务（在 HassiumTaskExecutor 线程池中执行）
     */
    private void processNextTask() {
        LoadTask task = pendingTasks.poll();
        if (task == null) {
            Constants.LOG.debug("[CACHE_LOAD] No pending tasks to process");
            return;
        }

        DebugLogger.info(LogType.CACHE, "[CACHE_LOAD] Processing chunk {} (priority={}, pendingSize={})",
                task.pos(), String.format("%.1f", task.priority()), pendingTasks.size());

        try {
            // 从缓存加载（磁盘 I/O + ZSTD 解压，在后台线程安全执行）
            byte[] data = ClientChunkHandler.loadChunkDataFromCache(task.pos());
            if (data != null) {
                // 后台线程重组 NBT→packet 字节（CPU 密集，前移出主线程）
                // maybeNbtToPacketBytes 幂等：NBT 字节重组为 packet，packet 字节原样返回
                net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
                net.minecraft.client.multiplayer.ClientLevel level = mc.level;
                byte[] packetBytes;
                if (level != null) {
                    packetBytes = ChunkDiskCodec.maybeNbtToPacketBytes(
                            data, level.registryAccess(), level.getSectionsCount());
                } else {
                    // level 未就绪：直接存 NBT，主线程 applyChunkData 的 maybeNbtToPacketBytes 兜底重组
                    packetBytes = data;
                }
                readyQueue.offer(new ReadyChunk(task.pos(), packetBytes, task.priority(), task.renderOnly()));
                DebugLogger.info(LogType.CACHE, "[CACHE_LOAD] Chunk {} loaded from disk ({} bytes, readySize={})",
                        task.pos(), packetBytes.length, readyQueue.size());
            } else {
                if (task.renderOnly()) {
                    // 超视渲染：缓存 miss 静默，不向服务器请求，回滚 loadedRenderOnly 标记
                    DebugLogger.info(LogType.CACHE,
                            "[CACHE_LOAD] renderOnly miss for {} (no cache, no server request)", task.pos());
                    ViewDistanceExtensionService.getInstance().onRenderOnlyMiss(task.pos());
                } else {
                    Constants.LOG.warn("[CACHE_LOAD] Failed to load chunk {} from cache, requesting from server",
                            task.pos());
                    requestChunkFromServer(task.pos());
                }
            }
        } catch (Exception e) {
            if (task.renderOnly()) {
                DebugLogger.error("[CACHE_LOAD] renderOnly load error for {}", task.pos(), e);
                ViewDistanceExtensionService.getInstance().onRenderOnlyMiss(task.pos());
            } else {
                Constants.LOG.error("[CACHE_LOAD] Error loading chunk {} from cache", task.pos(), e);
                requestChunkFromServer(task.pos());
            }
        }

        // 继续处理队列中剩余任务
        if (!pendingTasks.isEmpty()) {
            submitNextTask();
        }
    }

    /**
     * 缓存加载失败时回退为向服务端请求完整数据（切回主线程发送）
     */
    private void requestChunkFromServer(ChunkPos pos) {
        io.github.limuqy.mc.hassium.concurrent.MainThreadDispatcher.execute(() -> {
            try {
                net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
                if (mc.player == null || mc.getConnection() == null) {
                    return;
                }
                String dimension = mc.player.level().dimension()
#if MC_VER < MC_1_21_11
                        .location()
#else
                        .identifier()
#endif
                        .toString();
                ChunkDataRequestC2SPacket request = new ChunkDataRequestC2SPacket(dimension, List.of(pos));
                FriendlyByteBuf buf = new FriendlyByteBuf(io.netty.buffer.Unpooled.buffer());
                boolean sent = false;
                try {
                    request.encode(buf);
                    Services.NETWORK_MANAGER.sendChunkDataRequest(buf);
                    sent = true;
                } finally {
                    if (!sent) {
                        buf.release();
                    }
                }
            } catch (Exception e) {
                Constants.LOG.error("[CACHE_LOAD] Failed to request chunk {} from server", pos, e);
            }
        }, pos);
    }

    /**
     * 每帧应用区块（无预算参数，使用当前 JoinBoost/配置预算）。
     */
    public void processQueue() {
        long deadlineNs = System.nanoTime() + ClientMainThreadBudget.getBudgetNs();
        processQueueUntil(deadlineNs);
    }

    /**
     * 在共享时间预算内从就绪队列应用区块。
     *
     * @param deadlineNs 本帧截止时间（{@link System#nanoTime()}）
     * @return 剩余截止时间（可能已过期）
     */
    public long processQueueUntil(long deadlineNs) {
        if (readyQueue.isEmpty()) {
            return deadlineNs;
        }

        Constants.LOG.debug("[CACHE_APPLY] Processing queue, readySize={}", readyQueue.size());

        int hardCap = ClientMainThreadBudget.getHardCap();
        int applied = 0;
        // 至少应用 1 个，避免预算过紧饿死
        boolean forceOne = true;

        while (!readyQueue.isEmpty() && applied < hardCap) {
            long now = System.nanoTime();
            if (!forceOne && now >= deadlineNs) {
                break;
            }
            forceOne = false;

            ReadyChunk chunk = readyQueue.poll();
            if (chunk == null) {
                break;
            }
            Constants.LOG.debug("[CACHE_APPLY] Applying chunk {} to world (renderOnly={}, remaining={})",
                    chunk.pos(), chunk.renderOnly(), readyQueue.size());
            boolean appliedToWorld = ClientChunkHandler.applyChunkData(
                    chunk.pos().x, chunk.pos().z, chunk.data(), chunk.renderOnly());
            if (appliedToWorld) {
                applied++;
            } else if (!chunk.renderOnly()) {
                requestChunkFromServer(chunk.pos());
            }
        }

        if (applied > 0) {
            DebugLogger.info(LogType.CHUNK_APPLY, "[CACHE_APPLY] Applied {} chunks this frame (hardCap={}, remaining: {})",
                    applied, hardCap, readyQueue.size());
        }
        return deadlineNs;
    }

    /**
     * 获取待加载队列大小
     */
    public int getPendingSize() {
        return pendingTasks.size();
    }

    /**
     * 获取就绪队列大小
     */
    public int getReadySize() {
        return readyQueue.size();
    }

    /**
     * 清空所有队列（断开连接时调用）
     */
    public void clear() {
        pendingTasks.clear();
        readyQueue.clear();
        Constants.LOG.debug("Hassium: Cleared cache load queue");
    }
}
