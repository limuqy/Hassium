package io.github.limuqy.mc.hassium.network;

/**
 * Hassium 数据包 ID 定义
 */
public final class HassiumPacketIds {

    private HassiumPacketIds() {
        // 工具类，禁止实例化
    }

    /**
     * 命名空间前缀
     */
    public static final String NAMESPACE = "hassium";

    // ===== 握手包 =====

    /**
     * 客户端 -> 服务端：握手请求
     */
    public static final String HANDSHAKE_C2S = "hassium:handshake_c2s";

    /**
     * 服务端 -> 客户端：握手响应
     */
    public static final String HANDSHAKE_S2C = "hassium:handshake_s2c";

    // ===== 缓存相关包 =====

    /**
     * 客户端 -> 服务端：缓存查询
     */
    public static final String CHUNK_CACHE_QUERY_C2S = "hassium:chunk_cache_query_c2s";

    /**
     * 服务端 -> 客户端：缓存决策
     */
    public static final String CHUNK_CACHE_DECISION_S2C = "hassium:chunk_cache_decision_s2c";

    /**
     * 服务端 -> 客户端：压缩区块数据
     */
    public static final String CHUNK_PAYLOAD_S2C = "hassium:chunk_payload_s2c";

    // ===== 字典相关包 =====

    /**
     * 服务端 -> 客户端：字典清单
     */
    public static final String DICTIONARY_MANIFEST_S2C = "hassium:dictionary_manifest_s2c";

    // ===== 指标相关包 =====

    /**
     * 客户端 -> 服务端：指标请求
     */
    public static final String METRICS_REQUEST_C2S = "hassium:metrics_request_c2s";

    /**
     * 服务端 -> 客户端：指标响应
     */
    public static final String METRICS_RESPONSE_S2C = "hassium:metrics_response_s2c";

    // ===== 区块缓存推送相关包 =====

    /**
     * 客户端 -> 服务端：请求区块数据
     */
    public static final String CHUNK_DATA_REQUEST_C2S = "hassium:chunk_data_request_c2s";

    // ===== Per-Section 缓存优化相关包 =====

    /**
     * 服务端 -> 客户端：区块哈希广播（阶段一，轻量）
     */
    public static final String CHUNK_HASH_S2C = "hassium:chunk_hash_s2c";

    /**
     * 客户端 -> 服务端：请求 section 详情（阶段二）
     */
    public static final String SECTION_HASH_REQUEST_C2S = "hassium:section_hash_request_c2s";

    /**
     * 服务端 -> 客户端：section delta 响应（阶段二）
     */
    public static final String SECTION_DELTA_S2C = "hassium:section_delta_s2c";

    /**
     * 检查是否为 Hassium 数据包
     */
    public static boolean isHassiumPacket(String packetId) {
        return packetId != null && packetId.startsWith(NAMESPACE + ":");
    }
}
