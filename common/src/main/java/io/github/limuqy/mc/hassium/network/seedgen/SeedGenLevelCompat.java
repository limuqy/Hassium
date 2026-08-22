package io.github.limuqy.mc.hassium.network.seedgen;

import com.mojang.serialization.Lifecycle;
import io.github.limuqy.mc.hassium.Constants;
import io.github.limuqy.mc.hassium.utils.DebugLogger;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import net.minecraft.commands.Commands;
import net.minecraft.core.MappedRegistry;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.RegistryOps;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.Services;
import net.minecraft.server.WorldLoader;
import net.minecraft.server.WorldStem;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.packs.repository.PackRepository;
#if MC_VER < MC_1_20_2
import net.minecraft.server.packs.repository.ServerPacksSource;
#else
import net.minecraft.server.packs.repository.ServerPacksSource;
import net.minecraft.world.level.validation.DirectoryValidator;
#endif
#if MC_VER >= MC_1_21_11
import net.minecraft.server.permissions.LevelBasedPermissionSet;
#endif
import net.minecraft.world.Difficulty;
import net.minecraft.world.flag.FeatureFlags;
import net.minecraft.world.level.DataPackConfig;
#if MC_VER < MC_1_21_11
import net.minecraft.world.level.GameRules;
#else
import net.minecraft.world.level.gamerules.GameRules;
#endif
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.LevelSettings;
import net.minecraft.world.level.WorldDataConfiguration;
import net.minecraft.world.level.dimension.LevelStem;
import net.minecraft.world.level.levelgen.WorldDimensions;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.presets.WorldPresets;
import net.minecraft.world.level.storage.LevelStorageSource;
import net.minecraft.world.level.storage.PrimaryLevelData;
#if MC_VER < MC_1_21_11
import net.minecraft.Util;
#else
import net.minecraft.util.Util;
#endif

/**
 * 影子服务端装配：临时目录 + 空存档 + 数据包加载 + 世界 stem 构建（两版本分段）。
 * <p>
 * 模板 = 原版 GameTestServer.create，差异：
 * <ul>
 *   <li>seed 用服务端下发的 worldSeed（WorldOptions(seed, generateStructures=true, bonusChest=false)）</li>
 *   <li>preset 用 NORMAL（原版主世界，与多人服务器一致）；服务端握手 LevelStem
 *       存在时优先消费（自定义 worldgen 同源装配，见 {@link #buildWorldDimensions}）</li>
 *   <li>PrimaryLevelData 预置 initialized=true，跳过 setInitialSpawn 的 spawn 区块生成</li>
 * </ul>
 */
public final class SeedGenLevelCompat {

    private SeedGenLevelCompat() {}

    /**
     * 创建并启动影子服务端（阻塞数秒；任意线程可调）。失败抛异常。
     * <p>
     * 装配在专用线程执行（= ServerChunkCache.mainThread），装配完成后该线程进入
     * {@link ShadowSeedServer#runMainLoop()} 驱动任务队列；调用方线程不持有主线程身份，
     * 因此 {@code getChunkFuture} 走非主线程路径，永不阻塞在 managedBlock。
     */
    public static ShadowSeedServer createShadowServer(long seed) throws IOException {
        AtomicReference<ShadowSeedServer> ref = new AtomicReference<>();
        AtomicReference<Throwable> error = new AtomicReference<>();
        CountDownLatch ready = new CountDownLatch(1);
        Thread main = new Thread(() -> {
            try {
                ShadowSeedServer server = assembleShadowServer(seed);
                ref.set(server);
                ready.countDown();
                server.runMainLoop();
            } catch (Throwable e) {
                error.set(e);
                ready.countDown();
            }
        }, "hassium-seedgen-main");
        main.setDaemon(true);
        main.start();
        try {
            if (!ready.await(120, TimeUnit.SECONDS)) {
                throw new IOException("Shadow seed server creation timed out");
            }
        } catch (InterruptedException e) {
            throw new IOException("Interrupted while creating shadow seed server", e);
        }
        Throwable t = error.get();
        if (t != null) {
            throw t instanceof IOException ioe ? ioe : new IOException("Shadow seed server creation failed", t);
        }
        ShadowSeedServer server = ref.get();
        server.attachMainThread(main);
        return server;
    }

