package io.github.limuqy.mc.promethium.light;

import it.unimi.dsi.fastutil.longs.LongArrayFIFOQueue;

/**
 * 纯 Java 光照泛洪重算（方案 D 后台重算核心；无任何 Minecraft 依赖，可脱离注册表单测）。
 * <p>
 * 传播公式逐行复刻官方 {@code BlockLightEngine/SkyLightEngine.propagateIncrease}
 * （1.20.1 与 1.21.11 反编译逐行一致）：
 * <pre>
 *   propagateIncrease(src, level, dir):
 *     target = src + dir
 *     if stored(target) < level - 1:                       # 门槛
 *       opacity = max(1, lightBlock(target))
 *       candidate = level - opacity                        # 直接减目标格遮挡（含那 1 跳）
 *       if candidate > stored(target) &amp;&amp; !shapeOccludes(src, target, dir):
 *         stored(target) = candidate
 *         if candidate > 1: enqueue(target, candidate, allDirs - -dir)   # 值 ≤ 1 不再传播
 * </pre>
 * 其中形状遮挡 {@code shapeOccludes} 只对 {@code useShapeForLightOcclusion=true} 的方块
 * （台阶/楼梯/栅栏/活板门等）起作用；空形状不遮挡。
 * <p>
 * 天空光按官方 {@code SkyLightEngine} 语义：每列注入 y = 首个「下格 lightBlock != 0
 * 或形状遮挡」边缘上方的格；注入点及以上整列 = 15；种子 = 注入点（方向含 DOWN，即
 * {@code increaseSkySourceInDirections} 的 down 标志）+ 所有低于 4 邻列注入点的已填充格
 * （仅水平方向掩码，向注入点更高的邻列扩散）。官方 {@code propagateFromEmptySections}
 * 为空 section 加速优化，只影响工作量不影响结果，不实现。
 * <p>
 * 域内线性索引：{@code (y * W + z) * W + x}（W = 域宽 = 3 柱 = 48）。
 * 方向序数 = {@code net.minecraft.core.Direction} 声明序（DOWN=0, UP=1, NORTH=2, SOUTH=3,
 * WEST=4, EAST=5），跨版本稳定；由调用方负责映射到实际 VoxelShape。
 */
public final class LightFloodFill {

    /** 无天空注入的哨兵（等价官方 ChunkSkyLightSources.extendSourcesBelowWorld → NEGATIVE_INFINITY；真实列整列填充 15）。 */
    public static final int NEG_INF = Integer.MIN_VALUE;

    /** 缺块占位列（未加载邻居）：不填充、不注入（官方 BFS 对缺块 storingLightForSection=false 直接跳过）。 */
    public static final int NO_COLUMN = NEG_INF + 1;
    public static final int MAX_LEVEL = 15;

    // Direction 声明序（与 net.minecraft.core.Direction.values() 一致，跨版本稳定）
    public static final int DOWN = 0;
    public static final int UP = 1;
    public static final int NORTH = 2;
    public static final int SOUTH = 3;
    public static final int WEST = 4;
    public static final int EAST = 5;

    /** 全部 6 方向掩码（增量传播种子默认方向；排除回源方向由 BFS 自身处理）。 */
    public static final int ALL_DIRS = 0b111111;
    private static final int[] OPPOSITE = {UP, DOWN, SOUTH, NORTH, EAST, WEST};

    /** 形状遮挡判定（src 面 dir 与 dst 面 -dir）；两个 shapeId 均非 0 时才会被调用。 */
    @FunctionalInterface
    public interface Occlusion {
        boolean occludes(int srcShape, int dstShape, int dir);
    }

    /** 域大小结果：每格一字节（0–15），布局同输入（y*W+z)*W+x。 */
    public record Result(byte[] blockLight, byte[] skyLight) {
    }

    private LightFloodFill() {
    }

