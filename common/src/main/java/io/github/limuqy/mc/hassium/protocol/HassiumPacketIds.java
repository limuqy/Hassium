package io.github.limuqy.mc.hassium.protocol;

import io.github.limuqy.mc.hassium.compat.HassiumChannels;

/**
 * Hassium 数据包 ID 定义（String 视图）
 * <p>
 * 通道 ID 的<b>真相源</b>为 {@link HassiumChannels}（{@code PacketId} 值类型）；
 * 本类的 String 常量仅为<b>遗留比较点</b>（压缩黑名单 / 聚合包类型判断 / 配置默认值
 * 等 String 消费方）保留的委托视图，值均派生自 {@code HassiumChannels.X.fullId()}。
 * <p>
 * ID 值为 2.0.X 冻结兼容面：任何改动都会破坏线上通道，禁止修改字符串。
 */
public final class HassiumPacketIds {

    private HassiumPacketIds() {
        // 工具类，禁止实例化
    }

    /**
     * 服务端 -> 客户端：字典同步
     */
    public static final String DICTIONARY_SYNC_S2C = HassiumChannels.DICTIONARY_SYNC_S2C.fullId();

    /**
     * 服务端 -> 客户端：包类型索引同步
     */
    public static final String INDEX_SYNC_S2C = HassiumChannels.INDEX_SYNC_S2C.fullId();

    /**
     * 服务端 -> 客户端：聚合包
     */
    public static final String AGGREGATION_S2C = HassiumChannels.AGGREGATION_S2C.fullId();

    /**
     * Forge/NeoForge SimpleChannel 共用通道
     */
    public static final String MAIN_CHANNEL = HassiumChannels.MAIN_CHANNEL.fullId();

    /**
     * 客户端 -> 服务端：请求区块数据
     */
    public static final String SHADOW_PULL_REQUEST_C2S = HassiumChannels.SHADOW_PULL_REQUEST_C2S.fullId();

    public static final String SHADOW_PULL_RESPONSE_S2C = HassiumChannels.SHADOW_PULL_RESPONSE_S2C.fullId();

    /**
     * 服务端 -> 客户端：权威边沿（真实 tracking 集合 enter 通知 + 权威 chunkHash）。
     * 独立控制面通道（不进聚合），与 shadow_pull 同属区块核心协议族。
     */
    public static final String CHUNK_AUTHORITY_S2C = HassiumChannels.CHUNK_AUTHORITY_S2C.fullId();

    /** Play 期激活（协商位 + SeedGen 种子）；进服控制面，禁止进 PENDING 聚合。 */
    public static final String PLAY_INIT_S2C = HassiumChannels.PLAY_INIT_S2C.fullId();
}
