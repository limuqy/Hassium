package io.github.limuqy.mc.hassium.platform;

import io.github.limuqy.mc.hassium.network.ChunkDataRequestC2SPacket;
import io.github.limuqy.mc.hassium.network.ChunkHashS2CPacket;
import io.github.limuqy.mc.hassium.network.SeedRefS2CPacket;
import io.github.limuqy.mc.hassium.network.FabricNetworkManager;
import io.github.limuqy.mc.hassium.network.LightDeltaS2CPacket;
import io.github.limuqy.mc.hassium.network.SectionDeltaS2CPacket;
import io.github.limuqy.mc.hassium.network.BlockEntityDataS2CPacket;
import io.github.limuqy.mc.hassium.network.BlockEntityRequestC2SPacket;
import io.github.limuqy.mc.hassium.network.SectionHashRequestC2SPacket;
import io.github.limuqy.mc.hassium.platform.services.INetworkManagerService;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
#if MC_VER >= MC_1_20_5
import io.github.limuqy.mc.hassium.network.FabricPayloadRegistry;
#endif

/**
 * Fabric 平台的网络管理器服务实现
 */
public class FabricNetworkManagerService implements INetworkManagerService {

    private static final Logger LOGGER = LoggerFactory.getLogger("Hassium/Network");

    private static final FabricNetworkManager NETWORK_MANAGER = new FabricNetworkManager();

    @Override
    public void sendChunkDataRequest(FriendlyByteBuf buf) {
        // review-fix: T10-6: 无 connection 检查直接 send → 断线竞态下 Fabric send 抛异常且 buf 未释放；对齐 FabricNetworkManager:250-263
        if (Minecraft.getInstance().getConnection() != null) {
#if MC_VER < MC_1_20_5
            ClientPlayNetworking.send(ChunkDataRequestC2SPacket.CHANNEL, buf);
#else
            ClientPlayNetworking.send(FabricPayloadRegistry.toPayload(FabricPayloadRegistry.CHUNK_DATA_REQUEST_C2S_TYPE, buf));
#endif
        } else {
            buf.release();
        }
    }

    @Override
    public void sendChunkHashPacket(ServerPlayer player, FriendlyByteBuf buf) {
        // T12 网关收口：网关玩家走 kind=1 HASSIUM 帧（客户端 receiver 已退役，回落 CustomPayload 是死路径）
        if (io.github.limuqy.mc.hassium.server.GatewayPlayerBridge.tryRouteS2C(
                player, io.github.limuqy.mc.hassium.network.core.GatewayPacketCodec.HassiumSub.CHUNK_HASH.id(), buf)) {
            return;
        }
#if MC_VER < MC_1_20_5
        ServerPlayNetworking.send(player, ChunkHashS2CPacket.CHANNEL, buf);
#else
        ServerPlayNetworking.send(player, FabricPayloadRegistry.toPayload(FabricPayloadRegistry.CHUNK_HASH_S2C_TYPE, buf));
#endif
    }

    @Override
    public void sendSeedRef(ServerPlayer player, FriendlyByteBuf buf) {
        if (io.github.limuqy.mc.hassium.server.GatewayPlayerBridge.tryRouteS2C(
                player, io.github.limuqy.mc.hassium.network.core.GatewayPacketCodec.HassiumSub.SEED_REF.id(), buf)) {
            return;
        }
#if MC_VER < MC_1_20_5
        ServerPlayNetworking.send(player, SeedRefS2CPacket.CHANNEL, buf);
#else
        ServerPlayNetworking.send(player, FabricPayloadRegistry.toPayload(FabricPayloadRegistry.SEED_REF_S2C_TYPE, buf));
#endif
    }

    @Override
    public void sendSectionHashRequest(FriendlyByteBuf buf) {
#if MC_VER < MC_1_20_5
        ClientPlayNetworking.send(SectionHashRequestC2SPacket.CHANNEL, buf);
#else
        ClientPlayNetworking.send(FabricPayloadRegistry.toPayload(FabricPayloadRegistry.SECTION_HASH_REQUEST_C2S_TYPE, buf));
#endif
    }

    @Override
    public void sendSectionDeltaPacket(ServerPlayer player, FriendlyByteBuf buf) {
        if (io.github.limuqy.mc.hassium.server.GatewayPlayerBridge.tryRouteS2C(
                player, io.github.limuqy.mc.hassium.network.core.GatewayPacketCodec.HassiumSub.SECTION_DELTA.id(), buf)) {
            return;
        }
#if MC_VER < MC_1_20_5
        ServerPlayNetworking.send(player, SectionDeltaS2CPacket.CHANNEL, buf);
#else
        ServerPlayNetworking.send(player, FabricPayloadRegistry.toPayload(FabricPayloadRegistry.SECTION_DELTA_S2C_TYPE, buf));
#endif
    }

    @Override
    public void sendBlockEntityRequest(FriendlyByteBuf buf) {
#if MC_VER < MC_1_20_5
        ClientPlayNetworking.send(BlockEntityRequestC2SPacket.CHANNEL, buf);
#else
        ClientPlayNetworking.send(FabricPayloadRegistry.toPayload(FabricPayloadRegistry.BLOCK_ENTITY_REQUEST_C2S_TYPE, buf));
#endif
    }

    @Override
    public void sendBlockEntityData(ServerPlayer player, FriendlyByteBuf buf) {
        if (io.github.limuqy.mc.hassium.server.GatewayPlayerBridge.tryRouteS2C(
                player, io.github.limuqy.mc.hassium.network.core.GatewayPacketCodec.HassiumSub.BLOCK_ENTITY_DATA.id(), buf)) {
            return;
        }
#if MC_VER < MC_1_20_5
        ServerPlayNetworking.send(player, BlockEntityDataS2CPacket.CHANNEL, buf);
#else
        ServerPlayNetworking.send(player, FabricPayloadRegistry.toPayload(FabricPayloadRegistry.BLOCK_ENTITY_DATA_S2C_TYPE, buf));
#endif
    }

    @Override
    public void sendLightDeltaPacket(ServerPlayer player, FriendlyByteBuf buf) {
        if (io.github.limuqy.mc.hassium.server.GatewayPlayerBridge.tryRouteS2C(
                player, io.github.limuqy.mc.hassium.network.core.GatewayPacketCodec.HassiumSub.LIGHT_DELTA.id(), buf)) {
            return;
        }
#if MC_VER < MC_1_20_5
        ServerPlayNetworking.send(player, LightDeltaS2CPacket.CHANNEL, buf);
#else
        ServerPlayNetworking.send(player, FabricPayloadRegistry.toPayload(FabricPayloadRegistry.LIGHT_DELTA_S2C_TYPE, buf));
#endif
    }

    @Override
    public void sendClientBloomSync(FriendlyByteBuf buf) {
#if MC_VER < MC_1_20_5
        ClientPlayNetworking.send(io.github.limuqy.mc.hassium.network.ClientBloomSyncPacket.CHANNEL, buf);
#else
        ClientPlayNetworking.send(FabricPayloadRegistry.toPayload(FabricPayloadRegistry.CLIENT_BLOOM_SYNC_C2S_TYPE, buf));
#endif
    }
}
