package io.github.limuqy.mc.hassium.network.core.outbound;

import java.util.HashMap;
import java.util.Map;

/**
 * outbound TCP 控制面帧类型（网关自有通道，纯 Netty 零 MC 依赖）。
 *
 * <p>帧 = {@code [varint 帧长(含 type+payload)] [type 1B] [payload]}，见 {@link ControlFrameCodec}。
 * 新增帧类型必须 append（id 只增不减），与三端现有 CustomPayload 通道互不相干。
 */
public enum ControlFrameType {

    /** 客户端 → 主控：握手请求（payload = {@link HandshakeCodec#encodeClientRequest}）。 */
    HANDSHAKE_C2S(1),

    /** 主控 → 客户端：握手响应（payload = {@link HandshakeCodec#decodeServerResponse} 输入）。 */
    HANDSHAKE_S2C(2),

    /** 客户端 → 主控：单条 C2S 包 payload（编码器由 T5 注册）。 */
    PACKET_C2S(3),

    /** 主控 → 客户端：单条 S2C 包 payload（解码后经 NetworkCore#dispatchS2C 注入，T5）。 */
    PACKET_S2C(4),

    /** 聚合帧：批量子包（复用 CompactHeaderCodec/HassiumAggregationManager 逻辑，后续接入）。 */
    AGGREGATED(5),

    /** 心跳探测（主控 → 客户端）。 */
    PING(6),

    /** 心跳应答（客户端 → 主控）。 */
    PONG(7),

    /** 应用层心跳（双向；迁移引擎存活判定输入，T7）。 */
    HEARTBEAT(8),

    /** 客户端 → 主控：登录阶段 C2S 包（payload = kind0 原版登录包完整编码，T5 定义/T11 消费）。 */
    LOGIN_C2S(9),

    /** 主控 → 客户端：登录阶段 S2C 包（payload = kind0 原版登录包完整编码，T5 定义/T11 消费）。 */
    LOGIN_S2C(10),

    /** 客户端 → 主控：配置阶段 C2S 包（payload = kind0 原版 configuration 包完整编码，T10）。 */
    CONFIG_C2S(11),

    /** 主控 → 客户端：配置阶段 S2C 包（payload = kind0 原版 configuration 包完整编码，T10）。 */
    CONFIG_S2C(12),

    /** 保留的 append-only 帧序号；不得收发或处理。 */
    CHUNK_APPLY_ACK(13);

    private static final Map<Integer, ControlFrameType> BY_ID = new HashMap<>();

    static {
        for (ControlFrameType t : values()) {
            BY_ID.put(t.id, t);
        }
    }

    private final int id;

    ControlFrameType(int id) {
        this.id = id;
    }

    public int id() {
        return id;
    }

    /** 按帧字节 id 查类型；未知 id 返回 {@code null}。 */
    public static ControlFrameType fromId(int id) {
        return BY_ID.get(id);
    }
}
