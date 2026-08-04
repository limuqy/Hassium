package io.github.limuqy.mc.hassium.command;

import io.github.limuqy.mc.hassium.cache.client.CacheWorldExporter;
import io.github.limuqy.mc.hassium.cache.client.ViewDistanceExtensionService;
import io.github.limuqy.mc.hassium.metrics.HassiumMetricsImpl;
import io.github.limuqy.mc.hassium.metrics.MetricsTextFormatter;
import io.github.limuqy.mc.hassium.metrics.NetworkStats;
import io.github.limuqy.mc.hassium.metrics.VanillaZlibEstimator;
import net.minecraft.client.Minecraft;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

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
                "§e发送:§r %s (原版Zlib %s) — §a节省 %s§r\n" +
                "§e压缩比(Zlib->ZSTD):§r %s\n" +
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
        StringBuilder sb = new StringBuilder();
        sb.append("§6=== Hassium 客户端统计 ===§r\n");
        sb.append(formatBandwidthLine(metrics)).append('\n');
        sb.append(formatChunkCacheLine(metrics)).append('\n');
        sb.append(formatChunkLoadLine(metrics)).append('\n');
        sb.append(formatLightCacheLine(metrics)).append('\n');
        sb.append(formatLightRecomputeLine(metrics)).append('\n');
        sb.append(formatOvdLine()).append('\n');
        sb.append(formatSavingsLine(metrics)).append('\n');
        // 每行末尾统一带 \n；冒烟 strip 后用 split("\\R", -1) 已能容忍空末行。
        return sb.toString();
    }

    private static String formatBandwidthLine(HassiumMetricsImpl m) {
        long vanillaRecv = m.getVanillaBytesReceived();
        long actualRecv = m.getActualBytesReceived();
        // 「压缩」行只看压缩算法本身带来的节省（不含缓存/光照/OVD）：
        //   压缩节省 = (vanilla_Zlib_wire - actual_ZSTD_wire) / vanilla_Zlib_wire × 100
        // 当 actual > vanilla（Hassium ZSTD 反而比 vanilla Zlib 多用字节）→ 节省为负，
        // 由 formatPercent 下限 0% 体现「无压缩优势」，不再 ratio>100 clamp 假性 100% 误导。
        // 同时压缩比 1.X:1 = vanilla/actual 保留：>1 表示 ZSTD 比 Zlib 省，<1 表示反而膨胀。
        double saving = vanillaRecv > 0
                ? (double) (vanillaRecv - actualRecv) / vanillaRecv * 100.0
                : 0.0;
        return String.format("§e带宽压缩：§r%s（当前 %s，原版 %s，压缩比 %s）",
                MetricsTextFormatter.formatPercent(saving),
                MetricsTextFormatter.formatBytes(actualRecv),
                MetricsTextFormatter.formatBytes(vanillaRecv),
                MetricsTextFormatter.formatCompressionRatio(vanillaRecv, actualRecv));
    }

    private static String formatChunkCacheLine(HassiumMetricsImpl m) {
        // 命中：完整区块本地缓存直接提供（count + bytes）；增量：分段增量补齐节省（count + bytes）
        long fullHitCount = m.getCacheHitFullChunkCount();
        long fullHitBytes = m.getCacheHitFullChunkBytes();
        long deltaCount = m.getCacheDeltaCount();
        long deltaBytes = m.getCacheDeltaSavedBytes();
        return String.format("§e区块缓存：§r%s（全命中 %d/%s，增量 %d/%s）",
                MetricsTextFormatter.formatPercent(m.getEffectiveCacheHitRate() * 100.0),
                fullHitCount, MetricsTextFormatter.formatBytes(fullHitBytes),
                deltaCount, MetricsTextFormatter.formatBytes(deltaBytes));
    }

    private static String formatChunkLoadLine(HassiumMetricsImpl m) {
        long fullRequests = m.getFullChunkRequestCount();
        long newRequests = m.getNewFullChunkRequestCount();
        long staleRequests = m.getStaleFullChunkRequestCount();
        long newRequestBytes = m.getNewFullChunkRequestBytes();
        long staleRequestBytes = m.getStaleFullChunkRequestBytes();
        return String.format("§e区块加载：§r%d（新增 %d/%s，过期 %d/%s）",
                fullRequests,
                newRequests, MetricsTextFormatter.formatBytes(newRequestBytes),
                staleRequests, MetricsTextFormatter.formatBytes(staleRequestBytes));
    }

    private static String formatOvdLine() {
        ViewDistanceExtensionService ovd = ViewDistanceExtensionService.getInstance();
        if (!ovd.isEnabled()) {
            return "§e超视渲染：§r§7OFF§r";
        }
        // 拆分"渲染 N/M"与"已加载/缺失"两段，一目了然区分客户端渲染半径 vs 服务端推送半径
        return String.format("§e超视渲染：§r§aON§r（§a渲染 %d/%d§r，已加载 %d，缺失 %d）",
                ovd.getLastClientVD(), ovd.getLastServerVD(),
                ovd.getLoadedCount(), ovd.getPendingMissCount());
    }

    private static String formatLightCacheLine(HassiumMetricsImpl m) {
        long lightHit = m.getLightCacheHitCount();
        long lightMiss = m.getLightCacheMissCount();
        return String.format("§e光照缓存：§r%s（命中 %d/%s，重算 %d/%s）",
                MetricsTextFormatter.formatPercent(m.getLightCacheHitRate() * 100.0),
                lightHit, MetricsTextFormatter.formatBytes(m.getLightCacheHitBytes()),
                lightMiss, MetricsTextFormatter.formatBytes(m.getLightCacheMissBytes()));
    }

    /** 光照重算耗时行：主线程 = 玩家感知的世界应用阻塞；后台 = 并行引擎 BFS（同步路径恒 0）。 */
    private static String formatLightRecomputeLine(HassiumMetricsImpl m) {
        long miss = m.getLightCacheMissCount();
        double mainThreadMs = m.getLightRecomputeTimeMs();
        double backgroundMs = m.getLightRecomputeBackgroundTimeMs();
        return String.format("§e光照重算：§r主线程 %.1f ms，后台 %.1f ms（%d 次，平均 %.2f/%.2f ms）",
                mainThreadMs, backgroundMs, miss,
                miss == 0 ? 0.0 : mainThreadMs / miss,
                miss == 0 ? 0.0 : backgroundMs / miss);
    }

    private static String formatSavingsLine(HassiumMetricsImpl m) {
        // 「带宽节省」line 合并所有 Hassium 优化（含压缩）相对纯 vanilla Zlib 等价总 wire 的节省。
        // 口径：所有按 wire 字节同源——vanilla Zlib 压缩后等价 wire，
        //      current = actualBytesReceived 是 Hassium ZSTD 实际 wire。
        //
        // 公式：
        //   total_vanilla_wire = vanillaBytesReceived (实际发出 chunk 包的 vanilla Zlib 等价 wire，已由 recordChunkReceived 累计)
        //                     + savedByCacheFullHit    (vanilla 等价为走全量推这些 chunk，VanillaZlibEstimator.estimate(16KB) × count)
        //                     + savedByCacheDelta       (同上，分段增量对应 vanilla 全量 wire)
        //                     + savedByLightHit          (光照命中时 vanilla 等价随 chunk packet 携带光照字节)
        //                     + savedByOvd                (OVD 拿到的 chunks 在 vanilla 要 serverVD=clientVD 才能推)
        //   current  = actualBytesReceived  (Hassium ZSTD wire)
        //   saved    = total_vanilla_wire - current
        //              (= 各优化 wire 节省 + 压缩节省自带；ZSTD 比 Zlib 更省的部分)
        //   saving%  = saved / total_vanilla_wire × 100
        //
        // ROUND1 命中：saved 仅含压缩节省，saving% ≈ 带宽压缩 saving%（≈40%）。
        // ROUND2 OVD+cache 全开：saved 同时含压缩/缓存/光照/OVD 全套优化，saving% 高 → 99% 区间。
        long chunkWireEstimate = VanillaZlibEstimator.estimate((int) NetworkStats.ESTIMATED_CHUNK_BYTES);
        long lightWireEstimate = VanillaZlibEstimator.estimate((int) NetworkStats.ESTIMATED_LIGHT_BYTES);
        long savedByCacheFullHit = chunkWireEstimate * m.getCacheHitFullChunkCount();
        long savedByCacheDelta = chunkWireEstimate * m.getCacheDeltaCount();
        long savedByLightHit = lightWireEstimate * m.getLightCacheHitCount();
        long savedByOvd = chunkWireEstimate
                * ViewDistanceExtensionService.getInstance().getLoadedCount();
        long totalVanillaWire = m.getVanillaBytesReceived()
                + savedByCacheFullHit + savedByCacheDelta + savedByLightHit + savedByOvd;
        long current = m.getActualBytesReceived();
        long saved = Math.max(0L, totalVanillaWire - current);
        double saving = totalVanillaWire > 0 ? (double) saved / totalVanillaWire * 100.0 : 0.0;
        return String.format("§e带宽节省：§r%s（当前 %s，原版 %s）",
                MetricsTextFormatter.formatPercent(saving),
                MetricsTextFormatter.formatBytes(current),
                MetricsTextFormatter.formatBytes(totalVanillaWire));
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
                "§e发送:§r %s (原版Zlib %s) — §a节省 %s§r\n" +
                "§e接收:§r %s (原版Zlib %s) — §a节省 %s§r\n" +
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
     * @param serverIp 服务器 IP:Port（null/空时导出当前连接的服务器缓存）
     * @param seed     世界种子（null 时使用随机 seed + 空岛模式）
     * @return 启动结果消息
     */
    public static String startCacheExport(String serverIp, Long seed) {
        if (CacheWorldExporter.isRunning()) {
            return "§c已有导出任务正在运行，请等待完成§r";
        }

        // 确定是否为空岛模式（seed 为 null 时使用空岛）
        boolean voidWorld = (seed == null);
        long actualSeed = (seed != null) ? seed : new java.util.Random().nextLong();

        CacheWorldExporter.ProgressCallback progress = (done, total, message) -> {
            Minecraft mc = Minecraft.getInstance();
            if (mc != null && mc.gui != null) {
                mc.gui.getChat().addMessage(net.minecraft.network.chat.Component.literal("§6[Hassium]§r " + message));
            }
        };

        if (serverIp != null && !serverIp.isEmpty()) {
            // 指定服务器导出
            Minecraft mc = Minecraft.getInstance();
            if (mc == null) {
                return "§cMinecraft 实例不可用§r";
            }
            Path gameDir = mc.gameDirectory.toPath();
            String sanitized = sanitizeServerIp(serverIp);
            Path serverDir = gameDir.resolve("hassium_cache").resolve(sanitized);
            if (!Files.isDirectory(serverDir)) {
                return "§c未找到服务器 " + serverIp + " 的缓存目录§r";
            }
            CacheWorldExporter.exportOffline(serverDir, actualSeed, voidWorld, progress);
            return "§a开始导出 " + serverIp + " 的缓存...§r"
                    + (voidWorld ? "\n§7(空岛模式，seed: " + actualSeed + ")§r" : "");
        } else {
            // 当前服务器导出
            Minecraft mc = Minecraft.getInstance();
            if (mc == null || mc.level == null) {
                return "§c未连接到服务器，无法导出当前世界§r";
            }

            // 检查是否为单人世界
            if (mc.hasSingleplayerServer()) {
                return "§c单人世界无法导出缓存，请指定要导出的服务器 IP§r\n"
                        + "§7用法: /hassiumc export <serverIp> [seed]§r";
            }

            boolean started = CacheWorldExporter.exportAsync(actualSeed, voidWorld, progress);
            return started
                    ? "§a开始导出缓存...§r"
                    + (voidWorld ? "\n§7(空岛模式，seed: " + actualSeed + ")§r" : "")
                    : "§c导出启动失败（未连接服务器或已有任务在跑）§r";
        }
    }

    /** 将服务器 IP:Port 转换为缓存目录名。 */
    private static String sanitizeServerIp(String serverIp) {
        // 127.0.0.1:25565 → server_127.0.0.1_25565
        String sanitized = serverIp.replaceAll("[^a-zA-Z0-9._-]", "_");
        return "server_" + sanitized;
    }

    /** 获取可自动补全的缓存服务器列表（显示名）。 */
    public static List<String> getCachedServerIds() {
        Minecraft mc = Minecraft.getInstance();
        if (mc == null) return List.of();
        Path gameDir = mc.gameDirectory.toPath();
        return CacheWorldExporter.listCachedServers(gameDir).stream()
                .map(CacheWorldExporter.ServerCacheInfo::displayName)
                .toList();
    }

    /** 查询当前导出状态。 */
    public static String getCacheExportStatus() {
        return CacheWorldExporter.isRunning()
                ? "§6导出进行中...§r"
                : "§a无导出任务§r";
    }
}
