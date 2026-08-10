package io.github.limuqy.mc.hassium.network.seedgen;

import io.github.limuqy.mc.hassium.Constants;
import io.github.limuqy.mc.hassium.concurrent.HassiumTaskExecutor;
import io.github.limuqy.mc.hassium.concurrent.TaskCategory;
import io.github.limuqy.mc.hassium.config.HassiumConfig;
import io.github.limuqy.mc.hassium.config.HassiumConfigService;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import net.minecraft.world.level.ChunkPos;

/**
 * 影子端缓存清理（容量上限 + 热度淘汰）。
 * <p>
 * 迁移自旧客户端缓存清理（HBT1 时代的 {@code CacheEvictionManager}，其数据库/热度索引
 * 已随旧链删除），语义对齐：
 * <ul>
 *   <li>热度公式保留：{@code hotScore = recencyWeight * 1/(1+ageTicks)
 *       + frequencyWeight * 1/(1+accessCount)}；时间基从游戏 tick 改为 epoch 毫秒
 *       （影子端存档跨会话持久，游戏时间每会话重置不可比），ageTicks = 毫秒差 / 50。</li>
 *   <li>容量 = 全部 region 文件 offset 表实际分配扇区之和。文件大小不可用：Anvil
 *       删除后扇区仅标记释放、文件不缩小，按文件大小会永远超限触发。</li>
 *   <li>触发：客户端主线程帧尾 tick（{@code MixinClientTick}），按
 *       {@code cleanupIntervalTicks} 节流；超限时后台池执行（扫描 → 排序 → 逐柱删除，
 *       每轮最多 {@code minCleanupBatchSize} 柱）。</li>
 *   <li>删除 = {@code chunkMap.write(pos, null)}（1.21.2+ 传 {@code () -> null}）→
 *       IOWorker → {@code RegionFile.clear}：offset 置 0 + 释放扇区；内存注入表 /
 *       hash 桥同步移除。</li>
 *   <li>安全 gate：仅删除<b>不在 {@code injectedChunks}（本会话使用中）</b>的磁盘区块——
 *       会话内数据保留，清理目标是历史会话残留（R1/R2 之前落盘的冷数据）。</li>
 * </ul>
 * 热度索引持久化：{@code heat.idx}（{@code hassium_cache/<serverId>/heat.idx}，per-server），
 * 影子端装配时加载、断连 saveAll 时落盘；跨会话累计（多轮 R1/R2 的访问历史都参与评分）。
 * <p>
 * 线程模型：{@link #recordAccess} 任意线程（注入/读盘链）；{@link #tick} 客户端主线程；
 * 清理执行在后台池（与 consumeLoop 同池，不阻塞主线程/Netty）。
 */
public final class ShadowCacheEviction {

    /** heat.idx 魔数（"HSH1"）与版本。 */
    private static final int HEAT_MAGIC = 0x48534831;
    private static final int HEAT_VERSION = 1;
    /** 加载时条目数上限（防损坏文件撑爆内存；4GB 上限对应 ~26 万柱）。 */
    private static final int HEAT_MAX_ENTRIES = 10_000_000;

    private static final int SECTOR_SIZE = 4096;
    /** 原版 RegionFile 头 = offset 表 + timestamp 表（2 sectors，非旧 HassiumRegionFile 的 3-sector）。 */
    private static final long HEADER_BYTES = (long) SECTOR_SIZE * 2;

    private static final ConcurrentHashMap<Long, HotEntry> HEAT = new ConcurrentHashMap<>();
    private static final AtomicBoolean cleanupRunning = new AtomicBoolean(false);
    private static int tickCounter = 0;

    private record HotEntry(int accessCount, long lastAccessMillis) {}

    /** 清理候选：磁盘存在的区块 + 占用量。 */
    private record Candidate(ChunkPos pos, long sizeBytes, HotEntry hot) {}

    private ShadowCacheEviction() {}

    // === 热度记录（任意线程） ===

    /** 记录一次区块访问（注入 / 读盘命中时调用）。 */
    public static void recordAccess(ChunkPos pos) {
        long key = ChunkPos.asLong(pos.x, pos.z);
        long now = System.currentTimeMillis();
        HEAT.compute(key, (k, h) -> new HotEntry(h == null ? 1 : h.accessCount + 1, now));
    }

    /** 删除区块时同步移除热度条目（清理执行时调用）。 */
    public static void remove(ChunkPos pos) {
        HEAT.remove(ChunkPos.asLong(pos.x, pos.z));
    }

