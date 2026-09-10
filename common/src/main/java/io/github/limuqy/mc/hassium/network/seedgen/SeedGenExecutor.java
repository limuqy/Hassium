package io.github.limuqy.mc.hassium.network.seedgen;

import io.github.limuqy.mc.hassium.config.HassiumConfigService;
import io.github.limuqy.mc.hassium.network.ClientChunkPipeline;

/**
 * SeedGen 本地生成门控（SeedRef 推送路径已退役）。
 * <p>
 * 纯 Compare+Pull 下本地生成由影子 tracking 触发 vanilla worldgen：
 * 门控开时 {@code MixinChunkMap} 不压制生成；交付经
 * {@code ShadowTrackingSession.onChunkMaterialized} →
 * {@link ShadowLightCompute#publishCachedChunk}（LOCAL_GENERATION）。
 * 服务端 SeedRef / PristineRegistry / SeedGen 工作队列均已删除。
 */
public final class SeedGenExecutor {

    private static final SeedGenExecutor INSTANCE = new SeedGenExecutor();

    private SeedGenExecutor() {}

    public static SeedGenExecutor getInstance() {
        return INSTANCE;
    }

    /** 门控：客户端本地生成开启、服务端 SeedGen 开启、真实 seed 已到达、影子端未失败。 */
    private boolean isEnabled() {
        if (ShadowServerRegistry.getInstance().isFailed()) {
            return false;
        }
        if (!HassiumConfigService.getInstance().isClientSeedGenEnabled()) {
            return false;
        }
        ClientChunkPipeline pipeline = ClientChunkPipeline.getInstance();
        return pipeline.isServerSeedGenEnabled() && pipeline.isServerSeedAvailable();
    }

    /**
     * §6 本地生成硬门控：影子虚拟玩家 tracking 是否触发原版生成链
     * （客户端本地生成开启 + 服务端 SeedGen 开启 + 真实 seed 已到达）。
     */
    public boolean isGenerationGateOpen() {
        return isEnabled();
    }
}
