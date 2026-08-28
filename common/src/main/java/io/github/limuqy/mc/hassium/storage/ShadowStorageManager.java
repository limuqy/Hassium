package io.github.limuqy.mc.hassium.storage;

import io.github.limuqy.mc.hassium.utils.DimensionKey;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.LongPredicate;
import net.minecraft.world.level.ChunkPos;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 影子缓存存储管理器：脏位 + 内存 region 压缩映像；磁盘 .mca 不实时写。
 * <p>
 * 热路径只标脏，不 {@code ChunkSerializer}。定时 {@link #scheduleFlush()}（不堵 tick）
 * 与退出 {@link #flushDirty} 从活柱快照编进映像再落盘；刷脏与 region persist 队列分离，
 * 写映像前暂停 worker 消费。
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
    private final Object flushLock = new Object();
    private final AtomicBoolean flushQueued = new AtomicBoolean();
    private final ExecutorService flushExecutor;
    private final List<ChunkPos> writeLog = new ArrayList<>();
    private volatile boolean closed;
    /**
     * 仅 1.20.1 Forge {@code revertToFrozen} 窗口：挡住新的 {@code ChunkSerializer}
     * 以免饿死注册表写锁。窗口 TAIL 即 {@link #resumeEncoding()}，退出落盘在恢复之后。
     */
    private static final java.util.concurrent.atomic.AtomicBoolean ENCODING_PAUSED =
            new java.util.concurrent.atomic.AtomicBoolean();
    /** 测试钩子：worker 编码前睡眠，用于 flush 超时。 */
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
        String threadName = "hassium-shadow-flush-" + dimension.replace(':', '_');
        this.flushExecutor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, threadName);
            t.setDaemon(true);
            return t;
        });
    }

    /** 本 manager 绑定的维度 id。 */
    public String dimension() {
        return dimension;
    }

    public void markContentDirty(ChunkPos pos) {
        ShadowStorageHashes.markContentDirty(dimension, pos);
    }

    public static void pauseEncoding() {
        ENCODING_PAUSED.set(true);
    }

    public static boolean isEncodingPaused() {
        return ENCODING_PAUSED.get();
    }

    public static void resumeEncoding() {
        ENCODING_PAUSED.set(false);
    }

    public void markLightReady(ChunkPos pos) {
        ShadowStorageHashes.markLightReady(dimension, pos);
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
     * 热路径不得序列化。保留空实现以免旧调用方误入 RegionWorker。
     */
    public void enqueue(ChunkPos pos) {
        // 只脏位由调用方 mark*；编码走 scheduleFlush / flushDirty / encodeDirty
    }

    /**
     * 测试：本维度脏柱编进映像，不落盘。
     */
    public void enqueueDirty() {
        encodeDirty(DEFAULT_FLUSH_TIMEOUT_MS);
    }

    /** 光收敛残留：编进映像，不落盘。 */
    public void enqueueLightReady() {
        synchronized (flushLock) {
            runFlushCycle(false, ShadowStorageHashes::isLightReady, DEFAULT_FLUSH_TIMEOUT_MS);
        }
    }

    /** 中途变更编进映像，不落盘。 */
    public void enqueueMutations() {
        synchronized (flushLock) {
            runFlushCycle(false, ShadowStorageHashes::isMutation, DEFAULT_FLUSH_TIMEOUT_MS);
        }
    }

    /**
     * 定时入口：不堵调用线程。暂停 region worker 后从活柱刷脏进映像并落盘。
     */
    public void scheduleFlush() {
        if (closed || ENCODING_PAUSED.get()) {
            return;
        }
        if (!flushQueued.compareAndSet(false, true)) {
            return;
        }
        outstandingWrites.incrementAndGet();
        try {
            flushExecutor.execute(() -> {
                try {
                    synchronized (flushLock) {
                        flushQueued.set(false);
                        runFlushCycle(true, ShadowStorageHashes::isDirty, DEFAULT_FLUSH_TIMEOUT_MS);
                    }
                } finally {
                    completeWrite();
                }
            });
        } catch (RejectedExecutionException e) {
            flushQueued.set(false);
            completeWrite();
        }
    }

    /**
     * 退出 / 测试：同步刷全部脏柱进映像并落盘。可与 {@link #scheduleFlush} 互斥等待。
     */
    public FlushResult flushDirty(long timeoutMs) {
        if (closed) {
            return new FlushResult(0, 0, true);
        }
        synchronized (flushLock) {
            return runFlushCycle(true, ShadowStorageHashes::isDirty, timeoutMs);
        }
    }

    /** 测试：脏柱只进映像，不写 .mca。 */
    public FlushResult encodeDirty(long timeoutMs) {
        if (closed) {
            return new FlushResult(0, 0, true);
        }
        synchronized (flushLock) {
            return runFlushCycle(false, ShadowStorageHashes::isDirty, timeoutMs);
        }
    }

    /**
     * 同步：mutation∩injected 编码进映像再落盘。
     */
    public FlushResult flush(long timeoutMs) {
        if (closed) {
            return new FlushResult(0, 0, true);
        }
        synchronized (flushLock) {
            return runFlushCycle(true, ShadowStorageHashes::isMutation, timeoutMs);
        }
    }

    public FlushResult flushRemaining(long timeoutMs) {
        return flushDirty(timeoutMs);
    }

    /** 等待 {@link #scheduleFlush} 结束。 */
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

    /** T5：脏柱不在调用线程序列化，留给定时/退出刷脏。 */
    public boolean flushColumn(ChunkPos pos, long timeoutMs) {
        long key = DimensionKey.key(dimension, pos.x, pos.z);
        if (ShadowStorageHashes.isDirty(key)) {
            return false;
        }
        saveDirtyRegionsAsync();
        return true;
    }

    /**
     * 把已脏内存映像写盘（调用线程；只写 {@code isFileDirty} 的 region）。
     * 不是全量区块序列化。热路径请用 {@link #saveDirtyRegionsAsync()}。
     */
    public FlushResult saveDirtyRegions(long timeoutMs) {
        return saveRegions(timeoutMs, null);
    }

    /**
     * 把落盘任务接到各 region worker 队尾：排在已排队的内存编码之后，不阻塞调用方。
     */
    public void saveDirtyRegionsAsync() {
        if (closed) {
            return;
        }
        for (Long regionKey : regionKeysToSave()) {
            Path file = RegionCache.regionFileByKey(regionDir, regionKey);
            worker(regionKey).requestPersist(() -> saveImage(regionKey, file));
        }
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
                    Path file = RegionCache.regionFileByKey(regionDir, regionKey);
                    image.save(file);
                    touchHeatSize(regionKey, file);
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
                Path file = RegionCache.regionFile(regionDir, pos.x, pos.z);
                image.save(file);
                touchHeatSize(RegionCache.regionKey(pos.x, pos.z), file);
            } catch (IOException e) {
                LOGGER.debug("Hassium: deleteColumn save failed for {}", pos, e);
            }
        }
    }

    /** 卸映像、停 worker、删整个 {@code r.X.Z.mca}，并清该 region 的 hash / 热度。 */
    public void deleteRegion(int regionX, int regionZ) {
        long regionKey = ChunkPos.asLong(regionX, regionZ);
        RegionWorker worker = workers.remove(regionKey);
        if (worker != null) {
            worker.shutdown();
        }
        images.remove(regionKey);
        Path file = RegionCache.regionFileByKey(regionDir, regionKey);
        try {
            Files.deleteIfExists(file);
        } catch (IOException e) {
            LOGGER.debug("Hassium: deleteRegion failed {}", file, e);
        }
        ShadowStorageHashes.removeRegion(dimension, regionX, regionZ);
        ShadowRegionHeat.removeRegion(dimension, regionX, regionZ);
    }

    @Override
    public void close() {
        closed = true;
        flushExecutor.shutdownNow();
        try {
            if (!flushExecutor.awaitTermination(200, TimeUnit.MILLISECONDS)) {
                LOGGER.debug("Hassium: flush executor still running on close");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        drain(200L);
        for (RegionWorker worker : workers.values()) {
            worker.shutdown();
        }
        workers.clear();
        for (var e : images.entrySet()) {
            try {
                Path file = RegionCache.regionFileByKey(regionDir, e.getKey());
                e.getValue().save(file);
                touchHeatSize(e.getKey(), file);
            } catch (IOException ex) {
                LOGGER.debug("Hassium: region save on close failed {}", e.getKey(), ex);
            }
        }
        images.clear();
    }

    private FlushResult runFlushCycle(boolean persist, LongPredicate filter, long timeoutMs) {
        if (closed) {
            return new FlushResult(0, 0, true);
        }
        pauseAllWorkers();
        boolean idle = awaitAllWorkersIdle(Math.min(Math.max(0L, timeoutMs), 5_000L));
        int written = 0;
        int abandoned = 0;
        boolean timedOut = !idle;
        try {
            if (!ENCODING_PAUSED.get()) {
                FlushResult encoded = encodeDirtyOnThisThread(filter, timeoutMs);
                written = encoded.written();
                abandoned += encoded.abandoned();
                timedOut |= encoded.timedOut();
            }
            if (persist) {
                FlushResult saved = saveRegions(timeoutMs, null);
                abandoned += saved.abandoned();
                timedOut |= saved.timedOut();
            }
        } finally {
            resumeAllWorkers();
        }
        return new FlushResult(written, abandoned, timedOut);
    }

    private FlushResult encodeDirtyOnThisThread(LongPredicate filter, long timeoutMs) {
        List<PendingWrite> pending = new ArrayList<>();
        for (Long key : ShadowStorageHashes.dirtyKeys(dimension)) {
            if (!filter.test(key)) {
                continue;
            }
            PendingWrite write = claimForWorker(
                    new ChunkPos(DimensionKey.chunkXOf(key), DimensionKey.chunkZOf(key)));
            if (write != null) {
                pending.add(write);
            }
        }
        AtomicInteger written = new AtomicInteger();
        long deadline = System.currentTimeMillis() + Math.max(0L, timeoutMs);
        writeBatch(pending, written, deadline);
        int abandoned = 0;
        for (PendingWrite write : pending) {
            if (ShadowStorageHashes.isDirty(DimensionKey.key(dimension, write.pos.x, write.pos.z))
                    && write.nbt == null) {
                // 超时还原后仍脏，不计入 written
            }
        }
        boolean timedOut = System.currentTimeMillis() > deadline && written.get() < pending.size();
        return new FlushResult(written.get(), abandoned, timedOut);
    }

    private void pauseAllWorkers() {
        for (RegionWorker worker : workers.values()) {
            worker.pause();
        }
    }

    private void resumeAllWorkers() {
        for (RegionWorker worker : workers.values()) {
            worker.resume();
        }
    }

    private boolean awaitAllWorkersIdle(long timeoutMs) {
        long deadline = System.currentTimeMillis() + Math.max(0L, timeoutMs);
        for (RegionWorker worker : workers.values()) {
            long remaining = deadline - System.currentTimeMillis();
            if (remaining <= 0L) {
                return false;
            }
            if (!worker.awaitIdle(remaining)) {
                return false;
            }
        }
        return true;
    }

    /** 认领脏位，NBT 由 RegionWorker 再快照（热路径不得同步序列化）。 */
    private PendingWrite claimForWorker(ChunkPos pos) {
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
        return new PendingWrite(pos, null, content, light, mutation, lightReady, hash);
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
        writeBatch(batch, written, Long.MAX_VALUE);
    }

    private void writeBatch(List<PendingWrite> batch, AtomicInteger written, long deadlineMs) {
        if (batch.isEmpty()) {
            return;
        }
        List<PendingWrite> encoded = new ArrayList<>();
        RegionCache.Image image = null;
        long imageRegionKey = Long.MIN_VALUE;
        for (int i = 0; i < batch.size(); i++) {
            PendingWrite write = batch.get(i);
            if (ENCODING_PAUSED.get() || Thread.currentThread().isInterrupted()
                    || System.currentTimeMillis() > deadlineMs) {
                restoreAll(batch.subList(i, batch.size()));
                break;
            }
            if (testWriteDelayMs > 0L) {
                try {
                    Thread.sleep(testWriteDelayMs);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    restoreAll(batch.subList(i, batch.size()));
                    break;
                }
            }
            if (ENCODING_PAUSED.get() || Thread.currentThread().isInterrupted()
                    || System.currentTimeMillis() > deadlineMs) {
                restoreAll(batch.subList(i, batch.size()));
                break;
            }
            try {
                byte[] nbt = write.nbt;
                if (nbt == null) {
                    nbt = serializer.serialize(write.pos);
                    if (nbt == null) {
                        ShadowStorageHashes.restoreDirty(
                                DimensionKey.key(dimension, write.pos.x, write.pos.z),
                                write.content, write.light, write.mutation, write.lightReady);
                        continue;
                    }
                }
                Long hash = write.hash;
                if (hash == null) {
                    hash = ShadowStorageHashes.get(dimension, write.pos);
                }
                byte[] sector = HassiumType126Codec.encodeSector(nbt, hash, zstdLevel);
                byte[] payload = HassiumType126Codec.payloadAfterType(sector);
                // review-fix: image 仅同 region 复用。encodeDirtyOnThisThread 的批次来自脏表、
                // 不按 region 分组；若固定首块 region，后续柱会带着自己的 localIndex(x&31,z&31)
                // 写进首块 .mca 的同槽位——与相邻 region 镜像柱（差 32）互串，原版按坐标读时
                // 报 wrong location 且内容错位（relocating）。submitAsync/submitAndWait 已按
                // region 分组，本修复让混批路径同样安全。
                long writeRegionKey = RegionCache.regionKey(write.pos.x, write.pos.z);
                if (image == null || imageRegionKey != writeRegionKey) {
                    image = imageFor(write.pos, true);
                    imageRegionKey = writeRegionKey;
                }
                image.writePayload(RegionCache.localIndex(write.pos.x, write.pos.z), payload, hash);
                encoded.add(write);
            } catch (Exception e) {
                LOGGER.warn("Hassium: region encode failed for {}", write.pos, e);
                ShadowStorageHashes.restoreDirty(
                        DimensionKey.key(dimension, write.pos.x, write.pos.z),
                        write.content, write.light, write.mutation, write.lightReady);
            }
        }
        if (encoded.isEmpty()) {
            return;
        }
        written.addAndGet(encoded.size());
        for (PendingWrite write : encoded) {
            ShadowStorageHashes.markPersisted(dimension, write.pos);
        }
        synchronized (writeLog) {
            for (PendingWrite write : encoded) {
                writeLog.add(write.pos);
            }
        }
    }

    private FlushResult saveRegions(long timeoutMs, Long onlyRegionKey) {
        long deadline = System.currentTimeMillis() + Math.max(0L, timeoutMs);
        int saved = 0;
        int abandoned = 0;
        boolean timedOut = false;
        for (Long regionKey : regionKeysToSave()) {
            if (onlyRegionKey != null && !onlyRegionKey.equals(regionKey)) {
                continue;
            }
            if (System.currentTimeMillis() > deadline) {
                timedOut = true;
                abandoned++;
                continue;
            }
            Path file = RegionCache.regionFileByKey(regionDir, regionKey);
            try {
                if (saveImage(regionKey, file)) {
                    saved++;
                }
            } catch (Exception e) {
                abandoned++;
            }
        }
        return new FlushResult(saved, abandoned, timedOut);
    }

    private Set<Long> regionKeysToSave() {
        Set<Long> keys = new HashSet<>();
        keys.addAll(images.keySet());
        keys.addAll(workers.keySet());
        return keys;
    }

    private boolean saveImage(long regionKey, Path file) {
        RegionCache.Image image = images.get(regionKey);
        if (image == null || !image.isFileDirty()) {
            return false;
        }
        try {
            image.save(file);
            touchHeatSize(regionKey, file);
            return true;
        } catch (IOException e) {
            LOGGER.warn("Hassium: region file save failed {}", file, e);
            return false;
        }
    }

    private void touchHeatSize(long regionKey, Path file) {
        try {
            if (Files.isRegularFile(file)) {
                ShadowRegionHeat.updateRegionSize(dimension,
                        RegionCache.regionXOf(regionKey), RegionCache.regionZOf(regionKey),
                        Files.size(file));
            }
        } catch (IOException ignored) {
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

    /** 每 {@code r.x.z} 单线程队列：仅 persist；刷脏编码在 flush 线程，写映像前暂停本队列。 */
    static final class RegionWorker {
        private final ExecutorService executor;
        private final java.util.concurrent.atomic.AtomicBoolean persistQueued =
                new java.util.concurrent.atomic.AtomicBoolean();
        private final AtomicBoolean paused = new AtomicBoolean();
        private final AtomicInteger inflight = new AtomicInteger();
        private final Object idleMonitor = new Object();

        RegionWorker(long regionKey) {
            int rx = (int) regionKey;
            int rz = (int) (regionKey >> 32);
            this.executor = Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "hassium-region-r." + rx + "." + rz);
                t.setDaemon(true);
                return t;
            });
        }

        void pause() {
            paused.set(true);
        }

        void resume() {
            paused.set(false);
        }

        boolean awaitIdle(long timeoutMs) {
            long deadline = System.currentTimeMillis() + Math.max(0L, timeoutMs);
            synchronized (idleMonitor) {
                while (inflight.get() > 0) {
                    long remaining = deadline - System.currentTimeMillis();
                    if (remaining <= 0L) {
                        return false;
                    }
                    try {
                        idleMonitor.wait(remaining);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return false;
                    }
                }
            }
            return true;
        }

        Future<?> submit(Runnable task) {
            inflight.incrementAndGet();
            try {
                return executor.submit(() -> {
                    try {
                        task.run();
                    } finally {
                        done();
                    }
                });
            } catch (RejectedExecutionException e) {
                done();
                throw e;
            }
        }

        void requestPersist(Runnable persist) {
            if (paused.get()) {
                return;
            }
            if (!persistQueued.compareAndSet(false, true)) {
                return;
            }
            inflight.incrementAndGet();
            try {
                executor.submit(() -> {
                    try {
                        persist.run();
                    } finally {
                        persistQueued.set(false);
                        done();
                    }
                });
            } catch (RejectedExecutionException e) {
                persistQueued.set(false);
                done();
            }
        }

        private void done() {
            if (inflight.decrementAndGet() == 0) {
                synchronized (idleMonitor) {
                    idleMonitor.notifyAll();
                }
            }
        }

        void shutdown() {
            executor.shutdown();
            try {
                if (!executor.awaitTermination(200, TimeUnit.MILLISECONDS)) {
                    executor.shutdownNow();
                }
            } catch (InterruptedException e) {
                executor.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
    }
}