    /** 纯装配（专用线程内）：持久世界目录 + 存档 + 数据包 + 世界 stem + initServer。 */
    private static ShadowSeedServer assembleShadowServer(long seed) throws IOException {
        long t0Ns = System.nanoTime(); // T0b 诊断：装配各阶段耗时
        // 影子上下文须在 WorldLoader.load 之前置位：ReloadableServerResources.listeners()
        // Mixin 据此跳过 recipe/advancement/function/loot（保留 TagManager）。
        io.github.limuqy.mc.hassium.server.RuntimeServerContext.setShadowServer(true);
        // 影子端世界根 = 客户端缓存目录下原版存档结构（hassium_cache/<serverId>/world）。
        // 断连保存、重连复用，不删除（不再兼容旧 HBT1 客户端缓存格式，数据不迁移）。
        Path worldRoot = resolveShadowWorldRoot();
        long tResolveNs = System.nanoTime();
        LevelStorageSource storage = LevelStorageSource.createDefault(worldRoot);
        LevelStorageSource.LevelStorageAccess access = null;
        PackRepository repo = null;
        WorldStem stem = null;
        try {
            access = storage.validateAndCreateAccess("world");
            long tAccessNs = System.nanoTime();
#if MC_VER < MC_1_20_2
            repo = new PackRepository(new ServerPacksSource());
#else
            repo = new PackRepository(new ServerPacksSource(
                    new DirectoryValidator(ignored -> false)));
#endif
            repo.reload();
            long tRepoNs = System.nanoTime();

            List<String> packIds = new ArrayList<>(repo.getAvailableIds());
            WorldDataConfiguration dataConfig = new WorldDataConfiguration(
                    new DataPackConfig(packIds, List.of()), FeatureFlags.REGISTRY.allFlags());
            LevelSettings settings = new LevelSettings("HassiumSeedGen", GameType.CREATIVE, false,
                    Difficulty.NORMAL, true,
#if MC_VER < MC_1_21_2
                    new GameRules(),
#else
                    new GameRules(FeatureFlags.REGISTRY.allFlags()),
#endif
                    dataConfig);
            WorldLoader.PackConfig packConfig = new WorldLoader.PackConfig(repo, dataConfig, false, true);
#if MC_VER < MC_1_21_11
            WorldLoader.InitConfig initConfig = new WorldLoader.InitConfig(
                    packConfig, Commands.CommandSelection.DEDICATED, 4);
#else
            WorldLoader.InitConfig initConfig = new WorldLoader.InitConfig(
                    packConfig, Commands.CommandSelection.DEDICATED, LevelBasedPermissionSet.OWNER);
#endif

            WorldOptions worldOptions = new WorldOptions(seed, true, false);
            stem = Util.<WorldStem>blockUntilDone(
                            executor -> WorldLoader.load(
                                    initConfig,
                                    dataLoadContext -> {
                                        Registry<LevelStem> stemRegistry =
                                                new MappedRegistry<>(Registries.LEVEL_STEM, Lifecycle.stable()).freeze();
                                        WorldDimensions.Complete complete = buildWorldDimensions(
                                                dataLoadContext, stemRegistry);
                                        PrimaryLevelData worldData = new PrimaryLevelData(
                                                settings, worldOptions, complete.specialWorldProperty(), complete.lifecycle());
                                        worldData.setInitialized(true);
                                        return new WorldLoader.DataLoadOutput<>(
                                                worldData, complete.dimensionsRegistryAccess());
                                    },
                                    WorldStem::new,
                                    Util.backgroundExecutor(),
                                    executor)
                    )
                    .get(120, TimeUnit.SECONDS);
        long tStemNs = System.nanoTime();
        ShadowSeedServer server = ShadowSeedServer.create(
                Thread.currentThread(), access, repo, stem, seed, worldRoot);
        server.initServer();
        DebugLogger.info(DebugLogger.LogType.ASYNC,
                "[SHADOW-DIAG] assembleShadowServer: worldRoot={}ms storage+access={}ms packRepo={}ms worldStem(WorldLoader)={}ms initServer={}ms total={}ms (seed={})",
                (tResolveNs - t0Ns) / 1_000_000L,
                (tAccessNs - tResolveNs) / 1_000_000L,
                (tRepoNs - tAccessNs) / 1_000_000L,
                (tStemNs - tRepoNs) / 1_000_000L,
                (System.nanoTime() - tStemNs) / 1_000_000L,
                (System.nanoTime() - t0Ns) / 1_000_000L,
                seed);
        return server;

        } catch (Exception e) {
            // 装配失败：复位影子标志（代际由本轮 setShadowServer(true) 递增）后回收资源
            try {
                int gen = io.github.limuqy.mc.hassium.server.RuntimeServerContext.getShadowGeneration();
                io.github.limuqy.mc.hassium.server.RuntimeServerContext.clearShadowServerIfCurrentGeneration(gen);
            } catch (Throwable ignored) {
            }
            Constants.LOG.error("Hassium: Failed to create shadow seed server", e);
            closeQuietly(stem, access);
            if (e instanceof IOException ioe) {
                throw ioe;
            }
            throw new IOException("Shadow seed server creation failed", e);
        }
    }

