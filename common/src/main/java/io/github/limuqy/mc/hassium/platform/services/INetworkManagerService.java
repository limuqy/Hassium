package io.github.limuqy.mc.hassium.platform.services;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;

/**
 * 网络管理器服务接口
 * 用于在 common 模块中访问平台特定的网络功能
 */
public interface INetworkManagerService {

    /** 发送 shadowPullV1 区块请求到服务端（客户端调用）。 */
    default void sendShadowPullRequest(FriendlyByteBuf buf) {
        if (buf != null && buf.refCnt() > 0) {
            buf.release();
        }
    }

    /**
     * 发送光照增量通知到客户端（服务端调用；vanilla 通道 play S2C 直发）
     */
    void sendLightDeltaPacket(ServerPlayer player, FriendlyByteBuf buf);

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
     */
    default void sendPlayInit(ServerPlayer player, int negotiatedCaps, long worldSeed,
                              byte[] stemNbt, boolean seedGenEnabled) {
    }

    /**
     * 客户端回发 aggregation_ready ACK（index_sync 收到后激活聚合时调用；C2S）。
     */
    default void sendAggregationReady() {
    }

}

