package io.github.limuqy.mc.promethium.light;

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

    // ---- 分段重算（壳层种子）对照 ----

    /** 从全量解提取一层壳种子：(level<<20)|((layerWorldY-domBaseY)*W+z)*W+x；level>0 才收。 */
    private static int[] shellLayer(byte[] full, int layerWorldY, int domBaseY) {
        int[] seeds = new int[WW];
        int n = 0;
        for (int z = 0; z < W; z++) {
            for (int x = 0; x < W; x++) {
                int level = full[layerWorldY * WW + z * W + x] & 0xFF;
                if (level > 0) {
                    seeds[n++] = (level << 20) | ((layerWorldY - domBaseY) * W + z) * W + x;
                }
            }
        }
        return java.util.Arrays.copyOf(seeds, n);
    }

    private static int[] concat(int[] a, int[] b) {
        int[] r = java.util.Arrays.copyOf(a, a.length + b.length);
        System.arraycopy(b, 0, r, a.length, b.length);
        return r;
    }

    /** 随机域（固定 seed 42）：lightBlock 每格 0–15、6 个 emission=8 光源、sourceY 随机 NEG_INF 或 [0,96)。 */
    private static void randomWorld(java.util.Random rnd, int fullH,
                                    byte[] lb, int[] shapeIds, int[] emitters, int[] sourceY) {
        int fullSize = WW * fullH;
        for (int i = 0; i < fullSize; i++) {
            lb[i] = (byte) rnd.nextInt(16);
            shapeIds[i] = rnd.nextInt(2);
        }
        for (int i = 0; i < emitters.length; i++) {
            emitters[i] = (8 << 20) | rnd.nextInt(fullSize);
        }
        for (int c = 0; c < WW; c++) {
            sourceY[c] = rnd.nextBoolean() ? LightFloodFill.NEG_INF : rnd.nextInt(fullH);
        }
    }

    /** 子域逐格对比断言：分段解（域内 y = 世界 y - domBase）与全量解在 D=[domMin,domMax) 相等。 */
    private static void assertSubdomainEquals(byte[] full, byte[] seg, int domBase, int domMin, int domMax, String layer) {
        for (int y = domMin; y < domMax; y++) {
            int ly = y - domBase;
            for (int z = 0; z < W; z++) {
                for (int x = 0; x < W; x++) {
                    int fullIdx = y * WW + z * W + x;
                    int segIdx = ly * WW + z * W + x;
                    assertEquals(full[fullIdx] & 0xFF, seg[segIdx] & 0xFF,
                            layer + " mismatch at (" + x + "," + y + "," + z + ")");
                }
            }
        }
    }

    /** 分段 solve ≡ 全量 solve：H=96 随机域，D=[16,64)，双壳（y=15/y=64 层值做种子）。 */
    @Test
    void segmentedSolveMatchesFullSolve() {
        java.util.Random rnd = new java.util.Random(42L);
        int fullH = 96;
        int fullSize = WW * fullH;
        byte[] lb = new byte[fullSize];
        int[] shapeIds = new int[fullSize];
        int[] emitters = new int[6];
        int[] sourceY = new int[WW];
        randomWorld(rnd, fullH, lb, shapeIds, emitters, sourceY);

        LightFloodFill.Occlusion occ = neverOccludes();
        byte[] fullSky = LightFloodFill.solveSky(W, fullH, lb, sourceY, shapeIds, occ, null, 0, 0);
        byte[] fullBlock = LightFloodFill.solveBlock(W, fullH, lb, emitters, shapeIds, occ, null, 0, 0);

        int domBase = 15; // 底壳世界 y；域内 y = 世界 y - 15
        int domMin = 16, domMax = 64;
        int segH = domMax - domMin + 2; // D 48 + 底壳 + 顶壳
        int segSize = WW * segH;
        byte[] segLb = new byte[segSize];
        int[] segShape = new int[segSize];
        int[] segSy = new int[WW];
        int[] segEmitters = new int[emitters.length];
        int nEmit = 0;
        for (int c = 0; c < WW; c++) {
            int sy = sourceY[c];
            segSy[c] = (sy == LightFloodFill.NEG_INF || sy == LightFloodFill.NO_COLUMN) ? sy : sy - domBase;
        }
        for (int ly = 0; ly < segH; ly++) {
            System.arraycopy(lb, (domBase + ly) * WW, segLb, ly * WW, WW);
            System.arraycopy(shapeIds, (domBase + ly) * WW, segShape, ly * WW, WW);
        }
        for (int e : emitters) {
            int cell = e & 0xFFFFF;
            int y = cell / WW;
            if (y < domMin || y >= domMax) {
                continue; // 壳层内发射源已含在壳层光值里
            }
            int z = (cell / W) % W;
            int x = cell % W;
            segEmitters[nEmit++] = (e & ~0xFFFFF) | ((y - domBase) * W + z) * W + x;
        }
        int[] skyShell = concat(shellLayer(fullSky, domBase, domBase), shellLayer(fullSky, domMax, domBase));
        int[] blockShell = concat(shellLayer(fullBlock, domBase, domBase), shellLayer(fullBlock, domMax, domBase));

        byte[] segSky = LightFloodFill.solveSky(W, segH, segLb, segSy, segShape, occ, skyShell, 1, 1);
        byte[] segBlock = LightFloodFill.solveBlock(W, segH, segLb,
                java.util.Arrays.copyOf(segEmitters, nEmit), segShape, occ, blockShell, 1, 1);
        assertSubdomainEquals(fullSky, segSky, domBase, domMin, domMax, "sky");
        assertSubdomainEquals(fullBlock, segBlock, domBase, domMin, domMax, "block");
    }

    /** 底边贴世界底（无底壳）：D=[0,48)，只有顶壳（y=48 层），shellBottomLayers=0。 */
    @Test
    void segmentedAtBottomEdgeWithoutBottomShell() {
        java.util.Random rnd = new java.util.Random(7L);
        int fullH = 96;
        int fullSize = WW * fullH;
        byte[] lb = new byte[fullSize];
        int[] shapeIds = new int[fullSize];
        int[] emitters = new int[6];
        int[] sourceY = new int[WW];
        randomWorld(rnd, fullH, lb, shapeIds, emitters, sourceY);

        LightFloodFill.Occlusion occ = neverOccludes();
        byte[] fullSky = LightFloodFill.solveSky(W, fullH, lb, sourceY, shapeIds, occ, null, 0, 0);
        byte[] fullBlock = LightFloodFill.solveBlock(W, fullH, lb, emitters, shapeIds, occ, null, 0, 0);

        int domBase = 0; // 世界底即域底：无底壳
        int domMin = 0, domMax = 48;
        int segH = domMax - domMin + 1; // D 48 + 顶壳
        int segSize = WW * segH;
        byte[] segLb = new byte[segSize];
        int[] segShape = new int[segSize];
        int[] segEmitters = new int[emitters.length];
        int nEmit = 0;
        for (int ly = 0; ly < segH; ly++) {
            System.arraycopy(lb, ly * WW, segLb, ly * WW, WW);
            System.arraycopy(shapeIds, ly * WW, segShape, ly * WW, WW);
        }
        for (int e : emitters) {
            int cell = e & 0xFFFFF;
            int y = cell / WW;
            if (y < domMin || y >= domMax) {
                continue;
            }
            int z = (cell / W) % W;
            int x = cell % W;
            segEmitters[nEmit++] = (e & ~0xFFFFF) | (y * W + z) * W + x;
        }
        int[] skyShell = shellLayer(fullSky, domMax, domBase);
        int[] blockShell = shellLayer(fullBlock, domMax, domBase);

        byte[] segSky = LightFloodFill.solveSky(W, segH, segLb, sourceY, segShape, occ, skyShell, 0, 1);
        byte[] segBlock = LightFloodFill.solveBlock(W, segH, segLb,
                java.util.Arrays.copyOf(segEmitters, nEmit), segShape, occ, blockShell, 0, 1);
        assertSubdomainEquals(fullSky, segSky, domBase, domMin, domMax, "sky");
        assertSubdomainEquals(fullBlock, segBlock, domBase, domMin, domMax, "block");
    }

    /** 注入点恰在顶壳层（sy = 世界 64）：壳层预置值不被列注入覆盖，子域与全量一致。 */
    @Test
    void segmentedSkyShellNotOverwrittenByColumnInjection() {
        java.util.Random rnd = new java.util.Random(99L);
        int fullH = 96;
        int fullSize = WW * fullH;
        byte[] lb = new byte[fullSize];
        int[] shapeIds = new int[fullSize];
        int[] emitters = new int[6];
        int[] sourceY = new int[WW];
        randomWorld(rnd, fullH, lb, shapeIds, emitters, sourceY);
        int colX = 24, colZ = 31;
        sourceY[colZ * W + colX] = 64; // 注入点位于顶壳层（世界 y=64 = 域内 y=49）

        LightFloodFill.Occlusion occ = neverOccludes();
        byte[] fullSky = LightFloodFill.solveSky(W, fullH, lb, sourceY, shapeIds, occ, null, 0, 0);
        assertEquals(15, fullSky[64 * WW + colZ * W + colX] & 0xFF, "全量解壳层格 = 15（列注入覆盖）");

        int domBase = 15;
        int domMin = 16, domMax = 64;
        int segH = domMax - domMin + 2;
        int segSize = WW * segH;
        byte[] segLb = new byte[segSize];
        int[] segShape = new int[segSize];
        int[] segSy = new int[WW];
        for (int c = 0; c < WW; c++) {
            int sy = sourceY[c];
            segSy[c] = (sy == LightFloodFill.NEG_INF || sy == LightFloodFill.NO_COLUMN) ? sy : sy - domBase;
        }
        for (int ly = 0; ly < segH; ly++) {
            System.arraycopy(lb, (domBase + ly) * WW, segLb, ly * WW, WW);
            System.arraycopy(shapeIds, (domBase + ly) * WW, segShape, ly * WW, WW);
        }
        int[] skyShell = concat(shellLayer(fullSky, domBase, domBase), shellLayer(fullSky, domMax, domBase));
        byte[] segSky = LightFloodFill.solveSky(W, segH, segLb, segSy, segShape, occ, skyShell, 1, 1);
        // 壳层预置值保留（列注入/种子循环跳过壳层格，未被覆盖或改写）
        assertEquals(15, segSky[49 * WW + colZ * W + colX] & 0xFF, "顶壳格值 = 预置值");
        assertSubdomainEquals(fullSky, segSky, domBase, domMin, domMax, "sky");
    }
}
