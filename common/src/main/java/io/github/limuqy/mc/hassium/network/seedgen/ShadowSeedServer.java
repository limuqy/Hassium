package io.github.limuqy.mc.hassium.network.seedgen;

import com.mojang.logging.LogUtils;
import io.github.limuqy.mc.hassium.compat.BlockEntityCompat;
import io.github.limuqy.mc.hassium.compat.ChunkDataCompat;
import io.github.limuqy.mc.hassium.compat.LevelCompat;
import io.github.limuqy.mc.hassium.compat.ShadowChunkMapCompat;
import io.github.limuqy.mc.hassium.compat.ShadowServerCompat;
import io.github.limuqy.mc.hassium.compat.LevelChunkSectionCompat;
import io.github.limuqy.mc.hassium.mixin.ThreadedLevelLightEngineAccessor;
import io.github.limuqy.mc.hassium.network.SectionDeltaS2CPacket;
import io.github.limuqy.mc.hassium.network.sectiondelta.SectionDeltaSnapshot;
import io.github.limuqy.mc.hassium.network.sectiondelta.SectionDeltaSnapshots;
import io.github.limuqy.mc.hassium.utils.DebugLogger;
import io.github.limuqy.mc.hassium.network.sectiondelta.SectionPlaneSyndrome;
import io.github.limuqy.mc.hassium.utils.DimensionKey;
import java.net.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.locks.LockSupport;
import java.util.function.BooleanSupplier;
import net.minecraft.CrashReport;
import net.minecraft.SystemReport;
import net.minecraft.core.BlockPos;
import net.minecraft.util.datafix.DataFixers;
import net.minecraft.resources.ResourceKey;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.core.SectionPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.Packet;
import net.minecraft.server.MinecraftServer;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.network.protocol.game.ClientboundBlockUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundLevelChunkPacketData;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import net.minecraft.network.protocol.game.ClientboundSectionBlocksUpdatePacket;
import net.minecraft.server.level.ServerLevel;
import java.util.UUID;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.server.Services;
import net.minecraft.server.WorldStem;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ThreadedLevelLightEngine;
import net.minecraft.server.packs.repository.PackRepository;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.lighting.LevelLightEngine;
import net.minecraft.world.level.lighting.LightEngine;
import net.minecraft.world.level.storage.LevelStorageSource;
import org.slf4j.Logger;

/**
 * 影子服务端：客户端进程内、不 spin 的 MinecraftServer 子类，
 * 专用于按服务器下发的 worldSeed 本地生成 FULL 区块（SeedGen Phase 2）。
 * <p>
 * 模板 = 原版 GameTestServer（同样不走正常流程、不 prepareLevels），差异：
 * <ul>
 *   <li>worldSeed 用服务端下发的种子，preset 用 NORMAL（原版主世界，与服务器一致）</li>
 *   <li>overworldData 预置 initialized=true，跳过 setInitialSpawn 的 spawn 区块生成</li>
 *   <li>区块生成靠 {@link ServerChunkCache#getChunk} 的 managedBlock 驱动任务队列，
 *       任意线程阻塞调用即可，无需本 server 跑 tick 循环</li>
 * </ul>
 * <p><b>WAVE2 收口</b>：方法体内 API / 构造器 / ChunkStatus 换包 / 进度监听 / 权限集以外的
 * 运行时差异已迁入 {@link io.github.limuqy.mc.hassium.compat.ShadowServerCompat}。
 * 残留 {@code #if} 仅限 {@code MinecraftServer} 子类必须覆写的抽象方法签名，以及
 * {@code createLevels} 的 protected 有参/无参调用（compat 包无法代调）。
 */
public class ShadowSeedServer extends MinecraftServer {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final java.util.concurrent.atomic.AtomicLong SAVE_ALL_SEQ =
            new java.util.concurrent.atomic.AtomicLong();

    /** Services 最小化（1.20.1 四参 / 1.21.9 五参 mock resolver，见 {@link ShadowServerCompat#noServices}） */
    private static final Services NO_SERVICES = ShadowServerCompat.noServices();

    /**
     * 共享只读空光层：清光路径 {@code queueSectionData(layer, sp, EMPTY)} 用——
     * 由引擎在 markNewInconsistencies 时安装为 section 的 updating 层，之后的写入都走
     * copy-on-write（changedSections 副本），本实例绝不被就地修改（同
     * MixinLayerLightSectionStorage.hassium$EMPTY_DATA_LAYER 的只读约定）。
     * <p>
     * <b>参数 2048 是刻意的非 0 默认 nibble 值，不要"顺手"改成 0</b>：{@code DataLayer(int)}
     * 的参数是默认光照值，非 0 会让 {@code propagateLightSources}/{@code propagateIncrease}
     * 认为该 section 已满而不再排任务——现有光照管线正是按这个工作量调优的
     * （{@link ShadowLightCompute#ENGINE_TASK_LOW_WATER} 水位、重注入路径
     * {@code awaitLightTaskDrain} 的 5s 上限）。改成 0 会让传播真的跑起来：
     * 2026-09-16 实测 lightTasks 越过水位 → 主线程 {@code injectChunk} 每柱白等 5s → 客户端整卡死。
     */
    private static final net.minecraft.world.level.chunk.DataLayer EMPTY_LIGHT_LAYER =
            new net.minecraft.world.level.chunk.DataLayer(2048);

