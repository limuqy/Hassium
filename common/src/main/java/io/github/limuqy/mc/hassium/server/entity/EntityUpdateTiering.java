package io.github.limuqy.mc.hassium.server.entity;

/**
 * 实体更新降帧的纯逻辑（无 Minecraft 类型）：把「每实体每 tick 每连接」的实体包按观察距离分档，
 * 再把「当帧该连接挤了多少个实体」折算成额外间隔倍数，最后与原版自身的 updateInterval 取保守值。
 * <p>
 * 分档按「水平距离 / 有效跟踪范围」的比例而非绝对方块数，这样服务器调整 {@code entityTrackingRange}
 * 时不必重新调参：{@link #TIER_FRACTIONS} 直接就是跟踪范围的百分比。所有方法均为静态纯函数、无状态，
 * 由调用方（mixin / 网络引擎）决定用哪一档、乘几次。
 */
public final class EntityUpdateTiering {

    /** 档位数量：由近到远（近档间隔最小、远档最大）。 */
    public static final int TIER_COUNT = 4;

    /** 降帧间隔上限（tick）：2 秒一发是降帧的收益底线，再稀会让远处实体看起来卡死。 */
    public static final int MAX_INTERVAL = 40;

    /** 各档位在有效跟踪范围中的比例上界；ratio 落入哪段就取哪一档。 */
    public static final double[] TIER_FRACTIONS = {0.25, 0.5, 0.75, 1.0};

    /** 实体距离档间隔默认表（逗号分隔，近/中/远/边缘）；{@code ConfigSchema} 与 {@code HassiumConfig.DEFAULT} 共用同一份，避免默认值漂移。 */
    public static final String DEFAULT_ENTITY_INTERVALS = "3,4,6,10";

    /** 物品流（掉落物/经验球）默认表；近档 2 刻仍在客户端 3 刻插值窗口内。 */
    public static final String DEFAULT_ITEM_INTERVALS = "2,4,8,16";

    /** 每档热点阈值默认表（近/中/远/边缘）。 */
    public static final String DEFAULT_DENSITY_COUNTS = "10,20,32,64";

    /** 每档热点倍率默认表（近/中/远/边缘）。 */
    public static final String DEFAULT_DENSITY_FACTORS = "1.5,2.0,3.0,4.0";

    /** 间隔下限：0 会让实体每 tick 都发，等价于没降帧。 */
    private static final int MIN_INTERVAL = 1;

    /** 间隔上限：用户配置再大也会被 {@link #effectiveInterval} 的 MAX_INTERVAL 收口，此处只防手滑输入天文数字。 */
    private static final int MAX_SANITIZED_INTERVAL = 120;

    /** 热点倍率上限：只防手滑写出天文倍数；真正生效的上限是引擎侧 {@code master.entityMaxThrottleFactor} 收口后的乘积。 */
    private static final double MAX_SANITIZED_FACTOR = 64.0;

    private EntityUpdateTiering() {
    }

    /**
     * 解析「每档间隔」配置串（逗号分隔，按 近/中/远/边缘 顺序）。
     * <p>
     * 容错口径（与热点两张表一致）：单个元素写坏只回落该元素的默认值；元素个数不对则整表回落默认——
     * 少写一档会让「哪一档配的哪一段距离」整体错位，比单个数字写错危险得多。
     * 另外强制 **非降序**：远档比近档还勤既没省带宽又让画面错乱，直接把该元素抬到前一档的值。
     *
     * @param raw      形如 {@code "3,4,6,10"}；{@code null}/空白/长度不符按 {@code fallback}
     * @param fallback 整表回落用的默认串（须与 ConfigSchema 的默认值一致）
     * @return 长度 {@link #TIER_COUNT} 的新数组，元素非降序且落在 [{@value #MIN_INTERVAL}, {@value #MAX_SANITIZED_INTERVAL}]
     */
    public static int[] parseIntervals(String raw, String fallback) {
        int[] fallbackValues = parseNumbers(fallback);
        String[] parts = split4(raw);
        int[] out = new int[TIER_COUNT];
        int previous = MIN_INTERVAL;
        for (int i = 0; i < TIER_COUNT; i++) {
            int value = 0;
            if (parts != null) {
                try {
                    value = Integer.parseInt(parts[i]);
                } catch (NumberFormatException ignored) {
                    value = 0;
                }
            }
            // ≤ 0 视作未配置：间隔 0 会让实体每 tick 发包，比原版还费，必须回落默认而不是夹到 1
            if (value <= 0) {
                value = fallbackValues[i];
            }
            value = Math.max(MIN_INTERVAL, Math.min(MAX_SANITIZED_INTERVAL, value));
            if (value < previous) {
                value = previous;
            }
            out[i] = value;
            previous = value;
        }
        return out;
    }

