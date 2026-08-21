package io.github.limuqy.mc.hassium.network.seedgen;

#if MC_VER < MC_1_21_9
import com.mojang.authlib.GameProfile;
#else
import com.mojang.authlib.GameProfile;
import com.mojang.authlib.yggdrasil.ServicesKeySet;
import net.minecraft.server.players.NameAndId;
import net.minecraft.server.players.ProfileResolver;
import net.minecraft.server.players.UserNameToIdResolver;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.Optional;
#endif

#if MC_VER >= MC_1_21_11
import net.minecraft.server.permissions.LevelBasedPermissionSet;
import net.minecraft.server.permissions.PermissionSet;
#endif

#if MC_VER >= MC_1_20_5
import net.minecraft.util.debugchart.LocalSampleLogger;
import net.minecraft.util.debugchart.SampleLogger;
#endif

import com.mojang.authlib.yggdrasil.ServicesKeySet;
import com.mojang.logging.LogUtils;
import io.github.limuqy.mc.hassium.compat.BlockEntityCompat;
import io.github.limuqy.mc.hassium.compat.EntityPacketCompat;
import io.github.limuqy.mc.hassium.compat.ShadowChunkMapCompat;
import io.github.limuqy.mc.hassium.mixin.ServerLevelAccessor;
import io.github.limuqy.mc.hassium.mixin.ThreadedLevelLightEngineAccessor;
import io.github.limuqy.mc.hassium.network.BlockEntityDataS2CPacket;
import io.github.limuqy.mc.hassium.network.SectionDeltaS2CPacket;
import io.github.limuqy.mc.hassium.utils.DebugLogger;
import java.net.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.locks.LockSupport;
import java.util.function.BooleanSupplier;
import net.minecraft.CrashReport;
import net.minecraft.SystemReport;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.core.SectionPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundAddEntityPacket;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.network.protocol.game.ClientboundBlockUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundLevelChunkPacketData;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import net.minecraft.network.protocol.game.ClientboundSectionBlocksUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundMoveEntityPacket;
import net.minecraft.network.protocol.game.ClientboundRemoveEntitiesPacket;
import net.minecraft.network.protocol.game.ClientboundRotateHeadPacket;
import net.minecraft.network.protocol.game.ClientboundSetEntityDataPacket;
import net.minecraft.network.protocol.game.ClientboundSetEntityMotionPacket;
import net.minecraft.network.protocol.game.ClientboundTeleportEntityPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.FullChunkStatus;
#if MC_VER >= MC_1_21_2
import net.minecraft.world.entity.EntitySpawnReason;
#endif
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.entity.PersistentEntitySectionManager;
import net.minecraft.server.Services;
import net.minecraft.server.WorldStem;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ThreadedLevelLightEngine;
#if MC_VER < MC_1_20_5
import com.mojang.datafixers.util.Either;
import net.minecraft.server.level.ChunkHolder;
#else
import net.minecraft.server.level.ChunkResult;
#endif
#if MC_VER < MC_1_21_9
import net.minecraft.server.level.progress.LoggerChunkProgressListener;
#else
import net.minecraft.server.level.progress.LoggingLevelLoadListener;
#endif
#if MC_VER >= MC_1_21_1
import net.minecraft.ReportType;
#endif
import net.minecraft.server.packs.repository.PackRepository;
import net.minecraft.server.players.PlayerList;
#if MC_VER >= MC_1_21_9
import net.minecraft.server.notifications.EmptyNotificationService;
#endif
import net.minecraft.util.datafix.DataFixers;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.DataLayer;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.lighting.LevelLightEngine;
import net.minecraft.world.level.lighting.LightEngine;
import net.minecraft.world.level.storage.LevelStorageSource;
#if MC_VER < MC_1_20_5
import net.minecraft.world.level.chunk.ChunkStatus;
#else
import net.minecraft.world.level.chunk.status.ChunkStatus;
#endif
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
 */
public class ShadowSeedServer extends MinecraftServer {
    private static final Logger LOGGER = LogUtils.getLogger();

    /** Services 最小化（1.20.1 四参 / 1.21.11 五参，后者补 mock resolver 防 NPE） */
    private static final Services NO_SERVICES = buildNoServices();

    /**
     * 共享只读空光层（全 0）：清光路径 {@code queueSectionData(layer, sp, EMPTY)} 用——
     * 由引擎在 markNewInconsistencies 时安装为 section 的 updating 层，之后的写入都走
     * copy-on-write（changedSections 副本），本实例绝不被就地修改（同
     * MixinLayerLightSectionStorage.hassium$EMPTY_DATA_LAYER 的只读约定）。
     */
    private static final net.minecraft.world.level.chunk.DataLayer EMPTY_LIGHT_LAYER =
            new net.minecraft.world.level.chunk.DataLayer(2048);

    private final WorldStem stem;
    private final long worldSeed;
    /** 持久世界根（客户端缓存目录下原版存档结构；断连保存、重连复用，不删除）。 */
    private final java.nio.file.Path worldRoot;
    /**
     * 注入区块表（pos → LevelChunk）：网络/读盘 decode 后的 FULL 柱。随后加
     * {@code TicketType.UNKNOWN} FULL 级票进入 ChunkMap（{@code scheduleChunkLoad}
     * 短路为 {@code ImposterProtoChunk}）。探活结论：不能赌金字塔对 FULL+
     * {@code !isLightCorrect} 柱只重跑 LIGHT（邻柱无盘会 worldgen），算光仍走
     * {@code ShadowLightCompute} 官方 {@code initializeLight}+{@code lightChunk}。
     * 打包与 saveAll 仍取本表；REPLACE 覆盖。
     */
    private final java.util.concurrent.ConcurrentHashMap<Long, net.minecraft.world.level.chunk.LevelChunk>
            injectedChunks = new java.util.concurrent.ConcurrentHashMap<>();
    /** 本端为注入柱加上的 UNKNOWN FULL 票（与注入表同阶，卸载还票）。 */
    private final java.util.Set<Long> injectTickets = java.util.concurrent.ConcurrentHashMap.newKeySet();

    /**
     * 断连 saveAll 是否需要重写该柱：脏位在 {@link io.github.limuqy.mc.hassium.storage.ShadowStorageHashes}
     * （contentDirty / lightDirty）。活数据仍在 {@link #injectedChunks} 的 LevelChunk。
     */
    private io.github.limuqy.mc.hassium.storage.ShadowStorageManager storage;

    /**
     * 本端是否已进入关停保存（SeedGenLevelCompat.shutdown 首步置位）：本端 saveAll 的
     * 写路径豁免 {@code ShadowServerRegistry.isPreviousShutdownComplete()} 写 gate——
     * 关停 saver 已前置等待上次关停完成（有界 30s，超时跳过保存），本端写盘与任何
     * 其他端无并发（数据安全红线：同一 mca 禁并发写）。运行期（R2 会话）恒为 false，
     * 此时写 gate 生效：R1 saveAll 未完成 → saveChunkToDisk/deleteChunk 拒绝落盘，
     * 内存驻留/比对 miss 兜底。
     */
    private volatile boolean ownShutdownInProgress;

    /** 关停保存开始（SeedGenLevelCompat.shutdown 首步调用）：写 gate 对本端保存放行。 */
    void beginShutdownSave() {
        this.ownShutdownInProgress = true;
    }

    private ShadowSeedServer(Thread thread,
                             LevelStorageSource.LevelStorageAccess access,
                             PackRepository repo,
                             WorldStem stem,
                             long seed,
                             java.nio.file.Path worldRoot) {
        super(thread, access, repo, stem, Proxy.NO_PROXY, DataFixers.getDataFixer(), NO_SERVICES,
#if MC_VER < MC_1_20_5
                LoggerChunkProgressListener::new);
#elif MC_VER < MC_1_21_9
                LoggerChunkProgressListener::create);
#else
                LoggingLevelLoadListener.forDedicatedServer());
#endif
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
        this.setPlayerList(new PlayerList(this, this.registries(), this.playerDataStorage,
#if MC_VER < MC_1_21_9
                1
#else
                new EmptyNotificationService()
#endif
        ) {});
        long t0Ns = System.nanoTime(); // T0b 诊断：initServer 各阶段耗时
        this.loadLevel();
        long t1Ns = System.nanoTime();
        // 缓存清理热度索引加载（跨会话累计；损坏/缺失 → 空索引）
        ShadowCacheEviction.load(worldRoot);
        long t2Ns = System.nanoTime();
        this.storage = new io.github.limuqy.mc.hassium.storage.ShadowStorageManager(
                regionDir(),
                this::serializeInjectedColumn,
                injectedChunks::containsKey,
                io.github.limuqy.mc.hassium.config.HassiumConfigService.getInstance()
                        .getStorageCompressionLevel());
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
#if MC_VER < MC_1_20_5
        this.createLevels(new LoggerChunkProgressListener(11));
