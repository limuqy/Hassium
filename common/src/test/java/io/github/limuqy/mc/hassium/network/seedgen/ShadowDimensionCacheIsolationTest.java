package io.github.limuqy.mc.hassium.network.seedgen;

import io.github.limuqy.mc.hassium.cache.client.ChunkBloomFilter;
import io.github.limuqy.mc.hassium.storage.ShadowStorageHashes;
import io.github.limuqy.mc.hassium.utils.DimensionKey;
import net.minecraft.world.level.ChunkPos;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 影子端多维度缓存隔离（T2）：
 * <ul>
 *   <li>bloom per-dimension：同坐标柱在 overworld/nether/end 三帧布隆互不可见
 *       （{@code buildBloomFilter(dimension)} 的过滤语义由
 *       {@code ChunkBloomFilter.put/mightContain} 的 dimension 段承载）；</li>
 *   <li>hash 表复合键：跨维同坐标互不碰撞、旧无参签名委托 OVERWORLD；</li>
 *   <li>热度索引复合键：跨维访问计数互不串扰。</li>
 * </ul>
 * 不实例化 ShadowSeedServer / ServerLevel（依赖完整 MC 运行时），只测纯逻辑面。
 */
class ShadowDimensionCacheIsolationTest {

#if MC_VER >= MC_1_21_2
    // 同 ShadowCacheEvictionTest：1.21.2+ ChunkPos.<clinit> 触碰 BuiltInRegistries 需先 bootstrap。
    @BeforeAll
    static void bootstrapRegistries() {
        net.minecraft.SharedConstants.setVersion(net.minecraft.DetectedVersion.BUILT_IN);
        net.minecraft.server.Bootstrap.bootStrap();
    }
#endif

    @AfterEach
    void resetState() {
        ShadowStorageHashes.clear();
        ShadowCacheEviction.reset();
    }

    @Test
    @DisplayName("bloom per-dimension：NETHER 帧不含 overworld 同坐标柱")
    void bloomFramesAreDimensionIsolated() {
        int x = 1234;
        int z = -5678;
        String ow = DimensionKey.OVERWORLD;
        String nether = DimensionKey.NETHER;
        String end = DimensionKey.END;

        ChunkBloomFilter netherFrame = ChunkBloomFilter.createDefault();
        netherFrame.put(x, z, nether);

        assertTrue(netherFrame.mightContain(x, z, nether), "本维柱必须命中");
        assertFalse(netherFrame.mightContain(x, z, ow), "nether 帧不得含 overworld 同坐标柱");
        assertFalse(netherFrame.mightContain(x, z, end), "nether 帧不得含 end 同坐标柱");

        // 复合键维度段参与哈希：三帧各自独立构建后互不误报对方柱（假阳性率受容量约束，
        // 单坐标断言确定性成立——不同 dimension 哈希值不同）。
        ChunkBloomFilter owFrame = ChunkBloomFilter.createDefault();
        owFrame.put(x, z, ow);
        assertFalse(owFrame.mightContain(x, z, nether));
        assertTrue(owFrame.mightContain(x, z, ow));
    }

    @Test
    @DisplayName("buildBloomFilter 过滤语义：dimensionOf 复合键解维与 put 维度一致")
    void compositeKeyRoundTripMatchesBloomDimension() {
        // buildBloomFilter(dimension) 内部：injectedChunks/hashKeys(dimension) 的复合键
        // 经 DimensionKey.chunkXOf/chunkZOf 解出裸坐标后按原维度 put。验证 round-trip：
        for (String dim : new String[] {
                DimensionKey.OVERWORLD, DimensionKey.NETHER, DimensionKey.END}) {
            long key = DimensionKey.key(dim, 100, 200);
            // 与裸 ChunkPos 键的坐标语义对应：复合键低 52 位 = x[51..26] | z[25..0]
            // （对称位域；vanilla 裸键为 z 高/x 低 32+32，经 chunkXOf/chunkZOf 解出同值）
            assertEquals(100, DimensionKey.chunkXOf(key));
            assertEquals(200, DimensionKey.chunkZOf(key));
            if (dim.equals(DimensionKey.OVERWORLD)) {
                // overworld id=0：复合键低 52 位 == 对称位域裸键
                long bare = ((100L & 0x3FFFFFFL) << 26) | (200L & 0x3FFFFFFL);
                assertEquals(bare, key & 0xFFFFFFFFFFFFFL);
            }
        }
    }