    private final WorldStem stem;
    private final long worldSeed;
    /** 持久世界根（客户端缓存目录下原版存档结构；断连保存、重连复用，不删除）。 */
    private final java.nio.file.Path worldRoot;
    /**
     * 注入区块表（{@link DimensionKey} 复合键 → LevelChunk）：网络/读盘 decode 后的
     * FULL 柱。柱子靠**外部票**驱动进 ChunkMap（虚拟玩家 tracking 的 PLAYER 票，
     * 以及 P5 起 {@link ShadowTicketDriver} 按权威声明集合出的 FORCED 票）——
     * 本类自身不出票。票一到位 {@code scheduleChunkLoad} 即短路为
     * {@code ImposterProtoChunk}。探活结论：
     * 不能赌金字塔对 FULL+{@code !isLightCorrect} 柱只重跑 LIGHT（邻柱无盘会
     * worldgen），算光仍走 {@code ShadowLightCompute} 官方 {@code initializeLight}+
     * {@code lightChunk}。打包与 saveAll 仍取本表；REPLACE 覆盖。
     * <p>
     * 键 = {@code DimensionKey.key(dimension, x, z)} 复合键（高 12 位维度 id + 低 52 位
     * 对称坐标位域）：跨维同坐标互不覆盖。
     */
    private final java.util.concurrent.ConcurrentHashMap<Long, net.minecraft.world.level.chunk.LevelChunk>
            injectedChunks = new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * 空气空壳占位柱集合（{@link DimensionKey} 复合键）：权威范围外邻柱的光照齐套占位。
     * 占位柱 = 全空气 LevelChunk，天光透过（地表正确，洞穴边缘偏亮）。
     * 不进客户端交付集、不进磁盘缓存、不参与 chunkHash；真实数据到达时由
     * {@link #injectChunk} 替换并从本集合移除。
     */
    private final java.util.Set<Long> placeholderChunks = java.util.concurrent.ConcurrentHashMap.newKeySet();

    /**
     * ChunkCache 实例 → 维度 id（懒解析后缓存；仅影子上下文使用）。
     * <p>
     * F17 根因修复：{@code MixinServerChunkCache} 的两座桥（getChunk / getChunkForLighting）
     * 原用 2 参数 {@link #injectedChunk(int, int)}（写死主世界），nether/end 注入柱命中不了桥，
     * 落回原版 {@code ServerChunkCache.getChunk} 的 {@code CompletableFuture.join()}，与影子
     * 主循环的 managedBlock 互等 ⇒ 硬死锁（Windows 判挂起关闭进程，AppHangB1 + 0xCFFFFFFF）。
     * 桥改为按本实例维度查表。三维度装配后不变，首次解析后缓存为 O(1)。
     */
    private final java.util.concurrent.ConcurrentHashMap<ServerChunkCache, String> cacheDimensions =
            new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * 按维度目录落盘的存储管理器（dimension id → manager，各绑定
     * {@link #regionDir(String)} 对应的 vanilla 布局 region 目录）。
     * 断连 saveAll 是否需要重写该柱：脏位在 {@link io.github.limuqy.mc.hassium.storage.ShadowStorageHashes}
     * （contentDirty / lightDirty / mutation / lightReady）。活数据仍在 {@link #injectedChunks} 的 LevelChunk。
     * 玩法中按 region 把已编码映像提前落盘；断连只补写尚未落盘的脏映像。
     * 磁盘只在定时 / 卸载 / saveAll 各落一次，编码路径不写盘。
     */
    private volatile java.util.concurrent.ConcurrentHashMap<String, io.github.limuqy.mc.hassium.storage.ShadowStorageManager> storages;

    /**
     * 本端是否已进入关停保存（SeedGenLevelCompat.shutdown 首步置位）：本端 saveAll 的
     * 写路径豁免 {@code ShadowServerRegistry.isPreviousShutdownComplete()} 写 gate——
     * 关停 saver 已前置等待上次关停完成（有界 30s，超时跳过保存），本端写盘与任何
     * 其他端无并发（数据安全红线：同一 mca 禁并发写）。运行期（R2 会话）恒为 false，
     * 此时写 gate 生效：R1 saveAll 未完成 → saveChunkToDisk/deleteChunk 拒绝落盘，
     * 内存驻留/比对 miss 兜底。
     */
    private volatile boolean ownShutdownInProgress;
    /** 中途变更定时入队（墙钟，影子端 tickCount 不前进）。 */
    private volatile long lastMutationFlushMs = System.currentTimeMillis();
    private static final long MUTATION_FLUSH_INTERVAL_MS = 5_000L;

    /** 关停保存开始（SeedGenLevelCompat.shutdown 首步调用）：写 gate 对本端保存放行。 */
    void beginShutdownSave() {
        this.ownShutdownInProgress = true;
    }

    private boolean canWriteStorage() {
        return ownShutdownInProgress
                || ShadowServerRegistry.getInstance().isPreviousShutdownComplete();
    }

    private ShadowSeedServer(Thread thread,
                             LevelStorageSource.LevelStorageAccess access,
                             PackRepository repo,
                             WorldStem stem,
                             long seed,
                             java.nio.file.Path worldRoot) {
        super(thread, access, repo, stem, Proxy.NO_PROXY, DataFixers.getDataFixer(), NO_SERVICES,
                SeedGenLevelCompat.chunkProgressArg());
        this.stem = stem;
        this.worldSeed = seed;
        this.worldRoot = worldRoot;
    }

    static ShadowSeedServer create(Thread thread,
                                   LevelStorageSource.LevelStorageAccess access,
                                   PackRepository repo,
                                   WorldStem stem,
                                   long seed,
                                   java.nio.file.Path worldRoot) {
        return new ShadowSeedServer(thread, access, repo, stem, seed, worldRoot);
    }

    @Override
    public boolean initServer() {
        // 影子服务端上下文：通常已在 SeedGenLevelCompat.assembleShadowServer
        // （WorldLoader 前）置位；此处幂等确保 createLevels 期间 RegionFile gate 有效。
        io.github.limuqy.mc.hassium.server.RuntimeServerContext.setShadowServer(true);
        io.github.limuqy.mc.hassium.storage.ShadowStorageHashes.clear();
        SectionDeltaSnapshots.clear();
        this.setPlayerList(ShadowServerCompat.createPlayerList(this, this.playerDataStorage));
        long t0Ns = System.nanoTime(); // T0b 诊断：initServer 各阶段耗时
        this.loadLevel();
        long t1Ns = System.nanoTime();
        // 缓存清理热度索引加载（跨会话累计；损坏/缺失 → 空索引）
        ShadowCacheEviction.load(worldRoot);
        long t2Ns = System.nanoTime();
        this.storages = new java.util.concurrent.ConcurrentHashMap<>();
        for (ServerLevel lvl : getAllLevels()) {
            String dim = dimensionId(lvl);
            this.storages.put(dim, new io.github.limuqy.mc.hassium.storage.ShadowStorageManager(
                    dim,
                    regionDir(dim),
                    pos -> serializeInjectedColumn(dim, pos),
                    key -> injectedChunks.containsKey(DimensionKey.key(dim, key)),
                    io.github.limuqy.mc.hassium.config.HassiumConfigService.getInstance()
                            .getStorageCompressionLevel()));
        }
        DebugLogger.info(DebugLogger.LogType.ASYNC,
                "[SHADOW-DIAG] initServer: loadLevel={}ms evictionLoad={}ms (seed={})",
                (t1Ns - t0Ns) / 1_000_000L, (t2Ns - t1Ns) / 1_000_000L, worldSeed);
        LOGGER.info("Hassium: Shadow seed server started (seed={})", worldSeed);
        return true;
    }

    @Override
    protected void loadLevel() {
        this.worldData.setModdedInfo(this.getServerModName(), this.getModdedStatus().shouldReportAsModified());
        long tCreateNs = System.nanoTime();
        // 豁免：createLevels 1.20.1 有参 / 1.21.1 工厂 / 1.21.9 无参，且为 MinecraftServer.protected
#if MC_VER < MC_1_21_1
        this.createLevels(new net.minecraft.server.level.progress.LoggerChunkProgressListener(11));
#elif MC_VER < MC_1_21_9
        this.createLevels(net.minecraft.server.level.progress.LoggerChunkProgressListener.create(11));
#else
        this.createLevels();
#endif
        long tAfterLevelsNs = System.nanoTime();
        this.forceDifficulty();
        // 不调用 prepareLevels()：不等待 441 ticking 区块，按需生成
        saveWorldData();
        DebugLogger.info(DebugLogger.LogType.ASYNC,
                "[SHADOW-DIAG] loadLevel: createLevels={}ms forceDifficulty+saveWorldData={}ms (seed={})",
                (tAfterLevelsNs - tCreateNs) / 1_000_000L,
                (System.nanoTime() - tAfterLevelsNs) / 1_000_000L,
                worldSeed);
    }

    /**
     * 用原版 {@code LevelStorageAccess.saveDataTag} 写出 {@code level.dat}
     * （含 WorldOptions 种子）。须在 storageSource 仍打开时调用。
     * 这样 {@code hassium_cache/<id>/world} 可直接拷到 {@code saves/} 当单机存档。
     */
    void saveWorldData() {
        try {
            this.storageSource.saveDataTag(this.registryAccess(), this.worldData);
            LOGGER.info("Hassium: Shadow level.dat saved (seed={})", worldSeed);
        } catch (Throwable t) {
            LOGGER.warn("Hassium: Shadow level.dat save failed", t);
        }
    }

    /**
     * 单区块生成超时：原版 worldgen 卡死时兜底回退。
     * <p>
     * 原版首启（{@code MinecraftServer.prepareLevels}）对出生点加 {@code START} 票（半径 11）
     * 并在主线程 {@code waitUntilNextTick} 等到 {@code getTickingGenerated()==441}，由 ticket
     * 系统限速推进，几乎不因「等 future」失败。影子端声明驱动是多路 {@code getChunkFuture(FULL)}
     * 并发 + 主循环 mailbox 推进，金字塔邻柱互抢时 3s 会在饱和窗口误判失败（test2/seedfix
     * 实测失败率 ~25–50%）。抬到 15s：远大于单柱正常 FULL，仍远小于会话时长；超时后仍回退网络。
     */
    private static final long GENERATION_TIMEOUT_NANOS = 15_000_000_000L;

    /** 仅按请求生成目标 FULL 区块；不再由影子端主动扩展邻域。 */
    public LevelChunk generateChunk(ChunkPos pos) {
        return generateChunk(DimensionKey.OVERWORLD, pos);
    }

    /**
     * 在指定维度生成一个服务端请求的 FULL 区块。
     * 原版 ChunkMap 会为生成步骤自行处理依赖区块；影子端不额外预生成 halo。
     * <p>
     * <b>调用方必须已在影子主循环钉好 FORCED 票</b>（见 {@link #pinForcedTicket}）：
     * vanilla {@code getChunkFuture(load=true)} 临时加的是 {@code TicketType.UNKNOWN}
     * （timeout=**1 tick**），异步 sleep 等待会过期 → holder 降级 → future 以
     * {@code UNLOADED_CHUNK} 立即完成。票只能在主循环增删，worker 动 DistanceManager
     * 会弄坏距离图（实测 {@code LeveledPriorityQueue} NSEE）。
     */
    public LevelChunk generateChunk(String dimension, ChunkPos pos) {
        ServerLevel level = level(dimension);
        if (level == null) {
            return null;
        }
        ServerChunkCache cache = (ServerChunkCache) level.getChunkSource();
        long deadline = System.nanoTime() + GENERATION_TIMEOUT_NANOS;
        ChunkAccess chunk = generateChunkInternal(cache, pos, false, deadline);
        // scheduleChunkLoad 短路会以 ImposterProtoChunk 完成 FULL future（已注入/盘命中）；
        // 它不是 LevelChunk 子类，直接 instanceof 会把「已有数据」误判成生成失败。
        return ShadowChunkMapCompat.unwrapLevelChunk(chunk);
    }

    /**
     * 生成窗口内钉住目标柱（FORCED，radius 0）。<b>仅影子主循环线程可调</b>。
     * 对齐 {@code prepareLevels} 的 START 票：durable 票覆盖异步等待，完成后再撤。
     */
    public static boolean pinForcedTicket(ServerLevel level, ChunkPos pos) {
        if (level == null || pos == null) {
            return false;
        }
        try {
#if MC_VER < MC_1_21_5
            level.getChunkSource().addRegionTicket(
                    net.minecraft.server.level.TicketType.FORCED, pos, 0, pos);
#else
            ((io.github.limuqy.mc.hassium.mixin.ServerChunkCacheAccessor) (Object) level.getChunkSource())
                    .hassium$getTicketStorage()
                    .addTicketWithRadius(net.minecraft.server.level.TicketType.FORCED, pos, 0);
#endif
            return true;
        } catch (Throwable t) {
            LOGGER.warn("Hassium: pin FORCED ticket failed ({}, {})", pos.x, pos.z, t);
            return false;
        }
    }

    /** 与 {@link #pinForcedTicket} 成对；仅影子主循环线程可调。 */
    public static void unpinForcedTicket(ServerLevel level, ChunkPos pos) {
        if (level == null || pos == null) {
            return;
        }
        try {
#if MC_VER < MC_1_21_5
            level.getChunkSource().removeRegionTicket(
                    net.minecraft.server.level.TicketType.FORCED, pos, 0, pos);
#else
            ((io.github.limuqy.mc.hassium.mixin.ServerChunkCacheAccessor) (Object) level.getChunkSource())
                    .hassium$getTicketStorage()
                    .removeTicketWithRadius(net.minecraft.server.level.TicketType.FORCED, pos, 0);
#endif
        } catch (Throwable ignored) {
            // world 已停：票随实例销毁
        }
    }

    /**
     * ③ 声明驱动本地生成的投递线程池（小池：worldgen 本身在 ChunkMap 生成链的
     * {@code ShadowWorldgenExecutor} worker 上跑，这里只是等 future + 回主循环）。
     */
    private final java.util.concurrent.ExecutorService localGenExecutor =
            java.util.concurrent.Executors.newFixedThreadPool(4, r -> {
                Thread t = new Thread(r, "hassium-local-gen");
                t.setDaemon(true);
                return t;
            });

    /**
     * 声明驱动本地生成（③，2026-09-13）：worker 线程执行 {@link #generateChunk}（同步等 vanilla
     * 生成链 future，由影子主循环 pollTask 驱动完成），完成后投递主循环执行物化桥回调 —— 绕过
     * 1.20.1 {@code playerLoadedChunk} 的虚拟玩家 tracking 依赖（接管态 tracking 钝化视距 1；
     * FORCED 票在影子端不被 ChunkMap tick 消化成生成任务，本地生成只能显式触发）。
     * 生成失败（null / 超时）以 null 回调，调用方回退网络 FULL。
     * <p>
     * <b>必须从影子主循环调用</b>：在此钉 FORCED 票（UNKNOWN 仅 1 tick，撑不过异步等待），
     * 回调里撤票。DistanceManager 非线程安全，禁止在 local-gen worker 上增删票。
     */
    public void generateChunkAsync(String dimension, ChunkPos pos,
            java.util.function.BiConsumer<String, LevelChunk> onDone) {
        ServerLevel pinLevel = level(dimension);
        pinForcedTicket(pinLevel, pos);
        localGenExecutor.execute(() -> {
            LevelChunk chunk = generateChunk(dimension, pos);
            try {
                this.execute(() -> {
                    try {
                        onDone.accept(dimension, chunk);
                    } finally {
                        unpinForcedTicket(pinLevel, pos);
                    }
                });
            } catch (java.util.concurrent.RejectedExecutionException e) {
                // 主循环已停（断连竞态）：票随影子世界销毁
            }
        });
    }

    /** 单块按 FULL 状态生成；失败返回 null，由调用方回退全量请求。 */
    private ChunkAccess generateChunkInternal(ServerChunkCache cache, ChunkPos pos, boolean biomesOnly, long deadline) {
        ShadowChunkMapCompat.enterWorldgen();
        try {
            return ShadowServerCompat.awaitGeneratedChunk(cache, pos, biomesOnly, deadline);
        } finally {
            ShadowChunkMapCompat.leaveWorldgen();
        }
    }

    /** shutdown 用：资源引用 */
    WorldStem stem() {
        return stem;
    }



    /**
     * 注入一个服务端区块包（任意线程可调）：空壳 LevelChunk（不 worldgen）+
     * packet 数据填充（含 {@code initializeLightSources} 重填 sky 光源表）+ 清光触发引擎
     * 传播重算。
     * <p>
     * 影子端是冻结后端：区块数据只来源于服务端 packet（{@code replaceWithPacketData}
     * 整柱替换），本 server 不生成世界。种子仅用于 ServerLevel 装配，不影响注入数据。
     * <p>
     * 把剥光 S2C 包 decode 成 {@code LevelChunk} 写入注入表，并加 UNKNOWN LIGHT 票
     * 进入 ChunkMap（{@code scheduleChunkLoad} 短路为允许读取真实 sections 的
     * {@code ImposterProtoChunk}）。
     * <p>
     * 原版 {@code ChunkStatus.LIGHT} future 负责 INITIALIZE_LIGHT/LIGHT 任务和邻柱
     * holder 依赖；影子端不请求 FULL，因此不会把无盘邻柱送入 GENERATION_PYRAMID。
     * 重注入只标记 {@code lightCorrect=false}，由同一 LIGHT future 重新计算，不直接
     * 清理或排水 {@code ThreadedLevelLightEngine}。注入失败返回 false（调用方走单柱兜底）。
     */
    /** 注入常规 packet-backed 区块。 */
    public boolean injectChunk(ChunkPos pos, ClientboundLevelChunkWithLightPacket packet) {
        return injectChunk(DimensionKey.OVERWORLD, pos, packet);
    }
    /** 统一 packet-backed pre-LIGHT 入口；来源必须是权威 packet 快照。 */
    public boolean injectPreLight(String dimension, ChunkPos pos,
                                  ClientboundLevelChunkWithLightPacket packet,
                                  ShadowChunkSource source) {
        if (source == null || !source.isPacketSnapshot()) {
            return false;
        }
        return injectChunk(dimension, pos, packet);
    }


    public boolean injectChunk(String dimension, ChunkPos pos, ClientboundLevelChunkWithLightPacket packet) {
        long key = DimensionKey.key(dimension, pos.x, pos.z);
        LevelChunk previous = this.injectedChunks.get(key);
        try {
            ServerLevel level = level(dimension);
            if (level == null) {
                LOGGER.warn("Hassium: Shadow inject skipped for ({}, {}): dimension {} not assembled",
                        pos.x, pos.z, dimension);
                return false;
            }
            boolean fresh = previous == null;
            LevelChunk chunk = ShadowLightCompute.withChunkLock(pos, () ->
                    decodeInjectedPacketLocked(dimension, key, pos, level, packet));
            ShadowLightProbe.onInjected(dimension, pos, chunk);
            if (!fresh) {
                clearChunkLight(pos, chunk);
            }
            io.github.limuqy.mc.hassium.storage.ShadowStorageHashes.markContentDirty(key);
            chunk.setLightCorrect(false);
            ShadowCacheEviction.recordAccess(dimension, pos);
            if (!fresh) {
                drainAfterClear(level);
            }
            // 悬置柱放行：数据到位后原版加载链恢复推进（LIGHT→FULL→playerLoadedChunk 桥，
            // R2 重连比对触达的前提；holder 永卡 EMPTY 会让 tracking 静默失明）
            ShadowChunkMapCompat.completeSuspendedLoad(dimension, pos, chunk);
            if (clearPlaceholder(dimension, pos, key)) {
                ShadowLightCompute.notePlaceholderReplaced(this, dimension, pos);
            }
            return true;
        } catch (Throwable t) {
            ShadowLightCompute.withChunkLock(pos, () -> restoreInjectedChunk(key, previous));
            LOGGER.warn("Hassium: Shadow light inject failed for {}", pos, t);
            return false;
        }
    }

    /**
     * 空壳入表 + {@code replaceWithPacketData} + hash/快照。调用方必须已持
     * {@code chunkLock}：入表发生在 decode 完成前，region worker 会立刻
     * {@code ChunkSerializer.pack} 同一份 PalettedContainer。
     */
    private LevelChunk decodeInjectedPacketLocked(String dimension, long key, ChunkPos pos,
                                                  ServerLevel level,
                                                  ClientboundLevelChunkWithLightPacket packet) {
        LevelChunk chunk = new LevelChunk(level, pos); // 空壳，不 worldgen
        // FULL 票扩散后本柱可能已有 ProtoChunk holder。replaceWithPacketData 会走
        // initializeLightSources / BE → ServerLevel.getChunk，把 Proto 强转 LevelChunk。
        // 必须先让 getChunk mixin 命中本柱，decode 失败再还原。
        this.injectedChunks.put(key, chunk);
        ClientboundLevelChunkPacketData data = packet.getChunkData();
        // 显式把 section 读游标复位到 0：getReadBuffer() 返回的是包内 byte[] 的
        // 新包装，正常应本来就是 0，但这里不依赖该假设——防止某些 Netty/FriendlyByteBuf
        // 路径把 readerIndex 留在尾部导致整柱 section 全部被跳过（区块只剩高度图/空气）。
        FriendlyByteBuf sectionBuf = data.getReadBuffer();
        sectionBuf.readerIndex(0);
        int sectionBytes = sectionBuf.readableBytes();
        chunk.replaceWithPacketData(sectionBuf, data.getHeightmaps(),
                data.getBlockEntitiesTagsConsumer(pos.x, pos.z));
        int nonAirSections = countNonAirSections(chunk);
        // 防御：包内确实有 section 字节却解析成整柱空气时，用全新的 LevelChunk 和
        // 全新的 read buffer 再试一次（不沿用可能已被推进/污染的包装）。
        if (nonAirSections == 0 && sectionBytes > 0) {
            DebugLogger.warn(DebugLogger.LogType.ASYNC,
                    "[SHADOW_INJECT] Empty sections detected for ({}, {}), retrying with fresh read buffer "
                            + "(sections={}, sectionBytes={}, heightmapKeys={})",
                    pos.x, pos.z, chunk.getSections().length, sectionBytes, data.getHeightmaps().size());
            chunk = new LevelChunk(level, pos);
            this.injectedChunks.put(key, chunk);
            FriendlyByteBuf retryBuf = data.getReadBuffer();
            retryBuf.readerIndex(0);
            chunk.replaceWithPacketData(retryBuf, data.getHeightmaps(),
                    data.getBlockEntitiesTagsConsumer(pos.x, pos.z));
            nonAirSections = countNonAirSections(chunk);
        }
        DebugLogger.debug(DebugLogger.LogType.ASYNC,
                "[SHADOW_INJECT] pos=({},{}) sections={} nonAirSections={} sectionBytes={} heightmapKeys={}",
                pos.x, pos.z, chunk.getSections().length, nonAirSections, sectionBytes,
                data.getHeightmaps().size());
        // 网络注入：packet 内容可能与磁盘表 hash 不同，必须现算并覆盖。
        // 读盘柱走 injectLoadedChunk，hash 已由 MixinRegionFile 回填，禁止在此重复现算。
        try {
            long contentHash = io.github.limuqy.mc.hassium.cache.ChunkContentHashUtil
                    .combineSectionHashes(io.github.limuqy.mc.hassium.cache.ChunkContentHashUtil
                            .computeSectionHashes(chunk));
            io.github.limuqy.mc.hassium.storage.ShadowStorageHashes.put(dimension, pos, contentHash);
        } catch (Throwable hashError) {
            LOGGER.debug("Hassium: Shadow contentHash compute failed for {}, skip hash write", pos);
        }
        SectionDeltaSnapshots.put(dimension, pos, SectionDeltaSnapshot.capture(chunk));
        return chunk;
    }

    private static int countNonAirSections(LevelChunk chunk) {
        int nonAirSections = 0;
        for (net.minecraft.world.level.chunk.LevelChunkSection section : chunk.getSections()) {
            if (!section.hasOnlyAir()) {
                nonAirSections++;
            }
        }
        return nonAirSections;
    }

    private void restoreInjectedChunk(long key, LevelChunk previous) {
        if (previous != null) {
            this.injectedChunks.put(key, previous);
        } else {
            this.injectedChunks.remove(key);
        }
    }

    /**
     * 在影子服务端主线程、注入票已生效后请求原版 ChunkHolder 3×3 LIGHT future。
     * 影子柱只需要原版光照步骤；使用 FULL 会向邻柱扩散并误触发地形 worldgen。
     * injectChunk 允许后台调用，不能在其返回线程直接请求，否则会早于
     * registerInjectTicket 的队列任务而看见未建 holder。
     */
    public java.util.concurrent.CompletableFuture<LevelChunk> requestNativeLight(
            String dimension, ChunkPos pos) {
        java.util.concurrent.CompletableFuture<LevelChunk> result =
                new java.util.concurrent.CompletableFuture<>();
        this.execute(() -> {
            try {
                ServerLevel level = level(dimension);
                if (level == null || injectedChunk(dimension, pos.x, pos.z) == null) {
                    result.complete(null);
                    return;
                }
                io.github.limuqy.mc.hassium.compat.ShadowServerCompat
                        .requestLightChunk(level.getChunkSource(), pos)
                        .whenComplete((ignored, failure) -> {
                            if (failure != null) {
                                result.completeExceptionally(failure);
                            } else {
                                result.complete(injectedChunk(dimension, pos.x, pos.z));
                            }
                        });
            } catch (Throwable failure) {
                result.completeExceptionally(failure);
            }
        });
        return result;
    }

    /**
     * 清光公共方法（重注入 / relightChunk 共用）：把整柱全部 section 光数据层强制覆盖为
     * 共享空层（{@code queueSectionData(layer, sp, EMPTY)}，markNewInconsistencies 时安装），
     * 并撤销 sky 光源注册——随后由官方 {@code initializeLight} + {@code lightChunk}
     * （{@code propagateLightSources} 播种 fill + 向下衰减传播）重建。
     * 全新柱勿调（无引擎状态，见 injectChunk）。
     * <p>
     * 为什么不用 {@code queueSectionData(null)} + {@code updateSectionStatus(notReady=true)}：
     * 原版 removeSection 只在 section 邻域计数归零时物理删层（边缘 section 的 26 邻域含
     * 相邻柱 → 永不归零 → 旧层残留 = 清光形同虚设，旧光/变黑场景不收敛）；且重建时
     * lightOnInSection=true 会走 {@code new DataLayer(15)} 初始化——位于天空源以下的
     * section 被 15 填满后 propagation 只增不减（`$$10 > $$7` 严格大于），水面垂直梯度
     * 永久丢失。强制覆盖空层则无状态机依赖：空层由播种 fill + 传播按块重新写入。
     * <p>
     * 全程持有 {@link ShadowLightCompute#LIGHT_ENGINE_MUTEX}：清光投递与光屏障
     * （{@code ShadowLightCompute.submitLightBatch} 的 lightChunk）互斥，否则相邻柱的
     * 传播与清光在引擎 Worker 线程交错（2026-08-14 1.20.1 定位，见锁 javadoc）。
     * 锁内仅投递任务（addTask，微秒级）。
     */
    private void clearChunkLight(ChunkPos pos, LevelChunk chunk) {
        if (!io.github.limuqy.mc.hassium.compat.mods.ForeignLightEngine
                .usesSectionDataClear(chunkLevel(chunk).getChunkSource().getLightEngine())) {
            // 外部光照引擎（Starlight / ScalableLux）把 queueSectionData 覆写为 no-op，
            // 清光不再需要：调用方 relightChunk 已先置 lightCorrect=false，
            // 随后 lightChunk(lit=false) 会走对方的全量重算并覆写 nibble。
            return;
        }
        synchronized (ShadowLightCompute.LIGHT_ENGINE_MUTEX) {
            boolean hasSky = chunkLevel(chunk).dimensionType().hasSkyLight();
            ThreadedLevelLightEngine lightEngine =
                    (ThreadedLevelLightEngine) chunkLevel(chunk).getChunkSource().getLightEngine();
            // 含上下各一层 padding。只清 chunk 实际 section 会残留 padding 光，边界传播时
            // 读到陈旧数据（视距边缘/水面上下边缘黑块来源之一）。
            for (int y = lightEngine.getMinLightSection(); y < lightEngine.getMaxLightSection(); y++) {
                SectionPos sp = SectionPos.of(pos, y);
                if (hasSky) {
                    lightEngine.queueSectionData(LightLayer.SKY, sp, EMPTY_LIGHT_LAYER);
                }
                lightEngine.queueSectionData(LightLayer.BLOCK, sp, EMPTY_LIGHT_LAYER);
            }
        }
    }

    /**
     * 增量算光清光（LightDelta 消费）：只清服务端声明发生变化的 section——
     * 含「变为全空」的 empty 掩码（旧 LightDelta 协议缺失该信息，现已 append 补发）。
     * <p>
     * 清光方式 = 强制覆盖共享空层（{@link #EMPTY_LIGHT_LAYER}，见 clearChunkLight 注释：
     * notReady 移除路径对带邻域的 section 永不生效）。随后由两阶段光屏障的
     * initializeLight 先安装空层、lightChunk 再执行 propagateLightSources——位于源的
     * section 被播种 fill 重填、源以下经向下衰减传播重写（水面垂直梯度重建）。
     * 这里不提前 propagate（提前写旧层会在 markNewInconsistencies 安装空层时被覆盖白算）。
     * <p>
     * 返回 false 表示目标柱未注入（可能已卸载/尚未到达）；调用方只需标脏，
     * 后续 R2 读盘命中会走 relight 链，不会把旧光当权威光复用。
     */
    public boolean invalidateLightSections(ChunkPos pos,
                                           java.util.BitSet skyMask,
                                           java.util.BitSet blockMask,
                                           java.util.BitSet emptySkyMask,
                                           java.util.BitSet emptyBlockMask) {
        return invalidateLightSections(DimensionKey.OVERWORLD, pos, skyMask, blockMask, emptySkyMask, emptyBlockMask);
    }

    /** 指定维度增量算光清光（LightDelta 消费）。 */
    public boolean invalidateLightSections(String dimension, ChunkPos pos,
                                           java.util.BitSet skyMask,
                                           java.util.BitSet blockMask,
                                           java.util.BitSet emptySkyMask,
                                           java.util.BitSet emptyBlockMask) {
        LevelChunk chunk = this.injectedChunks.get(DimensionKey.key(dimension, pos.x, pos.z));
        if (chunk == null) {
            return false;
        }
        // 光增量会改写引擎光照，saveAll 序列化时从引擎读光，必须把该柱标脏重写。
        io.github.limuqy.mc.hassium.storage.ShadowStorageHashes.markLightDirty(dimension, pos);
        if (!io.github.limuqy.mc.hassium.compat.mods.ForeignLightEngine
                .usesSectionDataClear(chunkLevel(chunk).getChunkSource().getLightEngine())) {
            // 外部光照引擎下 queueSectionData 为 no-op：跳过分段清光即可。
            // 调用方（LightDelta 消费）随后以 LightMetric.RECOMPUTE（lit=false）提交该柱，
            // 对方的 lightChunk 会整柱重算，语义等价且更省。
            return true;
        }
        synchronized (ShadowLightCompute.LIGHT_ENGINE_MUTEX) {
            ServerLevel owner = chunkLevel(chunk);
            ThreadedLevelLightEngine lightEngine =
                    (ThreadedLevelLightEngine) owner.getChunkSource().getLightEngine();
            boolean hasSky = owner.dimensionType().hasSkyLight();
            int minLight = lightEngine.getMinLightSection();
            int lightCount = lightEngine.getLightSectionCount();
            java.util.TreeSet<Integer> touched = new java.util.TreeSet<>();
            hassium$collectLightBits(skyMask, minLight, lightCount, touched);
            hassium$collectLightBits(blockMask, minLight, lightCount, touched);
            hassium$collectLightBits(emptySkyMask, minLight, lightCount, touched);
            hassium$collectLightBits(emptyBlockMask, minLight, lightCount, touched);
            if (touched.isEmpty()) {
                return true; // 服务端异常空包：无可清 section，不投递空传播
            }
            for (int y : touched) {
                SectionPos sp = SectionPos.of(pos, y);
                int bit = y - minLight;
                if (hasSky && (skyMask.get(bit) || emptySkyMask.get(bit))) {
                    lightEngine.queueSectionData(LightLayer.SKY, sp, EMPTY_LIGHT_LAYER);
                }
                if (blockMask.get(bit) || emptyBlockMask.get(bit)) {
                    lightEngine.queueSectionData(LightLayer.BLOCK, sp, EMPTY_LIGHT_LAYER);
                }
            }
            // 不在这里 propagateLightSources：两阶段屏障的 initializeLight 先把排队的
            // 共享空层安装（markNewInconsistencies），随后的 lightChunk 播种/传播才写
            // 在空层上。这里提前 propagate 会先写旧层、随后被空层安装覆盖 = 一轮白算，
            // 且多投 49+ 个 PRE 任务推高 lightTasks 水位。
        }
        return true;
    }

    /** BitSet 位索引（相对 minLightSection）→ 绝对 sectionY，越界位丢弃。 */
    private static void hassium$collectLightBits(java.util.BitSet mask, int minLight, int lightCount,
                                                 java.util.TreeSet<Integer> out) {
        for (int bit = mask.nextSetBit(0); bit >= 0 && bit < lightCount; bit = mask.nextSetBit(bit + 1)) {
            out.add(minLight + bit);
        }
    }

    /** 原版 LIGHT future 的唯一完成标志，并校验引擎层确实已安装。 */
    public boolean isChunkLightComplete(ChunkPos pos, LevelChunk chunk) {
        try {
            if (chunk == null || !chunk.isLightCorrect()) {
                return false;
            }
            return hasCompleteLightLayers(pos, chunk);
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * 引擎里是否有**非空**可用光（非仅 DataLayer 对象存在）。
     * {@link #hasCompleteLightLayers} 只判 null：INITIALIZE_LIGHT 装上的全 0 空层
     * 会误判为完整，REUSE 打包出 {@code skyTop=0} 黑柱。探针失败时返回 true
     * （不阻塞旧路径）；有天光维度看高空/地表 sky，无天光看 block。
     */
    public boolean hasUsableEngineLight(ChunkPos pos, LevelChunk chunk) {
        if (chunk == null || pos == null) {
            return false;
        }
        try {
            ServerLevel level = chunkLevel(chunk);
            net.minecraft.world.level.lighting.LevelLightEngine lightEngine =
                    level.getChunkSource().getLightEngine();
            int bx = pos.getMinBlockX() + 8;
            int bz = pos.getMinBlockZ() + 8;
            // 高度 API 跨版本（1.21.2 起 getMinY/getMaxY）：一律走 LevelHeightCompat。
            int minBlockY = io.github.limuqy.mc.hassium.compat.LevelHeightCompat.getMinBlockY(level);
            int highY = io.github.limuqy.mc.hassium.compat.LevelHeightCompat
                    .getMaxBlockYExclusive(level) - 2;
            BlockPos highPos = new BlockPos(bx, highY, bz);
            if (level.dimensionType().hasSkyLight()) {
                // 先看高空（世界最高格 -2）再回落地表：高空采样成本低、对绝大多数柱直接成立。
                // 注意：只填了顶部的半成品柱会在此短路为"可用"——这是**刻意保留**的旧行为，
                // 改成只探地表会让 isLightReusable 大量为假 → 每次多排 2 轮光屏障 →
                // lightTasks 越水位 → 重注入路径主线程 5s 忙等（2026-09-16 实测卡死）。
                if (lightEngine.getLayerListener(LightLayer.SKY).getLightValue(highPos) > 0) {
                    return true;
                }
                int top = chunk.getHeight(Heightmap.Types.WORLD_SURFACE, 8, 8);
                int y = Math.min(Math.max(top + 1, minBlockY + 1), highY);
                return lightEngine.getLayerListener(LightLayer.SKY)
                        .getLightValue(new BlockPos(bx, y, bz)) > 0;
            }
            if (lightEngine.getLayerListener(LightLayer.BLOCK).getLightValue(highPos) > 0) {
                return true;
            }
            int top = chunk.getHeight(Heightmap.Types.MOTION_BLOCKING, 8, 8);
            int y = Math.min(Math.max(top + 1, minBlockY + 1), highY);
            return lightEngine.getLayerListener(LightLayer.BLOCK)
                    .getLightValue(new BlockPos(bx, y, bz)) > 0;
        } catch (Throwable t) {
            return true;
        }
    }

    /**
     * 整柱包交付前的**列级地表光就绪**（对齐原版 {@code light()}：{@code setLightOn(true)}
     * 只在 propagate 完成后才置位，从不把半成品当权威值发出）。
     * <p>
     * 判据 = 地表探针点（与客户端 skyTop 同点）**天光 ≥15**（开阔地表原版终值）
     * 且 section 线上确有非 0；无天光维 block 光 &gt;0。不得只判 &gt;0——半成品
     * 天光（3/8）会被当权威整柱发出，回程呈大片过暗/斑马纹。
     * <p>
     * 探针异常时返回 {@code false}（fail-closed：宁可停车也不发空光整柱）。
     */
    public boolean isColumnSurfaceLightReady(ChunkPos pos, LevelChunk chunk,
                                             ServerLevel levelOverride) {
        if (chunk == null || pos == null) {
            return false;
        }
        try {
            ServerLevel level = levelOverride != null ? levelOverride : chunkLevel(chunk);
            if (level == null) {
                return false;
            }
            net.minecraft.world.level.lighting.LevelLightEngine lightEngine =
                    level.getChunkSource().getLightEngine();
            int minBlockY = io.github.limuqy.mc.hassium.compat.LevelHeightCompat.getMinBlockY(level);
            int highY = io.github.limuqy.mc.hassium.compat.LevelHeightCompat
                    .getMaxBlockYExclusive(level) - 2;
            int top;
            net.minecraft.world.level.LightLayer layer;
            if (level.dimensionType().hasSkyLight()) {
                top = chunk.getHeight(Heightmap.Types.WORLD_SURFACE, 8, 8);
                layer = net.minecraft.world.level.LightLayer.SKY;
            } else {
                top = chunk.getHeight(Heightmap.Types.MOTION_BLOCKING, 8, 8);
                layer = net.minecraft.world.level.LightLayer.BLOCK;
            }
            int y = Math.min(Math.max(top + 1, minBlockY + 1), highY);
            int sectionY = net.minecraft.core.SectionPos.blockToSectionCoord(y);
            net.minecraft.world.level.chunk.DataLayer data =
                    lightEngine.getLayerListener(layer)
                            .getDataLayerData(net.minecraft.core.SectionPos.of(pos, sectionY));
            // 与客户端探针同一点：地表上一格中心。
            // 天光维：开阔地表原版必为 15；只判 >0 会把传播中间态（3/8/11…）
            // 当权威整柱发出 → 回程「大片过暗 + 斑马纹」（邻柱收敛时刻交错）。
            // 无天光维：block 光按 >0（火把/岩浆亮度本就不固定）。
            int probe = lightEngine.getLayerListener(layer)
                    .getLightValue(new BlockPos(
                            pos.getMinBlockX() + 8, y, pos.getMinBlockZ() + 8));
            if (level.dimensionType().hasSkyLight()) {
                return probe >= 15 && SeedGenChunkCodec.hasWireLight(data);
            }
            return probe > 0 && SeedGenChunkCodec.hasWireLight(data);
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * 检查非空 section 的 block 光层，以及有天空光源 section 的 sky 光层。
     * {@code isLightCorrect} 可能先于异步 light engine 层安装完成，不能单独作为
     * R2 缓存光照复用条件。
     */
    boolean hasCompleteLightLayers(ChunkPos pos, LevelChunk chunk) {
        try {
            ServerLevel owner = chunkLevel(chunk);
            net.minecraft.world.level.lighting.LevelLightEngine lightEngine =
                    owner.getChunkSource().getLightEngine();
            boolean hasSky = owner.dimensionType().hasSkyLight();
            net.minecraft.world.level.lighting.ChunkSkyLightSources skySources =
                    hasSky ? chunk.getSkyLightSources() : null;
            int minSection = io.github.limuqy.mc.hassium.compat.LevelHeightCompat.getMinSection(chunk);
            int maxSection = io.github.limuqy.mc.hassium.compat.LevelHeightCompat.getMaxSectionExclusive(chunk);
            for (int y = minSection; y < maxSection; y++) {
                SectionPos sp = SectionPos.of(pos, y);
                net.minecraft.world.level.chunk.LevelChunkSection section =
                        chunk.getSection(chunk.getSectionIndexFromSectionY(y));
                if (section == null || section.hasOnlyAir()) {
                    continue;
                }
                if (lightEngine.getLayerListener(LightLayer.BLOCK).getDataLayerData(sp) == null) {
                    return false;
                }
                if (hasSky && lightEngine.getLayerListener(LightLayer.SKY).getDataLayerData(sp) == null
                        && io.github.limuqy.mc.hassium.compat.mods.ForeignLightEngine
                                .skySourcesReliable(lightEngine)
                        && sectionAtOrAboveAnySkySource(skySources, y,
                                io.github.limuqy.mc.hassium.compat.LevelHeightCompat.getMinBlockY(owner))) {
                    // 外部光照引擎把 ChunkSkyLightSources.update 重定向为 no-op，
                    // getLowestSourceY 数值过期 → 该子检查不可信，跳过（天空层判空仍由
                    // 上面的 getDataLayerData 承担，对方的 sky reader 在 emptinessMap
                    // 缺失时本就返回 null）。
                    return false;
                }
            }
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    static boolean sectionAtOrAboveAnySkySource(
            net.minecraft.world.level.lighting.ChunkSkyLightSources sources,
            int sectionY, int minBlockY) {
        if (sources == null) {
            return false;
        }
        int sectionTop = SectionPos.sectionToBlockCoord(sectionY) + 15;
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                int src = sources.getLowestSourceY(x, z);
                if (src >= minBlockY && src <= sectionTop) {
                    return true;
                }
            }
        }
        return false;
    }

    private static ServerLevel chunkLevel(LevelChunk chunk) {
        return (ServerLevel) chunk.getLevel();
    }
    /**
     * 磁盘命中 + hash 一致但 {@code !isLightCorrect}：本地重算光照（不请求网络全量）。
     * 清光后引擎传播期间保持 {@code isLightCorrect=false}，完成后由原版 LIGHT future 写回。
     */
    public void relightChunk(ChunkPos pos, LevelChunk chunk) {
        chunk.setLightCorrect(false);
        io.github.limuqy.mc.hassium.storage.ShadowStorageHashes
                .markLightDirty(dimensionId(chunkLevel(chunk)), pos);
        clearChunkLight(pos, chunk);
        awaitLightTaskDrain(chunkLevel(chunk));
    }
    /** 清光投递后的引擎任务水位控制，避免批量 relight 越过原版 sorter 阈值。 */
    private void awaitLightTaskDrain(ServerLevel level) {
        try {
            ThreadedLevelLightEngine lightEngine =
                    (ThreadedLevelLightEngine) level.getChunkSource().getLightEngine();
            ShadowLightCompute.awaitEngineTaskDrain(lightEngine);
        } catch (Throwable ignored) {
            // 引擎不可用时跳过排水，不阻塞影子端。
        }
    }

    /** 上次异步排水投递时刻（节流去重：间隔内不重复投递；无「任务被取消后标志位卡死」的失败模式）。 */
    private volatile long lastAsyncDrainScheduledMs;

    /** 异步排水节流间隔。 */
    private static final long ASYNC_DRAIN_MIN_INTERVAL_MS = 500L;
    /** 异步排水时长上限：远小于 smoke 强退窗口(2s)与执行器关机等待(3s)，保证挂不住关机序列。 */
    private static final long ASYNC_DRAIN_TIMEOUT_MS = 500L;

    /**
     * 清光后的水位控制，但**不得在客户端主线程上同步等**。
     * <p>
     * 主线程调用链：{@code MixinClientPacketListener.handleLevelChunkWithLight} HEAD →
     * {@code ShadowVanillaLightPipeline.submitVisible} → {@code injectPreLight} → 本方法；
     * lightTasks 越过水位时每柱白等最长 {@code CONVERGENCE_WAIT_TIMEOUT_MS}=5s = 客户端整卡死
     * （2026-09-16 判严 isLightReusable 实测）。主线程路径改为投递**节流的**短时长异步排水
     * ——「清光后把队列压回低水位」的 sorter 防错序语义保留，只是不阻塞帧；后台调用方
     * （consumeLoop 等）保持同步排水。无后台执行器（冷启动）/已停（断连竞态）时宁可漏一次
     * 排水也不阻塞主线程（漏排水的后果是任务错序窗口，由后续排水收敛）。
     */
    private void drainAfterClear(ServerLevel level) {
        net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
        if (mc == null || !mc.isSameThread()) {
            awaitLightTaskDrain(level);
            return;
        }
        long now = System.currentTimeMillis();
        if (now - lastAsyncDrainScheduledMs < ASYNC_DRAIN_MIN_INTERVAL_MS) {
            return; // 近期已有排水：本柱的清光任务会与之合并压回水位
        }
        lastAsyncDrainScheduledMs = now;
        io.github.limuqy.mc.hassium.concurrent.HassiumTaskExecutor executor =
                io.github.limuqy.mc.hassium.concurrent.HassiumTaskExecutor.getClient();
        if (executor == null || !executor.isRunning()) {
            return;
        }
        final ThreadedLevelLightEngine lightEngine;
        try {
            lightEngine = (ThreadedLevelLightEngine) level.getChunkSource().getLightEngine();
        } catch (Throwable ignored) {
            return; // 引擎不可用：跳过本次排水
        }
        try {
            // SAFE_TO_CANCEL + 500ms 上限双保险：断连清理会取消它；即便在 cancelAll 之后才
            // 投递（无人取消），也绝无可能 park 进关机窗口（flyrt16/17 实证）。
            executor.submit(() -> ShadowLightCompute.awaitEngineTaskDrain(lightEngine, ASYNC_DRAIN_TIMEOUT_MS),
                    io.github.limuqy.mc.hassium.concurrent.TaskCategory.SAFE_TO_CANCEL);
        } catch (java.util.concurrent.RejectedExecutionException ignored) {
            // 执行器已停（断连竞态）：跳过本次排水。
        }
    }


    /**
     * 应用服务端分段增量（SectionDeltaS2CPacket）到已注入区块。
     * <p>
     * 步骤（镜像 {@code replaceWithPacketData} 语义，按 delta 包裁剪）：
     * <ol>
     *   <li>变更 sections：{@code KIND_FULL} → {@code LevelChunkSection.read(buf)}；
     *       {@code KIND_BLOCKS} → {@code section.setBlockState}（禁止 {@code level.setBlock}）</li>
     *   <li>heightmaps：逐 type {@code setHeightmap}（delta 包随附服务端 rawData——
     *       直接改 section 不会自动更新高度图）</li>
     *   <li>blockEntity：服务端发整 chunk BE 快照 → 先清旧表，再镜像官方
     *       replaceWithPacketData 的 consumer（IMMEDIATE 创建 + type 校验 + load）</li>
     *   <li><b>光</b>：delta 不含光照 → 变更 section 清光（{@code queueSectionData(EMPTY)}，
     *       injectChunk 同款）→ 由调用方两阶段屏障（initializeLight → lightChunk）重算</li>
     *   <li>比对 {@code expectedChunkHash}，失败返回 false（调用方回退全量）</li>
     * </ol>
     * 完成后重算 contentHash 写存储桥，并 recapture 平面综合征 memo。
     * 仅 {@code consumeLoop} 单线程调用；失败返回 false（调用方回退全量请求）。
    public boolean applySectionDelta(ChunkPos pos, SectionDeltaS2CPacket.DeltaEntry entry) {
        return applySectionDelta(DimensionKey.OVERWORLD, pos, entry);
    }

    /** 应用指定维度服务端分段增量（consumeLoop 单线程调用）。 */
    public boolean applySectionDelta(String dimension, ChunkPos pos, SectionDeltaS2CPacket.DeltaEntry entry) {
        // 变更 section 清光投递与光屏障互斥（同 clearChunkLight；2026-08-14 NPE 同源）。
        synchronized (ShadowLightCompute.LIGHT_ENGINE_MUTEX) {
            try {
            LevelChunk chunk = injectedChunks.get(DimensionKey.key(dimension, pos.x, pos.z));
            if (chunk == null) {
                LOGGER.debug("Hassium: Shadow applySectionDelta chunk not injected ({}, {})", pos.x, pos.z);
                return false;
            }
            // 增量应用会就地覆盖 section/heightmap/BE/光，标记为需要 saveAll 重写。
            io.github.limuqy.mc.hassium.storage.ShadowStorageHashes.markContentDirty(dimension, pos);
            ServerLevel owner = chunkLevel(chunk);
            ThreadedLevelLightEngine lightEngine =
                    (ThreadedLevelLightEngine) owner.getChunkSource().getLightEngine();
            boolean hasSky = owner.dimensionType().hasSkyLight();
            // 1) sections 就地覆盖（先于 BE——BE 创建依赖新 block state）
            for (SectionDeltaS2CPacket.SectionData sd : entry.changedSections()) {
                LevelChunkSection section = chunk.getSection(sd.sectionIndex());
                FriendlyByteBuf buf = new FriendlyByteBuf(io.netty.buffer.Unpooled.wrappedBuffer(sd.blockData()));
                try {
                    if (sd.kind() == SectionDeltaS2CPacket.KIND_FULL) {
                        section.read(buf);
                    } else if (sd.kind() == SectionDeltaS2CPacket.KIND_BLOCKS) {
                        if (!applyBlockList(section, buf)) {
                            SectionDeltaSnapshots.invalidate(dimension, pos);
                            return false;
                        }
                    } else {
                        LOGGER.debug("Hassium: Shadow applySectionDelta unknown kind {} ({}, {})",
                                sd.kind(), pos.x, pos.z);
                        SectionDeltaSnapshots.invalidate(dimension, pos);
                        return false;
                    }
                } finally {
                    buf.release();
                }
                // 2) 变更 section 清光：delta 无光，旧光已过期 → 由随后的两阶段光屏障重算。
                //    强制覆盖共享空层（notReady 移除对带邻域 section 永不生效，
                //    见 clearChunkLight 注释）；不在这里 propagate（会被空层安装覆盖白算）。
                SectionPos sp = SectionPos.of(pos, chunk.getSectionYFromSectionIndex(sd.sectionIndex()));
                if (hasSky) {
                    lightEngine.queueSectionData(LightLayer.SKY, sp, EMPTY_LIGHT_LAYER);
                }
                lightEngine.queueSectionData(LightLayer.BLOCK, sp, EMPTY_LIGHT_LAYER);
            }
            // 3) heightmaps 逐 type 覆盖
            Heightmap.Types[] types = Heightmap.Types.values();
            for (SectionDeltaS2CPacket.HeightmapData hm : entry.heightmaps()) {
                if (hm.typeId() >= 0 && hm.typeId() < types.length) {
                    chunk.setHeightmap(types[hm.typeId()], hm.data());
                }
            }
            // heightmap 变化 → sky 光源表必须重算，否则 lightChunk 的播种仍按旧地形高度
            // （水面升降/填海造陆后 sky 光错位 = 暗区来源之一）。
            chunk.initializeLightSources();
            // 4) BE 全量覆盖（服务端发整 chunk BE 快照）：先清旧，再镜像官方
            // replaceWithPacketData 的 consumer（IMMEDIATE 创建 + load）。不做 type 校验：
            // IMMEDIATE 创建的 BE 类型必然匹配新 block state（delta 数据自洽），
            // 且 1.21.11 Registry.get 返回 Optional，跨版本校验成本大于收益。
            for (BlockPos bp : new ArrayList<>(chunk.getBlockEntities().keySet())) {
                chunk.removeBlockEntity(bp);
            }
            for (SectionDeltaS2CPacket.BlockEntityData bed : entry.blockEntities()) {
                net.minecraft.world.level.block.entity.BlockEntity be =
                        chunk.getBlockEntity(bed.pos(), LevelChunk.EntityCreationType.IMMEDIATE);
                if (be != null && bed.nbt() != null) {
                    BlockEntityCompat.loadFromTag(be, bed.nbt(), this.overworld().registryAccess());
                }
            }
            // 5) 重算 contentHash 写存储桥（R2 比对 / 断连落盘复用），并校验 expectedChunkHash
            try {
                long contentHash = io.github.limuqy.mc.hassium.cache.ChunkContentHashUtil
                        .combineSectionHashes(io.github.limuqy.mc.hassium.cache.ChunkContentHashUtil
                                .computeSectionHashes(chunk));
                if (contentHash != entry.expectedChunkHash()) {
                    LOGGER.debug("Hassium: Shadow applySectionDelta hash mismatch ({}, {}): got={} expected={}",
                            pos.x, pos.z, Long.toHexString(contentHash),
                            Long.toHexString(entry.expectedChunkHash()));
                    SectionDeltaSnapshots.invalidate(dimension, pos);
                    return false;
                }
                io.github.limuqy.mc.hassium.storage.ShadowStorageHashes.put(dimension, pos, contentHash);
                SectionDeltaSnapshots.put(dimension, pos, SectionDeltaSnapshot.capture(chunk));
            } catch (Throwable hashError) {
                LOGGER.debug("Hassium: Shadow contentHash recompute failed for {}, skip hash write", pos);
                SectionDeltaSnapshots.invalidate(dimension, pos);
                return false;
            }
            return true;
        } catch (Throwable t) {
            LOGGER.warn("Hassium: Shadow applySectionDelta failed for {}", pos, t);
            SectionDeltaSnapshots.invalidate(dimension, pos);
            return false;
        }
        }
    }

    private static boolean applyBlockList(LevelChunkSection section, FriendlyByteBuf buf) {
        int count = buf.readVarInt();
        if (count < 0 || count > SectionPlaneSyndrome.CELLS) {
            return false;
        }
        for (int i = 0; i < count; i++) {
            long packed = buf.readVarLong();
            int localPos = (int) (packed & SectionPlaneSyndrome.LOCAL_POS_MASK);
            int stateId = (int) (packed >>> 12);
            BlockState state = LevelChunkSectionCompat.blockStateFromId(stateId);
            if (state == null) {
                return false;
            }
            section.setBlockState(
                    SectionPlaneSyndrome.localX(localPos),
                    SectionPlaneSyndrome.localY(localPos),
                    SectionPlaneSyndrome.localZ(localPos),
                    state);
        }
        return true;
    }

    /**
     * 应用一个官方方块同步包到影子端（任意线程可调；内部 {@code execute()} 投递影子端主线程）。
     * <p>
     * 与客户端同源的服务端方块更新（T2 mixin 转发，不 cancel 原版处理）：内容变更 →
     * 缓存 hash 失效（下次比对现算）+ 光照标脏（读盘不直接打包欠光）。三类包：
     * <ul>
     *   <li>{@code ClientboundBlockUpdatePacket}：单方块，{@code level.setBlock(pos, state, 3)}</li>
     *   <li>{@code ClientboundSectionBlocksUpdatePacket}：分段批量，
     *       {@code packet.runUpdates((pos, state) -> ...)}（两版逐字一致）逐块 setBlock</li>
     *   <li>{@code ClientboundBlockEntityDataPacket}：BE 更新，
     *       {@code BlockEntityCompat.loadFromTag}（镜像原版 handler 语义）</li>
     * </ul>
     * 执行线程 = 影子端主循环线程（mainThreadProcessor 由 {@link #runMainLoop()} 驱动，
     * {@link #generateChunk} 同模式），天然串行；主循环已停（断连/关停）时投递被
     * {@code RejectedExecutionException} 兜底丢弃，无残留。
     */
    public void applyBlockUpdate(String dimension, Packet<?> packet) {
        if (packet == null) {
            return;
        }
        try {
            this.execute(() -> {
                try {
                    if (packet instanceof ClientboundBlockUpdatePacket block) {
                        applyBlock(dimension, block.getPos(), block.getBlockState());
                    } else if (packet instanceof ClientboundSectionBlocksUpdatePacket section) {
                        java.util.Set<Long> affected = new java.util.HashSet<>();
                        section.runUpdates((pos, state) -> {
                            long key = DimensionKey.key(dimension, pos.getX() >> 4, pos.getZ() >> 4);
                            LevelChunk chunk = injectedChunks.get(key);
                            if (chunk != null) {
                                ChunkPos cp = new ChunkPos(pos);
                                // 禁止 level.setBlock：会邻接更新并 scheduleTick，
                                // 影子端 ChunkMap 未加载邻柱时 1.21.2+ 刷
                                // "Trying to schedule tick in not loaded position" ERROR。
                                ShadowLightCompute.withChunkLock(cp, () ->
                                        ShadowServerCompat.setBlockState(chunk, pos, state));
                            }
                            affected.add(key);
                        });
                        invalidateChunkContent(dimension, affected);
                    } else if (packet instanceof ClientboundBlockEntityDataPacket blockEntity) {
                        applyBlockEntity(dimension, blockEntity);
                    }
                } catch (Throwable t) {
                    LOGGER.debug("Hassium: Shadow applyBlockUpdate ignored: {}", t.toString());
                }
            });
        } catch (java.util.concurrent.RejectedExecutionException e) {
            // 主循环已停（断连竞态）：更新丢弃，数据由下次进服 hash 比对/直推兜底
        }
    }

    /** 单方块应用（指定维度）：注入区块直接 setBlockState；未注入跳过并标脏。 */
    private void applyBlock(String dimension, BlockPos pos, BlockState state) {
        long key = DimensionKey.key(dimension, pos.getX() >> 4, pos.getZ() >> 4);
        LevelChunk chunk = injectedChunks.get(key);
        if (chunk != null) {
            // review-fix: T13-FixT3Chunk-2：注入区块不经 ChunkMap，level.setBlock 会触发幻影 worldgen——直接对注入 chunk 应用
            ChunkPos cp = new ChunkPos(pos.getX() >> 4, pos.getZ() >> 4);
            ShadowLightCompute.withChunkLock(cp, () ->
                    ShadowServerCompat.setBlockState(chunk, pos, state));
        }
        invalidateChunkContent(dimension, java.util.Collections.singleton(key));
    }

    /** BE 更新应用（指定维度）：镜像原版 handleBlockEntityData，无 BE 则忽略。 */
    private void applyBlockEntity(String dimension, ClientboundBlockEntityDataPacket packet) {
        ServerLevel level = this.level(dimension);
        long key = DimensionKey.key(dimension, packet.getPos().getX() >> 4, packet.getPos().getZ() >> 4);
        LevelChunk chunk = injectedChunks.get(key);
        if (chunk == null) {
            // review-fix: T13-FixT3Chunk-2：未注入——跳过应用并标脏（hash 比对触发全量回拉）
            invalidateChunkContent(dimension, java.util.Collections.singleton(key));
            return;
        }
        net.minecraft.world.level.block.entity.BlockEntity be = chunk.getBlockEntity(packet.getPos());
        if (be == null || packet.getTag() == null) {
            return;
        }
        BlockEntityCompat.loadFromTag(be, packet.getTag(), level.registryAccess());
        // Shadow world 的 BE setChanged 会触发邻居区块查询；影子端邻柱可能尚未装载。
        // 直接标记所属柱 dirty，避免 1.21.1 的 Should always be able to create a chunk!。
        ChunkDataCompat.markUnsaved(chunk);
        // BE NBT 不进 chunkHash：只标脏落盘，不要丢掉方块 hash 表（否则下次比对无谓重算）。
        io.github.limuqy.mc.hassium.storage.ShadowStorageHashes.markContentDirty(
                dimension, new ChunkPos(DimensionKey.chunkXOf(key), DimensionKey.chunkZOf(key)));
    }

    /**
    /** 内容失效（指定维度；key 集合为 DimensionKey 复合键）：移除 hash 缓存（下次比对不得误命中）。 */
    private void invalidateChunkContent(String dimension, java.util.Set<Long> chunkKeys) {
        for (long key : chunkKeys) {
            ChunkPos pos = new ChunkPos(DimensionKey.chunkXOf(key), DimensionKey.chunkZOf(key));
            io.github.limuqy.mc.hassium.storage.ShadowStorageHashes.remove(dimension, pos);
            io.github.limuqy.mc.hassium.storage.ShadowStorageHashes.markContentDirty(key);
            SectionDeltaSnapshots.invalidate(dimension, pos);
        }
    }


    /**
     * 从影子端存档（磁盘 region，type 126）加载区块——官方 {@code scheduleChunkLoad}
     * 完整链（readChunk → 126 解压（MixinRegionFile 读 hook）→ NBT 解析 → 光照恢复）。
     * 返回的 LevelChunk 带存档光（断连 saveAll 落的收敛光），直接打包即可（无需重算）。
     * <p>
     * R2 缓存命中 / OVD 环带回填共用此入口；失败或存档无此柱返回 null。
     * 注意：官方加载链的 mainThreadExecutor 步骤由 runMainLoop 的 pollTask 驱动，
     * 本方法可在任意线程调用（同步等待 future 完成，与 generateChunk 同模式）。
     */
    public LevelChunk loadFromDisk(ChunkPos pos) {
        return loadFromDisk(DimensionKey.OVERWORLD, pos);
    }

    /** 从指定维度存档（磁盘 region，type 126）加载区块。只走 {@link ShadowStorageManager}，
     * 禁止回落到原版 IOWorker：同一 .mca 被映像整文件重写后，原版扇区表会读出垃圾柱。 */
    public LevelChunk loadFromDisk(String dimension, ChunkPos pos) {
        io.github.limuqy.mc.hassium.storage.ShadowStorageManager mgr = storage(dimension);
        if (mgr == null) {
            return null;
        }
        byte[] nbt = mgr.readChunk(pos);
        if (nbt == null) {
            return null;
        }
        return parseNbtBytes(dimension, pos, nbt);
    }

    /**
     * 后台读盘 + 主线程回调。客户端主线程 miss（权威 hash 命中 / UNCHANGED）不得
     * 同步堵在 region 冷挂载与 NBT 解析上。无客户端执行器时退化为同步
     * {@link #loadFromDisk} 并在调用线程回调。
     */
    public void loadFromDiskAsync(String dimension, ChunkPos pos,
                                  java.util.function.Consumer<LevelChunk> callback) {
        io.github.limuqy.mc.hassium.concurrent.HassiumTaskExecutor executor =
                io.github.limuqy.mc.hassium.concurrent.HassiumTaskExecutor.getClient();
        if (executor == null || !executor.isRunning()) {
            LevelChunk sync = loadFromDisk(dimension, pos);
            callback.accept(sync);
            return;
        }
        executor.submit(() -> {
            LevelChunk loaded = null;
            try {
                loaded = loadFromDisk(dimension, pos);
            } catch (Throwable t) {
                LOGGER.debug("Hassium: async loadFromDisk failed for ({}, {})", pos.x, pos.z, t);
            }
            LevelChunk result = loaded;
            io.github.limuqy.mc.hassium.concurrent.MainThreadDispatcher.execute(
                    () -> callback.accept(result), pos,
                    io.github.limuqy.mc.hassium.concurrent.TaskCategory.SAFE_TO_CANCEL);
        }, io.github.limuqy.mc.hassium.concurrent.TaskCategory.SAFE_TO_CANCEL);
    }

    /** 官方加载产物为 ProtoChunk（ChunkSerializer.read 语义）：FULL 转换同款。 */
    private LevelChunk toLevelChunk(String dimension, net.minecraft.world.level.chunk.ChunkAccess accChunk, ChunkPos pos) {
        // 读盘命中（R2 缓存复用）记一次访问：容量清理热度评分用。
        // 覆盖 consumeLoop 磁盘优先路径（不经 injectLoadedChunk）与
        // processRemoteHashes 读盘比对路径（两者都经 loadFromDisk）。
        ShadowCacheEviction.recordAccess(dimension, pos);
        if (accChunk instanceof LevelChunk levelChunk) {
            return levelChunk;
        }
        ServerLevel dimLevel = this.level(dimension);
        if (dimLevel == null) {
            return null;
        }
        return new LevelChunk(dimLevel, (net.minecraft.world.level.chunk.ProtoChunk) accChunk,
                chunk -> { });
    }

    /** 注入区块表取用（主世界；未注入返回 null）。过渡期兼容签名，语义 = OVERWORLD。 */
    public net.minecraft.world.level.chunk.LevelChunk injectedChunk(int x, int z) {
        return injectedChunk(DimensionKey.OVERWORLD, x, z);
    }

    /** 指定维度注入区块表取用（打包/保存；未注入返回 null）。 */
    public net.minecraft.world.level.chunk.LevelChunk injectedChunk(String dimension, int x, int z) {
        return injectedChunks.get(DimensionKey.key(dimension, x, z));
    }

    /** 该柱是否为空气空壳占位（权威范围外邻柱的光照齐套占位）。 */
    public boolean isPlaceholder(String dimension, int x, int z) {
        return placeholderChunks.contains(DimensionKey.key(dimension, x, z));
    }

    /**
     * 注入空气空壳占位柱：全空气 LevelChunk，供光照齐套门控在权威范围外邻柱上「立即就绪」。
     * <p>
     * 语义：天光从上方灌入、水平透过（等价原版 {@code NEG_INF} 哨兵）——地表屋檐正确，
     * 洞穴边缘偏亮（原版视距边缘同样不准，可接受）。不写 hash、不写快照、不进交付集；
     * 真实数据到达时 {@link #injectChunk} REPLACE 覆盖并从占位集合移除。
     * <p>
     * 幂等：已有非占位柱时 no-op；已有占位柱时 no-op（不重复创建）。
     */
    public boolean injectPlaceholder(String dimension, int x, int z) {
        long key = DimensionKey.key(dimension, x, z);
        if (injectedChunks.containsKey(key)) {
            return true; // 已有柱（真实或占位）：齐套条件已满足
        }
        ServerLevel level = level(dimension);
        if (level == null) {
            return false;
        }
        ChunkPos pos = new ChunkPos(x, z);
        LevelChunk placeholder = new LevelChunk(level, pos);
        // 空壳 = 全空气 section，无需 replaceWithPacketData；heightmap 由引擎按需重建
        LevelChunk previous = injectedChunks.putIfAbsent(key, placeholder);
        if (previous != null) {
            return true; // 竞态：另一线程先注入
        }
        placeholderChunks.add(key);
        // 占位柱标 lightCorrect=true：全空气 = 开天空，天光从上方灌入、水平透过，
        // 对齐原版 NEG_INF 哨兵语义。邻柱算光读到的是「开天空」而非空层基岩。
        placeholder.setLightCorrect(true);
        // 必须过 INITIALIZE_LIGHT：只 put 进表时引擎无 section 状态/空层，
        // 中心柱 lightChunk 的 propagate 读邻柱会缺数据 → 空 DataLayer → skyTop=0。
        ShadowLightCompute.initializeLightImmediately(this, key, placeholder, level);
        DebugLogger.info(DebugLogger.LogType.CHUNK_APPLY,
                "[SHADOW_PLACEHOLDER] Injected air placeholder ({}, {}) dim={}", x, z, dimension);
        return true;
    }

    /**
     * 维度取 level（CONTRACTS §2）：三维度可取；未知维度返回 null。
     * {@code minecraft:the_nether} → DIM-1 level（dimensionType 无天光）。
     */
    public ServerLevel level(String dimension) {
        if (dimension == null) {
            return null;
        }
        return this.getLevel(ResourceKey.create(
                net.minecraft.core.registries.Registries.DIMENSION,
                io.github.limuqy.mc.hassium.compat.ResourceLocationCompat.create(dimension)));
    }

    /** 维度取 level（ResourceKey 形态重载；未装配维度返回 null）。 */
    public ServerLevel level(ResourceKey<net.minecraft.world.level.Level> dim) {
        return this.getLevel(dim);
    }

    /** ServerLevel 的维度 id 字符串（{@code namespace:path}；两版本 location/identifier 封装）。 */
    static String dimensionId(ServerLevel lvl) {
        return LevelCompat.getDimensionId(lvl);
    }

    /**
     * 影子上下文：ChunkCache 实例 → 维度 id。未装配/未匹配返回 null（调用方保持现状不拦截）。
     * 首次解析后缓存；解析只依赖 {@link #level(String)}（装配后只读）与
     * {@code ServerLevel.getChunkSource()}（final 字段读），跨线程安全。
     * <p>
     * 必须覆盖全部已装配维度：此前只扫三维，自定义维（AoA/ES 等）返回 null →
     * MixinServerChunkCache 防死锁桥不生效 → setBlockState→NeoForge 流体邻柱
     * getChunk managedBlock 自锁（es3 hang 实证）。
     */
    public String dimensionOfCache(ServerChunkCache cache) {
        if (cache == null) {
            return null;
        }
        String known = cacheDimensions.get(cache);
        if (known != null) {
            return known;
        }
        java.util.Set<String> dims = storageDimensions();
        if (dims.isEmpty()) {
            dims = java.util.Set.of(
                    DimensionKey.OVERWORLD, DimensionKey.NETHER, DimensionKey.END);
        }
        for (String dim : dims) {
            ServerLevel lvl = level(dim);
            if (lvl != null && lvl.getChunkSource() == cache) {
                cacheDimensions.put(cache, dim);
                return dim;
            }
        }
        return null;
    }

    /**
     * 指定维度的存储管理器（落盘/读盘/探活按维度路由）；
     * 未装配的维度返回 null（调用方降级：内存比对 miss 兜底）。
     */
    public io.github.limuqy.mc.hassium.storage.ShadowStorageManager storage(String dimension) {
        java.util.concurrent.ConcurrentHashMap<String, io.github.limuqy.mc.hassium.storage.ShadowStorageManager> map = storages;
        return map == null ? null : map.get(dimension);
    }

    /** 已装配维度 id 集合（淘汰扫描等动态消费；未初始化返回空集）。 */
    public java.util.Set<String> storageDimensions() {
        java.util.concurrent.ConcurrentHashMap<String, io.github.limuqy.mc.hassium.storage.ShadowStorageManager> map = storages;
        return map == null ? java.util.Set.of() : java.util.Collections.unmodifiableSet(map.keySet());
    }

    /**
     * 影子上下文：按 region 目录定位本端存储管理器（原版 {@code RegionFile} 归属判定）。
     * <p>
     * 原版 RegionFile 不携带维度，但携带构造时传入的 region 目录；本端三维度的 region
     * 目录与 {@code ChunkMap} 的 {@code ChunkStorage} 目录同源（同一 {@code getDimensionPath}），
     * 可直接比对。未匹配（非本影子存档的 RegionFile）返回 null，调用方回落原版写盘路径。
     */
    public io.github.limuqy.mc.hassium.storage.ShadowStorageManager storageForRegionDir(
            java.nio.file.Path dir) {
        java.util.concurrent.ConcurrentHashMap<String, io.github.limuqy.mc.hassium.storage.ShadowStorageManager> map = storages;
        if (dir == null || map == null) {
            return null;
        }
        java.nio.file.Path normalized = dir.toAbsolutePath().normalize();
        for (io.github.limuqy.mc.hassium.storage.ShadowStorageManager mgr : map.values()) {
            if (mgr.regionDir().toAbsolutePath().normalize().equals(normalized)) {
                return mgr;
            }
        }
        return null;
    }

    public boolean hasVisibleChunkHolder(int x, int z) {
        try {
            return ShadowChunkMapCompat.hasVisibleHolder(
                    (ServerChunkCache) this.overworld().getChunkSource(), x, z);
        } catch (Throwable ignored) {
            return false;
        }
    }

    /**
     * 将已从磁盘命中并校验的区块注入影子端表。
     */
    public void injectLoadedChunk(ChunkPos pos, net.minecraft.world.level.chunk.LevelChunk chunk) {
        injectLoadedChunk(DimensionKey.OVERWORLD, pos, chunk, false);
    }

    public void injectLoadedChunk(String dimension, ChunkPos pos, net.minecraft.world.level.chunk.LevelChunk chunk) {
        injectLoadedChunk(dimension, pos, chunk, false);
    }

    /**
     * 加载进影子端表并显式指定是否需要 saveAll 重写。
     * 无 hash 时回填 content hash，保证随后 compare-pull 有基线。
     *
     * @param dirty true = 本地新生成或与磁盘不一致，必须落盘；false = 磁盘 clean 命中
     */
    public void injectLoadedChunk(String dimension, ChunkPos pos,
                                  net.minecraft.world.level.chunk.LevelChunk chunk, boolean dirty) {
        if (chunk == null || pos == null) {
            return;
        }
        long key = DimensionKey.key(dimension, pos.x, pos.z);
        if (dirty) {
            io.github.limuqy.mc.hassium.storage.ShadowStorageHashes.markContentDirty(key);
        } else {
            io.github.limuqy.mc.hassium.storage.ShadowStorageHashes.claimDirty(key);
            io.github.limuqy.mc.hassium.storage.ShadowStorageHashes.markPersisted(key);
        }
        ShadowLightCompute.withChunkLock(pos, () -> {
            this.injectedChunks.put(key, chunk);
            if (io.github.limuqy.mc.hassium.storage.ShadowStorageHashes.get(dimension, pos) == null) {
                try {
                    long contentHash = io.github.limuqy.mc.hassium.cache.ChunkContentHashUtil
                            .combineSectionHashes(io.github.limuqy.mc.hassium.cache.ChunkContentHashUtil
                                    .computeSectionHashes(chunk));
                    io.github.limuqy.mc.hassium.storage.ShadowStorageHashes.put(dimension, pos, contentHash);
                } catch (Throwable ignored) {
                    // compare 无 hash 会走空基线 FULL；不得因 hash 失败丢柱
                }
            }
            SectionDeltaSnapshots.put(dimension, pos, SectionDeltaSnapshot.capture(chunk));
        });
        // 悬置柱放行（同 injectChunk）：读盘/生成柱入表即恢复原版加载链
        ShadowChunkMapCompat.completeSuspendedLoad(dimension, pos, chunk);
        if (clearPlaceholder(dimension, pos, key)) {
            ShadowLightCompute.notePlaceholderReplaced(this, dimension, pos);
        }
    }

    /**
     * 真实数据已就位：撤掉空气空壳占位标记。
     * <p>
     * 占位标记是「该柱当前内容是齐套用的临时空气壳」的判据，被下游多处（{@code publishCachedChunk}、
     * tracking 的 drainSelections / drainRedeliver / tryServeOvdLocal / onChunkMaterialized / shape sweep）
     * 用来判定「未物化」。不撤会让该柱**永久**被当未物化：缓存命中路径拒绝交付、
     * tracking 反复重拉、redeliver 永久跳过——每轮都白付一次网络往返 + 一次多余算光。
     *
     * @return true = 旧柱曾是占位（调用方应触发 8 邻光残差登记）
     */
    private boolean clearPlaceholder(String dimension, ChunkPos pos, long key) {
        if (placeholderChunks.remove(key)) {
            DebugLogger.info(DebugLogger.LogType.CHUNK_APPLY,
                    "[SHADOW_PLACEHOLDER] Cleared placeholder ({}, {}) dim={} on real data",
                    pos.x, pos.z, dimension);
            return true;
        }
        return false;
    }

    /**
     * 占位顶替后邻柱强制重算前置：清光 + {@code isLightCorrect=false}。
     * 引擎只加不减，邻柱曾按空气占位算出的过亮 DataLayer 不会自发回撤。
     */
    void forceNeighborLightReset(ChunkPos pos, LevelChunk chunk) {
        if (chunk == null || pos == null) {
            return;
        }
        clearChunkLight(pos, chunk);
        chunk.setLightCorrect(false);
    }

    /**
     * 与原版 {@code ChunkSerializer} 对齐：{@code isLightCorrect} 决定落盘是否写
     * {@code isLightOn}。热路径只标脏；定时/退出从 ChunkMap 刷当前层。Halo 不写光。
     */
    public void syncLightCorrect(LevelChunk chunk, boolean correct) {
        if (chunk == null) {
            return;
        }
        if (chunk.isLightCorrect() == correct) {
            return;
        }
        chunk.setLightCorrect(correct);
        ChunkPos pos = chunk.getPos();
        String dimension = LevelCompat.getDimensionId(chunkLevel(chunk));
        if (correct) {
            persistLightReady(dimension, pos);
        } else {
            io.github.limuqy.mc.hassium.storage.ShadowStorageHashes.markLightDirty(dimension, pos);
        }
    }


    /**
     * 单柱落盘：标脏后由 {@link io.github.limuqy.mc.hassium.storage.ShadowStorageManager}
     * 从当前 {@code LevelChunk} 序列化压缩写盘。任意线程可调。
     *
     * @return true=已提交写队列；false=写 gate 拒绝或 flush 失败
     */
    public boolean saveChunkToDisk(String dimension, ChunkPos pos, LevelChunk chunk) {
        if (!ownShutdownInProgress
                && !ShadowServerRegistry.getInstance().isPreviousShutdownComplete()) {
            return false;
        }
        io.github.limuqy.mc.hassium.storage.ShadowStorageHashes.markContentDirty(dimension, pos);
        return true;
    }



    void setPersistenceRole(String dimension, ChunkPos pos, ShadowChunkPersistenceRole role) {
        long key = DimensionKey.key(dimension, pos.x, pos.z);
    }
    public boolean saveChunkToDisk(ChunkPos pos, LevelChunk chunk) {
        return saveChunkToDisk(DimensionKey.OVERWORLD, pos, chunk);
    }

    /** 主世界存储管理器（过渡期兼容；新代码请用 {@link #storage(String)}）。 */
    public io.github.limuqy.mc.hassium.storage.ShadowStorageManager storage() {
        return storage(DimensionKey.OVERWORLD);
    }

    void closeStorage() {
        java.util.concurrent.ConcurrentHashMap<String, io.github.limuqy.mc.hassium.storage.ShadowStorageManager> map = storages;
        if (map != null) {
            for (io.github.limuqy.mc.hassium.storage.ShadowStorageManager mgr : map.values()) {
                mgr.close();
            }
        }
    }
    /** 原版序列化（版本差异封装；flush 时从 LevelChunk 取 NBT）。 */
    private net.minecraft.nbt.CompoundTag serializeChunkForSave(ServerLevel level, LevelChunk chunk) {
        return ShadowServerCompat.serializeChunk(level, chunk);
    }
    /** 指定维度注入柱序列化（flush 回调；hash 缺失时回填带维度）。
     * PalettedContainer 须与预览打包 / hash 比对持同一把 {@code chunkLock}，
     * 否则 1.20.1 ThreadingDetector 会刷 ERROR 并把 SeedGen 打包打爆。 */
    private byte[] serializeInjectedColumn(String dimension, ChunkPos pos) {
        if (io.github.limuqy.mc.hassium.storage.ShadowStorageManager.isEncodingPaused()) {
            return null;
        }
        return ShadowLightCompute.withChunkLock(pos, () -> serializeInjectedColumnLocked(dimension, pos));
    }

    private byte[] serializeInjectedColumnLocked(String dimension, ChunkPos pos) {
        if (io.github.limuqy.mc.hassium.storage.ShadowStorageManager.isEncodingPaused()) {
            return null;
        }
        LevelChunk chunk = ShadowChunkMapCompat.fullLevelChunkIfPresent(level(dimension), pos);
        if (chunk == null) {
            chunk = injectedChunks.get(DimensionKey.key(dimension, pos.x, pos.z));
        }
        if (chunk == null) {
            return null;
        }
        try {
            if (io.github.limuqy.mc.hassium.storage.ShadowStorageHashes.get(dimension, pos) == null) {
                try {
                    long contentHash = io.github.limuqy.mc.hassium.cache.ChunkContentHashUtil
                            .combineSectionHashes(io.github.limuqy.mc.hassium.cache.ChunkContentHashUtil
                                    .computeSectionHashes(chunk));
                    io.github.limuqy.mc.hassium.storage.ShadowStorageHashes.put(dimension, pos, contentHash);
                } catch (Throwable hashError) {
                    LOGGER.debug("Hassium: Shadow flush hash backfill failed for {}", pos);
                }
            }
            net.minecraft.nbt.CompoundTag nbt = serializeChunkForSave(level(dimension), chunk);
            java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
            net.minecraft.nbt.NbtIo.write(nbt, new java.io.DataOutputStream(baos));
            return baos.toByteArray();
        } catch (Throwable t) {
            LOGGER.warn("Hassium: Shadow column serialize failed for ({}, {})", pos.x, pos.z, t);
            return null;
        }
    }

    private LevelChunk parseNbtBytes(String dimension, ChunkPos pos, byte[] nbtBytes) {
        try {
            net.minecraft.nbt.CompoundTag tag = net.minecraft.nbt.NbtIo.read(
                    new java.io.DataInputStream(new java.io.ByteArrayInputStream(nbtBytes)));
            if (tag == null) {
                return null;
            }
            ServerLevel dimLevel = level(dimension);
            if (dimLevel == null) {
                return null;
            }
            net.minecraft.world.level.chunk.ChunkAccess accChunk =
                    ShadowServerCompat.parseChunkNbt(
                            dimLevel, this.storageSource.getLevelId(), pos, tag);
            if (accChunk == null) {
                return null;
            }
            return toLevelChunk(dimension, accChunk, pos);
        } catch (Throwable t) {
            LOGGER.debug("Hassium: parseNbtBytes failed for {}", pos, t);
            return null;
        }
    }

    public boolean unloadChunk(ChunkPos pos, LevelChunk chunk) {
        return unloadChunk(DimensionKey.OVERWORLD, pos, chunk, true);
    }

    /** 单柱卸载（指定维度）：脏则先 flush 该柱再摘 LevelChunk。不删盘、不卸 hash（R2 探活用）。 */
    public boolean unloadChunk(String dimension, ChunkPos pos, LevelChunk chunk) {
        return unloadChunk(dimension, pos, chunk, true);
    }

    /**
     * @param unmountIdle 为 false 时不扫 region（卸载扫描应在整批结束后 {@link #unmountIdleStorage} 一次）
     */
    public boolean unloadChunk(String dimension, ChunkPos pos, LevelChunk chunk, boolean unmountIdle) {
        // 对齐原版 light()：propagate 未完成不得把 isLightOn 固化进 NBT。
        // 全局 isLightConverged 不够——假收敛时队列空但地表仍 0，会写出 isLightOn=1+空光。
        ServerLevel unloadLevel = level(dimension);
        if (!isLightConverged(unloadLevel)
                || !isColumnSurfaceLightReady(pos, chunk, unloadLevel)) {
            chunk.setLightCorrect(false);
            io.github.limuqy.mc.hassium.storage.ShadowStorageHashes.markLightDirty(dimension, pos);
        }
        io.github.limuqy.mc.hassium.storage.ShadowStorageManager mgr = storage(dimension);
        if (mgr != null
                && io.github.limuqy.mc.hassium.storage.ShadowStorageHashes
                        .isDirty(DimensionKey.key(dimension, pos.x, pos.z))
                && !mgr.flushColumn(pos,
                io.github.limuqy.mc.hassium.storage.ShadowStorageManager.DEFAULT_FLUSH_TIMEOUT_MS)) {
            return false;
        }
        long key = DimensionKey.key(dimension, pos.x, pos.z);
        injectedChunks.remove(key, chunk);
        placeholderChunks.remove(key); // 占位柱卸载：标记随柱一起走，不得留在表里
        if (unmountIdle && mgr != null) {
            mgr.unmountIdleRegions();
        }
        return true;
    }

    /** 该维度无注入柱且无未刷脏时卸压缩映像。卸载扫描整批结束后调用一次。 */
    public void unmountIdleStorage(String dimension) {
        io.github.limuqy.mc.hassium.storage.ShadowStorageManager mgr = storage(dimension);
        if (mgr != null) {
            mgr.unmountIdleRegions();
        }
    }

    /** 注入区块表条目视图（T5 卸载扫描遍历用；弱一致迭代，任意线程）。 */
    public java.util.Set<java.util.Map.Entry<Long, LevelChunk>> injectedChunkEntries() {
        return injectedChunks.entrySet();
    }

    /**
     * 断连/关停：刷剩余脏柱进映像，再把脏 region 映像写出。
     * 编码已暂停时（仅 1.20.1 Forge revert 窗口）不能 {@code ChunkSerializer}，只落已编码映像。
     * 退出路径在窗口结束后调用，从还活着的 ChunkMap 刷脏再落盘。
     */
    public void saveAll() {
        long saveStartNs = System.nanoTime();
        int dirty = io.github.limuqy.mc.hassium.storage.ShadowStorageHashes.dirtyKeys().size();
        LOGGER.debug("Hassium: Shadow saveAll start, injected={} dirty={} shadow={}",
                injectedChunks.size(), dirty,
                io.github.limuqy.mc.hassium.server.RuntimeServerContext.isShadowServerContext());
        try {
            int savedCount = 0;
            int abandoned = 0;
            boolean timedOut = false;
            java.util.concurrent.ConcurrentHashMap<String, io.github.limuqy.mc.hassium.storage.ShadowStorageManager> map = storages;
            if (map != null && !map.isEmpty() && canWriteStorage()) {
                for (io.github.limuqy.mc.hassium.storage.ShadowStorageManager mgr : map.values()) {
                    mgr.drain(5_000L);
                    io.github.limuqy.mc.hassium.storage.ShadowStorageManager.FlushResult saved =
                            mgr.flushDirty(5_000L);
                    savedCount += saved.written();
                    timedOut |= saved.timedOut();
                    abandoned += saved.abandoned();
                }
            }
            ShadowCacheEviction.save(worldRoot);
            long elapsedMs = (System.nanoTime() - saveStartNs) / 1_000_000L;
            LOGGER.info("Hassium: Shadow saveAll done in {}ms, saved={} abandoned={} timedOut={} injected={} dirtyLeft={}",
                    elapsedMs, savedCount, abandoned, timedOut, injectedChunks.size(),
                    io.github.limuqy.mc.hassium.storage.ShadowStorageHashes.dirtyKeys().size());
        } catch (Throwable t) {
            LOGGER.warn("Hassium: Shadow server save failed", t);
        } finally {
            SAVE_ALL_SEQ.incrementAndGet();
        }
    }

    /** 断连等待落盘用：每次 {@link #saveAll} 结束（成败都算）递增。 */
    public static long saveAllSeq() {
        return SAVE_ALL_SEQ.get();
    }

    /**
     * 定时刷新：不堵 tick，后台从 ChunkMap 刷脏进映像并落盘。
     */
    public void tickStorageFlush() {
        if (!canWriteStorage()) {
            return;
        }
        long now = System.currentTimeMillis();
        if (now - lastMutationFlushMs < MUTATION_FLUSH_INTERVAL_MS) {
            return;
        }
        lastMutationFlushMs = now;
        java.util.concurrent.ConcurrentHashMap<String, io.github.limuqy.mc.hassium.storage.ShadowStorageManager> map = storages;
        if (map == null) {
            return;
        }
        for (io.github.limuqy.mc.hassium.storage.ShadowStorageManager mgr : map.values()) {
            mgr.scheduleFlush();
        }
    }

    private void persistLightReady(String dimension, ChunkPos pos) {
        io.github.limuqy.mc.hassium.storage.ShadowStorageManager mgr = storage(dimension);
        if (mgr != null && canWriteStorage()) {
            mgr.markLightReady(pos);
        } else {
            io.github.limuqy.mc.hassium.storage.ShadowStorageHashes.markLightReady(dimension, pos);
        }
    }

    /**
     * 真引擎已回传给客户端（含超时欠光）：半成品 DataLayer 入队，保持
     * {@code isLightCorrect=false}（NBT 不写 {@code isLightOn}）。Halo 剥光，不走这里。
     */
    public void persistPartialLight(LevelChunk chunk) {
        if (chunk == null) {
            return;
        }
        if (chunk.isLightCorrect()) {
            chunk.setLightCorrect(false);
        }
        ChunkPos pos = chunk.getPos();
        String dimension = LevelCompat.getDimensionId(chunkLevel(chunk));
        io.github.limuqy.mc.hassium.storage.ShadowStorageHashes.markLightDirty(dimension, pos);
    }

    /** 可见柱回传后的存储：收敛且地表确有非 0 光 → {@code isLightOn}；否则半成品层。 */
    public void persistAfterClientLightPush(LevelChunk chunk, boolean converged) {
        if (chunk == null) {
            return;
        }
        ChunkPos pos = chunk.getPos();
        // 对齐原版：isLightOn 只在 light() 完成后置位。地表未就绪（含高空短路误判）
        // 绝不能写 isLightOn=1，否则回程 diskNeedRelight=false 永不续算 = 出生点黑块。
        if (converged && hasCompleteLightLayers(pos, chunk)
                && isColumnSurfaceLightReady(pos, chunk, null)) {
            syncLightCorrect(chunk, true);
            return;
        }
        persistPartialLight(chunk);
    }

    /**
     * 光照是否全局收敛（任意线程可调）：ThreadedLevelLightEngine 任务队列空
     * && 两引擎传播队列（blockNodesToCheck / decrease / increase）空。
     * <p>
     * 影子端世界只有注入任务，队列空即全部算完。1.21.11 的 ConsecutiveExecutor
     * 自驱动 + 异步 runUpdate，调用方须轮询（20ms 间隔）等待。
     */
    public boolean isLightConverged() {
        try {
            return isLightConverged(this.overworld());
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * 按维度版本的收敛判据（光桥在客户端主线程判「现在回传的是收敛值还是传播中间态」）。
     * 只读引擎队列是否为空：不读引擎数据层（与光照 worker 并发读 fastutil 会自旋），
     * 也不排新光任务（推高 {@code lightTasks} 水位 → 主线程 injectChunk 5s 忙等 → 卡死）。
     * 探针失败返回 true（= 不推迟，与护栏引入前的行为一致）；{@link #isLightConverged()}
     * 相反取 false（那里要拦「标记全部可用」的写）。
     */
    public boolean isLightConverged(ServerLevel level) {
        if (level == null) {
            return true;
        }
        try {
            ThreadedLevelLightEngine engine =
                    (ThreadedLevelLightEngine) level.getChunkSource().getLightEngine();
            if (engine.hasLightWork()) {
                return false;
            }
            if (io.github.limuqy.mc.hassium.compat.mods.ForeignLightEngine.usesLightTaskWatermark(engine)) {
                ThreadedLevelLightEngineAccessor acc = (ThreadedLevelLightEngineAccessor) engine;
                if (!acc.hassium$getLightTasks().isEmpty()) {
                    return false;
                }
            }
            return true;
        } catch (Throwable t) {
            return true;
        }
    }
    /**
     * 已确认引擎收敛后，把所有可见且**地表确有非 0 光**的柱标记为可安全复用。
     * 这是异步 LIGHT future 回调之外的最终持久化收口。
     * 与原版 light() 完成语义对齐：不得用高空短路/空层判「已点亮」。
     */
    public void confirmLightsCorrectIfConverged() {
        for (Map.Entry<Long, LevelChunk> entry : injectedChunks.entrySet()) {
            LevelChunk chunk = entry.getValue();
            if (chunk == null || isPlaceholder(DimensionKey.dimensionOf(entry.getKey()),
                    DimensionKey.chunkXOf(entry.getKey()),
                    DimensionKey.chunkZOf(entry.getKey()))) {
                continue;
            }
            ChunkPos pos = chunk.getPos();
            boolean complete = hasCompleteLightLayers(pos, chunk)
                    && isColumnSurfaceLightReady(pos, chunk, null);
            if (complete && !chunk.isLightCorrect()) {
                syncLightCorrect(chunk, true);
            } else if (!complete && chunk.isLightCorrect()) {
                persistPartialLight(chunk);
            }
        }
    }

    /** shutdown 用：存档访问（MinecraftServer.storageSource 为 protected）。 */
    LevelStorageSource.LevelStorageAccess storageAccess() {
        return this.storageSource;
    }

    private volatile Thread mainThreadLoop;

    /** 装配线程回填（见 {@link #createAndStart} 语义）。 */
    void attachMainThread(Thread main) {
        this.mainThreadLoop = main;
    }

    /**
     * 影子端不跑完整 server tick；每轮必须消费 MinecraftServer 的 execute() 队列与
     * ServerChunkCache.mainThreadProcessor。前者在无 tick 预算时不会消费后者。
     */
    void runMainLoop() {
        long loopCount = 0;
        while (!Thread.currentThread().isInterrupted()) {
            if (ShadowWorldgenExecutor.isTerminated()) {
                break;
            }
            loopCount++;
            if (loopCount == 200 || loopCount == 2000 || loopCount == 20000) {
                io.github.limuqy.mc.hassium.Constants.LOG.info(
                        "[SHADOW_LOOP] alive loops={} shadowPlayer={} trackingDim={}",
                        loopCount,
                        io.github.limuqy.mc.hassium.network.seedgen.ShadowTrackingSession
                                .getInstance().hasVirtualPlayer(),
                        io.github.limuqy.mc.hassium.network.seedgen.ShadowTrackingSession
                                .getInstance().currentDimension());
            }
            boolean worked;
            try {
                worked = this.pollTask();
                for (ServerLevel level : getAllLevels()) {
                    worked |= ((ServerChunkCache) level.getChunkSource()).pollTask();
                }
                // 影子虚拟玩家 tracking 会话：位置同步消费 + chunk 系统簿记 + pull 请求分批
                io.github.limuqy.mc.hassium.network.seedgen.ShadowTrackingSession.getInstance()
                        .consumeOnShadowLoop();
            } catch (Throwable t) {
                // server 已 halt 时 pollTask 会抛中断类异常，属正常退出；
                // 其余异常静默吞掉会把影子主循环杀成「无声停摆」，必须留痕。
                if (t instanceof InterruptedException
                        || (t.getCause() instanceof InterruptedException)) {
                    break;
                }
                // 客户端 Stopping! 后 GLFW 已销毁：pollTask → haveTime → Util.getNanos() 经 GLX
                // 时钟取时（glfwGetTime）抛 NPE。影子端只服务游戏会话，退出窗口内收敛属正常；
                // 前置条件 isSharedIoPoolShutdown 把真实崩溃（非 teardown 窗口）排除在外。
                if (io.github.limuqy.mc.hassium.compat.ShadowServerCompat.isSharedIoPoolShutdown()
                        && isGlfwClockFailure(t)) {
                    io.github.limuqy.mc.hassium.Constants.LOG.info(
                            "[SHADOW_LOOP] shadow main loop exited during client teardown");
                    break;
                }
                io.github.limuqy.mc.hassium.Constants.LOG.error(
                        "[SHADOW_LOOP] shadow main loop crashed; session halted", t);
                break;
             }
            for (ServerLevel level : getAllLevels()) {
                try {
                    ((ThreadedLevelLightEngine) ((ServerChunkCache) level.getChunkSource())
                            .getLightEngine()).tryScheduleUpdate();
                } catch (Throwable ignored) {
                    // 光照任务驱动失败不影响 worldgen 主循环
                }
            }
            if (!worked) {
                // 原版队列均为空时短暂停驻。
                LockSupport.parkNanos("hassium-seedgen-main", 100_000L);
            }
        }
    }

    void stopMainLoop() {
        Thread t = mainThreadLoop;
        mainThreadLoop = null;
        if (t != null) {
            t.interrupt();
        }
    }

    /** halt 之后、关 worldgen 池之前：等主循环退出，避免 ChunkMap.save 打到已关 FJP。 */
    void joinMainLoop(long timeoutMs) {
        Thread t = mainThreadLoop;
        if (t == null || t == Thread.currentThread()) {
            return;
        }
        t.interrupt();
        try {
            t.join(Math.max(1L, timeoutMs));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * 是否为客户端退出窗口内的 GLFW 时钟失效异常。
     * <p>
     * {@code MinecraftServer.haveTime()} 走 {@code Util.getNanos()} → GLX 时钟（{@code glfwGetTime}）；
     * 客户端 {@code Stopping!} 后 GLFW 已销毁，取时抛异常。仅用于把「teardown 引发的正常退出」
     * 与真实崩溃区分开，调用方须同时校验退出窗口前置条件。
     * <p>
     * 判据 = <b>帧签名</b>而非异常类型。同一竞态实测会以不同异常现形：GLFW 函数表已置空时是
     * {@code NullPointerException}，而关闭期 Fabric {@code KnotClassLoader} 与 LWJGL
     * {@code CallbackI} 错配时是 {@code IncompatibleClassChangeError}。真正的证据是
     * 「{@code GLX} 时钟 lambda + {@code GLFW.glfwGetTime}」这条调用链。早期只认 NPE，
     * 使 1.21.1 接管态冒烟在收尾竞态下被误判为真实崩溃（日志审计 FAIL）。
     */
    private static boolean isGlfwClockFailure(Throwable t) {
        for (Throwable current = t; current != null; current = current.getCause()) {
            boolean glfwGetTime = false;
            boolean glxClock = false;
            for (StackTraceElement frame : current.getStackTrace()) {
                String className = frame.getClassName();
                if ("org.lwjgl.glfw.GLFW".equals(className)) {
                    glfwGetTime = true;
                } else if ("com.mojang.blaze3d.platform.GLX".equals(className)) {
                    glxClock = true;
                }
            }
            if (glfwGetTime && glxClock) {
                return true;
            }
        }
        return false;
    }
    /** 影子端主世界 region 目录（过渡期兼容；新代码请用 {@link #regionDir(String)}）。 */
    java.nio.file.Path regionDir() {
        return regionDir(DimensionKey.OVERWORLD);
    }

    /**
     * 指定维度 region 目录（vanilla 存档布局）：overworld→{@code world/region}、
     * nether→{@code world/DIM-1/region}、end→{@code world/DIM1/region}。
     * 旧单维度数据（{@code world/region}）即 overworld 数据，布局天然兼容；
     * nether/end 从空开始。
     */
    java.nio.file.Path regionDir(String dimension) {
        return this.storageSource.getDimensionPath(
                ResourceKey.create(net.minecraft.core.registries.Registries.DIMENSION,
                        io.github.limuqy.mc.hassium.compat.ResourceLocationCompat.create(dimension)))
                .resolve("region");
    }
    /** shutdown 用：持久世界根（LevelStorageAccess 两版本无统一目录 getter）。 */
    java.nio.file.Path worldRoot() {
        return worldRoot;
    }


    /** 注入区块复合键视图（清理扫描用；弱一致迭代）。 */
    public java.util.Set<Long> injectedKeys() {
        return injectedChunks.keySet();
    }

    /** 该 region 是否仍有本会话注入柱。 */
    public boolean regionHasInjected(String dimension, int regionX, int regionZ) {
        for (Long key : injectedChunks.keySet()) {
            if (!dimension.equals(DimensionKey.dimensionOf(key))) {
                continue;
            }
            if (Math.floorDiv(DimensionKey.chunkXOf(key), 32) == regionX
                    && Math.floorDiv(DimensionKey.chunkZOf(key), 32) == regionZ) {
                return true;
            }
        }
        return false;
    }

    public void deleteChunk(ChunkPos pos) {
        deleteChunk(DimensionKey.OVERWORLD, pos);
    }

    /** 删除指定维度磁盘区块（缓存清理调用，任意线程）。 */
    public void deleteChunk(String dimension, ChunkPos pos) {
        long key = DimensionKey.key(dimension, pos.x, pos.z);
        injectedChunks.remove(key);
        placeholderChunks.remove(key); // 磁盘清理连带撤占位标记，避免残留键挡住后续真实注入
        if (!ownShutdownInProgress
                && !ShadowServerRegistry.getInstance().isPreviousShutdownComplete()) {
            io.github.limuqy.mc.hassium.storage.ShadowStorageHashes.remove(dimension, pos);
            return;
        }
        io.github.limuqy.mc.hassium.storage.ShadowStorageManager mgr = storage(dimension);
        if (mgr != null) {
            mgr.deleteColumn(pos);
        } else {
            io.github.limuqy.mc.hassium.storage.ShadowStorageHashes.remove(dimension, pos);
        }
    }

    /** 删除整个 region 文件（缓存容量清理；任意线程）。本会话占用中的 region 拒绝。 */
    public void deleteRegion(String dimension, int regionX, int regionZ) {
        if (regionHasInjected(dimension, regionX, regionZ)) {
            return;
        }
        io.github.limuqy.mc.hassium.storage.ShadowStorageManager mgr = storage(dimension);
        if (mgr != null) {
            mgr.deleteRegion(regionX, regionZ);
            return;
        }
        io.github.limuqy.mc.hassium.storage.ShadowStorageHashes.removeRegion(dimension, regionX, regionZ);
        io.github.limuqy.mc.hassium.storage.ShadowRegionHeat.removeRegion(dimension, regionX, regionZ);
        try {
            java.nio.file.Files.deleteIfExists(
                    io.github.limuqy.mc.hassium.storage.RegionCache.regionFileByKey(
                            regionDir(dimension), net.minecraft.world.level.ChunkPos.asLong(regionX, regionZ)));
        } catch (Exception e) {
            LOGGER.debug("Hassium: deleteRegion file failed dim={} r.{}.{}", dimension, regionX, regionZ, e);
        }
    }

    long worldSeed() {
        return worldSeed;
    }

    /**
     * 登出保活：saveAll 已把脏柱刷盘。注入表留给 T5 / idle shutdown 回收——
     * 3s 重连要靠 {@code injectedChunks} 走内存 hash 命中；park 立刻摘表会让
     * Bloom/内存双空，R2 被当 ROUND1 直推。
     */
    void clearHotStateAfterPark() {
        ShadowChunkMapCompat.clearSuspendedLoads();
        // 占位成对摘：只清标记会让 isPlaceholder 对仍驻留的空气壳变假 → publish 误交付；
        // 空气壳对 R2 内存 hash 命中无价值，与「保留真实柱」不冲突。
        for (Long key : java.util.List.copyOf(placeholderChunks)) {
            injectedChunks.remove(key);
            placeholderChunks.remove(key);
        }
        ShadowLightCompute.clearPendingPlaceholderNeighborRelight();
        java.util.concurrent.ConcurrentHashMap<String, io.github.limuqy.mc.hassium.storage.ShadowStorageManager> map = storages;
        if (map != null) {
            for (io.github.limuqy.mc.hassium.storage.ShadowStorageManager mgr : map.values()) {
                mgr.unmountIdleRegions();
            }
        }
        LOGGER.info("Hassium: Shadow parked (seed={}, injected={}, dirty={})",
                worldSeed, injectedChunks.size(),
                io.github.limuqy.mc.hassium.storage.ShadowStorageHashes.dirtyKeys().size());
    }

    /**
     * 刷新本端全部 region 存储（chunk / poi / entities）：关闭已缓存的 RegionFile 并
     * 清空缓存，下次访问重新打开（读到完整头部）。
     * <p>
     * 上次关停（R1 saveAll）落盘完成时由 saver 调用：本端（R2）可能在 R1 saveAll
     * 期间已打开同一 .mca（构造时读到半写头 → 陈旧 usedSectors/offset 位图），若不
     * 刷新，本端后续写入（T5c gate 放行后）会与 R1 数据扇区重叠——错位区块、垃圾
     * 长度、外部流残留等持续损坏（2026-08-14 定位）。
     * <p>
     * 与 T5c 写 gate 组合：本方法执行时 previousShutdownComplete 仍为 false，本端写
     * 路径（saveChunkToDisk/deleteChunk）保持拒绝，仅在途读会短暂失败（空区块重建
     * → 数据重推，正确降级）。
     */
    public void refreshRegionFiles() {
        try {
            for (net.minecraft.server.level.ServerLevel level : getAllLevels()) {
                net.minecraft.server.level.ServerChunkCache cache =
                        (net.minecraft.server.level.ServerChunkCache) level.getChunkSource();
                // chunk 存储：ChunkMap extends SimpleRegionStorage(≥1.21.2)/ChunkStorage(<1.21.2)
                refreshRegionStorageFromHop((io.github.limuqy.mc.hassium.mixin.SimpleRegionStorageAccessor)
                        (Object) cache.chunkMap);
                // POI 存储：PoiManager extends SectionStorage
                io.github.limuqy.mc.hassium.mixin.ChunkMapAccessor cm =
                        (io.github.limuqy.mc.hassium.mixin.ChunkMapAccessor) (Object) cache.chunkMap;
                refreshRegionStorageFromHop((io.github.limuqy.mc.hassium.mixin.SectionStorageAccessor)
                        (Object) cm.hassium$getPoiManager());
            }
        } catch (Throwable t) {
            LOGGER.warn("Hassium: Shadow region file refresh failed", t);
        }
    }

    /** 经带 #if 的 accessor 取到下一跳存储对象（SimpleRegionStorage / IOWorker）后统一刷新。 */
    private static void refreshRegionStorageFromHop(Object hop) {
        if (hop instanceof io.github.limuqy.mc.hassium.mixin.SimpleRegionStorageAccessor acc) {
            refreshRegionStorage((io.github.limuqy.mc.hassium.mixin.IOWorkerAccessor)
                    (Object) acc.hassium$getWorker());
        } else if (hop instanceof io.github.limuqy.mc.hassium.mixin.IOWorkerAccessor worker) {
            refreshRegionStorage(worker);
        }
    }

    private static void refreshRegionStorage(io.github.limuqy.mc.hassium.mixin.IOWorkerAccessor worker) {
        Object storage = worker.hassium$getStorage();
        try {
            // RegionFileStorage 是 final 类：接口型 accessor 无法注入（"target type mismatch
            // ... is not an interface"），类 mixin 又无法从外部 cast——此处反射读取私有
            // regionCache（字段名 1.20.1~1.21.11 一致），关闭全部 RegionFile 并清空缓存，
            // 下次访问（getRegionFile）重新打开读取完整头。
            java.lang.reflect.Field cacheField = storage.getClass().getDeclaredField("regionCache");
            cacheField.setAccessible(true);
            it.unimi.dsi.fastutil.longs.Long2ObjectLinkedOpenHashMap<?> cache =
                    (it.unimi.dsi.fastutil.longs.Long2ObjectLinkedOpenHashMap<?>) cacheField.get(storage);
            for (Object file : cache.values()) {
                if (file instanceof java.io.Closeable closeable) {
                    try {
                        closeable.close();
                    } catch (java.io.IOException ignored) {
                        // 关闭失败不影响：下次访问重新打开
                    }
                }
            }
            cache.clear();
        } catch (Throwable t) {
            LOGGER.warn("Hassium: Shadow region file refresh failed", t);
        }
    }

    // ---- GameTestServer 镜像覆写（不跑 tick 循环，覆写仅满足 abstract 集与防御） ----

    @Override
    public void tickServer(BooleanSupplier hasTimeLeft) {
        super.tickServer(hasTimeLeft);
    }

    @Override
    public void waitUntilNextTick() {
        this.runAllTasks();
    }
    @Override
    protected boolean shouldRun(net.minecraft.server.TickTask task) {
        // 影子端不 tickServer，tickCount 恒定；所有任务均来自本进程的影子调度，须立即执行。
        return true;
    }


    @Override
    public SystemReport fillServerSystemReport(SystemReport systemReport) {
        systemReport.setDetail("Type", "Hassium shadow seed server");
        return systemReport;
    }

    @Override
    public void onServerExit() {
        super.onServerExit();
        LOGGER.info("Hassium: Shadow seed server exiting");
    }

    @Override
    public void onServerCrash(CrashReport crashReport) {
        super.onServerCrash(crashReport);
        LOGGER.error("Hassium: Shadow seed server crashed\n{}",
                ShadowServerCompat.friendlyReport(crashReport));
    }

    @Override
    public boolean isHardcore() {
        return false;
    }

    // 豁免：SampleLogger / tick 日志抽象方法仅 1.21.1+ 存在（1.20.1 无此 API）
#if MC_VER >= MC_1_21_1
    private final net.minecraft.util.debugchart.SampleLogger sampleLogger =
            new net.minecraft.util.debugchart.LocalSampleLogger(4);

    @Override
    public net.minecraft.util.debugchart.SampleLogger getTickTimeLogger() {
        return this.sampleLogger;
    }

    @Override
    public boolean isTickTimeLoggingEnabled() {
        return false;
    }
#endif

    @Override
    public boolean shouldRconBroadcast() {
        return false;
    }

    @Override
    public boolean isDedicatedServer() {
        return false;
    }

    @Override
    public boolean shouldInformAdmins() {
        return false;
    }

    @Override
    public boolean isPublished() {
        return false;
    }

    @Override
    public int getRateLimitPacketsPerSecond() {
        return 0;
    }

    // 豁免：MinecraftServer 抽象方法签名随 1.21.9 改名换参、1.21.11 改 PermissionSet，必须落在子类
#if MC_VER < MC_1_21_9
    @Override
    public int getOperatorUserPermissionLevel() {
        return 0;
    }

    @Override
    public int getFunctionCompilationLevel() {
        return 4;
    }

    @Override
    public boolean isEpollEnabled() {
        return false;
    }

    @Override
    public boolean isCommandBlockEnabled() {
        return false;
    }

    @Override
    public boolean isSingleplayerOwner(com.mojang.authlib.GameProfile profile) {
        return false;
    }
#elif MC_VER < MC_1_21_11
    @Override
    public int operatorUserPermissionLevel() {
        return 0;
    }

    @Override
    public int getFunctionCompilationLevel() {
        return 4;
    }

    @Override
    public boolean isEpollEnabled() {
        return false;
    }

    @Override
    public boolean isCommandBlockEnabled() {
        return false;
    }

    @Override
    public boolean isSingleplayerOwner(net.minecraft.server.players.NameAndId nameAndId) {
        return false;
    }

    @Override
    public int getMaxPlayers() {
        return 1;
    }
#else
    @Override
    public net.minecraft.server.permissions.LevelBasedPermissionSet operatorUserPermissions() {
        return net.minecraft.server.permissions.LevelBasedPermissionSet.ALL;
    }

    @Override
    public net.minecraft.server.permissions.PermissionSet getFunctionCompilationPermissions() {
        return net.minecraft.server.permissions.LevelBasedPermissionSet.OWNER;
    }

    @Override
    public boolean useNativeTransport() {
        return false;
    }

    @Override
    public boolean isSingleplayerOwner(net.minecraft.server.players.NameAndId nameAndId) {
        return false;
    }

    @Override
    public int getMaxPlayers() {
        return 1;
    }
#endif
}
