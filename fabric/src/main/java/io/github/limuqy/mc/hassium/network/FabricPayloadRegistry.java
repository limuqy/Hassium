#if MC_VER >= MC_1_21_1
package io.github.limuqy.mc.hassium.network;

import io.github.limuqy.mc.hassium.Constants;
import io.github.limuqy.mc.hassium.compat.PacketPayloadCompat;
import io.github.limuqy.mc.hassium.compat.ResourceLocationCompat;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
#if MC_VER < MC_1_21_11
import net.minecraft.resources.ResourceLocation;
#else
import net.minecraft.resources.Identifier;
#endif
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Fabric 现代段（{@code MC_VER >= MC_1_21_1}）payload 类型注册表
 * <p>
 * 现代 Fabric 网络 API（自 1.20.5 引入）要求：
 * 1. 使用 CustomPacketPayload + StreamCodec 替代旧的 Identifier + FriendlyByteBuf
 * 2. 通过 PayloadTypeRegistry 注册 payload 类型（必须在注册 receiver 之前）
 * 3. 使用 CustomPacketPayload.Type 作为 receiver 注册和发送的标识
 */
public final class FabricPayloadRegistry {

    private static final Logger LOGGER = LoggerFactory.getLogger("Hassium/PayloadRegistry");
    private static volatile boolean registered = false;
    private static volatile boolean gatewayRegistered = false;

    // ===== S2C payload types (server -> client) =====

    public static final CustomPacketPayload.Type<RawPayload> CHUNK_PAYLOAD_S2C_TYPE =
            type("chunk_payload_s2c");
    public static final CustomPacketPayload.Type<RawPayload> DICTIONARY_SYNC_S2C_TYPE =
            type("dictionary_sync");
    public static final CustomPacketPayload.Type<RawPayload> HANDSHAKE_S2C_TYPE =
            type("handshake_s2c");
    public static final CustomPacketPayload.Type<RawPayload> INDEX_SYNC_S2C_TYPE =
            type("index_sync_s2c");
    public static final CustomPacketPayload.Type<RawPayload> AGGREGATION_S2C_TYPE =
            type("aggregation");
    public static final CustomPacketPayload.Type<RawPayload> CHUNK_HASH_S2C_TYPE =
            type("chunk_hash_s2c");
    public static final CustomPacketPayload.Type<RawPayload> SEED_REF_S2C_TYPE =
            type("seed_ref_s2c");
    public static final CustomPacketPayload.Type<RawPayload> SECTION_DELTA_S2C_TYPE =
            type("section_delta_s2c");
    public static final CustomPacketPayload.Type<RawPayload> BLOCK_ENTITY_DATA_S2C_TYPE =
            type("block_entity_data_s2c");
    public static final CustomPacketPayload.Type<RawPayload> LIGHT_DELTA_S2C_TYPE =
            type("light_delta_s2c");

    /** gateway_info：M1 bootstrap 握手（ServerGatewayInfoSender → PacketPayloadCompat.createClientboundPayload 直发）。 */
    public static final CustomPacketPayload.Type<PacketPayloadCompat.RawCustomPayload> GATEWAY_INFO_S2C_TYPE =
            new CustomPacketPayload.Type<>(ResourceLocationCompat.create(HassiumPacketIds.GATEWAY_INFO_S2C));

    // ===== C2S payload types (client -> server) =====

    public static final CustomPacketPayload.Type<RawPayload> HANDSHAKE_C2S_TYPE =
            type("handshake_c2s");
    public static final CustomPacketPayload.Type<RawPayload> COMPRESSION_READY_C2S_TYPE =
            type("compression_ready_c2s");
    public static final CustomPacketPayload.Type<RawPayload> CHUNK_DATA_REQUEST_C2S_TYPE =
            type("chunk_data_request_c2s");
    public static final CustomPacketPayload.Type<RawPayload> SECTION_HASH_REQUEST_C2S_TYPE =
            type("section_hash_request_c2s");
    public static final CustomPacketPayload.Type<RawPayload> BLOCK_ENTITY_REQUEST_C2S_TYPE =
            type("block_entity_request_c2s");
    public static final CustomPacketPayload.Type<RawPayload> CLIENT_BLOOM_SYNC_C2S_TYPE =
            type("client_bloom_sync_c2s");

    // ===== Helper methods =====

    private static CustomPacketPayload.Type<RawPayload> type(String path) {
        return new CustomPacketPayload.Type<>(
                ResourceLocationCompat.create(Constants.MOD_ID, path));
    }

    private static StreamCodec<FriendlyByteBuf, RawPayload> codec(CustomPacketPayload.Type<RawPayload> type) {
        return StreamCodec.of(
                (buf, payload) -> buf.writeByteArray(payload.data()),
                buf -> new RawPayload(type, buf.readByteArray())
        );
    }

    /**
     * 创建 RawPayload
     */
    public static RawPayload createPayload(CustomPacketPayload.Type<RawPayload> type, byte[] data) {
        return new RawPayload(type, data);
    }

    /**
     * 将 FriendlyByteBuf 转换为 RawPayload（释放原 buf）
     */
    public static RawPayload toPayload(CustomPacketPayload.Type<RawPayload> type, FriendlyByteBuf buf) {
        byte[] data = new byte[buf.readableBytes()];
        buf.readBytes(data);
        buf.release();
        return new RawPayload(type, data);
    }

    /**
     * 将 RawPayload 转换为 FriendlyByteBuf
     */
    public static FriendlyByteBuf fromPayload(RawPayload payload) {
        return new FriendlyByteBuf(io.netty.buffer.Unpooled.wrappedBuffer(payload.data()));
    }

