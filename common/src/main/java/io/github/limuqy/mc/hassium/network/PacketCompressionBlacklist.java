package io.github.limuqy.mc.hassium.network;

import io.github.limuqy.mc.hassium.config.HassiumConfigService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashSet;
import java.util.Set;

/**
 * 包压缩黑名单管理
 * <p>
 * 管理哪些包类型应该被排除在全局压缩 / 聚合之外。
 */
public class PacketCompressionBlacklist {

    private static final Logger LOGGER = LoggerFactory.getLogger("Hassium/CompressionBlacklist");

    /**
     * 硬编码黑名单（始终排除，不可配置）
     * <p>
     * 含：已单独压缩的区块 payload、握手/索引/字典控制面、chunk hash 推送通道。
     * 避免 PENDING 聚合窗口把控制面包缓冲数秒导致进服空窗。
     */
    private static final Set<String> HARDCODED_BLACKLIST = Set.of(
            HassiumPacketIds.CHUNK_PAYLOAD_S2C,
            // Forge/NeoForge SimpleChannel 共用通道；禁止被 MixinConnection 聚合吞掉
            "hassium:main",
            // 区块缓存推送控制面
            HassiumPacketIds.CHUNK_HASH_S2C,
            HassiumPacketIds.CHUNK_METADATA_S2C,
            HassiumPacketIds.CHUNK_DATA_REQUEST_C2S,
            HassiumPacketIds.SECTION_HASH_REQUEST_C2S,
            HassiumPacketIds.SECTION_DELTA_S2C,
            "hassium:block_entity_request_c2s",
            "hassium:block_entity_data_s2c",
            // 握手与全局压缩就绪
            HassiumPacketIds.HANDSHAKE_C2S,
            HassiumPacketIds.HANDSHAKE_S2C,
            "hassium:index_sync_s2c",
            "hassium:dictionary_sync",
            "hassium:compression_ready_c2s",
            // 聚合包自身
            "hassium:aggregation"
    );

    /**
     * 检查包是否应该压缩 / 聚合
     *
     * @param packetType 包类型标识符（如 "minecraft:commands"）
     * @return true 表示应该压缩，false 表示应该排除
     */
    public static boolean shouldCompress(String packetType) {
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
