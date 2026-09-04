package io.github.limuqy.mc.hassium.network.handshake;

import io.github.limuqy.mc.hassium.config.HassiumConfigService;

/**
 * 登录期能力位（直连拓扑；两端共用同一组位定义，协商结果 = 两端按位与）。
 * <p>
 * 位语义按「声明方」解释：客户端声明客户端侧支持（缓存/本地生成/影子光照等），
 * 服务端声明服务端侧开启（配置开关）；按位与后即本连接实际启用的能力集合。
 */
public final class LoginCaps {
    private LoginCaps() {
    }

    /** 服务端包聚合（客户端需带反聚合 receiver）。 */
    public static final int AGGREGATION = 1 << 0;

    /** 紧凑包头（NamespaceIndexManager 两级 VarInt 索引）。 */
    public static final int COMPACT_HEADER = 1 << 1;

    /** 区块推送链（chunkHash/缓存/压缩 full 区块业务帧）。 */
    public static final int CHUNK_PUSH = 1 << 2;

    /** 分段增量（SectionDelta）。 */
    public static final int SECTION_DELTA = 1 << 3;

    /** 本地生成（SeedGen；服务端开启会下发种子）。 */
    public static final int SEED_GEN = 1 << 4;

    /** 光照剥离（Hassium 影子引擎统一算光）。 */
    public static final int LIGHT_STRIP = 1 << 5;

    /** ShadowPull（缓存未命中/比对不一致时的区块回源通道）。 */
    public static final int SHADOW_PULL = 1 << 6;

    /** 超视渲染（多人渲染环带扩展）。 */
    public static final int VIEW_DIST_EXT = 1 << 7;

    /** 服务端声明位（配置驱动；登录 query 发送时构建）。 */
    public static int buildServerCaps() {
        HassiumConfigService cfg = HassiumConfigService.getInstance();
        int caps = 0;
        if (cfg.isPacketAggregationEnabled()) {
            caps |= AGGREGATION;
        }
        if (cfg.isCompactHeaderEnabled()) {
            caps |= COMPACT_HEADER;
        }
        if (cfg.isClientCacheEnabled()) {
            caps |= CHUNK_PUSH;
        }
        if (cfg.isSectionDeltaEnabled()) {
            caps |= SECTION_DELTA;
        }
        if (cfg.isSeedGenEnabled()) {
            caps |= SEED_GEN;
        }
        if (cfg.isServerLightStrip()) {
            caps |= LIGHT_STRIP;
        }
        caps |= SHADOW_PULL;
        if (cfg.isViewDistanceExtensionEnabled()) {
            caps |= VIEW_DIST_EXT;
        }
        return caps;
    }

    /** 客户端声明位（配置驱动；应答 login_hello 时构建）。 */
    public static int buildClientCaps() {
        HassiumConfigService cfg = HassiumConfigService.getInstance();
        int caps = 0;
        if (cfg.isPacketAggregationEnabled()) {
            caps |= AGGREGATION;
        }
        if (cfg.isCompactHeaderEnabled()) {
            caps |= COMPACT_HEADER;
        }
        if (cfg.isClientCacheEnabled()) {
            caps |= CHUNK_PUSH;
        }
        if (cfg.isSectionDeltaEnabled()) {
            caps |= SECTION_DELTA;
        }
        if (cfg.isClientSeedGenEnabled()) {
            caps |= SEED_GEN;
        }
        if (cfg.isHassiumEngineEnabled()) {
            caps |= LIGHT_STRIP;
        }
        caps |= SHADOW_PULL;
        if (cfg.isViewDistanceExtensionEnabled()) {
            caps |= VIEW_DIST_EXT;
        }
        return caps;
    }

    /** 按位与协商。 */
    public static int negotiate(int serverCaps, int clientCaps) {
        return serverCaps & clientCaps;
    }

    public static boolean has(int caps, int bit) {
        return (caps & bit) != 0;
    }
}