    /**
     * 装配世界维度：优先消费服务端握手下发的 LevelStem NBT（自定义 worldgen 服务器
     * 本地生成与服务器一致——dimension type / generator settings 同源）；未下发或
     * 解码失败（自定义 datapack 客户端缺失）回落原版 NORMAL preset（旧行为）。
     * 残余地形不一致由 SeedGen 生成后 chunkHash 校验兜底（不匹配 → 回退全量）。
     */
    private static WorldDimensions.Complete buildWorldDimensions(
            WorldLoader.DataLoadContext dataLoadContext,
            Registry<LevelStem> stemRegistry) {
        byte[] stemNbt = io.github.limuqy.mc.hassium.network.ClientChunkPipeline
                .getInstance().getServerLevelStemNbt();
        if (stemNbt != null && stemNbt.length > 0) {
            try {
                FriendlyByteBuf buf = new FriendlyByteBuf(
                        io.netty.buffer.Unpooled.wrappedBuffer(stemNbt));
                CompoundTag tag;
                try {
                    tag = buf.readNbt();
                } finally {
                    buf.release();
                }
                if (tag != null) {
                    RegistryOps<net.minecraft.nbt.Tag> ops =
                            RegistryOps.create(NbtOps.INSTANCE, dataLoadContext.datapackWorldgen());
                    java.util.Optional<LevelStem> decoded = LevelStem.CODEC.parse(ops, tag).result();
                    if (decoded.isPresent()) {
                        Constants.LOG.info("Hassium: Shadow server consuming server LevelStem "
                                + "(custom worldgen overworld + vanilla nether/end)");
                        return threeDimensions(dataLoadContext, decoded.get()).bake(stemRegistry);
                    }
                }
            } catch (Throwable t) {
                Constants.LOG.warn("Hassium: LevelStem decode failed, fallback to NORMAL preset", t);
            }
        }
        WorldDimensions presetDims = normalPresetDimensions(dataLoadContext);
        LevelStem overworld = presetDims.get(LevelStem.OVERWORLD)
                .orElseThrow(() -> new IllegalStateException("NORMAL preset missing overworld stem"));
        return threeDimensions(dataLoadContext, overworld).bake(stemRegistry);
    }

    /** NORMAL preset 的 WorldDimensions（两版本 registry/lookup 形态差异封装）。 */
    private static WorldDimensions normalPresetDimensions(WorldLoader.DataLoadContext dataLoadContext) {
        return dataLoadContext.datapackWorldgen()
#if MC_VER < MC_1_21_2
                .registryOrThrow(Registries.WORLD_PRESET)
                .getHolderOrThrow(WorldPresets.NORMAL)
#else
                .lookupOrThrow(Registries.WORLD_PRESET)
                .getOrThrow(WorldPresets.NORMAL)
#endif
                .value()
                .createWorldDimensions();
    }

