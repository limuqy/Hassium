package io.github.limuqy.mc.hassium.platform;

import io.github.limuqy.mc.hassium.compat.HassiumChannels;
import io.github.limuqy.mc.hassium.network.FabricNetworkManager;
import io.github.limuqy.mc.hassium.platform.services.INetworkManagerService;
import net.minecraft.client.Minecraft;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;

/**
 * Fabric 平台的网络管理器服务实现
 */
public class FabricNetworkManagerService implements INetworkManagerService {

    private static final FabricNetworkManager NETWORK_MANAGER = new FabricNetworkManager();


    @Override
    public void sendShadowPullRequest(FriendlyByteBuf buf) {
        if (Minecraft.getInstance().getConnection() != null) {
            FabricSendCompat.sendToServer(HassiumChannels.SHADOW_PULL_REQUEST_C2S, buf);
        } else if (buf != null && buf.refCnt() > 0) {
            buf.release();
        }
    }

    @Override
    public void sendSeedRef(ServerPlayer player, FriendlyByteBuf buf) {
        if (io.github.limuqy.mc.hassium.server.GatewayPlayerBridge.tryRouteS2C(
                player, io.github.limuqy.mc.hassium.network.core.GatewayPacketCodec.HassiumSub.SEED_REF.id(), buf)) {
            return;
        }
        FabricSendCompat.sendToPlayer(player, HassiumChannels.SEED_REF_S2C, buf);
    }

    @Override
    public void sendSectionHashRequest(FriendlyByteBuf buf) {
        FabricSendCompat.sendToServer(HassiumChannels.SECTION_HASH_REQUEST_C2S, buf);
    }

    @Override
    public void sendSectionDeltaPacket(ServerPlayer player, FriendlyByteBuf buf) {
        if (io.github.limuqy.mc.hassium.server.GatewayPlayerBridge.tryRouteS2C(
                player, io.github.limuqy.mc.hassium.network.core.GatewayPacketCodec.HassiumSub.SECTION_DELTA.id(), buf)) {
            return;
        }
        FabricSendCompat.sendToPlayer(player, HassiumChannels.SECTION_DELTA_S2C, buf);
    }

    @Override
    public void sendBlockEntityRequest(FriendlyByteBuf buf) {
        FabricSendCompat.sendToServer(HassiumChannels.BLOCK_ENTITY_REQUEST_C2S, buf);
    }

    @Override
    public void sendBlockEntityData(ServerPlayer player, FriendlyByteBuf buf) {
        if (io.github.limuqy.mc.hassium.server.GatewayPlayerBridge.tryRouteS2C(
                player, io.github.limuqy.mc.hassium.network.core.GatewayPacketCodec.HassiumSub.BLOCK_ENTITY_DATA.id(), buf)) {
            return;
        }
        FabricSendCompat.sendToPlayer(player, HassiumChannels.BLOCK_ENTITY_DATA_S2C, buf);
    }

    @Override
    public void sendLightDeltaPacket(ServerPlayer player, FriendlyByteBuf buf) {
        if (io.github.limuqy.mc.hassium.server.GatewayPlayerBridge.tryRouteS2C(
                player, io.github.limuqy.mc.hassium.network.core.GatewayPacketCodec.HassiumSub.LIGHT_DELTA.id(), buf)) {
            return;
        }
        buf.release();
    }
}
