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
    public static final int SECTION_DELTA = 1 << 1;

    /** 本地生成（SeedGen；服务端开启会下发种子）。 */
    public static final int SEED_GEN = 1 << 2;

    /** 光照剥离（Hassium 影子引擎统一算光）。 */
    public static final int LIGHT_STRIP = 1 << 3;

    /** ShadowPull（缓存未命中/比对不一致时的区块回源通道）。 */
    public static final int SHADOW_PULL = 1 << 4;

    /**
     * Pull 模式（Compare+Pull 对齐）：协商通过后服务端对该玩家停发原版整柱推送
     * （forget/元数据照常），区块数据全部由客户端影子虚拟玩家
     * tracking 驱动的统一 Compare+Pull 拉取；pull FULL 响应走固定 zstd。
     */
    public static final int PULL_MODE = 1 << 5;

    /**
     * 服务端声明权威边沿（enter 通知 + 权威 chunkHash）。
     * <p>
     * 协商通过后服务端在整柱推送抑制点发 {@code chunk_authority_s2c}：客户端本地有基线
     * 且 hash 相同时<b>不发任何请求</b>直接本地交付（记缓存全命中）；hash 未知/不等才走
     * Compare+Pull。未协商则客户端完全不消费该载荷，走原路径。
     */
    public static final int AUTHORITY_NOTIFY = 1 << 6;

    /** 服务端声明位（配置驱动；登录 query 发送时构建）。 */
    public static int buildServerCaps() {
        HassiumConfigService cfg = HassiumConfigService.getInstance();
        int caps = 0;
        if (cfg.isPacketAggregationEnabled()) {
            caps |= AGGREGATION;
        }
        if (cfg.isClientCacheEnabled()) {
            caps |= PULL_MODE;
            // 权威边沿：三端 receiver 已注册（fabric/forge/neoforge 的 chunk_authority_s2c），
            // 未协商的客户端不受影响（不消费该载荷，走原路径）。
            caps |= AUTHORITY_NOTIFY;
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
            caps |= AUTHORITY_NOTIFY;
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
