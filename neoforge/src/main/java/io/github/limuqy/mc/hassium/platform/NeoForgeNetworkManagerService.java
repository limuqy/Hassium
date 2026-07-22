package io.github.limuqy.mc.hassium.platform;

import io.github.limuqy.mc.hassium.network.NeoForgeNetworkManager;
import io.github.limuqy.mc.hassium.platform.services.INetworkManagerService;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * NeoForge 平台的网络管理器服务实现
 * 委托给 NeoForgeNetworkManager 处理实际网络操作
 */
public class NeoForgeNetworkManagerService implements INetworkManagerService {

    private static final Logger LOGGER = LoggerFactory.getLogger("Hassium/NeoForgeNetworkService");

    private final NeoForgeNetworkManager networkManager;

    public NeoForgeNetworkManagerService() {
        this.networkManager = new NeoForgeNetworkManager();
    }

    @Override
    public void sendChunkDataRequest(FriendlyByteBuf buf) {
        networkManager.sendChunkDataRequest(buf);
    }

    @Override
    public void sendChunkHashPacket(ServerPlayer player, FriendlyByteBuf buf) {
        networkManager.sendChunkHashPacket(player, buf);
    }

    @Override
    public void sendSectionHashRequest(FriendlyByteBuf buf) {
        networkManager.sendSectionHashRequest(buf);
    }

    @Override
    public void sendSectionDeltaPacket(ServerPlayer player, FriendlyByteBuf buf) {
        networkManager.sendSectionDeltaPacket(player, buf);
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

    /**
     * 获取底层的 NetworkManager 实例
     */
    public NeoForgeNetworkManager getNetworkManager() {
        return networkManager;
    }
}
