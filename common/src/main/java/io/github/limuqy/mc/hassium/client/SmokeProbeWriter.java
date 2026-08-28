package io.github.limuqy.mc.hassium.client;

import io.github.limuqy.mc.hassium.cache.client.ViewDistanceExtensionService;
import io.github.limuqy.mc.hassium.metrics.HassiumMetricsImpl;
import io.github.limuqy.mc.hassium.metrics.NetworkStats;
import io.github.limuqy.mc.hassium.network.ClientChunkPipeline;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.stream.Stream;

/**
 * T1 PROBE JSON 落盘：每轮冒烟统计输出时，把 metrics 原值 / 网关状态 / 计数器 /
 * 影子端 region 目录状态写成 {@code roundN.json}（手拼字符串，零新依赖）。
 * <p>
 * 目标目录由 JVM 属性 {@code hassium.smokeTest.probeDir} 指定（harness 注入
 * {@code build/smoke-test/probe}）；属性未设置时本类整体 no-op——不建目录、
 * 不写文件、无任何日志输出，行为与未接入前完全一致。
 * <p>
 * JSON v1 顶层键（T2 harness 依赖，只增不改名不删）：
 * {@code round, timestampMs, joined, dimension, playerPos[x,y,z],
 * stats, gateway{state,resumeAccepted,c2s,s2c},
 * counters{ovdLoaded,sectionDeltaApplied,lightSegRecalc,locallyGenerated,...},
 * disk{shadowRegionExists,regionFileCount,cacheDir,dimensions{overworld|nether|end:{regionFileCount}}}。
 * 未进服轮次 {@code joined=false}，dimension/playerPos 为 null。
 */
public final class SmokeProbeWriter {

    private static final Logger LOGGER = LoggerFactory.getLogger("Hassium/SmokeTest");

    private SmokeProbeWriter() {
    }

    /**
     * 写一轮探针文件。任何内部失败只记 warn，绝不影响冒烟状态机判定/退出码。
     *
     * @param round 轮次（1 或 2；文件名 roundN.json）
     * @param mc    客户端实例（player 为空按 joined=false 处理）
     */
    public static void writeRound(int round, net.minecraft.client.Minecraft mc) {
        String dir = System.getProperty("hassium.smokeTest.probeDir");
        if (dir == null || dir.isBlank()) {
            return; // probeDir 未设置：零行为变化
        }
        try {
            boolean joined = mc != null && mc.player != null && mc.level != null;
            String dimension = null;
            if (joined) {
                // 与 ClientSmokeTest MIGRATE_POS_* 同款跨版本取法（1.21.11 起 ResourceKey 改 identifier()）
                dimension = mc.player.level().dimension()
#if MC_VER < MC_1_21_11
                        .location()
#else
                        .identifier()
#endif
                        .toString();
            }
            StringBuilder sb = new StringBuilder(4096);
            sb.append("{\n");
            sb.append("  \"round\": ").append(round).append(",\n");
            sb.append("  \"timestampMs\": ").append(System.currentTimeMillis()).append(",\n");
            sb.append("  \"joined\": ").append(joined).append(",\n");
            sb.append("  \"dimension\": ");
            sb.append(dimension == null ? "null" : jsonString(dimension));
            sb.append(",\n");
            sb.append("  \"playerPos\": ");
            if (joined) {
                sb.append('[')
                        .append(posJson(mc.player.getX())).append(", ")
                        .append(posJson(mc.player.getY())).append(", ")
                        .append(posJson(mc.player.getZ())).append(']');
            } else {
                sb.append("null");
            }
            sb.append(",\n");
            appendStats(sb, NetworkStats.getMetrics());
            appendClientCache(sb, mc);
            appendGateway(sb);
            appendCounters(sb);
            appendDisk(sb);
            appendChunkTrace(sb, dimension);
            sb.append("}\n");

            Path out = Path.of(dir, "round" + round + ".json");
            Files.createDirectories(out.getParent());
            Files.writeString(out, sb.toString());
            LOGGER.info("HassiumSmokeTest:PROBE_WRITTEN ROUND{} -> {}", round, out);
        } catch (Throwable t) {
            LOGGER.warn("HassiumSmokeTest: probe write failed (round {})", round, t);
        }
    }

