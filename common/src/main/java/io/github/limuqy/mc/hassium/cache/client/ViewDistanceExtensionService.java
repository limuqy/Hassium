package io.github.limuqy.mc.hassium.cache.client;

import io.github.limuqy.mc.hassium.Constants;
import io.github.limuqy.mc.hassium.config.HassiumConfigService;
import io.github.limuqy.mc.hassium.mixin.ClientLevelAccessor;
import io.github.limuqy.mc.hassium.mixin.OptionsAccessor;
import io.github.limuqy.mc.hassium.network.ClientChunkHandler;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientChunkCache;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunk;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 超视渲染服务（Beyond-view render）
 * <p>
 * 当客户端有效渲染距离（滑块 RD 与 {@code maxRenderDistance} 取 min）&gt; 服务端视距时，
 * 从本地 {@code hassium_cache} 加载超视距区块用于视觉渲染。
 * 这些区块标记为 {@code renderOnly}：
 * <ul>
 *   <li>仅参与渲染，不执行实体 AI、方块更新等逻辑</li>
 *   <li>不向服务器请求区块数据或 blockEntity（缓存 miss 静默跳过，可退避重试）</li>
 *   <li>真实区块到达时由 applier 清除标记，覆盖为正常区块</li>
 *   <li>服务端 Forget：若仍在 clientVD 环带内则<strong>取消 drop</strong>，原地标 renderOnly + 落盘（不卸载再读盘）</li>
 *   <li>其它 unload 路径兜底：主线程即时快照并同栈 apply（避免帧间虚空）</li>
 *   <li>离开环带：按 {@code ovdUnloadDelaySecs} 延迟卸载，减少高速移动抖动</li>
 *   <li>修改 RD / 重连后：reconcile 已排队但未进 Storage 的空洞，storage 就绪后强制重扫</li>
 * </ul>
 * <p>
 * 单人游戏不启用（无缓存数据源，且单人 RD 不受 server 钳制）。
 */
public class ViewDistanceExtensionService {

    private static final ViewDistanceExtensionService INSTANCE = new ViewDistanceExtensionService();

    /** miss 首次重试延迟（ms） */
    private static final long MISS_RETRY_BASE_MS = 1000L;
    /** miss 最大重试间隔（ms） */
    private static final long MISS_RETRY_MAX_MS = 10_000L;
    /** 同一 pos 最大重试次数；超出后等玩家移动 / storageReady / RD 变化再清 */
    private static final int MISS_RETRY_MAX_COUNT = 8;
    /**
     * 已在 loadedRenderOnly 但 ClientChunkCache 无块时，多久强制重新 enqueue（ms）。
     * 覆盖：改 RD 后 updateViewRadius 重建 Storage 丢块、apply 失败未回滚等。
     */
    private static final long STALE_LOADED_RECONCILE_MS = 2000L;

    /**
     * 超视渲染静默阈值：当权威区块加载队列（pending + ready）超过此值时，
     * 暂停 renderOnly enqueue，优先保证 serverVD 内权威区块加载。
     * 避免进服/飞行时超视渲染环带（数千区块）压垮 executor，导致 chunkHash 比对和
     * 权威区块加载延迟。
     */
    private static final int OVD_LOAD_THRESHOLD = 128;

    public static ViewDistanceExtensionService getInstance() {
        return INSTANCE;
    }

    /**
     * 已成功 apply 到 ClientChunkCache 的 renderOnly 区块（真正「loaded」）。
     * 仅排队、尚未命中缓存者不计入此集合，避免 stats loaded 虚高或 miss 后出现负语义。
     * Concurrent：主线程 enqueue/apply 与工作线程 onRenderOnlyMiss 并发访问。
     */
    private final Set<ChunkPos> loadedRenderOnly = ConcurrentHashMap.newKeySet();

    /** 已 enqueue 等待磁盘加载的 renderOnly 区块（与 loaded 互斥） */
    private final Set<ChunkPos> pendingRenderOnly = ConcurrentHashMap.newKeySet();

