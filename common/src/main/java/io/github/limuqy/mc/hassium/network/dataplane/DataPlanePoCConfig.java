package io.github.limuqy.mc.hassium.network.dataplane;

/**
 * PoC 数据面静态配置常量。
 * 设计稿 §8；后续握手扩展阶段迁移到 server.toml。
 */
public class DataPlanePoCConfig {

    public static final boolean ENABLED = true;
    public static final String BULK_ROUTE_MODE = "share"; // "share" | "exclusive"
    public static final int PRIMARY_WEIGHT = 100;
    public static final int DEGRADE_AFTER_DROPS = 3;
    public static final boolean CLIENT_ENABLE_DATA_PLANE = true;

    public static final byte[] BIND_TOKEN = new byte[16]; // 全零 PoC

    /** HKDF info 区分标签（PoC 固定；用于每帧写密钥派生） */
    public static final int FRAME_KEY_INFO_TAG = 0x44_50_4C_31; // "DPL1"

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