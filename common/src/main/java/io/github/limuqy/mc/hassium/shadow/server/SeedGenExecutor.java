package io.github.limuqy.mc.hassium.shadow.server;

import io.github.limuqy.mc.hassium.config.HassiumConfigService;
import io.github.limuqy.mc.hassium.network.ClientChunkPipeline;

/**
 * SeedGen 本地生成门控（SeedRef 推送路径已退役）。
 * <p>
 * 纯 Compare+Pull 下本地生成由影子 tracking 触发 vanilla worldgen：
 * 门控开时 {@code MixinChunkMap} 不压制生成。1.20.1 产出经 {@code playerLoadedChunk}；
 * 1.21+ 经 {@code ChunkMap.onChunkReadyToSend}（该版已无 playerLoadedChunk，
 * 且影子主循环不跑 {@code MinecraftServer} 的 send-chunks 泵）。
 * 权威窗内先 compare-pull，UNCHANGED / DELTA / FULL 落地后再算光交付——
 * 避免权威微变导致二次算光。有磁盘/注入基线时禁止再 worldgen。
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

    /** 首物化且无盘 hash：本会话 vanilla worldgen（非读盘、非网络注入）。 */
    public static boolean isFreshLocalWorldgen(boolean alreadyMaterialized, boolean diskHit) {
        return !alreadyMaterialized && !diskHit;
    }

    /** 新柱必须标 dirty 落盘；磁盘命中不得重写。没落盘就是缓存基线丢失。 */
    public static boolean persistAsDirty(boolean diskHit) {
        return !diskHit;
    }

    /**
     * 权威窗内的本地生成：先比对权威，再算光交付。
     * OVD / 磁盘命中不走这条（OVD 无权威；盘上柱直接复用后 compare）。
     */
    public static boolean deferLightUntilAuthority(boolean freshLocalWorldgen,
                                                   boolean inVanillaVisibleShape) {
        return freshLocalWorldgen && inVanillaVisibleShape;
    }
}
