package io.github.limuqy.mc.hassium.protocol;

import io.github.limuqy.mc.hassium.config.HassiumConfigService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashSet;
import java.util.Set;

/**
 * 包压缩 / 聚合黑名单管理。
 * <p>
 * 两层排除：
 * <ol>
 *   <li>硬编码黑名单：控制面 / 独立压缩通道，禁止进聚合与管线业务语义。</li>
 *   <li>高频延迟敏感包：客户端区块图控制路径（center/radius/forget），
 *       pull 模式域保持直发以规避顺序倒置窗口。</li>
 * </ol>
 * <p>
 * 历史注记：早期版本曾将实体位移/旋转/motion 等高频包排除出聚合，根因是当时
 * 客户端区块解码/应用/算光占满主线程。2026-09-14 评估放开：聚合冲刷由 tick 尾
 * + maxWatchdog（默认 50ms）兜底，帧 staleness 上界恒定；客户端子包重放路径与
 * 原版等价。见 {@link #isHighFrequencyNoAggregate(String)} 注释。
 */
public class PacketCompressionBlacklist {

    private static final Logger LOGGER = LoggerFactory.getLogger("Hassium/CompressionBlacklist");

    /**
     * 硬编码黑名单（始终排除，不可配置）
     * <p>
     * 两类必须排除：
     * 1. 已独立 ZSTD 压缩的数据面包（避免双重压缩）
     * 2. 控制面 / 专用 receiver 通道（禁止进 PENDING 聚合缓冲；
     *    聚合拆包走 RawCustomPayload.handle 会绕开 Fabric/NeoForge receiver，
     *    导致 chunkHash / IndexSync / play_init / shadow_pull_response 等永不触达或卡住进服）
     */
    private static final Set<String> HARDCODED_BLACKLIST = Set.of(
            // 控制面：字典 / 索引 / 激活 / 光照增量 / Pull 响应
            HassiumPacketIds.DICTIONARY_SYNC_S2C,
            HassiumPacketIds.INDEX_SYNC_S2C,
            HassiumPacketIds.PLAY_INIT_S2C,
            HassiumPacketIds.SHADOW_PULL_RESPONSE_S2C,
            HassiumPacketIds.CHUNK_AUTHORITY_S2C,
            HassiumPacketIds.LIGHT_DELTA_S2C,
            // 技术限制：Forge/NeoForge SimpleChannel 共用通道
            HassiumPacketIds.MAIN_CHANNEL,
            // 聚合包自身（递归聚合）
            HassiumPacketIds.AGGREGATION_S2C
    );

    /**
     * 检查包是否允许进管线/业务压缩语义（配置 + 硬编码）。
     * <p>
     * 注意：MixinConnection 聚合入口应使用 {@link #shouldAggregate(String)}。
     *
     * @param packetType 包类型标识符（如 "minecraft:commands"）
     * @return true 表示未在黑名单，false 表示硬编码或配置排除
     */
    public static boolean shouldCompress(String packetType) {
        if (packetType == null) {
            return false;
        }
        // 硬编码黑名单
        if (HARDCODED_BLACKLIST.contains(packetType)) {
            return false;
        }

        // 用户配置黑名单
        HassiumConfigService configService = HassiumConfigService.getInstance();
        if (configService.isPacketCompressible(packetType)) {
            return true;
        }

        LOGGER.debug("Packet type {} is blacklisted, skipping compression", packetType);
        return false;
    }

    /**
     * 检查包是否允许进应用层聚合。
     * <p>
     * 在 {@link #shouldCompress(String)} 之上再排除高频延迟敏感原版包
     *（chunk center/radius/forget——pull 模式域保持直发）。
     */
    public static boolean shouldAggregate(String packetType) {
        if (!shouldCompress(packetType)) {
            return false;
        }
        if (isHighFrequencyNoAggregate(packetType)) {
            LOGGER.debug("Packet type {} is high-frequency latency-sensitive, skipping aggregation",
                    packetType);
            return false;
        }
        return true;
    }

    /**
     * 高频 / 延迟敏感原版包：禁止应用层聚合。
     * <p>
     * 仅保留客户端区块图控制路径（center/radius/forget）：Hassium pull 模式
     * 重度改写此域，保持直发以规避顺序倒置窗口；三包低频小包，聚合收益趋零。
     * <p>
     * 实体高频包（位移/旋转/motion/装备等）已放开进聚合（2026-09-14 评估）：
     * 聚合冲刷由 tick 尾 + maxWaitMs watchdog 兜底（默认 50ms），帧 staleness
     * 上界恒定，不随 MSPT 恶化（MSPT>50 时 vanilla 反而一次投递多 tick 包）；
     * 客户端子包重放经 PacketUtils hop 主线程，与原版 channelRead0 路径等价。
     * 历史上「淹没主线程」的根因是客户端区块解码/应用/算光占主线程，非实体包。
     * <p>
     * 跨版本 path 形态兼容：
     * <ul>
     *   <li>1.20.1 snake_case 类名：{@code forget_level_chunk} 等</li>
     *   <li>1.20.5+ PacketType id：同 path 形态</li>
     * </ul>
     */
    public static boolean isHighFrequencyNoAggregate(String packetType) {
        if (packetType == null || packetType.isEmpty()) {
            return false;
        }
        int colon = packetType.indexOf(':');
        String path = colon >= 0 ? packetType.substring(colon + 1) : packetType;

        // 区块跟踪关键路径：中心/卸载延迟会直接拖慢 trackChunk（pull 模式域，保持直发）
        return pathContains(path, "forget_level_chunk")
                || pathContains(path, "set_chunk_cache_center")
                || pathContains(path, "set_chunk_cache_radius");
    }

    private static boolean pathContains(String path, String needle) {
        return path.contains(needle);
    }

    /**
     * 获取所有黑名单（硬编码 + 用户配置）
     */
    public static Set<String> getAllBlacklistedTypes() {
        Set<String> allBlacklist = new HashSet<>(HARDCODED_BLACKLIST);
        allBlacklist.addAll(HassiumConfigService.getInstance().getCompressionBlacklist());
        return allBlacklist;
    }

    /**
     * 检查是否是硬编码黑名单
     */
    public static boolean isHardcodedBlacklist(String packetType) {
        return HARDCODED_BLACKLIST.contains(packetType);
    }
}