    /**
     * miss 退避：pos → 下次允许重试的 epoch ms。
     * 与 loaded/pending 互斥（miss 后从两者移除，登记到此 map）。
     */
    private final Map<ChunkPos, Long> missRetryAt = new ConcurrentHashMap<>();
    /** miss 次数：pos → count */
    private final Map<ChunkPos, Integer> missRetryCount = new ConcurrentHashMap<>();
    /**
     * 离开环带后的延迟卸载：pos → 到期 epoch ms。
     * 仍在 loaded 中，避免高速移动时反复 drop/load 闪虚空。
     */
    private final Map<ChunkPos, Long> delayedUnloadAt = new ConcurrentHashMap<>();

    private ChunkPos lastPlayerPos = null;
    private int lastServerVD = -1;
    private int lastClientVD = -1;
    /** 上次 reconcile 墙钟；限制全表扫描频率 */
    private long lastReconcileMs = 0L;
    /**
     * storage 异步初始化完成后置 true，下一 tick update 强制 geometry 重扫
     *（避免 lastPlayerPos 未变 early-return 导致重连后永不 enqueue）。
     */
    private volatile boolean forceRescan = false;

    private final AtomicLong missTotal = new AtomicLong();
    private final AtomicLong retryTotal = new AtomicLong();
    /** Forget 被拦截、原地保留为 renderOnly 的次数 */
    private final AtomicLong forgetRetainTotal = new AtomicLong();
    /** unload 路径同栈/入队替换次数（Forget 以外的兜底） */
    private final AtomicLong unloadSubstituteTotal = new AtomicLong();

    private ViewDistanceExtensionService() {
    }

    /**
     * 有效超视渲染 / 渲染距离：min(客户端视频 RD 滑块, 配置 maxRenderDistance)。
     * 供环带计算、Cache 半径、MixinOptions 解钳制共用。
     */
    public static int resolveEffectiveClientVD(Minecraft mc) {
        if (mc == null || mc.options == null) {
            return 2;
        }
        int slider = mc.options.renderDistance().get();
        int max = HassiumConfigService.getInstance().getMaxRenderDistance();
        return Math.max(2, Math.min(slider, max));
    }

    /**
     * 判断指定区块是否为 renderOnly（仅渲染、不参与模拟）。
     * 供 {@link CacheSaveQueue} 在卸载时短路：renderOnly 区块不写回缓存，保留历史快照。
     */
    public boolean isRenderOnly(ChunkPos pos) {
        return pos != null && (loadedRenderOnly.contains(pos) || pendingRenderOnly.contains(pos));
    }

    /**
     * 与原版 {@code ChunkMap.isChunkInRange} / 服务端 {@code ServerChunkPushManager.isServerChunkInRange}
     * 一致的视距判定（圆角方形近似）。
     * <p>
     * 必须使用此算法而非欧氏距离，否则会把服务器实际推送的边界区块（如 vd=6 时的 [6, ±1]、[6, ±2]、
     * [±1, 6]、[±2, 6]）误判为超视距，导致先 apply renderOnly 历史快照、再被服务器真实数据覆盖，
     * 表现为「闪烁跳变」与边界虚空。
     */
    private static boolean isChunkInServerRange(int dx, int dz, int serverVD) {
        int adx = Math.max(0, Math.abs(dx) - 1);
        int adz = Math.max(0, Math.abs(dz) - 1);
        long outer = Math.max(0, Math.max(adx, adz) - 1);
        long inner = Math.min(adx, adz);
        long distSq = inner * inner + outer * outer;
        long limit = (long) serverVD * (long) serverVD;
        return distSq < limit;
    }

    /**
     * 客户端渲染范围判定（切比雪夫 / 方形，与原版 ViewArea 一致）。
     * {@code Options.renderDistance} 直接作为方形半径，{@code Math.max(|dx|,|dz|) <= clientVD} 即在渲染范围内。
     */
    private static boolean isChunkInClientRange(int dx, int dz, int clientVD) {
        return Math.abs(dx) <= clientVD && Math.abs(dz) <= clientVD;
    }

    /**
     * 当前门控下，pos 是否应作为超视渲染环带区块存在（serverVD &lt; dist ≤ clientVD）。
     * 供 unload 即时替换判断。
     */
    public boolean shouldKeepAsRenderOnly(ChunkPos pos) {
        if (pos == null || !isEnabled()) {
            return false;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) {
            return false;
        }
        int clientVD = resolveEffectiveClientVD(mc);
        int serverVD = resolveServerVD(mc);
        if (serverVD <= 0 || clientVD <= serverVD) {
            return false;
        }
        ChunkPos playerPos = mc.player.chunkPosition();
        int dx = pos.x - playerPos.x;
        int dz = pos.z - playerPos.z;
        // 超视距环带 = 不在服务器推送范围内 && 仍在客户端渲染范围内
        return !isChunkInServerRange(dx, dz, serverVD) && isChunkInClientRange(dx, dz, clientVD);
    }

