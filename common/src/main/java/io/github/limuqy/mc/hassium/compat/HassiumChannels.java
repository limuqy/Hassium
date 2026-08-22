package io.github.limuqy.mc.hassium.compat;

import io.github.limuqy.mc.hassium.Constants;

/**
 * Hassium 自定义包通道常量集中持有
 * <p>
 * 11 个自定义包的 CHANNEL 统一收口于此，类型为稳定值类型 {@link PacketId}
 * （namespace + path 纯字符串对，<b>零</b> {@code #if}/{@code ResourceLocation}/
 * {@code Identifier} 出现）。
 * <p>
 * 加载器边界（FabricPayloadRegistry / 各端 NetworkManager / FabricSendCompat）
 * 需要 vanilla 类型时经 {@link ResourceLocationCompat#vanilla(PacketId)} 转换。
 */
public final class HassiumChannels {
    private HassiumChannels() {}

    public static final PacketId BLOCK_ENTITY_DATA_S2C = new PacketId(Constants.MOD_ID, "block_entity_data_s2c");

    public static final PacketId BLOCK_ENTITY_REQUEST_C2S = new PacketId(Constants.MOD_ID, "block_entity_request_c2s");

    public static final PacketId CHUNK_DATA_REQUEST_C2S = new PacketId(Constants.MOD_ID, "chunk_data_request_c2s");

    public static final PacketId CHUNK_HASH_S2C = new PacketId(Constants.MOD_ID, "chunk_hash_s2c");

    public static final PacketId CLIENT_BLOOM_SYNC_C2S = new PacketId(Constants.MOD_ID, "client_bloom_sync_c2s");

    public static final PacketId COMPRESSION_READY_C2S = new PacketId(Constants.MOD_ID, "compression_ready_c2s");

    public static final PacketId DICTIONARY_SYNC = new PacketId(Constants.MOD_ID, "dictionary_sync");

    public static final PacketId LIGHT_DELTA_S2C = new PacketId(Constants.MOD_ID, "light_delta_s2c");

    public static final PacketId SECTION_DELTA_S2C = new PacketId(Constants.MOD_ID, "section_delta_s2c");

    public static final PacketId SECTION_HASH_REQUEST_C2S = new PacketId(Constants.MOD_ID, "section_hash_request_c2s");

    public static final PacketId SEED_REF_S2C = new PacketId(Constants.MOD_ID, "seed_ref_s2c");
}
