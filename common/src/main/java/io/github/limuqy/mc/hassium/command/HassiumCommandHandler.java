package io.github.limuqy.mc.hassium.command;

import io.github.limuqy.mc.hassium.metrics.HassiumMetricsImpl;
import io.github.limuqy.mc.hassium.metrics.MetricsTextFormatter;
import io.github.limuqy.mc.hassium.metrics.NetworkStats;
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
        // 后台注入/hash 会并发加计数：缓存行与加载行必须用同一组快照，否则
        // 「应用区块」与「区块加载」会差 1，冒烟公式校验失败。
        long fullHitCount = metrics.getCacheHitFullChunkCount();
        long fullHitBytes = metrics.getCacheHitFullChunkBytes();
        long localCount = metrics.getLocallyGeneratedChunkCount();
        long deltaCount = metrics.getCacheDeltaCount();
        long deltaBytes = metrics.getCacheDeltaSavedBytes();
        long shardBytes = metrics.getCacheShardBytes();
        long fullRequests = metrics.getFullChunkRequestCount();
        long serverPush = metrics.getServerPushAppliedCount();
        long appliedBytes = metrics.getFullChunkRequestBytes() + fullHitBytes + deltaBytes
                + serverPush * NetworkStats.ESTIMATED_CHUNK_BYTES;
        StringBuilder sb = new StringBuilder();
        sb.append("§6=== Hassium 客户端统计 ===§r\n");
        sb.append(formatBandwidthLine(metrics)).append('\n');
        sb.append(formatChunkCacheLine(fullHitCount, fullHitBytes, deltaCount, deltaBytes,
                shardBytes, appliedBytes)).append('\n');
        sb.append(formatChunkLoadLine(metrics, fullRequests + serverPush, localCount)).append('\n');
        sb.append(formatLightCacheLine(metrics)).append('\n');
        sb.append(formatSavingsLine(metrics)).append('\n');
        // 每行末尾统一带 \n；冒烟 strip 后用 split("\\R", -1) 已能容忍空末行。
        return sb.toString();
    }

    private static String formatBandwidthLine(HassiumMetricsImpl m) {
        long originalBytes = m.getZstdOriginalBytes();
        long compressedBytes = m.getZstdCompressedBytes();
        // 「带宽压缩」行 = 聚合包压缩帧 + shadow pull 分段增量（SectionDeltaS2CPacket 内嵌
        // 载荷，decode 全库唯一调用点在 ShadowPullClient DELTA 终态）：压缩前原始 payload
        // vs 压缩后线缆字节，chunk_payload 等其他通道与未压缩帧不计入。
        // 整体「比无 MOD 少收多少」由下方「流量节省」行汇总（含缓存/光照/SeedGen），
        // 其「数据包」项只累计区块域埋点，聚合全局包不计。
        double saving = originalBytes > 0
                ? (double) (originalBytes - compressedBytes) / originalBytes * 100.0
                : 0.0;
        return String.format("§e带宽压缩：§r%s（原始 %s，压缩后 %s，压缩比 %s）",
                MetricsTextFormatter.formatPercent(saving),
                MetricsTextFormatter.formatBytes(originalBytes),
                MetricsTextFormatter.formatBytes(compressedBytes),
                MetricsTextFormatter.formatCompressionRatio(originalBytes, compressedBytes));
    }

    private static String formatChunkCacheLine(long fullHitCount, long fullHitBytes,
                                              long partialCount, long partialBytes,
                                              long shardBytes, long appliedBytes) {
        // 缓存命中 = (全命中 + 部分命中 - 增量) / 应用来源等价字节，按内容等价值计算。
        long hitBytes = Math.max(0L, fullHitBytes + partialBytes - shardBytes);
        double rate = appliedBytes <= 0L
                ? 0.0
                : (double) Math.min(hitBytes, appliedBytes) / appliedBytes * 100.0;
        return String.format(
                "§e区块缓存：§r%s（全命中 %d/%s，部分命中 %d/%s，增量 %s，应用 %s）",
                MetricsTextFormatter.formatPercent(rate),
                fullHitCount, MetricsTextFormatter.formatBytes(fullHitBytes),
                partialCount, MetricsTextFormatter.formatBytes(partialBytes),
                MetricsTextFormatter.formatBytes(shardBytes),
                MetricsTextFormatter.formatBytes(appliedBytes));
    }

    private static String formatChunkLoadLine(HassiumMetricsImpl m, long loadedCount, long localCount) {
        long serverPush = m.getServerPushAppliedCount();
        long newRequests = m.getNewFullChunkRequestCount() + serverPush;
        long staleRequests = m.getStaleFullChunkRequestCount();
        long newRequestBytes = m.getNewFullChunkRequestBytes()
                + serverPush * NetworkStats.ESTIMATED_CHUNK_BYTES;
        long staleRequestBytes = m.getStaleFullChunkRequestBytes();
        long localBytes = m.getLocallyGeneratedChunkBytes();
        return String.format("§e区块加载：§r%d（新增 %d/%s，过期 %d/%s，本地 %d/%s）",
                loadedCount,
                newRequests, MetricsTextFormatter.formatBytes(newRequestBytes),
                staleRequests, MetricsTextFormatter.formatBytes(staleRequestBytes),
                localCount, MetricsTextFormatter.formatBytes(localBytes));
    }


    private static String formatLightCacheLine(HassiumMetricsImpl m) {
        // 剥光协商（lightComputeSupported=true）下 hasCachedLight 恒 false → 直连命中口径
        // 恒 0；光照复用由影子链路承担（key light.reuse.shadow.*），与直连命中合并展示为
        // 「命中」，不再单列。
        // 命中率按内容等价值字节计（口径与 getLightCacheHitRate 一致，对齐区块缓存行）：
        // （直连命中字节 + 影子复用字节）/（命中 + 本地重算）。影子端本会话重算的光
        // （远程全量注入 / 分段增量 / 磁盘光脏续算）按柱记一次 lightCacheMiss。
        // 邻柱 LIGHT_ONLY 收敛补光不进重算分母（按引擎任务计会把一柱刷成几十次）。
        // OVD/renderOnly 柱由本地影子端全量服务，记复用不进重算分母。
        long lightHit = m.getLightCacheHitCount() + m.getLightReuseShadowCount();
        long lightHitBytes = m.getLightCacheHitBytes() + m.getLightReuseShadowBytes();
        long lightMiss = m.getLightCacheMissCount();
        return String.format("§e光照缓存：§r%s（命中 %d/%s，重算 %d/%s）",
                MetricsTextFormatter.formatPercent(m.getLightCacheHitRate() * 100.0),
                lightHit, MetricsTextFormatter.formatBytes(lightHitBytes),
                lightMiss, MetricsTextFormatter.formatBytes(m.getLightCacheMissBytes()));
    }

    private static String formatSavingsLine(HassiumMetricsImpl m) {
        // 「流量节省」line（用户定稿）：流量节省 = 服务端实际推送 / 无 MOD 时要接收。
        // 无 MOD 应收 = 数据包 + 本地重算（SeedGen）+ 客户端缓存 + 光照（直连命中/影子复用/本地重算），
        // 统一为原版 Zlib 等价 wire。分段增量已按「若走全量的原版 Zlib 等价」计入数据包
        // （recordSectionDeltaReceived），不再单列；OVD 环带不计入（无 MOD 时服务端本来也不推）。
        // 第一段百分比 = 已节省（= 100% - 实际/无MOD），括号内给当前/无MOD 绝对值便于阅读。
        long noModReceive = m.getNoModReceiveBytes();
        long current = m.getActualBytesReceived();
        long saved = Math.max(0L, noModReceive - current);
        double saving = noModReceive > 0 ? (double) saved / noModReceive * 100.0 : 0.0;
        return String.format("§e流量节省：§r%s（当前 %s，无MOD %s）",
                MetricsTextFormatter.formatPercent(saving),
                MetricsTextFormatter.formatBytes(current),
                MetricsTextFormatter.formatBytes(noModReceive));
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
     * 导出影子端世界为不含实体数据的完整存档目录（客户端命令）。
     * <p>
     * 复制影子端世界的区块、POI 和 level.dat；任何路径段为 {@code entities}
     * 的实体存储目录均跳过。影子玩家只存在于当前会话内，不进入导出存档。
     *
     * @param serverIp 服务器 IP:Port（null/空时导出当前连接的服务器缓存）
     * @param seed     保留参数（种子已在影子端 level.dat 中，拷贝即可）
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
                    + "\n§7(不导出 entities；保留 type 126 + chunkHash；level.dat 为影子端原版写出)§r";
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
                Path relative = src.relativize(p);
                boolean entityPath = relative.iterator().hasNext()
                        && java.util.stream.StreamSupport.stream(relative.spliterator(), false)
                        .anyMatch(part -> "entities".equals(part.toString()));
                if (entityPath) {
                    continue;
                }
                Path target = dst.resolve(relative.toString());
                if (Files.isDirectory(p)) {
                    Files.createDirectories(target);
                } else if ("session.lock".equals(p.getFileName().toString())) {
                    continue;
                } else {
                    Files.createDirectories(target.getParent());
                    Files.copy(p, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
    }
    /** 将服务器 IP:Port 转换为缓存目录名（review-fix: T8-27: 收敛到 utils/ServerIdUtil 单一实现）。 */
    private static String sanitizeServerIp(String serverIp) {
        return io.github.limuqy.mc.hassium.utils.ServerIdUtil.sanitize(serverIp);
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

    // ==================== /hassium migrate（B4 迁移演练；仅开发环境注册） ====================

    /** 客户端命令上下文检查：migrate 依赖客户端进程内的 NetworkCore 网关单例。 */
    private static boolean isClientContext() {
        return Minecraft.getInstance() != null;
    }

    private static String clientOnlyMessage() {
        return "§c/hassium migrate 是客户端命令，需在客户端执行§r";
    }

    /** migrate 无参数：用法帮助。L1 迁移已随网络核心裁剪退役，命令保留为退役提示。 */
    public static String migrateUsage() {
        return "§7/hassium migrate 已随主控无感切换裁剪退役（直连拓扑无迁移面）§r";
    }

    /** migrate list：已退役。 */
    public static String migrateList() {
        if (!isClientContext()) {
            return clientOnlyMessage();
        }
        return migrateUsage();
    }

    /** migrate &lt;host:port&gt;：已退役。 */
    public static String migrateTo(String hostPort) {
        if (!isClientContext()) {
            return clientOnlyMessage();
        }
        return migrateUsage();
    }

    /** migrate status：已退役。 */
    public static String migrateStatus() {
        if (!isClientContext()) {
            return clientOnlyMessage();
        }
        return migrateUsage();
    }
}
