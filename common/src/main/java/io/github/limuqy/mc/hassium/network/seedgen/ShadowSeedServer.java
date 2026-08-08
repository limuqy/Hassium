package io.github.limuqy.mc.hassium.network.seedgen;

#if MC_VER < MC_1_21_11
import com.mojang.authlib.GameProfile;
#else
import com.mojang.authlib.GameProfile;
import com.mojang.authlib.yggdrasil.ServicesKeySet;
import net.minecraft.server.players.NameAndId;
import net.minecraft.server.permissions.LevelBasedPermissionSet;
import net.minecraft.server.permissions.PermissionSet;
import net.minecraft.server.players.ProfileResolver;
import net.minecraft.server.players.UserNameToIdResolver;
import net.minecraft.util.debugchart.LocalSampleLogger;
import net.minecraft.util.debugchart.SampleLogger;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.Optional;
#endif

import com.mojang.authlib.yggdrasil.ServicesKeySet;
import com.mojang.logging.LogUtils;
import io.github.limuqy.mc.hassium.mixin.ThreadedLevelLightEngineAccessor;
import java.net.Proxy;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.locks.LockSupport;
import java.util.function.BooleanSupplier;
import net.minecraft.CrashReport;
import net.minecraft.SystemReport;
import net.minecraft.core.SectionPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.game.ClientboundLevelChunkPacketData;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.Services;
import net.minecraft.server.WorldStem;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ThreadedLevelLightEngine;
#if MC_VER < MC_1_21_11
import com.mojang.datafixers.util.Either;
import net.minecraft.server.level.ChunkHolder;
#else
import net.minecraft.server.level.ChunkResult;
#endif
#if MC_VER < MC_1_21_11
import net.minecraft.server.level.progress.LoggerChunkProgressListener;
#else
import net.minecraft.server.level.progress.LoggingLevelLoadListener;
import net.minecraft.ReportType;
#endif
import net.minecraft.server.packs.repository.PackRepository;
import net.minecraft.server.players.PlayerList;
#if MC_VER >= MC_1_21_11
import net.minecraft.server.notifications.EmptyNotificationService;
#endif
import net.minecraft.util.datafix.DataFixers;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.DataLayer;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.lighting.LevelLightEngine;
import net.minecraft.world.level.lighting.LightEngine;
import net.minecraft.world.level.storage.LevelStorageSource;
#if MC_VER < MC_1_21_11
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

    private final WorldStem stem;
    private final long worldSeed;
    private final java.nio.file.Path tmpRoot;

    private ShadowSeedServer(Thread thread,
                             LevelStorageSource.LevelStorageAccess access,
                             PackRepository repo,
                             WorldStem stem,
                             long seed,
                             java.nio.file.Path tmpRoot) {
        super(thread, access, repo, stem, Proxy.NO_PROXY, DataFixers.getDataFixer(), NO_SERVICES,
#if MC_VER < MC_1_21_11
                LoggerChunkProgressListener::new);
#else
                LoggingLevelLoadListener.forDedicatedServer());
