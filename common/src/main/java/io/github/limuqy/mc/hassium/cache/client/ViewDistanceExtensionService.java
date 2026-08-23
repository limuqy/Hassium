package io.github.limuqy.mc.hassium.cache.client;

import io.github.limuqy.mc.hassium.Constants;
import io.github.limuqy.mc.hassium.concurrent.MainThreadDispatcher;
import io.github.limuqy.mc.hassium.config.HassiumConfigService;
import io.github.limuqy.mc.hassium.mixin.ClientLevelAccessor;
import io.github.limuqy.mc.hassium.mixin.OptionsAccessor;
import io.github.limuqy.mc.hassium.network.ClientChunkHandler;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientChunkCache;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunk;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
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
    /** 原版 ClientChunkCache.Storage 在请求半径外额外保留的区块安全带。 */
    private static final int CLIENT_CACHE_PADDING = 3;


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
    /**
     * 影子端未就绪期间被延迟的环带重扫（ready 后由 {@link #onShadowReady()} 补扫；
     * 影子失败/无握手则永不触发——OVD 本就关闭）。每次登录经
     * {@link #onClientStorageReady()} 重新评估，无跨会话残留。
     */
    private volatile boolean rescanDeferred = false;
    /** pending 卡住时下次再 request 的墙钟 */
    private long lastPendingKickMs = 0L;

    private final AtomicLong missTotal = new AtomicLong();
    private final AtomicLong retryTotal = new AtomicLong();
    /** Forget 被拦截、原地保留为 renderOnly 的次数 */
    private final AtomicLong forgetRetainTotal = new AtomicLong();
    /** unload 路径同栈/入队替换次数（Forget 以外的兜底） */
    private final AtomicLong unloadSubstituteTotal = new AtomicLong();
    /** 影子端缓存命中（hash 比对内存/磁盘读回）直接服务客户端的区块累计数（T5g）。
     *  与 loadedRenderOnly（已 apply 的 renderOnly）互斥口径：这些区块经官方通道以普通区块
     *  落地、不进入 loaded 集合，单独记账供「超视渲染」行展示影子复用；不随 clearAllRenderOnly
     *  重置（会话累计，与 forgetRetainTotal 同级）。 */
    private final AtomicLong shadowServedTotal = new AtomicLong();

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
     * 影子模式下数据源为影子端，卸载直接 drop（断连落盘由影子端 saveAll 承担）。
     */
    public boolean isRenderOnly(ChunkPos pos) {
        return pos != null && (loadedRenderOnly.contains(pos) || pendingRenderOnly.contains(pos));
    }
    /**
     * 当前客户端渲染方形窗口。后台影子任务不得访问 Minecraft 客户端状态；
     * 仅由渲染线程在回传包消费前调用。
     */
    public boolean isWithinCurrentClientView(ChunkPos pos) {
        return isWithinCurrentClientRange(pos, 0);
    }

    /**
     * 当前原版 {@link ClientChunkCache} 可接收的方形窗口。
     * Storage 使用 {@code max(2, requestedRadius) + 3}；保留这三格预取安全带，
     * 避免服务端已跟踪的边界包被客户端提前丢弃后永久不再重发。
     */
    public boolean isWithinCurrentClientCacheWindow(ChunkPos pos) {
        return isWithinCurrentClientRange(pos, CLIENT_CACHE_PADDING);
    }

    private boolean isWithinCurrentClientRange(ChunkPos pos, int padding) {
        Minecraft mc = Minecraft.getInstance();
        if (pos == null || mc == null || mc.player == null) {
            return false;
        }
        int playerChunkX = (int) Math.floor(mc.player.getX()) >> 4;
        int playerChunkZ = (int) Math.floor(mc.player.getZ()) >> 4;
        int radius = isEnabled() ? resolveEffectiveClientVD(mc) : resolveServerVD(mc);
        return isChunkInClientRange(pos.x - playerChunkX, pos.z - playerChunkZ,
                Math.max(2, radius) + padding);
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
     * <p>
     * P5 对齐：影子端未激活（握手未完成/创建中/已失败）时延迟执行——此刻重扫必读空盘
     * （影子存档尚未落盘），全部 miss 并耗尽 retry；影子端 ready 后由
     * {@link io.github.limuqy.mc.hassium.network.seedgen.ShadowServerRegistry} 调
     * {@link #onShadowReady()} 补扫。
     */
    public void onClientStorageReady() {
        if (!io.github.limuqy.mc.hassium.network.ClientChunkPipeline.getInstance().isShadowEngineActive()) {
            rescanDeferred = true;
            Constants.LOG.debug("Hassium: OVD rescan deferred until shadow engine active");
            return;
        }
        rescanDeferred = false;
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
     * 影子端 ready（ShadowServerRegistry.getOrCreate 成功后调用）：补上被延迟的环带重扫
     * （{@link #onClientStorageReady()} 因影子未激活而延迟的补扫）；未延迟则不动作。
     */
    public void onShadowReady() {
        if (rescanDeferred) {
            onClientStorageReady();
        }
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
        if (!cfg.isClientFeatureGateOpen() || !cfg.isClientCacheEnabled() || !cfg.isViewDistanceExtensionEnabled()) {
            clearAllRenderOnly();
            return;
        }
        // 方案 A（客户端零侵入架构）：OVD 数据源 = 影子端（读盘/生成 → 官方包推送）。
        // 非影子模式 = 纯网络优化，无本地缓存/OVD。
        if (!io.github.limuqy.mc.hassium.network.seedgen.ShadowLightCompute.isEnabled()) {
            clearAllRenderOnly();
            return;
        }

        int clientVD = resolveEffectiveClientVD(mc);
        int serverVD = resolveServerVD(mc);
        if (serverVD <= 0 || clientVD <= serverVD) {
            clearAllRenderOnly();
            return;
        }

        // 环带应存在但 loaded 仍空：站立 R2 无 geometryChanged，且 pending 可能卡在
        // 光屏障里。强制重扫；每 2s 对 pending 再 request（队列已消费则重新投递）。
        if (loadedRenderOnly.isEmpty()) {
            forceRescan = true;
            long now = System.currentTimeMillis();
            if (now - lastPendingKickMs >= 2000L) {
                lastPendingKickMs = now;
                for (ChunkPos pos : new HashSet<>(pendingRenderOnly)) {
                    pendingRenderOnly.remove(pos);
                    io.github.limuqy.mc.hassium.network.seedgen.OvdLocalGenerator.request(pos);
                    pendingRenderOnly.add(pos);
                }
            }
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
        // 影子模式：OVD 请求直接投递影子端（读盘/生成），无客户端加载队列限流；
        // 生成预算由 SeedGenExecutor 的线程池/队列自限。

        List<ChunkPos> toLoad = new ArrayList<>(needed.size());
        for (ChunkPos pos : needed) {
            if (loadedRenderOnly.contains(pos) || pendingRenderOnly.contains(pos)) {
                continue;
            }
            Long retryAt = missRetryAt.get(pos);
            if (retryAt != null && System.currentTimeMillis() < retryAt) {
                continue;
            }
            toLoad.add(pos);
        }
        // 切比雪夫距离升序：环带内层先入队（与方形 client 渲染范围一致；同距时欧氏次键）
        int px = playerPos.x;
        int pz = playerPos.z;
        toLoad.sort(Comparator
                .comparingInt((ChunkPos p) -> Math.max(Math.abs(p.x - px), Math.abs(p.z - pz)))
                .thenComparingLong(p -> {
                    long dx = (long) p.x - px;
                    long dz = (long) p.z - pz;
                    return dx * dx + dz * dz;
                }));

        int enqueued = 0;
        int cursor = 0;
        // loaded 仍空时不与影子读盘抢 cache-read：R2 每 tick 磁盘 hash 抽干配额会让
        // 环带 0 入队，站立后又写 last* 不再扫（偶发 ovdLoaded=0）。
        boolean bypassCacheReadCap = loadedRenderOnly.isEmpty();
        for (; cursor < toLoad.size(); cursor++) {
            if (!bypassCacheReadCap && !ClientMainThreadBudget.tryAcquireCacheRead()) {
                break;
            }
            if (loadRenderOnlyChunk(toLoad.get(cursor))) {
                enqueued++;
            } else if (!bypassCacheReadCap) {
                ClientMainThreadBudget.refundCacheRead();
            }
        }

        // 本 tick 未扫完 toLoad（预算打满）→ 不写 last*，下 tick geometryChanged 继续近距灌队
        if (cursor < toLoad.size()) {
            Constants.LOG.debug("Hassium: OVD enqueue {}/{} this tick (cache-read cap exhausted), defer rest",
                    enqueued, toLoad.size());
            return;
        }
        // load 全失败（握手/影子未就绪、hasChunk 幽灵、isLoadEnabled 尚未开）若仍写 last*，
        // 站立 R2 再无 geometryChanged，环带永远 0（ovd2：16/10 已加载 0 缺失 0）。
        // 有环带却 0 入队时保持 dirty，下 tick 重试。
        if (enqueued == 0 && !toLoad.isEmpty()) {
            lastClientVD = clientVD;
            lastServerVD = serverVD;
            Constants.LOG.debug("Hassium: OVD enqueue 0/{} this tick (loads failed), retry next tick",
                    toLoad.size());
            return;
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
                ((IClientLevelExtension) level).hassium$removeRenderOnlyChunk(pos.toLong());
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

    /**
     * @return true 若已成功登记 pending 并投递影子端 OVD 生成/读盘
     */
    private boolean loadRenderOnlyChunk(ChunkPos pos) {
        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        if (level == null || mc.player == null) {
            return false;
        }
        // 环带内已有真实区块（非 renderOnly）：不得用 OVD 历史/磁盘数据覆盖。
        // 正常路径服务器 Forget 会 tryRetainOnServerForget 转 renderOnly，但影子回传
        // (drainReady) 曾直接走 vanilla 通道，可能留下未标记的真实区块。R2 缩 VD 后
        // Forget 未必再来——跳过会让 loadedRenderOnly 永远为 0（ovdLoaded_not_positive）。
        // 原地收养为 renderOnly，与 Forget 保留同口径，不覆盖块数据。
        if (((ClientLevelAccessor) level).hassium$getChunkSource().hasChunk(pos.x, pos.z)
                && !isRenderOnly(pos)
                && !((IClientLevelExtension) level).hassium$isRenderOnly(pos.toLong())) {
            return shouldKeepAsRenderOnly(pos) && adoptExistingAsRenderOnly(pos);
        }

        // 影子模式（客户端零侵入架构）：OVD 数据源 = 影子端——R1 落盘读回优先
        // （generateChunk 官方链 getChunkFuture 自动读盘），盘缺失时本地生成兜底，
        // 统一经官方包通道 apply renderOnly。不依赖 HBT1 storage，先于其检查。
        if (io.github.limuqy.mc.hassium.network.seedgen.ShadowLightCompute.isEnabled()
                && io.github.limuqy.mc.hassium.network.seedgen.OvdLocalGenerator.isLoadEnabled()) {
            io.github.limuqy.mc.hassium.network.seedgen.OvdLocalGenerator.request(pos);
            pendingRenderOnly.add(pos);
            loadedRenderOnly.remove(pos);
            delayedUnloadAt.remove(pos);
            missRetryAt.remove(pos);
            return true;
        }

        // 非影子模式（纯网络优化）：OVD 禁用，不加载 renderOnly
        return false;
    }

    /**
     * 环带内已有未标记的真实区块：原地标 renderOnly 并计入 {@link #loadedRenderOnly}。
     * 不改块数据（避免用磁盘历史覆盖刚落地的权威/影子块）。
     */
    private boolean adoptExistingAsRenderOnly(ChunkPos pos) {
        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        if (level == null || pos == null) {
            return false;
        }
        LevelChunk chunk = level.getChunkSource().getChunkNow(pos.x, pos.z);
        if (chunk == null) {
            return false;
        }
        pendingRenderOnly.remove(pos);
        loadedRenderOnly.add(pos);
        delayedUnloadAt.remove(pos);
        missRetryAt.remove(pos);
        missRetryCount.remove(pos);
        ((IClientLevelExtension) level).hassium$addRenderOnlyChunk(pos.toLong());
        return true;
    }

    /**
     * 把环带内已在 ClientChunkCache、但尚未记入 {@link #loadedRenderOnly} 的柱原地收养。
     * R2 影子 FIFO 可能以非 renderOnly 落地环带重叠柱，Forget 不再来；冒烟 dump 前补记。
     *
     * @return 新收养数量
     */
    public int adoptPresentRingChunks() {
        Minecraft mc = Minecraft.getInstance();
        if (mc == null || mc.player == null || mc.level == null || !isEnabled()) {
            return 0;
        }
        if (!io.github.limuqy.mc.hassium.network.seedgen.ShadowLightCompute.isEnabled()) {
            return 0;
        }
        int clientVD = resolveEffectiveClientVD(mc);
        int serverVD = resolveServerVD(mc);
        if (serverVD <= 0 || clientVD <= serverVD) {
            return 0;
        }
        ClientLevel level = mc.level;
        ClientChunkCache cache = ((ClientLevelAccessor) level).hassium$getChunkSource();
        int adopted = 0;
        for (ChunkPos pos : calculateNeededChunks(mc.player.chunkPosition(), serverVD, clientVD)) {
            if (loadedRenderOnly.contains(pos)) {
                continue;
            }
            if (cache.hasChunk(pos.x, pos.z) && adoptExistingAsRenderOnly(pos)) {
                adopted++;
            }
        }
        return adopted;
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
        if (isRenderOnly(pos) || ((IClientLevelExtension) level).hassium$isRenderOnly(pos.toLong())) {
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
            // 新架构：断连落盘由影子端 saveAll 统一承担（区块 R1 已进影子端 ChunkMap），
            // Forget 保留路径无需客户端写盘。

            pendingRenderOnly.remove(pos);
            loadedRenderOnly.add(pos);
            delayedUnloadAt.remove(pos);
            missRetryAt.remove(pos);
            missRetryCount.remove(pos);
            ((IClientLevelExtension) level).hassium$addRenderOnlyChunk(pos.toLong());
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
     * 新架构：OVD 区块数据常驻影子端存档，卸载直接 drop；重新进环带由
     * OVD 读盘快速补（影子端读盘 ~10ms），不再做 HBT1 同栈替换。
     *
     * @return 恒 false（不再替换）
     */
    public boolean trySubstituteOnUnload(LevelChunk chunk) {
        return false;
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
        if (accessor.hassium$isRenderOnly(pos.toLong()) || loadedRenderOnly.contains(pos) || pendingRenderOnly.contains(pos)) {
            // 先清标记，避免 drop→unload 再触发 substitute / 写盘短路误判
            accessor.hassium$removeRenderOnlyChunk(pos.toLong());
            loadedRenderOnly.remove(pos);
            pendingRenderOnly.remove(pos);
            dropChunkFromClientCache(level, pos);
        } else {
            accessor.hassium$removeRenderOnlyChunk(pos.toLong());
            loadedRenderOnly.remove(pos);
            pendingRenderOnly.remove(pos);
        }
        missRetryAt.remove(pos);
        missRetryCount.remove(pos);
    }

    private void dropChunkFromClientCache(ClientLevel level, ChunkPos pos) {
        try {
            ClientChunkCache cache = ((ClientLevelAccessor) level).hassium$getChunkSource();
            // <1.21.1: drop(int, int)；1.21.1+: drop(ChunkPos)
#if MC_VER < MC_1_21_1
            cache.drop(pos.x, pos.z);
#else
            cache.drop(pos);
#endif
        } catch (Exception e) {
            // 回退反射（部分版本 / 映射差异）；按结构特征匹配而非字段名（SRG/intermediary 安全）
            try {
                ClientChunkCache cache = ((ClientLevelAccessor) level).hassium$getChunkSource();
                java.lang.reflect.Field storageField = io.github.limuqy.mc.hassium.compat.ReflectionCompat.findMemberClassField(ClientChunkCache.class);
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
            ((IClientLevelExtension) mc.level).hassium$removeRenderOnlyChunk(pos.toLong());
        }
        if (pos == null) {
            return;
        }
        // 影子模式无客户端 storage：直接登记 miss 退避，由 OVD 读盘/生成补洞
        missTotal.incrementAndGet();
        int count = missRetryCount.getOrDefault(pos, 0) + 1;
        missRetryCount.put(pos, count);
        long delay = Math.min(MISS_RETRY_MAX_MS, MISS_RETRY_BASE_MS << Math.min(count - 1, 4));
        missRetryAt.put(pos, System.currentTimeMillis() + delay);
        Constants.LOG.debug("Hassium: OVD miss {} retry in {}ms (count={})", pos, delay, count);
        // OVD 本地生成：miss 时影子端按世界种子本地生成填充（默认关；无种子自动关闭）。
        // 生成的 renderOnly 区块落地后 onRenderOnlyApplied 清 miss 计数；同柱去重防风暴。
        // 关键：必须只在 pos 仍属于当前 OVD 环带时才重新请求。异步生成/apply 期间玩家
        // 可能已经飞远，旧 pos 已不在 clientVD 内；无条件重请求会让陈旧区块无限
        // “生成→apply→Ignoring chunk→miss→再生成”循环，灌爆主线程/执行器。
        if (shouldKeepAsRenderOnly(pos)) {
            io.github.limuqy.mc.hassium.network.seedgen.OvdLocalGenerator.request(pos);
        }
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
     * 影子端缓存命中（hash 比对内存/磁盘读回）直接服务客户端：累计计数（任意线程可调，
     * T5g）。调用点 = ShadowLightCompute.processRemoteHashes 内存/磁盘命中分支
     * （disk push / memory push 直推，客户端经官方通道以普通区块落地）。
     */
    public void noteShadowServed() {
        shadowServedTotal.incrementAndGet();
    }

    /**
     * 清理所有 renderOnly 状态。
     */
    public void clearAllRenderOnly() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level != null) {
            IClientLevelExtension accessor = (IClientLevelExtension) mc.level;
            for (ChunkPos pos : new HashSet<>(loadedRenderOnly)) {
                accessor.hassium$removeRenderOnlyChunk(pos.toLong());
            }
            for (ChunkPos pos : new HashSet<>(pendingRenderOnly)) {
                accessor.hassium$removeRenderOnlyChunk(pos.toLong());
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

    /** 影子端缓存命中直接服务的区块累计数（hash 比对内存/磁盘读回；T5g，与 loaded 互斥口径）。 */
    public long getShadowServedCount() {
        return shadowServedTotal.get();
    }

    /** 已 enqueue、等待磁盘 hit/miss 的 renderOnly 数量。 */
    public int getPendingLoadCount() {
        return pendingRenderOnly.size();
    }

    public int getPendingMissCount() {
        return missRetryAt.size();
    }

    /** 上一次 update 解析的服务端视距（环带下界，反映 Options.serverRenderDistance / simulationDistance 兜底）。 */
    public int getLastServerVD() {
        return lastServerVD;
    }

    /** 上一次 update 解析的客户端有效视距（环带上界，含 maxRenderDistance 上限钳制）。 */
    public int getLastClientVD() {
        return lastClientVD;
    }

    /**
     * 理论环带区块总数（面积法：方形渲染区 - 服务器推送椭圆近似）。
     * <p>客户端渲染方形面积 {@code (2*clientVD+1)^2} 减去 {@link #countServerPushedArea(int)} 椭圆内近似。
     * 用于 stats 与 {@code loaded + pending + miss} 对照，定量诊断客户端/服务端视野是否对齐：
     * 若 {@code (loaded + pending + miss) << theoretical}，说明 OVD 几何未扫完或 VD 不一致。
     */
    public int getTheoreticalRingCount() {
        if (!isEnabled() || lastServerVD <= 0 || lastClientVD <= lastServerVD) {
            return 0;
        }
        int clientArea = (2 * lastClientVD + 1) * (2 * lastClientVD + 1);
        return clientArea - countServerPushedArea(lastServerVD);
    }

    /**
     * 服务器实际推送近似面积——与 {@link #isChunkInServerRange} 收益守恒：
     * 对每个 {@code (dx, dz)} 在切比雪夫 {@code |dx|, |dz| <= serverVD} 的方形内，
     * 计数 {@code isChunkInServerRange(dx, dz, serverVD) == true} 的格子。
     */
    private static int countServerPushedArea(int serverVD) {
        if (serverVD <= 0) return 0;
        int sum = 0;
        for (int dx = -serverVD; dx <= serverVD; dx++) {
            for (int dz = -serverVD; dz <= serverVD; dz++) {
                if (isChunkInServerRange(dx, dz, serverVD)) sum++;
            }
        }
        return sum;
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
        if (!cfg.isClientFeatureGateOpen() || !cfg.isClientCacheEnabled() || !cfg.isViewDistanceExtensionEnabled()) {
            return false;
        }
        int clientVD = resolveEffectiveClientVD(mc);
        int serverVD = resolveServerVD(mc);
        return serverVD > 0 && clientVD > serverVD;
    }
}
