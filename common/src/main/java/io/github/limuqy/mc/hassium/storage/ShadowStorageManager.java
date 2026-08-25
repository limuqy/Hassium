package io.github.limuqy.mc.hassium.storage;

import io.github.limuqy.mc.hassium.utils.DimensionKey;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.LongPredicate;
import net.minecraft.world.level.ChunkPos;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 影子缓存存储管理器：脏位 + 按 region 串行写压缩 .mca。
 * <p>
 * 活柱工作集仍是 {@code LevelChunk}。本类不保留未压缩 NBT 镜像。
 * 光收敛 {@link #markLightReady} 立即快照 NBT 并入 RegionWorker 队列；
 * {@link #flush} / 定时刷新只刷中途变更（{@code mutation}）；
 * 退出 {@link #flushRemaining} 刷全部剩余脏柱。
 * <p>
 * <b>多维度兼容</b>：每个 manager 绑定一个维度（{@link #dimension()}），HashIndex
 * 键走 {@link DimensionKey#key(String, int, int)} 复合键；regionDir 仍由构造方给
 * 定（per-dimension 目录隔离由装配层负责）。旧无维度构造器/方法委托到
 * {@link DimensionKey#OVERWORLD}，主世界行为与现网一致。
 */
public final class ShadowStorageManager implements AutoCloseable {

    public static final long DEFAULT_FLUSH_TIMEOUT_MS = 30_000L;

    private static final Logger LOGGER = LoggerFactory.getLogger("Hassium/ShadowStorage");

    @FunctionalInterface
    public interface ColumnSerializer {
        /** 注入柱的未压缩 NBT；未注入或失败返回 null。flush / enqueue 调用。 */
        byte[] serialize(ChunkPos pos);
    }

    @FunctionalInterface
    public interface InjectedPredicate {
        boolean test(long chunkKey);
    }

    public enum ProbeStatus {
        /** contentHash 相等（HashIndex 或 9B 头）。 */
        MATCH,
        /** 槽/表存在但 hash 不等。 */
        MISMATCH,
        /** 缺文件或空槽。 */
        ABSENT
    }

    public record ProbeResult(ProbeStatus status) {
        public boolean match() {
            return status == ProbeStatus.MATCH;
        }

        public boolean present() {
            return status != ProbeStatus.ABSENT;
        }
    }

    public record FlushResult(int written, int abandoned, boolean timedOut) {}

    private final Path regionDir;
    private final ColumnSerializer serializer;
    private final InjectedPredicate injected;
    private final int zstdLevel;
    private final ConcurrentHashMap<Long, RegionCache.Image> images = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Long, RegionWorker> workers = new ConcurrentHashMap<>();
    private final AtomicInteger decompressCount = new AtomicInteger();
    private final AtomicInteger outstandingWrites = new AtomicInteger();
    private final Object drainMonitor = new Object();
    private final List<ChunkPos> writeLog = new ArrayList<>();
    private volatile boolean closed;
    /** 测试钩子：worker 写盘前睡眠，用于 flush 超时。 */
    volatile long testWriteDelayMs;
    /** 本 manager 服务的维度（HashIndex 复合键的维度段）。 */
    private final String dimension;

    public ShadowStorageManager(Path regionDir, ColumnSerializer serializer, InjectedPredicate injected) {
        this(DimensionKey.OVERWORLD, regionDir, serializer, injected, 3);
    }

    public ShadowStorageManager(Path regionDir, ColumnSerializer serializer, InjectedPredicate injected, int zstdLevel) {
        this(DimensionKey.OVERWORLD, regionDir, serializer, injected, zstdLevel);
    }

    /** 指定维度装配（nether/end 影子端使用；regionDir 由装配方给对应维度目录）。 */
    public ShadowStorageManager(
            String dimension, Path regionDir, ColumnSerializer serializer, InjectedPredicate injected, int zstdLevel) {
        this.dimension = dimension;
        this.regionDir = regionDir;
        this.serializer = serializer;
        this.injected = injected;
        this.zstdLevel = zstdLevel;
    }

    /** 本 manager 绑定的维度 id。 */
    public String dimension() {
        return dimension;
    }

    public void markContentDirty(ChunkPos pos) {
        ShadowStorageHashes.markContentDirty(dimension, pos);
    }

    public void markLightReady(ChunkPos pos) {
        ShadowStorageHashes.markLightReady(dimension, pos);
        enqueue(pos);
    }

    /**
     * 探活：HashIndex 命中 TRUE 直接 MATCH；否则读压缩映像/磁盘 9B 头
     * （表 FALSE 可能是脏表，磁盘头为准）。表空且缺文件/空槽 → ABSENT；
     * 表 FALSE 且缺文件/空槽 → MISMATCH。不解压整柱。
     */
    public ProbeResult probeHash(ChunkPos pos, long remoteHash) {
        return probeHash(dimension, pos, remoteHash);
    }

    /**
     * 指定维度探活，语义同 {@link #probeHash(ChunkPos, long)}；通常用实例绑定的
     * {@link #dimension()}，显式传参供过渡期跨维查询。
     */
    public ProbeResult probeHash(String probeDimension, ChunkPos pos, long remoteHash) {
        Boolean table = ShadowStorageHashes.matchesRemote(probeDimension, pos, remoteHash);
        if (table == Boolean.TRUE) {
            return new ProbeResult(ProbeStatus.MATCH);
        }
        RegionCache.Image image = imageFor(pos, false);
        if (image == null) {
            return new ProbeResult(table == Boolean.FALSE ? ProbeStatus.MISMATCH : ProbeStatus.ABSENT);
        }
        int index = RegionCache.localIndex(pos.x, pos.z);
        if (image.isEmptySlot(index)) {
            return new ProbeResult(table == Boolean.FALSE ? ProbeStatus.MISMATCH : ProbeStatus.ABSENT);
        }
        Long stored = image.probeHash(index);
        if (stored == null) {
            return new ProbeResult(ProbeStatus.MISMATCH);
        }
        ShadowStorageHashes.put(probeDimension, pos, stored);
        return new ProbeResult(stored == remoteHash ? ProbeStatus.MATCH : ProbeStatus.MISMATCH);
    }

    /**
     * 仅 R2/OVD 未注入柱：解压该槽。管理器不保留 NBT。
     */
    public byte[] readChunk(ChunkPos pos) {
        return readChunk(dimension, pos);
    }

    /** 指定维度读盘解压，语义同 {@link #readChunk(ChunkPos)}。 */
    public byte[] readChunk(String readDimension, ChunkPos pos) {
        RegionCache.Image image = imageFor(pos, false);
        if (image == null) {
            return null;
        }
        try {
            byte[] nbt = image.readDecompressed(RegionCache.localIndex(pos.x, pos.z), decompressCount);
            if (nbt != null) {
                Long hash = image.probeHash(RegionCache.localIndex(pos.x, pos.z));
                if (hash != null) {
                    ShadowStorageHashes.put(readDimension, pos, hash);
                }
            }
            return nbt;
        } catch (IOException e) {
            LOGGER.debug("Hassium: readChunk failed for {}", pos, e);
            return null;
        }
    }

    public boolean hasUncompressedMirror(ChunkPos pos) {
        return false;
    }

    public int decompressCount() {
        return decompressCount.get();
    }

    public int mountedRegionCount() {
        return images.size();
    }

    public boolean isRegionMounted(int regionX, int regionZ) {
        return images.containsKey(ChunkPos.asLong(regionX, regionZ));
    }

    public List<ChunkPos> snapshotWriteLog() {
        synchronized (writeLog) {
            return List.copyOf(writeLog);
        }
    }

    /**
     * 光收敛即时入队：认领脏位、快照 NBT、提交 RegionWorker，不等待 IO。
     * 未注入 / 未脏 / 已关闭则 no-op。
     */
    public void enqueue(ChunkPos pos) {
        if (closed) {
            return;
        }
        PendingWrite write = claimAndSerialize(pos);
        if (write != null) {
            submitAsync(List.of(write));
        }
    }

    /** 光收敛残留：把仍挂 lightReady 的注入柱入队（写 gate 刚放行时的补推）。 */
    public void enqueueLightReady() {
        enqueueMatching(ShadowStorageHashes::isLightReady);
    }

    /** 中途变更入队（定时刷新；不等待 IO）。 */
    public void enqueueMutations() {
        enqueueMatching(ShadowStorageHashes::isMutation);
    }

    /**
     * 对 mutation∩injected 从 serializer 取 NBT、提交对应 RegionWorker 压缩写盘并等待。
     * 定时刷新用：首次注入、尚未光收敛的柱不在此列。
     */
    public FlushResult flush(long timeoutMs) {
        return flushMatching(timeoutMs, ShadowStorageHashes::isMutation);
    }

    /**
     * 退出用：刷本维度全部剩余脏柱（首次注入残留 / 欠光 / lightReady / mutation）。
     * 欠光柱按当时 {@code isLightCorrect} 写 isLightOn，R2 读盘会续算。
     */
    public FlushResult flushRemaining(long timeoutMs) {
        return flushMatching(timeoutMs, ShadowStorageHashes::isDirty);
    }

    /** 等待异步入队写盘结束（退出 saveAll 排空队列）。 */
    public FlushResult drain(long timeoutMs) {
        long deadline = System.currentTimeMillis() + Math.max(0L, timeoutMs);
        synchronized (drainMonitor) {
            while (outstandingWrites.get() > 0) {
                long remaining = deadline - System.currentTimeMillis();
                if (remaining <= 0L) {
                    return new FlushResult(0, outstandingWrites.get(), true);
                }
                try {
                    drainMonitor.wait(remaining);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return new FlushResult(0, outstandingWrites.get(), true);
                }
            }
        }
        return new FlushResult(0, 0, false);
    }

    /** T5 单柱：若仍脏则序列化压缩写盘（卸载路径，含尚未光收敛的首次注入柱）。 */
    public boolean flushColumn(ChunkPos pos, long timeoutMs) {
        long key = DimensionKey.key(dimension, pos.x, pos.z);
        if (!ShadowStorageHashes.isDirty(key)) {
            return true;
        }
        PendingWrite write = claimAndSerialize(pos);
        if (write == null) {
            return !ShadowStorageHashes.isDirty(key);
        }
        FlushResult result = submitAndWait(List.of(write), timeoutMs);
        return result.abandoned() == 0 && !result.timedOut();
    }

    /**
     * 该 region 无注入柱且无未刷脏 → 卸压缩映像、停 worker。
     */
    public void unmountIdleRegions() {
        List<Long> keys = new ArrayList<>(images.keySet());
        for (Long regionKey : keys) {
            if (regionHasInjectedOrDirty(regionKey)) {
                continue;
            }
            RegionCache.Image image = images.remove(regionKey);
            RegionWorker worker = workers.remove(regionKey);
            if (worker != null) {
                worker.shutdown();
            }
            if (image != null) {
                try {
                    image.save(RegionCache.regionFileByKey(regionDir, regionKey));
                } catch (IOException e) {
                    LOGGER.debug("Hassium: idle region save failed {}", regionKey, e);
                }
            }
        }
    }

    public void deleteColumn(ChunkPos pos) {
        ShadowStorageHashes.remove(dimension, pos);
        RegionCache.Image image = imageFor(pos, false);
        if (image == null) {
            Path file = RegionCache.regionFile(regionDir, pos.x, pos.z);
            if (!Files.isRegularFile(file)) {
                return;
            }
            image = imageFor(pos, true);
        }
        if (image != null) {
            image.clearSlot(RegionCache.localIndex(pos.x, pos.z));
            try {
                image.save(RegionCache.regionFile(regionDir, pos.x, pos.z));
            } catch (IOException e) {
                LOGGER.debug("Hassium: deleteColumn save failed for {}", pos, e);
            }
        }
    }

    @Override
    public void close() {
        closed = true;
        drain(1_000L);
        for (RegionWorker worker : workers.values()) {
            worker.shutdown();
        }
        workers.clear();
        for (var e : images.entrySet()) {
            try {
                e.getValue().save(RegionCache.regionFileByKey(regionDir, e.getKey()));
            } catch (IOException ex) {
                LOGGER.debug("Hassium: region save on close failed {}", e.getKey(), ex);
            }
        }
        images.clear();
    }

    private void enqueueMatching(LongPredicate filter) {
        if (closed) {
            return;
        }
        List<PendingWrite> pending = new ArrayList<>();
        for (Long key : ShadowStorageHashes.dirtyKeys(dimension)) {
            if (!filter.test(key)) {
                continue;
            }
            PendingWrite write = claimAndSerialize(
                    new ChunkPos(DimensionKey.chunkXOf(key), DimensionKey.chunkZOf(key)));
            if (write != null) {
                pending.add(write);
            }
        }
        submitAsync(pending);
    }

    private FlushResult flushMatching(long timeoutMs, LongPredicate filter) {
        if (closed) {
            return new FlushResult(0, 0, true);
        }
        List<PendingWrite> pending = new ArrayList<>();
        for (Long key : ShadowStorageHashes.dirtyKeys(dimension)) {
            if (!filter.test(key)) {
                continue;
            }
            PendingWrite write = claimAndSerialize(
                    new ChunkPos(DimensionKey.chunkXOf(key), DimensionKey.chunkZOf(key)));
            if (write != null) {
                pending.add(write);
            }
        }
        return submitAndWait(pending, timeoutMs);
    }

    private PendingWrite claimAndSerialize(ChunkPos pos) {
        long key = DimensionKey.key(dimension, pos.x, pos.z);
        if (!injected.test(ChunkPos.asLong(pos.x, pos.z))) {
            return null;
        }
        boolean content = ShadowStorageHashes.isContentDirty(key);
        boolean light = ShadowStorageHashes.isLightDirty(key);
        boolean mutation = ShadowStorageHashes.isMutation(key);
        boolean lightReady = ShadowStorageHashes.isLightReady(key);
        if (!ShadowStorageHashes.claimDirty(key)) {
            return null;
        }
        Long hash = ShadowStorageHashes.get(dimension, pos);
        byte[] nbt = serializer.serialize(pos);
        if (nbt == null) {
            ShadowStorageHashes.restoreDirty(key, content, light, mutation, lightReady);
            return null;
        }
        if (hash == null) {
            hash = ShadowStorageHashes.get(dimension, pos);
        }
        return new PendingWrite(pos, nbt, content, light, mutation, lightReady, hash);
    }

    private void submitAsync(List<PendingWrite> pending) {
        if (pending.isEmpty()) {
            return;
        }
        Map<Long, List<PendingWrite>> byRegion = new HashMap<>();
        for (PendingWrite write : pending) {
            byRegion.computeIfAbsent(RegionCache.regionKey(write.pos.x, write.pos.z), k -> new ArrayList<>())
                    .add(write);
        }
        AtomicInteger written = new AtomicInteger();
        for (var e : byRegion.entrySet()) {
            RegionWorker worker = worker(e.getKey());
            List<PendingWrite> batch = e.getValue();
            outstandingWrites.incrementAndGet();
            try {
                worker.submit(() -> {
                    try {
                        writeBatch(batch, written);
                    } finally {
                        completeWrite();
                    }
                });
            } catch (RejectedExecutionException ex) {
                restoreAll(batch);
                completeWrite();
            }
        }
    }

    private void completeWrite() {
        outstandingWrites.decrementAndGet();
        synchronized (drainMonitor) {
            drainMonitor.notifyAll();
        }
    }

    private FlushResult submitAndWait(List<PendingWrite> pending, long timeoutMs) {
        if (pending.isEmpty()) {
            return new FlushResult(0, 0, false);
        }
        Map<Long, List<PendingWrite>> byRegion = new HashMap<>();
        for (PendingWrite write : pending) {
            byRegion.computeIfAbsent(RegionCache.regionKey(write.pos.x, write.pos.z), k -> new ArrayList<>())
                    .add(write);
        }
        AtomicInteger written = new AtomicInteger();
        List<Future<?>> futures = new ArrayList<>();
        Map<Future<?>, List<PendingWrite>> futureWrites = new HashMap<>();
        for (var e : byRegion.entrySet()) {
            RegionWorker worker = worker(e.getKey());
            List<PendingWrite> batch = e.getValue();
            try {
                Future<?> future = worker.submit(() -> writeBatch(batch, written));
                futures.add(future);
                futureWrites.put(future, batch);
            } catch (RejectedExecutionException ex) {
                restoreAll(batch);
            }
        }
        long deadline = System.currentTimeMillis() + Math.max(0L, timeoutMs);
        int abandoned = 0;
        boolean timedOut = false;
        for (Future<?> future : futures) {
            long remaining = deadline - System.currentTimeMillis();
            if (remaining <= 0L) {
                timedOut = true;
                future.cancel(true);
                List<PendingWrite> batch = futureWrites.get(future);
                if (batch != null && !future.isDone()) {
                    restoreAll(batch);
                    abandoned += batch.size();
                }
                continue;
            }
            try {
                future.get(remaining, TimeUnit.MILLISECONDS);
            } catch (TimeoutException e) {
                timedOut = true;
                future.cancel(true);
                List<PendingWrite> batch = futureWrites.get(future);
                if (batch != null) {
                    restoreAll(batch);
                    abandoned += batch.size();
                }
            } catch (Exception e) {
                List<PendingWrite> batch = futureWrites.get(future);
                if (batch != null) {
                    restoreAll(batch);
                    abandoned += batch.size();
                }
            }
        }
        return new FlushResult(written.get(), abandoned, timedOut);
    }

    private void writeBatch(List<PendingWrite> batch, AtomicInteger written) {
        if (batch.isEmpty()) {
            return;
        }
        List<PendingWrite> encoded = new ArrayList<>();
        RegionCache.Image image = null;
        Path regionFile = null;
        for (int i = 0; i < batch.size(); i++) {
            PendingWrite write = batch.get(i);
            if (Thread.currentThread().isInterrupted()) {
                restoreAll(encoded);
                restoreAll(batch.subList(i, batch.size()));
                return;
            }
            if (testWriteDelayMs > 0L) {
                try {
                    Thread.sleep(testWriteDelayMs);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    restoreAll(encoded);
                    restoreAll(batch.subList(i, batch.size()));
                    return;
                }
            }
            try {
                byte[] sector = HassiumType126Codec.encodeSector(write.nbt, write.hash, zstdLevel);
                byte[] payload = HassiumType126Codec.payloadAfterType(sector);
                if (image == null) {
                    image = imageFor(write.pos, true);
                    regionFile = RegionCache.regionFile(regionDir, write.pos.x, write.pos.z);
                }
                image.writePayload(RegionCache.localIndex(write.pos.x, write.pos.z), payload, write.hash);
                encoded.add(write);
            } catch (Exception e) {
                LOGGER.warn("Hassium: region write failed for {}", write.pos, e);
                ShadowStorageHashes.restoreDirty(
                        DimensionKey.key(dimension, write.pos.x, write.pos.z),
                        write.content, write.light, write.mutation, write.lightReady);
            }
        }
        if (image == null || regionFile == null || encoded.isEmpty()) {
            return;
        }
        try {
            image.save(regionFile);
            written.addAndGet(encoded.size());
            for (PendingWrite write : encoded) {
                ShadowStorageHashes.markPersisted(dimension, write.pos);
            }
            synchronized (writeLog) {
                for (PendingWrite write : encoded) {
                    writeLog.add(write.pos);
                }
            }
        } catch (Exception e) {
            LOGGER.warn("Hassium: region file save failed {}", regionFile, e);
            restoreAll(encoded);
        }
    }

    private void restoreAll(List<PendingWrite> batch) {
        for (PendingWrite write : batch) {
            ShadowStorageHashes.restoreDirty(
                    DimensionKey.key(dimension, write.pos.x, write.pos.z),
                    write.content, write.light, write.mutation, write.lightReady);
        }
    }

    private RegionCache.Image imageFor(ChunkPos pos, boolean create) {
        long key = RegionCache.regionKey(pos.x, pos.z);
        RegionCache.Image existing = images.get(key);
        if (existing != null) {
            return existing;
        }
        Path file = RegionCache.regionFile(regionDir, pos.x, pos.z);
        try {
            RegionCache.Image loaded = RegionCache.Image.load(file);
            if (loaded != null) {
                RegionCache.Image raced = images.putIfAbsent(key, loaded);
                return raced != null ? raced : loaded;
            }
        } catch (IOException e) {
            LOGGER.debug("Hassium: region load failed {}", file, e);
            if (!create) {
                return null;
            }
        }
        if (!create) {
            return null;
        }
        RegionCache.Image created = RegionCache.Image.empty();
        RegionCache.Image raced = images.putIfAbsent(key, created);
        return raced != null ? raced : created;
    }

    private RegionWorker worker(long regionKey) {
        return workers.computeIfAbsent(regionKey, RegionWorker::new);
    }

    private boolean regionHasInjectedOrDirty(long regionKey) {
        int rx = (int) regionKey;
        int rz = (int) (regionKey >> 32);
        for (int lx = 0; lx < 32; lx++) {
            for (int lz = 0; lz < 32; lz++) {
                long chunkKey = DimensionKey.key(dimension, rx * 32 + lx, rz * 32 + lz);
                if (injected.test(ChunkPos.asLong(rx * 32 + lx, rz * 32 + lz))
                        || ShadowStorageHashes.isDirty(chunkKey)) {
                    return true;
                }
            }
        }
        return false;
    }

    private record PendingWrite(ChunkPos pos, byte[] nbt, boolean content, boolean light,
                                boolean mutation, boolean lightReady, Long hash) {}

    /** 每 {@code r.x.z} 单线程队列。 */
    static final class RegionWorker {
        private final ExecutorService executor;

        RegionWorker(long regionKey) {
            int rx = (int) regionKey;
            int rz = (int) (regionKey >> 32);
            this.executor = Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "hassium-region-r." + rx + "." + rz);
                t.setDaemon(true);
                return t;
            });
        }

        Future<?> submit(Runnable task) {
            return executor.submit(task);
        }

        void shutdown() {
            executor.shutdown();
            try {
                if (!executor.awaitTermination(1, TimeUnit.SECONDS)) {
                    executor.shutdownNow();
                }
            } catch (InterruptedException e) {
                executor.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
    }
}
