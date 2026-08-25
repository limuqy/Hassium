package io.github.limuqy.mc.hassium.storage;

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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
        ShadowStorageHashes.clear();
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
    @DisplayName("markDirty 不分配 NBT；光收敛才入队 serialize")
    void markDirtyDoesNotSerializeUntilLightReady() {
        ChunkPos pos = new ChunkPos(1, 1);
        injected.add(ChunkPos.asLong(pos.x, pos.z));
        ShadowStorageHashes.put(pos, 7L);
        manager.markContentDirty(pos);
        assertEquals(0, serializeCalls.get());
        assertTrue(ShadowStorageHashes.isDirty(pos));
        assertEquals(0, manager.flush(5_000L).written(), "首次注入未收敛，flush 不刷");
        assertEquals(0, serializeCalls.get());
        manager.markLightReady(pos);
        assertEquals(1, serializeCalls.get());
        assertFalse(manager.drain(5_000L).timedOut());
        assertFalse(ShadowStorageHashes.isDirty(pos));
        assertTrue(ShadowStorageHashes.isPersisted(
                DimensionKey.key(DimensionKey.OVERWORLD, pos.x, pos.z)));
    }

    @Test
    @DisplayName("定时 flush 只刷 mutation；退出 flushRemaining 刷首次注入残留")
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
        assertTrue(ShadowStorageHashes.isDirty(ingest), "首次注入仍等光");
        assertFalse(ShadowStorageHashes.isDirty(mutated));
        assertEquals(1, manager.flushRemaining(5_000L).written(), "退出 flushRemaining 应刷首次注入残留");
        assertFalse(ShadowStorageHashes.isDirty(ingest));
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
        assertFalse(manager.drain(5_000L).timedOut());
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
        assertFalse(manager.drain(5_000L).timedOut());
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

    private void persistIngest(ChunkPos pos) {
        manager.markContentDirty(pos);
        manager.markLightReady(pos);
        assertFalse(manager.drain(5_000L).timedOut());
    }
}
