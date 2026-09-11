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

    private final NeoForgeNetworkManager networkManager = new NeoForgeNetworkManager();

    @Override
    public void sendShadowPullRequest(FriendlyByteBuf buf) {
        networkManager.sendShadowPullRequest(buf);
    }

    @Override
    public void sendShadowPullResponse(ServerPlayer player, FriendlyByteBuf buf) {
        networkManager.sendShadowPullResponse(player, buf);
    }

    @Override
    public void sendBlockEntityRequest(FriendlyByteBuf buf) {
        networkManager.sendBlockEntityRequest(buf);
    }

    @Override
    public void sendBlockEntityData(ServerPlayer player, FriendlyByteBuf buf) {
        networkManager.sendBlockEntityData(player, buf);
    }

    @Override
    public void sendLightDeltaPacket(ServerPlayer player, FriendlyByteBuf buf) {
        networkManager.sendLightDeltaPacket(player, buf);
    }

    @Override
    public void sendDictionarySync(ServerPlayer player) {
        NeoForgeNetworkManager.sendDictionarySyncPacket(player);
    }

    @Override
    public void sendIndexSync(ServerPlayer player) {
        NeoForgeNetworkManager.sendIndexSyncPacket(player);
    }

    @Override
    public void sendPlayInit(ServerPlayer player, int negotiatedCaps, long worldSeed,
                             byte[] stemNbt, boolean seedGenEnabled) {
        NeoForgeNetworkManager.sendPlayInit(player, negotiatedCaps, worldSeed, stemNbt, seedGenEnabled);
    }
}

