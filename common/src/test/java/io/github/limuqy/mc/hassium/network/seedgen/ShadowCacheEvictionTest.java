package io.github.limuqy.mc.hassium.network.seedgen;

import net.minecraft.world.level.ChunkPos;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link ShadowCacheEviction} 纯逻辑：热度公式迁移（旧 CacheEvictionManager）、
 * 容量决策、heat.idx 持久化 round-trip 与损坏容错。
 * 清理执行链（region 扫描/删除）依赖 Minecraft ServerLevel，走冒烟验证。
 */
class ShadowCacheEvictionTest {

    @TempDir
    Path tempDir;

#if MC_VER >= MC_1_21_2
    // 同 ChunkDistancePriorityTest：1.21.2+ ChunkPos.<clinit> 触碰 BuiltInRegistries 需先 bootstrap。
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
        // 刚访问（age=0, count=1）：0.7 * 1/(1+0) + 0.3 * 1/(1+1) = 0.85
        assertEquals(0.85, ShadowCacheEviction.hotScore(1, 0, 0, recencyWeight, frequencyWeight), 1e-9);
        // 5 分钟前（6000 ticks）且从未访问（count=0）：
        // 0.7 * 1/(1+6000) + 0.3 * 1 = 0.3001166…（默认阈值 0.3 下可淘汰）
        long fiveMinAgo = System.currentTimeMillis() - 6000L * 50;
        double score = ShadowCacheEviction.hotScore(0, fiveMinAgo, System.currentTimeMillis(),
                recencyWeight, frequencyWeight);
        assertEquals(0.3 + 0.7 / 6001.0, score, 1e-9);
        assertTrue(score > 0.3, "5 分钟无访问应为刚过阈值（可淘汰）");
        // 越旧越冷（单调递减）
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
    @DisplayName("heat.idx round-trip：recordAccess → save → reset → load 恢复条目")
    void heatIndexPersistsAcrossSessions() throws Exception {
        ChunkPos a = new ChunkPos(3, -7);
        ChunkPos b = new ChunkPos(-100, 200);
        ShadowCacheEviction.recordAccess(a);
        ShadowCacheEviction.recordAccess(b);
        ShadowCacheEviction.recordAccess(b); // count=2

        ShadowCacheEviction.save(tempDir);
        assertTrue(Files.exists(tempDir.resolve("heat.idx")));

        ShadowCacheEviction.reset();
        assertEquals(0, ShadowCacheEviction.entryCount(), "reset 后内存索引应为空");

        ShadowCacheEviction.load(tempDir);
        assertEquals(2, ShadowCacheEviction.entryCount(), "跨会话恢复 2 条");
        // 重新访问 b：count 3、lastAccess 刷新
        ShadowCacheEviction.recordAccess(b);
        assertEquals(3, ShadowCacheEviction.accessCountOf(b), "计数跨会话累计");
        assertEquals(1, ShadowCacheEviction.accessCountOf(a));

        // 删除区块：条目移除
        ShadowCacheEviction.remove(a);
        assertEquals(1, ShadowCacheEviction.entryCount());
    }

    @Test
    @DisplayName("损坏 heat.idx 容错：加载为空索引且不抛异常")
    void corruptHeatIndexResets() throws Exception {
        Files.write(tempDir.resolve("heat.idx"), new byte[] {1, 2, 3, 4, 5});
        ShadowCacheEviction.load(tempDir); // 魔数不符 → 空索引
        assertEquals(0, ShadowCacheEviction.entryCount());

        // 截断文件（合法魔数版本但数据不完整）→ 同样容错
        ShadowCacheEviction.recordAccess(new ChunkPos(1, 1));
        ShadowCacheEviction.save(tempDir);
        byte[] full = Files.readAllBytes(tempDir.resolve("heat.idx"));
        Files.write(tempDir.resolve("heat.idx"),
                java.util.Arrays.copyOf(full, full.length - 8));
        ShadowCacheEviction.load(tempDir);
        assertEquals(0, ShadowCacheEviction.entryCount());
    }
}
