package io.github.limuqy.mc.hassium.network.entity;

/**
 * 实体包背压的纯逻辑（无 Minecraft 类型）：按「最忙玩家当帧的实体包数 / 配额」闭环调整降帧压力档。
 * <p>
 * 压力是乘在间隔上的倍数：档位越高降帧越狠，直到单个连接的当帧实体包数回到配额以内。用 EWMA
 * 而不是单帧比值，是因为实体进出视锥本身抖动很大，直接跟随会让档位每帧跳变、玩家看到实体忽隐忽现。
 * <p>
 * 有状态、<b>非线程安全</b>，只允许主线程（或持有该实例的那条线程）独占调用。
 */
public final class EntityFramePressure {

    /** EWMA 平滑因子：0.25 意味着约 4 帧跟上一次突变，既能压制抖动又不至于反应迟钝。 */
    private static final double EWMA_ALPHA = 0.25;

    /** 超载阈值：留 25% 余量再升挡，避免贴着配额来回升降。 */
    private static final double OVERLOAD_RATIO = 1.25;

    /** 低载阈值：低到配额一半才考虑回收档位，否则一次安静帧就退挡、下帧又得升回来。 */
    private static final double UNDERLOAD_RATIO = 0.5;

    /** 低载持续多少次才降 1 挡：多数实体离开视锥是常态，退挡必须比升挡谨慎得多。 */
    private static final int UNDERLOAD_SAMPLES_PER_STEP = 20;

    /** 压力档上限硬顶：16 倍间隔已经远超 {@code MAX_INTERVAL} 的收益区间，再高无意义。 */
    private static final int MAX_PRESSURE_CAP = 16;

    private final int maxPressure;
    private int pressure = 1;
    private double ewma;
    private boolean ewmaInitialized;
    private int underloadSamples;

    /**
     * @param maxPressure 压力档上限，构造内 clamp 到 [1, 16]
     */
    public EntityFramePressure(int maxPressure) {
        this.maxPressure = Math.max(1, Math.min(MAX_PRESSURE_CAP, maxPressure));
    }

    /** @return 当前压力档，1 表示不做额外降帧 */
    public int pressure() {
        return pressure;
    }

    /**
     * 用当帧观测更新压力档。
     *
     * @param worstPlayerPackets 最忙玩家当帧发送的实体包数
     * @param budget             该帧允许的实体包配额；{@code <= 0} 表示本帧没有可用的配额信息
     *                           （实体降帧被临时关闭 / 玩家连接未就绪），此时回到初始档并清空内部状态，
     *                           避免拿一份过期 EWMA 在下次启用时误升挡
     */
    public void sample(int worstPlayerPackets, int budget) {
        if (budget <= 0) {
            reset();
            return;
        }
        double ratio = worstPlayerPackets / (double) budget;
        ewma = ewmaInitialized ? ewma * (1.0 - EWMA_ALPHA) + ratio * EWMA_ALPHA : ratio;
        ewmaInitialized = true;
        if (ewma > OVERLOAD_RATIO) {
            pressure = Math.min(maxPressure, pressure * 2);
            underloadSamples = 0;
        } else if (ewma < UNDERLOAD_RATIO) {
            if (++underloadSamples >= UNDERLOAD_SAMPLES_PER_STEP) {
                underloadSamples = 0;
                pressure = Math.max(1, pressure - 1);
            }
        }
        // 0.5 ≤ ewma ≤ 1.25 的回差区间：压力与退挡计数都保持，防止在阈值上反复横跳。
    }

    /** 回到初始态：压力 1、EWMA 未初始化、退挡计数清零。 */
    public void reset() {
        pressure = 1;
        ewma = 0.0;
        ewmaInitialized = false;
        underloadSamples = 0;
    }
}
