package io.github.limuqy.mc.hassium.platform;

import io.github.limuqy.mc.hassium.network.ChunkDataRequestC2SPacket;
import io.github.limuqy.mc.hassium.network.ChunkHashS2CPacket;
import io.github.limuqy.mc.hassium.network.ChunkMetadataS2CPacket;
import io.github.limuqy.mc.hassium.network.ForgeNetworkManager;
import io.github.limuqy.mc.hassium.network.SectionDeltaS2CPacket;
import io.github.limuqy.mc.hassium.network.BlockEntityDataS2CPacket;
import io.github.limuqy.mc.hassium.network.BlockEntityRequestC2SPacket;
import io.github.limuqy.mc.hassium.network.SectionHashRequestC2SPacket;
import io.github.limuqy.mc.hassium.platform.services.INetworkManagerService;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

#if MC_VER < MC_1_20_6
import net.minecraftforge.network.NetworkDirection;
#endif

/**
 * Forge 平台的网络管理器服务实现
 */
public class ForgeNetworkManagerService implements INetworkManagerService {

    private static final Logger LOGGER = LoggerFactory.getLogger("Hassium/Network");

    private static final ForgeNetworkManager NETWORK_MANAGER = new ForgeNetworkManager();

    @Override
    public void sendMetadataPacket(ServerPlayer player, FriendlyByteBuf buf) {
        byte[] data = new byte[buf.readableBytes()];
        buf.readBytes(data);
        buf.release();
#if MC_VER < MC_1_20_6
        ForgeNetworkManager.CHANNEL.sendTo(
                new ForgeNetworkManager.MetadataWrapper(data),
                player.connection.connection,
                NetworkDirection.PLAY_TO_CLIENT
        );
#else
        LOGGER.warn("Hassium: Forge networking not supported on 1.20.6+, dropping metadata packet");
#endif
    }

    @Override
    public void sendChunkDataRequest(FriendlyByteBuf buf) {
        byte[] data = new byte[buf.readableBytes()];
        buf.readBytes(data);
        buf.release();
#if MC_VER < MC_1_20_6
        ForgeNetworkManager.CHANNEL.sendToServer(new ForgeNetworkManager.DataRequestWrapper(data));
#else
        LOGGER.warn("Hassium: Forge networking not supported on 1.20.6+, dropping chunk data request");
#endif
    }

    @Override
    public void sendChunkHashPacket(ServerPlayer player, FriendlyByteBuf buf) {
        byte[] data = new byte[buf.readableBytes()];
        buf.readBytes(data);
        buf.release();
#if MC_VER < MC_1_20_6
        ForgeNetworkManager.CHANNEL.sendTo(
                new ForgeNetworkManager.ChunkHashWrapper(data),
                player.connection.connection,
                NetworkDirection.PLAY_TO_CLIENT
        );
#else
        LOGGER.warn("Hassium: Forge networking not supported on 1.20.6+, dropping chunk hash packet");
#endif
    }

    @Override
    public void sendSectionHashRequest(FriendlyByteBuf buf) {
        byte[] data = new byte[buf.readableBytes()];
        buf.readBytes(data);
        buf.release();
#if MC_VER < MC_1_20_6
        ForgeNetworkManager.CHANNEL.sendToServer(new ForgeNetworkManager.SectionHashRequestWrapper(data));
#else
        LOGGER.warn("Hassium: Forge networking not supported on 1.20.6+, dropping section hash request");
#endif
    }

    @Override
    public void sendSectionDeltaPacket(ServerPlayer player, FriendlyByteBuf buf) {
        byte[] data = new byte[buf.readableBytes()];
        buf.readBytes(data);
        buf.release();
#if MC_VER < MC_1_20_6
        ForgeNetworkManager.CHANNEL.sendTo(
                new ForgeNetworkManager.SectionDeltaWrapper(data),
                player.connection.connection,
                NetworkDirection.PLAY_TO_CLIENT
        );
#else
        LOGGER.warn("Hassium: Forge networking not supported on 1.20.6+, dropping section delta packet");
#endif
    }

    @Override
    public void sendBlockEntityRequest(FriendlyByteBuf buf) {
        byte[] data = new byte[buf.readableBytes()];
        buf.readBytes(data);
        buf.release();
#if MC_VER < MC_1_20_6
        ForgeNetworkManager.CHANNEL.sendToServer(new ForgeNetworkManager.BlockEntityRequestWrapper(data));
#else
        LOGGER.warn("Hassium: Forge networking not supported on 1.20.6+, dropping block entity request");
#endif
    }

    @Override
    public void sendBlockEntityData(ServerPlayer player, FriendlyByteBuf buf) {
        byte[] data = new byte[buf.readableBytes()];
        buf.readBytes(data);
        buf.release();
#if MC_VER < MC_1_20_6
        ForgeNetworkManager.CHANNEL.sendTo(
                new ForgeNetworkManager.BlockEntityDataWrapper(data),
                player.connection.connection,
                NetworkDirection.PLAY_TO_CLIENT
        );
#else
        LOGGER.warn("Hassium: Forge networking not supported on 1.20.6+, dropping block entity data");
#endif
    }
}
