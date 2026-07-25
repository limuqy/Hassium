package io.github.limuqy.mc.hassium.network.dataplane;

/**
 * PoC 数据面静态配置常量。
 * 设计稿 §8；后续握手扩展阶段迁移到 server.toml。
 */
public class DataPlanePoCConfig {

    /**
     * PoC 数据面总开关。
     * <p>
     * 由 {@link #isEnabled()} / {@link #setEnabled(boolean)} 读写，故非 {@code final}；
     * {@code volatile} 保证多线程可见。改非 final 的目的：单测可 {@code setEnabled(false)}
     * 验证「关闭即零副作用、走 vanilla Primary 路径」（设计稿 §7 step 7 回归守护），
     * 避免 {@code static final} 编译期内联进 {@code HassiumMod} 字节码后反射改不动。
     * 默认值仍为 {@code true}。
     */
    private static volatile boolean ENABLED = true;

    /** PoC 数据面总开关读取入口（call site 一律走此方法，便于将来迁移到 server.toml）。 */
    public static boolean isEnabled() { return ENABLED; }

    /** 供单测/冒烟在线翻转总开关；true=启用 Data 通道路由。生产化阶段迁移到 server.toml 后此方法废弃。 */
    public static void setEnabled(boolean enabled) { ENABLED = enabled; }

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

    public static final byte[] BIND_TOKEN = new byte[16]; // 全零 PoC

    /** HKDF info 区分标签（PoC 固定；用于每渠道写密钥派生） */
    public static final int FRAME_KEY_INFO_TAG = 0x44_50_4C_31; // "DPL1"

    /** 端点摘要（供 init 日志打印，不重复构造字符串） */
    public static String endpointsSummary() {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < ENDPOINTS.length; i++) {
            if (i > 0) sb.append(", ");
            Endpoint ep = ENDPOINTS[i];
            sb.append("#{").append(i + 1).append("} ")
              .append(ep.address).append(':').append(ep.port)
              .append("(w=").append(ep.weight).append(')');
        }
        return sb.append(']').toString();
    }

    public static class Endpoint {
        public final String address;
        public final int port;
        public final int weight;
        public final String bindHost;
        public final int bindPort;

        public Endpoint(String address, int port, int weight, String bindHost, int bindPort) {
            this.address = address;
            this.port = port;
            this.weight = weight;
            this.bindHost = bindHost;
            this.bindPort = bindPort;
        }
    }

    public static final Endpoint[] ENDPOINTS = new Endpoint[]{
        new Endpoint("127.0.0.1", 25566, 50, "0.0.0.0", 25566),
        new Endpoint("127.0.0.1", 25567, 50, "0.0.0.0", 25567),
    };

    /**
     * PoC 演示用的伪玩家 ID。因 PoC 不扩展握手，Data 通道无法绑定真实玩家 UUID；
     * 服务端按 Bind 时的 channelId 建 bundle，拦截处用此固定伪 ID 查 bundle。
     * 实际多玩家绑定留到握手扩展阶段。
     */
    public static java.util.UUID pseudoPlayerId() {
        return new java.util.UUID(0L, 1L);
    }
}