    /** stats：metrics 原值快照，字段名照 HassiumMetricsImpl getter 对应 AtomicLong 命名。 */
    private static void appendStats(StringBuilder sb, HassiumMetricsImpl m) {
        sb.append("  \"stats\": {\n");
        field(sb, "vanillaBytesReceived", m.getVanillaBytesReceived());
        field(sb, "actualBytesReceived", m.getActualBytesReceived());
        field(sb, "vanillaBytesSent", m.getVanillaBytesSent());
        field(sb, "actualBytesSent", m.getActualBytesSent());
        field(sb, "metadataBytesSent", m.getMetadataBytesSent());
        field(sb, "metadataBytesReceived", m.getMetadataBytesReceived());
        field(sb, "chunksCompressed", m.getChunksCompressed());
        field(sb, "chunksDecompressed", m.getChunksDecompressed());
        field(sb, "fullChunkRequestCount", m.getFullChunkRequestCount());
        field(sb, "newFullChunkRequestCount", m.getNewFullChunkRequestCount());
        field(sb, "staleFullChunkRequestCount", m.getStaleFullChunkRequestCount());
        field(sb, "cacheHitFullChunkCount", m.getCacheHitFullChunkCount());
        field(sb, "cacheHitFullChunkBytes", m.getCacheHitFullChunkBytes());
        field(sb, "cacheDeltaCount", m.getCacheDeltaCount());
        field(sb, "cacheDeltaSavedBytes", m.getCacheDeltaSavedBytes());
        field(sb, "cacheShardBytes", m.getCacheShardBytes());
        field(sb, "locallyGeneratedChunkCount", m.getLocallyGeneratedChunkCount());
        field(sb, "locallyGeneratedChunkBytes", m.getLocallyGeneratedChunkBytes());
        field(sb, "clientAppliedChunkCount", m.getClientAppliedChunkCount());
        field(sb, "clientLandedChunkCount", m.getClientLandedChunkCount());
        field(sb, "sectionDeltaRequestsSent", m.getSectionDeltaRequestsSent());
        field(sb, "sectionDeltaChunksReceived", m.getSectionDeltaChunksReceived());
        field(sb, "lightCacheHitCount", m.getLightCacheHitCount());
        field(sb, "lightCacheHitBytes", m.getLightCacheHitBytes());
        field(sb, "lightReuseShadowCount", m.getLightReuseShadowCount());
        field(sb, "lightReuseShadowBytes", m.getLightReuseShadowBytes());
        field(sb, "lightCacheMissCount", m.getLightCacheMissCount());
        lastField(sb, "noModReceiveBytes", m.getNoModReceiveBytes());
        sb.append("  },\n");
    }
    /** 客户端实际缓存快照：用于区分统计已应用与 ClientChunkCache 中真实存在的区块。 */
    private static void appendClientCache(StringBuilder sb, net.minecraft.client.Minecraft mc) {
        long loaded = -1L;
        long renderOnly = -1L;
        if (mc != null && mc.level != null) {
            try {
                net.minecraft.client.multiplayer.ClientLevel level = mc.level;
                loaded = ((io.github.limuqy.mc.hassium.mixin.ClientLevelAccessor) level)
                        .hassium$getChunkSource().getLoadedChunksCount();
                renderOnly = ((io.github.limuqy.mc.hassium.cache.client.IClientLevelExtension) level)
                        .hassium$getRenderOnlyChunks().size();
            } catch (Throwable ignored) {
                // 保持未知值，探针失败不影响场景状态机。
            }
        }
        sb.append("  \"clientCache\": {\n");
        field(sb, "loadedChunks", loaded);
        field(sb, "renderOnlyChunks", renderOnly);
        lastField(sb, "authoritativeEstimate", loaded >= 0L && renderOnly >= 0L
                ? Math.max(0L, loaded - renderOnly) : -1L);
        sb.append("  },\n");
    }


