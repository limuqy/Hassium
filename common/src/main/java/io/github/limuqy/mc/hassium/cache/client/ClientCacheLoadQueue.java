package io.github.limuqy.mc.hassium.cache.client;

import io.github.limuqy.mc.hassium.Constants;
import io.github.limuqy.mc.hassium.concurrent.HassiumTaskExecutor;
import io.github.limuqy.mc.hassium.concurrent.TaskCategory;
import io.github.limuqy.mc.hassium.metrics.NetworkStats;
import io.github.limuqy.mc.hassium.network.ChunkDataRequestC2SPacket;
import io.github.limuqy.mc.hassium.network.ClientChunkHandler;
import io.github.limuqy.mc.hassium.platform.Services;
import io.github.limuqy.mc.hassium.utils.DebugLogger;
import io.github.limuqy.mc.hassium.utils.DebugLogger.LogType;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.level.ChunkPos;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 客户端缓存加载队列
 * <p>
 * 异步加载缓存命中区块，按距离优先级排序；主线程按时间预算 apply，避免 FPS 负反馈。
 * Phase 6: 后台加载任务通过 HassiumTaskExecutor 提交，不再维护独立的线程池。
 * <p>
 * 两级队列均为 {@link io.github.limuqy.mc.hassium.concurrent.KeyedPriorityQueue}：
 * 同区块重复入队时新任务取代旧任务（REPLACE），避免重复磁盘读 / 重复 apply，
 * 并保证消费侧按最新数据落地（防老数据覆盖）。
 */
public class ClientCacheLoadQueue {

    private static final ClientCacheLoadQueue INSTANCE = new ClientCacheLoadQueue();

    /** 队列键（op 恒 0：本类所有条目均为「缓存加载」语义） */
    private static io.github.limuqy.mc.hassium.concurrent.KeyedPriorityQueue.Key keyOf(ChunkPos pos) {
        return new io.github.limuqy.mc.hassium.concurrent.KeyedPriorityQueue.Key(
                ChunkPos.asLong(pos.x, pos.z), 0);
    }

    /** 待加载任务（缓存命中）：按 region 分桶，每桶一个后台任务串行处理（避免多线程并发 drain 同一队列） */
    private final ConcurrentHashMap<Long, io.github.limuqy.mc.hassium.concurrent.KeyedPriorityQueue<LoadTask>> pendingByRegion = new ConcurrentHashMap<>();

    /** 正在运行的后台任务（region → 任务），保证每 region 最多一个加载任务 */
    private final Set<Long> activeRegions = ConcurrentHashMap.newKeySet();

    /** 就绪队列（后台线程加载完成后放入，主线程取出应用） */
    private final io.github.limuqy.mc.hassium.concurrent.KeyedPriorityQueue<ReadyChunk> readyQueue =
            new io.github.limuqy.mc.hassium.concurrent.KeyedPriorityQueue<>(100);

    /**
     * 未完成权威（renderOnly=false）加载数 = pending + ready。
     * <p>
     * OVD 门控依据：权威加载未归零时超视渲染不得 enqueue（权威块独占每帧配额）；
     * 归零后 OVD 才能启动（配额内权威优先，权威轮空才轮到 OVD）。
     */
    private final java.util.concurrent.atomic.AtomicInteger authorityLoad = new java.util.concurrent.atomic.AtomicInteger();

    /** 权威加载是否已完成（pending + ready 均无权威块）。 */
    public int getAuthorityLoad() {
        return authorityLoad.get();
    }

    /** readyQueue 中是否还有权威块（层序保证：peek 非 renderOnly 即含权威）。 */
    public boolean hasAuthorityReady() {
        io.github.limuqy.mc.hassium.concurrent.KeyedPriorityQueue.Entry<ReadyChunk> head = readyQueue.peek();
        return head != null && !head.item().renderOnly();
    }

