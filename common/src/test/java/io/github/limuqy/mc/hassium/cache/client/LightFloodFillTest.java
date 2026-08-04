package io.github.limuqy.mc.hassium.cache.client;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * {@link LightFloodFill} 纯算法单测（无 Minecraft 依赖，不引导注册表）。
 * <p>
 * 期望值按官方传播公式手工推导：{@code candidate = level - max(1, lightBlock(target))}，
 * 每跳衰减 max(1, lightBlock)，值 ≤ 1 不再传播；天空注入点及以上整列 = 15，
 * 注入点向除 UP 外方向按同一公式 BFS。
 */
class LightFloodFillTest {

    private static final int W = 48;
    private static final int H = 48;
    private static final int WW = W * W;

    private static int idx(int x, int y, int z) {
        return (y * W + z) * W + x;
    }

    private static int at(byte[] data, int x, int y, int z) {
        return data[idx(x, y, z)] & 0xFF;
    }

    private static byte[] zeros() {
        return new byte[WW * H];
    }

    private static int[] allColumns(int sourceY) {
        int[] sy = new int[W * W];
        java.util.Arrays.fill(sy, sourceY);
        return sy;
    }

    private static LightFloodFill.Occlusion neverOccludes() {
        return (src, dst, dir) -> false;
    }

    @Test
    void singleSourceAttenuatesOnePerHop() {
        byte[] lb = zeros();
        int[] emitters = {(15 << 20) | idx(24, 24, 24)};
        LightFloodFill.Result r = LightFloodFill.solve(W, H, lb, emitters, null, new int[WW * H], neverOccludes());
        assertEquals(15, at(r.blockLight(), 24, 24, 24), "光源格 = emission");
        assertEquals(14, at(r.blockLight(), 25, 24, 24), "1 跳");
        assertEquals(10, at(r.blockLight(), 29, 24, 24), "5 跳");
        assertEquals(0, at(r.blockLight(), 24, 24, 39), "15 跳 = 0（衰减完）");
        assertEquals(0, at(r.blockLight(), 24, 24, 40), "16 跳保持 0");
        assertEquals(0, at(r.blockLight(), 24, 9, 24), "对角方向同衰减");
    }

    @Test
    void opaqueWallBlocksEntirely() {
        byte[] lb = zeros();
        // x=16 整面不透明墙（全 y/z）
        for (int y = 0; y < H; y++) {
            for (int z = 0; z < W; z++) {
                lb[idx(16, y, z)] = 15;
            }
        }
        int[] emitters = {(15 << 20) | idx(15, 24, 24)};
        LightFloodFill.Result r = LightFloodFill.solve(W, H, lb, emitters, null, new int[WW * H], neverOccludes());
        assertEquals(15, at(r.blockLight(), 15, 24, 24), "光源侧");
        assertEquals(14, at(r.blockLight(), 14, 24, 24), "光源侧 1 跳");
        assertEquals(0, at(r.blockLight(), 16, 24, 24), "墙内（candidate = 15-15 = 0）");
        assertEquals(0, at(r.blockLight(), 17, 24, 24), "墙另一侧全 0");
        assertEquals(0, at(r.blockLight(), 30, 24, 24), "墙另一侧深处全 0");
    }

    @Test
    void skyInjectionFillsTopAndAttenuatesDownward() {
        byte[] lb = zeros();
        int[] sy = allColumns(47); // 注入点 = 顶格
        LightFloodFill.Result r = LightFloodFill.solve(W, H, lb, null, sy, new int[WW * H], neverOccludes());
        assertEquals(15, at(r.skyLight(), 24, 47, 24), "注入点 = 15");
        assertEquals(14, at(r.skyLight(), 24, 46, 24), "向下 1 格");
        assertEquals(8, at(r.skyLight(), 24, 40, 24), "向下 7 格");
        assertEquals(0, at(r.skyLight(), 24, 32, 24), "向下 15 格 = 0");
        assertEquals(15, at(r.skyLight(), 3, 47, 41), "整列顶格全部填充");
        assertEquals(14, at(r.skyLight(), 3, 46, 41), "其它列同衰减");
    }

    @Test
    void skyOpaquePlateBlocksEverythingBelow() {
        byte[] lb = zeros();
        // y=46 整层不透明板，注入点在其上方 y=47
        for (int x = 0; x < W; x++) {
            for (int z = 0; z < W; z++) {
                lb[idx(x, 46, z)] = 15;
            }
        }
        int[] sy = allColumns(47);
        LightFloodFill.Result r = LightFloodFill.solve(W, H, lb, null, sy, new int[WW * H], neverOccludes());
        assertEquals(15, at(r.skyLight(), 24, 47, 24), "板上 = 15");
        assertEquals(0, at(r.skyLight(), 24, 46, 24), "板内（15-15 = 0）");
        assertEquals(0, at(r.skyLight(), 24, 40, 24), "板下全 0");
        assertEquals(0, at(r.skyLight(), 24, 0, 24), "板下底全 0");
    }

