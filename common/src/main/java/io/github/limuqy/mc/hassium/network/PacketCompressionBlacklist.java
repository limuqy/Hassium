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
     * 原则：只有「已单独做压缩」或「有技术限制」的包才放黑名单。
     * 控制面小包走全局 Pipeline 压缩（压缩开销可忽略，且避免聚合延迟问题已由 Pipeline 自身处理）。
     */
    private static final Set<String> HARDCODED_BLACKLIST = Set.of(
            // 已独立 ZSTD 压缩的数据面包（不走全局 Pipeline，避免双重压缩）
            HassiumPacketIds.CHUNK_PAYLOAD_S2C,
            HassiumPacketIds.SECTION_DELTA_S2C,
            // 技术限制：Forge/NeoForge SimpleChannel 共用通道，禁止被 MixinConnection 聚合吞掉
            "hassium:main",
            // 聚合包自身（递归聚合）
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