    /** 加载任务（优先级由队列键承载；就绪后按加载完成时刻实时距离重算） */
    private record LoadTask(ChunkPos pos, boolean renderOnly) {}

    /** 单批最大区块数（region 批量读上限，控制单批内存与锁持有时间） */
    private static final int MAX_BATCH_SIZE = 256;

    private static long regionKey(int regionX, int regionZ) {
        return ((long) regionX << 32) | (regionZ & 0xFFFFFFFFL);
    }

    private static long regionKey(ChunkPos pos) {
        return regionKey(pos.x >> 5, pos.z >> 5);
    }

    private int pendingTotal() {
        int n = 0;
        for (io.github.limuqy.mc.hassium.concurrent.KeyedPriorityQueue<LoadTask> q : pendingByRegion.values()) {
            n += q.size();
        }
        return n;
    }

    /**
     * 已加载完成的区块。
     *
     * @param data              packet 字节（后台重组完成）或 NBT 字节（level 未就绪回退）
     * @param hasCachedLight    磁盘 NBT {@code is_light_on=1}，apply 时可跳过同步重算
     * @param lightWritebackNbt 仅 miss 时保留，供重算后回写，避免再读盘；有光照时为 null
     */
    public record ReadyChunk(ChunkPos pos, byte[] data, double priority, boolean renderOnly,
                             boolean hasCachedLight, CompoundTag lightWritebackNbt) {}

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
                pos, String.format("%.1f", priority), renderOnly, pendingTotal());
        long rk = regionKey(pos);
        io.github.limuqy.mc.hassium.concurrent.KeyedPriorityQueue<LoadTask> q = pendingByRegion.computeIfAbsent(rk,
                k -> new io.github.limuqy.mc.hassium.concurrent.KeyedPriorityQueue<>(16));
        // REPLACE：同区块重复入队（预填充/全量请求/重试多路径并发）用新任务取代旧任务，
        // 新任务带最新优先级；仅真正新插入的任务计入 authorityLoad（取代不重复计数，
        // 否则 OVD 门控的「权威未完成数」永久泄漏）。
        io.github.limuqy.mc.hassium.concurrent.KeyedPriorityQueue.OfferResult result =
                q.offer(new LoadTask(pos, renderOnly), keyOf(pos), priority,
                        io.github.limuqy.mc.hassium.concurrent.KeyedPriorityQueue.OfferPolicy.REPLACE);
        if (result == io.github.limuqy.mc.hassium.concurrent.KeyedPriorityQueue.OfferResult.INSERTED && !renderOnly) {
            authorityLoad.incrementAndGet();
        }
        // 提交该 region 的加载任务（每 region 最多一个在跑）
        scheduleRegion(rk);
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

        Set<Long> touched = new HashSet<>();
        int inserted = 0;
        for (int i = 0; i < positions.size(); i++) {
            ChunkPos pos = positions.get(i);
            long rk = regionKey(pos);
            io.github.limuqy.mc.hassium.concurrent.KeyedPriorityQueue<LoadTask> q = pendingByRegion.computeIfAbsent(rk,
                    k -> new io.github.limuqy.mc.hassium.concurrent.KeyedPriorityQueue<>(16));
            io.github.limuqy.mc.hassium.concurrent.KeyedPriorityQueue.OfferResult result =
                    q.offer(new LoadTask(pos, false), keyOf(pos), priorities.get(i),
                            io.github.limuqy.mc.hassium.concurrent.KeyedPriorityQueue.OfferPolicy.REPLACE);
            if (result == io.github.limuqy.mc.hassium.concurrent.KeyedPriorityQueue.OfferResult.INSERTED) {
                inserted++;
            }
            touched.add(rk);
        }
        authorityLoad.addAndGet(inserted);

        // 每个涉及的 region 提交一个加载任务
        for (long rk : touched) {
            scheduleRegion(rk);
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
        CompoundTag nbt = ChunkDiskCodec.bytesToNbt(data);
        boolean hasLight = ChunkDiskCodec.isLightOn(nbt);
        CompoundTag writeback = (!hasLight && nbt != null) ? nbt : null;
        // REPLACE：同 pos 已有就绪数据（缓存加载完成 / 服务端推送）时，新数据取代旧数据
        //（旧数据摘出堆不再 apply）——防「旧磁盘快照在服务端新数据之后落地」覆盖。
        readyQueue.offer(new ReadyChunk(pos, data, priority, renderOnly, hasLight, writeback),
                keyOf(pos), priority,
                io.github.limuqy.mc.hassium.concurrent.KeyedPriorityQueue.OfferPolicy.REPLACE);
        DebugLogger.info(LogType.CACHE,
                "[CACHE_LOAD_QUEUE] Enqueued with data {} (priority={}, renderOnly={}, hasLight={}, readySize={})",
                pos, String.format("%.1f", priority), renderOnly, hasLight, readyQueue.size());
    }

    /**
     * 提交一个 region 的加载任务到 HassiumTaskExecutor（每 region 最多一个在跑）
     */
    private void scheduleRegion(long rk) {
        if (!activeRegions.add(rk)) {
            return;
        }
        HassiumTaskExecutor executor = HassiumTaskExecutor.getClient();
        if (executor != null && executor.isRunning()) {
            executor.submit(() -> processRegion(rk), TaskCategory.SAFE_TO_CANCEL);
        } else {
            // 回退：直接在当前线程处理（HassiumTaskExecutor 未初始化时）
            processRegion(rk);
        }
    }

    /** 攒批等待窗口：任务尾部的 region 桶为空时等一等，让后续 enqueue 积累成批 */
    private static final long BATCH_WAIT_MS = 10;

    /**
     * 处理一个 region 的全部待加载任务（在 HassiumTaskExecutor 线程池中执行）
     * <p>
     * 同一 region 的块分批顺序读（每批一次锁持有，最多 {@link #MAX_BATCH_SIZE} 块），
     * 锁外解压/解析并行；不同 region 的任务互不阻塞（各自 region 锁）。
     * 就绪队列仍按距离优先级排序，主线程 apply 顺序不受批次影响。
     */
    private void processRegion(long rk) {
        int regionX = (int) (rk >> 32);
        int regionZ = (int) rk;
        try {
            while (true) {
                io.github.limuqy.mc.hassium.concurrent.KeyedPriorityQueue<LoadTask> q = pendingByRegion.get(rk);
                if (q == null) {
                    break;
                }
                // 取一批（串行 poll，KeyedPriorityQueue 内部线程安全；同区块被取代的任务自动跳过）；
                // 不再摘除桶——空桶保留在 map，enqueue 的 computeIfAbsent 永远拿到同一桶对象，
                // 孤儿桶窗口消除
                List<LoadTask> batch = new ArrayList<>();
                while (batch.size() < MAX_BATCH_SIZE) {
                    io.github.limuqy.mc.hassium.concurrent.KeyedPriorityQueue.Entry<LoadTask> e = q.poll();
                    if (e == null) {
                        break;
                    }
                    batch.add(e.item());
                }
                if (batch.isEmpty()) {
                    // 攒批窗口（原语义：一次 10ms）；最终判定空才退出。
                    // 退出后 activeRegions 释放：此后 producer 的 offer → scheduleRegion 必成功；
                    // 退出前 producer 的 offer → 本循环下一轮或 finally 补调度接手，无悬挂窗口。
                    try {
                        Thread.sleep(BATCH_WAIT_MS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                    if (q.isEmpty()) {
                        break;
                    }
                    continue;
                }
                processBatch(rk, regionX, regionZ, batch);
            }
        } finally {
            activeRegions.remove(rk);
            // 处理期间可能又有同 region 入队（scheduleRegion 被 activeRegions 挡住），锁内检查后补调度
            io.github.limuqy.mc.hassium.concurrent.KeyedPriorityQueue<LoadTask> q = pendingByRegion.get(rk);
            if (q != null) {
                if (!q.isEmpty()) {
                    scheduleRegion(rk);
                }
            }
        }
    }

    /**
     * 处理一批同 region 任务（region 批量读 + 逐块就绪入队）
     */
    private void processBatch(long rk, int regionX, int regionZ, List<LoadTask> batch) {
        DebugLogger.info(LogType.CACHE,
                "[CACHE_LOAD] Processing batch of {} chunks (region {},{}, pendingSize={})",
                batch.size(), regionX, regionZ, pendingTotal());

        List<ChunkPos> positions = new ArrayList<>(batch.size());
        for (LoadTask task : batch) {
            positions.add(task.pos());
        }
        Map<ChunkPos, byte[]> loaded = ClientChunkHandler.loadChunkDataBatchFromCache(positions);

        for (LoadTask task : batch) {
            byte[] data = loaded.get(task.pos());
            if (data != null) {
                handleLoaded(task, data);
            } else if (task.renderOnly()) {
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
    }

    /**
     * 单块加载成功后的就绪入队（后台线程执行；NBT 重组 packet 字节 CPU 密集前移）
     */
    private void handleLoaded(LoadTask task, byte[] data) {
        try {
            CompoundTag nbt = ChunkDiskCodec.bytesToNbt(data);
            boolean hasLight = ChunkDiskCodec.isLightOn(nbt);
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
            CompoundTag writeback = (!hasLight && nbt != null) ? nbt : null;
            // 磁盘读期间玩家可能已移动：用加载完成时刻的实时距离重算（而非入队快照），
            // 与收包路径（解压完成时刻算优先级）语义对齐——避免「进服即移动」时身边块
            // 按出生点快照垫底、身后块先 apply。readyQueue 是优先队列，offer 时新 key
            // 自动插队。renderOnly 用 RENDER_ONLY 层、权威用 AUTHORITATIVE 层，层序不破。
            double priority = task.renderOnly()
                    ? io.github.limuqy.mc.hassium.concurrent.MainThreadDispatcher.renderOnlyPriority(task.pos())
                    : io.github.limuqy.mc.hassium.concurrent.MainThreadDispatcher.authoritativePriority(task.pos());
            readyQueue.offer(new ReadyChunk(task.pos(), packetBytes, priority, task.renderOnly(),
                    hasLight, writeback), keyOf(task.pos()), priority,
                    io.github.limuqy.mc.hassium.concurrent.KeyedPriorityQueue.OfferPolicy.REPLACE);
            // OVD(renderOnly) 不经 chunkHash 比对，需在磁盘命中时单独记入缓存指标；
            // 权威路径已在 ClientMetadataHandler 记过，避免双重计数。
            // 使用 ESTIMATED_CHUNK_BYTES 与权威路径口径一致（不依赖 packetBytes 实际长度）。
            if (task.renderOnly() && packetBytes.length > 0) {
                NetworkStats.recordCacheLoadEligible(NetworkStats.ESTIMATED_CHUNK_BYTES);
                NetworkStats.recordCacheHit(NetworkStats.ESTIMATED_CHUNK_BYTES);
                NetworkStats.recordCacheFullHit(NetworkStats.ESTIMATED_CHUNK_BYTES);
            }
            DebugLogger.info(LogType.CACHE,
                    "[CACHE_LOAD] Chunk {} loaded from disk ({} bytes, hasLight={}, readySize={})",
                    task.pos(), packetBytes.length, hasLight, readyQueue.size());
        } catch (Exception e) {
            if (task.renderOnly()) {
                DebugLogger.error("[CACHE_LOAD] renderOnly load error for {}", task.pos(), e);
                ViewDistanceExtensionService.getInstance().onRenderOnlyMiss(task.pos());
            } else {
                Constants.LOG.error("[CACHE_LOAD] Error loading chunk {} from cache", task.pos(), e);
                requestChunkFromServer(task.pos());
            }
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
            // OP_REQUEST：网络请求任务，不与 OP_CHUNK_APPLY（数据 apply）互相取代
        }, io.github.limuqy.mc.hassium.concurrent.MainThreadDispatcher.chunkKey(
                pos, io.github.limuqy.mc.hassium.concurrent.MainThreadDispatcher.OP_REQUEST));
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

        // 帧配额（maxChunksPerFrame）覆盖全部缓存块；KeyedPriorityQueue 层序天然保证
        // 权威（AUTHORITATIVE < RENDER_ONLY）先 poll——配额满时 break 即「权威优先，
        // 权威轮空才轮到 OVD」，无需额外判据。
        while (!readyQueue.isEmpty()) {
            long now = System.nanoTime();
            if (!forceOne && now >= deadlineNs) {
                break;
            }
            forceOne = false;
            if (!ClientMainThreadBudget.tryAcquireCacheApply()) {
                break; // 本帧配额已满（含 OVD substitute 消耗）；权威/OVD 同受 maxChunksPerFrame 硬顶
            }

            io.github.limuqy.mc.hassium.concurrent.KeyedPriorityQueue.Entry<ReadyChunk> entry = readyQueue.poll();
            if (entry == null) {
                break;
            }
            ReadyChunk chunk = entry.item();
            if (!chunk.renderOnly()) {
                // 缓存侧职责结束（成功落地或转网络重取），权威未完成计数相应减少
                authorityLoad.decrementAndGet();
            }
            // 消费侧版本校验：poll 与执行之间同 pos 更新数据入队（如服务端推送）→
            // 丢弃旧数据，防「旧磁盘快照覆盖新数据」
            if (!readyQueue.isCurrent(entry)) {
                DebugLogger.debug(LogType.CACHE,
                        "[CACHE_APPLY] Skip superseded chunk {} (newer data arrived)", chunk.pos());
                continue;
            }
            // 恢复预填充 / OVD：renderOnly 落地前权威区块可能已到达（hasChunk=true）。
            // 跳过而非覆盖 —— 权威数据优先，过期磁盘快照不得回写（预填充的旧数据会
            // 覆盖刚到的权威区块，造成方块闪烁回退）。
            if (chunk.renderOnly()) {
                net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
                net.minecraft.client.multiplayer.ClientLevel level = mc.level;
                if (level != null && level.getChunkSource().hasChunk(chunk.pos().x, chunk.pos().z)) {
                    DebugLogger.debug(LogType.CACHE,
                            "[CACHE_APPLY] Skip stale renderOnly chunk {} (authority already applied)",
                            chunk.pos());
                    continue;
                }
            }
            Constants.LOG.debug("[CACHE_APPLY] Applying chunk {} to world (renderOnly={}, hasLight={}, remaining={})",
                    chunk.pos(), chunk.renderOnly(), chunk.hasCachedLight(), readyQueue.size());
            // 缓存无光照（is_light_on=0）：不预提交重算——客户端无本地光照逻辑；
            // 空光缓存 apply 后由 TAIL（MixinLightRecompute，影子端启用态）统一投递影子端。
            // renderOnly 走 VDES 自己的路径，不投递。
            boolean appliedToWorld = ClientChunkHandler.applyChunkData(
                    chunk.pos().x, chunk.pos().z, chunk.data(), chunk.renderOnly(),
                    chunk.lightWritebackNbt(), chunk.hasCachedLight());
            if (appliedToWorld) {
                applied++;
            } else if (!chunk.renderOnly()) {
                requestChunkFromServer(chunk.pos());
            }
            // 消费完成：释放 key 登记（同 pos 后续入队获得全新版本号）
            readyQueue.release(entry);
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
        return pendingTotal();
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
        pendingByRegion.clear();
        activeRegions.clear();
        readyQueue.clear();
        authorityLoad.set(0);
        Constants.LOG.debug("Hassium: Cleared cache load queue");
    }
}
