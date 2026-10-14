package io.github.limuqy.mc.hassium.protocol.handshake;

import io.github.limuqy.mc.hassium.Constants;
import io.github.limuqy.mc.hassium.client.ClientChunkPipeline;

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
        if (LoginCaps.has(payload.negotiatedCaps(), LoginCaps.AGGREGATION) && !LoginCaps.has(payload.negotiatedCaps(), LoginCaps.EGRESS_V3)) {
            var connection = net.minecraft.client.Minecraft.getInstance().getConnection();
            if (connection != null) connection.getConnection().disconnect(net.minecraft.network.chat.Component.literal(
                    "Hassium: incompatible aggregation protocol. Upgrade Hassium on both sides to protocol v3."));
            return;
        }
        ClientLoginNegotiation.set(payload.negotiatedCaps());
        io.github.limuqy.mc.hassium.client.ClientActivation.tryActivate();
        Constants.LOG.info("Hassium: play init (caps={}, seedGen={})",
                LoginHandshake.describeCaps(payload.negotiatedCaps()), payload.seedGenEnabled());

        // SeedGen 种子/LevelStem + 服务端维度清单（enabled=false 时 seed=0 且 stem 为空，不泄露）
        ClientChunkPipeline.getInstance().setServerSeedInfo(
                payload.worldSeed(), payload.stemNbt(), payload.seedGenEnabled(),
                payload.dimensionIds());
    }
}
