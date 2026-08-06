package io.github.limuqy.mc.hassium.cache.client;

import io.github.limuqy.mc.hassium.config.HassiumConfigService;
import io.github.limuqy.mc.hassium.metrics.NetworkStats;
import io.github.limuqy.mc.promethium.light.LightEngineConfig;
import io.github.limuqy.mc.promethium.light.LightEngineStats;

/**
 * Promethium 光照引擎的 Hassium 侧配置 / 指标绑定。
 * <p>
 * 引擎每次读取（而非缓存副本），Hassium 配置热更即时生效。
 */
public final class HassiumLightBindings implements LightEngineConfig, LightEngineStats {

    public static final HassiumLightBindings INSTANCE = new HassiumLightBindings();

    private HassiumLightBindings() {}

    @Override
    public int lightThreads() {
        return HassiumConfigService.getInstance().getParallelLightEngineThreads();
    }

    @Override
    public boolean lightVerifyEnabled() {
        return HassiumConfigService.getInstance().isLightVerifyEnabled();
    }

    @Override
    public boolean lightCacheEnabled() {
        return HassiumConfigService.getInstance().isLightCacheEnabled();
    }

    @Override
    public void recordBackgroundTime(long nanos) {
        NetworkStats.recordLightRecomputeBackgroundTime(nanos);
    }

    @Override
    public void recordMainThreadTime(long nanos) {
        NetworkStats.recordLightRecomputeTime(nanos);
    }

    @Override
    public void recordVerifyMismatch(long count) {
        NetworkStats.recordLightVerifyMismatch(count);
    }
}
