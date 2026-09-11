package io.github.limuqy.mc.hassium.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * T4 config-restructure 一次性 round-trip 验证（.omp/workflows/config-restructure）。
 * <p>
 * 验证点（对照 work/key-mapping.md；网关拓扑退役波后更新）：
 * 1. defaults 生成 57 键，前缀分布 21/16/16/2/2
 * 2. client/server toml 写读 round-trip（含全部新键组）
 * 3. 新键可加载抽查（chunk.seedGenEnabled 双端 / master.* 聚合键族）
 * 4. 删键（recoveryFreeze / controlStallMs / failoverExpiryMs / storage.mode / chunk.loadThreads /
 *    网关监听/鉴权/控制面端点/L1 迁移/续流票据/dataplane.*）不再出现在写出的 toml
 * 5. 默认值语义抽查 ≥5 键对照 key-mapping.md
 * <p>
 * 复跑：sh gradlew --no-daemon common:test --tests ConfigRestructureRoundTripTest
 */
class ConfigRestructureRoundTripTest {

    private static final List<String> DELETED_KEYS =
            List.of("recoveryFreeze", "controlStallMs", "failoverExpiryMs", "storage.mode", "loadThreads",
                    "dynamicThreadPoolEnabled", "minPushThreads", "maxPushThreads",
                    // 网关拓扑退役键族（toml 侧按裸键名扫描；schema 侧由 ConfigSchemaTest 兜底）
                    "migrationMinTps", "migrationMaxLoadAverage", "migrationMaintenanceWindow",
                    "migrationHeartbeatIntervalMs", "migrationIdleWindowMs", "migrationSilentTimeoutMs",
                    "migrationFaultTimeoutMs", "migrationPrewarmTtlMs", "resumeTicketTtlMs",
                    "bindHost", "authToken", "controlReachableEndpoints", "udpListeners");

    // === 1. defaults 生成：57 键齐全 ===

    @Test
    void defaultsCoverAll78NewKeys() {
        ConfigValues values = ConfigValues.defaults(ConfigSchema.entries());
        Map<String, ConfigEntry<?>> byPath = ConfigSchema.entries().stream()
                .collect(Collectors.toMap(e -> e.scope() + "/" + e.path(), Function.identity()));

        assertEquals(47, ConfigSchema.entries().size(), "schema 留存键数");
        assertEquals(47, values.asMap().size(), "defaults 键数");

        Map<String, Long> prefixCounts = ConfigSchema.entries().stream()
                .collect(Collectors.groupingBy(e -> e.path().substring(0, e.path().indexOf('.') + 1),
                        Collectors.counting()));
        assertEquals(Map.of("chunk.", 18L, "master.", 10L, "debug.", 15L,
                "storage.", 2L, "compat.", 2L), prefixCounts);

        // 双端同名键 chunk.seedGenEnabled 各一
        assertEquals(1L, ConfigSchema.entries().stream()
                .filter(e -> e.scope() == ConfigScope.CLIENT && e.path().equals("chunk.seedGenEnabled")).count());
        assertEquals(1L, ConfigSchema.entries().stream()
                .filter(e -> e.scope() == ConfigScope.SERVER && e.path().equals("chunk.seedGenEnabled")).count());

        // 删键不得存在于 schema / defaults
        for (String deleted : DELETED_KEYS) {
            assertFalse(byPath.containsKey("CLIENT/" + deleted), deleted);
            assertFalse(byPath.containsKey("SERVER/" + deleted), deleted);
        }
        assertFalse(ConfigSchema.entries().stream().anyMatch(e -> e.path().startsWith("clientCache.")));
        assertFalse(ConfigSchema.entries().stream().anyMatch(e -> e.path().startsWith("network.")));
    }

    // === 2+3. client toml round-trip（chunk./debug. 新键）===

