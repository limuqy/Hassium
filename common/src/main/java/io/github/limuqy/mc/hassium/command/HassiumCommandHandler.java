package io.github.limuqy.mc.hassium.command;


import io.github.limuqy.mc.hassium.cache.client.ViewDistanceExtensionService;
import io.github.limuqy.mc.hassium.metrics.HassiumMetricsImpl;
import io.github.limuqy.mc.hassium.metrics.MetricsTextFormatter;
import io.github.limuqy.mc.hassium.metrics.NetworkStats;
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

    private static String formatOvdLine() {
        ViewDistanceExtensionService ovd = ViewDistanceExtensionService.getInstance();
        if (!ovd.isEnabled()) {
            return "§e超视渲染：§r§7OFF§r";
        }
        // 拆分"渲染 N/M"与"已加载/缺失"两段，一目了然区分客户端渲染半径 vs 服务端推送半径
        // 影子复用 = 影子端 hash 比对命中（内存/磁盘读回）直接服务的区块数（T5g）：
        // 这些区块经官方通道以普通区块落地，不进入 loaded 集合，单独展示；
        // 「已加载」仍为 renderOnly 落地数（既有语义不变）。
        // T7 口径对齐：「环带服务」= 已加载 + 影子复用（会话内环带由本地存储服务的累计数），
        // 与 slm_final 长会话基线 OVD loaded（>1100，长会话累计）同族可比——基线只统计
        // renderOnly 落地，本值并入影子端直推后口径更全；窗口语义（10s/20s 快照 vs 长会话）
        // 由对比文档标注，不作为单值跨窗口硬比。
        long ringServed = ovd.getLoadedCount() + ovd.getShadowServedCount();
        return String.format(
                "§e超视渲染：§r§aON§r（§a渲染 %d/%d§r，已加载 %d，缺失 %d，影子复用 %d，环带服务 %d）",
                ovd.getLastClientVD(), ovd.getLastServerVD(),
                ovd.getLoadedCount(), ovd.getPendingMissCount(), ovd.getShadowServedCount(), ringServed);
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
     * 导出影子端世界为完整存档目录（客户端命令）。
     * <p>
     * 直接拷贝影子端世界目录 {@code hassium_cache/<serverId>/world}
     * 到 {@code hassium_exports/<serverId>}。{@code level.dat} 由影子端用原版
     * {@code saveDataTag} 写出（含 WorldOptions 种子），导出不再改写 NBT。
     * 亦可不进游戏，把该 {@code world} 目录复制到 {@code saves/} 当单机存档
     * （须已离开服务器，避免 session.lock；格式仍为 type 126）。
     * <p>
     * 异步执行；进度通过聊天回报。
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
                    + "\n§7亦可把 hassium_cache/" + cacheId + "/world 直接复制到 saves/§r"
                    + "\n§7(保留 type 126 + chunkHash；level.dat 为影子端原版写出)§r";
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

    /** migrate 无参数：用法帮助（命令仅在开发环境注册）。 */
    public static String migrateUsage() {
        return "§6=== /hassium migrate 用法（开发环境）===§r\n" +
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
