package io.github.limuqy.mc.hassium.network.seedgen;

import net.minecraft.world.level.ChunkPos;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * SeedGenExecutor SeedRef 维度路由纯逻辑测试（REQ 明细5）。
 * <p>
 * 边界：不引导 Minecraft、不触发 worldgen。覆盖维度上下文表的
 * 登记 → 解析 → 覆盖 → 清理全生命周期（{@code handleSeedRef} 捕获 /
 * {@code generateOne} 消费 / 出队清理的同源语义），worldgen 路由本体
 * （server.level(dim) 分派）留联机验收。
 */
class SeedGenDimensionRoutingTest {

    private static final String OVERWORLD = "minecraft:overworld";
    private static final String NETHER = "minecraft:the_nether";
    private static final String END = "minecraft:the_end";

    @AfterEach
    void tearDown() {
        SeedGenExecutor.clearDimensionsForTest();
    }

    @Test
    @DisplayName("SeedRef 接收时捕获的维度随条目解析（同坐标跨维互不串扰）")
    void dimensionContextRoutesByCapturedDimension() {
        ChunkPos pos = new ChunkPos(12, -34);
        assertNull(SeedGenExecutor.dimensionOfForTest(pos), "未登记条目不应有维度上下文");

        // 同一柱先后在两个维度接收 SeedRef：后者覆盖前者（新 SeedRef 覆盖旧语义一致）
        SeedGenExecutor.putDimensionForTest(pos, OVERWORLD);
        assertEquals(OVERWORLD, SeedGenExecutor.dimensionOfForTest(pos));
        SeedGenExecutor.putDimensionForTest(pos, NETHER);
        assertEquals(NETHER, SeedGenExecutor.dimensionOfForTest(pos));

        // 不同坐标各自独立
        ChunkPos other = new ChunkPos(-7, 5);
        SeedGenExecutor.putDimensionForTest(other, END);
        assertEquals(END, SeedGenExecutor.dimensionOfForTest(other));
        assertEquals(NETHER, SeedGenExecutor.dimensionOfForTest(pos));
    }

    @Test
    @DisplayName("clearDimensionsForTest 清空全部上下文（onDisconnect 同源语义）")
    void clearDropsAllDimensionContext() {
        SeedGenExecutor.putDimensionForTest(new ChunkPos(1, 1), NETHER);
        SeedGenExecutor.putDimensionForTest(new ChunkPos(2, 2), END);
        SeedGenExecutor.clearDimensionsForTest();
        assertNull(SeedGenExecutor.dimensionOfForTest(new ChunkPos(1, 1)));
        assertNull(SeedGenExecutor.dimensionOfForTest(new ChunkPos(2, 2)));
    }
}
