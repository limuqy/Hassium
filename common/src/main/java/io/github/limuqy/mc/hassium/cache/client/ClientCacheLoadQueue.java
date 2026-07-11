package io.github.limuqy.mc.hassium.cache.client;

import io.github.limuqy.mc.hassium.Constants;
import io.github.limuqy.mc.hassium.concurrent.HassiumTaskExecutor;
import io.github.limuqy.mc.hassium.concurrent.TaskCategory;
import io.github.limuqy.mc.hassium.config.HassiumConfigService;
import io.github.limuqy.mc.hassium.network.ChunkDataRequestC2SPacket;
import io.github.limuqy.mc.hassium.network.ClientChunkHandler;
import io.github.limuqy.mc.hassium.platform.Services;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.level.ChunkPos;

import java.util.List;
import java.util.concurrent.PriorityBlockingQueue;

/**
 * 客户端缓存加载队列
 * <p>
 * 异步加载缓存命中区块，按距离优先级排序，每帧限制应用数量避免卡顿。
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

    /** 已加载完成的区块 */
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
        Constants.LOG.info("[CACHE_LOAD_QUEUE] Enqueuing chunk {} (priority={}, renderOnly={}, pendingSize={})",
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
        readyQueue.offer(new ReadyChunk(pos, data, priority, false));
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

        Constants.LOG.info("[CACHE_LOAD] Processing chunk {} (priority={}, pendingSize={})",
                task.pos(), String.format("%.1f", task.priority()), pendingTasks.size());

        try {
            // 从缓存加载（磁盘 I/O + ZSTD 解压，在后台线程安全执行）
            byte[] data = ClientChunkHandler.loadChunkDataFromCache(task.pos());
            if (data != null) {
                readyQueue.offer(new ReadyChunk(task.pos(), data, task.priority(), task.renderOnly()));
                Constants.LOG.info("[CACHE_LOAD] Chunk {} loaded from disk ({} bytes, readySize={})",
                        task.pos(), data.length, readyQueue.size());
            } else {
                Constants.LOG.warn("[CACHE_LOAD] Failed to load chunk {} from cache, requesting from server",
                        task.pos());
                requestChunkFromServer(task.pos());
            }
        } catch (Exception e) {
            Constants.LOG.error("[CACHE_LOAD] Error loading chunk {} from cache", task.pos(), e);
            requestChunkFromServer(task.pos());
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
     * 每帧应用区块，从就绪队列中取出区块应用到世界
     * <p>
     * 自适应调整：根据当前 FPS 和距离因子动态调整每帧应用数
     */
    public void processQueue() {
        if (readyQueue.isEmpty()) {
            return;
        }

        Constants.LOG.debug("[CACHE_APPLY] Processing queue, readySize={}", readyQueue.size());

        int maxPerFrame = calculateAdaptiveMaxPerFrame();

        int applied = 0;
        while (applied < maxPerFrame && !readyQueue.isEmpty()) {
            ReadyChunk chunk = readyQueue.poll();
            if (chunk != null) {
                Constants.LOG.debug("[CACHE_APPLY] Applying chunk {} to world (renderOnly={}, remaining={})",
                        chunk.pos(), chunk.renderOnly(), readyQueue.size());
                try {
                    ClientChunkHandler.applyChunkData(chunk.pos().x, chunk.pos().z, chunk.data(), chunk.renderOnly());
                    applied++;
                } catch (Exception e) {
                    Constants.LOG.error("[CACHE_APPLY] Error applying cached chunk {}", chunk.pos(), e);
                }
            }
        }

        if (applied > 0) {
            Constants.LOG.info("[CACHE_APPLY] Applied {} chunks this frame (max={}, remaining: {})",
                    applied, maxPerFrame, readyQueue.size());
        }
    }

    /**
     * 计算自适应的每帧最大应用数
     * <p>
     * 算法：基准值 * FPS 因子 * 距离因子
     * <ul>
     *   <li>FPS 因子：当前 FPS / 目标 FPS，限制在 [0.25, 1.5] 范围</li>
     *   <li>距离因子：基于就绪队列中区块的平均距离，近距离优先</li>
     * </ul>
     */
    private int calculateAdaptiveMaxPerFrame() {
        int baseMax = HassiumConfigService.getInstance().getMaxChunksPerFrame();
        if (baseMax <= 0) {
            baseMax = 10;
        }

        // 获取当前 FPS
        int currentFPS = getCurrentFPS();
        int targetFPS = HassiumConfigService.getInstance().getTargetFPS();

        // FPS 因子：当前 FPS / 目标 FPS，限制在 [0.25, 1.5] 范围
        // 高 FPS 时可以增加应用数，低 FPS 时减少
        double fpsFactor = (double) currentFPS / targetFPS;
        fpsFactor = Math.max(0.25, Math.min(1.5, fpsFactor));

        // 距离因子：基于就绪队列中区块的平均距离
        double distanceFactor = calculateDistanceFactor();

        // 计算最终值
        int result = (int) (baseMax * fpsFactor * distanceFactor);
        result = Math.max(1, Math.min(baseMax * 2, result)); // 限制在 [1, baseMax*2] 范围

        Constants.LOG.debug("[CACHE_APPLY] Adaptive: base={}, fps={}({}), dist={}, result={}",
                baseMax, currentFPS, String.format("%.2f", fpsFactor),
                String.format("%.2f", distanceFactor), result);

        return result;
    }

    /**
     * 获取当前 FPS
     */
    private int getCurrentFPS() {
        try {
            net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
            if (mc instanceof io.github.limuqy.mc.hassium.mixin.MinecraftAccessor accessor) {
                return accessor.hassium$getFps();
            }
            return 60;
        } catch (Exception e) {
            return 60; // 默认值
        }
    }

    /**
     * 计算距离因子
     * <p>
     * 基于就绪队列中区块的平均距离，近距离区块优先处理。
     * 距离因子范围 [0.5, 1.0]，近距离区块因子更高。
     */
    private double calculateDistanceFactor() {
        if (readyQueue.isEmpty()) {
            return 1.0;
        }

        try {
            net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
            if (mc.player == null) {
                return 1.0;
            }

            double playerChunkX = mc.player.getX() / 16.0;
            double playerChunkZ = mc.player.getZ() / 16.0;

            // 采样前几个区块计算平均距离
            int sampleSize = Math.min(readyQueue.size(), 5);
            double totalDistance = 0;
            int sampled = 0;

            // 使用 peek 遍历（不移除元素）
            for (ReadyChunk chunk : readyQueue) {
                if (sampled >= sampleSize) break;
                double dx = chunk.pos().x - playerChunkX;
                double dz = chunk.pos().z - playerChunkZ;
                totalDistance += Math.sqrt(dx * dx + dz * dz);
                sampled++;
            }

            if (sampled == 0) {
                return 1.0;
            }

            double avgDistance = totalDistance / sampled;
            int renderDistance = mc.options.renderDistance().get();

            // 距离因子：近距离（< renderDistance/2）时因子为 1.0，远距离时逐渐降低到 0.5
            double distanceFactor = 1.0 - (avgDistance / renderDistance) * 0.5;
            return Math.max(0.5, Math.min(1.0, distanceFactor));
        } catch (Exception e) {
            return 1.0; // 出错时使用默认因子
        }
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