    @Test
    void semiTransparentBlockSubtractsOpacity() {
        byte[] lb = zeros();
        // 走廊强制光路经过半透明格（上下左右 opacity 15 墙；x∈[20,32]）
        for (int x = 20; x <= 32; x++) {
            lb[idx(x, 23, 24)] = 15;
            lb[idx(x, 25, 24)] = 15;
            lb[idx(x, 24, 23)] = 15;
            lb[idx(x, 24, 25)] = 15;
        }
        lb[idx(25, 24, 24)] = 8; // 光源与目标之间半透明格
        int[] emitters = {(15 << 20) | idx(24, 24, 24)};
        LightFloodFill.Result r = LightFloodFill.solve(W, H, lb, emitters, null, new int[WW * H], neverOccludes());
        assertEquals(7, at(r.blockLight(), 25, 24, 24), "目标格 opacity 8 → 15-8 = 7");
        assertEquals(6, at(r.blockLight(), 26, 24, 24), "其后每跳 -1");
        assertEquals(5, at(r.blockLight(), 27, 24, 24), "再后每跳 -1");
        assertEquals(14, at(r.blockLight(), 23, 24, 24), "光源另一侧（走廊内 1 跳）");
        assertEquals(0, at(r.blockLight(), 24, 23, 24), "墙内（15-15 = 0）");
    }

    @Test
    void multipleSourcesTakeMax() {
        byte[] lb = zeros();
        int[] emitters = {(15 << 20) | idx(20, 24, 24), (10 << 20) | idx(26, 24, 24)};
        LightFloodFill.Result r = LightFloodFill.solve(W, H, lb, emitters, null, new int[WW * H], neverOccludes());
        assertEquals(11, at(r.blockLight(), 24, 24, 24), "左 15-4=11 vs 右 10-2=8 → 11");
        assertEquals(10, at(r.blockLight(), 25, 24, 24), "左 15-5=10 vs 右 10-1=9 → 10");
        assertEquals(10, at(r.blockLight(), 26, 24, 24), "右侧光源自身 10（左侧 15-6=9 不覆盖）");
    }

    @Test
    void missingColumnsGetNoInjection() {
        byte[] lb = zeros();
        int[] sy = allColumns(47);
        // 缺块列（x=0 全列）：不填充不注入（透明，仅可接收邻居溢入）
        for (int z = 0; z < W; z++) {
            sy[z * W] = LightFloodFill.NO_COLUMN;
        }
        LightFloodFill.Result r = LightFloodFill.solve(W, H, lb, null, sy, new int[WW * H], neverOccludes());
        // 关键区别：真实全空气列（NEG_INF）整列填充 15（含底部）；缺块列底部必须为 0（无填充）
        assertEquals(0, at(r.skyLight(), 0, 0, 5), "缺块列底部无填充（溢入光路 ≤15 跳到不了）");
        assertEquals(0, at(r.skyLight(), 0, 1, 5), "缺块列底部无填充");
        assertEquals(15, at(r.skyLight(), 1, 47, 5), "相邻真实列注入点不受影响");
        assertEquals(14, at(r.skyLight(), 1, 46, 5), "相邻真实列正常向下衰减");
    }

    @Test
    void openAirWorldFillsEntireColumnFifteen() {
        byte[] lb = zeros();
        int[] sy = allColumns(LightFloodFill.NEG_INF); // 无边缘 → 整列填充
        LightFloodFill.Result r = LightFloodFill.solve(W, H, lb, null, sy, new int[WW * H], neverOccludes());
        assertEquals(15, at(r.skyLight(), 24, 0, 24), "底部也 15（官方整列填充语义）");
        assertEquals(15, at(r.skyLight(), 24, 47, 24), "顶部 15");
        assertEquals(15, at(r.skyLight(), 5, 23, 40), "任意格 15");
    }

    @Test
    void shapeOcclusionBlocksPropagation() {
        byte[] lb = zeros();
        int[] emitters = {(15 << 20) | idx(24, 24, 24)};
        // 全格非空形状 + 恒遮挡 → 光只在光源格
        int[] shapeIds = new int[WW * H];
        java.util.Arrays.fill(shapeIds, 1);
        LightFloodFill.Result r = LightFloodFill.solve(W, H, lb, emitters, null, shapeIds, (src, dst, dir) -> true);
        assertEquals(15, at(r.blockLight(), 24, 24, 24), "光源格自身");
        assertEquals(0, at(r.blockLight(), 25, 24, 24), "遮挡阻断传播");
        assertEquals(0, at(r.blockLight(), 24, 25, 24), "遮挡阻断传播（上方）");
    }
}
