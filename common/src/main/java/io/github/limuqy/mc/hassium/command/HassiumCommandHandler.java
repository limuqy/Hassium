package io.github.limuqy.mc.hassium.command;


import io.github.limuqy.mc.hassium.cache.client.ViewDistanceExtensionService;
import io.github.limuqy.mc.hassium.metrics.HassiumMetricsImpl;
import io.github.limuqy.mc.hassium.metrics.MetricsTextFormatter;
import io.github.limuqy.mc.hassium.metrics.NetworkStats;
import io.github.limuqy.mc.hassium.metrics.VanillaZlibEstimator;
import io.github.limuqy.mc.hassium.network.core.NetworkCore;
import io.github.limuqy.mc.hassium.network.core.NetworkCoreState;
import io.github.limuqy.mc.hassium.network.core.migration.MigrationEndpoint;
import io.github.limuqy.mc.hassium.network.core.migration.MigrationEngine;
import io.github.limuqy.mc.hassium.network.core.migration.MigrationPolicy;
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
        long localCount = m.getLocallyGeneratedChunkCount();
        long localBytes = m.getLocallyGeneratedChunkBytes();
        return String.format("§e区块加载：§r%d（新增 %d/%s，过期 %d/%s，本地 %d/%s）",
                fullRequests,
                newRequests, MetricsTextFormatter.formatBytes(newRequestBytes),
                staleRequests, MetricsTextFormatter.formatBytes(staleRequestBytes),
                localCount, MetricsTextFormatter.formatBytes(localBytes));
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
        //                     + savedByLocalGen           (SeedGen 本地生成 = 缓存命中同款：vanilla 等价为推全量 chunk)
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
        long savedByLocalGen = chunkWireEstimate * m.getLocallyGeneratedChunkCount();
        long totalVanillaWire = m.getVanillaBytesReceived()
                + savedByCacheFullHit + savedByCacheDelta + savedByLightHit + savedByOvd + savedByLocalGen;
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
     * 导出影子端世界为完整存档目录（客户端命令）。
     * <p>
     * 方案 A：export = 直接拷贝影子端世界目录 {@code hassium_cache/<serverId>/world}
     * 到 {@code hassium_exports/<serverId>}，保留 type 126 + hash 落盘格式；
     * 服务端翻译 126→原版后续单独实现。
     * <p>
     * 异步执行；进度通过聊天回报。
     *
     * @param serverIp 服务器 IP:Port（null/空时导出当前连接的服务器缓存）
     * @param seed     保留参数（目录拷贝不涉及种子）
     * @return 启动结果消息
     */
    public static String startCacheExport(String serverIp, Long seed) {
        Minecraft mc = Minecraft.getInstance();
        if (mc == null) {
            return "§cMinecraft 实例不可用§r";
        }
        Path gameDir = mc.gameDirectory.toPath();

        String cacheId;
        if (serverIp != null && !serverIp.isEmpty()) {
            cacheId = sanitizeServerIp(serverIp);
        } else {
            // 当前服务器：从影子端管线取 serverId（未连接/未初始化则失败）
            String serverId = io.github.limuqy.mc.hassium.network.ClientChunkPipeline.getInstance().getServerId();
            if (serverId == null) {
                return "§c未连接服务器，无法确定导出目标§r";
            }
            cacheId = serverId;
        }

        Path src = gameDir.resolve("hassium_cache").resolve(cacheId).resolve("world");
        if (!Files.isDirectory(src)) {
            return "§c未找到影子端世界目录：" + src + "§r";
        }

        Path dst = gameDir.resolve("hassium_exports").resolve(cacheId);
        try {
            Files.createDirectories(dst.getParent());
            copyTreeAsync(src, dst, cacheId);
            return "§a开始导出 " + cacheId + " 的影子端世界...§r"
                    + "\n§7目标: " + dst + "§r"
                    + "\n§7(保留 type 126 + chunkHash 格式；翻译为原版格式后续提供)§r";
        } catch (Exception e) {
            return "§c导出启动失败: " + e.getMessage() + "§r";
        }
    }

    private static void copyTreeAsync(Path src, Path dst, String cacheId) {
        Thread t = new Thread(() -> {
            try {
                copyTree(src, dst);
                Minecraft mc = Minecraft.getInstance();
                if (mc != null && mc.gui != null) {
                    mc.gui.getChat().addMessage(net.minecraft.network.chat.Component.literal(
                            "§6[Hassium]§r 导出完成: " + dst));
                }
            } catch (Exception e) {
                Minecraft mc = Minecraft.getInstance();
                if (mc != null && mc.gui != null) {
                    mc.gui.getChat().addMessage(net.minecraft.network.chat.Component.literal(
                            "§c[Hassium]§r 导出失败: " + e.getMessage()));
                }
            }
        }, "hassium-export-" + cacheId);
        t.setDaemon(true);
        t.start();
    }

    private static void copyTree(Path src, Path dst) throws java.io.IOException {
        try (java.util.stream.Stream<Path> stream = Files.walk(src)) {
            java.util.List<Path> paths = stream.toList();
            for (Path p : paths) {
                Path target = dst.resolve(src.relativize(p).toString());
                if (Files.isDirectory(p)) {
                    Files.createDirectories(target);
                } else {
                    Files.createDirectories(target.getParent());
                    Files.copy(p, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                }
            }
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
        Path cacheRoot = gameDir.resolve("hassium_cache");
        if (!Files.isDirectory(cacheRoot)) {
            return List.of();
        }
        List<String> ids = new java.util.ArrayList<>();
        try (java.util.stream.Stream<Path> stream = Files.list(cacheRoot)) {
            stream.filter(Files::isDirectory).forEach(p -> ids.add(p.getFileName().toString()));
        } catch (java.io.IOException e) {
            return List.of();
        }
        return ids;
    }

    /** 查询当前导出状态。 */
    public static String getCacheExportStatus() {
        return "§a无导出任务（目录拷贝同步完成，见聊天回报）§r";
    }

    // ==================== /hassium migrate（B4 迁移命令） ====================

    /** 客户端命令上下文检查：migrate 依赖客户端进程内的 NetworkCore 网关单例。 */
    private static boolean isClientContext() {
        return Minecraft.getInstance() != null;
    }

    private static String clientOnlyMessage() {
        return "§c/hassium migrate 是客户端命令，需在客户端执行§r";
    }

    /** migrate 无参数：用法帮助。 */
    public static String migrateUsage() {
        return "§6=== /hassium migrate 用法 ===§r\n" +
                "§e/hassium migrate list§r — 列出目标端点池（主控握手通告）\n" +
                "§e/hassium migrate <host:port>§r — 迁移到指定端点（预热感知全流程，演练用）\n" +
                "§e/hassium migrate status§r — 显示网关状态 / 当前端点 / 最近续流 / 策略参数";
    }

    /** migrate list：列出 MigrationEngine.targetEndpoints() 端点池。 */
    public static String migrateList() {
        if (!isClientContext()) {
            return clientOnlyMessage();
        }
        NetworkCore core = NetworkCore.getInstance();
        List<MigrationEndpoint> targets = core.migration().targetEndpoints();
        if (targets.isEmpty()) {
            return "§6=== Hassium 迁移目标端点 ===§r\n" +
                    "§e端点池:§r §7空§r\n" +
                    "§7（连接主控后经握手通告填充；也可直接 /hassium migrate <host:port> 指定）§r";
        }
        StringBuilder sb = new StringBuilder("§6=== Hassium 迁移目标端点 ===§r\n");
        for (MigrationEndpoint ep : targets) {
            sb.append("§7 - §r§e").append(ep).append("§r\n");
        }
        return sb.toString();
    }

    /**
     * migrate <host:port>：触发预热感知迁移（NetworkCore.migrateTo）。
     * <p>
     * 语义选择：命令走 {@link NetworkCore#migrateTo}（预热感知全流程——先建目标主控
     * 预热会话，就绪后重叠切换；预热禁用/失败自动回退直接续流连接），而非
     * {@link NetworkCore#migrateToImmediate}（故障路径直接切换，不预热）。
     * 演练用立即切换可后续追加子命令。
     */
    public static String migrateTo(String hostPort) {
        if (!isClientContext()) {
            return clientOnlyMessage();
        }
        if (hostPort == null || hostPort.indexOf(':') < 0) {
            return "§c端点格式非法: " + hostPort + "（必须为 host:port，如 127.0.0.1:25566；缺失端口拒绝执行，避免误迁移到默认端口）§r";
        }
        NetworkCore core = NetworkCore.getInstance();
        NetworkCoreState st = core.state();
        if (st != NetworkCoreState.ACTIVE) {
            return "§c迁移未触发：网关状态 " + st + "（迁移需从 ACTIVE 发起，未连接/切换中不可用）§r";
        }
        final MigrationEndpoint endpoint;
        try {
            endpoint = MigrationEndpoint.parse(hostPort.trim());
        } catch (IllegalArgumentException e) {
            return "§c端点格式非法: " + hostPort + "（应为 host:port，如 127.0.0.1:25566）§r";
        }
        core.migrateTo(endpoint);
        return "§a迁移已触发（预热感知）→ §e" + endpoint + "§r\n" +
                "§7状态 MIGRATING → 新主控握手接受后恢复 ACTIVE（/hassium migrate status 查看）§r";
    }

    /** migrate status：网关状态 + 当前端点 + 最近续流 + 端点池 + 策略参数。 */
    public static String migrateStatus() {
        if (!isClientContext()) {
            return clientOnlyMessage();
        }
        NetworkCore core = NetworkCore.getInstance();
        MigrationEngine engine = core.migration();
        MigrationPolicy p = engine.policy();
        StringBuilder sb = new StringBuilder();
        sb.append("§6=== Hassium 迁移状态 ===§r\n");
        sb.append("§e网关状态:§r ").append(core.state()).append('\n');
        String endpoint = core.lastEndpoint();
        sb.append("§e当前端点:§r ").append(endpoint != null ? endpoint : "§7未连接§r").append('\n');
        sb.append(core.lastResumeAccepted()
                ? "§e最近续流:§r §a已接受§r\n"
                : "§e最近续流:§r §7未接受§r\n");
        List<MigrationEndpoint> targets = engine.targetEndpoints();
        if (targets.isEmpty()) {
            sb.append("§e目标端点池:§r §7空（等待主控握手通告）§r\n");
        } else {
            sb.append("§e目标端点池:§r ");
            for (int i = 0; i < targets.size(); i++) {
                if (i > 0) {
                    sb.append(", ");
                }
                sb.append(targets.get(i));
            }
            sb.append('\n');
        }
        String window = p.maintenanceWindow();
        sb.append("§e策略:§r TPS<").append(p.minTps())
                .append(" | 负载>").append(p.maxLoadAverage())
                .append(" | 维护窗口 ").append(window == null || window.isBlank() ? "禁用" : window)
                .append(" | 心跳 ").append(p.heartbeatIntervalMs()).append("ms")
                .append(" | 静默超时 ").append(p.resolvedSilentTimeoutMs()).append("ms")
                .append(" | 空闲窗口 ").append(p.idleWindowMs()).append("ms")
                .append(" | 预热 ").append(p.prewarmEnabled() ? "开" : "关").append('\n');
        return sb.toString();
    }
}
