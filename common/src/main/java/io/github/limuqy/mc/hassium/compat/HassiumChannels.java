package io.github.limuqy.mc.hassium.compat;

import io.github.limuqy.mc.hassium.Constants;

/**
 * Hassium 自定义包通道常量集中持有
 * <p>
 * 自定义包的 CHANNEL 统一收口于此，类型为稳定值类型 {@link PacketId}
 * （namespace + path 纯字符串对，<b>零</b> {@code #if}/{@code ResourceLocation}/
 * {@code Identifier} 出现）。
 * <p>
 * 加载器边界（FabricPayloadRegistry / 各端 NetworkManager / FabricSendCompat）
 * 需要 vanilla 类型时经 {@link ResourceLocationCompat#vanilla(PacketId)} 转换。
 */
public final class HassiumChannels {
    private HassiumChannels() {}

    public static final PacketId SHADOW_PULL_REQUEST_C2S = new PacketId(Constants.MOD_ID, "shadow_pull_request_c2s");

    public static final PacketId SHADOW_PULL_RESPONSE_S2C = new PacketId(Constants.MOD_ID, "shadow_pull_response_s2c");

    /** 权威边沿 enter 通知（服务端声明权威集合 + 权威 chunkHash；S2C）。 */
    public static final PacketId CHUNK_AUTHORITY_S2C = new PacketId(Constants.MOD_ID, "chunk_authority_s2c");

    public static final PacketId AGGREGATION_READY_C2S = new PacketId(Constants.MOD_ID, "aggregation_ready_c2s");

    public static final PacketId DICTIONARY_SYNC_S2C = new PacketId(Constants.MOD_ID, "dictionary_sync");

    public static final PacketId LIGHT_DELTA_S2C = new PacketId(Constants.MOD_ID, "light_delta_s2c");

    /** Play 期激活（登录协商结果 + SeedGen 种子；S2C）。 */
    public static final PacketId PLAY_INIT_S2C = new PacketId(Constants.MOD_ID, "play_init_s2c");

    /** 包索引同步（紧凑包头两级 VarInt 命名空间索引；S2C）。 */
    public static final PacketId INDEX_SYNC_S2C = new PacketId(Constants.MOD_ID, "index_sync_s2c");

    /** 预握手 hello（S2C，配置阶段触发载体；1.21.1+）。 */
    public static final PacketId PRE_HANDSHAKE_HELLO_S2C = new PacketId(Constants.MOD_ID, "prehandshake_hello_s2c");

    /** 预握手能力声明（C2S，配置阶段；1.21.1+）。 */
    public static final PacketId PRE_HANDSHAKE_C2S = new PacketId(Constants.MOD_ID, "prehandshake_c2s");

    /** 聚合包（S2C）。 */
    public static final PacketId AGGREGATION_S2C = new PacketId(Constants.MOD_ID, "aggregation");

    /** Forge/NeoForge SimpleChannel 共用通道。 */
    public static final PacketId MAIN_CHANNEL = new PacketId(Constants.MOD_ID, "main");
}
