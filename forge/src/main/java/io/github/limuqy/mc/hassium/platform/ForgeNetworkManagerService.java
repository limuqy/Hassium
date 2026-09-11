package io.github.limuqy.mc.hassium.platform;

import io.github.limuqy.mc.hassium.network.ForgeNetworkManager;
import io.github.limuqy.mc.hassium.platform.services.INetworkManagerService;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;

/**
 * Forge 平台的网络管理器服务实现。
 * <p>
 * 直连拓扑（2.0.0）：业务帧全部走已注册的 {@code hassium:main} SimpleChannel，
 * 不再经网关路由（GatewayPlayerBridge / GatewayPacketCodec 已随网络核心裁剪）。
 * 登录协商下游（Play 激活 play_init_s2c / 字典与索引同步 / compression_ready ACK）
 * 由 common 握手链（{@code ServerHandshakeActivation} / {@code PlayInitClient}）经
 * SPI 下发，本类仅做通道转发。
 */
public class ForgeNetworkManagerService implements INetworkManagerService {

    private static final ForgeNetworkManager NETWORK_MANAGER = new ForgeNetworkManager();

    @Override
    public void sendShadowPullRequest(FriendlyByteBuf buf) {
        NETWORK_MANAGER.sendShadowPullRequest(buf);
    }

    @Override
    public void sendShadowPullResponse(ServerPlayer player, FriendlyByteBuf buf) {
        NETWORK_MANAGER.sendShadowPullResponse(player, buf);
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
    public void sendDictionarySync(ServerPlayer player) {
        ForgeNetworkManager.sendDictionarySync(player);
    }

    @Override
    public void sendIndexSync(ServerPlayer player) {
        ForgeNetworkManager.sendIndexSync(player);
    }

    @Override
    public void sendPlayInit(ServerPlayer player, int negotiatedCaps, long worldSeed,
                             byte[] stemNbt, boolean seedGenEnabled) {
        ForgeNetworkManager.sendPlayInit(player, negotiatedCaps, worldSeed, stemNbt, seedGenEnabled);
    }

    @Override
    public void announcePreHandshake(net.minecraft.network.Connection connection) {
        ForgeNetworkManager.announcePreHandshake(connection);
    }}