    /** 解析默认表串（回落串本身写坏了也不让调用方拿到空表）。 */
    private static int[] parseNumbers(String fallback) {
        int[] out = {3, 4, 6, 10};
        String[] parts = split4(fallback);
        if (parts == null) {
            return out;
        }
        for (int i = 0; i < TIER_COUNT; i++) {
            try {
                out[i] = Integer.parseInt(parts[i]);
            } catch (NumberFormatException ignored) {
                // 默认串写坏：保留该元素的兜底值
            }
        }
        return out;
    }

    /**
     * 距离比例落在哪一档。
     *
     * @param ratio 水平距离 / 有效跟踪范围
     * @return 0..{@code TIER_COUNT - 1}；{@code ratio <= 0}（distance == 0，观察者自身）归入最近档 0；
     *         {@code ratio > 1} 或非有限值（NaN/±Inf）返回 -1，表示该实体不在观察者跟踪范围内、不参与降帧
     */
    public static int tierOf(double ratio) {
        if (!Double.isFinite(ratio) || ratio > 1.0) {
            return -1;
        }
        if (ratio <= 0.0) {
            return 0;
        }
        for (int i = 0; i < TIER_COUNT; i++) {
            if (ratio <= TIER_FRACTIONS[i]) {
                return i;
            }
        }
        return TIER_COUNT - 1;
    }

    /**
     * 每档热点：实体所在 chunk 的活跃实体数 ≥ 该档阈值时，给该档实体乘上该档热点倍率。
     * <p>
     * 与距离档共用「档」的定义（{@link #tierOf}），所以「贴脸一堆掉落物」和「远处刷怪塔」可以用不同的
     * 阈值与倍率：近档可以把阈值调高 / 倍率压到 1.0（等效豁免），边缘档可以提前介入。
     *
     * @param count       实体所在 chunk 的活跃实体数（本引擎上一 tick 的观测）
     * @param tier        距离档；{@code < 0}（无观察者）或越界返回 1.0
     * @param tierCounts  每档阈值，长度 {@link #TIER_COUNT}
     * @param tierFactors 每档倍率，长度 {@link #TIER_COUNT}
     * @return {@code count >= tierCounts[tier] ? tierFactors[tier] : 1.0}
     */
    public static double densityFactor(int count, int tier, int[] tierCounts, double[] tierFactors) {
        if (count <= 0 || tier < 0 || tier >= TIER_COUNT) {
            return 1.0;
        }
        if (count < tierCounts[tier]) {
            return 1.0;
        }
        return Math.max(1.0, tierFactors[tier]);
    }

    /**
     * 解析「每档热点阈值」配置串（逗号分隔，按近/中/远/边缘顺序）。
     * <p>
     * 手写配置的容错口径与 {@link #parseIntervals} 一致：单个元素非法只回落该元素的默认值，
     * 元素个数不对（少写/多写一档）则整表回落默认——长度错会让档位与语义整体错位，比单个数字写错危险得多。
     *
     * @param raw 形如 {@code "10,20,32,64"}；{@code null}/空白按默认
     * @return 长度 {@link #TIER_COUNT} 的新数组，元素 ≥ 1
     */
    public static int[] parseDensityCounts(String raw) {
        int[] fallback = parseNumbers(DEFAULT_DENSITY_COUNTS);
        String[] parts = split4(raw);
        if (parts == null) {
            return fallback;
        }
        int[] out = new int[TIER_COUNT];
        for (int i = 0; i < TIER_COUNT; i++) {
            int value = fallback[i];
            try {
                value = Integer.parseInt(parts[i]);
            } catch (NumberFormatException ignored) {
                // 单个元素写坏只回落该元素
            }
            out[i] = Math.max(1, Math.min(1_000_000, value));
        }
        return out;
    }

    /**
     * 解析「每档热点倍率」配置串（逗号分隔，按近/中/远/边缘顺序）。倍率 &lt; 1 会被夹到 1，
     * 保持「只降速、永不提速」的不变式；小数受支持，最终间隔会四舍五入到整刻。
     *
     * @param raw 形如 {@code "1.5,2.0,3.0,4.0"}；{@code null}/空白按默认
     * @return 长度 {@link #TIER_COUNT} 的新数组，元素落在 [1.0, {@value #MAX_SANITIZED_FACTOR}]
     */
    public static double[] parseDensityFactors(String raw) {
        double[] fallback = parseFactors(DEFAULT_DENSITY_FACTORS);
        String[] parts = split4(raw);
        if (parts == null) {
            return fallback;
        }
        double[] out = new double[TIER_COUNT];
        for (int i = 0; i < TIER_COUNT; i++) {
            double value = fallback[i];
            try {
                value = Double.parseDouble(parts[i]);
            } catch (NumberFormatException ignored) {
                // 单个元素写坏只回落该元素
            }
            if (!Double.isFinite(value)) {
                value = fallback[i];
            }
            out[i] = Math.max(1.0, Math.min(MAX_SANITIZED_FACTOR, value));
        }
        return out;
    }