    /**
     * 仅注册 gateway_info S2C payload 类型（无条件路径，镜像 NeoForge registerPayloads
     * 先于 master.enabled 守卫注册 gateway_info 的语义）：ServerGatewayInfoSender.canSend
     * （dedicated + master.enabled）与网络守卫判定可能脱钩，未注册 S2C payload 在
     * 1.20.5+ 类型化通道会被客户端丢弃（DiscardedPayload 回退）。服务端不发送时注册无副作用。
     * <p>
     * 幂等，多次调用安全；{@link #registerAll()} 内部亦经由本方法。
     */
    public static void registerGatewayInfo() {
        if (gatewayRegistered) {
            return;
        }
        gatewayRegistered = true;

        // 服务端经 compat createClientboundPayload 直发；注册后 1.20.5+ 类型化
        // 编解码走 RawCustomPayload codec（未注册 → DiscardedPayload 回退 CCE / 客户端数据被丢弃）。
        PayloadTypeRegistry.playS2C().register(GATEWAY_INFO_S2C_TYPE,
                PacketPayloadCompat.rawPayloadCodec(ResourceLocationCompat.create(HassiumPacketIds.GATEWAY_INFO_S2C)));
    }

    /**
     * 注册除 gateway_info 外的所有 payload 类型到 PayloadTypeRegistry
     * <p>
     * 必须在注册 receiver 之前调用。幂等，多次调用安全。
     * gateway_info 走 {@link #registerGatewayInfo()} 无条件路径，不受 master.enabled 守卫约束。
     */
    public static void registerAll() {
        if (registered) {
            return;
        }
        registered = true;

        // gateway_info 无条件路径（可能已由 registerChannels 守卫前注册，幂等）
        registerGatewayInfo();

        // S2C types
        PayloadTypeRegistry.playS2C().register(CHUNK_PAYLOAD_S2C_TYPE, codec(CHUNK_PAYLOAD_S2C_TYPE));
        PayloadTypeRegistry.playS2C().register(DICTIONARY_SYNC_S2C_TYPE, codec(DICTIONARY_SYNC_S2C_TYPE));
        PayloadTypeRegistry.playS2C().register(HANDSHAKE_S2C_TYPE, codec(HANDSHAKE_S2C_TYPE));
        PayloadTypeRegistry.playS2C().register(INDEX_SYNC_S2C_TYPE, codec(INDEX_SYNC_S2C_TYPE));
        PayloadTypeRegistry.playS2C().register(AGGREGATION_S2C_TYPE, codec(AGGREGATION_S2C_TYPE));
        PayloadTypeRegistry.playS2C().register(CHUNK_HASH_S2C_TYPE, codec(CHUNK_HASH_S2C_TYPE));
        PayloadTypeRegistry.playS2C().register(SEED_REF_S2C_TYPE, codec(SEED_REF_S2C_TYPE));
        PayloadTypeRegistry.playS2C().register(SECTION_DELTA_S2C_TYPE, codec(SECTION_DELTA_S2C_TYPE));
        PayloadTypeRegistry.playS2C().register(BLOCK_ENTITY_DATA_S2C_TYPE, codec(BLOCK_ENTITY_DATA_S2C_TYPE));
        PayloadTypeRegistry.playS2C().register(LIGHT_DELTA_S2C_TYPE, codec(LIGHT_DELTA_S2C_TYPE));

        // C2S types
        PayloadTypeRegistry.playC2S().register(HANDSHAKE_C2S_TYPE, codec(HANDSHAKE_C2S_TYPE));
        PayloadTypeRegistry.playC2S().register(COMPRESSION_READY_C2S_TYPE, codec(COMPRESSION_READY_C2S_TYPE));
        PayloadTypeRegistry.playC2S().register(CHUNK_DATA_REQUEST_C2S_TYPE, codec(CHUNK_DATA_REQUEST_C2S_TYPE));
        PayloadTypeRegistry.playC2S().register(SECTION_HASH_REQUEST_C2S_TYPE, codec(SECTION_HASH_REQUEST_C2S_TYPE));
        PayloadTypeRegistry.playC2S().register(BLOCK_ENTITY_REQUEST_C2S_TYPE, codec(BLOCK_ENTITY_REQUEST_C2S_TYPE));
        PayloadTypeRegistry.playC2S().register(CLIENT_BLOOM_SYNC_C2S_TYPE, codec(CLIENT_BLOOM_SYNC_C2S_TYPE));
        // 预握手（login/配置阶段声明能力）：configuration 阶段 C2S payload
        PayloadTypeRegistry.configurationC2S().register(
                io.github.limuqy.mc.hassium.network.PreHandshakePayload.TYPE,
                io.github.limuqy.mc.hassium.network.PreHandshakePayload.STREAM_CODEC);

        // review-fix: T10-3: 本方法注册 10 S2C + 6 C2S + 1 configurationC2S；
        // gateway_info 经 registerGatewayInfo 单独无条件注册（合计 11 S2C）
        LOGGER.info("Hassium: Registered 10 S2C and 6 C2S (+1 config) payload types for 1.21.1+ (gateway_info via unconditional path)");
    }

    /**
     * 原始 payload 包装器
     * <p>
     * 用于包装原始字节数据，作为 CustomPacketPayload 使用。
     * 每个通道使用独立的 Type 实例，通过 PayloadTypeRegistry 注册。
     */
    public record RawPayload(
            CustomPacketPayload.Type<RawPayload> type,
            byte[] data
    ) implements CustomPacketPayload {
    }
}
#endif