    @Test
    void clientTomlRoundTripsNewKeys(@TempDir Path root) throws IOException {
        HassiumConfig.ChunkCoreConfig chunk = new HassiumConfig.ChunkCoreConfig(
                true, 8192, 0.5, 0.8, 0.2, 1200, 1024, 200,
                true, true, 16, false, 12, 30, 4, true, true);
        HassiumConfig.DebugConfig debug = new HassiumConfig.DebugConfig(
                true, false, true, false, true, false, true, true, true, false);
        // 网关拓扑退役：client.toml 不再承载任何 master.* 键（原迁移策略 6 键已删）
        HassiumConfig.MasterCoreConfig master = HassiumConfig.MasterCoreConfig.DEFAULT;
        HassiumConfig original = new HassiumConfig(
                HassiumConfig.StorageConfig.DEFAULT, chunk, master,
                HassiumConfig.CompatConfig.DEFAULT, debug);
        FabricTomlConfigIO.saveClient(root, original);
        HassiumConfig loaded = FabricTomlConfigIO.loadClient(root);
        assertEquals(chunk, loaded.chunk(), "chunk.* round-trip");
        assertEquals(debug, loaded.debug(), "debug.* round-trip");
        assertEquals(master, loaded.master(), "client toml 不再写 master.* 键 → 读回 DEFAULT");
        String toml = Files.readString(root.resolve("hassium/hassium-client.toml"));
        assertTrue(toml.contains("lightVerify = true"), "client toml 缺 debug.lightVerify=true:\n" + toml);
        // 网关退役键族不得出现在 client.toml
        assertFalse(toml.contains("migrationMinTps"), "client.toml 不应含已退役 master.migrationMinTps:\n" + toml);
        assertFalse(toml.contains("migrationSilentTimeoutMs"), "client.toml 不应含已退役 master.migrationSilentTimeoutMs:\n" + toml);
        assertFalse(toml.contains("authToken"), "client.toml 不应再写 master.authToken:\n" + toml);
        assertFalse(toml.contains("controlReachableEndpoints"),
                "client.toml 不应再写 master.controlReachableEndpoints:\n" + toml);
    }

    // === 2+3. server toml round-trip（master./storage./compat./chunk.lightStrip）===

    @Test
    void serverTomlRoundTripsNewKeys(@TempDir Path root) throws IOException {
        HassiumConfig.MasterCoreConfig master = new HassiumConfig.MasterCoreConfig(
                true, 9, false, false, 8, 50L, 131072,
                Set.of("MAIN_CHANNEL"), 7, 4);
        HassiumConfig.StorageConfig storage = new HassiumConfig.StorageConfig(true, 9);
        // server toml 只写 chunk.lightStrip/chunk.seedGenEnabled 两键，其余键读回默认 → 仅改这两键
        HassiumConfig.ChunkCoreConfig chunk = new HassiumConfig.ChunkCoreConfig(
                true, 4096, 0.3, 0.7, 0.3, 6000, 0, 100,
                true, true, 16, false, 6, 15, 2, false, false);
        HassiumConfig.CompatConfig compat = new HassiumConfig.CompatConfig(true, false);
        HassiumConfig.DebugConfig debug = new HassiumConfig.DebugConfig(
                false, true, false, true, false, true, false, false, false, true);

        HassiumConfig original = new HassiumConfig(storage, chunk, master, compat, debug);
        FabricTomlConfigIO.saveServer(root, original);

        HassiumConfig loaded = FabricTomlConfigIO.loadServer(root);
        assertEquals(storage, loaded.storage(), "storage.* round-trip");
        assertEquals(chunk.seedGenEnabled(), loaded.chunk().seedGenEnabled(), "chunk.seedGenEnabled round-trip");
        assertEquals(chunk.lightStrip(), loaded.chunk().lightStrip(), "chunk.lightStrip round-trip");
        assertEquals(master, loaded.master(), "master.* round-trip（含聚合键族与压缩黑名单）");
        assertEquals(compat, loaded.compat(), "compat.* round-trip");
        assertEquals(debug, loaded.debug(), "debug.* round-trip");

        String toml = Files.readString(root.resolve("hassium/hassium-server.toml"));
        // 网关退役键族不得出现在 server.toml（写侧 legacy cfg.remove 同步清理旧文件残留）
        assertFalse(toml.contains("[[dataplane.udpListeners]]"), "server toml 不应含已退役 dataplane.udpListeners:\n" + toml);
        assertFalse(toml.contains("[[master.controlReachableEndpoints]]"),
                "server toml 不应含已退役 master.controlReachableEndpoints:\n" + toml);
        assertFalse(toml.contains("migrationFaultTimeoutMs"), "server toml 不应含已退役 migrationFaultTimeoutMs:\n" + toml);
        assertFalse(toml.contains("migrationPrewarmTtlMs"), "server toml 不应含已退役 migrationPrewarmTtlMs:\n" + toml);
        assertFalse(toml.contains("migrationMinTps"), "server toml 不应含已退役 migrationMinTps:\n" + toml);
        assertFalse(toml.contains("resumeTicketTtlMs"), "server toml 不应含已退役 resumeTicketTtlMs:\n" + toml);
        assertFalse(toml.contains("bindHost"), "server toml 不应含已退役 master.bindHost:\n" + toml);
        assertFalse(toml.contains("authToken"), "server toml 不应含已退役 master.authToken:\n" + toml);
        assertTrue(toml.contains("seedGenEnabled = false"), "server toml 缺 chunk.seedGenEnabled=false");
        assertTrue(toml.contains("lightStrip = false"), "server toml 缺 chunk.lightStrip=false");
        assertTrue(toml.contains("zstdLevel = 9"), "server toml 缺 storage.zstdLevel=9");
        assertTrue(toml.contains("autoDowngradeOnError = false"), "server toml 缺 compat.autoDowngradeOnError=false");
        assertFalse(toml.contains("metadataLogging"), "server toml 不应含客户端专属 debug.metadataLogging:\n" + toml);
        assertFalse(toml.contains("cacheLogging"), "server toml 不应含客户端专属 debug.cacheLogging:\n" + toml);
        assertFalse(toml.contains("lightVerify"), "server toml 不应含客户端专属 debug.lightVerify:\n" + toml);
    }