    /** gateway：NetworkCore 只读公开 API；读取异常按 dumpGatewayAssertion 同语义降级为 ERROR。 */
    private static void appendGateway(StringBuilder sb) {
        String state;
        boolean resumeAccepted;
        long c2s;
        long s2c;
        try {
            io.github.limuqy.mc.hassium.network.core.NetworkCore core =
                    io.github.limuqy.mc.hassium.network.core.NetworkCore.getInstance();
            state = core.state().toString();
            resumeAccepted = core.lastResumeAccepted();
            c2s = core.c2sRoutedCount();
            s2c = core.s2cDispatchedCount();
        } catch (Throwable t) {
            state = "ERROR";
            resumeAccepted = false;
            c2s = 0L;
            s2c = 0L;
        }
        sb.append("  \"gateway\": {\n");
        strField(sb, "state", state);
        sb.append("    \"resumeAccepted\": ").append(resumeAccepted).append(",\n");
        field(sb, "c2s", c2s);
        lastField(sb, "s2c", s2c);
        sb.append("  },\n");
    }

    /**
     * counters：真实字段来源——
     * ovdLoaded = ViewDistanceExtensionService.loadedRenderOnly（getLoadedCount）；
     * sectionDeltaApplied = HassiumMetricsImpl.sectionDeltaChunksReceived；
     * lightSegRecalc = lightCacheMissCount（[LIGHT-SEG] 增量分段重算在光屏障提交时记 recordLightCacheMiss）；
     * locallyGenerated = locallyGeneratedChunkCount（SeedGen 本地生成）。
     */
    private static void appendCounters(StringBuilder sb) {
        HassiumMetricsImpl m = NetworkStats.getMetrics();
        ViewDistanceExtensionService ovd = ViewDistanceExtensionService.getInstance();
        long ovdLoaded;
        long ovdPendingMiss;
        long ovdShadowServed;
        try {
            ovdLoaded = ovd.getLoadedCount();
            ovdPendingMiss = ovd.getPendingMissCount();
            ovdShadowServed = ovd.getShadowServedCount();
        } catch (Throwable t) {
            ovdLoaded = -1L;
            ovdPendingMiss = -1L;
            ovdShadowServed = -1L;
        }
        sb.append("  \"counters\": {\n");
        field(sb, "ovdLoaded", ovdLoaded);
        field(sb, "ovdPendingMiss", ovdPendingMiss);
        field(sb, "ovdShadowServed", ovdShadowServed);
        field(sb, "hashMemoryHit", io.github.limuqy.mc.hassium.network.seedgen.ShadowLightCompute.hashMemoryHitCount());
        field(sb, "hashMemoryMismatch", io.github.limuqy.mc.hassium.network.seedgen.ShadowLightCompute.hashMemoryMismatchCount());
        field(sb, "hashDiskHit", io.github.limuqy.mc.hassium.network.seedgen.ShadowLightCompute.hashDiskHitCount());
        field(sb, "hashDiskMismatch", io.github.limuqy.mc.hassium.network.seedgen.ShadowLightCompute.hashDiskMismatchCount());
        field(sb, "hashAbsent", io.github.limuqy.mc.hassium.network.seedgen.ShadowLightCompute.hashAbsentCount());
        field(sb, "hashLeftover", io.github.limuqy.mc.hassium.network.seedgen.ShadowLightCompute.hashLeftoverCount());
        field(sb, "hashEntriesProcessed", io.github.limuqy.mc.hassium.network.seedgen.ShadowLightCompute.hashEntriesProcessedCount());
        field(sb, "hashEntriesUnique", io.github.limuqy.mc.hassium.network.seedgen.ShadowLightCompute.hashEntriesUniqueCount());
        field(sb, "hashChunkKeysUnique", io.github.limuqy.mc.hassium.network.seedgen.ShadowLightCompute.hashChunkKeysUniqueCount());
        field(sb, "hashEntryDuplicates", io.github.limuqy.mc.hassium.network.seedgen.ShadowLightCompute.hashEntryDuplicatesCount());
        field(sb, "hashMemoryMismatchUnique", io.github.limuqy.mc.hassium.network.seedgen.ShadowLightCompute.hashMemoryMismatchUniqueCount());
        field(sb, "hashMemoryMismatchDuplicates", io.github.limuqy.mc.hassium.network.seedgen.ShadowLightCompute.hashMemoryMismatchDuplicatesCount());
        field(sb, "hashDiskMismatchUnique", io.github.limuqy.mc.hassium.network.seedgen.ShadowLightCompute.hashDiskMismatchUniqueCount());
        field(sb, "hashDiskMismatchDuplicates", io.github.limuqy.mc.hassium.network.seedgen.ShadowLightCompute.hashDiskMismatchDuplicatesCount());
        field(sb, "hashLeftoverUnique", io.github.limuqy.mc.hassium.network.seedgen.ShadowLightCompute.hashLeftoverUniqueCount());
        field(sb, "hashLeftoverUniqueChunkKeys", io.github.limuqy.mc.hassium.network.seedgen.ShadowLightCompute.hashLeftoverUniqueChunkKeysCount());
        field(sb, "hashLeftoverDuplicates", io.github.limuqy.mc.hassium.network.seedgen.ShadowLightCompute.hashLeftoverDuplicatesCount());
        field(sb, "sectionDeltaRequestsSent", m.getSectionDeltaRequestsSent());
        field(sb, "sectionDeltaApplied", m.getSectionDeltaChunksReceived());
        field(sb, "lightSegRecalc", m.getLightCacheMissCount());
        lastField(sb, "locallyGenerated", m.getLocallyGeneratedChunkCount());
        sb.append("  },\n");
    }