    // === 热度索引持久化（影子端生命周期挂钩） ===

    /** 装配时加载（影子端 initServer 后调用）；文件缺失/损坏 → 空索引。 */
    public static void load(Path worldRoot) {
        HEAT.clear();
        Path file = worldRoot.resolve("heat.idx");
        if (!Files.exists(file)) {
            return;
        }
        try (DataInputStream in = new DataInputStream(
                new BufferedInputStream(Files.newInputStream(file)))) {
            if (in.readInt() != HEAT_MAGIC || in.readInt() != HEAT_VERSION) {
                Constants.LOG.warn("Hassium: [SHADOW_CLEANUP] heat.idx version mismatch, reset");
                return;
            }
            int count = in.readInt();
            if (count < 0 || count > HEAT_MAX_ENTRIES) {
                Constants.LOG.warn("Hassium: [SHADOW_CLEANUP] heat.idx corrupt count={}, reset", count);
                return;
            }
            for (int i = 0; i < count; i++) {
                long key = in.readLong();
                int accessCount = in.readInt();
                long lastAccessMillis = in.readLong();
                HEAT.put(key, new HotEntry(accessCount, lastAccessMillis));
            }
            Constants.LOG.info("Hassium: [SHADOW_CLEANUP] heat.idx loaded {} entries", HEAT.size());
        } catch (EOFException e) {
            Constants.LOG.warn("Hassium: [SHADOW_CLEANUP] heat.idx truncated, reset");
            HEAT.clear();
        } catch (IOException e) {
            Constants.LOG.warn("Hassium: [SHADOW_CLEANUP] heat.idx load failed, reset", e);
            HEAT.clear();
        }
    }

    /** 断连 saveAll 时落盘（临时文件 + 原子替换，防进程中断留半截文件）。 */
    public static void save(Path worldRoot) {
        if (HEAT.isEmpty()) {
            return;
        }
        try {
            Path file = worldRoot.resolve("heat.idx");
            Path tmp = worldRoot.resolve("heat.idx.tmp");
            try (DataOutputStream out = new DataOutputStream(
                    new BufferedOutputStream(Files.newOutputStream(tmp)))) {
                out.writeInt(HEAT_MAGIC);
                out.writeInt(HEAT_VERSION);
                out.writeInt(HEAT.size());
                for (var e : HEAT.entrySet()) {
                    HotEntry h = e.getValue();
                    out.writeLong(e.getKey());
                    out.writeInt(h.accessCount);
                    out.writeLong(h.lastAccessMillis);
                }
            }
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            Constants.LOG.warn("Hassium: [SHADOW_CLEANUP] heat.idx save failed", e);
        }
    }

    /** 断连关停后清内存（磁盘索引保留，重连加载）。 */
    public static void reset() {
        HEAT.clear();
    }

    /** 诊断：热度索引条目数（测试/排障用）。 */
    public static int entryCount() {
        return HEAT.size();
    }

    /** 诊断：区块访问次数（无记录返回 0；测试/排障用）。 */
    public static int accessCountOf(ChunkPos pos) {
        HotEntry h = HEAT.get(ChunkPos.asLong(pos.x, pos.z));
        return h == null ? 0 : h.accessCount();
    }

    // === 驱动（客户端主线程帧尾） ===