    // === 4. 删键不再出现 ===

    @Test
    void writtenTomlOmitsDeletedKeys(@TempDir Path root) throws IOException {
        FabricTomlConfigIO.saveClient(root, HassiumConfig.DEFAULT);
        FabricTomlConfigIO.saveServer(root, HassiumConfig.DEFAULT);

        String clientToml = Files.readString(root.resolve("hassium/hassium-client.toml"));
        String serverToml = Files.readString(root.resolve("hassium/hassium-server.toml"));
        for (String deleted : DELETED_KEYS) {
            assertFalse(clientToml.contains(deleted), "client toml 含删键: " + deleted);
            assertFalse(serverToml.contains(deleted), "server toml 含删键: " + deleted);
        }
        // 旧前缀也不得出现
        assertFalse(clientToml.contains("clientCache."));
        assertFalse(clientToml.contains("network."));
        assertFalse(serverToml.contains("clientCache."));
        assertFalse(serverToml.contains("network."));
        // 写读后值仍为默认
        assertEquals(HassiumConfig.DEFAULT.storage(), FabricTomlConfigIO.loadServer(root).storage());
    }

    // === 5. 默认值语义抽查（对照 work/key-mapping.md）===

    @Test
    void defaultValuesMatchKeyMapping() {
        ConfigValues values = ConfigValues.defaults(ConfigSchema.entries());

        // chunk.seedGenEnabled 双端默认 false（network.seedGen.enabled → chunk.seedGenEnabled, false）
        assertEquals(false, values.get(ConfigSchema.CLIENT_CHUNK_SEED_GEN_ENABLED));
        assertEquals(false, values.get(ConfigSchema.SERVER_CHUNK_SEED_GEN_ENABLED));
        // master 聚合键族默认（enablePacketAggregation=true / minBatch=4 / maxWait=20ms / maxSize=256KiB / compactHeader=true）
        assertEquals(true, values.get(ConfigSchema.MASTER_PACKET_AGGREGATION));
        assertEquals(4, values.get(ConfigSchema.MASTER_AGGREGATION_MIN_BATCH));
        assertEquals(50L, values.get(ConfigSchema.MASTER_AGGREGATION_MAX_WAIT));
        assertEquals(256 * 1024, values.get(ConfigSchema.MASTER_AGGREGATION_MAX_SIZE));
        // master.maxChunksPerTick / serverChunkPushThreads 默认 5 / 4
        assertEquals(5, values.get(ConfigSchema.MASTER_MAX_CHUNKS_PER_TICK));
        assertEquals(4, values.get(ConfigSchema.MASTER_SERVER_PUSH_THREADS));
        // storage.enabled 默认 false（REQ 决策 6 修正 lang 错误）
        assertEquals(false, values.get(ConfigSchema.STORAGE_ENABLED));
        // debug.* 客户端网络指标默认关闭，退出自动复位默认开启
        assertEquals(false, values.get(ConfigSchema.CLIENT_DEBUG_NETWORK_METRICS));
        assertEquals(true, values.get(ConfigSchema.CLIENT_DEBUG_NETWORK_METRICS_AUTO_RESET));
        // chunk 区块核心抽查
        assertEquals(6000, values.get(ConfigSchema.CHUNK_CLEANUP_INTERVAL_TICKS));
        assertEquals(0.3, values.get(ConfigSchema.CHUNK_HOT_SCORE_THRESHOLD));
        // 黑名单 7 项（CHUNK_PAYLOAD_S2C 随 chunk_payload 通道退役移除；旧 SECTION_DELTA_S2C 更早随独立通道删除）
        assertEquals(7, values.get(ConfigSchema.MASTER_COMPRESSION_BLACKLIST).size());
    }
}
