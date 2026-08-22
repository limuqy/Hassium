package io.github.limuqy.mc.hassium.utils;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 维度复合键工具：将「维度 + 区块坐标」编码为单个 {@code long}，供客户端缓存域
 * 各内存表统一使用（hash 表 / 脏表 / pending 表等），禁止各表自造编码。
 * <p>
 * 纯 Java 工具类，无 Minecraft 依赖；坐标段布局与 {@code ChunkPos.asLong} 语义一致
 * （x、z 各为 32 位有符号整数），保证既有裸键可直接接入。
 * <p>
 * 编码布局（64 位）：
 * <ul>
 *   <li>高 12 位 [63..52]：维度稳定 id（内部注册表分配，单调递增永不复用）</li>
 *   <li>低 52 位 [51..0]：区块坐标，对称位域 —— x 占 [51..26] 共 26 位，
 *       z 占 [25..0] 共 26 位（各自二进制补码截取，26 位可表达 ±33.5M 方块 ≈ ±2M 区块）</li>
 * </ul>
 * 拼接采用无符号位域而非异或/加法：负坐标不碰撞、跨维同坐标不碰撞。
 */
public final class DimensionKey {
    private DimensionKey() {}

    /** 主世界维度 id。 */
    public static final String OVERWORLD = "minecraft:overworld";
    /** 下界维度 id。 */
    public static final String NETHER = "minecraft:the_nether";
    /** 末地维度 id。 */
    public static final String END = "minecraft:the_end";

    /** 维度 id 位宽。 */
    private static final int DIM_BITS = 12;
    /** 坐标位宽（x/z 对称各 26 位）。 */
    private static final int COORD_BITS = 26;
    /** 单轴掩码（26 位）。 */
    private static final long COORD_MASK = (1L << COORD_BITS) - 1;
    /** posKey 有效位宽（x+z 两段坐标，维度 id 位于其上的高 12 位）。 */
    private static final int POS_BITS = 2 * COORD_BITS;

    /** dimension → 稳定 int id（注册即固定，永不复用）。 */
    private static final Map<String, Integer> IDS = new ConcurrentHashMap<>();
    /** id → dimension 反查表（id 分配仅在 computeIfAbsent 内串行化）。 */
    private static final Map<Integer, String> NAMES = new ConcurrentHashMap<>();

    static {
        register(OVERWORLD);
        register(NETHER);
        register(END);
    }

    /**
     * 注册维度并返回其稳定 id；已注册则返回既有 id（幂等）。
     * 未注册维度在 {@link #key(String, int, int)} 时自动注册。
     */
    public static int register(String dimension) {
        Integer existing = IDS.get(dimension);
        if (existing != null) {
            return existing;
        }
        return IDS.computeIfAbsent(dimension, dim -> {
            // NAMES.putIfAbsent 保证并发下先到者占 id，后到者顺延复用探测值，id 永不复用
            int next = IDS.size();
            while (NAMES.putIfAbsent(next, dim) != null) {
                next++;
            }
            return next;
        });
    }

    /**
     * 复合键：dimension 映射为稳定 id 后与区块坐标组合。
     * 未注册维度自动注册（幂等）。
     */
    public static long key(String dimension, int chunkX, int chunkZ) {
        return compose(register(dimension), chunkX, chunkZ);
    }

    /**
     * 复合键：接受既有裸键（{@code ChunkPos.asLong} 布局：x 低 32 位、z 高 32 位）。
     * 解码出 x/z 后重打包为内部对称布局，未注册维度自动注册（幂等）。
     */
    public static long key(String dimension, long chunkPosKey) {
        return key(dimension, (int) chunkPosKey, (int) (chunkPosKey >>> 32));
    }

    /** 由复合键反解维度名；未知 id 返回 null（防御性，正常不可达）。 */
    public static String dimensionOf(long key) {
        return NAMES.get((int) (key >>> POS_BITS));
    }

    /** 由复合键反解区块 X 坐标（26 位补码符号扩展）。 */
    public static int chunkXOf(long key) {
        return (int) ((key >>> COORD_BITS) << (Integer.SIZE - COORD_BITS)) >> (Integer.SIZE - COORD_BITS);
    }

    /** 由复合键反解区块 Z 坐标（26 位补码符号扩展）。 */
    public static int chunkZOf(long key) {
        int raw = (int) (key & COORD_MASK);
        return (raw << (Integer.SIZE - COORD_BITS)) >> (Integer.SIZE - COORD_BITS);
    }

    /**
     * 维度白名单判定：仅三主维度可进客户端缓存链路；
     * null / 自定义维度一律 false（调用方透传，不进影子比对/OVD/落盘/SeedGen）。
     */
    public static boolean isCacheableDimension(String dimension) {
        return OVERWORLD.equals(dimension) || NETHER.equals(dimension) || END.equals(dimension);
    }

    private static long compose(int dimId, int chunkX, int chunkZ) {
        long pos = ((chunkX & COORD_MASK) << COORD_BITS) | (chunkZ & COORD_MASK);
        return (((long) dimId) << POS_BITS) | pos;
    }
}
