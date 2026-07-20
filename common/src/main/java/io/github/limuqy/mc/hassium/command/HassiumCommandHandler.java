package io.github.limuqy.mc.hassium.command;

import io.github.limuqy.mc.hassium.cache.client.CacheWorldExporter;
import io.github.limuqy.mc.hassium.cache.client.ViewDistanceExtensionService;
import io.github.limuqy.mc.hassium.metrics.HassiumMetricsImpl;
import io.github.limuqy.mc.hassium.metrics.MetricsTextFormatter;
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
        return String.format(
                "§6=== Hassium 服务端统计 ===§r\n" +
                "§e发送:§r %s (原版 %s) — §a节省 %s§r\n" +
                "§e压缩比:§r %s\n" +
                "§e元数据发送:§r %s\n" +
                "§e数据请求接收:§r %d\n" +
                "§e区块压缩:§r %d",
                MetricsTextFormatter.formatBytes(actualSent), MetricsTextFormatter.formatBytes(vanillaSent),
                MetricsTextFormatter.formatPercent(metrics.getSendBandwidthSavingPercent()),
                MetricsTextFormatter.formatCompressionRatio(vanillaSent, actualSent),
                MetricsTextFormatter.formatBytes(metadataSent),
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
        long fullHitBytes = metrics.getCacheHitFullChunkBytes();
        long deltaSavedBytes = metrics.getCacheDeltaSavedBytes();
        long fullRequests = metrics.getFullChunkRequestCount();
        long newRequests = metrics.getNewFullChunkRequestCount();
        long staleRequests = metrics.getStaleFullChunkRequestCount();
        long newRequestBytes = metrics.getNewFullChunkRequestBytes();
        long staleRequestBytes = metrics.getStaleFullChunkRequestBytes();

        double currentBandwidthPercent = vanillaRecv > 0
                ? (double) actualRecv / vanillaRecv * 100.0
                : 0.0;

        ViewDistanceExtensionService ovd = ViewDistanceExtensionService.getInstance();

        return String.format(
                "§6=== Hassium 客户端统计 ===§r\n" +
                "§e带宽压缩：§r%s（当前 %s，原版 %s，压缩比 %s）\n" +
                "§e缓存命中：§r%s（命中 %s，增量 %s）\n" +
                "§e区块加载：§r%d（新增 %d/%s，过期 %d/%s）\n" +
                "§e超视渲染：§r%s（已加载 %d，缺失 %d）",
                MetricsTextFormatter.formatPercent(currentBandwidthPercent),
                MetricsTextFormatter.formatBytes(actualRecv), MetricsTextFormatter.formatBytes(vanillaRecv),
                MetricsTextFormatter.formatCompressionRatio(vanillaRecv, actualRecv),
                MetricsTextFormatter.formatPercent(metrics.getEffectiveCacheHitRate() * 100.0),
                MetricsTextFormatter.formatBytes(fullHitBytes), MetricsTextFormatter.formatBytes(deltaSavedBytes),
                fullRequests, newRequests, MetricsTextFormatter.formatBytes(newRequestBytes),
                staleRequests, MetricsTextFormatter.formatBytes(staleRequestBytes),
                ovd.isEnabled() ? "§aON§r" : "§7OFF§r",
                ovd.getLoadedCount(),
                ovd.getPendingMissCount()
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
        return String.format(
                "§6=== Hassium 网络统计 ===§r\n" +
                "§e发送:§r %s (原版 %s) — §a节省 %s§r\n" +
                "§e接收:§r %s (原版 %s) — §a节省 %s§r\n" +
                "§e缓存命中率:§r %s (§a命中 %d§r, §c未命中 %d§r, §6过期 %d§r)\n" +
                "§e压缩比:§r %s\n" +
                "§e元数据:§r 发送 %s, 接收 %s\n" +
                "§e数据请求:§r 发送 %d, 接收 %d\n" +
                "§e区块:§r 压缩 %d, 解压 %d",
                MetricsTextFormatter.formatBytes(actualSent), MetricsTextFormatter.formatBytes(vanillaSent),
                MetricsTextFormatter.formatPercent(metrics.getSendBandwidthSavingPercent()),
                MetricsTextFormatter.formatBytes(actualRecv), MetricsTextFormatter.formatBytes(vanillaRecv),
                MetricsTextFormatter.formatPercent(metrics.getReceiveBandwidthSavingPercent()),
                MetricsTextFormatter.formatPercent(metrics.getCacheHitRate() * 100.0), cacheHits, cacheMisses, cacheStale,
                MetricsTextFormatter.formatCompressionRatio(vanillaSent, actualSent),
                MetricsTextFormatter.formatBytes(metrics.getMetadataBytesSent()), MetricsTextFormatter.formatBytes(metrics.getMetadataBytesReceived()),
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
     * 启动缓存导出为原版世界（客户端命令）。
     * <p>
     * 异步执行；进度通过聊天回报。限制说明见 {@link CacheWorldExporter}。
     *
     * @param worldName 输出世界名；null/空时自动生成 {@code HassiumCache_<timestamp>}
     * @return 启动结果消息
     */
    public static String startCacheExport(String worldName) {
        if (worldName == null || worldName.isEmpty()) {
            worldName = "HassiumCache_" + System.currentTimeMillis();
        }
        if (CacheWorldExporter.isRunning()) {
            return "§c已有导出任务正在运行，请等待完成§r";
        }
        boolean started = CacheWorldExporter.exportAsync(worldName, (done, total, message) -> {
            net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
            if (mc != null && mc.gui != null) {
                mc.gui.getChat().addMessage(net.minecraft.network.chat.Component.literal("§6[Hassium]§r " + message));
            }
        });
        return started
                ? "§a开始导出缓存到 saves/" + worldName + "/...§r\n§7限制: 无实体/玩家背包；仅为去过区块快照；模组方块需相同模组§r"
                : "§c导出启动失败（执行器不可用或已有任务在跑）§r";
    }

    /** 查询当前导出状态。 */
    public static String getCacheExportStatus() {
        return CacheWorldExporter.isRunning()
                ? "§6导出进行中...§r"
                : "§a无导出任务§r";
    }
}
