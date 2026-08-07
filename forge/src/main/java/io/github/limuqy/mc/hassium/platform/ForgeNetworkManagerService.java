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
    @Override
    public void sendLightDeltaPacket(ServerPlayer player, FriendlyByteBuf buf) {
        NETWORK_MANAGER.sendLightDeltaPacket(player, buf);
    }

    @Override
    public void sendClientBloomSync(FriendlyByteBuf buf) {
        NETWORK_MANAGER.sendClientBloomSync(buf);
    }

    // ForgeNetworkManager.sendPreHandshake 定义在 #if MC_VER >= MC_1_20_2 块内
    // （1.20.1 无配置阶段通道，方法整体不编译）；覆写调用点必须同分段，
    // 否则 1.20.1 编译报找不到符号。1.20.1 走接口 default 空实现（fabric 同）。
#if MC_VER >= MC_1_20_2
    @Override
    public void sendPreHandshake(net.minecraft.network.Connection connection) {
        NETWORK_MANAGER.sendPreHandshake(connection);
    }
#endif
}