    /** 解析默认倍率串。 */
    private static double[] parseFactors(String fallback) {
        double[] out = {1.5, 2.0, 3.0, 4.0};
        String[] parts = split4(fallback);
        if (parts == null) {
            return out;
        }
        for (int i = 0; i < TIER_COUNT; i++) {
            try {
                double value = Double.parseDouble(parts[i]);
                if (Double.isFinite(value)) {
                    out[i] = Math.max(1.0, Math.min(MAX_SANITIZED_FACTOR, value));
                }
            } catch (NumberFormatException ignored) {
                // 默认串写坏：保留该元素的兜底值
            }
        }
        return out;
    }

    /** 逗号分列并去空白；元素个数不是 {@link #TIER_COUNT} 时返回 {@code null}（调用方整表回落）。 */
    private static String[] split4(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String[] parts = raw.split(",", -1);
        if (parts.length != TIER_COUNT) {
            return null;
        }
        for (int i = 0; i < parts.length; i++) {
            parts[i] = parts[i].trim();
        }
        return parts;
    }

    /**
     * 降帧后应作为「原版间隔下限」的值。
     * <p>
     * 对绝大多数实体，原版 {@code updateInterval} 就是它的**位置**节拍，取 {@code max} 保证只降速不提速。
     * 物品流例外：{@code EntityType.ITEM} / {@code EXPERIENCE_ORB} 把它设成 20，而这两个实体的位置节拍
     * 其实由每 tick 被置位的 {@code hasImpulse} 驱动（1 刻）；20 只是它的空闲/元数据节拍。降帧接管
     * {@code hasImpulse} 之后若仍拿 20 当下限，档位表会被整体压平——物品恒 1 包/s，客户端插值（3 刻）
     * 之间有 17 刻空档，画面上就是瞬移/闪烁。故物品流的下限取 {@value #MIN_INTERVAL}。
     *
     * @param vanillaInterval 原版该实体的 updateInterval
     * @param itemFlow        是否为物品流（掉落物 / 经验球）
     */
    public static int intervalFloor(int vanillaInterval, boolean itemFlow) {
        return itemFlow ? MIN_INTERVAL : vanillaInterval;
    }

    /**
     * 合成最终发送间隔：与「原版间隔下限」取较大者，再乘降帧倍率（密度 × 压力，可含小数）并收口到
     * {@link #MAX_INTERVAL}。
     * <p>
     * 下限由调用方给出（普通实体 = 原版 {@code updateInterval}，物品流 = 1，见 {@link #intervalFloor}）；
     * {@code floorInterval > MAX_INTERVAL} 时原样透传：这类值是原版自己有意的「几乎不发」标记
     * （ItemFrame 用 {@code Integer.MAX_VALUE}），已经比我们的降帧更省，截断反而会凭空放大带宽。
     *
     * @param floorInterval 原版间隔下限（tick）
     * @param tierInterval  本档配置间隔；{@code < 1} 视作 1
     * @param factor        密度 × 压力倍率；{@code < 1} 视作 1，允许小数（结果四舍五入到整刻）
     * @return 实际使用的间隔（tick）
     */
    public static int effectiveInterval(int floorInterval, int tierInterval, double factor) {
        if (floorInterval > MAX_INTERVAL) {
            return floorInterval;
        }
        int tier = tierInterval < 1 ? 1 : tierInterval;
        double multiplier = !Double.isFinite(factor) || factor < 1.0 ? 1.0 : factor;
        long scaled = Math.round(Math.max(floorInterval, tier) * multiplier);
        return (int) Math.max(MIN_INTERVAL, Math.min(MAX_INTERVAL, scaled));
    }

    /**
     * 无观察者时用的占位间隔。
     * <p>
     * 取值同为 {@link #MAX_INTERVAL}：原版此时照样遍历实体并组包，只是发出去没人收；改用最大间隔
     * 语义不变（首包仍会发，后续几乎不发），只是替服务端省掉 CPU。
     */
    public static int noObserverInterval() {
        return MAX_INTERVAL;
    }

    /**
     * 稳定相位偏移（错峰推送）：同一更新间隔的实体按 hash 错开发送时刻。
     * <p>
     * 门条件从 {@code tickCount % interval == 0} 变成 {@code (tickCount + phase) % interval == 0}。
     * 任意连续 {@code interval} 个 tick 内每个实体仍只发一次（总量不变），但不同实体落在不同刻，
     * 把 vanilla「同 interval 齐发」的尖峰摊平。无队列、无延迟积压。
     *
     * @param entityHash 实体稳定哈希（UUID）
     * @param interval   生效间隔（刻）
     * @return {@code [0, interval)} 的相位；{@code interval <= 1} 时恒为 0（每 tick 都发，错峰无意义）
     */
    public static int phaseOffset(int entityHash, int interval) {
        if (interval <= 1) {
            return 0;
        }
        return Math.floorMod(entityHash, interval);
    }
}
