package io.github.limuqy.mc.hassium.network.dataplane;

/**
 * UDP 数据面协议与路由常量。
 *
 * <p>服务端启用状态和 UDP listener 端点由
 * {@link io.github.limuqy.mc.hassium.config.HassiumConfig.MasterCoreConfig#dataPlane()}
 * 的 {@code hassium-server.toml dataplane.*} 快照提供；本类不再持有可变端点或启用开关。
 */
public final class DataPlanePoCConfig {

    private DataPlanePoCConfig() {
    }

    public static final String BULK_ROUTE_MODE = "share"; // "share" | "exclusive"
    public static final int PRIMARY_WEIGHT = 100;
    public static final int DEGRADE_AFTER_DROPS = 3;
    public static final boolean CLIENT_ENABLE_DATA_PLANE = true;

    /**
     * Data 通道读超时（秒）。Bind 后空闲无入帧即断。
     * <p>
     * 由 {@link #KEEPALIVE_INTERVAL_SECS} 双向心跳刷新（间隔 < 读超时）；
     * 心跳关闭时退化为短超时检测。原 PoC 硬编码 5s 在 bulk 稀疏阶段（chunkHash 增量
     * perTick=32 节流）会误踢健康通道，已调大为 30s 并配合心跳（见 2026-07-26 端到端首跑回写）。
     */
    public static final int READ_TIMEOUT_SECS = 30;

    /** KEEPALIVE/ACK 双向心跳间隔（秒）；须严格小于 {@link #READ_TIMEOUT_SECS}。 */
    public static final int KEEPALIVE_INTERVAL_SECS = 2;

    /** 心跳总开关。关时两端不发心跳，仅靠 READ_TIMEOUT_SECS 超时检测。 */
    public static final boolean KEEPALIVE_ENABLED = true;

    /**
     * 数据面热路径日志开关（PoC 已落地，1.1.0 起合并进 {@code debug.dataplaneLogging}）。
     * <p>
     * 旧 PoC 阶段曾以 {@code public static final boolean DEBUG_DATAPLANE = true} 硬编码开启，
     * 1.1.0 后改为通过 {@link io.github.limuqy.mc.hassium.utils.DebugLogger#isEnabled} 查
     * {@link io.github.limuqy.mc.hassium.config.HassiumConfig.DebugConfig#dataplaneLogging()}，
     * 与其它 7 个 {@code debug.*} 开关保持同一控制面。Hot path 调用点统一改用
     * {@link io.github.limuqy.mc.hassium.utils.DebugLogger#info(LogType, String, Object...)}，
     * 调用点判断分支用本方法。
     */
    public static boolean isDataplaneLogEnabled() {
        return io.github.limuqy.mc.hassium.utils.DebugLogger.isEnabled(
                io.github.limuqy.mc.hassium.utils.DebugLogger.LogType.DATAPLANE);
    }

    // review-fix: T4-80 — 删除死常量 BIND_TOKEN（全零 public byte[]，可被任何代码改写，误用即静默
    // 取消全部鉴权；grep 确认 src/main + src/test + 三端均无引用）与 FRAME_KEY_INFO_TAG（PoC 遗留，
    // 密钥派生已收敛到 UdpSessionKey.derive / DataPlaneUdpServer，无需区分标签）。
}