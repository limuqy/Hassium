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
#if MC_VER < MC_1_21_11
import net.minecraft.server.packs.repository.ServerPacksSource;
#else
import net.minecraft.server.packs.repository.ServerPacksSource;
import net.minecraft.server.permissions.LevelBasedPermissionSet;
import net.minecraft.world.level.validation.DirectoryValidator;
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

    /** 纯装配（专用线程内）：临时目录 + 空存档 + 数据包 + 世界 stem + initServer。 */
    private static ShadowSeedServer assembleShadowServer(long seed) throws IOException {
        Path tmpDir = Files.createTempDirectory("hassium-seedgen");
        LevelStorageSource storage = LevelStorageSource.createDefault(tmpDir);
        LevelStorageSource.LevelStorageAccess access = null;
        PackRepository repo = null;
        WorldStem stem = null;
        try {
            access = storage.validateAndCreateAccess("seedgen");
#if MC_VER < MC_1_21_11
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
#if MC_VER < MC_1_21_11
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
#if MC_VER < MC_1_21_11
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
                    Thread.currentThread(), access, repo, stem, seed, tmpDir);
            server.initServer();
            return server;
        } catch (Exception e) {
            // 装配失败：回收已创建的资源后重抛
            Constants.LOG.error("Hassium: Failed to create shadow seed server", e);
            closeQuietly(stem, access);
            deleteRecursively(tmpDir);
            if (e instanceof IOException ioe) {
                throw ioe;
            }
            throw new IOException("Shadow seed server creation failed", e);
        }
    }

    /** 关闭影子服务端并回收临时目录。 */
    public static void shutdown(ShadowSeedServer server) {
        if (server == null) {
            return;
        }
        try {
            server.halt(false);
        } catch (Exception e) {
            Constants.LOG.warn("Hassium: Shadow seed server halt failed", e);
        }
        // 停各维度的 chunk 源（worldgen/光照线程）
        for (ServerLevel level : server.getAllLevels()) {
            try {
                level.close();
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
        // 回收临时目录（LevelStorageAccess.close 可能已删，容错）
        deleteRecursively(server.tmpRoot());
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

    private static void deleteRecursively(Path root) {
        if (root == null) {
            return;
        }
        try {
            if (Files.exists(root)) {
                try (java.util.stream.Stream<Path> paths = Files.walk(root)) {
                    paths.sorted(java.util.Comparator.reverseOrder())
                            .forEach(p -> {
                                try {
                                    Files.deleteIfExists(p);
                                } catch (IOException e) {
                                    // 忽略单个文件删除失败，尽力回收
                                }
                            });
                }
            }
        } catch (IOException e) {
            Constants.LOG.warn("Hassium: Failed to delete shadow temp dir {}", root, e);
        }
    }
}
