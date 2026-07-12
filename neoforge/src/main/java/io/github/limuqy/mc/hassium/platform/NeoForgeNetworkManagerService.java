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
    public void sendMetadataPacket(ServerPlayer player, FriendlyByteBuf buf) {
        try {
            byte[] data = new byte[buf.readableBytes()];
            buf.readBytes(data);
            buf.release();

#if MC_VER < MC_1_20_2
            // 1.20.1 使用 SimpleChannel
            net.minecraftforge.network.NetworkDirection direction = 
                net.minecraftforge.network.NetworkDirection.PLAY_TO_CLIENT;
            NeoForgeNetworkManager.CHANNEL.sendTo(
                new NeoForgeNetworkManager.ChunkHashWrapper(data),
                player.connection.connection,
                direction
            );
#else
            // 1.20.2+ 使用 Payload API
            io.github.limuqy.mc.hassium.network.NeoForgeNetworkManager.ChunkMetadataPayload payload = 
                new io.github.limuqy.mc.hassium.network.NeoForgeNetworkManager.ChunkMetadataPayload(data);
            player.connection.send(payload);
#endif
            LOGGER.debug("Sent metadata packet to {}", player.getName().getString());
        } catch (Exception e) {
            LOGGER.error("Failed to send metadata packet to {}", player.getName().getString(), e);
        }
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

    /**
     * 获取底层的 NetworkManager 实例
     */
    public NeoForgeNetworkManager getNetworkManager() {
        return networkManager;
    }
}
