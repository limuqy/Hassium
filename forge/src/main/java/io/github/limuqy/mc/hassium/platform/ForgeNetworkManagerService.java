package io.github.limuqy.mc.hassium.platform;

import io.github.limuqy.mc.hassium.network.ForgeNetworkManager;
import io.github.limuqy.mc.hassium.platform.services.INetworkManagerService;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;

/**
 * Forge 平台的网络管理器服务实现
 */
public class ForgeNetworkManagerService implements INetworkManagerService {

    private static final ForgeNetworkManager NETWORK_MANAGER = new ForgeNetworkManager();


    @Override
    public void sendSeedRef(ServerPlayer player, FriendlyByteBuf buf) {
        if (io.github.limuqy.mc.hassium.server.GatewayPlayerBridge.tryRouteS2C(
                player, io.github.limuqy.mc.hassium.network.core.GatewayPacketCodec.HassiumSub.SEED_REF.id(), buf)) {
            return;
        }
        NETWORK_MANAGER.sendSeedRef(player, buf);
    }

    @Override
    public void sendClientHandshake(io.github.limuqy.mc.hassium.network.ClientHandshakeRequest request) {
        ForgeNetworkManager.sendClientHandshake(request);
    }


    @Override
    public void sendBlockEntityRequest(FriendlyByteBuf buf) {
        NETWORK_MANAGER.sendBlockEntityRequest(buf);
    }

    @Override
    public void sendBlockEntityData(ServerPlayer player, FriendlyByteBuf buf) {
        if (io.github.limuqy.mc.hassium.server.GatewayPlayerBridge.tryRouteS2C(
                player, io.github.limuqy.mc.hassium.network.core.GatewayPacketCodec.HassiumSub.BLOCK_ENTITY_DATA.id(), buf)) {
            return;
        }
        NETWORK_MANAGER.sendBlockEntityData(player, buf);
    }

    @Override
    public void sendLightDeltaPacket(ServerPlayer player, FriendlyByteBuf buf) {
        if (io.github.limuqy.mc.hassium.server.GatewayPlayerBridge.tryRouteS2C(
                player, io.github.limuqy.mc.hassium.network.core.GatewayPacketCodec.HassiumSub.LIGHT_DELTA.id(), buf)) {
            return;
        }
        NETWORK_MANAGER.sendLightDeltaPacket(player, buf);
    }
}