#endif
        this.stem = stem;
        this.worldSeed = seed;
        this.tmpRoot = tmpRoot;
    }

    static ShadowSeedServer create(Thread thread,
                                   LevelStorageSource.LevelStorageAccess access,
                                   PackRepository repo,
                                   WorldStem stem,
                                   long seed,
                                   java.nio.file.Path tmpRoot) {
        return new ShadowSeedServer(thread, access, repo, stem, seed, tmpRoot);
    }

    @Override
    public boolean initServer() {
        this.setPlayerList(new PlayerList(this, this.registries(), this.playerDataStorage,
#if MC_VER < MC_1_21_11
                1
#else
                new EmptyNotificationService()
#endif
        ) {});
        this.loadLevel();
        LOGGER.info("Hassium: Shadow seed server started (seed={})", worldSeed);
        return true;
    }

    @Override
    protected void loadLevel() {
        this.worldData.setModdedInfo(this.getServerModName(), this.getModdedStatus().shouldReportAsModified());
#if MC_VER < MC_1_21_11
        this.createLevels(new LoggerChunkProgressListener(11));
#else
        this.createLevels();
#endif
        this.forceDifficulty();
        // 不调用 prepareLevels()：不等待 441 ticking 区块，按需生成
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
        long deadline = System.nanoTime() + GENERATION_TIMEOUT_NANOS;
#if MC_VER < MC_1_21_11
        CompletableFuture<Either<ChunkAccess, ChunkHolder.ChunkLoadingFailure>> future =
                cache.getChunkFuture(pos.x, pos.z, ChunkStatus.FULL, true);
#else
        CompletableFuture<ChunkResult<ChunkAccess>> future =
                cache.getChunkFuture(pos.x, pos.z, ChunkStatus.FULL, true);
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
#if MC_VER < MC_1_21_11
        ChunkAccess chunk = future.join().left().orElse(null);
#else
        ChunkAccess chunk = future.join().orElse(null);
#endif
        return chunk instanceof LevelChunk levelChunk ? levelChunk : null;
    }

    /** shutdown 用：资源引用 */
    WorldStem stem() {
        return stem;
    }

    /**
     * 注入一个服务端区块包（任意线程可调）：空壳 LevelChunk（不 worldgen）+
     * packet 数据填充 + 清光触发引擎传播重算。
     * <p>
     * 影子端是冻结后端：区块数据只来源于服务端 packet（{@code replaceWithPacketData}
     * 整柱替换），本 server 不生成世界。种子仅用于 ServerLevel 装配，不影响注入数据。
     * <p>
     * 清光 = 原版剥光同款机制：queueSectionData(null) 清数据层 → 引擎传播重算 →
     * propagateLightSources 推发光源。全部经 ThreadedLevelLightEngine 异步任务
     * （runMainLoop 已驱动 tryScheduleUpdate）。注入失败返回 false（调用方走单柱兜底）。
     */
    public boolean injectChunk(ChunkPos pos, ClientboundLevelChunkWithLightPacket packet) {
        try {
            ServerLevel level = this.overworld();
            LevelChunk chunk = new LevelChunk(level, pos); // 空壳，不 worldgen
            ClientboundLevelChunkPacketData data = packet.getChunkData();
            chunk.replaceWithPacketData(data.getReadBuffer(), data.getHeightmaps(),
                    data.getBlockEntitiesTagsConsumer(pos.x, pos.z));
            ThreadedLevelLightEngine lightEngine = level.getChunkSource().getLightEngine();
            int minSection = io.github.limuqy.mc.hassium.compat.LevelHeightCompat.getMinSection(chunk);
            int maxSection = io.github.limuqy.mc.hassium.compat.LevelHeightCompat.getMaxSectionExclusive(chunk);
            for (int y = minSection; y < maxSection; y++) {
                SectionPos sp = SectionPos.of(pos, y);
                lightEngine.queueSectionData(LightLayer.SKY, sp, null);
                lightEngine.queueSectionData(LightLayer.BLOCK, sp, null);
                lightEngine.updateSectionStatus(sp, false);
            }
            lightEngine.propagateLightSources(pos);
            return true;
        } catch (Throwable t) {
            LOGGER.warn("Hassium: Shadow light inject failed for {}", pos, t);
            return false;
        }
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

    /**
     * 提取一个区块的光照结果（任意线程可调）：逐 section 取数据层并 copy 独立副本。
     * null 保留 null（空 section 无数据层）。
     *
     * @param bottomSection 最低 section Y（inclusive）
     * @param topSection    最高 section Y（exclusive）
     */
    public ShadowLightPatch extractLight(ChunkPos pos, int bottomSection, int topSection) {
        ThreadedLevelLightEngine engine =
                (ThreadedLevelLightEngine) this.overworld().getChunkSource().getLightEngine();
        int count = topSection - bottomSection;
        DataLayer[] sky = new DataLayer[count];
        DataLayer[] block = new DataLayer[count];
        for (int i = 0; i < count; i++) {
            SectionPos sp = SectionPos.of(pos, bottomSection + i);
            DataLayer s = engine.getLayerListener(LightLayer.SKY).getDataLayerData(sp);
            DataLayer b = engine.getLayerListener(LightLayer.BLOCK).getDataLayerData(sp);
            sky[i] = s == null ? null : s.copy();
            block[i] = b == null ? null : b.copy();
        }
        return new ShadowLightPatch(pos, sky, block, bottomSection);
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

    /** shutdown 用：临时目录根（LevelStorageAccess 两版本无统一目录 getter）。 */
    java.nio.file.Path tmpRoot() {
        return tmpRoot;
    }

    long worldSeed() {
        return worldSeed;
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
#if MC_VER < MC_1_21_11
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

#if MC_VER < MC_1_21_11
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
#if MC_VER < MC_1_21_11
        return new Services(null, ServicesKeySet.EMPTY, null, null);
#else
        return new Services(null, ServicesKeySet.EMPTY, null,
                new MockUserNameToIdResolver(), new MockProfileResolver());
#endif
    }

#if MC_VER >= MC_1_21_11
    private final SampleLogger sampleLogger = new LocalSampleLogger(4);

    /** 1.21.11 Services 需要非 null resolver（PlayerList 构造期可能查询） */
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