    /**
     * 装配三维度（overworld/nether/end）：overworld stem 由调用方给定（服务端握手
     * 下发的自定义 worldgen stem 或 NORMAL preset 主世界 stem），nether/end 取
     * NORMAL preset 原版 stem——下界/末地无天光（dimensionType.hasSkyLight=false），
     * 光照管线按 hasSkyLight 分支，无需额外适配。
     * <p>
     * createLevels 按维度 registry 建 level（overworld + registry 其余维度各一个
     * ServerLevel），generateChunk/injectChunk/bloom/落盘全部按
     * {@code ShadowSeedServer.level(dimension)} 路由到对应 level。
     */
    private static WorldDimensions threeDimensions(
            WorldLoader.DataLoadContext dataLoadContext,
            LevelStem overworldStem) {
        WorldDimensions presetDims = normalPresetDimensions(dataLoadContext);
        LevelStem netherStem = presetDims.get(LevelStem.NETHER)
                .orElseThrow(() -> new IllegalStateException("NORMAL preset missing nether stem"));
        LevelStem endStem = presetDims.get(LevelStem.END)
                .orElseThrow(() -> new IllegalStateException("NORMAL preset missing end stem"));
#if MC_VER < MC_1_20_5
        MappedRegistry<LevelStem> dims = new MappedRegistry<>(Registries.LEVEL_STEM, Lifecycle.stable());
        dims.register(LevelStem.OVERWORLD, overworldStem, Lifecycle.stable());
        dims.register(LevelStem.NETHER, netherStem, Lifecycle.stable());
        dims.register(LevelStem.END, endStem, Lifecycle.stable());
        return new WorldDimensions(dims.freeze());
#else
        java.util.Map<ResourceKey<LevelStem>, LevelStem> stems = new java.util.LinkedHashMap<>();
        stems.put(LevelStem.OVERWORLD, overworldStem);
        stems.put(LevelStem.NETHER, netherStem);
        stems.put(LevelStem.END, endStem);
        return new WorldDimensions(java.util.Map.copyOf(stems));
#endif
    }

    /**
     * 影子端世界根决议（唯一规则，P3 修复；勿在别处另立规则）。优先级：
     * <ol>
     *   <li>{@code serverId} 已就绪（gateway-only 握手完成时 {@code ClientLifecycleHelper}
     *       已同步记录；正常路径 storage 初始化亦记录）→ {@code <gameDir>/hassium_cache/<serverId>}
     *       （既有目录布局，重连复用）。若真实目录尚不存在且存在同 server 的 pending 目录
     *       （此前 serverId 未就绪期间的落盘），整体改名迁移复用（{@link #migratePendingWorld}）。</li>
     *   <li>{@code serverId} 未就绪（首连早期竞态兜底）→
     *       {@code <gameDir>/hassium_cache/pending-<sanitized(serverIp)>}，<b>不回落 TEMP</b>——
     *       TEMP 目录进程退出即丢，重连后真实目录为空导致全量 miss（P3 根因）；pending 数据
     *       在重连（serverId 就绪）时按第 1 条迁移复用。</li>
     *   <li>无任何服务器标识（理论不可达：影子创建前置条件 = 握手完成 ≥ serverId 已记录；
     *       防御）→ 抛异常，由 ShadowServerRegistry 走降级，不静默丢档。</li>
     * </ol>
     * 存档名固定为 "world"，最终目录 = {@code hassium_cache/<serverId>/world}。
     */
    static Path resolveShadowWorldRoot() {
        io.github.limuqy.mc.hassium.network.ClientChunkPipeline pipeline =
                io.github.limuqy.mc.hassium.network.ClientChunkPipeline.getInstance();
        java.nio.file.Path gameDir = pipeline.getGameDir();
        String serverId = pipeline.getServerId();
        if (gameDir == null) {
            throw new IllegalStateException(
                    "Cannot resolve shadow world root: gameDir not recorded (login incomplete)");
        }
        java.nio.file.Path cacheRoot = gameDir.resolve("hassium_cache");
        if (serverId != null) {
            migratePendingWorld(cacheRoot, serverId);
            return cacheRoot.resolve(serverId);
        }
        String serverIp = io.github.limuqy.mc.hassium.cache.client.ClientLifecycleHelper.currentServerIp();
        if (serverIp != null && !serverIp.isBlank()) {
            return cacheRoot.resolve(
                    "pending-" + io.github.limuqy.mc.hassium.utils.ServerIdUtil.sanitize(serverIp));
        }
        throw new IllegalStateException(
                "Cannot resolve shadow world root: no server identity (serverId/serverIp unavailable)");
    }

