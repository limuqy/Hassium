package io.github.limuqy.mc.hassium.platform.services;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;

/**
 * 网络管理器服务接口
 * 用于在 common 模块中访问平台特定的网络功能
 */
public interface INetworkManagerService {
    default void sendSleepControl(ServerPlayer player, byte[] data) { throw new UnsupportedOperationException("Sleep carrier unavailable"); }
    default void sendSleepControlToServer(byte[] data) { throw new UnsupportedOperationException("Sleep carrier unavailable"); }

    /** Construct the registered carrier; the actual bound native codec encodes it on EventLoop. */
    default net.minecraft.network.protocol.Packet<?> createAggregationCarrier(byte[] data) {
        throw new UnsupportedOperationException("No aggregation carrier registered");
    }

    default String egressPacketKey(net.minecraft.network.protocol.Packet<?> packet, io.netty.buffer.ByteBuf encoded, String fallback) { return fallback; }
    default int egressEnvelopePrefixBytes(String type) { return 0; }
    default void inspectAggregationCarrier(io.netty.buffer.ByteBuf encoded) {}

    /** Inspect only the owned carrier envelope, before vanilla or loader decoding. */
    default byte[] extractAggregationFrame(String channel, FriendlyByteBuf body) {
        if (!io.github.limuqy.mc.hassium.protocol.HassiumPacketIds.AGGREGATION_S2C.equals(channel)) return null;
        int length = body.readVarInt();
        if (length <= 0 || length > io.github.limuqy.mc.hassium.protocol.egress.EgressFrameCodec.MAX_FRAME_BYTES || body.readableBytes() != length)
            throw new IllegalArgumentException("Invalid native aggregation envelope");
        byte[] frame = new byte[length]; body.readBytes(frame); return frame;
    }
    default boolean isEgressControlChannel(String channel) { return false; }
    default net.minecraft.network.protocol.Packet<?> createEgressFixtureCarrier(io.github.limuqy.mc.hassium.protocol.egress.EgressFixture.Payload payload) {
        return io.github.limuqy.mc.hassium.compat.EgressFixtureCompat.packet(payload);
    }

    /** 发送 shadowPullV1 区块请求到服务端（客户端调用）。 */
    default void sendShadowPullRequest(FriendlyByteBuf buf) {
        trySendShadowPullRequest(buf);
    }
    /** True means same live local connection admission, not remote ACK. Always consumes/releases buf. */
    default boolean trySendShadowPullRequest(FriendlyByteBuf buf) {
        if (buf != null && buf.refCnt() > 0) {
            buf.release();
        }
        return false;
    }

    /**
     * 服务端主动推送 shadowPullV1 响应（待推送队列泵用；复用原 requestId）。
     * buf 所有权转移给实现（未消费时释放）。
     */
    default void sendShadowPullResponse(ServerPlayer player, FriendlyByteBuf buf) {
        if (buf != null && buf.refCnt() > 0) {
            buf.release();
        }
    }

    /**
     * 服务端 → 客户端：权威边沿 enter 通知（{@code chunk_authority_s2c}）。
     * buf 所有权转移给实现（未消费时释放）。
     */
    default void sendChunkAuthorityS2C(ServerPlayer player, FriendlyByteBuf buf) {
        if (buf != null && buf.refCnt() > 0) {
            buf.release();
        }
    }

    /**
     * 发送聚合字典同步到客户端（服务端调用；Play 期 ZSTD 安装后）。
     * 三端各自走已注册的 dictionary_sync 通道。
     */
    default void sendDictionarySync(ServerPlayer player) {
    }

    /**
     * 发送包索引同步到客户端（服务端调用；Play 期 ZSTD 安装后）。
     */
    default void sendIndexSync(ServerPlayer player) {
    }

    /**
     * 发送 Play 期激活包到客户端（服务端调用；登录协商完成后玩家就绪时）。
     *
     * @param negotiatedCaps 服务端按位与后的协商能力位
     * @param worldSeed      主世界种子；seedGenEnabled=false 时为 0
     * @param stemNbt        LevelStem NBT（可空）
     * @param seedGenEnabled 服务端 SeedGen 开关
     * @param dimensionIds   服务端维度 id 列表（客户端本地 resolve LevelStem 装配自定义维度）
     */
    default void sendPlayInit(ServerPlayer player, int negotiatedCaps, long worldSeed,
                              byte[] stemNbt, boolean seedGenEnabled,
                              java.util.List<String> dimensionIds) {
    }

    /**
     * 客户端回发 aggregation_ready ACK（index_sync 收到后激活聚合时调用；C2S）。
     * <p>
     * 携带已安装字典的版本回执（epoch + 内容 hash）：服务端据此校验字典一致性、
     * 登记 epoch 感知、并对热更 offer 计 ACK 门控。
     *
     * @param dictionaryEpoch 客户端已安装字典的 epoch（未安装为 0）
     * @param dictionaryId    客户端已安装字典的内容 hash（未安装为 0）
     */
    default void sendAggregationReady(int dictionaryEpoch, long dictionaryId) {
    }
    /** Bind initial readiness to the connection whose byte receiver was installed. */
    default void sendAggregationReady(net.minecraft.network.Connection connection, int dictionaryEpoch, long dictionaryId) {
        throw new UnsupportedOperationException("No bound aggregation acknowledgement registered");
    }

}
