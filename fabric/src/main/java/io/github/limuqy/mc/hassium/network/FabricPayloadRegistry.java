#if MC_VER >= MC_1_20_5
package io.github.limuqy.mc.hassium.network;

import io.github.limuqy.mc.hassium.Constants;
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
 * Fabric 1.20.5+ payload 类型注册表
 * <p>
 * 在 1.20.5+ 中，Fabric 网络 API 要求：
 * 1. 使用 CustomPacketPayload + StreamCodec 替代旧的 Identifier + FriendlyByteBuf
 * 2. 通过 PayloadTypeRegistry 注册 payload 类型（必须在注册 receiver 之前）
 * 3. 使用 CustomPacketPayload.Type 作为 receiver 注册和发送的标识
 */
public final class FabricPayloadRegistry {

    private static final Logger LOGGER = LoggerFactory.getLogger("Hassium/PayloadRegistry");
    private static volatile boolean registered = false;

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
    public static final CustomPacketPayload.Type<RawPayload> SECTION_DELTA_S2C_TYPE =
            type("section_delta_s2c");
    public static final CustomPacketPayload.Type<RawPayload> BLOCK_ENTITY_DATA_S2C_TYPE =
            type("block_entity_data_s2c");

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
     * 注册所有 payload 类型到 PayloadTypeRegistry
     * <p>
     * 必须在注册 receiver 之前调用。幂等，多次调用安全。
     */
    public static void registerAll() {
        if (registered) {
            return;
        }
        registered = true;

        // S2C types
        PayloadTypeRegistry.playS2C().register(CHUNK_PAYLOAD_S2C_TYPE, codec(CHUNK_PAYLOAD_S2C_TYPE));
        PayloadTypeRegistry.playS2C().register(DICTIONARY_SYNC_S2C_TYPE, codec(DICTIONARY_SYNC_S2C_TYPE));
        PayloadTypeRegistry.playS2C().register(HANDSHAKE_S2C_TYPE, codec(HANDSHAKE_S2C_TYPE));
        PayloadTypeRegistry.playS2C().register(INDEX_SYNC_S2C_TYPE, codec(INDEX_SYNC_S2C_TYPE));
        PayloadTypeRegistry.playS2C().register(AGGREGATION_S2C_TYPE, codec(AGGREGATION_S2C_TYPE));
        PayloadTypeRegistry.playS2C().register(CHUNK_HASH_S2C_TYPE, codec(CHUNK_HASH_S2C_TYPE));
        PayloadTypeRegistry.playS2C().register(SECTION_DELTA_S2C_TYPE, codec(SECTION_DELTA_S2C_TYPE));
        PayloadTypeRegistry.playS2C().register(BLOCK_ENTITY_DATA_S2C_TYPE, codec(BLOCK_ENTITY_DATA_S2C_TYPE));

        // C2S types
        PayloadTypeRegistry.playC2S().register(HANDSHAKE_C2S_TYPE, codec(HANDSHAKE_C2S_TYPE));
        PayloadTypeRegistry.playC2S().register(COMPRESSION_READY_C2S_TYPE, codec(COMPRESSION_READY_C2S_TYPE));
        PayloadTypeRegistry.playC2S().register(CHUNK_DATA_REQUEST_C2S_TYPE, codec(CHUNK_DATA_REQUEST_C2S_TYPE));
        PayloadTypeRegistry.playC2S().register(SECTION_HASH_REQUEST_C2S_TYPE, codec(SECTION_HASH_REQUEST_C2S_TYPE));
        PayloadTypeRegistry.playC2S().register(BLOCK_ENTITY_REQUEST_C2S_TYPE, codec(BLOCK_ENTITY_REQUEST_C2S_TYPE));

        LOGGER.info("Hassium: Registered 8 S2C and 5 C2S payload types for 1.20.5+");
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
