package io.github.limuqy.mc.hassium.cache.client;

import io.github.limuqy.mc.hassium.config.HassiumConfigService;
import io.github.limuqy.mc.hassium.metrics.NetworkStats;

/**
 * Promethium 光照引擎的 Hassium 侧配置 / 指标绑定。
 * <p>
 * 引擎每次读取（而非缓存副本），Hassium 配置热更即时生效。
 * 引擎接口（LightEngineConfig / LightEngineStats）在 Promethium MOD 内，Hassium 无编译
 * 依赖——本类不 implements 接口，由 {@link PromethiumLightBridge} 经反射 Proxy 包装注入。
 */
public final class HassiumLightBindings {

    public static final HassiumLightBindings INSTANCE = new HassiumLightBindings();

    private HassiumLightBindings() {}

    public int lightThreads() {
        return HassiumConfigService.getInstance().getParallelLightEngineThreads();
    }

    public boolean lightVerifyEnabled() {
        return HassiumConfigService.getInstance().isLightVerifyEnabled();
    }

    public boolean lightCacheEnabled() {
        return HassiumConfigService.getInstance().isLightCacheEnabled();
    }

    public void recordBackgroundTime(long nanos) {
        NetworkStats.recordLightRecomputeBackgroundTime(nanos);
    }

    public void recordMainThreadTime(long nanos) {
        NetworkStats.recordLightRecomputeTime(nanos);
    }

    public void recordVerifyMismatch(long count) {
        NetworkStats.recordLightVerifyMismatch(count);
    }
}
