package io.github.limuqy.mc.hassium.network.handshake;

import io.github.limuqy.mc.hassium.Constants;
import io.github.limuqy.mc.hassium.network.ClientChunkPipeline;

/**
 * Play 期激活（客户端）：处理服务端 {@code play_init_s2c}（协商结果 + SeedGen 种子）。
 * <p>
 * 只做两件事：登记协商位 + 注册 SeedGen 种子/LevelStem。原版压缩层全程不触碰
 * （管线级 ZSTD 已退役，见 {@code docs/architecture.md}）；聚合由 index_sync 后的
 * 激活 ACK（{@code aggregation_ready} 通道，语义=聚合确认）放行。
 * <p>
 * 本类为 loader receiver 的统一转调点（三端注册 {@code hassium:play_init_s2c}）。
 */
public final class PlayInitClient {

    private PlayInitClient() {
    }

    public static void handle(LoginHandshake.PlayInitPayload payload) {
        ClientLoginNegotiation.set(payload.negotiatedCaps());
        Constants.LOG.info("Hassium: play init (caps={}, seedGen={})",
                LoginHandshake.describeCaps(payload.negotiatedCaps()), payload.seedGenEnabled());

        // SeedGen 种子/LevelStem（enabled=false 时 seed=0 且 stem 为空，不泄露）
        ClientChunkPipeline.getInstance().setServerSeedInfo(
                payload.worldSeed(), payload.stemNbt(), payload.seedGenEnabled());
    }
}
