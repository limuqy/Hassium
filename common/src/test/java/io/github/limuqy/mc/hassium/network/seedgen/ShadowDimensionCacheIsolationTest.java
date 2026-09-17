package io.github.limuqy.mc.hassium.network.seedgen;

import io.github.limuqy.mc.hassium.shadow.storage.ShadowCacheEviction;
import io.github.limuqy.mc.hassium.shadow.server.ShadowServerRegistry;
import io.github.limuqy.mc.hassium.shadow.server.ShadowSeedServer;
import io.github.limuqy.mc.hassium.shadow.storage.ShadowStorageHashes;
import io.github.limuqy.mc.hassium.utils.DimensionKey;
import net.minecraft.world.level.ChunkPos;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 影子端多维度缓存隔离：
 * <ul>
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
    @DisplayName("dirtyKeys(dimension)：脏位按维度隔离")
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

    @Test
    @DisplayName("维度清单重建判定：仅当出现未装配的自定义维才重建")
    void shouldRebuildForDimensions_onlyWhenCustomMissing() {
        var three = java.util.Set.of(
                DimensionKey.OVERWORLD, DimensionKey.NETHER, DimensionKey.END);
        assertFalse(ShadowServerRegistry.shouldRebuildForDimensions(
                java.util.List.of(DimensionKey.OVERWORLD, DimensionKey.NETHER), three));
        assertFalse(ShadowServerRegistry.shouldRebuildForDimensions(java.util.List.of(), three));
        assertFalse(ShadowServerRegistry.shouldRebuildForDimensions(null, three));
        assertTrue(ShadowServerRegistry.shouldRebuildForDimensions(
                java.util.List.of(DimensionKey.OVERWORLD, "aoa3:abyss"), three));
        assertFalse(ShadowServerRegistry.shouldRebuildForDimensions(
                java.util.List.of("aoa3:abyss"),
                java.util.Set.of(DimensionKey.OVERWORLD, "aoa3:abyss")));
    }
}
