package io.github.limuqy.mc.hassium;

import io.github.limuqy.mc.hassium.network.NetworkManager;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * NeoForge 平台网络管理器实现
 * TODO: 实现 NeoForge PayloadRegistrar API
 */
public class NeoForgeNetworkManager implements NetworkManager {

    private static final Logger LOGGER = LoggerFactory.getLogger("Hassium/NeoForgeNetwork");

    @Override
    public void registerChannels() {
        LOGGER.info("Hassium: Registering NeoForge network channels");
        // TODO: 实现 NeoForge PayloadRegistrar 网络包注册
        // NeoForge 1.20.4+ 使用 PayloadRegistrar 替代 SimpleChannel
    }

    @Override
    public void sendHandshakeRequest() {
        // TODO: 实现握手请求
    }

    @Override
    public void sendMetadataPacket(FriendlyByteBuf buf) {
        buf.release();
    }

    @Override
    public void sendChunkDataRequest(FriendlyByteBuf buf) {
        buf.release();
    }

    @Override
    public void sendCompressedPayload(io.github.limuqy.mc.hassium.network.CompressedPayloadPacket packet) {
        throw new UnsupportedOperationException("Not implemented");
    }

    @Override
    public void sendChunkHashPacket(ServerPlayer player, FriendlyByteBuf buf) {
        buf.release();
    }

    @Override
    public void sendSectionHashRequest(FriendlyByteBuf buf) {
        buf.release();
    }

    @Override
    public void sendSectionDeltaPacket(ServerPlayer player, FriendlyByteBuf buf) {
        buf.release();
    }

    @Override
    public void sendBlockEntityRequest(FriendlyByteBuf buf) {
        buf.release();
    }

    @Override
    public void sendBlockEntityData(ServerPlayer player, FriendlyByteBuf buf) {
        buf.release();
    }
}