    @Test
    @DisplayName("hash 表复合键：跨维同坐标互不碰撞；旧签名委托 OVERWORLD")
    void hashTableCompositeKeyIsolation() {
        ChunkPos pos = new ChunkPos(42, -42);
        long owHash = 0xAAAAL;
        long netherHash = 0xBBBBL;

        ShadowStorageHashes.put(DimensionKey.OVERWORLD, pos, owHash);
        ShadowStorageHashes.put(DimensionKey.NETHER, pos, netherHash);

        assertEquals(owHash, ShadowStorageHashes.get(DimensionKey.OVERWORLD, pos));
        assertEquals(netherHash, ShadowStorageHashes.get(DimensionKey.NETHER, pos));

        // 旧无参签名 = OVERWORLD（主世界语义零回归）
        assertEquals(owHash, ShadowStorageHashes.get(pos));

        // hashKeys(dimension) 预过滤：各维只看到自己的键
        assertEquals(1, ShadowStorageHashes.hashKeys(DimensionKey.OVERWORLD).size());
        assertEquals(1, ShadowStorageHashes.hashKeys(DimensionKey.NETHER).size());
        assertTrue(ShadowStorageHashes.hashKeys(DimensionKey.END).isEmpty());

        // remove(dimension, pos) 只摘本维
        ShadowStorageHashes.remove(DimensionKey.NETHER, pos);
        assertNull(ShadowStorageHashes.get(DimensionKey.NETHER, pos));
        assertNotNull(ShadowStorageHashes.get(DimensionKey.OVERWORLD, pos));
    }

    @Test
    @DisplayName("dirtyKeys(dimension)：脏位按维度隔离（saveAll/bloom 预过滤依据）")
    void dirtyKeysAreDimensionScoped() {
        ChunkPos a = new ChunkPos(7, 9);
        ChunkPos b = new ChunkPos(-7, 9);
        ShadowStorageHashes.markContentDirty(DimensionKey.OVERWORLD, a);
        ShadowStorageHashes.markContentDirty(DimensionKey.NETHER, b);

        var owDirty = ShadowStorageHashes.dirtyKeys(DimensionKey.OVERWORLD);
        var netherDirty = ShadowStorageHashes.dirtyKeys(DimensionKey.NETHER);
        assertEquals(1, owDirty.size());
        assertEquals(1, netherDirty.size());
        assertTrue(owDirty.contains(DimensionKey.key(DimensionKey.OVERWORLD, 7, 9)));
        assertTrue(netherDirty.contains(DimensionKey.key(DimensionKey.NETHER, -7, 9)));
        // 旧无参 dirtyKeys() = 全量（复合键），兼容 saveAll 日志语义
        assertEquals(2, ShadowStorageHashes.dirtyKeys().size());
    }

    @Test
    @DisplayName("热度索引按 region 文件 + 维度隔离：跨维互不串扰；旧签名=OVERWORLD")
    void heatIndexIsDimensionScoped() {
        ChunkPos pos = new ChunkPos(15, -63);

        ShadowCacheEviction.recordAccess(DimensionKey.NETHER, pos);
        ShadowCacheEviction.recordAccess(DimensionKey.NETHER, pos);

        assertEquals(2, ShadowCacheEviction.accessCountOf(DimensionKey.NETHER, pos));
        assertEquals(0, ShadowCacheEviction.accessCountOf(DimensionKey.OVERWORLD, pos),
                "overworld 访问计数不得被 nether 记录串扰");

        // 旧无参签名 = OVERWORLD
        ShadowCacheEviction.recordAccess(pos);
        assertEquals(1, ShadowCacheEviction.accessCountOf(pos));
        assertEquals(1, ShadowCacheEviction.accessCountOf(DimensionKey.OVERWORLD, pos));

        // remove(dimension) 只清本维
        ShadowCacheEviction.remove(DimensionKey.NETHER, pos);
        assertEquals(0, ShadowCacheEviction.accessCountOf(DimensionKey.NETHER, pos));
        assertEquals(1, ShadowCacheEviction.accessCountOf(DimensionKey.OVERWORLD, pos));
    }
}
