package io.github.limuqy.mc.hassium.network.seedgen;

import io.github.limuqy.mc.hassium.Constants;
import io.github.limuqy.mc.hassium.concurrent.HassiumTaskExecutor;
import io.github.limuqy.mc.hassium.concurrent.TaskCategory;
import io.github.limuqy.mc.hassium.config.HassiumConfig;
import io.github.limuqy.mc.hassium.config.HassiumConfigService;
import io.github.limuqy.mc.hassium.storage.ShadowRegionHeat;
import io.github.limuqy.mc.hassium.utils.DimensionKey;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import net.minecraft.world.level.ChunkPos;

/**
 * 影子端缓存清理（容量上限 + region 文件热度淘汰）。
 * <p>
 * 热度索引在 {@link ShadowRegionHeat}（{@code heat.idx}，按 {@code r.X.Z.mca} 计）：
 * 装配时解析文件内容加载、断连 {@code saveAll} 落盘；存储管理器写盘时回写文件大小。
 * 容量扫描只列 region 目录 + 文件体积，不拆 Anvil 头。
 * <ul>
 *   <li>热度公式：{@code hotScore = recencyWeight * 1/(1+ageTicks)
 *       + frequencyWeight * 1/(1+accessCount)}；ageTicks = 毫秒差 / 50。</li>
 *   <li>容量 = 各维度 {@code *.mca} 的 {@code Files.size} 之和。整文件删除会真实缩小占用。</li>
 *   <li>触发：客户端主线程帧尾 tick（{@code MixinClientTick}），按
 *       {@code cleanupIntervalTicks} 节流；超限时后台池执行。</li>
 *   <li>删除 = {@link ShadowSeedServer#deleteRegion} → 存储管理器卸映像并删 {@code .mca}。</li>
 *   <li>安全 gate：本会话 {@code injectedChunks} 落到的 region 整文件跳过。</li>
 * </ul>
 */
public final class ShadowCacheEviction {

    private static final String[] DIMENSIONS = {
            DimensionKey.OVERWORLD,
            DimensionKey.NETHER,
            DimensionKey.END
    };
    private static final AtomicBoolean cleanupRunning = new AtomicBoolean(false);
    private static int tickCounter = 0;

    private ShadowCacheEviction() {}

    public static void recordAccess(ChunkPos pos) {
        ShadowRegionHeat.recordAccess(pos);
    }

    public static void recordAccess(String dimension, ChunkPos pos) {
        ShadowRegionHeat.recordAccess(dimension, pos);
    }

    public static void remove(ChunkPos pos) {
        ShadowRegionHeat.remove(pos);
    }

    public static void remove(String dimension, ChunkPos pos) {
        ShadowRegionHeat.remove(dimension, pos);
    }

    public static void load(Path worldRoot) {
        ShadowRegionHeat.load(worldRoot);
    }

    public static void save(Path worldRoot) {
        ShadowRegionHeat.save(worldRoot);
    }

    public static void reset() {
        ShadowRegionHeat.reset();
    }

    public static int accessCountOf(ChunkPos pos) {
        return ShadowRegionHeat.accessCountOf(pos);
    }

    public static int accessCountOf(String dimension, ChunkPos pos) {
        return ShadowRegionHeat.accessCountOf(dimension, pos);
    }

    public static int entryCount() {
        return ShadowRegionHeat.entryCount();
    }

    public static void tick() {
        HassiumConfigService cfg = HassiumConfigService.getInstance();
        if (!cfg.isClientCacheEnabled() || !cfg.isHassiumEngineEnabled()) {
            return;
        }
        int interval = cfg.getCleanupIntervalTicks();
        if (interval <= 0 || ++tickCounter % interval != 0) {
            return;
        }
        if (!cleanupRunning.compareAndSet(false, true)) {
            return;
        }
        HassiumTaskExecutor executor = HassiumTaskExecutor.getClient();
        if (executor == null || !executor.isRunning()) {
            cleanupRunning.set(false);
            return;
        }
        try {
            executor.submit(() -> {
                try {
                    runCleanup();
                } finally {
                    cleanupRunning.set(false);
                }
            }, TaskCategory.BEST_EFFORT);
        } catch (RejectedExecutionException e) {
            cleanupRunning.set(false);
        }
    }

