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
    public void sendChunkDataRequest(FriendlyByteBuf buf) {
        NETWORK_MANAGER.sendChunkDataRequest(buf);
    }

    @Override
    public void sendChunkHashPacket(ServerPlayer player, FriendlyByteBuf buf) {
        // T12 网关收口：网关玩家走 kind=1 HASSIUM 帧（客户端 receiver 已退役，回落 CustomPayload 是死路径）
        if (io.github.limuqy.mc.hassium.server.GatewayPlayerBridge.tryRouteS2C(
                player, io.github.limuqy.mc.hassium.network.core.GatewayPacketCodec.HassiumSub.CHUNK_HASH.id(), buf)) {
            return;
        }
        NETWORK_MANAGER.sendChunkHashPacket(player, buf);
    }

    @Override
    public void sendSeedRef(ServerPlayer player, FriendlyByteBuf buf) {
        if (io.github.limuqy.mc.hassium.server.GatewayPlayerBridge.tryRouteS2C(
                player, io.github.limuqy.mc.hassium.network.core.GatewayPacketCodec.HassiumSub.SEED_REF.id(), buf)) {
            return;
        }
        NETWORK_MANAGER.sendSeedRef(player, buf);
    }

    @Override
    public void sendSectionHashRequest(FriendlyByteBuf buf) {
        NETWORK_MANAGER.sendSectionHashRequest(buf);
    }

    @Override
    public void sendSectionDeltaPacket(ServerPlayer player, FriendlyByteBuf buf) {
        if (io.github.limuqy.mc.hassium.server.GatewayPlayerBridge.tryRouteS2C(
                player, io.github.limuqy.mc.hassium.network.core.GatewayPacketCodec.HassiumSub.SECTION_DELTA.id(), buf)) {
            return;
        }
        NETWORK_MANAGER.sendSectionDeltaPacket(player, buf);
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