#elif MC_VER < MC_1_21_9
        this.createLevels(LoggerChunkProgressListener.create(11));
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

    /** 单区块生成超时：原版 worldgen 卡死时兜底回退（3s ≫ 正常生成耗时）。 */
    private static final long GENERATION_TIMEOUT_NANOS = 3_000_000_000L;

    /**
     * 生成一个 FULL 区块（任意线程可调；超时/中断返回 null → 调用方回退全量请求）。
     * <p>
     * 非主线程路径（本 server 的 mainThread 是装配线程）：getChunkFuture 提交到
     * mainThreadProcessor，由 {@link #runMainLoop()} 驱动完成；本方法只轮询不阻塞。
     */
    public LevelChunk generateChunk(ChunkPos pos) {
        ServerChunkCache cache = (ServerChunkCache) this.overworld().getChunkSource();
        // 3×3 邻域预生成：FEATURES 步骤（applyBiomeDecoration）的 biome 集合与
        // per-step feature 排序索引取决于生成时已就绪的邻域区块（range 8 内）
        // ——影子端逐块生成、邻域为空时，feature 放置（矿石/花岗岩闪长岩团块/
        // 树木种类/植被）与服务端（生成时邻域齐备）不一致 → contentHash 不匹配。
        // 邻块只预生成到 BIOMES（非 FULL）：FEATURES 需要的仅是邻块 biome 集合，
        // BIOMES 状态即就绪且确定性；若邻块提前升到 FULL，将以空邻域生成错误
        // feature 并被缓存命中 → 邻块成为目标时级联污染。改 BIOMES 后，邻块成为
        // 目标时从 BIOMES 继续升 FULL（非复用错数据），级联消除。
        // 队列按玩家距离升序，多数邻块已在队首生成，仅补缺口；已生成直接命中缓存。
        // 总超时放宽到 9×单块（8 邻块 + 目标），兜底回退语义不变。
        long deadline = System.nanoTime() + GENERATION_TIMEOUT_NANOS * 9;
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                if (dx == 0 && dz == 0) {
                    continue;
                }
                if (generateChunkInternal(cache, new ChunkPos(pos.x + dx, pos.z + dz), ChunkStatus.BIOMES, deadline) == null) {
                    return null;
                }
            }
        }
        ChunkAccess chunk = generateChunkInternal(cache, pos, ChunkStatus.FULL, deadline);
        return chunk instanceof LevelChunk levelChunk ? levelChunk : null;
    }

    /**
     * 单块按指定 ChunkStatus 生成（generateChunk 内部：目标块 FULL、邻块 BIOMES，
     * 共用实现与超时）。返回生成后的 ChunkAccess（BIOMES 时为 ProtoChunk，FULL 时
     * 为 LevelChunk），超时/中断/失败返回 null → 调用方回退全量。
     */
    private ChunkAccess generateChunkInternal(ServerChunkCache cache, ChunkPos pos, ChunkStatus status, long deadline) {
        ShadowChunkMapCompat.enterWorldgen();
        try {
            return generateChunkInternalAllowed(cache, pos, status, deadline);
        } finally {
            ShadowChunkMapCompat.leaveWorldgen();
        }
    }

    private ChunkAccess generateChunkInternalAllowed(ServerChunkCache cache, ChunkPos pos, ChunkStatus status, long deadline) {
#if MC_VER < MC_1_20_5
        CompletableFuture<Either<ChunkAccess, ChunkHolder.ChunkLoadingFailure>> future =
                cache.getChunkFuture(pos.x, pos.z, status, true);
#else
        CompletableFuture<ChunkResult<ChunkAccess>> future =
                cache.getChunkFuture(pos.x, pos.z, status, true);
#endif
        while (!future.isDone()) {
            if (System.nanoTime() > deadline) {
                return null;
            }
            try {
                Thread.sleep(10L);
            } catch (InterruptedException e) {
                return null;
            }
        }
#if MC_VER < MC_1_20_5
        return future.join().left().orElse(null);
#else
        return future.join().orElse(null);
#endif
    }

    /**
     * 大批量光任务投递后的引擎任务水位排水（委托
     * {@link ShadowLightCompute#awaitEngineTaskDrain}）：仅 injectChunk（重注入清光）与
     * relightChunk 使用——两柱清光单柱可投 48+ 个 PRE 任务，连续多柱叠加会越过
     * vanilla sorter 并发 runUpdate 阈值（1000）→ 任务错序 → 空光层打包黑块。
     * 每柱末尾调用：水位已达标时零开销（一次 size 读），积压时忙等 mailbox 消化。
     */
    private void awaitLightTaskDrain() {
        try {
            ThreadedLevelLightEngine lightEngine =
                    (ThreadedLevelLightEngine) this.overworld().getChunkSource().getLightEngine();
            ShadowLightCompute.awaitEngineTaskDrain(lightEngine);
        } catch (Throwable ignored) {
            // 引擎不可用：排水跳过（不阻塞注入链）
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
     * 把剥光 S2C 包 decode 成 {@code LevelChunk} 写入注入表，并加 UNKNOWN FULL 票
     * 进入 ChunkMap（{@code scheduleChunkLoad} 短路为 ImposterProtoChunk）。
     * <p>
     * 探活（vanilla ChunkMap / ChunkStatusTasks）：FULL 柱 persisted 恒 FULL；
     * {@code isLighted==false} 时 LIGHT task <em>可能</em>仍被调用，但 LIGHT range=1
     * 会拉邻柱，无盘邻柱走 GENERATION_PYRAMID。注入路径禁止 worldgen，因此不算光
     * 不依赖金字塔重跑 LIGHT；清光仍用 {@link #clearChunkLight}（禁止换成
     * {@code updateChunkStatus}）。
     * <p>
     * 清光只在重注入（REPLACE 覆盖）时执行（强制覆盖共享空光层，见
     * {@link #clearChunkLight}）；全新柱无引擎状态直接跳过。全部经
     * ThreadedLevelLightEngine 异步任务（runMainLoop 已驱动 tryScheduleUpdate）。
     * 注入失败返回 false（调用方走单柱兜底）。
     */
    public boolean injectChunk(ChunkPos pos, ClientboundLevelChunkWithLightPacket packet) {
        long key = ChunkPos.asLong(pos.x, pos.z);
        LevelChunk previous = this.injectedChunks.get(key);
        try {
            ServerLevel level = this.overworld();
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
            int nonAirSections = 0;
            for (net.minecraft.world.level.chunk.LevelChunkSection section : chunk.getSections()) {
                if (!section.hasOnlyAir()) {
                    nonAirSections++;
                }
            }
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
                nonAirSections = 0;
                for (net.minecraft.world.level.chunk.LevelChunkSection section : chunk.getSections()) {
                    if (!section.hasOnlyAir()) {
                        nonAirSections++;
                    }
                }
            }
            DebugLogger.debug(DebugLogger.LogType.ASYNC,
                    "[SHADOW_INJECT] pos=({},{}) sections={} nonAirSections={} sectionBytes={} heightmapKeys={}",
                    pos.x, pos.z, chunk.getSections().length, nonAirSections, sectionBytes,
                    data.getHeightmaps().size());
            boolean fresh = previous == null;
            if (!fresh) {
                // 重注入（REPLACE 覆盖）：旧光必须物理清除再重算。全新柱无任何引擎状态
                // （section 状态 0、无数据层），跳过清光——省 2×~30 个引擎任务，避免首波
                // 并发任务量越过 ThreadedLevelLightEngine 1000 阈值触发 sorter 线程并发
                // runUpdate → 任务错序（propagateLightSources 先于 updateSectionStatus）→
                // 空光层被打包推送（客户端黑面/黑块）。全新柱由官方 initializeLight +
                // lightChunk 直接初始化。
                clearChunkLight(pos, chunk);
            }
            // 网络注入：packet 内容可能与磁盘表 hash 不同，必须现算并覆盖。
            // 读盘柱走 injectLoadedChunk，hash 已由 MixinRegionFile 回填，禁止在此重复现算。
            try {
                long contentHash = io.github.limuqy.mc.hassium.cache.ChunkContentHashUtil
                        .combineSectionHashes(io.github.limuqy.mc.hassium.cache.ChunkContentHashUtil
                                .computeSectionHashes(chunk));
                io.github.limuqy.mc.hassium.storage.ShadowStorageHashes.put(pos, contentHash);
            } catch (Throwable hashError) {
                LOGGER.debug("Hassium: Shadow contentHash compute failed for {}, skip hash write", pos);
            }
            io.github.limuqy.mc.hassium.storage.ShadowStorageHashes.markContentDirty(key);
            ShadowCacheEviction.recordAccess(pos);
            registerInjectTicket(pos);
            if (!fresh) {
                // 重注入清光投递 48+ 个 PRE 任务：按柱排水，防连续重注入叠加越 1000 阈值
                // （fresh 柱零投递，size 检查立即通过零开销）。
                awaitLightTaskDrain();
            }
            return true;
        } catch (Throwable t) {
            if (previous != null) {
                this.injectedChunks.put(key, previous);
            } else {
                this.injectedChunks.remove(key);
            }
            LOGGER.warn("Hassium: Shadow light inject failed for {}", pos, t);
            return false;
        }
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
        synchronized (ShadowLightCompute.LIGHT_ENGINE_MUTEX) {
            ThreadedLevelLightEngine lightEngine =
                    (ThreadedLevelLightEngine) this.overworld().getChunkSource().getLightEngine();
            lightEngine.setLightEnabled(pos, false);
            // 与 vanilla ThreadedLevelLightEngine.updateChunkStatus 同范围（minLight..maxLight）：
            // 含上下各一层 padding。只清 chunk 实际 section 会残留 padding 光，边界传播时
            // 读到陈旧数据（视距边缘/水面上下边缘黑块来源之一）。
            for (int y = lightEngine.getMinLightSection(); y < lightEngine.getMaxLightSection(); y++) {
                SectionPos sp = SectionPos.of(pos, y);
                lightEngine.queueSectionData(LightLayer.SKY, sp, EMPTY_LIGHT_LAYER);
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
        LevelChunk chunk = this.injectedChunks.get(ChunkPos.asLong(pos.x, pos.z));
        if (chunk == null) {
            return false;
        }
        // 光增量会改写引擎光照，saveAll 序列化时从引擎读光，必须把该柱标脏重写。
        io.github.limuqy.mc.hassium.storage.ShadowStorageHashes.markLightDirty(pos);
        synchronized (ShadowLightCompute.LIGHT_ENGINE_MUTEX) {
            ThreadedLevelLightEngine lightEngine =
                    (ThreadedLevelLightEngine) this.overworld().getChunkSource().getLightEngine();
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
                if (skyMask.get(bit) || emptySkyMask.get(bit)) {
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

    /**
     * 整柱实际 section 的权威光是否全部就绪：sky/block 两层 DataLayer 非 null，且
     * <b>位于任一行天空源的 section 的 sky 层必须非全空</b>。
     * <p>
     * 供 {@code ShadowLightCompute.finishLight} 对齐原版 {@code isLightCorrect}：
     * 不全 → {@code setLightCorrect(false)} + 仍推首包；补光走 drainLightMasks。
     * 补光走 drainLightMasks。不再用于挡首包或续投屏障。
     * <p>
     * 官方 {@code ClientboundLightUpdatePacketData} 对 null DataLayer 是「静默省略」（新柱
     * apply 后该 section 无光数据 → 黑块）；对全空层则打包成 empty 掩码——客户端收到后
     * 把该 section 置为全 0 光。全空 sky 层只在「该行全部天空源都低于本 section 顶」时合法；
     * 位于源的 section 全空 = 播种/fill 未跑到（引擎任务错序），按未完备处理。
     */
    public boolean isChunkLightComplete(ChunkPos pos, LevelChunk chunk) {
        try {
            // lightCorrect=false 只出现在异常路径 / 尚未跑完 LIGHT，按未完备处理。
            if (!chunk.isLightCorrect()) {
                return false;
            }
            LevelLightEngine lightEngine = this.overworld().getChunkSource().getLightEngine();
            boolean hasSky = this.overworld().dimensionType().hasSkyLight();
            net.minecraft.world.level.lighting.ChunkSkyLightSources skySources =
                    hasSky ? chunk.getSkyLightSources() : null;
            int minSection = io.github.limuqy.mc.hassium.compat.LevelHeightCompat.getMinSection(chunk);
            int maxSection = io.github.limuqy.mc.hassium.compat.LevelHeightCompat.getMaxSectionExclusive(chunk);
            for (int y = minSection; y < maxSection; y++) {
                SectionPos sp = SectionPos.of(pos, y);
                int sectionIndex = chunk.getSectionIndexFromSectionY(y);
                net.minecraft.world.level.chunk.LevelChunkSection section = chunk.getSection(sectionIndex);
                if (section == null || section.hasOnlyAir()) {
                    // 纯空气 section 不需要光数据层：源之上由官方 lightChunk 播种；
                    // 源之下全 0 正确（官方包省略 null 层 = 新柱零光）。
                    continue;
                }
                // 非空 section：initializeLight 必须已为它建层。block 层缺失 = 屏障异常。
                if (lightEngine.getLayerListener(LightLayer.BLOCK).getDataLayerData(sp) == null) {
                    return false;
                }
                if (hasSky && lightEngine.getLayerListener(LightLayer.SKY).getDataLayerData(sp) == null
                        && sectionAtOrAboveAnySkySource(skySources, y, io.github.limuqy.mc.hassium.compat.LevelHeightCompat.getMinBlockY(this.overworld()))) {
                    return false; // 源所在/更高 section 的 sky 层缺建层：heal 无法基于可见层打包
                }
            }
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * 打包前天空光校验（只读）——官方 {@code lightChunk} / {@code propagateLightSources}
     * 应已把源及其上方写成 15。缺失只告警，不回写 queuedSections。
     */
    public void fillSkySectionsForPacket(ChunkPos pos, LevelChunk chunk) {
        if (!isSkySeeded(pos, chunk)) {
            DebugLogger.warn(DebugLogger.LogType.ASYNC,
                    "[SHADOW_LIGHT] Sky seed missing ({}, {}) — verify-only after vanilla lightChunk",
                    pos.x, pos.z);
        }
    }

    /**
     * 打包可见的天空光是否已把「源及其上方」写成 15。
     */
    public boolean isSkySeeded(ChunkPos pos, LevelChunk chunk) {
        try {
            if (chunk == null || !this.overworld().dimensionType().hasSkyLight()) {
                return true;
            }
            net.minecraft.world.level.lighting.ChunkSkyLightSources sources =
                    chunk.getSkyLightSources();
            LevelLightEngine levelLightEngine = this.overworld().getChunkSource().getLightEngine();
            LightEngine<?, ?> skyEngine =
                    ((io.github.limuqy.mc.hassium.mixin.LevelLightEngineAccessor) levelLightEngine)
                            .hassium$getSkyEngine();
            if (skyEngine == null) {
                return true;
            }
            int minBlockY = io.github.limuqy.mc.hassium.compat.LevelHeightCompat.getMinBlockY(this.overworld());
            int minSection = io.github.limuqy.mc.hassium.compat.LevelHeightCompat.getMinSection(chunk);
            int maxSection = io.github.limuqy.mc.hassium.compat.LevelHeightCompat.getMaxSectionExclusive(chunk);
            for (int y = minSection; y < maxSection; y++) {
                if (!sectionAtOrAboveAnySkySource(sources, y, minBlockY)) {
                    continue;
                }
                SectionPos sp = SectionPos.of(pos, y);
                DataLayer sky = skyEngine.getDataLayerData(sp);
                int sectionTop = SectionPos.sectionToBlockCoord(y) + 15;
                for (int x = 0; x < 16; x++) {
                    for (int z = 0; z < 16; z++) {
                        int src = sources.getLowestSourceY(x, z);
                        if (src < minBlockY || src > sectionTop) {
                            continue;
                        }
                        int fromY = Math.max(src, SectionPos.sectionToBlockCoord(y));
                        for (int by = fromY; by <= sectionTop; by++) {
                            if (sky == null || sky.get(x, SectionPos.sectionRelative(by), z) != 15) {
                                return false;
                            }
                        }
                    }
                }
            }
            return true;
        } catch (Throwable t) {
            DebugLogger.warn(DebugLogger.LogType.ASYNC,
                    "[SHADOW_LIGHT] Sky seed check failed ({}, {}): {}", pos.x, pos.z, t.toString());
            return false;
        }
    }

    /** 是否存在真实天空源（非 {@code NEGATIVE_INFINITY} 哨兵）位于 sectionTop 或以下。 */
    static boolean sectionAtOrAboveAnySkySource(
            net.minecraft.world.level.lighting.ChunkSkyLightSources sources,
            int sectionY, int minBlockY) {
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

    /**
     * 磁盘命中 + hash 一致但 {@code !isLightCorrect}：本地重算光照（不请求网络全量）。
     * 清光后引擎传播期间保持 {@code isLightCorrect=false}，收敛由 finishLight /
     * confirmLightsCorrectIfConverged 写回。
     */
    public void relightChunk(ChunkPos pos, LevelChunk chunk) {
        chunk.setLightCorrect(false);
        io.github.limuqy.mc.hassium.storage.ShadowStorageHashes.markLightDirty(pos);
        clearChunkLight(pos, chunk);
        awaitLightTaskDrain(); // 清光投递 48+ PRE 任务：按柱排水（processRemoteHashes 批量 relight 叠加防护）
    }

    /**
     * 应用服务端分段增量（SectionDeltaS2CPacket）到已注入区块。
     * <p>
     * 步骤（镜像 {@code replaceWithPacketData} 语义，按 delta 包裁剪）：
     * <ol>
     *   <li>变更 sections：{@code LevelChunkSection.read(buf)} 就地覆盖
     *       （counts+states+biomes 官方网络格式，与服务端 serializeSection 对称）</li>
     *   <li>heightmaps：逐 type {@code setHeightmap}（delta 包随附服务端 rawData——
     *       直接改 section 不会自动更新高度图）</li>
     *   <li>blockEntity：服务端发整 chunk BE 快照 → 先清旧表，再镜像官方
     *       replaceWithPacketData 的 consumer（IMMEDIATE 创建 + type 校验 + load）</li>
     *   <li><b>光</b>：delta 不含光照 → 变更 section 清光（{@code queueSectionData(EMPTY)}，
     *       injectChunk 同款）→ 由调用方两阶段屏障（initializeLight → lightChunk）重算，
     *       不在此处 propagate（提前传播会被空层安装覆盖）；欠光由光照更新
     *       桥梁（collectLightUpdate → drainLightMasks）事件驱动补发</li>
     * </ol>
     * 完成后重算 contentHash 写存储桥（后续 hash 比对 / R2 落盘复用）。
     * 仅 {@code consumeLoop} 单线程调用；失败返回 false（调用方回退全量请求）。
     */
    public boolean applySectionDelta(ChunkPos pos,
                                     List<SectionDeltaS2CPacket.SectionData> changedSections,
                                     List<SectionDeltaS2CPacket.HeightmapData> heightmaps,
                                     List<SectionDeltaS2CPacket.BlockEntityData> blockEntities) {
        // 变更 section 清光投递与光屏障互斥（同 clearChunkLight；2026-08-14 NPE 同源）。
        synchronized (ShadowLightCompute.LIGHT_ENGINE_MUTEX) {
            try {
            LevelChunk chunk = injectedChunks.get(ChunkPos.asLong(pos.x, pos.z));
            if (chunk == null) {
                LOGGER.debug("Hassium: Shadow applySectionDelta chunk not injected ({}, {})", pos.x, pos.z);
                return false;
            }
            // 增量应用会就地覆盖 section/heightmap/BE/光，标记为需要 saveAll 重写。
            io.github.limuqy.mc.hassium.storage.ShadowStorageHashes.markContentDirty(pos);
            ThreadedLevelLightEngine lightEngine =
                    (ThreadedLevelLightEngine) this.overworld().getChunkSource().getLightEngine();
            // 1) sections 就地覆盖（先于 BE——BE 创建依赖新 block state）
            for (SectionDeltaS2CPacket.SectionData sd : changedSections) {
                LevelChunkSection section = chunk.getSection(sd.sectionIndex());
                FriendlyByteBuf buf = new FriendlyByteBuf(io.netty.buffer.Unpooled.wrappedBuffer(sd.blockData()));
                try {
                    section.read(buf);
                } finally {
                    buf.release();
                }
                // 2) 变更 section 清光：delta 无光，旧光已过期 → 由随后的两阶段光屏障重算。
                //    强制覆盖共享空层（notReady 移除对带邻域 section 永不生效，
                //    见 clearChunkLight 注释）；不在这里 propagate（会被空层安装覆盖白算）。
                SectionPos sp = SectionPos.of(pos, chunk.getSectionYFromSectionIndex(sd.sectionIndex()));
                lightEngine.queueSectionData(LightLayer.SKY, sp, EMPTY_LIGHT_LAYER);
                lightEngine.queueSectionData(LightLayer.BLOCK, sp, EMPTY_LIGHT_LAYER);
            }
            // 3) heightmaps 逐 type 覆盖
            Heightmap.Types[] types = Heightmap.Types.values();
            for (SectionDeltaS2CPacket.HeightmapData hm : heightmaps) {
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
            for (SectionDeltaS2CPacket.BlockEntityData bed : blockEntities) {
                net.minecraft.world.level.block.entity.BlockEntity be =
                        chunk.getBlockEntity(bed.pos(), LevelChunk.EntityCreationType.IMMEDIATE);
                if (be != null && bed.nbt() != null) {
                    BlockEntityCompat.loadFromTag(be, bed.nbt(), this.overworld().registryAccess());
                }
            }
            // 5) 重算 contentHash 写存储桥（R2 比对 / 断连落盘复用）
            try {
                long contentHash = io.github.limuqy.mc.hassium.cache.ChunkContentHashUtil
                        .combineSectionHashes(io.github.limuqy.mc.hassium.cache.ChunkContentHashUtil
                                .computeSectionHashes(chunk));
                io.github.limuqy.mc.hassium.storage.ShadowStorageHashes.put(pos, contentHash);
            } catch (Throwable hashError) {
                LOGGER.debug("Hassium: Shadow contentHash recompute failed for {}, skip hash write", pos);
            }
            return true;
        } catch (Throwable t) {
            LOGGER.warn("Hassium: Shadow applySectionDelta failed for {}", pos, t);
            return false;
        }
        }
    }

    /**
     * 实体操作串行锁：PersistentEntitySectionManager 的 EntityLookup/sectionStorage
     * 非线程安全（原版仅 server 主线程单写）。入口已统一 execute() 到影子主循环线程
     * （与方块操作同线程，天然串行），锁保留作防御（不再承担跨线程互斥）。
     */
    private final Object entityApplyLock = new Object();

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
    public void applyBlockUpdate(Packet<?> packet) {
        if (packet == null) {
            return;
        }
        try {
            this.execute(() -> {
                try {
                    if (packet instanceof ClientboundBlockUpdatePacket block) {
                        applyBlock(block.getPos(), block.getBlockState());
                    } else if (packet instanceof ClientboundSectionBlocksUpdatePacket section) {
                        java.util.Set<Long> affected = new java.util.HashSet<>();
                        section.runUpdates((pos, state) -> {
                            this.overworld().setBlock(pos, state, 3);
                            affected.add(ChunkPos.asLong(pos.getX() >> 4, pos.getZ() >> 4));
                        });
                        invalidateChunkContent(affected);
                    } else if (packet instanceof ClientboundBlockEntityDataPacket blockEntity) {
                        applyBlockEntity(blockEntity);
                    }
                } catch (Throwable t) {
                    LOGGER.debug("Hassium: Shadow applyBlockUpdate ignored: {}", t.toString());
                }
            });
        } catch (java.util.concurrent.RejectedExecutionException e) {
            // 主循环已停（断连竞态）：更新丢弃，数据由下次进服 hash 比对/直推兜底
        }
    }

    /** 单方块应用：注入区块直接 setBlockState（flags 与 {@code level.setBlock(pos, state, 3)} 同源）；未注入跳过并标脏。 */
    private void applyBlock(BlockPos pos, BlockState state) {
        long key = ChunkPos.asLong(pos.getX() >> 4, pos.getZ() >> 4);
        LevelChunk chunk = injectedChunks.get(key);
        if (chunk != null) {
            // review-fix: T13-FixT3Chunk-2：注入区块不经 ChunkMap，level.setBlock 会触发幻影 worldgen——直接对注入 chunk 应用
#if MC_VER < MC_1_21_5
            chunk.setBlockState(pos, state, false);
#else
            chunk.setBlockState(pos, state, 3);
#endif
        }
        invalidateChunkContent(java.util.Collections.singleton(key));
    }

    /** BE 更新应用：镜像原版 handleBlockEntityData（loadFromTag + setChanged），无 BE 则忽略。 */
    private void applyBlockEntity(ClientboundBlockEntityDataPacket packet) {
        ServerLevel level = this.overworld();
        long key = ChunkPos.asLong(packet.getPos().getX() >> 4, packet.getPos().getZ() >> 4);
        LevelChunk chunk = injectedChunks.get(key);
        if (chunk == null) {
            // review-fix: T13-FixT3Chunk-2：未注入——跳过应用并标脏（hash 比对触发全量回拉）
            invalidateChunkContent(java.util.Collections.singleton(key));
            return;
        }
        net.minecraft.world.level.block.entity.BlockEntity be = chunk.getBlockEntity(packet.getPos());
        if (be == null || packet.getTag() == null) {
            return;
        }
        BlockEntityCompat.loadFromTag(be, packet.getTag(), level.registryAccess());
        be.setChanged();
        // BE NBT 不进 chunkHash：只标脏落盘，不要丢掉方块 hash 表（否则下次比对无谓重算）。
        io.github.limuqy.mc.hassium.storage.ShadowStorageHashes.markContentDirty(new ChunkPos(key));
    }

    /**
     * 缓存命中后主控 BE 快照：覆盖影子柱 NBT 并标脏落盘。不改 contentHash（方块未变）。
     * 投递影子主循环；主循环已停则丢弃（下次进服再拉）。
     */
    public void applyBlockEntitySnapshot(ChunkPos pos,
                                         List<BlockEntityDataS2CPacket.BlockEntityData> blockEntities) {
        if (pos == null || blockEntities == null || blockEntities.isEmpty()) {
            return;
        }
        try {
            this.execute(() -> {
                LevelChunk chunk = injectedChunks.get(ChunkPos.asLong(pos.x, pos.z));
                if (chunk == null) {
                    return;
                }
                for (BlockEntityDataS2CPacket.BlockEntityData bed : blockEntities) {
                    if (bed == null || bed.nbt() == null) {
                        continue;
                    }
                    net.minecraft.world.level.block.entity.BlockEntity be = chunk.getBlockEntity(bed.pos());
                    if (be == null) {
                        continue;
                    }
                    CompoundTag copy = bed.nbt().copy();
                    copy.putInt("x", bed.pos().getX());
                    copy.putInt("y", bed.pos().getY());
                    copy.putInt("z", bed.pos().getZ());
                    BlockEntityCompat.loadFromTag(be, copy, this.overworld().registryAccess());
                    be.setChanged();
                }
                io.github.limuqy.mc.hassium.storage.ShadowStorageHashes.markContentDirty(pos);
            });
        } catch (java.util.concurrent.RejectedExecutionException ignored) {
            // 主循环已停：下次进服 hash 命中会再拉 BE
        }
    }

    /**
     * 内容失效（chunkKey 去重集合）：移除 hash 缓存（下次比对不得误命中）。
     * 光照交给柱上 {@code isLightCorrect} / 随后全量注入清光。
     */
    private void invalidateChunkContent(java.util.Set<Long> chunkKeys) {
        for (long key : chunkKeys) {
            ChunkPos pos = new ChunkPos(key);
            io.github.limuqy.mc.hassium.storage.ShadowStorageHashes.remove(pos);
            io.github.limuqy.mc.hassium.storage.ShadowStorageHashes.markContentDirty(key);
        }
    }

    /**
     * 应用一个官方实体同步包到影子端（任意线程可调；内部 {@code execute()} 投递影子端
     * 主循环线程，与 {@link #applyBlockUpdate} 同模式——方块/实体操作同线程串行）。
     * <p>
     * 客户端纯转发（T3 mixin 侧只调本方法），进程内对象直传（零编码零压缩）。
     * 按 instanceof 分发 7 类包，镜像原版 ClientPacketListener handler 的实体语义，
     * 但影子端不 tick 实体：全部为瞬时状态操作（挂载/assignValues/setPos/速度/移除），
     * 无插值、无 tick 循环、不阻塞主线程。
     * <p>
     * 容错：实体不存在/类型不匹配/异常一律静默（debug 日志），与 handler 容错一致；
     * 主循环已停（断连/关停）时投递被 {@code RejectedExecutionException} 兜底丢弃，无残留。
     */
    public void applyEntityPacket(Packet<?> packet) {
        try {
            this.execute(() -> {
                synchronized (entityApplyLock) {
                    try {
                        if (packet instanceof ClientboundAddEntityPacket add) {
                            applyAddEntity(add);
                        } else if (packet instanceof ClientboundSetEntityDataPacket data) {
                            applySetEntityData(data);
                        } else if (packet instanceof ClientboundMoveEntityPacket move) {
                            applyMoveEntity(move);
                        } else if (packet instanceof ClientboundTeleportEntityPacket teleport) {
                            applyTeleportEntity(teleport);
                        } else if (packet instanceof ClientboundSetEntityMotionPacket motion) {
                            applySetEntityMotion(motion);
                        } else if (packet instanceof ClientboundRotateHeadPacket head) {
                            applyRotateHead(head);
                        } else if (packet instanceof ClientboundRemoveEntitiesPacket remove) {
                            applyRemoveEntities(remove);
                        }
                    } catch (Throwable t) {
                        LOGGER.debug("Hassium: Shadow applyEntityPacket ignored: {}", t.toString());
                    }
                }
            });
        } catch (java.util.concurrent.RejectedExecutionException e) {
            // review-fix: T13-FixT3Chunk-3：主循环已停（断连竞态）：更新丢弃，数据由下次进服 hash 比对/直推兜底
        }
    }

    /**
     * Add：镜像原版 handleAddEntity 重建链 —— {@code type.create(level[, LOAD])} →
     * {@code entity.recreateFromPacket(packet)}（内部 setId/UUID/位置/旋转/运动/data）→
     * {@code ServerLevel.addFreshEntity} 挂载（LevelChunk.addEntity 全段空方法不可用，
     * 见 T1 事实表 ④）。
     * <p>
     * 挂载成功后把实体所在 chunk 可见性置 ENTITY_TICKING：默认 HIDDEN 下实体不进
     * visibleEntityStorage（getEntity(id) 查不到）且 saveAll 走 processChunkUnload
     * 摘除实体；ENTITY_TICKING = accessible（可查、可存）但不 ticking（不进 entityTickList，
     * 影子端零 tick 红线保持）。
     */
    private void applyAddEntity(ClientboundAddEntityPacket packet) {
        ServerLevel level = this.overworld();
        if (level.getEntity(packet.getId()) != null) {
            return; // 防重复（原版 UUID 去重等价：addFreshEntity 内部按 UUID 拒绝重复）
        }
        EntityType<?> type = packet.getType();
        if (type == EntityType.PLAYER) {
            return; // 影子端不重建玩家（1.21.11 原版走 RemotePlayer 专用路径，服务端也不发 AddEntity 玩家）
        }
        Entity entity = type.create(level
#if MC_VER >= MC_1_21_2
                , EntitySpawnReason.LOAD
#endif
        );
        if (entity == null) {
            return;
        }
        entity.recreateFromPacket(packet);
        if (!level.addFreshEntity(entity)) {
            return;
        }
        this.entityManager().updateChunkStatus(entity.chunkPosition(), FullChunkStatus.ENTITY_TICKING);
    }

    /** SetEntityData：官方 handler 同款 {@code assignValues}，无解析无消费。 */
    private void applySetEntityData(ClientboundSetEntityDataPacket packet) {
        ServerLevel level = this.overworld();
        Entity entity = level.getEntity(packet.id());
        if (entity != null) {
            entity.getEntityData().assignValues(packet.packedItems());
        }
    }

    /**
     * MoveEntity（Pos/PosRot/Rot 三重载同构）：相对位移 → 绝对（客户端 VecDeltaCodec
     * 同语义：base + delta/4096；影子端实体位置 = 上次包应用后位置，故当前坐标 + delta）。
     * 旋转按 hasRotation 决定，getter 三段漂移由 EntityPacketCompat 归一。
     */
    private void applyMoveEntity(ClientboundMoveEntityPacket packet) {
        ServerLevel level = this.overworld();
        Entity entity = packet.getEntity(level);
        if (entity == null) {
            return;
        }
        if (packet.hasPosition()) {
            entity.setPos(
                    entity.getX() + packet.getXa() / 4096.0,
                    entity.getY() + packet.getYa() / 4096.0,
                    entity.getZ() + packet.getZa() / 4096.0);
        }
        if (packet.hasRotation()) {
            entity.setYRot(EntityPacketCompat.moveYRot(packet));
            entity.setXRot(EntityPacketCompat.moveXRot(packet));
        }
        entity.setOnGround(packet.isOnGround());
    }

    /**
     * TeleportEntity：段 A–D 包字段直取绝对坐标；段 E+ record 经
     * {@code PositionMoveRotation.calculateAbsolute(prev, change, relatives)} 计算
     * （prev = 实体当前位置/旋转/已知运动，EntityPacketCompat 归一）。
     * yHeadRot 同步 yRot（镜像服务端 teleportSetPosition 语义）。
     */
    private void applyTeleportEntity(ClientboundTeleportEntityPacket packet) {
        ServerLevel level = this.overworld();
        Entity entity = level.getEntity(EntityPacketCompat.teleportId(packet));
        if (entity == null) {
            return;
        }
        EntityPacketCompat.TeleportState tp = EntityPacketCompat.teleportState(entity, packet);
        entity.setPos(tp.position().x, tp.position().y, tp.position().z);
        entity.setYRot(tp.yRot());
        entity.setYHeadRot(tp.yRot());
        entity.setXRot(tp.xRot());
        if (tp.deltaMovement() != null) {
            entity.setDeltaMovement(tp.deltaMovement());
        }
        entity.setOnGround(tp.onGround());
    }

    /** Motion：setDeltaMovement（A–C int / D–G double / H+ Vec3 由 EntityPacketCompat 归一）。 */
    private void applySetEntityMotion(ClientboundSetEntityMotionPacket packet) {
        ServerLevel level = this.overworld();
        Entity entity = level.getEntity(packet.getId());
        if (entity != null) {
            entity.setDeltaMovement(EntityPacketCompat.motionVec(packet));
        }
    }

    /** RotateHead：setYHeadRot（A–D byte / E+ float 由 EntityPacketCompat 归一）。 */
    private void applyRotateHead(ClientboundRotateHeadPacket packet) {
        ServerLevel level = this.overworld();
        Entity entity = packet.getEntity(level);
        if (entity != null) {
            entity.setYHeadRot(EntityPacketCompat.headYRot(packet));
        }
    }

    /**
     * Remove：IntList 逐个按 id 移除。移除路径查证（T1 事实表 ④）：服务端无
     * {@code Level.removeEntity(int, reason)}（仅 ClientLevel 专有）；立即生效且不依赖
     * tick 的公开路径 = {@code entity.remove(DISCARDED)} → setRemoved → levelCallback.onRemove
     * 同步摘除（section + visibleEntityStorage + knownUuids），两版本同构。
     */
    private void applyRemoveEntities(ClientboundRemoveEntitiesPacket packet) {
        ServerLevel level = this.overworld();
        for (int id : packet.getEntityIds()) {
            Entity entity = level.getEntity(id);
            if (entity != null) {
                entity.remove(Entity.RemovalReason.DISCARDED);
            }
        }
    }

    /** 影子端实体管理器（ServerLevel 私有字段，经 ServerLevelAccessor）。 */
    private PersistentEntitySectionManager<Entity> entityManager() {
        return ((ServerLevelAccessor) this.overworld()).hassium$getEntityManager();
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
        if (storage != null) {
            byte[] nbt = storage.readChunk(pos);
            if (nbt != null) {
                LevelChunk parsed = parseNbtBytes(pos, nbt);
                if (parsed != null) {
                    return parsed;
                }
            }
        }
        try {
            net.minecraft.server.level.ChunkMap chunkMap =
                    ((net.minecraft.server.level.ServerChunkCache) this.overworld().getChunkSource()).chunkMap;
            io.github.limuqy.mc.hassium.mixin.ChunkMapAccessor acc =
                    (io.github.limuqy.mc.hassium.mixin.ChunkMapAccessor) chunkMap;
#if MC_VER < MC_1_21_2
            // 1.20.1~1.21.1：readChunk 拿原始 NBT（126 已由 MixinRegionFile 读 hook 解压），
            // 再走 ChunkSerializer.read 官方解析（含光照恢复）。
            java.util.concurrent.CompletableFuture<java.util.Optional<net.minecraft.nbt.CompoundTag>> future =
                    acc.hassium$readChunk(pos);
            long deadline = System.nanoTime() + GENERATION_TIMEOUT_NANOS;
            while (!future.isDone()) {
                if (System.nanoTime() > deadline) {
                    LOGGER.warn("Hassium: Shadow loadFromDisk timeout ({}, {})", pos.x, pos.z);
                    return null;
                }
                try {
                    Thread.sleep(10L);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return null;
                }
            }
            net.minecraft.nbt.CompoundTag tag = future.join().orElse(null);
            if (tag == null) {
                return null; // 存档无此柱
            }
            net.minecraft.world.level.chunk.ChunkAccess accChunk =
#if MC_VER < MC_1_21_1
                    net.minecraft.world.level.chunk.storage.ChunkSerializer.read(
                            this.overworld(), this.overworld().getPoiManager(), pos, tag);
#else
                    // 1.21.1+：read 增加 RegionStorageInfo（官方 ChunkMap 构造同款取值）
                    net.minecraft.world.level.chunk.storage.ChunkSerializer.read(
                            this.overworld(), this.overworld().getPoiManager(),
                            new net.minecraft.world.level.chunk.storage.RegionStorageInfo(
                                    this.storageSource.getLevelId(), this.overworld().dimension(), "chunk"),
                            pos, tag);
#endif
            return toLevelChunk(accChunk, pos);
#else
            // 1.21.2+：官方 scheduleChunkLoad 完整链（SerializableChunkData 解析）。
            // TODO(版本推广)：1.21.2+ 分段适配（readChunk → SerializableChunkData.parse+read）
            CompletableFuture<?> future = acc.hassium$scheduleChunkLoad(pos);
            long deadline = System.nanoTime() + GENERATION_TIMEOUT_NANOS;
            while (!future.isDone()) {
                if (System.nanoTime() > deadline) {
                    return null;
                }
                try {
                    Thread.sleep(10L);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return null;
                }
            }
            net.minecraft.world.level.chunk.ChunkAccess accChunk =
                    (net.minecraft.world.level.chunk.ChunkAccess) future.join();
            if (accChunk == null) {
                return null;
            }
            return toLevelChunk(accChunk, pos);
#endif
        } catch (Throwable t) {
            LOGGER.debug("Hassium: Shadow loadFromDisk failed for {}", pos, t);
            return null;
        }
    }

    /** 官方加载产物为 ProtoChunk（ChunkSerializer.read 语义）：FULL 转换同款。 */
    private LevelChunk toLevelChunk(net.minecraft.world.level.chunk.ChunkAccess accChunk, ChunkPos pos) {
        // 读盘命中（R2 缓存复用）记一次访问：容量清理热度评分用。
        // 覆盖 consumeLoop 磁盘优先路径（不经 injectLoadedChunk）与
        // processRemoteHashes 读盘比对路径（两者都经 loadFromDisk）。
        ShadowCacheEviction.recordAccess(pos);
        if (accChunk instanceof LevelChunk levelChunk) {
            return levelChunk;
        }
        return new LevelChunk(this.overworld(), (net.minecraft.world.level.chunk.ProtoChunk) accChunk,
                chunk -> { });
    }

    /** 注入区块表取用（打包/保存；未注入返回 null）。 */
    public net.minecraft.world.level.chunk.LevelChunk injectedChunk(int x, int z) {
        return injectedChunks.get(ChunkPos.asLong(x, z));
    }

    /**
     * holder 上已有 {@code INITIALIZE_LIGHT} 的 parent chunk（与原版
     * {@code getChunkForLighting} 同一条件）。无 holder 视为视距边缘。
     */
    public boolean hasInitializeLightParent(int x, int z) {
        try {
            return ShadowChunkMapCompat.hasInitializeLightParent(
                    (ServerChunkCache) this.overworld().getChunkSource(), x, z);
        } catch (Throwable ignored) {
            return false;
        }
    }

    public boolean hasVisibleChunkHolder(int x, int z) {
        try {
            return ShadowChunkMapCompat.hasVisibleHolder(
                    (ServerChunkCache) this.overworld().getChunkSource(), x, z);
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** 当前注入票数量（与注入表同阶；测试/泄漏诊断）。 */
    int injectTicketCount() {
        return injectTickets.size();
    }

    boolean hasInjectTicket(ChunkPos pos) {
        return pos != null && injectTickets.contains(ChunkPos.asLong(pos.x, pos.z));
    }

    /**
     * 注入成功后加 {@code TicketType.UNKNOWN}、{@code ChunkLevel.byStatus(FULL)}
     * （非 32/31 ticking）。主循环 {@code pollTask} 会 {@code runDistanceManagerUpdates}。
     */
    void registerInjectTicket(ChunkPos pos) {
        if (pos == null) {
            return;
        }
        long key = ChunkPos.asLong(pos.x, pos.z);
        if (!ShadowChunkMapCompat.rememberTicketKey(injectTickets, key)) {
            return;
        }
        ChunkPos ticketPos = new ChunkPos(pos.x, pos.z);
        runOnShadowMain(() -> {
            try {
                ServerChunkCache cache = (ServerChunkCache) this.overworld().getChunkSource();
                ShadowChunkMapCompat.addFullUnknownTicket(cache, ticketPos);
            } catch (Throwable t) {
                injectTickets.remove(key);
                LOGGER.debug("Hassium: shadow inject ticket add failed for {}", ticketPos);
            }
        });
    }

    /** 卸载成功后还同一 UNKNOWN 票，避免 holder 泄漏。 */
    void unregisterInjectTicket(ChunkPos pos) {
        if (pos == null) {
            return;
        }
        long key = ChunkPos.asLong(pos.x, pos.z);
        if (!ShadowChunkMapCompat.forgetTicketKey(injectTickets, key)) {
            return;
        }
        ChunkPos ticketPos = new ChunkPos(pos.x, pos.z);
        runOnShadowMain(() -> {
            try {
                ServerChunkCache cache = (ServerChunkCache) this.overworld().getChunkSource();
                ShadowChunkMapCompat.removeFullUnknownTicket(cache, ticketPos);
            } catch (Throwable t) {
                LOGGER.debug("Hassium: shadow inject ticket remove failed for {}", ticketPos);
            }
        });
    }

    private void runOnShadowMain(Runnable job) {
        if (this.isSameThread()) {
            job.run();
        } else {
            this.execute(job);
        }
    }

    /**
     * 读盘命中（hash 比对一致）区块加载进影子端表：后续 hash 到达直接内存比对
     * （无需再读盘）；saveAll 落盘复用同一表。contentHash 已由 MixinRegionFile
     * 读盘回填，此处不再 computeSectionHashes。光照是否续算由调用方按
     * {@code chunk.isLightCorrect()}（NBT isLightOn）决定，本方法不清光。
     * 默认视为 clean（磁盘已有该柱），saveAll 不重写；本地生成/盲预生成等新数据
     * 请使用 {@link #injectLoadedChunk(ChunkPos, LevelChunk, boolean)} 并传 true。
     */
    public void injectLoadedChunk(ChunkPos pos, net.minecraft.world.level.chunk.LevelChunk chunk) {
        injectLoadedChunk(pos, chunk, false);
    }

    /**
     * 加载进影子端表并显式指定是否需要 saveAll 重写。
     *
     * @param dirty true = 该柱是本地新生成/与磁盘不一致的数据，saveAll 必须重写；
     *              false = 该柱刚从磁盘加载且未被修改，可跳过重写。
     */
    public void injectLoadedChunk(ChunkPos pos, net.minecraft.world.level.chunk.LevelChunk chunk, boolean dirty) {
        long key = ChunkPos.asLong(pos.x, pos.z);
        injectedChunks.put(key, chunk);
        if (dirty) {
            io.github.limuqy.mc.hassium.storage.ShadowStorageHashes.markContentDirty(key);
        } else {
            io.github.limuqy.mc.hassium.storage.ShadowStorageHashes.claimDirty(key);
        }
        registerInjectTicket(pos);
    }

    /**
     * 与原版 {@code ChunkSerializer} 对齐：{@code isLightCorrect} 决定落盘是否写
     * {@code isLightOn}。变更后加入 dirtyChunks，saveAll 才会重写该柱。
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
        if (correct) {
            io.github.limuqy.mc.hassium.storage.ShadowStorageHashes.markLightReady(pos);
        } else {
            io.github.limuqy.mc.hassium.storage.ShadowStorageHashes.markLightDirty(pos);
        }
    }

    /**
     * 引擎任务排空后，仅把「层已齐全」的内存柱改回 {@code isLightCorrect=true}，
     * 随后 saveAll 写入 isLightOn。层不齐的柱保持 false，避免误 reuse 空光。
     */
    public void confirmLightsCorrectIfConverged() {
        if (!isLightConverged()) {
            return;
        }
        boolean any = false;
        for (Map.Entry<Long, LevelChunk> e : injectedChunks.entrySet()) {
            LevelChunk chunk = e.getValue();
            if (chunk == null || chunk.isLightCorrect()) {
                continue;
            }
            ChunkPos pos = chunk.getPos();
            if (!isChunkLightComplete(pos, chunk)) {
                continue;
            }
            chunk.setLightCorrect(true);
            io.github.limuqy.mc.hassium.storage.ShadowStorageHashes.markLightReady(pos);
            any = true;
        }
        if (any) {
            DebugLogger.info(DebugLogger.LogType.ASYNC,
                    "[SHADOW_LIGHT] Global convergence confirmed, isLightCorrect restored");
        }
    }

    /**
     * 影子端存档布隆位图（R2 重连握手上报）：扫描全部 region 文件头部位图
     * （每 region 256 块存在位），存在的区块放入 Bloom（含 dimension 混淆，
     * 与 {@code ChunkBloomFilter.put} 语义一致）。维度以存档维度名为准
     * （overworld 存档单维度：影子端仅存主世界）。
     * <p>
     * 线程：任意线程（region 头读取是轻量 IO，不上锁；调用方在后台池）。
     */
    public io.github.limuqy.mc.hassium.cache.client.ChunkBloomFilter buildBloomFilter() {
        io.github.limuqy.mc.hassium.cache.client.ChunkBloomFilter filter =
                io.github.limuqy.mc.hassium.cache.client.ChunkBloomFilter.createDefault();
        String dimension = this.overworld().dimension()
#if MC_VER < MC_1_21_11
                .location()
#else
                .identifier()
#endif
                .toString();
        // park 异步 save 尚未刷盘时，热表仍有柱：一并放入 bloom。
        for (Map.Entry<Long, LevelChunk> e : injectedChunks.entrySet()) {
            long key = e.getKey();
            filter.put((int) key, (int) (key >> 32), dimension);
        }
        // saveAll 已摘注入表后 HashIndex 仍在：必须进 bloom，否则空图被当 ROUND1 直推。
        for (Long key : io.github.limuqy.mc.hassium.storage.ShadowStorageHashes.hashKeys()) {
            filter.put((int) (long) key, (int) (key >> 32), dimension);
        }
        try {
            java.io.File regionDir =
                    new java.io.File(this.storageSource.getDimensionPath(this.overworld().dimension()).toFile(), "region");
            java.io.File[] files = regionDir.listFiles((dir, name) -> name.endsWith(".mca"));
            if (files == null) {
                return filter;
            }
            for (java.io.File f : files) {
                int regionX;
                int regionZ;
                try {
                    java.util.regex.Matcher m = java.util.regex.Pattern
                            .compile("r\\.(-?\\d+)\\.(-?\\d+)\\.mca").matcher(f.getName());
                    if (!m.matches()) {
                        continue;
                    }
                    regionX = Integer.parseInt(m.group(1));
                    regionZ = Integer.parseInt(m.group(2));
                } catch (Exception e) {
                    continue;
                }
                try (java.io.RandomAccessFile raf = new java.io.RandomAccessFile(f, "r")) {
                    byte[] header = new byte[4096];
                    raf.readFully(header);
                    for (int i = 0; i < 1024; i++) {
                        if (io.github.limuqy.mc.hassium.storage.RegionCache.locationAt(header, i) != 0) {
                            int chunkX = regionX * 32 + (i & 31);
                            int chunkZ = regionZ * 32 + (i >> 5);
                            filter.put(chunkX, chunkZ, dimension);
                        }
                    }
                } catch (Throwable t) {
                    LOGGER.debug("Hassium: Shadow bloom scan failed for {}", f.getName(), t);
                }
            }
        } catch (Throwable t) {
            LOGGER.debug("Hassium: Shadow bloom build failed", t);
        }
        return filter;
    }

    /**
     * 单柱落盘：标脏后由 {@link io.github.limuqy.mc.hassium.storage.ShadowStorageManager}
     * 从当前 {@code LevelChunk} 序列化压缩写盘。任意线程可调。
     *
     * @return true=已提交写队列；false=写 gate 拒绝或 flush 失败
     */
    public boolean saveChunkToDisk(ChunkPos pos, LevelChunk chunk) {
        if (!ownShutdownInProgress
                && !ShadowServerRegistry.getInstance().isPreviousShutdownComplete()) {
            return false;
        }
        io.github.limuqy.mc.hassium.storage.ShadowStorageHashes.markContentDirty(pos);
        if (storage == null) {
            return false;
        }
        return storage.flushColumn(pos,
                io.github.limuqy.mc.hassium.storage.ShadowStorageManager.DEFAULT_FLUSH_TIMEOUT_MS);
    }

    public io.github.limuqy.mc.hassium.storage.ShadowStorageManager storage() {
        return storage;
    }

    void closeStorage() {
        if (storage != null) {
            storage.close();
        }
    }

    /** 原版序列化（版本差异封装；flush 时从 LevelChunk 取 NBT）。 */
    private net.minecraft.nbt.CompoundTag serializeChunkForSave(ServerLevel level, LevelChunk chunk) {
#if MC_VER < MC_1_21_2
        return net.minecraft.world.level.chunk.storage.ChunkSerializer.write(level, chunk);
#else
        return net.minecraft.world.level.chunk.storage.SerializableChunkData
                .copyOf(level, chunk).write();
#endif
    }

    private byte[] serializeInjectedColumn(ChunkPos pos) {
        LevelChunk chunk = injectedChunks.get(ChunkPos.asLong(pos.x, pos.z));
        if (chunk == null) {
            return null;
        }
        try {
            if (io.github.limuqy.mc.hassium.storage.ShadowStorageHashes.get(pos) == null) {
                try {
                    long contentHash = io.github.limuqy.mc.hassium.cache.ChunkContentHashUtil
                            .combineSectionHashes(io.github.limuqy.mc.hassium.cache.ChunkContentHashUtil
                                    .computeSectionHashes(chunk));
                    io.github.limuqy.mc.hassium.storage.ShadowStorageHashes.put(pos, contentHash);
                } catch (Throwable hashError) {
                    LOGGER.debug("Hassium: Shadow flush hash backfill failed for {}", pos);
                }
            }
            net.minecraft.nbt.CompoundTag nbt = serializeChunkForSave(this.overworld(), chunk);
            java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
            net.minecraft.nbt.NbtIo.write(nbt, new java.io.DataOutputStream(baos));
            return baos.toByteArray();
        } catch (Throwable t) {
            LOGGER.warn("Hassium: Shadow column serialize failed for ({}, {})", pos.x, pos.z, t);
            return null;
        }
    }

    private LevelChunk parseNbtBytes(ChunkPos pos, byte[] nbtBytes) {
        try {
            net.minecraft.nbt.CompoundTag tag = net.minecraft.nbt.NbtIo.read(
                    new java.io.DataInputStream(new java.io.ByteArrayInputStream(nbtBytes)));
            if (tag == null) {
                return null;
            }
            net.minecraft.world.level.chunk.ChunkAccess accChunk =
#if MC_VER < MC_1_21_1
                    net.minecraft.world.level.chunk.storage.ChunkSerializer.read(
                            this.overworld(), this.overworld().getPoiManager(), pos, tag);
#elif MC_VER < MC_1_21_2
                    net.minecraft.world.level.chunk.storage.ChunkSerializer.read(
                            this.overworld(), this.overworld().getPoiManager(),
                            new net.minecraft.world.level.chunk.storage.RegionStorageInfo(
                                    this.storageSource.getLevelId(), this.overworld().dimension(), "chunk"),
                            pos, tag);
#else
                    null;
#endif
#if MC_VER >= MC_1_21_2
            LOGGER.debug("Hassium: parseNbtBytes 1.21.2+ not wired; falling back to vanilla load");
            return null;
#else
            return toLevelChunk(accChunk, pos);
#endif
        } catch (Throwable t) {
            LOGGER.debug("Hassium: parseNbtBytes failed for {}", pos, t);
            return null;
        }
    }

    /**
     * 单柱卸载（T5）：脏则先 flush 该柱再摘 LevelChunk。不删盘、不卸 hash（R2 探活用）。
     */
    public boolean unloadChunk(ChunkPos pos, LevelChunk chunk) {
        if (!isLightConverged()) {
            chunk.setLightCorrect(false);
            io.github.limuqy.mc.hassium.storage.ShadowStorageHashes.markLightDirty(pos);
        }
        if (!ownShutdownInProgress
                && !ShadowServerRegistry.getInstance().isPreviousShutdownComplete()) {
            return false;
        }
        if (storage != null
                && io.github.limuqy.mc.hassium.storage.ShadowStorageHashes.isDirty(pos)
                && !storage.flushColumn(pos,
                io.github.limuqy.mc.hassium.storage.ShadowStorageManager.DEFAULT_FLUSH_TIMEOUT_MS)) {
            return false;
        }
        long key = ChunkPos.asLong(pos.x, pos.z);
        injectedChunks.remove(key, chunk);
        unregisterInjectTicket(pos);
        if (storage != null) {
            storage.unmountIdleRegions();
        }
        return true;
    }

    /** 注入区块表条目视图（T5 卸载扫描遍历用；弱一致迭代，任意线程）。 */
    public java.util.Set<java.util.Map.Entry<Long, LevelChunk>> injectedChunkEntries() {
        return injectedChunks.entrySet();
    }

    /**
     * 断连/关停保存：只扫 HashIndex 脏∩注入柱，从 LevelChunk 序列化压缩落盘。
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
            if (storage != null
                    && (ownShutdownInProgress
                    || ShadowServerRegistry.getInstance().isPreviousShutdownComplete())) {
                for (int pass = 0; pass < 3; pass++) {
                    io.github.limuqy.mc.hassium.storage.ShadowStorageManager.FlushResult result =
                            storage.flush(io.github.limuqy.mc.hassium.storage.ShadowStorageManager
                                    .DEFAULT_FLUSH_TIMEOUT_MS);
                    savedCount += result.written();
                    abandoned += result.abandoned();
                    timedOut |= result.timedOut();
                    if (result.written() == 0) {
                        break;
                    }
                }
                storage.unmountIdleRegions();
            }
            try {
                this.entityManager().saveAll();
            } catch (Throwable t) {
                LOGGER.warn("Hassium: Shadow entity save failed", t);
            }
            try {
                flushChunkWorker(this.overworld());
            } catch (Throwable t) {
                LOGGER.debug("Hassium: Shadow entity/poi flush failed", t);
            }
            ShadowCacheEviction.save(worldRoot);
            long elapsedMs = (System.nanoTime() - saveStartNs) / 1_000_000L;
            LOGGER.info("Hassium: Shadow saveAll done in {}ms, saved={} abandoned={} timedOut={} injected={} dirtyLeft={}",
                    elapsedMs, savedCount, abandoned, timedOut, injectedChunks.size(),
                    io.github.limuqy.mc.hassium.storage.ShadowStorageHashes.dirtyKeys().size());
        } catch (Throwable t) {
            LOGGER.warn("Hassium: Shadow server save failed", t);
        }
    }

    /** 同步等待 ChunkMap 的 IOWorker 队列落盘（版本差异封装）。 */
    private void flushChunkWorker(ServerLevel level) {
#if MC_VER < MC_1_21_11
        level.getChunkSource().chunkMap.flushWorker();
#else
        level.getChunkSource().chunkMap.synchronize(true).join();
#endif
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
            ThreadedLevelLightEngine engine =
                    (ThreadedLevelLightEngine) this.overworld().getChunkSource().getLightEngine();
            if (engine.hasLightWork()) {
                return false;
            }
            ThreadedLevelLightEngineAccessor acc = (ThreadedLevelLightEngineAccessor) engine;
            if (!acc.hassium$getLightTasks().isEmpty()) {
                return false;
            }
            return true;
        } catch (Throwable t) {
            return false;
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
     * 影子服务端主循环：持续驱动 {@link ServerChunkCache#pollTask()}。
     * <p>
     * 本 server 的 ServerChunkCache.mainThread 是装配线程（=本循环线程），
     * pollTask 覆写链 = runDistanceManagerUpdates（提交 worldgen 任务）+ 队列回调，
     * 等价 GameTestRunner 对 GameTestServer 的 tick 驱动，但不跑完整 tick（无玩家/实体）。
     */
    void runMainLoop() {
        ServerChunkCache cache = (ServerChunkCache) this.overworld().getChunkSource();
        // 服务端光照任务驱动：1.20.1~1.21.10 的任务邮箱由 ServerLevel.tick 的
        // tryScheduleUpdate 投递（影子端不跑完整 tick）；1.21.11 ConsecutiveExecutor
        // 自驱动，此处幂等无害。pre-update 任务（queueSectionData/updateSectionStatus/
        // propagateLightSources）与传播（runLightUpdates）都经它执行。
        ThreadedLevelLightEngine lightEngine = cache.getLightEngine();
        while (!Thread.currentThread().isInterrupted()) {
            boolean worked;
            try {
                worked = cache.pollTask();
            } catch (Throwable ignored) {
                break; // server 已 halt
            }
            try {
                lightEngine.tryScheduleUpdate();
            } catch (Throwable ignored) {
                // 光照任务驱动失败不影响 worldgen 主循环
            }
            if (!worked) {
                // managedBlock 同款：pollTask 覆写优先 runDistanceManagerUpdates，
                // 只有其返回 false 的间隙才消费 mainThreadProcessor 队列；park 100µs 保证高频重试。
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

    /** shutdown 用：持久世界根（LevelStorageAccess 两版本无统一目录 getter）。 */
    java.nio.file.Path worldRoot() {
        return worldRoot;
    }

    /** 影子端世界 region 目录（容量统计/清理扫描用；与 buildBloomFilter 同源）。 */
    java.nio.file.Path regionDir() {
        return this.storageSource.getDimensionPath(this.overworld().dimension()).resolve("region");
    }

    /**
     * 删除磁盘区块（缓存清理调用，任意线程）：chunkMap.write(pos, null) →
     * IOWorker 异步 → RegionFile.clear（offset 置 0 + 释放扇区，下次写入复用）。
     * 内存注入表 / hash 桥 / 热度索引同步移除，防后续比对误命中。
     * <p>
     * 版本差异：1.21.2+ 的 write 接收 Supplier，传 {@code () -> null} 与官方
     * {@code IOWorker.STORE_EMPTY} 同语义（PendingStore.data=null → clear）。
     */
    public void deleteChunk(ChunkPos pos) {
        long key = ChunkPos.asLong(pos.x, pos.z);
        injectedChunks.remove(key);
        ShadowCacheEviction.remove(pos);
        if (!ownShutdownInProgress
                && !ShadowServerRegistry.getInstance().isPreviousShutdownComplete()) {
            io.github.limuqy.mc.hassium.storage.ShadowStorageHashes.remove(pos);
            return;
        }
        if (storage != null) {
            storage.deleteColumn(pos);
        } else {
            io.github.limuqy.mc.hassium.storage.ShadowStorageHashes.remove(pos);
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
        if (storage != null) {
            storage.unmountIdleRegions();
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
                // 实体存储：ServerLevel.entityManager（1.21.11 为字段）→ permanentStorage = EntityStorage
                io.github.limuqy.mc.hassium.mixin.PersistentEntitySectionManagerAccessor em =
                        (io.github.limuqy.mc.hassium.mixin.PersistentEntitySectionManagerAccessor)
                                (Object) ((io.github.limuqy.mc.hassium.mixin.ServerLevelAccessor)
                                        (Object) level).hassium$getEntityManager();
                refreshRegionStorageFromHop((io.github.limuqy.mc.hassium.mixin.EntityStorageAccessor)
                        (Object) em.hassium$getPermanentStorage());
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
#if MC_VER < MC_1_21_1
                crashReport.getFriendlyReport()
#else
                crashReport.getFriendlyReport(ReportType.CRASH)
#endif
        );
    }

    @Override
    public boolean isHardcore() {
        return false;
    }

#if MC_VER >= MC_1_20_5 && MC_VER < MC_1_21_11
    /** 1.20.5-1.21.10 段 MinecraftServer 抽象方法（1.21.11 起有默认实现）。 */
    @Override
    public boolean isTickTimeLoggingEnabled() {
        return false;
    }
#endif

#if MC_VER >= MC_1_20_5 && MC_VER < MC_1_21_11
    private final SampleLogger sampleLogger = new LocalSampleLogger(4);

    @Override
    public SampleLogger getTickTimeLogger() {
        return this.sampleLogger;
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
    public boolean isSingleplayerOwner(GameProfile profile) {
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
    public boolean isSingleplayerOwner(NameAndId nameAndId) {
        return false;
    }

    @Override
    public int getMaxPlayers() {
        return 1;
    }
#else
    @Override
    public LevelBasedPermissionSet operatorUserPermissions() {
        return LevelBasedPermissionSet.ALL;
    }

    @Override
    public PermissionSet getFunctionCompilationPermissions() {
        return LevelBasedPermissionSet.OWNER;
    }

    @Override
    public boolean useNativeTransport() {
        return false;
    }

    @Override
    public boolean isSingleplayerOwner(NameAndId nameAndId) {
        return false;
    }

    @Override
    public int getMaxPlayers() {
        return 1;
    }

    @Override
    public SampleLogger getTickTimeLogger() {
        return this.sampleLogger;
    }

    @Override
    public boolean isTickTimeLoggingEnabled() {
        return false;
    }
#endif

    private static Services buildNoServices() {
#if MC_VER < MC_1_21_9
        return new Services(null, ServicesKeySet.EMPTY, null, null);
#else
        return new Services(null, ServicesKeySet.EMPTY, null,
                new MockUserNameToIdResolver(), new MockProfileResolver());
#endif
    }

#if MC_VER >= MC_1_21_11
    private final SampleLogger sampleLogger = new LocalSampleLogger(4);
#endif

#if MC_VER >= MC_1_21_9
    /** 1.21.9+ Services 需要非 null resolver（PlayerList 构造期可能查询） */
    private static final class MockProfileResolver implements ProfileResolver {
        @Override
        public Optional<GameProfile> fetchByName(String name) {
            return Optional.empty();
        }

        @Override
        public Optional<GameProfile> fetchById(UUID uuid) {
            return Optional.empty();
        }
    }

    private static final class MockUserNameToIdResolver implements UserNameToIdResolver {
        private final Set<NameAndId> savedIds = new HashSet<>();

        @Override
        public void add(NameAndId nameAndId) {
            this.savedIds.add(nameAndId);
        }

        @Override
        public Optional<NameAndId> get(String name) {
            return this.savedIds.stream()
                    .filter(id -> id.name().equals(name))
                    .findFirst()
                    .or(() -> Optional.of(NameAndId.createOffline(name)));
        }

        @Override
        public Optional<NameAndId> get(UUID uuid) {
            return this.savedIds.stream().filter(id -> id.id().equals(uuid)).findFirst();
        }

        @Override
        public void resolveOfflineUsers(boolean offlineMode) {
        }

        @Override
        public void save() {
        }
    }
#endif
}
