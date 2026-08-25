package io.github.limuqy.mc.hassium.network.seedgen;

import io.github.limuqy.mc.hassium.storage.ShadowRegionHeat;
import net.minecraft.world.level.ChunkPos;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link ShadowCacheEviction} 纯逻辑：热度公式、容量决策、
 * region 文件级 heat.idx 持久化（解析文件内容）与目录扫描（不拆 Anvil 头）。
 */
class ShadowCacheEvictionTest {

    @TempDir
    Path tempDir;

#if MC_VER >= MC_1_21_2
    @BeforeAll
    static void bootstrapRegistries() {
        net.minecraft.SharedConstants.setVersion(net.minecraft.DetectedVersion.BUILT_IN);
        net.minecraft.server.Bootstrap.bootStrap();
    }
#endif

    @AfterEach
    void resetHeat() {
        ShadowCacheEviction.reset();
    }

    @Test
    @DisplayName("热度公式迁移：权重/年龄/次数按旧公式数值")
    void hotScoreMatchesLegacyFormula() {
        double recencyWeight = 0.7;
        double frequencyWeight = 0.3;
        assertEquals(0.85, ShadowCacheEviction.hotScore(1, 0, 0, recencyWeight, frequencyWeight), 1e-9);
        long fiveMinAgo = System.currentTimeMillis() - 6000L * 50;
        double score = ShadowCacheEviction.hotScore(0, fiveMinAgo, System.currentTimeMillis(),
                recencyWeight, frequencyWeight);
        assertEquals(0.3 + 0.7 / 6001.0, score, 1e-9);
        assertTrue(score > 0.3, "5 分钟无访问应为刚过阈值（可淘汰）");
        long now = System.currentTimeMillis();
        double fresh = ShadowCacheEviction.hotScore(0, now - 1000, now, recencyWeight, frequencyWeight);
        double stale = ShadowCacheEviction.hotScore(0, now - 10_000_000, now, recencyWeight, frequencyWeight);
        assertTrue(fresh > stale);
    }

    @Test
    @DisplayName("容量决策：超上限或超目标 90% 触发，目标 90% 内不触发")
    void shouldCleanupThresholds() {
        long max = 4096L * 1024 * 1024;
        long target = (long) (max * 0.8);
        long preTrigger = (long) (target * 0.9) - 1;
        assertFalse(ShadowCacheEviction.shouldCleanup(preTrigger, max, target), "目标 90% 内不清理");
        assertTrue(ShadowCacheEviction.shouldCleanup((long) (target * 0.9) + 1, max, target), "超目标 90% 提前触发");
        assertTrue(ShadowCacheEviction.shouldCleanup(max + 1, max, target), "超上限必触发");
        assertFalse(ShadowCacheEviction.shouldCleanup(10, 0, 0), "上限 0 = 禁用");
    }

    @Test
    @DisplayName("同 region 的柱合并为一条热度；跨 region 分开")
    void heatIsPerRegionFileNotPerChunk() {
        ChunkPos a = new ChunkPos(0, 0);
        ChunkPos sameRegion = new ChunkPos(31, 31);
        ChunkPos otherRegion = new ChunkPos(32, 0);
        ShadowCacheEviction.recordAccess(a);
        ShadowCacheEviction.recordAccess(sameRegion);
        ShadowCacheEviction.recordAccess(otherRegion);

        assertEquals(2, ShadowCacheEviction.entryCount(), "两个 region 文件 → 2 条");
        assertEquals(2, ShadowCacheEviction.accessCountOf(a));
        assertEquals(2, ShadowCacheEviction.accessCountOf(sameRegion));
        assertEquals(1, ShadowCacheEviction.accessCountOf(otherRegion));
    }

    @Test
    @DisplayName("heat.idx round-trip：解析文件内容恢复 region 条目与 size")
    void heatIndexPersistsAcrossSessions() throws Exception {
        ChunkPos a = new ChunkPos(3, -7);
        ChunkPos b = new ChunkPos(-100, 200);
        ShadowCacheEviction.recordAccess(a);
        ShadowCacheEviction.recordAccess(b);
        ShadowCacheEviction.recordAccess(b);
        ShadowRegionHeat.updateRegionSize(io.github.limuqy.mc.hassium.utils.DimensionKey.OVERWORLD,
                Math.floorDiv(b.x, 32), Math.floorDiv(b.z, 32), 12_345L);

        ShadowCacheEviction.save(tempDir);
        assertTrue(Files.exists(tempDir.resolve("heat.idx")));

        ShadowCacheEviction.reset();
        assertEquals(0, ShadowCacheEviction.entryCount(), "reset 后内存索引应为空");

        ShadowCacheEviction.load(tempDir);
        assertEquals(2, ShadowCacheEviction.entryCount(), "跨会话恢复 2 个 region");
        ShadowCacheEviction.recordAccess(b);
        assertEquals(3, ShadowCacheEviction.accessCountOf(b), "计数跨会话累计");
        assertEquals(1, ShadowCacheEviction.accessCountOf(a));
        ShadowRegionHeat.HotEntry hotB = ShadowRegionHeat.get(
                io.github.limuqy.mc.hassium.utils.DimensionKey.OVERWORLD,
                Math.floorDiv(b.x, 32), Math.floorDiv(b.z, 32));
        assertNotNull(hotB);
        assertEquals(12_345L, hotB.sizeBytes(), "解析 heat.idx 应读出文件大小");

        ShadowCacheEviction.remove(a);
        assertEquals(1, ShadowCacheEviction.entryCount());
    }

    @Test
    @DisplayName("损坏 / 旧版 heat.idx 容错：加载为空索引且不抛异常")
    void corruptHeatIndexResets() throws Exception {
        Files.write(tempDir.resolve("heat.idx"), new byte[] {1, 2, 3, 4, 5});
        ShadowCacheEviction.load(tempDir);
        assertEquals(0, ShadowCacheEviction.entryCount());

        // v1 魔数 HSH1：版本不符 → 空
        java.io.DataOutputStream v1 = new java.io.DataOutputStream(
                Files.newOutputStream(tempDir.resolve("heat.idx")));
        v1.writeInt(0x48534831);
        v1.writeInt(1);
        v1.writeInt(0);
        v1.close();
        ShadowCacheEviction.load(tempDir);
        assertEquals(0, ShadowCacheEviction.entryCount());

        ShadowCacheEviction.recordAccess(new ChunkPos(1, 1));
        ShadowCacheEviction.save(tempDir);
        byte[] full = Files.readAllBytes(tempDir.resolve("heat.idx"));
        Files.write(tempDir.resolve("heat.idx"),
                java.util.Arrays.copyOf(full, full.length - 8));
        ShadowCacheEviction.load(tempDir);
        assertEquals(0, ShadowCacheEviction.entryCount());
    }

    @Test
    @DisplayName("扫描只认文件名与 Files.size，不要求合法 Anvil 头")
    void collectRegionFilesDoesNotParseAnvilHeader() throws Exception {
        Files.write(tempDir.resolve("r.0.0.mca"), new byte[12_345]);
        Files.write(tempDir.resolve("r.1.-2.mca"), new byte[8_192]);
        Files.write(tempDir.resolve("not-a-region.dat"), new byte[100]);

        List<ShadowRegionHeat.RegionFileStat> stats =
                ShadowRegionHeat.collectRegionFiles(tempDir, "minecraft:overworld");
        assertEquals(2, stats.size());
        long total = stats.stream().mapToLong(ShadowRegionHeat.RegionFileStat::sizeBytes).sum();
        assertEquals(12_345L + 8_192L, total);
    }
}
