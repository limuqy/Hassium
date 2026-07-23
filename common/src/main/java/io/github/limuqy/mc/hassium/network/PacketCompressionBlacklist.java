package io.github.limuqy.mc.hassium.network;

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
 *   <li>高频延迟敏感包：实体位移/旋转/motion 等每 tick 大量发送的原版包，
 *       禁止进应用层聚合（仍可走管线 ZSTD；体积通常低于 threshold 本就不压）。</li>
 * </ol>
 * 根因：高频实体包被 20ms 聚合 + 客户端 {@code client.execute} 主线程拆包后，
 * 淹没主线程导致 C2S 位置滞后 → 服务端 ChunkMap 不 track 新区块 → 干旱后爆发加载。
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
     *    导致 chunkHash / IndexSync 等永不触达业务处理器）
     */
    private static final Set<String> HARDCODED_BLACKLIST = Set.of(
            // 已独立 ZSTD 压缩的数据面包
            HassiumPacketIds.CHUNK_PAYLOAD_S2C,
            HassiumPacketIds.SECTION_DELTA_S2C,
            // 控制面：握手 / 字典 / 索引 / hash / 光照增量 / BE 数据
            HassiumPacketIds.HANDSHAKE_S2C,
            HassiumPacketIds.DICTIONARY_SYNC_S2C,
            HassiumPacketIds.INDEX_SYNC_S2C,
            HassiumPacketIds.CHUNK_HASH_S2C,
            HassiumPacketIds.LIGHT_DELTA_S2C,
            HassiumPacketIds.BLOCK_ENTITY_DATA_S2C,
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
     *（实体位移/旋转/motion、chunk center/forget 等）。
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
     * 跨版本 path 形态兼容：
     * <ul>
     *   <li>1.20.1 snake_case 类名：{@code clientbound_set_entity_motion_packet}、
     *       嵌套类短名 {@code pos}/{@code pos_rot}/{@code rot}</li>
     *   <li>1.20.5+ PacketType id：{@code set_entity_motion}、{@code move_entity_pos} 等</li>
     * </ul>
     */
    public static boolean isHighFrequencyNoAggregate(String packetType) {
        if (packetType == null || packetType.isEmpty()) {
            return false;
        }
        int colon = packetType.indexOf(':');
        String path = colon >= 0 ? packetType.substring(colon + 1) : packetType;

        // 1.20.1 ClientboundMoveEntityPacket 嵌套类短 path
        if (path.equals("pos") || path.equals("pos_rot") || path.equals("rot")) {
            return true;
        }

        // 实体高频
        if (pathContains(path, "entity_motion")
                || pathContains(path, "move_entity")
                || pathContains(path, "rotate_head")
                || pathContains(path, "teleport_entity")
                || pathContains(path, "set_entity_data")
                || pathContains(path, "entity_event")
                || pathContains(path, "remove_entities")
                || pathContains(path, "update_attributes")
                || pathContains(path, "set_equipment")) {
            return true;
        }

        // 区块跟踪关键路径：中心/卸载延迟会直接拖慢 trackChunk
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
