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

    /** 分段增量（SectionDelta）。 */
    public static final int SECTION_DELTA = 1 << 3;

    /** 本地生成（SeedGen；服务端开启会下发种子）。 */
    public static final int SEED_GEN = 1 << 4;

    /** 光照剥离（Hassium 影子引擎统一算光）。 */
    public static final int LIGHT_STRIP = 1 << 5;

    /** ShadowPull（缓存未命中/比对不一致时的区块回源通道）。 */
    public static final int SHADOW_PULL = 1 << 6;

    /**
     * Pull 模式（Compare+Pull 对齐）：协商通过后服务端对该玩家停发 chunk_payload
     * 整柱推送（forget/元数据/SeedRef 照常），区块数据全部由客户端影子虚拟玩家
     * tracking 驱动的统一 Compare+Pull 拉取；pull FULL 响应走固定 zstd。
     */
    public static final int PULL_MODE = 1 << 8;

    /** 服务端声明位（配置驱动；登录 query 发送时构建）。 */
    public static int buildServerCaps() {
        HassiumConfigService cfg = HassiumConfigService.getInstance();
        int caps = 0;
        if (cfg.isPacketAggregationEnabled()) {
            caps |= AGGREGATION;
        }
        if (cfg.isClientCacheEnabled()) {
            caps |= PULL_MODE;
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
        return caps;
    }

    /** 客户端声明位（配置驱动；应答 login_hello 时构建）。 */
    public static int buildClientCaps() {
        HassiumConfigService cfg = HassiumConfigService.getInstance();
        int caps = 0;
        if (cfg.isPacketAggregationEnabled()) {
            caps |= AGGREGATION;
        }
        if (cfg.isSectionDeltaEnabled()) {
            caps |= SECTION_DELTA;
        }
        if (cfg.isClientSeedGenEnabled()) {
            caps |= SEED_GEN;
        }
        if (cfg.isHassiumEngineEnabled()) {
            caps |= LIGHT_STRIP;
            // 影子虚拟玩家 tracking 在位（引擎开启）才声明 pull 模式：
            // 停推后区块数据完全依赖客户端 Compare+Pull 采集
            caps |= PULL_MODE;
        }
        caps |= SHADOW_PULL;
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
