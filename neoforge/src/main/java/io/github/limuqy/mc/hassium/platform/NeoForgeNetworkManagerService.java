package io.github.limuqy.mc.hassium.platform;

import io.github.limuqy.mc.hassium.network.NeoForgeNetworkManager;
import io.github.limuqy.mc.hassium.platform.services.INetworkManagerService;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;

/**
 * NeoForge 平台的网络管理器服务实现
 * 委托给 NeoForgeNetworkManager 处理实际网络操作
 */
public class NeoForgeNetworkManagerService implements INetworkManagerService {

    private final NeoForgeNetworkManager networkManager;

    public NeoForgeNetworkManagerService() {
        this.networkManager = new NeoForgeNetworkManager();
    }


    @Override
    public void sendGatewayInfo(ServerPlayer player, byte[] data) {
        networkManager.sendGatewayInfo(player, data);
    }

    @Override
    public void sendSeedRef(ServerPlayer player, FriendlyByteBuf buf) {
        if (io.github.limuqy.mc.hassium.server.GatewayPlayerBridge.tryRouteS2C(
                player, io.github.limuqy.mc.hassium.network.core.GatewayPacketCodec.HassiumSub.SEED_REF.id(), buf)) {
            return;
        }
        networkManager.sendSeedRef(player, buf);
    }

    @Override
    public void sendSectionHashRequest(FriendlyByteBuf buf) {
        networkManager.sendSectionHashRequest(buf);
    }

    @Override
    public void sendSectionDeltaPacket(ServerPlayer player, FriendlyByteBuf buf) {
        if (io.github.limuqy.mc.hassium.server.GatewayPlayerBridge.tryRouteS2C(
                player, io.github.limuqy.mc.hassium.network.core.GatewayPacketCodec.HassiumSub.SECTION_DELTA.id(), buf)) {
            return;
        }
        networkManager.sendSectionDeltaPacket(player, buf);
    }

    @Override
    public void sendBlockEntityRequest(FriendlyByteBuf buf) {
        networkManager.sendBlockEntityRequest(buf);
    }

    @Override
    public void sendBlockEntityData(ServerPlayer player, FriendlyByteBuf buf) {
        if (io.github.limuqy.mc.hassium.server.GatewayPlayerBridge.tryRouteS2C(
                player, io.github.limuqy.mc.hassium.network.core.GatewayPacketCodec.HassiumSub.BLOCK_ENTITY_DATA.id(), buf)) {
            return;
        }
        networkManager.sendBlockEntityData(player, buf);
    }

    @Override
    public void sendLightDeltaPacket(ServerPlayer player, FriendlyByteBuf buf) {
        if (io.github.limuqy.mc.hassium.server.GatewayPlayerBridge.tryRouteS2C(
                player, io.github.limuqy.mc.hassium.network.core.GatewayPacketCodec.HassiumSub.LIGHT_DELTA.id(), buf)) {
            return;
        }
        networkManager.sendLightDeltaPacket(player, buf);
    }
}