    /**
     * 全量重算域内 block/sky 光照（包装：分别调用 {@link #solveBlock} 与 {@link #solveSky}）。
     * block 与 sky 相互独立（不同数组、不同种子、队列无交互），分离计算与合并计算等价，
     * 分离后可独立计时与独立调度（sky 优先落地，区块先亮，观感优先）。
     *
     * @param width     域宽/深（格数；= 3 柱 = 48）
     * @param height    域高（世界格数）
     * @param lightBlock 每格原始 lightBlock（0–15，域内索引）
     * @param emitters  发光源，packed {@code (emission << 20) | index}（emission 0–15）
     * @param sourceY   每列天空注入 y（{@code z*width+x} 索引；{@link #NEG_INF} = 无注入/整列填充 15）
     * @param shapeIds  每格形状 id（0 = 空形状，不参与遮挡；非 0 供 {@code occlusion} 查表）
     * @param occlusion 形状遮挡判定（恒不挡的实现合法）
     */
    public static Result solve(int width, int height,
                               byte[] lightBlock,
                               int[] emitters,
                               int[] sourceY,
                               int[] shapeIds,
                               Occlusion occlusion) {
        return new Result(
                solveBlock(width, height, lightBlock, emitters, shapeIds, occlusion, null, 0, 0),
                solveSky(width, height, lightBlock, sourceY, shapeIds, occlusion, null, 0, 0));
    }

    /**
     * 仅重算方块光：发光源种子 BFS（官方 increaseLightFromEmission 语义）。
     * 无光源（emitters 空/仅自然地形）时只做数组分配，成本近似为零。
     *
     * @param shellSeeds       壳层种子，packed {@code (level << 20) | index}（域内线性索引；null = 无壳）。
     *                         壳格值 = 全量解（不动点），BFS 传播 candidate ≤ stored 恒成立，
     *                         壳格不会被域内光改写，无需额外保护。
     * @param shellBottomLayers 底部壳层格数（0/1）：solveBlock 无列注入，不参与任何判断，仅对称签名。
     * @param shellTopLayers   顶部壳层格数（0/1）：同上。
     * @return 域内 block 光数组（布局 {@code (y*W+z)*W+x}，默认全 0）
     */
    public static byte[] solveBlock(int width, int height,
                                    byte[] lightBlock,
                                    int[] emitters,
                                    int[] shapeIds,
                                    Occlusion occlusion,
                                    int[] shellSeeds, int shellBottomLayers, int shellTopLayers) {
        int ww = width * width;
        byte[] block = new byte[ww * height];
        LongArrayFIFOQueue queue = new LongArrayFIFOQueue();
        if (emitters != null) {
            for (int e : emitters) {
                int idx = e & 0xFFFFF;
                int emission = e >>> 20;
                if (emission <= 0 || emission > MAX_LEVEL) {
                    continue;
                }
                if ((block[idx] & 0xFF) < emission) {
                    block[idx] = (byte) emission;
                }
                queue.enqueue(entry(idx, emission, ALL_DIRS, shapeIds[idx] == 0, 0));
            }
        }
        if (shellSeeds != null) {
            for (int e : shellSeeds) {
                int idx = e & 0xFFFFF;
                int level = e >>> 20;
                if (level <= 0 || level > MAX_LEVEL) {
                    continue;
                }
                if ((block[idx] & 0xFF) < level) {
                    block[idx] = (byte) level;
                }
                queue.enqueue(entry(idx, level, ALL_DIRS, shapeIds[idx] == 0, 0));
            }
        }
        bfs(width, height, block, lightBlock, shapeIds, occlusion, queue, 0);
        return block;
    }

