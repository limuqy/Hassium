package io.github.limuqy.mc.hassium.concurrent;

import net.minecraft.world.level.ChunkPos;

/**
 * 区块距离优先级（统一入口）。
 * <p>
 * <b>约定</b>：数值 <strong>越小越优先</strong>。
 * 排序键为 {@code tier * TIER_BIAS + distSq}（权威 / 未知 / 超视分层），比较与排序用平方距离，
 * 避免重复 {@code sqrt}；仅日志展示时可调 {@link #distance}。
 * <p>
 * <b>层序（始终）</b>：
 * {@link Tier#AUTHORITATIVE} &gt; {@link Tier#UNKNOWN} &gt; {@link Tier#RENDER_ONLY}。
 * 无 chunk 锚点的全局回调用 {@link #unknown()}；
 * 坐标未知时权威 / 环带仍落在各自层（{@code base + 0}），不伪装 (0,0)，也不互相越层。
 * <p>
 * <b>冻结语义</b>：调用方在入队瞬间算出 {@code double} 后写入任务记录；
 * 之后玩家移动<strong>不会</strong>改写已入队任务的优先级（{@link java.util.concurrent.PriorityBlockingQueue}
 * 也不支持 key 变化后自动堆调整）。需要更近的块优先时，应在入队前用最新坐标计算。
 * <p>
 * 玩家坐标缓存见 {@link MainThreadDispatcher#updatePlayerPosition}；客户端路径应先刷新缓存再算。
 * 门控入口见 {@link MainThreadDispatcher#authoritativePriority} /
 * {@link MainThreadDispatcher#renderOnlyPriority}。
 */
public final class ChunkDistancePriority {

    /**
     * 绝对垫底（非法入口、null 等）。正常路径应使用 {@link Tier} 键，
     * 全局无锚点任务用 {@link #unknown()}（落在 UNKNOWN 层，仍优先于环带）。
     */
    public static final double LOWEST = Double.MAX_VALUE;

    /**
     * 层间间隔。任意合理视距下单层 {@code distSq}（chunk 空间）远小于此值，
     * 保证层序：权威永远先于未知任务，未知任务永远先于环带。
     * <p>
     * 例：半径 512 chunk → max distSq ≈ 2·512² ≈ 5.2e5 ≪ 1e9。
     */
    public static final double TIER_BIAS = 1_000_000_000d;

    /**
     * 优先级层：先比 tier，再比层内 distSq。
     * <p>
     * 序：{@link #AUTHORITATIVE} &lt; {@link #UNKNOWN} &lt; {@link #RENDER_ONLY}
     *（数值越小越优先 → 权威始终最先，环带始终最后）。
     */
    public enum Tier {
        /** serverVD 内权威块（chunkHash 命中、全量推送 apply 等） */
        AUTHORITATIVE(0),
        /** 无 chunk 锚点 / 全局回调；夹在权威与环带之间 */
        UNKNOWN(1),
        /** 超视渲染环带 renderOnly，永远低于权威与未知任务 */
        RENDER_ONLY(2);

        private final int index;

        Tier(int index) {
            this.index = index;
        }

        public int index() {
            return index;
        }

        public double base() {
            return index * TIER_BIAS;
        }
    }

    private ChunkDistancePriority() {}

    // ── 原始距离 ──────────────────────────────────────────────

    /**
     * chunk 坐标空间平方欧氏距离（用于 Comparator / 排序，无开方）。
     */
    public static double distSq(ChunkPos pos, double playerChunkX, double playerChunkZ) {
        double dx = pos.x - playerChunkX;
        double dz = pos.z - playerChunkZ;
        return dx * dx + dz * dz;
    }

    /**
     * 整数中心（如 view-distance resync 扫掠中心）的平方距离。
     */
    public static double distSq(ChunkPos pos, int centerChunkX, int centerChunkZ) {
        double dx = pos.x - (double) centerChunkX;
        double dz = pos.z - (double) centerChunkZ;
        return dx * dx + dz * dz;
    }

    /**
     * 由世界坐标（方块）换算玩家所在 chunk 分式坐标后的 distSq。
     */
    public static double distSqFromWorld(ChunkPos pos, double worldX, double worldZ) {
        return distSq(pos, worldX / 16.0, worldZ / 16.0);
    }

    /**
     * 欧氏距离（仅日志 / 展示；排序请用 {@link #distSq} 或 {@link #of}）。
     */
    public static double distance(ChunkPos pos, double playerChunkX, double playerChunkZ) {
        return Math.sqrt(distSq(pos, playerChunkX, playerChunkZ));
    }

    public static double distanceFromWorld(ChunkPos pos, double worldX, double worldZ) {
        return Math.sqrt(distSqFromWorld(pos, worldX, worldZ));
    }

    // ── 冻结优先级键 ──────────────────────────────────────────

    /**
     * {@code tier * TIER_BIAS + distSq}，入队时调用一次后冻结。
     */
    public static double of(Tier tier, ChunkPos pos, double playerChunkX, double playerChunkZ) {
        if (pos == null || tier == null) {
            return LOWEST;
        }
        return tier.base() + distSq(pos, playerChunkX, playerChunkZ);
    }

    /**
     * 世界坐标版 {@link #of}。
     */
    public static double ofWorld(Tier tier, ChunkPos pos, double worldX, double worldZ) {
        return of(tier, pos, worldX / 16.0, worldZ / 16.0);
    }

    /**
     * 整数中心版（服务端 resync 等）。
     */
    public static double ofCenter(Tier tier, ChunkPos pos, int centerChunkX, int centerChunkZ) {
        if (pos == null || tier == null) {
            return LOWEST;
        }
        return tier.base() + distSq(pos, centerChunkX, centerChunkZ);
    }

    /**
     * 层内仅 base、无距离（坐标未知时的权威/环带，或未知层本身）。
     * 保持层序，不因 (0,0) 伪装而打乱远近。
     */
    public static double ofUnknownDistance(Tier tier) {
        if (tier == null) {
            return LOWEST;
        }
        return tier.base();
    }

    /** 无 chunk 锚点的全局任务：UNKNOWN 层，介于权威与环带之间。 */
    public static double unknown() {
        return Tier.UNKNOWN.base();
    }

    /** 权威层：{@link Tier#AUTHORITATIVE} + distSq。 */
    public static double authoritative(ChunkPos pos, double playerChunkX, double playerChunkZ) {
        return of(Tier.AUTHORITATIVE, pos, playerChunkX, playerChunkZ);
    }

    public static double authoritativeFromWorld(ChunkPos pos, double worldX, double worldZ) {
        return ofWorld(Tier.AUTHORITATIVE, pos, worldX, worldZ);
    }

    public static double authoritativeFromCenter(ChunkPos pos, int centerChunkX, int centerChunkZ) {
        return ofCenter(Tier.AUTHORITATIVE, pos, centerChunkX, centerChunkZ);
    }

    /** 超视 renderOnly 层：永远排在权威与未知任务之后。 */
    public static double renderOnly(ChunkPos pos, double playerChunkX, double playerChunkZ) {
        return of(Tier.RENDER_ONLY, pos, playerChunkX, playerChunkZ);
    }

    public static double renderOnlyFromWorld(ChunkPos pos, double worldX, double worldZ) {
        return ofWorld(Tier.RENDER_ONLY, pos, worldX, worldZ);
    }
}
