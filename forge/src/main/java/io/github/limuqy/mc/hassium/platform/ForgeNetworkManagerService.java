package io.github.limuqy.mc.hassium.platform;

import io.github.limuqy.mc.hassium.network.ForgeNetworkManager;
import io.github.limuqy.mc.hassium.platform.services.INetworkManagerService;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;

#if MC_VER < MC_1_20_2
import net.minecraftforge.network.NetworkDirection;
#endif

/**
 * Forge 平台的网络管理器服务实现
 */
public class ForgeNetworkManagerService implements INetworkManagerService {

    private static final ForgeNetworkManager NETWORK_MANAGER = new ForgeNetworkManager();

    @Override
    public void sendMetadataPacket(ServerPlayer player, FriendlyByteBuf buf) {
        byte[] data = new byte[buf.readableBytes()];
        buf.readBytes(data);
        buf.release();
#if MC_VER < MC_1_20_2
        ForgeNetworkManager.CHANNEL.sendTo(
                new ForgeNetworkManager.MetadataWrapper(data),
                player.connection.connection,
                NetworkDirection.PLAY_TO_CLIENT
        );
#else
        ForgeNetworkManager.sendMetadataToPlayer(player, data);
#endif
    }

    @Override
    public void sendChunkDataRequest(FriendlyByteBuf buf) {
        NETWORK_MANAGER.sendChunkDataRequest(buf);
    }

    @Override
    public void sendChunkHashPacket(ServerPlayer player, FriendlyByteBuf buf) {
        NETWORK_MANAGER.sendChunkHashPacket(player, buf);
    }

    @Override
    public void sendSectionHashRequest(FriendlyByteBuf buf) {
        NETWORK_MANAGER.sendSectionHashRequest(buf);
    }

    @Override
    public void sendSectionDeltaPacket(ServerPlayer player, FriendlyByteBuf buf) {
        NETWORK_MANAGER.sendSectionDeltaPacket(player, buf);
    }

    @Override
    public void sendBlockEntityRequest(FriendlyByteBuf buf) {
        NETWORK_MANAGER.sendBlockEntityRequest(buf);
    }

    @Override
    public void sendBlockEntityData(ServerPlayer player, FriendlyByteBuf buf) {
        NETWORK_MANAGER.sendBlockEntityData(player, buf);
    }
}