    /**
     * 仅重算天空光：列注入填充 + 天空种子 BFS（官方 propagateLightSources /
     * increaseSkySourceInDirections 语义；不含空 section 加速，只影响工作量不影响结果）。
     *
     * @param shellSeeds       壳层种子，packed {@code (level << 20) | index}（域内线性索引；null = 无壳）。
     *                         壳格值 = 全量解（不动点），BFS 传播 candidate ≤ stored 恒成立，
     *                         壳格不会被域内光改写，无需额外保护。
     * @param shellBottomLayers 底部壳层格数（0/1）：列注入填充与种子循环跳过这些层
     *                         （壳层格由预置值承载，列注入强制 15 会覆盖预置值）。
     * @param shellTopLayers   顶部壳层格数（0/1）：同上。
     * @return 域内 sky 光数组（布局 {@code (y*W+z)*W+x}）
     */
    public static byte[] solveSky(int width, int height,
                                  byte[] lightBlock,
                                  int[] sourceY,
                                  int[] shapeIds,
                                  Occlusion occlusion,
                                  int[] shellSeeds, int shellBottomLayers, int shellTopLayers) {
        int ww = width * width;
        byte[] sky = new byte[ww * height];
        LongArrayFIFOQueue queue = new LongArrayFIFOQueue();
        if (sourceY != null) {
            // 列注入填充（官方 propagateLightSources 的 $$13.set(..., 15) 循环）
            for (int x = 0; x < width; x++) {
                for (int z = 0; z < width; z++) {
                    int sy = sourceY[z * width + x];
                    if (sy == NO_COLUMN || sy >= height) {
                        continue; // 缺块占位 / 注入点在世界顶之上（最高格即边缘）：无格可填
                    }
                    int colBase = z * width + x;
                    int y0 = Math.max(0, sy); // NEG_INF → 整列填充
                    for (int y = y0; y < height; y++) {
                        if (y < shellBottomLayers || y >= height - shellTopLayers) {
                            continue; // 壳层格由预置值承载，列注入强制 15 会覆盖预置值
                        }
                        sky[y * ww + colBase] = MAX_LEVEL;
                    }
                }
            }
            // 种子：注入点（y == sy，方向含 DOWN）+ 低于 max(4 邻列注入点) 的已填充格（仅水平）
            for (int x = 0; x < width; x++) {
                for (int z = 0; z < width; z++) {
                    int sy = sourceY[z * width + x];
                    if (sy == NO_COLUMN || sy >= height) {
                        continue;
                    }
                    int y0 = Math.max(0, sy);
                    int maxN = maxNeighborSourceY(sourceY, width, x, z);
                    for (int y = y0; y < height; y++) {
                        if (y < shellBottomLayers || y >= height - shellTopLayers) {
                            continue; // 壳层格种子由 shellSeeds 承载
                        }
                        if (y == sy || y < maxN) {
                            int mask = skySeedMask(sourceY, width, x, z, y, sy);
                            if (mask != 0) {
                                queue.enqueue(entry(y * ww + z * width + x, MAX_LEVEL, mask, false, 1));
                            }
                        }
                    }
                }
            }
        }
        if (shellSeeds != null) {
            for (int e : shellSeeds) {
                int idx = e & 0xFFFFF;
                int level = e >>> 20;
                if (level <= 0 || level > MAX_LEVEL) {
                    continue;
                }
                if ((sky[idx] & 0xFF) < level) {
                    sky[idx] = (byte) level;
                }
                queue.enqueue(entry(idx, level, ALL_DIRS, shapeIds[idx] == 0, 1));
            }
        }
        bfs(width, height, sky, lightBlock, shapeIds, occlusion, queue, 1);
        return sky;
    }

    /**

    /**
     * 单层 BFS 主循环（block/sky 共用传播逻辑；FIFO + candidate > stored 单调写保证收敛）。
     *
     * @param layer 0 = block / 1 = sky（写入 arr 与入队条目，层间无交互）
     */
    private static void bfs(int width, int height, byte[] arr, byte[] lightBlock, int[] shapeIds,
                            Occlusion occlusion, LongArrayFIFOQueue queue, int layer) {
        int ww = width * width;
        while (!queue.isEmpty()) {
            long e = queue.dequeueLong();
            int idx = (int) (e >>> 12);
            int level = (int) (e & 0xF);
            int dirMask = (int) ((e >>> 5) & 0x3F);
            boolean fromEmpty = ((e >>> 4) & 1) != 0;
            if ((arr[idx] & 0xFF) != level) {
                continue; // 过期条目（值已被更高写入）：官方 propagateIncreases 的 stored == level 检查
            }

            int x = idx % width;
            int z = (idx / width) % width;
            int y = idx / ww;
            if ((dirMask & (1 << DOWN)) != 0 && y > 0) {
                spread(idx, idx - ww, level, fromEmpty, DOWN, arr, lightBlock, shapeIds, occlusion, queue, layer);
            }
            if ((dirMask & (1 << UP)) != 0 && y < height - 1) {
                spread(idx, idx + ww, level, fromEmpty, UP, arr, lightBlock, shapeIds, occlusion, queue, layer);
            }
            if ((dirMask & (1 << NORTH)) != 0 && z > 0) {
                spread(idx, idx - width, level, fromEmpty, NORTH, arr, lightBlock, shapeIds, occlusion, queue, layer);
            }
            if ((dirMask & (1 << SOUTH)) != 0 && z < width - 1) {
                spread(idx, idx + width, level, fromEmpty, SOUTH, arr, lightBlock, shapeIds, occlusion, queue, layer);
            }
            if ((dirMask & (1 << WEST)) != 0 && x > 0) {
                spread(idx, idx - 1, level, fromEmpty, WEST, arr, lightBlock, shapeIds, occlusion, queue, layer);
            }
            if ((dirMask & (1 << EAST)) != 0 && x < width - 1) {
                spread(idx, idx + 1, level, fromEmpty, EAST, arr, lightBlock, shapeIds, occlusion, queue, layer);
            }
        }
    }

