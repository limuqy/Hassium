package io.github.limuqy.mc.hassium.platform;

import io.github.limuqy.mc.hassium.compat.HassiumChannels;
import io.github.limuqy.mc.hassium.network.FabricNetworkManager;
import io.github.limuqy.mc.hassium.platform.services.INetworkManagerService;
import net.minecraft.client.Minecraft;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;

/**
 * Fabric 平台的网络管理器服务实现（直连拓扑）
 * <p>
 * C2S/S2C 收发经 {@link FabricSendCompat} 收口；聚合配套（Dict/Index）、Play 期激活
 * （play_init）与 compression_ready ACK 委托 {@link FabricNetworkManager} 静态实现
 * （common {@code ServerHandshakeActivation} / {@code PlayInitClient} 消费）。
 */
public class FabricNetworkManagerService implements INetworkManagerService {

    @Override
    public void sendShadowPullRequest(FriendlyByteBuf buf) {
        if (Minecraft.getInstance().getConnection() != null) {
            FabricSendCompat.sendToServer(HassiumChannels.SHADOW_PULL_REQUEST_C2S, buf);
        } else if (buf != null && buf.refCnt() > 0) {
            buf.release();
        }
    }

    @Override
    public void sendBlockEntityRequest(FriendlyByteBuf buf) {
        FabricSendCompat.sendToServer(HassiumChannels.BLOCK_ENTITY_REQUEST_C2S, buf);
    }

    @Override
    public void sendBlockEntityData(ServerPlayer player, FriendlyByteBuf buf) {
        FabricSendCompat.sendToPlayer(player, HassiumChannels.BLOCK_ENTITY_DATA_S2C, buf);
    }

    @Override
    public void sendLightDeltaPacket(ServerPlayer player, FriendlyByteBuf buf) {
        FabricSendCompat.sendToPlayer(player, HassiumChannels.LIGHT_DELTA_S2C, buf);
    }

    @Override
    public void sendDictionarySync(ServerPlayer player) {
        FabricNetworkManager.sendDictionarySync(player);
    }

    @Override
    public void sendIndexSync(ServerPlayer player) {
        FabricNetworkManager.sendIndexSync(player);
    }

    @Override
    public void sendPlayInit(ServerPlayer player, int negotiatedCaps, long worldSeed,
                             byte[] stemNbt, boolean seedGenEnabled) {
        FabricNetworkManager.sendPlayInit(player, negotiatedCaps, worldSeed, stemNbt, seedGenEnabled);
    }
}