    /**
     * disk：影子端存档 {@code hassium_cache/<serverId>/world} 检查；位置未知时数值皆 null/false。
     * T7 追加 {@code dimensions} 子对象（只增不改既有键）：影子端为原版存档结构，
     * 维度目录 overworld=region、nether=DIM-1/region、end=DIM1/region（docs/chunk-cache.md §12）。
     */
    private static void appendDisk(StringBuilder sb) {
        boolean shadowRegionExists = false;
        long regionFileCount = 0L;
        String cacheDir = null;
        long overworldRegions = -1L;
        long netherRegions = -1L;
        long endRegions = -1L;
        try {
            Path gameDir = ClientChunkPipeline.getInstance().getGameDir();
            String serverId = ClientChunkPipeline.getInstance().getServerId();
            if (gameDir != null && serverId != null) {
                Path world = gameDir.resolve("hassium_cache").resolve(serverId).resolve("world");
                Path region = world.resolve("region");
                cacheDir = region.toString();
                shadowRegionExists = Files.isDirectory(region);
                if (shadowRegionExists) {
                    try (Stream<Path> files = Files.list(region)) {
                        regionFileCount = files.filter(Files::isRegularFile).count();
                    }
                }
                overworldRegions = countRegionFiles(world.resolve("region"));
                netherRegions = countRegionFiles(world.resolve("DIM-1").resolve("region"));
                endRegions = countRegionFiles(world.resolve("DIM1").resolve("region"));
            }
        } catch (Throwable ignored) {
            // 保持默认值（false/0/null/-1）
        }
        sb.append("  \"disk\": {\n");
        sb.append("    \"shadowRegionExists\": ").append(shadowRegionExists).append(",\n");
        field(sb, "regionFileCount", regionFileCount);
        sb.append("    \"cacheDir\": ").append(cacheDir == null ? "null" : jsonString(cacheDir)).append(",\n");
        sb.append("    \"dimensions\": {\n");
        sb.append("      \"overworld\": {\"regionFileCount\": ").append(overworldRegions).append("},\n");
        sb.append("      \"nether\": {\"regionFileCount\": ").append(netherRegions).append("},\n");
        sb.append("      \"end\": {\"regionFileCount\": ").append(endRegions).append("}\n");
        sb.append("    }\n");
        sb.append("  },\n");
    }
    private static void appendChunkTrace(StringBuilder sb, String dimension) {
        io.github.limuqy.mc.hassium.network.seedgen.SmokeChunkTrace.Snapshot trace =
                io.github.limuqy.mc.hassium.network.seedgen.SmokeChunkTrace.snapshot(dimension);
        sb.append("  \"chunkTrace\": {\n");
        appendTraceStage(sb, "networkReceived", trace.networkReceived(), true);
        appendTraceStage(sb, "shadowInjected", trace.shadowInjected(), true);
        appendTraceStage(sb, "shadowReady", trace.shadowReady(), true);
        appendTraceStage(sb, "clientApplied", trace.clientApplied(), true);
        appendTraceStage(sb, "meshCompiled", trace.meshCompiled(), true);
        appendTraceStage(sb, "receivedNotInjected", trace.receivedNotInjected(), true);
        appendTraceStage(sb, "injectedNotReady", trace.injectedNotReady(), true);
        appendTraceStage(sb, "readyNotApplied", trace.readyNotApplied(), true);
        appendTraceStage(sb, "appliedNotMeshed", trace.appliedNotMeshed(), false);
        sb.append("  }\n");
    }