    /**
     * 重连迁移：真实目录尚未创建时，若存在同 server 的 pending 目录（此前 serverId 未就绪
     * 期间的落盘），整体改名迁移复用——首连数据不因 worldRoot 决议漂移而丢失。
     * 真实目录已存在（正常重连复用路径）或 pending 不存在/非目录 → 不动（不破坏既有布局）。
     * 迁移失败仅告警，回落全新真实目录（pending 数据保留，不删除）。
     */
    private static void migratePendingWorld(java.nio.file.Path cacheRoot, String serverId) {
        java.nio.file.Path pending = cacheRoot.resolve("pending-" + serverId);
        java.nio.file.Path real = cacheRoot.resolve(serverId);
        if (!Files.isDirectory(pending) || Files.exists(real)) {
            return;
        }
        try {
            Files.move(pending, real);
            Constants.LOG.info("Hassium: shadow cache migrated pending-{} -> {}", serverId, serverId);
        } catch (IOException e) {
            Constants.LOG.warn("Hassium: shadow cache pending migration failed (using fresh dir)", e);
        }
    }

    /**
     * 关闭影子服务端：先用原版能力写出 {@code level.dat}，再释放世界目录锁
     * （session.lock），然后全量保存区块（type 126 + hash），最后停线程；持久目录保留。
     * <p>
     * 锁在 level.dat 之后、区块 saveAll 之前释放（T5cShadowReady）：
     * {@code LevelStorageAccess} 的 session.lock 只用于防止「同一存档目录并发开两个
     * access」——区块保存链（saveAll → halt → chunkMap.close）全程只依赖路径/region
     * 文件句柄，不依赖该锁；{@code saveDataTag} 必须在 close 之前。把
     * {@code access.close()} 提到区块 saveAll 之前，R2 重连建端便不再被 R1 的 saveAll
     * 阻塞（毫秒级拿到锁即可创建）。
     * R2 创建后与 R1 saveAll 并发：R2 只读盘（loadFromDisk），R1 只写——torn read
     * 退化为比对 miss → 数据重推（正确降级）；R2 的写路径由
     * {@code ShadowServerRegistry} 的关停完成 gate 串行化（见
     * {@code isPreviousShutdownComplete} / {@code beginShutdownSave}），杜绝并发写
     * 同一 mca。
     */
    public static void shutdown(ShadowSeedServer server) {
        shutdown(server, false);
    }