    /** 官方 propagateIncrease 单方向扩散。 */
    private static void spread(int srcIdx, int targetIdx, int srcLevel, boolean fromEmpty, int dir,
                               byte[] arr, byte[] lightBlock, int[] shapeIds, Occlusion occlusion,
                               LongArrayFIFOQueue queue, int layer) {
        int stored = arr[targetIdx] & 0xFF;
        if (srcLevel - 1 > stored) {
            int candidate = srcLevel - Math.max(1, lightBlock[targetIdx] & 0xFF);
            if (candidate > stored) {
                int srcShape = fromEmpty ? 0 : shapeIds[srcIdx];
                int dstShape = shapeIds[targetIdx];
                if (srcShape == 0 || dstShape == 0 || !occlusion.occludes(srcShape, dstShape, dir)) {
                    arr[targetIdx] = (byte) candidate;
                    if (candidate > 1) {
                        queue.enqueue(entry(targetIdx, candidate, ALL_DIRS & ~(1 << OPPOSITE[dir]), dstShape == 0, layer));
                    }
                }
            }
        }
    }

    /** 4 邻列注入点最大值（域外邻列 = 无注入 NEG_INF，等价官方缺块 emptyChunkSources）。 */
    private static int maxNeighborSourceY(int[] sourceY, int width, int x, int z) {
        int m = NEG_INF;
        if (z > 0) {
            m = Math.max(m, sourceY[(z - 1) * width + x]);
        }
        if (z < width - 1) {
            m = Math.max(m, sourceY[(z + 1) * width + x]);
        }
        if (x > 0) {
            m = Math.max(m, sourceY[z * width + x - 1]);
        }
        if (x < width - 1) {
            m = Math.max(m, sourceY[z * width + x + 1]);
        }
        return m;
    }

    /** 天空种子方向掩码（官方 increaseSkySourceInDirections(down, north, south, west, east)）。 */
    private static int skySeedMask(int[] sourceY, int width, int x, int z, int y, int sy) {
        int mask = 0;
        if (y == sy) {
            mask |= 1 << DOWN;
        }
        if (z > 0 && y < sourceY[(z - 1) * width + x]) {
            mask |= 1 << NORTH;
        }
        if (z < width - 1 && y < sourceY[(z + 1) * width + x]) {
            mask |= 1 << SOUTH;
        }
        if (x > 0 && y < sourceY[z * width + x - 1]) {
            mask |= 1 << WEST;
        }
        if (x < width - 1 && y < sourceY[z * width + x + 1]) {
            mask |= 1 << EAST;
        }
        return mask;
    }

    /** 队列条目：index(20bit) | layer(1) | dirMask(6) | fromEmpty(1) | level(4)。 */
    private static long entry(int idx, int level, int dirMask, boolean fromEmpty, int layer) {
        return ((long) idx << 12) | ((long) layer << 11) | ((long) dirMask << 5)
                | ((long) (fromEmpty ? 1 : 0) << 4) | level;
    }
}