    private static void appendTraceStage(StringBuilder sb, String name,
                                         java.util.List<net.minecraft.world.level.ChunkPos> positions,
                                         boolean trailingComma) {
        sb.append("    \"").append(name).append("\": {\"count\": ")
                .append(positions.size()).append(", \"positions\": [");
        for (int i = 0; i < positions.size(); i++) {
            net.minecraft.world.level.ChunkPos pos = positions.get(i);
            if (i > 0) {
                sb.append(", ");
            }
            sb.append('[').append(pos.x).append(", ").append(pos.z).append(']');
        }
        sb.append("]}");
        if (trailingComma) {
            sb.append(',');
        }
        sb.append('\n');
    }

    /** 统计维度 region 目录下的常规文件数；目录不存在返回 -1（与未知/未落盘区分）。 */
    private static long countRegionFiles(Path regionDir) {
        if (!Files.isDirectory(regionDir)) {
            return -1L;
        }
        try (Stream<Path> files = Files.list(regionDir)) {
            return files.filter(Files::isRegularFile).count();
        } catch (Throwable t) {
            return -1L;
        }
    }

    /** 带尾逗号的数值字段（对象内非末位）。 */
    private static void field(StringBuilder sb, String name, long value) {
        sb.append("    \"").append(name).append("\": ").append(value).append(",\n");
    }

    /** 对象末位数值字段（无尾逗号）。 */
    private static void lastField(StringBuilder sb, String name, long value) {
        sb.append("    \"").append(name).append("\": ").append(value).append('\n');
    }

    /** 带尾逗号的字符串字段。 */
    private static void strField(StringBuilder sb, String name, String value) {
        sb.append("    \"").append(name).append("\": ").append(jsonString(value)).append(",\n");
    }

    /** 坐标用定点小数（6 位），避免科学计数法进 JSON。 */
    private static String posJson(double v) {
        return String.format(Locale.ROOT, "%.6f", v);
    }

    private static String jsonString(String s) {
        StringBuilder out = new StringBuilder(s.length() + 8);
        out.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (c < 0x20) {
                        out.append(String.format(Locale.ROOT, "\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        out.append('"');
        return out.toString();
    }
}