    /**
     * 帧尾 tick：按 cleanupIntervalTicks 节流，超限时提交后台清理任务。
     * 主线程只做计数与提交（扫描/删除在后台池，不卡帧）。
     */
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
            return; // 上一轮还在跑
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
            cleanupRunning.set(false); // 断连竞态：池已停
        }
    }

    // === 清理执行（后台池） ===

    private static void runCleanup() {
        ShadowSeedServer server = ShadowServerRegistry.getInstance().get();
        if (server == null) {
            return; // 影子端未创建/已关停
        }
        HassiumConfigService cfg = HassiumConfigService.getInstance();
        HassiumConfig.ChunkCoreConfig cc = cfg.getConfig().chunk();
        long maxBytes = cc.maxCacheSizeBytes();
        if (maxBytes <= 0) {
            return;
        }
        long targetBytes = cc.targetCacheSizeBytes();
        List<Candidate> candidates = new ArrayList<>();
        long currentBytes = scanRegions(server, candidates);
        if (!shouldCleanup(currentBytes, maxBytes, targetBytes)) {
            return;
        }
        long sizeToFree = Math.max(0, currentBytes - targetBytes);
        long now = System.currentTimeMillis();
        double recencyWeight = cc.recencyWeight();
        double frequencyWeight = cc.frequencyWeight();
        double threshold = cc.hotScoreThreshold();
        int budget = Math.max(cc.minCleanupBatchSize(), 100);
        candidates.sort(Comparator
                .comparingDouble((Candidate c) -> hotScoreOf(c, now, recencyWeight, frequencyWeight))
                .thenComparingLong(c -> ChunkPos.asLong(c.pos().x, c.pos().z)));

        Constants.LOG.info("Hassium: [SHADOW_CLEANUP] Triggered: current={}MB, target={}MB, needToFree={}MB, candidates={}",
                currentBytes >> 20, targetBytes >> 20, sizeToFree >> 20, candidates.size());

        int removed = 0;
        long freed = 0;
        for (int i = 0; i < candidates.size() && i < budget && freed < sizeToFree; i++) {
            Candidate c = candidates.get(i);
            double score = hotScoreOf(c, now, recencyWeight, frequencyWeight);
            if (score > threshold) {
                continue; // 热度过高：不清理（更冷的在后面，继续看）
            }
            // review-fix: T3-50：扫描与删除间的 TOCTOU——复核注入表；扫描后被
            // consumeLoop 注入的区块跳过（deleteChunk 会无条件摘注入 + 清磁盘，
            // 导致该柱回传跳过、磁盘缓存丢失）
            if (server.injectedChunk(c.pos().x, c.pos().z) != null) {
                continue;
            }
            server.deleteChunk(c.pos());
            removed++;
            freed += c.sizeBytes();
            Constants.LOG.debug("Hassium: [SHADOW_CLEANUP] Removed chunk [{}, {}] (hotScore={}, size={}KB)",
                    c.pos().x, c.pos().z, String.format("%.3f", score), c.sizeBytes() >> 10);
        }
        Constants.LOG.info("Hassium: [SHADOW_CLEANUP] Complete: removed {} chunks, freed {}MB",
                removed, freed >> 20);
    }

    /**
     * 扫描影子端全部 region 文件：返回有效容量（offset 表分配扇区 + 文件头），
     * 并把「磁盘存在且不在内存注入表」的区块收进候选。
     * 与 {@code ShadowSeedServer.buildBloomFilter} 同款轻量只读（4096B 头，无锁）。
     */
    private static long scanRegions(ShadowSeedServer server, List<Candidate> out) {
        java.io.File regionDir = server.regionDir().toFile();
        java.io.File[] files = regionDir.listFiles((dir, name) -> name.endsWith(".mca"));
        if (files == null) {
            return 0;
        }
        long total = 0;
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
                byte[] header = new byte[SECTOR_SIZE];
                raf.readFully(header);
                for (int i = 0; i < 1024; i++) {
                    int offset = (header[i] & 0xFF) << 16 | (header[i + 1024] & 0xFF) << 8 | (header[i + 2048] & 0xFF);
                    if (offset == 0) {
                        continue;
                    }
                    int sectorCount = header[i + 3072] & 0xFF;
                    long sizeBytes = (long) sectorCount * SECTOR_SIZE;
                    total += sizeBytes;
                    if (out != null) {
                        int chunkX = regionX * 32 + (i % 32);
                        int chunkZ = regionZ * 32 + (i / 32);
                        if (server.injectedChunk(chunkX, chunkZ) == null) {
                            out.add(new Candidate(new ChunkPos(chunkX, chunkZ), sizeBytes,
                                    HEAT.get(ChunkPos.asLong(chunkX, chunkZ))));
                        }
                    }
                }
                total += HEADER_BYTES;
            } catch (Throwable t) {
                Constants.LOG.debug("Hassium: [SHADOW_CLEANUP] region scan failed for {}", f.getName(), t);
            }
        }
        return total;
    }

    private static double hotScoreOf(Candidate c, long nowMillis,
                                     double recencyWeight, double frequencyWeight) {
        HotEntry h = c.hot();
        return hotScore(h == null ? 0 : h.accessCount(),
                h == null ? 0L : h.lastAccessMillis(), nowMillis, recencyWeight, frequencyWeight);
    }

    // === 纯逻辑（单测直接覆盖） ===

    /**
     * 热度评分（迁移旧 CacheEvictionManager 公式）：
     * {@code recencyWeight * 1/(1+ageTicks) + frequencyWeight * 1/(1+accessCount)}，
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