    private static void runCleanup() {
        ShadowSeedServer server = ShadowServerRegistry.getInstance().get();
        if (server == null) {
            return;
        }
        HassiumConfigService cfg = HassiumConfigService.getInstance();
        HassiumConfig.ChunkCoreConfig cc = cfg.getConfig().chunk();
        long maxBytes = cc.maxCacheSizeBytes();
        if (maxBytes <= 0) {
            return;
        }
        long targetBytes = cc.targetCacheSizeBytes();
        List<ShadowRegionHeat.RegionFileStat> candidates = new ArrayList<>();
        long currentBytes = scanRegionFiles(server, candidates);
        if (!shouldCleanup(currentBytes, maxBytes, targetBytes)) {
            return;
        }
        long sizeToFree = Math.max(0, currentBytes - targetBytes);
        long now = System.currentTimeMillis();
        double recencyWeight = cc.recencyWeight();
        double frequencyWeight = cc.frequencyWeight();
        double threshold = cc.hotScoreThreshold();
        int budget = Math.max(cc.minCleanupBatchSize(), 1);
        candidates.sort(Comparator
                .comparingDouble((ShadowRegionHeat.RegionFileStat c) ->
                        hotScoreOf(c, now, recencyWeight, frequencyWeight))
                .thenComparingLong(c -> ChunkPos.asLong(c.regionX(), c.regionZ())));

        Constants.LOG.info("Hassium: [SHADOW_CLEANUP] Triggered: current={}MB, target={}MB, needToFree={}MB, candidates={}",
                currentBytes >> 20, targetBytes >> 20, sizeToFree >> 20, candidates.size());

        int removed = 0;
        long freed = 0;
        for (int i = 0; i < candidates.size() && i < budget && freed < sizeToFree; i++) {
            ShadowRegionHeat.RegionFileStat c = candidates.get(i);
            double score = hotScoreOf(c, now, recencyWeight, frequencyWeight);
            if (score > threshold) {
                continue;
            }
            if (server.regionHasInjected(c.dimension(), c.regionX(), c.regionZ())) {
                continue;
            }
            server.deleteRegion(c.dimension(), c.regionX(), c.regionZ());
            removed++;
            freed += c.sizeBytes();
            Constants.LOG.debug("Hassium: [SHADOW_CLEANUP] Removed region [{}, {}] dim={} (hotScore={}, size={}KB)",
                    c.regionX(), c.regionZ(), c.dimension(), String.format("%.3f", score), c.sizeBytes() >> 10);
        }
        Constants.LOG.info("Hassium: [SHADOW_CLEANUP] Complete: removed {} region files, freed {}MB",
                removed, freed >> 20);
    }

    private static long scanRegionFiles(ShadowSeedServer server,
                                        List<ShadowRegionHeat.RegionFileStat> out) {
        long total = 0L;
        for (String dimension : DIMENSIONS) {
            Set<Long> live = liveRegionKeys(server, dimension);
            total += ShadowRegionHeat.collectRegionFiles(
                    server.regionDir(dimension), dimension, live, out);
        }
        return total;
    }

    private static Set<Long> liveRegionKeys(ShadowSeedServer server, String dimension) {
        Set<Long> live = new HashSet<>();
        for (Long key : server.injectedKeys()) {
            if (!dimension.equals(DimensionKey.dimensionOf(key))) {
                continue;
            }
            int rx = Math.floorDiv(DimensionKey.chunkXOf(key), 32);
            int rz = Math.floorDiv(DimensionKey.chunkZOf(key), 32);
            live.add(ChunkPos.asLong(rx, rz));
        }
        return live;
    }

    private static double hotScoreOf(ShadowRegionHeat.RegionFileStat c, long nowMillis,
                                     double recencyWeight, double frequencyWeight) {
        ShadowRegionHeat.HotEntry h = c.hot();
        return hotScore(h == null ? 0 : h.accessCount(),
                h == null ? 0L : h.lastAccessMillis(), nowMillis, recencyWeight, frequencyWeight);
    }

    /**
     * 热度评分：{@code recencyWeight * 1/(1+ageTicks) + frequencyWeight * 1/(1+accessCount)}，
     * ageTicks = 毫秒差 / 50（1 tick = 50ms）。
     */
    public static double hotScore(int accessCount, long lastAccessMillis, long nowMillis,
                                  double recencyWeight, double frequencyWeight) {
        long ageTicks = Math.max(0, (nowMillis - lastAccessMillis)) / 50L;
        double recencyScore = 1.0 / (1.0 + ageTicks);
        double frequencyScore = 1.0 / (1.0 + accessCount);
        return recencyWeight * recencyScore + frequencyWeight * frequencyScore;
    }

    /** 是否需要清理：超过上限，或超过目标（0.9 提前触发，避免反复越界）。 */
    public static boolean shouldCleanup(long currentBytes, long maxBytes, long targetBytes) {
        if (maxBytes <= 0) {
            return false;
        }
        if (currentBytes > maxBytes) {
            return true;
        }
        return currentBytes > (long) (targetBytes * 0.9);
    }
}
