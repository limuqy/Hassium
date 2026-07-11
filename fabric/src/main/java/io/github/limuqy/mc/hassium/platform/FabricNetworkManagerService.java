package io.github.limuqy.mc.hassium.platform;

import io.github.limuqy.mc.hassium.network.ChunkDataRequestC2SPacket;
import io.github.limuqy.mc.hassium.network.ChunkHashS2CPacket;
import io.github.limuqy.mc.hassium.network.ChunkMetadataS2CPacket;
import io.github.limuqy.mc.hassium.network.FabricNetworkManager;
import io.github.limuqy.mc.hassium.network.SectionDeltaS2CPacket;
import io.github.limuqy.mc.hassium.network.BlockEntityDataS2CPacket;
import io.github.limuqy.mc.hassium.network.BlockEntityRequestC2SPacket;
import io.github.limuqy.mc.hassium.network.SectionHashRequestC2SPacket;
import io.github.limuqy.mc.hassium.platform.services.INetworkManagerService;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Fabric 平台的网络管理器服务实现
 */
public class FabricNetworkManagerService implements INetworkManagerService {

    private static final Logger LOGGER = LoggerFactory.getLogger("Hassium/Network");

    private static final FabricNetworkManager NETWORK_MANAGER = new FabricNetworkManager();

    @Override
    public void sendMetadataPacket(ServerPlayer player, FriendlyByteBuf buf) {
        // ServerPlayNetworking.send 会接管 buf 的所有权并负责释放
        // 不需要手动释放
#if MC_VER < MC_1_20_6
        ServerPlayNetworking.send(player, ChunkMetadataS2CPacket.CHANNEL, buf);
#else
        LOGGER.warn("Fabric networking send not supported on 1.20.6+, dropping packet");
        buf.release();
#endif
    }

    @Override
    public void sendChunkDataRequest(FriendlyByteBuf buf) {
#if MC_VER < MC_1_20_6
        ClientPlayNetworking.send(ChunkDataRequestC2SPacket.CHANNEL, buf);
#else
        LOGGER.warn("Fabric networking send not supported on 1.20.6+, dropping packet");
        buf.release();
#endif
    }

    @Override
    public void sendChunkHashPacket(ServerPlayer player, FriendlyByteBuf buf) {
#if MC_VER < MC_1_20_6
        ServerPlayNetworking.send(player, ChunkHashS2CPacket.CHANNEL, buf);
#else
        LOGGER.warn("Fabric networking send not supported on 1.20.6+, dropping packet");
        buf.release();
#endif
    }

    @Override
    public void sendSectionHashRequest(FriendlyByteBuf buf) {
#if MC_VER < MC_1_20_6
        ClientPlayNetworking.send(SectionHashRequestC2SPacket.CHANNEL, buf);
#else
        LOGGER.warn("Fabric networking send not supported on 1.20.6+, dropping packet");
        buf.release();
#endif
    }

    @Override
    public void sendSectionDeltaPacket(ServerPlayer player, FriendlyByteBuf buf) {
#if MC_VER < MC_1_20_6
        ServerPlayNetworking.send(player, SectionDeltaS2CPacket.CHANNEL, buf);
#else
        LOGGER.warn("Fabric networking send not supported on 1.20.6+, dropping packet");
        buf.release();
#endif
    }

    @Override
    public void sendBlockEntityRequest(FriendlyByteBuf buf) {
#if MC_VER < MC_1_20_6
        ClientPlayNetworking.send(BlockEntityRequestC2SPacket.CHANNEL, buf);
#else
        LOGGER.warn("Fabric networking send not supported on 1.20.6+, dropping packet");
        buf.release();
#endif
    }

    @Override
    public void sendBlockEntityData(ServerPlayer player, FriendlyByteBuf buf) {
#if MC_VER < MC_1_20_6
        ServerPlayNetworking.send(player, BlockEntityDataS2CPacket.CHANNEL, buf);
#else
        LOGGER.warn("Fabric networking send not supported on 1.20.6+, dropping packet");
        buf.release();
#endif
    }
}
