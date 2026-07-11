package io.github.limuqy.mc.hassium.platform;

import io.github.limuqy.mc.hassium.platform.services.INetworkManagerService;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * NeoForge 平台的网络管理器服务实现
 * TODO: 实现 NeoForge 网络 API
 */
public class NeoForgeNetworkManagerService implements INetworkManagerService {

    private static final Logger LOGGER = LoggerFactory.getLogger("Hassium/NeoForgeNetworkService");

    @Override
    public void sendMetadataPacket(ServerPlayer player, FriendlyByteBuf buf) {
        // TODO: 实现 NeoForge PacketDistributor 发送
        buf.release();
    }

    @Override
    public void sendChunkDataRequest(FriendlyByteBuf buf) {
        // TODO: 实现 NeoForge 发送
        buf.release();
    }

    @Override
    public void sendChunkHashPacket(ServerPlayer player, FriendlyByteBuf buf) {
        // TODO: 实现 NeoForge 发送
        buf.release();
    }

    @Override
    public void sendSectionHashRequest(FriendlyByteBuf buf) {
        // TODO: 实现 NeoForge 发送
        buf.release();
    }

    @Override
    public void sendSectionDeltaPacket(ServerPlayer player, FriendlyByteBuf buf) {
        // TODO: 实现 NeoForge 发送
        buf.release();
    }

    @Override
    public void sendBlockEntityRequest(FriendlyByteBuf buf) {
        // TODO: 实现 NeoForge 发送
        buf.release();
    }

    @Override
    public void sendBlockEntityData(ServerPlayer player, FriendlyByteBuf buf) {
        // TODO: 实现 NeoForge 发送
        buf.release();
    }
}
