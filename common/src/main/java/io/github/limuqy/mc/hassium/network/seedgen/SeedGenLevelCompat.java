package io.github.limuqy.mc.hassium.network.seedgen;

import com.mojang.serialization.Lifecycle;
import io.github.limuqy.mc.hassium.Constants;
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
 *   <li>preset 用 NORMAL（原版主世界，与多人服务器一致；GameTestServer 用 FLAT 测结构）</li>
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
        // 影子端世界根 = 客户端缓存目录下原版存档结构（hassium_cache/<serverId>/world）。
        // 断连保存、重连复用，不删除（不再兼容旧 HBT1 客户端缓存格式，数据不迁移）。
        Path worldRoot = resolveShadowWorldRoot();
        LevelStorageSource storage = LevelStorageSource.createDefault(worldRoot);
        LevelStorageSource.LevelStorageAccess access = null;
        PackRepository repo = null;
        WorldStem stem = null;
        try {
            access = storage.validateAndCreateAccess("world");
#if MC_VER < MC_1_20_2
            repo = new PackRepository(new ServerPacksSource());
#else
            repo = new PackRepository(new ServerPacksSource(
                    new DirectoryValidator(ignored -> false)));
#endif
            repo.reload();

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
                                        WorldDimensions.Complete complete = dataLoadContext.datapackWorldgen()
#if MC_VER < MC_1_21_2
                                                .registryOrThrow(Registries.WORLD_PRESET)
                                                .getHolderOrThrow(WorldPresets.NORMAL)
#else
                                                .lookupOrThrow(Registries.WORLD_PRESET)
                                                .getOrThrow(WorldPresets.NORMAL)
#endif
                                                .value()
                                                .createWorldDimensions()
                                                .bake(stemRegistry);
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

            ShadowSeedServer server = ShadowSeedServer.create(
                    Thread.currentThread(), access, repo, stem, seed, worldRoot);
            server.initServer();
            return server;
        } catch (Exception e) {
            // 装配失败：回收已创建的资源后重抛（持久目录保留，不删除）
            Constants.LOG.error("Hassium: Failed to create shadow seed server", e);
            closeQuietly(stem, access);
            if (e instanceof IOException ioe) {
                throw ioe;
            }
            throw new IOException("Shadow seed server creation failed", e);
        }
    }

    /**
     * 影子端世界根：{@code <gameDir>/hassium_cache/<serverId>}（serverId 未就绪
     * 时退回临时目录——进服早期竞态兜底，正常路径 storage 初始化已记录目录）。
     * 存档名固定为 "world"，最终目录 = {@code hassium_cache/<serverId>/world}。
     */
    static Path resolveShadowWorldRoot() {
        io.github.limuqy.mc.hassium.network.ClientChunkPipeline pipeline =
                io.github.limuqy.mc.hassium.network.ClientChunkPipeline.getInstance();
        java.nio.file.Path gameDir = pipeline.getGameDir();
        String serverId = pipeline.getServerId();
        if (gameDir == null || serverId == null) {
            try {
                return Files.createTempDirectory("hassium-shadow");
            } catch (IOException e) {
                throw new RuntimeException("Cannot resolve shadow world root", e);
            }
        }
        return gameDir.resolve("hassium_cache").resolve(serverId);
    }

    /** 关闭影子服务端：先全量保存（type 126 + hash 落盘），再停线程；持久目录保留。 */
    public static void shutdown(ShadowSeedServer server) {
        if (server == null) {
            return;
        }
        try {
            server.saveAll();
        } catch (Exception e) {
            Constants.LOG.warn("Hassium: Shadow seed server save failed", e);
        }
        try {
            server.halt(false);
        } catch (Exception e) {
            Constants.LOG.warn("Hassium: Shadow seed server halt failed", e);
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
        LevelStorageSource.LevelStorageAccess access = server.storageAccess();
        if (access != null) {
            try {
                access.close();
            } catch (Exception e) {
                Constants.LOG.warn("Hassium: Shadow storage close failed", e);
            }
        }
        // 持久世界目录保留（重连复用）；复位影子上下文与 hash 桥。
        io.github.limuqy.mc.hassium.server.RuntimeServerContext.setShadowServer(false);
        io.github.limuqy.mc.hassium.storage.ShadowStorageHashes.clear();
        // 热度索引内存态清空（磁盘 heat.idx 已随 saveAll 落盘，重连装配时重新加载）
        ShadowCacheEviction.reset();
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
