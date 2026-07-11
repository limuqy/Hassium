package io.github.limuqy.mc.hassium.command;

import io.github.limuqy.mc.hassium.metrics.HassiumMetricsImpl;
import io.github.limuqy.mc.hassium.metrics.NetworkStats;

/**
 * Hassium 命令处理器
 * <p>
 * 提供命令逻辑，由 Fabric/Forge 各自注册到命令系统。
 * 服务端和客户端在不同 JVM 中运行，各自有独立的 NetworkStats 实例。
 */
public class HassiumCommandHandler {

    /**
     * 检查指标是否启用
     */
    public static boolean isMetricsEnabled() {
        return NetworkStats.isEnabled();
    }

    /**
     * 获取服务端统计信息（服务端执行 /hassium stats 时显示）
     */
    public static String getServerStatsMessage() {
        HassiumMetricsImpl metrics = NetworkStats.getMetrics();

        long vanillaSent = metrics.getVanillaBytesSent();
        long actualSent = metrics.getActualBytesSent();
        long metadataSent = metrics.getMetadataBytesSent();
        long dataRequestsReceived = metrics.getDataRequestsReceived();
        long chunksCompressed = metrics.getChunksCompressed();
        double sendSaving = vanillaSent > 0 ? (double) (vanillaSent - actualSent) / vanillaSent * 100.0 : 0.0;
        double compressionRatio = actualSent > 0 ? (double) vanillaSent / actualSent : 0.0;

        return String.format(
                "§6=== Hassium 服务端统计 ===§r\n" +
                "§e发送:§r %s (原版 %s) — §a节省 %.1f%%§r\n" +
                "§e压缩比:§r %.2f:1\n" +
                "§e元数据发送:§r %s\n" +
                "§e数据请求接收:§r %d\n" +
                "§e区块压缩:§r %d",
                formatBytes(actualSent), formatBytes(vanillaSent), sendSaving,
                compressionRatio,
                formatBytes(metadataSent),
                dataRequestsReceived,
                chunksCompressed
        );
    }

    /**
     * 获取客户端统计信息（客户端执行时显示）
     */
    public static String getClientStatsMessage() {
        HassiumMetricsImpl metrics = NetworkStats.getMetrics();

        long vanillaRecv = metrics.getVanillaBytesReceived();
        long actualRecv = metrics.getActualBytesReceived();
        long cacheHits = metrics.getCacheHitCount();
        long cacheMisses = metrics.getCacheMissCount();
        long cacheStale = metrics.getCacheStaleCount();
        double cacheHitRate = metrics.getCacheHitRate() * 100.0;
        double recvSaving = vanillaRecv > 0 ? (double) (vanillaRecv - actualRecv) / vanillaRecv * 100.0 : 0.0;
        double compressionRatio = actualRecv > 0 ? (double) vanillaRecv / actualRecv : 0.0;

        return String.format(
                "§6=== Hassium 客户端统计 ===§r\n" +
                "§e接收:§r %s (原版 %s) — §a节省 %.1f%%§r\n" +
                "§e压缩比:§r %.2f:1\n" +
                "§e缓存命中率:§r %.1f%% (§a命中 %d§r, §c未命中 %d§r, §6过期 %d§r)\n" +
                "§e元数据接收:§r %s\n" +
                "§e数据请求发送:§r %d\n" +
                "§e区块解压:§r %d",
                formatBytes(actualRecv), formatBytes(vanillaRecv), recvSaving,
                compressionRatio,
                cacheHitRate, cacheHits, cacheMisses, cacheStale,
                formatBytes(metrics.getMetadataBytesReceived()),
                metrics.getDataRequestsSent(),
                metrics.getChunksDecompressed()
        );
    }

    /**
     * 获取完整统计信息（单人游戏时，服务端和客户端在同一 JVM）
     */
    public static String getFullStatsMessage() {
        HassiumMetricsImpl metrics = NetworkStats.getMetrics();

        long vanillaSent = metrics.getVanillaBytesSent();
        long actualSent = metrics.getActualBytesSent();
        long vanillaRecv = metrics.getVanillaBytesReceived();
        long actualRecv = metrics.getActualBytesReceived();
        long cacheHits = metrics.getCacheHitCount();
        long cacheMisses = metrics.getCacheMissCount();
        long cacheStale = metrics.getCacheStaleCount();
        double cacheHitRate = metrics.getCacheHitRate() * 100.0;
        double sendSaving = vanillaSent > 0 ? (double) (vanillaSent - actualSent) / vanillaSent * 100.0 : 0.0;
        double recvSaving = vanillaRecv > 0 ? (double) (vanillaRecv - actualRecv) / vanillaRecv * 100.0 : 0.0;
        double compressionRatio = actualSent > 0 ? (double) vanillaSent / actualSent : 0.0;

        return String.format(
                "§6=== Hassium 网络统计 ===§r\n" +
                "§e发送:§r %s (原版 %s) — §a节省 %.1f%%§r\n" +
                "§e接收:§r %s (原版 %s) — §a节省 %.1f%%§r\n" +
                "§e缓存命中率:§r %.1f%% (§a命中 %d§r, §c未命中 %d§r, §6过期 %d§r)\n" +
                "§e压缩比:§r %.2f:1\n" +
                "§e元数据:§r 发送 %s, 接收 %s\n" +
                "§e数据请求:§r 发送 %d, 接收 %d\n" +
                "§e区块:§r 压缩 %d, 解压 %d",
                formatBytes(actualSent), formatBytes(vanillaSent), sendSaving,
                formatBytes(actualRecv), formatBytes(vanillaRecv), recvSaving,
                cacheHitRate, cacheHits, cacheMisses, cacheStale,
                compressionRatio,
                formatBytes(metrics.getMetadataBytesSent()), formatBytes(metrics.getMetadataBytesReceived()),
                metrics.getDataRequestsSent(), metrics.getDataRequestsReceived(),
                metrics.getChunksCompressed(), metrics.getChunksDecompressed()
        );
    }

    /**
     * 重置统计信息
     */
    public static String resetStats() {
        NetworkStats.reset();
        return "§aHassium 统计信息已重置§r";
    }

    /**
     * 切换指标收集开关
     */
    public static String toggleStats() {
        boolean newState = !NetworkStats.isEnabled();
        NetworkStats.setEnabled(newState);
        return newState
                ? "§aHassium 指标收集已启用§r"
                : "§cHassium 指标收集已关闭§r";
    }

    /**
     * 格式化字节数为人类可读格式
     */
    private static String formatBytes(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format("%.1f KB", bytes / 1024.0);
        if (bytes < 1024L * 1024 * 1024) return String.format("%.1f MB", bytes / (1024.0 * 1024));
        return String.format("%.1f GB", bytes / (1024.0 * 1024 * 1024));
    }
}