    /**
     * 客户端缓存 storage 异步就绪后调用：清 miss 计数并强制下一 tick 全环带重扫。
     * 解决「重连后超视渲染失效」：onLogin 异步 init 期间 load 全 miss 并耗尽 retry，
     * 且 lastPlayerPos 未变导致 early-return。
     */
    public void onClientStorageReady() {
        missRetryAt.clear();
        missRetryCount.clear();
        delayedUnloadAt.clear();
        forceRescan = true;
        lastPlayerPos = null;
        lastClientVD = -1;
        lastServerVD = -1;
        Constants.LOG.debug("Hassium: OVD storage ready → force rescan");
    }

    /**
     * 更新视距扩展。应在客户端 tick 中调用。
     */
    public void update() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null || mc.getConnection() == null) {
            return;
        }
        if (mc.getSingleplayerServer() != null) {
            return;
        }

        HassiumConfigService cfg = HassiumConfigService.getInstance();
        if (!cfg.isClientCacheEnabled() || !cfg.isViewDistanceExtensionEnabled()) {
            clearAllRenderOnly();
            return;
        }

        int clientVD = resolveEffectiveClientVD(mc);
        int serverVD = resolveServerVD(mc);
        if (serverVD <= 0 || clientVD <= serverVD) {
            clearAllRenderOnly();
            return;
        }

        // 关键：每 tick 都必须 expand。原版 SetChunkCacheRadius 会把半径缩回 serverVD；
        // 即使玩家未移动，也要在 early-return 之前守护半径，否则 apply 会 inRange 失败。
        // 使用 effective clientVD（含 maxRenderDistance 上限）。
        ensureChunkCacheRadius(mc.level, clientVD);

        ChunkPos playerPos = mc.player.chunkPosition();
        boolean geometryChanged = forceRescan
                || !playerPos.equals(lastPlayerPos)
                || clientVD != lastClientVD
                || serverVD != lastServerVD;
        if (forceRescan) {
            forceRescan = false;
        }

        // 到期 miss 重试：不依赖 geometryChanged（站立时也要补洞）
        retryExpiredMisses(playerPos, serverVD, clientVD);

        // 已标记 loaded 但 Storage 无块：改 RD 会重建 Storage 丢块，必须补回
        reconcileMissingLoadedChunks(mc.level, playerPos, serverVD, clientVD);

        // 延迟卸载：即使站立也要到期 drop（玩家可能已离开环带）
        processDelayedUnloads(playerPos, serverVD, clientVD);

        if (!geometryChanged) {
            return;
        }

        Set<ChunkPos> needed = calculateNeededChunks(playerPos, serverVD, clientVD);

        // 离开环带：登记延迟卸载（默认 ovdUnloadDelaySecs），高速移动时避免立即空洞
        long now = System.currentTimeMillis();
        int delaySecs = cfg.getOvdUnloadDelaySecs();
        long delayMs = Math.max(0L, delaySecs) * 1000L;
        Set<ChunkPos> toRemove = new HashSet<>(loadedRenderOnly);
        toRemove.removeAll(needed);
        for (ChunkPos pos : toRemove) {
            if (delayMs <= 0L) {
                unloadRenderOnlyChunk(pos);
            } else {
                delayedUnloadAt.putIfAbsent(pos, now + delayMs);
            }
        }
        // 离开环带的 pending：取消排队登记（避免 stats/ isRenderOnly 假阳性）
        pendingRenderOnly.removeIf(p -> !needed.contains(p));
        // 重新进入环带：取消待卸载
        for (ChunkPos pos : needed) {
            delayedUnloadAt.remove(pos);
        }

        Iterator<Map.Entry<ChunkPos, Long>> missIt = missRetryAt.entrySet().iterator();
        while (missIt.hasNext()) {
            ChunkPos pos = missIt.next().getKey();
            if (!needed.contains(pos)) {
                missIt.remove();
                missRetryCount.remove(pos);
            }
        }

        // 加载新的 renderOnly（跳过已 apply / 已排队 / 未到期 miss）
        // 负载高时静默：优先保证 serverVD 内权威区块（chunkHash 比对后的缓存加载），
        // 避免超视渲染环带（数千区块）压垮 executor 导致权威区块延迟。
        // JoinBoost 窗口内（进服 5s）暂停限制：让超视距环带立即 enqueue，与权威区块并行加载，
        // 避免进服时环带空洞与不连贯。权威区块距离玩家更近（serverVD 内），PriorityBlockingQueue
        // 按距离排序会优先出队，不会被 renderOnly 饿死。
        // 不更新 lastPlayerPos → 下一 tick geometryChanged 仍为 true → 自动重试。
        if (!ClientMainThreadBudget.isJoinBoostActive()) {
            int pendingLoad = ClientCacheLoadQueue.getInstance().getPendingSize()
                    + ClientCacheLoadQueue.getInstance().getReadySize();
            if (pendingLoad > OVD_LOAD_THRESHOLD) {
                Constants.LOG.debug("Hassium: OVD paused (pendingLoad={}, threshold={}), waiting for authority chunks",
                        pendingLoad, OVD_LOAD_THRESHOLD);
                return;
            }
        }

        Set<ChunkPos> toLoad = new HashSet<>(needed);
        toLoad.removeAll(loadedRenderOnly);
        toLoad.removeAll(pendingRenderOnly);
        for (ChunkPos pos : toLoad) {
            Long retryAt = missRetryAt.get(pos);
            if (retryAt != null && System.currentTimeMillis() < retryAt) {
                continue;
            }
            loadRenderOnlyChunk(pos);
        }

        lastPlayerPos = playerPos;
        lastClientVD = clientVD;
        lastServerVD = serverVD;
    }

    /**
     * 处理到期的延迟卸载。若 pos 已回到环带则取消；否则真正 drop。
     */
    private void processDelayedUnloads(ChunkPos playerPos, int serverVD, int clientVD) {
        if (delayedUnloadAt.isEmpty()) {
            return;
        }
        long now = System.currentTimeMillis();
        Set<ChunkPos> due = new HashSet<>();
        for (Map.Entry<ChunkPos, Long> e : delayedUnloadAt.entrySet()) {
            if (e.getValue() <= now) {
                due.add(e.getKey());
            }
        }
        for (ChunkPos pos : due) {
            delayedUnloadAt.remove(pos);
            int dx = pos.x - playerPos.x;
            int dz = pos.z - playerPos.z;
            // 到期时若又回到环带则保留
            if (!isChunkInServerRange(dx, dz, serverVD) && isChunkInClientRange(dx, dz, clientVD)) {
                continue;
            }
            unloadRenderOnlyChunk(pos);
        }
    }

    /**
     * 已在 loadedRenderOnly 但 ClientChunkCache 无块时重新 enqueue。
     * 改渲染距离会触发 updateViewRadius 新建 Storage，旧 LevelChunk 引用未迁入
     *（仅迁移 inRange 的；半径从大变小再变大时环带洞）。
     */
    private void reconcileMissingLoadedChunks(ClientLevel level, ChunkPos playerPos,
                                              int serverVD, int clientVD) {
        if (loadedRenderOnly.isEmpty()) {
            return;
        }
        long now = System.currentTimeMillis();
        if (now - lastReconcileMs < STALE_LOADED_RECONCILE_MS) {
            return;
        }
        lastReconcileMs = now;

        ClientChunkCache cache = ((ClientLevelAccessor) level).hassium$getChunkSource();
        Set<ChunkPos> stale = new HashSet<>();
        for (ChunkPos pos : loadedRenderOnly) {
            int dx = pos.x - playerPos.x;
            int dz = pos.z - playerPos.z;
            if (isChunkInServerRange(dx, dz, serverVD) || !isChunkInClientRange(dx, dz, clientVD)) {
                continue;
            }
            if (!cache.hasChunk(pos.x, pos.z)) {
                stale.add(pos);
            }
        }
        for (ChunkPos pos : stale) {
            // 从 loaded 摘出再 load，否则 loadRenderOnlyChunk 会因 contains 直接 return
            loadedRenderOnly.remove(pos);
            if (level != null) {
                ((IClientLevelExtension) level).hassium$removeRenderOnlyChunk(pos);
            }
            missRetryAt.remove(pos);
            Constants.LOG.debug("Hassium: OVD reconcile re-queue missing {}", pos);
            loadRenderOnlyChunk(pos);
        }
    }

    private void retryExpiredMisses(ChunkPos playerPos, int serverVD, int clientVD) {
        long now = System.currentTimeMillis();
        Set<ChunkPos> due = new HashSet<>();
        for (Map.Entry<ChunkPos, Long> e : missRetryAt.entrySet()) {
            if (e.getValue() <= now) {
                due.add(e.getKey());
            }
        }
        for (ChunkPos pos : due) {
            int dx = pos.x - playerPos.x;
            int dz = pos.z - playerPos.z;
            if (isChunkInServerRange(dx, dz, serverVD) || !isChunkInClientRange(dx, dz, clientVD)) {
                missRetryAt.remove(pos);
                missRetryCount.remove(pos);
                continue;
            }
            Integer count = missRetryCount.getOrDefault(pos, 0);
            if (count >= MISS_RETRY_MAX_COUNT) {
                // 保留登记但不无限打盘；storageReady / RD 变化会清表
                continue;
            }
            if (loadedRenderOnly.contains(pos) || pendingRenderOnly.contains(pos)) {
                missRetryAt.remove(pos);
                missRetryCount.remove(pos);
                continue;
            }
            missRetryAt.remove(pos);
            retryTotal.incrementAndGet();
            loadRenderOnlyChunk(pos);
        }
    }

    private int resolveServerVD(Minecraft mc) {
        int serverVD = ((OptionsAccessor) mc.options).hassium$getServerRenderDistance();
        if (serverVD <= 0) {
            serverVD = mc.options.simulationDistance().get();
        }
        return serverVD;
    }

    /**
     * 公开入口：apply 前 / 包处理中扩大 {@link ClientChunkCache} 半径到 effective clientVD。
     * 防止 server {@code SetChunkCacheRadius} 缩回后、本 tick ensure 之前的 apply 被 inRange 丢弃。
     */
    public void ensureExpandedRadius() {
        Minecraft mc = Minecraft.getInstance();
        if (mc == null || mc.level == null) {
            return;
        }
        if (!isEnabled()) {
            return;
        }
        ensureChunkCacheRadius(mc.level, resolveEffectiveClientVD(mc));
    }

    private void ensureChunkCacheRadius(ClientLevel level, int clientVD) {
        try {
            ClientChunkCache cache = ((ClientLevelAccessor) level).hassium$getChunkSource();
            cache.updateViewRadius(clientVD);
        } catch (Exception e) {
            Constants.LOG.debug("Hassium: Failed to expand ClientChunkCache radius to {}", clientVD, e);
        }
    }

    private Set<ChunkPos> calculateNeededChunks(ChunkPos playerPos, int serverVD, int clientVD) {
        Set<ChunkPos> chunks = new HashSet<>();
        for (int dx = -clientVD; dx <= clientVD; dx++) {
            for (int dz = -clientVD; dz <= clientVD; dz++) {
                // 形状与服务器实际推送范围一致：避免边界区块误判为超视距导致闪烁
                if (isChunkInServerRange(dx, dz, serverVD)) continue;
                if (!isChunkInClientRange(dx, dz, clientVD)) continue;
                chunks.add(new ChunkPos(playerPos.x + dx, playerPos.z + dz));
            }
        }
        return chunks;
    }

    private void loadRenderOnlyChunk(ChunkPos pos) {
        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        if (level == null || mc.player == null) {
            return;
        }

        // storage 未就绪：不要进队列烧 miss 次数（异步 onLogin 窗口）
        if (ClientChunkHandler.getClientStorage() == null) {
            Constants.LOG.debug("Hassium: OVD skip enqueue {} (client storage not ready)", pos);
            return;
        }

        IClientLevelExtension accessor = (IClientLevelExtension) level;
        if (accessor.hassium$isRenderOnly(pos)) {
            return;
        }
        ClientChunkCache cache = ((ClientLevelAccessor) level).hassium$getChunkSource();
        if (cache.hasChunk(pos.x, pos.z)) {
            return;
        }

        double dx = pos.x - (mc.player.getX() / 16.0);
        double dz = pos.z - (mc.player.getZ() / 16.0);
        double priority = Math.sqrt(dx * dx + dz * dz);
        // 仅 pending；apply 成功后再进 loaded，避免 miss 后 loaded 语义错误 / 负向感知
        pendingRenderOnly.add(pos);
        loadedRenderOnly.remove(pos);
        ClientCacheLoadQueue.getInstance().enqueue(pos, priority, true);
        delayedUnloadAt.remove(pos);
        // 重试路径：清掉旧 miss 时间戳，保留 count 供下次 miss 退避
        missRetryAt.remove(pos);
        Constants.LOG.debug("Hassium: Queued render-only chunk {} for async loading", pos);
    }

    /**
     * 服务端 {@code ClientboundForgetLevelChunk} 到达时：若 pos 仍在超视渲染环带且本地已有块，
     * <strong>不 drop</strong>，原地标为 renderOnly，并异步落盘最新快照。
     * <p>
     * 这是主路径：避免「卸载 → 再读缓存 apply」的多余往返与帧间空洞。
     * 由 {@code MixinClientPacketListener.handleForgetLevelChunk} HEAD cancellable 调用；
     * 返回 true 时应 cancel 包处理（跳过 {@code drop} 与 light removal）。
     *
     * @return true 若已原地保留（调用方 cancel Forget）
     */
    public boolean tryRetainOnServerForget(ChunkPos pos) {
        if (pos == null || !shouldKeepAsRenderOnly(pos)) {
            return false;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc == null || mc.level == null) {
            return false;
        }
        ClientLevel level = mc.level;

        // 已是 renderOnly：仍应吞掉 Forget，避免服务端卸载把超视渲染块打穿
        if (isRenderOnly(pos) || ((IClientLevelExtension) level).hassium$isRenderOnly(pos)) {
            delayedUnloadAt.remove(pos);
            forgetRetainTotal.incrementAndGet();
            Constants.LOG.debug("Hassium: OVD forget retain (already renderOnly) {}", pos);
            return true;
        }

        LevelChunk chunk = level.getChunkSource().getChunkNow(pos.x, pos.z);
        if (chunk == null) {
            // 本地无块：无需 cancel；让 vanilla drop 空操作，后续超视渲染 load 补洞
            return false;
        }

        try {
            ensureChunkCacheRadius(level, resolveEffectiveClientVD(mc));
            // 落盘必须在标 renderOnly 之前（CacheSaveQueue 对 renderOnly 短路）
            CacheSaveQueue.getInstance().enqueue(chunk);

            pendingRenderOnly.remove(pos);
            loadedRenderOnly.add(pos);
            delayedUnloadAt.remove(pos);
            missRetryAt.remove(pos);
            missRetryCount.remove(pos);
            ((IClientLevelExtension) level).hassium$addRenderOnlyChunk(pos);
            forgetRetainTotal.incrementAndGet();
            Constants.LOG.debug("Hassium: OVD forget retain in-place {}", pos);
            return true;
        } catch (Exception e) {
            Constants.LOG.debug("Hassium: OVD forget retain failed for {}", pos, e);
            return false;
        }
    }

    /**
     * 真实区块即将被卸载时的兜底（{@link MixinClientLevel} unload HEAD）。
     * <p>
     * 主路径已在 Forget 时 {@link #tryRetainOnServerForget} 取消 drop；此处覆盖
     * 其它仍会走到 {@code level.unload} 的情况。
     * vanilla {@code Storage.replace(old, null)}：先 CAS 清空 slot，再 unload，
     * 故 HEAD 时可同栈 apply 填回。
     *
     * @return true 若已提交即时替换（调用方勿再清 renderOnly 标记）
     */
    public boolean trySubstituteOnUnload(LevelChunk chunk) {
        if (chunk == null) {
            return false;
        }
        ChunkPos pos = chunk.getPos();
        // 已是 renderOnly：不重复替换
        if (isRenderOnly(pos)) {
            return false;
        }
        if (!shouldKeepAsRenderOnly(pos)) {
            return false;
        }
        if (!(chunk.getLevel() instanceof ClientLevel clientLevel)) {
            return false;
        }

        try {
            // 扩大半径，避免随后 apply 被 inRange 丢弃
            Minecraft mc = Minecraft.getInstance();
            if (mc != null) {
                ensureChunkCacheRadius(clientLevel, resolveEffectiveClientVD(mc));
            }

            net.minecraft.nbt.CompoundTag nbt = ChunkDiskCodec.levelChunkToNbt(chunk, clientLevel);
            if (nbt == null) {
                Constants.LOG.debug("Hassium: OVD unload substitute serialize failed for {}", pos);
                return false;
            }
            byte[] nbtBytes = ChunkDiskCodec.nbtToBytes(nbt);
            if (nbtBytes == null) {
                return false;
            }

            pendingRenderOnly.remove(pos);
            loadedRenderOnly.add(pos);
            delayedUnloadAt.remove(pos);
            missRetryAt.remove(pos);
            missRetryCount.remove(pos);
            unloadSubstituteTotal.incrementAndGet();

            // 同栈 apply：Storage slot 已在 unload 前被 CAS 清空，此时写入不会被随后覆盖
            if (ClientChunkHandler.applyChunkData(pos.x, pos.z, nbtBytes, true)) {
                Constants.LOG.debug("Hassium: OVD unload substitute applied immediately for {}", pos);
            } else {
                Constants.LOG.debug("Hassium: OVD unload substitute immediate apply failed {}, queued by miss retry", pos);
            }
            return true;
        } catch (Exception e) {
            Constants.LOG.debug("Hassium: OVD unload substitute failed for {}", pos, e);
            return false;
        }
    }

    private void unloadRenderOnlyChunk(ChunkPos pos) {
        delayedUnloadAt.remove(pos);
        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        if (level == null) {
            loadedRenderOnly.remove(pos);
            pendingRenderOnly.remove(pos);
            missRetryAt.remove(pos);
            missRetryCount.remove(pos);
            return;
        }
        IClientLevelExtension accessor = (IClientLevelExtension) level;
        // 仅 drop 当前仍标为 renderOnly 的块；真实区块留给 vanilla Forget 路径
        if (accessor.hassium$isRenderOnly(pos) || loadedRenderOnly.contains(pos) || pendingRenderOnly.contains(pos)) {
            // 先清标记，避免 drop→unload 再触发 substitute / 写盘短路误判
            accessor.hassium$removeRenderOnlyChunk(pos);
            loadedRenderOnly.remove(pos);
            pendingRenderOnly.remove(pos);
            dropChunkFromClientCache(level, pos);
        } else {
            accessor.hassium$removeRenderOnlyChunk(pos);
            loadedRenderOnly.remove(pos);
            pendingRenderOnly.remove(pos);
        }
        missRetryAt.remove(pos);
        missRetryCount.remove(pos);
    }

    private void dropChunkFromClientCache(ClientLevel level, ChunkPos pos) {
        try {
            ClientChunkCache cache = ((ClientLevelAccessor) level).hassium$getChunkSource();
            // 1.20.1: drop(int, int)；1.20.2+: drop(ChunkPos)
#if MC_VER < MC_1_20_2
            cache.drop(pos.x, pos.z);
#else
            cache.drop(pos);
#endif
        } catch (Exception e) {
            // 回退反射（部分版本 / 映射差异）
            try {
                ClientChunkCache cache = ((ClientLevelAccessor) level).hassium$getChunkSource();
                java.lang.reflect.Field storageField = ClientChunkCache.class.getDeclaredField("storage");
                storageField.setAccessible(true);
                Object storage = storageField.get(cache);
                if (storage == null) {
                    return;
                }
                java.lang.reflect.Method dropMethod = storage.getClass().getDeclaredMethod("drop", int.class, int.class);
                dropMethod.setAccessible(true);
                LevelChunk old = (LevelChunk) dropMethod.invoke(storage, pos.x, pos.z);
                if (old != null) {
                    level.unload(old);
                }
            } catch (Exception e2) {
                Constants.LOG.debug("Hassium: Failed to drop renderOnly chunk {}", pos, e2);
            }
        }
    }

    /**
     * renderOnly 缓存 miss：登记退避重试，不向服务器请求。
     * storage 未就绪时不计入 miss 次数（避免重连窗口耗尽 8 次后永久静默）。
     */
    public void onRenderOnlyMiss(ChunkPos pos) {
        Minecraft mc = Minecraft.getInstance();
        pendingRenderOnly.remove(pos);
        loadedRenderOnly.remove(pos);
        delayedUnloadAt.remove(pos);
        if (mc.level != null) {
            ((IClientLevelExtension) mc.level).hassium$removeRenderOnlyChunk(pos);
        }
        if (pos == null) {
            return;
        }
        // 异步 init 未完成：短延迟再试，不烧 count
        if (ClientChunkHandler.getClientStorage() == null) {
            missRetryAt.put(pos, System.currentTimeMillis() + MISS_RETRY_BASE_MS);
            Constants.LOG.debug("Hassium: OVD miss {} (storage not ready), soft retry in {}ms",
                    pos, MISS_RETRY_BASE_MS);
            return;
        }
        missTotal.incrementAndGet();
        int count = missRetryCount.getOrDefault(pos, 0) + 1;
        missRetryCount.put(pos, count);
        long delay = Math.min(MISS_RETRY_MAX_MS, MISS_RETRY_BASE_MS << Math.min(count - 1, 4));
        missRetryAt.put(pos, System.currentTimeMillis() + delay);
        Constants.LOG.debug("Hassium: OVD miss {} retry in {}ms (count={})", pos, delay, count);
    }

    /**
     * 真实区块到达 renderOnly pos 时由 applier 回调。
     */
    public void onRealChunkApplied(ChunkPos pos) {
        pendingRenderOnly.remove(pos);
        loadedRenderOnly.remove(pos);
        delayedUnloadAt.remove(pos);
        missRetryAt.remove(pos);
        missRetryCount.remove(pos);
    }

    /**
     * renderOnly apply 成功：pending → loaded，清 miss 计数。
     */
    public void onRenderOnlyApplied(ChunkPos pos) {
        if (pos == null) {
            return;
        }
        pendingRenderOnly.remove(pos);
        loadedRenderOnly.add(pos);
        delayedUnloadAt.remove(pos);
        missRetryAt.remove(pos);
        missRetryCount.remove(pos);
    }

    /**
     * 清理所有 renderOnly 状态。
     */
    public void clearAllRenderOnly() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level != null) {
            IClientLevelExtension accessor = (IClientLevelExtension) mc.level;
            for (ChunkPos pos : new HashSet<>(loadedRenderOnly)) {
                accessor.hassium$removeRenderOnlyChunk(pos);
            }
            for (ChunkPos pos : new HashSet<>(pendingRenderOnly)) {
                accessor.hassium$removeRenderOnlyChunk(pos);
            }
        }
        int cleared = loadedRenderOnly.size() + pendingRenderOnly.size();
        if (cleared > 0) {
            Constants.LOG.debug("Hassium: Cleared {} render-only chunks", cleared);
        }
        loadedRenderOnly.clear();
        pendingRenderOnly.clear();
        missRetryAt.clear();
        missRetryCount.clear();
        delayedUnloadAt.clear();
        lastPlayerPos = null;
        lastServerVD = -1;
        lastClientVD = -1;
        lastReconcileMs = 0L;
        forceRescan = false;
    }

    /** 已成功 apply 到客户端的 renderOnly 数量（不含仅排队）。 */
    public int getLoadedCount() {
        return loadedRenderOnly.size();
    }

    /** 已 enqueue、等待磁盘 hit/miss 的 renderOnly 数量。 */
    public int getPendingLoadCount() {
        return pendingRenderOnly.size();
    }

    public int getPendingMissCount() {
        return missRetryAt.size();
    }

    public long getMissTotal() {
        return missTotal.get();
    }

    public long getRetryTotal() {
        return retryTotal.get();
    }

    public long getForgetRetainTotal() {
        return forgetRetainTotal.get();
    }

    public long getUnloadSubstituteTotal() {
        return unloadSubstituteTotal.get();
    }

    public boolean isEnabled() {
        Minecraft mc = Minecraft.getInstance();
        if (mc == null || mc.getConnection() == null || mc.getSingleplayerServer() != null) {
            return false;
        }
        HassiumConfigService cfg = HassiumConfigService.getInstance();
        if (!cfg.isClientCacheEnabled() || !cfg.isViewDistanceExtensionEnabled()) {
            return false;
        }
        int clientVD = resolveEffectiveClientVD(mc);
        int serverVD = resolveServerVD(mc);
        return serverVD > 0 && clientVD > serverVD;
    }
}
