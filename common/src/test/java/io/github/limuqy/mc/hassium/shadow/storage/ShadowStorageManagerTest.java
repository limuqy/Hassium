package io.github.limuqy.mc.hassium.shadow.storage;

import io.github.limuqy.mc.hassium.shadow.storage.ShadowStorageManager;
import io.github.limuqy.mc.hassium.shadow.storage.ShadowStorageHashes;
import io.github.limuqy.mc.hassium.shadow.storage.ShadowRegionHeat;
import io.github.limuqy.mc.hassium.shadow.storage.RegionCache;
import io.github.limuqy.mc.hassium.shadow.storage.HassiumType126Codec;
import io.github.limuqy.mc.hassium.utils.DimensionKey;
import io.github.limuqy.mc.hassium.compression.HassiumCompression;
import java.nio.file.Path;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.world.level.ChunkPos;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ShadowStorageManagerTest {

    @TempDir
    Path regionDir;

    private ShadowStorageManager manager;
    private final Set<Long> injected = ConcurrentHashMap.newKeySet();
    private final AtomicInteger serializeCalls = new AtomicInteger();
    private byte[] nbtPayload = new byte[]{1, 2, 3, 4, 5, 6, 7, 8, 9, 10};

    @BeforeAll
    static void initCompression() {
        HassiumCompression.reset();
        HassiumCompression.initialize();
    }

    @BeforeEach
    void setUp() {
        ShadowStorageManager.resumeEncoding();
        ShadowStorageHashes.clear();
        serializeCalls.set(0);
        injected.clear();
        manager = new ShadowStorageManager(regionDir, pos -> {
            serializeCalls.incrementAndGet();
            return nbtPayload.clone();
        }, injected::contains, 1);
    }

    @AfterEach
    void tearDown() {
        if (manager != null) {
            manager.close();
        }
        ShadowStorageManager.resumeEncoding();
        ShadowStorageHashes.clear();
        ShadowRegionHeat.reset();
    }

    @Test
    @DisplayName("光收敛只进内存映像，encodeDirty 不写 .mca")
    void lightReadyDoesNotWriteRegionFileUntilSave() throws Exception {
        ChunkPos pos = new ChunkPos(1, 2);
        injected.add(ChunkPos.asLong(pos.x, pos.z));
        ShadowStorageHashes.put(pos, 8L);
        manager.markContentDirty(pos);
        manager.markLightReady(pos);
        assertFalse(manager.encodeDirty(5_000L).timedOut());
        Path file = RegionCache.regionFile(regionDir, pos.x, pos.z);
        assertFalse(java.nio.file.Files.isRegularFile(file), "热路径不得整文件落盘");
        assertFalse(manager.saveDirtyRegions(5_000L).timedOut());
        assertTrue(java.nio.file.Files.isRegularFile(file));
    }

    @Test
    @DisplayName("flushColumn：脏柱同步编码+落盘，成功后 isDirty=false 可读回")
    void flushColumnPersistsDirtyColumnBeforeUnload() throws Exception {
        ChunkPos pos = new ChunkPos(5, 6);
        injected.add(ChunkPos.asLong(pos.x, pos.z));
        ShadowStorageHashes.put(pos, 99L);
        manager.markContentDirty(pos);
        manager.markLightReady(pos);
        assertTrue(ShadowStorageHashes.isDirty(pos), "脏位在 flushColumn 前应存在");
        assertTrue(manager.flushColumn(pos, 5_000L), "脏柱 flushColumn 必须成功");
        assertFalse(ShadowStorageHashes.isDirty(pos), "成功后脏位应清零");
        Path file = RegionCache.regionFile(regionDir, pos.x, pos.z);
        assertTrue(java.nio.file.Files.isRegularFile(file), "flushColumn 必须写 .mca");
        // 模拟 unload 后回程读盘
        injected.remove(ChunkPos.asLong(pos.x, pos.z));
        manager.close();
        manager = new ShadowStorageManager(regionDir, p -> nbtPayload.clone(), injected::contains, 1);
        byte[] read = manager.readChunk(pos);
        assertArrayEquals(nbtPayload, read, "回程 loadFromDisk 必须读到 flushColumn 写入的柱");
    }

    @Test
    @DisplayName("flushColumn：未注入脏位失败且不清脏，unload 不得摘表")
    void flushColumnFailsWhenNotInjected() {
        ChunkPos pos = new ChunkPos(9, 9);
        ShadowStorageHashes.put(pos, 1L);
        manager.markContentDirty(pos);
        assertFalse(manager.flushColumn(pos, 1_000L));
        assertTrue(ShadowStorageHashes.isDirty(pos), "失败时不得清脏");
    }

    @Test
    @DisplayName("缺文件 / 空槽 probeHash → mismatch，0 次整柱解压")
    void probeHashMissingFileIsMismatchWithoutDecompress() {
        ChunkPos pos = new ChunkPos(3, 7);
        ShadowStorageManager.ProbeResult missing = manager.probeHash(pos, 99L);
        assertFalse(missing.match());
        assertEquals(ShadowStorageManager.ProbeStatus.ABSENT, missing.status());
        assertEquals(0, manager.decompressCount());

        injected.add(ChunkPos.asLong(pos.x, pos.z));
        ShadowStorageHashes.put(pos, 42L);
        persistIngest(pos);
        manager.close();
        manager = new ShadowStorageManager(regionDir, pos2 -> nbtPayload.clone(), injected::contains, 1);

        ChunkPos empty = new ChunkPos(4, 7); // 同 region 另一空槽
        ShadowStorageManager.ProbeResult emptySlot = manager.probeHash(empty, 42L);
        assertEquals(ShadowStorageManager.ProbeStatus.ABSENT, emptySlot.status());
        assertEquals(0, manager.decompressCount());
    }

    @Test
    @DisplayName("markDirty 不分配 NBT；未刷脏的首次注入不进 flush()")
    void markDirtyDoesNotSerializeUntilFlush() {
        ChunkPos pos = new ChunkPos(1, 1);
        injected.add(ChunkPos.asLong(pos.x, pos.z));
        ShadowStorageHashes.put(pos, 7L);
        manager.markContentDirty(pos);
        assertEquals(0, serializeCalls.get());
        assertTrue(ShadowStorageHashes.isDirty(pos));
        assertEquals(0, manager.flush(5_000L).written(), "首次注入未 mutation，flush 不刷");
        assertEquals(0, serializeCalls.get());
        manager.markLightReady(pos);
        assertFalse(manager.encodeDirty(5_000L).timedOut());
        assertEquals(1, serializeCalls.get());
        assertFalse(ShadowStorageHashes.isDirty(pos));
        assertTrue(ShadowStorageHashes.isPersisted(
                DimensionKey.key(DimensionKey.OVERWORLD, pos.x, pos.z)));
    }

    @Test
    @DisplayName("encodeDirty 只进映像，不写 .mca")
    void encodeDirtyDoesNotWriteRegionFile() throws Exception {
        ChunkPos pos = new ChunkPos(14, 1);
        injected.add(ChunkPos.asLong(pos.x, pos.z));
        ShadowStorageHashes.put(pos, 33L);
        manager.markContentDirty(pos);
        manager.encodeDirty(5_000L);
        Path file = RegionCache.regionFile(regionDir, pos.x, pos.z);
        assertFalse(java.nio.file.Files.isRegularFile(file), "刷脏编码不得落盘");
        assertEquals(1, serializeCalls.get());
        assertFalse(manager.saveDirtyRegions(5_000L).timedOut());
        assertTrue(java.nio.file.Files.isRegularFile(file));
    }

    @Test
    @DisplayName("enqueueDirty 刷 contentDirty；saveDirtyRegions 才落盘")
    void enqueueDirtyPicksContentDirtyThenSaveWritesFile() throws Exception {
        ChunkPos pos = new ChunkPos(15, 1);
        injected.add(ChunkPos.asLong(pos.x, pos.z));
        ShadowStorageHashes.put(pos, 34L);
        manager.markContentDirty(pos);
        assertEquals(0, serializeCalls.get());
        manager.enqueueDirty();
        assertEquals(1, serializeCalls.get());
        Path file = RegionCache.regionFile(regionDir, pos.x, pos.z);
        assertFalse(java.nio.file.Files.isRegularFile(file), "刷脏入队仍不落盘");
        assertFalse(manager.saveDirtyRegions(5_000L).timedOut());
        assertTrue(java.nio.file.Files.isRegularFile(file));
        assertFalse(ShadowStorageHashes.isDirty(pos));
    }

    @Test
    @DisplayName("阶段2：已 persist 后再 markLightDirty+encodeDirty 再序列化")
    void lightDirtyAfterPersistReenqueues() {
        ChunkPos pos = new ChunkPos(13, 1);
        injected.add(ChunkPos.asLong(pos.x, pos.z));
        ShadowStorageHashes.put(pos, 32L);
        persistIngest(pos);
        assertEquals(1, serializeCalls.get());
        long key = DimensionKey.key(DimensionKey.OVERWORLD, pos.x, pos.z);
        ShadowStorageHashes.markLightDirty(pos);
        assertTrue(ShadowStorageHashes.isMutation(key));
        assertFalse(manager.encodeDirty(5_000L).timedOut());
        assertEquals(2, serializeCalls.get());
        assertFalse(ShadowStorageHashes.isDirty(pos));
        assertTrue(ShadowStorageHashes.isPersisted(key));
    }

    @Test
    @DisplayName("flush() 仍只刷 mutation；enqueueDirty 才刷首次注入 contentDirty")
    void flushOnlyWritesMutations() {
        ChunkPos ingest = new ChunkPos(2, 2);
        ChunkPos mutated = new ChunkPos(3, 3);
        injected.add(ChunkPos.asLong(ingest.x, ingest.z));
        injected.add(ChunkPos.asLong(mutated.x, mutated.z));
        ShadowStorageHashes.put(ingest, 1L);
        ShadowStorageHashes.put(mutated, 2L);
        manager.markContentDirty(ingest);
        ShadowStorageHashes.markPersisted(
                DimensionKey.key(DimensionKey.OVERWORLD, mutated.x, mutated.z));
        manager.markContentDirty(mutated);
        assertTrue(ShadowStorageHashes.isMutation(
                DimensionKey.key(DimensionKey.OVERWORLD, mutated.x, mutated.z)));
        assertFalse(ShadowStorageHashes.isMutation(
                DimensionKey.key(DimensionKey.OVERWORLD, ingest.x, ingest.z)));
        assertEquals(1, manager.flush(5_000L).written());
        assertTrue(ShadowStorageHashes.isDirty(ingest), "未 mutation 的首次注入仍不进 flush()");
        assertFalse(ShadowStorageHashes.isDirty(mutated));
        manager.enqueueDirty();
        assertFalse(manager.saveDirtyRegions(5_000L).timedOut());
        assertFalse(ShadowStorageHashes.isDirty(ingest));
    }

    @Test
    @DisplayName("pauseEncoding 后 encodeDirty 不再序列化")
    void pauseEncodingSkipsEnqueue() {
        ChunkPos pos = new ChunkPos(11, 11);
        injected.add(ChunkPos.asLong(pos.x, pos.z));
        ShadowStorageHashes.put(pos, 21L);
        manager.markContentDirty(pos);
        ShadowStorageManager.pauseEncoding();
        assertTrue(ShadowStorageManager.isEncodingPaused());
        manager.encodeDirty(5_000L);
        assertEquals(0, serializeCalls.get());
        ShadowStorageManager.resumeEncoding();
        assertFalse(manager.encodeDirty(5_000L).timedOut());
        assertEquals(1, serializeCalls.get());
    }

    @Test
    @DisplayName("saveDirtyRegions 只写已编码映像，不再走 ChunkSerializer")
    void saveDirtyRegionsDoesNotSerialize() {
        ChunkPos pos = new ChunkPos(9, 9);
        injected.add(ChunkPos.asLong(pos.x, pos.z));
        ShadowStorageHashes.put(pos, 13L);
        persistIngest(pos);
        int before = serializeCalls.get();
        assertFalse(manager.saveDirtyRegions(5_000L).timedOut());
        assertEquals(before, serializeCalls.get(), "退出落盘不得再序列化活柱");
    }

    @Test
    @DisplayName("已 persist 的点亮柱退出再 markLightReady 会按当前层重写")
    void lightReadyAfterPersistRewritesColumn() {
        ChunkPos pos = new ChunkPos(4, 4);
        injected.add(ChunkPos.asLong(pos.x, pos.z));
        ShadowStorageHashes.put(pos, 9L);
        persistIngest(pos);
        assertEquals(1, serializeCalls.get());
        assertTrue(ShadowStorageHashes.isPersisted(
                DimensionKey.key(DimensionKey.OVERWORLD, pos.x, pos.z)));
        assertFalse(ShadowStorageHashes.isDirty(pos));
        manager.markLightReady(pos);
        assertFalse(manager.encodeDirty(5_000L).timedOut());
        assertEquals(2, serializeCalls.get(), "退出终态快照应再序列化一次");
        assertFalse(ShadowStorageHashes.isDirty(pos));
    }

    @Test
    @DisplayName("活柱与 RegionCache 同时存在时，活柱不以无压缩 NBT 镜像")
    void liveColumnHasNoUncompressedNbtMirror() {
        ChunkPos pos = new ChunkPos(8, 8);
        injected.add(ChunkPos.asLong(pos.x, pos.z));
        ShadowStorageHashes.put(pos, 11L);
        manager.markContentDirty(pos);
        assertFalse(manager.hasUncompressedMirror(pos));
        persistIngest(pos);
        assertFalse(manager.hasUncompressedMirror(pos));
        assertTrue(manager.mountedRegionCount() <= 1);
    }

    @Test
    @DisplayName("lightDirty 时 content hash 仍 hit")
    void lightDirtyDoesNotBlockContentHit() {
        ChunkPos pos = new ChunkPos(2, 3);
        ShadowStorageHashes.put(pos, 0xABCDEFL);
        manager.markLightReady(pos);
        assertTrue(ShadowStorageHashes.isLightDirty(pos));
        assertEquals(Boolean.TRUE, ShadowStorageHashes.matchesRemote(pos, 0xABCDEFL));
        ShadowStorageManager.ProbeResult probe = manager.probeHash(pos, 0xABCDEFL);
        assertTrue(probe.match());
        assertEquals(0, manager.decompressCount());
    }

    @Test
    @DisplayName("每 region 写队列串行")
    void regionWritesAreSerial() {
        ChunkPos a = new ChunkPos(0, 0);
        ChunkPos b = new ChunkPos(1, 0);
        injected.add(ChunkPos.asLong(a.x, a.z));
        injected.add(ChunkPos.asLong(b.x, b.z));
        ShadowStorageHashes.put(a, 1L);
        ShadowStorageHashes.put(b, 2L);
        persistIngest(a);
        persistIngest(b);
        var log = manager.snapshotWriteLog();
        assertEquals(2, log.size());
        assertEquals(1, manager.mountedRegionCount());
        assertTrue(manager.isRegionMounted(0, 0));
    }

    @Test
    @DisplayName("flush 超时 abandoned")
    void flushTimeoutAbandoned() {
        ChunkPos pos = new ChunkPos(5, 5);
        injected.add(ChunkPos.asLong(pos.x, pos.z));
        ShadowStorageHashes.put(pos, 3L);
        ShadowStorageHashes.markPersisted(
                DimensionKey.key(DimensionKey.OVERWORLD, pos.x, pos.z));
        manager.markContentDirty(pos);
        manager.testWriteDelayMs = 400L;
        ShadowStorageManager.FlushResult result = manager.flush(50L);
        assertTrue(result.timedOut() || result.abandoned() > 0);
        assertTrue(ShadowStorageHashes.isDirty(pos), "超时应还原脏位");
    }

    @Test
    @DisplayName("无注入柱的压缩映像可卸；再 probe 可重新 load")
    void idleCompressedImageUnmountsAndReloads() throws Exception {
        ChunkPos pos = new ChunkPos(10, 10);
        injected.add(ChunkPos.asLong(pos.x, pos.z));
        ShadowStorageHashes.put(pos, 99L);
        persistIngest(pos);
        injected.clear();
        manager.unmountIdleRegions();
        assertEquals(0, manager.mountedRegionCount());

        ShadowStorageHashes.clear(); // 新会话：HashIndex 空，磁盘仍在
        ShadowStorageManager.ProbeResult probe = manager.probeHash(pos, 99L);
        assertTrue(probe.match());
        assertTrue(manager.mountedRegionCount() >= 1);
        byte[] nbt = manager.readChunk(pos);
        assertTrue(nbt != null && nbt.length > 0);
        assertEquals(1, manager.decompressCount());
    }

    @Test
    @DisplayName("flush 期间 HashIndex 被清空仍写出 0x48 头，新会话 probe 可命中")
    void flushCapturesHashBeforeIndexClear() {
        ChunkPos pos = new ChunkPos(12, 4);
        injected.add(ChunkPos.asLong(pos.x, pos.z));
        ShadowStorageHashes.put(pos, 0xCAFEBABEL);
        manager.markContentDirty(pos);
        manager.close();
        manager = new ShadowStorageManager(regionDir, pos2 -> {
            byte[] nbt = nbtPayload.clone();
            ShadowStorageHashes.clear();
            return nbt;
        }, injected::contains, 1);
        manager.markLightReady(pos);
        assertFalse(manager.encodeDirty(5_000L).timedOut());
        manager.close();
        manager = new ShadowStorageManager(regionDir, pos2 -> nbtPayload.clone(), injected::contains, 1);
        assertTrue(manager.probeHash(pos, 0xCAFEBABEL).match());
        assertEquals(0, manager.decompressCount());
    }

    @Test
    @DisplayName("表项过期 mismatch 时仍以磁盘 9B 头为准")
    void probeHashTrustsDiskWhenTableStale() {
        ChunkPos pos = new ChunkPos(7, 2);
        injected.add(ChunkPos.asLong(pos.x, pos.z));
        ShadowStorageHashes.put(pos, 0x1111L);
        persistIngest(pos);
        injected.clear();
        manager.unmountIdleRegions();
        ShadowStorageHashes.put(pos, 0xDEADL); // 脏表
        assertTrue(manager.probeHash(pos, 0x1111L).match());
        assertEquals(0, manager.decompressCount());
        assertEquals(Boolean.TRUE, ShadowStorageHashes.matchesRemote(pos, 0x1111L));
    }

    @Test
    @DisplayName("Anvil 头按 4B location 读；三平面读法会漏槽")
    void anvilHeaderUsesSequentialLocationsNotThreePlanes() throws Exception {
        ChunkPos pos = new ChunkPos(3, 1);
        injected.add(ChunkPos.asLong(pos.x, pos.z));
        ShadowStorageHashes.put(pos, 5L);
        persistIngest(pos);
        Path file = RegionCache.regionFile(regionDir, pos.x, pos.z);
        byte[] header = java.nio.file.Files.readAllBytes(file);
        header = java.util.Arrays.copyOf(header, RegionCache.SECTOR_SIZE);
        int index = RegionCache.localIndex(pos.x, pos.z);
        assertTrue(RegionCache.locationAt(header, index) != 0);
        int threePlane = (header[index] & 0xFF) << 16
                | (header[index + 1024] & 0xFF) << 8
                | (header[index + 2048] & 0xFF);
        assertEquals(0, threePlane, "标准 Anvil 头三平面读应为 0，旧 bloom 会漏报");
    }

    @Test
    @DisplayName("同一 r.x.z 不并排两份压缩映像")
    void oneCompressedImagePerRegion() {
        ChunkPos a = new ChunkPos(0, 0);
        ChunkPos b = new ChunkPos(31, 31);
        injected.add(ChunkPos.asLong(a.x, a.z));
        injected.add(ChunkPos.asLong(b.x, b.z));
        ShadowStorageHashes.put(a, 1L);
        ShadowStorageHashes.put(b, 2L);
        persistIngest(a);
        persistIngest(b);
        manager.probeHash(a, 1L);
        manager.probeHash(b, 2L);
        assertEquals(1, manager.mountedRegionCount());
        assertTrue(manager.isRegionMounted(0, 0));
        assertFalse(manager.isRegionMounted(1, 0));
    }

    @Test
    @DisplayName("跨维同坐标：nether manager flush/probe 与 overworld 互不干扰")
    void crossDimensionManagersDoNotInterfere() {
        ChunkPos pos = new ChunkPos(6, 6);
        injected.add(ChunkPos.asLong(pos.x, pos.z));

        ShadowStorageManager nether = new ShadowStorageManager(
                DimensionKey.NETHER, regionDir, pos2 -> nbtPayload.clone(), injected::contains, 1);
        try {
            assertEquals(DimensionKey.NETHER, nether.dimension());
            ShadowStorageHashes.put(DimensionKey.OVERWORLD, pos, 0x111L);
            ShadowStorageHashes.put(DimensionKey.NETHER, pos, 0x222L);
            nether.markContentDirty(pos);
            assertTrue(ShadowStorageHashes.isContentDirty(DimensionKey.NETHER, pos));
            assertFalse(ShadowStorageHashes.isContentDirty(DimensionKey.OVERWORLD, pos));

            // nether probe 只看 nether 键：表值 0x222 命中
            assertTrue(nether.probeHash(pos, 0x222L).match());
            // overworld 表值不受 nether flush 影响
            assertEquals(Boolean.TRUE, ShadowStorageHashes.matchesRemote(DimensionKey.OVERWORLD, pos, 0x111L));
        } finally {
            nether.close();
        }
    }

    @Test
    @DisplayName("原版写入收进映像：adopt 柱落盘重载后各自自持（撕裂写回归）")
    void adoptedVanillaWritesSurviveWholeFileRewrite() throws Exception {
        // 影子存档单写者 = 映像：原版 RegionFile 的写入经 adoptEncodedColumn 进映像，
        // 不再写 .mca。多柱（含镜像 localIndex 的跨 region 对）落盘重载后必须各自自持。
        ChunkPos positive = new ChunkPos(19, 4);
        ChunkPos negative = new ChunkPos(-13, 4);
        ChunkPos neighbour = new ChunkPos(20, 4);
        assertEquals(RegionCache.localIndex(positive.x, positive.z),
                RegionCache.localIndex(negative.x, negative.z), "前置：镜像槽位");

        assertTrue(manager.adoptEncodedColumn(positive, adoptedPayload(0xA1L, 11), 0xA1L));
        assertTrue(manager.adoptEncodedColumn(negative, adoptedPayload(0xB2L, 22), 0xB2L));
        assertTrue(manager.adoptEncodedColumn(neighbour, adoptedPayload(0xC3L, 33), 0xC3L));
        assertFalse(manager.adoptEncodedColumn(positive, new byte[0], 0xA1L), "空载荷拒绝");

        // 清表走映像路径（HashIndex 命中会短路，掩盖槽位错位）
        ShadowStorageHashes.clear();
        assertTrue(manager.probeHash(positive, 0xA1L).match());
        assertTrue(manager.probeHash(negative, 0xB2L).match());
        assertTrue(manager.probeHash(neighbour, 0xC3L).match());
        assertArrayEquals(adoptedNbt(11), manager.readChunk(positive), "解压回读不得串槽");

        assertFalse(manager.saveDirtyRegions(5_000L).timedOut());
        manager.close();
        manager = new ShadowStorageManager(regionDir, pos2 -> nbtPayload.clone(), injected::contains, 1);
        ShadowStorageHashes.clear();
        assertTrue(manager.probeHash(positive, 0xA1L).match(), "落盘后正 region adopt 柱自持");
        assertTrue(manager.probeHash(negative, 0xB2L).match(), "落盘后负 region adopt 柱不得串槽");
        assertTrue(manager.probeHash(neighbour, 0xC3L).match());
        assertArrayEquals(adoptedNbt(11), manager.readChunk(positive), "重载后解压回读仍自持");
        assertArrayEquals(adoptedNbt(22), manager.readChunk(negative));
        assertArrayEquals(adoptedNbt(33), manager.readChunk(neighbour));
    }

    @Test
    @DisplayName("超 255 扇区柱整槽不落盘，且不覆写邻槽（钳位回归）")
    void oversizeColumnIsDroppedWithoutCorruptingNeighbour() throws Exception {
        // 旧实现把超限柱扇区数钳到 255，随后 arraycopy 越界覆写后续槽数据（或直接 AIOOBE）。
        // 新实现整槽不进文件：邻槽安全，该柱退化为下次会话 cache miss。
        ChunkPos neighbour = new ChunkPos(9, 1);
        ChunkPos oversize = new ChunkPos(10, 1);
        assertEquals(RegionCache.regionKey(neighbour.x, neighbour.z),
                RegionCache.regionKey(oversize.x, oversize.z), "前置：同 region");
        assertTrue(manager.adoptEncodedColumn(neighbour, adoptedPayload(0xD4L, 44), 0xD4L));
        assertTrue(manager.adoptEncodedColumn(oversize, new byte[255 * 4096], 0xE5L),
                "超槽位柱照收（本会话仍由映像服务）");
        assertFalse(manager.saveDirtyRegions(5_000L).timedOut());

        manager.close();
        manager = new ShadowStorageManager(regionDir, pos2 -> nbtPayload.clone(), injected::contains, 1);
        ShadowStorageHashes.clear();
        assertTrue(manager.probeHash(neighbour, 0xD4L).match(), "邻槽不得被超槽位柱覆写");
        assertArrayEquals(adoptedNbt(44), manager.readChunk(neighbour), "邻槽内容自持");
        assertEquals(ShadowStorageManager.ProbeStatus.ABSENT, manager.probeHash(oversize, 0xE5L).status(),
                "超槽位柱整槽不进文件（退化为 cache miss，而非损坏邻槽）");
    }

    @Test
    @DisplayName("整文件落盘走 tmp+原子改名：不留 tmp 残留，目标被完整替换")
    void savePublishesRegionFileAtomically() throws Exception {
        // 旧实现 Files.write 原地截断重写：写入窗口内强退/断电会留半截 .mca（整个 region 退化）。
        // 新实现写同目录 tmp → force → ATOMIC_MOVE；本用例钉住可观测契约：无 tmp 残留、
        // 旧柱扇区随重写真正消失、产物仍是可读的完整映像。
        ChunkPos kept = new ChunkPos(6, 1);
        ChunkPos dropped = new ChunkPos(7, 1);
        assertEquals(RegionCache.regionKey(kept.x, kept.z),
                RegionCache.regionKey(dropped.x, dropped.z), "前置：同 region");
        Path file = RegionCache.regionFile(regionDir, kept.x, kept.z);
        Path tmp = file.resolveSibling(file.getFileName() + RegionCache.Image.TMP_SUFFIX);

        assertTrue(manager.adoptEncodedColumn(kept, adoptedPayload(0x51L, 51), 0x51L));
        assertTrue(manager.adoptEncodedColumn(dropped, adoptedPayload(0x62L, 62), 0x62L));
        assertFalse(manager.saveDirtyRegions(5_000L).timedOut());
        assertFalse(java.nio.file.Files.exists(tmp), "落盘后不得留 .tmp 残留");
        long twoColumnBytes = java.nio.file.Files.size(file);

        manager.deleteColumn(dropped);
        assertFalse(java.nio.file.Files.exists(tmp), "重写后不得留 .tmp 残留");
        assertTrue(java.nio.file.Files.size(file) < twoColumnBytes,
                "整文件替换：被删柱的扇区必须随重写消失");

        ShadowStorageHashes.clear();
        manager.close();
        manager = new ShadowStorageManager(regionDir, pos2 -> nbtPayload.clone(), injected::contains, 1);
        assertTrue(manager.probeHash(kept, 0x51L).match(), "替换后保留柱自持");
        assertArrayEquals(adoptedNbt(51), manager.readChunk(kept), "改名不得损坏内容");
        assertEquals(ShadowStorageManager.ProbeStatus.ABSENT, manager.probeHash(dropped, 0x62L).status(),
                "被删柱必须真正消失");
    }

    @Test
    @DisplayName("目标 .mca 被别的句柄持有（Windows 共享删除受限）时退化为原地写，不丢柱")
    void saveFallsBackToInPlaceWhenTargetHandleHeld() throws Exception {
        // 影子服务端自己的原版 RegionFile 长期持有同一 .mca 的 FileChannel（构造即 open），
        // Windows 未授予 FILE_SHARE_DELETE → 原子改名必被拒。退化路径必须仍然落盘完整内容，
        // 否则脏位永不清、重连复用整条链断掉（实测 ATOMIC_MOVE 抛 AccessDeniedException）。
        ChunkPos pos = new ChunkPos(6, 3);
        injected.add(ChunkPos.asLong(pos.x, pos.z));
        ShadowStorageHashes.put(pos, 0x71L);
        persistIngest(pos);
        Path file = RegionCache.regionFile(regionDir, pos.x, pos.z);
        Path tmp = file.resolveSibling(file.getFileName() + RegionCache.Image.TMP_SUFFIX);
        assertTrue(java.nio.file.Files.isRegularFile(file), "前置：先有一次落盘");

        try (java.nio.channels.FileChannel held = java.nio.channels.FileChannel.open(
                file, java.nio.file.StandardOpenOption.READ, java.nio.file.StandardOpenOption.WRITE)) {
            assertTrue(held.isOpen());
            ShadowStorageHashes.put(pos, 0x72L);
            manager.markContentDirty(pos);
            manager.markLightReady(pos);
            assertFalse(manager.encodeDirty(5_000L).timedOut());
            assertFalse(manager.saveDirtyRegions(5_000L).timedOut(), "持有句柄不得让落盘失败");
        }
        assertFalse(java.nio.file.Files.exists(tmp), "退化路径同样不得留 .tmp 残留");

        ShadowStorageHashes.clear();
        manager.close();
        manager = new ShadowStorageManager(regionDir, pos2 -> nbtPayload.clone(), injected::contains, 1);
        assertTrue(manager.probeHash(pos, 0x72L).match(), "退化路径写出的 hash 头必须可探活");
        assertArrayEquals(nbtPayload, manager.readChunk(pos), "退化路径不得丢柱");
    }

    /** adopt 入参：type126 槽的 type-之后载荷（0x48 头 + ZSTD）。 */
    private static byte[] adoptedPayload(long hash, int marker) throws Exception {
        return HassiumType126Codec.payloadAfterType(
                HassiumType126Codec.encodeSector(adoptedNbt(marker), hash, 1));
    }

    /** 每柱可辨识的 NBT 字节。 */
    private static byte[] adoptedNbt(int marker) {
        return new byte[]{(byte) marker, (byte) (marker + 1), (byte) (marker + 2)};
    }

    private void persistIngest(ChunkPos pos) {
        manager.markContentDirty(pos);
        manager.markLightReady(pos);
        assertFalse(manager.encodeDirty(5_000L).timedOut());
        assertFalse(manager.saveDirtyRegions(5_000L).timedOut());
    }

    @Test
    @DisplayName("deleteRegion 删除整个 .mca 并清 hash / 热度")
    void deleteRegionRemovesFileAndHeat() throws Exception {
        ChunkPos pos = new ChunkPos(1, 2);
        injected.add(ChunkPos.asLong(pos.x, pos.z));
        ShadowStorageHashes.put(pos, 8L);
        persistIngest(pos);
        Path file = RegionCache.regionFile(regionDir, pos.x, pos.z);
        assertTrue(java.nio.file.Files.isRegularFile(file));
        injected.remove(ChunkPos.asLong(pos.x, pos.z));
        manager.deleteRegion(Math.floorDiv(pos.x, 32), Math.floorDiv(pos.z, 32));
        assertFalse(java.nio.file.Files.isRegularFile(file));
        assertNull(ShadowStorageHashes.get(pos));
        assertEquals(0, ShadowRegionHeat.entryCount());
    }

    @Test
    @DisplayName("跨 region 混批 flush：镜像槽位互不串写（wrong location 回归）")
    void mixedRegionFlushKeepsMirroredSlotsSeparate() {
        // (19,4) 与 (-13,4) 同 localIndex(x&31=19)、分属 region (0,0)/(-1,0)。
        // writeBatch 曾固定首块柱的 region image，把后写柱带进前者 .mca 的同槽位，
        // 原版按坐标读取即报 wrong location 且内容错位（冒烟 1.20.1 fabric 114 条实证）。
        ChunkPos positive = new ChunkPos(19, 4);
        ChunkPos negative = new ChunkPos(-13, 4);
        assertEquals(RegionCache.localIndex(positive.x, positive.z),
                RegionCache.localIndex(negative.x, negative.z), "前置：镜像槽位");
        injected.add(ChunkPos.asLong(positive.x, positive.z));
        injected.add(ChunkPos.asLong(negative.x, negative.z));
        ShadowStorageHashes.put(positive, 0x111L);
        ShadowStorageHashes.put(negative, 0x222L);
        manager.markContentDirty(positive);
        manager.markContentDirty(negative);
        manager.markLightReady(positive);
        manager.markLightReady(negative);
        assertFalse(manager.flushDirty(5_000L).timedOut());

        // 清表走映像路径（HashIndex 命中会短路，掩盖文件/映像错位）
        ShadowStorageHashes.remove(DimensionKey.OVERWORLD, positive);
        ShadowStorageHashes.remove(DimensionKey.OVERWORLD, negative);
        assertTrue(manager.probeHash(positive, 0x111L).match(), "正 region 柱保留自己的槽位");
        assertTrue(manager.probeHash(negative, 0x222L).match(), "负 region 柱不得写进镜像文件");

        // 重开校验磁盘（文件同样必须按 region 归位）
        manager.close();
        manager = new ShadowStorageManager(regionDir, pos2 -> nbtPayload.clone(), injected::contains, 1);
        ShadowStorageHashes.remove(DimensionKey.OVERWORLD, positive);
        ShadowStorageHashes.remove(DimensionKey.OVERWORLD, negative);
        assertTrue(manager.probeHash(positive, 0x111L).match(), "落盘后正 region 文件自持");
        assertTrue(manager.probeHash(negative, 0x222L).match(), "落盘后负 region 文件自持");
    }
}