    /**
     * @param skipSave true = 上次关停异常未完成（saver 前置等待 30s 超时）→ 放弃本次
     *                 保存仅回收资源（数据安全红线：禁止与挂起的 saveAll 并发写同一
     *                 mca；数据由后续会话比对 miss 重推兜底）。
     */
    public static void shutdown(ShadowSeedServer server, boolean skipSave) {
        if (server == null) {
            return;
        }
        // 捕获当前影子端代际：关停期间若新会话的影子端已并发创建（initServer 置位
        // true 并递增代际），末尾不得复位全局标志/清空桥表——否则误清新会话的存储
        // 格式门控（原版 zlib 混入 126 文件）与 hash/热度内存态。
        int shadowGeneration =
                io.github.limuqy.mc.hassium.server.RuntimeServerContext.getShadowGeneration();
        // 本端进入关停保存：写 gate 对本端放行（上次关停已在 saver 前置等待完成，
        // 本端写盘与任何其他端无并发）。
        server.beginShutdownSave();
        // 1) 原版写出 level.dat（须在关闭 storageSource 之前；种子/维度设置来自 WorldOptions）
        try {
            server.saveWorldData();
        } catch (Exception e) {
            Constants.LOG.warn("Hassium: Shadow level.dat save failed", e);
        }
        // 2) 再释放世界目录锁（毫秒级）：R2 重连建端不再等待 R1 saveAll 落盘完成。
        LevelStorageSource.LevelStorageAccess access = server.storageAccess();
        if (access != null) {
            try {
                access.close();
            } catch (Exception e) {
                Constants.LOG.warn("Hassium: Shadow storage close failed", e);
            }
        }
        // 3) 全量保存区块（此时 R2 可并发创建影子端并读盘；本端写盘与任何其他端无并发）
        if (skipSave) {
            Constants.LOG.warn("Hassium: Shadow seed server save skipped "
                    + "(previous shutdown incomplete; data re-pushed on next session)");
        } else {
            try {
                server.saveAll();
            } catch (Exception e) {
                Constants.LOG.warn("Hassium: Shadow seed server save failed", e);
            }
        }
        // 4) 停线程。注意：不能先 stopMainLoop——saveAll 期间主循环仍在驱动光照任务，
        // isLightConverged 才可能为 true（提前停会误判未收敛 → 全量标脏 →
        // R2 hash 命中全被拦截）。mainLoop 由 shutdown 内部的 halt 停止。
        try {
            server.halt(false);
        } catch (Exception e) {
            Constants.LOG.warn("Hassium: Shadow seed server halt failed", e);
        }
        try {
            server.closeStorage();
        } catch (Exception e) {
            Constants.LOG.warn("Hassium: Shadow storage manager close failed", e);
        }
        // 停各维度的 chunk 源：只关 region 文件层（ChunkStorage.close），不关
        // ServerChunkCache——其 mainThreadProcessor.close() → BlockableEventLoop.close()
        // 会 shutdown 进程级共享的 Util.backgroundExecutor()（1.20.1 BlockableEventLoop
        // 构造即用该 ForkJoinPool），R2 影子端重建后 light mailbox 全部
        // RejectedExecutionException（官方 integrated server 切世界也只 halt 不关
        // chunkSource，共享 pool 生命周期 = 进程生命周期）。
        for (ServerLevel level : server.getAllLevels()) {
            try {
#if MC_VER < MC_1_21_2
                ((net.minecraft.server.level.ServerChunkCache) level.getChunkSource()).chunkMap.close();
#else
                level.close();
#endif
            } catch (Exception e) {
                Constants.LOG.warn("Hassium: Shadow level close failed for {}", level.dimension(), e);
            }
        }
        WorldStem stem = server.stem();
        closeQuietly(stem, null);
        // 目录锁已在步骤 1 释放（幂等；此处不再重复 close）。
        // 持久世界目录保留（重连复用）；复位影子上下文与 hash 桥——仅当代际未变
        // （关停期间无新影子端接管）时执行，防止异步关停清掉新会话的存储 gate /
        // hash 表 / 热度索引（T5cShadowReady 并发创建设计下的数据损坏根因）。
        if (io.github.limuqy.mc.hassium.server.RuntimeServerContext
                .getShadowGeneration() == shadowGeneration) {
            io.github.limuqy.mc.hassium.server.RuntimeServerContext
                    .clearShadowServerIfCurrentGeneration(shadowGeneration);
            io.github.limuqy.mc.hassium.storage.ShadowStorageHashes.clear();
            io.github.limuqy.mc.hassium.network.sectiondelta.SectionDeltaSnapshots.clear();
            // 热度索引内存态清空（磁盘 heat.idx 已随 saveAll 落盘，重连装配时重新加载）
            ShadowCacheEviction.reset();
        }
    }

    private static void closeQuietly(WorldStem stem, LevelStorageSource.LevelStorageAccess access) {
        if (stem != null) {
            try {
                stem.close();
            } catch (Exception e) {
                Constants.LOG.warn("Hassium: Shadow stem close failed", e);
            }
        }
        if (access != null) {
            try {
                access.close();
            } catch (Exception e) {
                Constants.LOG.warn("Hassium: Shadow storage close failed", e);
            }
        }
    }
}
