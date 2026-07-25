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
}