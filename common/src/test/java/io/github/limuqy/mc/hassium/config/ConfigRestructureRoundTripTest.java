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
 * 验证点（对照 work/key-mapping.md）：
 * 1. defaults 生成 71 键，前缀分布 23/3/21/18/2/2/2
 * 2. client/server toml 写读 round-trip（含全部新键组）
 * 3. 新键可加载抽查（chunk.seedGenEnabled 双端 / master.migrationFaultTimeoutMs / dataplane.udpListeners 复杂值）
 * 4. 删键（recoveryFreeze / controlStallMs / failoverExpiryMs / storage.mode / chunk.loadThreads）不再出现在写出的 toml
 * 5. 默认值语义抽查 ≥5 键对照 key-mapping.md
 * <p>
 * 复跑：sh gradlew --no-daemon common:test --tests ConfigRestructureRoundTripTest
 */
class ConfigRestructureRoundTripTest {

    private static final List<String> DELETED_KEYS =
            List.of("recoveryFreeze", "controlStallMs", "failoverExpiryMs", "storage.mode", "loadThreads");

    // === 1. defaults 生成：71 键齐全 ===

    @Test
    void defaultsCoverAll71NewKeys() {
        ConfigValues values = ConfigValues.defaults(ConfigSchema.entries());
        Map<String, ConfigEntry<?>> byPath = ConfigSchema.entries().stream()
                .collect(Collectors.toMap(e -> e.scope() + "/" + e.path(), Function.identity()));

        assertEquals(71, ConfigSchema.entries().size(), "schema 留存键数");
        assertEquals(71, values.asMap().size(), "defaults 键数");

        Map<String, Long> prefixCounts = ConfigSchema.entries().stream()
                .collect(Collectors.groupingBy(e -> e.path().substring(0, e.path().indexOf('.') + 1),
                        Collectors.counting()));
        assertEquals(Map.of("chunk.", 23L, "net.", 3L, "master.", 21L, "debug.", 18L,
                "dataplane.", 2L, "storage.", 2L, "compat.", 2L), prefixCounts);

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

    // === 2+3. client toml round-trip（chunk./net./debug. 新键）===

    @Test
    void clientTomlRoundTripsNewKeys(@TempDir Path root) throws IOException {
        HassiumConfig.ChunkCoreConfig chunk = new HassiumConfig.ChunkCoreConfig(
                true, 8192, 7, 0.5, 0.8, 0.2, 1200, 1024, 200,
                true, false, true, 32, 10, 45, 12, 30, 4, true, true, true, true);
        HassiumConfig.NetCoreConfig net = new HassiumConfig.NetCoreConfig(true, true, false);
        HassiumConfig.DebugConfig debug = new HassiumConfig.DebugConfig(
                true, false, true, false, true, false, true, false, true);

        HassiumConfig original = new HassiumConfig(HassiumConfig.StorageConfig.DEFAULT, chunk, net,
                HassiumConfig.MasterCoreConfig.DEFAULT, HassiumConfig.CompatConfig.DEFAULT, debug);
        FabricTomlConfigIO.saveClient(root, original);

        HassiumConfig loaded = FabricTomlConfigIO.loadClient(root);
        assertEquals(chunk, loaded.chunk(), "chunk.* round-trip");
        assertEquals(net, loaded.net(), "net.* round-trip");
        assertEquals(debug, loaded.debug(), "debug.* round-trip");

        String toml = Files.readString(root.resolve("hassium/hassium-client.toml"));
        // nightconfig 嵌套表格式：[chunk] 表内 seedGenEnabled = true
        assertTrue(toml.contains("seedGenEnabled = true"), "client toml 缺 seedGenEnabled=true:\n" + toml);
        assertTrue(toml.contains("unloadDelaySecs = 45"), "client toml 缺 chunk.unloadDelaySecs=45:\n" + toml);
        assertTrue(toml.contains("[net]"), "client toml 缺 [net] 表");
        assertTrue(toml.contains("lightVerify = true"), "client toml 缺 debug.lightVerify=true");
    }

    // === 2+3. server toml round-trip（master./dataplane./storage./compat./chunk.lightStrip）===

    @Test
    void serverTomlRoundTripsNewKeys(@TempDir Path root) throws IOException {
        HassiumConfig.MasterCoreConfig master = new HassiumConfig.MasterCoreConfig(
                true, 9, false, false, 5, 512, false, false, 8, 50L, 131072, false,
                Set.of("CHUNK_PAYLOAD_S2C", "MAIN_CHANNEL"), true, 7, 4, false, 3, 12,
                List.of(new HassiumConfig.ReachableEndpoint("play.example", 25565, 100),
                        new HassiumConfig.ReachableEndpoint("backup.example", 25565, 80)),
                90_000L,
                new HassiumConfig.DataPlaneConfig(true, List.of(
                        new HassiumConfig.UdpListenerConfig("0.0.0.0", 31001, 60, List.of(
                                new HassiumConfig.ReachableEndpoint("edge-a.example", 41001, 100),
                                new HassiumConfig.ReachableEndpoint("edge-b.example", 42001, 80))),
                        new HassiumConfig.UdpListenerConfig("10.0.0.10", 31002, 40, List.of(
                                new HassiumConfig.ReachableEndpoint("edge-a.example", 43001, 100))))));
        HassiumConfig.StorageConfig storage = new HassiumConfig.StorageConfig(true, 9);
        // server toml 只写 chunk.lightStrip/chunk.seedGenEnabled 两键，其余键读回默认 → 仅改这两键
        HassiumConfig.ChunkCoreConfig chunk = new HassiumConfig.ChunkCoreConfig(
                true, 4096, 3, 0.3, 0.7, 0.3, 6000, 0, 100,
                true, true, true, 16, 5, 30, 6, 15, 2, true, false, true, false);
        HassiumConfig.CompatConfig compat = new HassiumConfig.CompatConfig(true, false);
        HassiumConfig.DebugConfig debug = new HassiumConfig.DebugConfig(
                false, true, false, true, false, true, false, true, false);

        HassiumConfig original = new HassiumConfig(storage, chunk,
                HassiumConfig.NetCoreConfig.DEFAULT, master, compat, debug);
        FabricTomlConfigIO.saveServer(root, original);

        HassiumConfig loaded = FabricTomlConfigIO.loadServer(root);
        assertEquals(storage, loaded.storage(), "storage.* round-trip");
        assertEquals(chunk, loaded.chunk(), "chunk.lightStrip/seedGenEnabled round-trip");
        assertEquals(master, loaded.master(), "master.*+dataplane.* round-trip（含 migrationFaultTimeoutMs/udpListeners 复杂值）");
        assertEquals(compat, loaded.compat(), "compat.* round-trip");
        assertEquals(debug, loaded.debug(), "debug.* round-trip");

        String toml = Files.readString(root.resolve("hassium/hassium-server.toml"));
        assertTrue(toml.contains("[[dataplane.udpListeners]]"));
        assertTrue(toml.contains("[[dataplane.udpListeners.reachableEndpoints]]"));
        assertTrue(toml.contains("[[master.controlReachableEndpoints]]"));
        // nightconfig 嵌套表格式：scalar 键在 [master] 表内
        assertTrue(toml.contains("migrationFaultTimeoutMs = 90000"), "server toml 缺 migrationFaultTimeoutMs=90000:\n" + toml);
        assertTrue(toml.contains("seedGenEnabled = true"), "server toml 缺 chunk.seedGenEnabled=true");
        assertTrue(toml.contains("lightStrip = false"), "server toml 缺 chunk.lightStrip=false");
        assertTrue(toml.contains("zstdLevel = 9"), "server toml 缺 storage.zstdLevel=9");
        assertTrue(toml.contains("autoDowngradeOnError = false"), "server toml 缺 compat.autoDowngradeOnError=false");
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
        // recoveryWindowMs → master.migrationFaultTimeoutMs = 60000（语义化迁移）
        assertEquals(60_000L, values.get(ConfigSchema.MASTER_MIGRATION_FAULT_TIMEOUT_MS));
        // master.maxChunksPerTick 以 schema 为准 = 5（REQ 决策 6）
        assertEquals(5, values.get(ConfigSchema.MASTER_MAX_CHUNKS_PER_TICK));
        // dataplane.udpListeners 默认编码 [0.0.0.0:25565 (w=100) → 127.0.0.1:25565 (w=100)]
        assertEquals(List.of("0.0.0.0,25565,100;127.0.0.1,25565,100"),
                values.get(ConfigSchema.DATAPLANE_UDP_LISTENERS));
        // master.controlReachableEndpoints 默认空表
        assertEquals(List.of(), values.get(ConfigSchema.MASTER_CONTROL_ENDPOINTS));
        // storage.enabled 默认 false（REQ 决策 6 修正 lang 错误）
        assertEquals(false, values.get(ConfigSchema.STORAGE_ENABLED));
        // net.* 网关键
        assertEquals(true, values.get(ConfigSchema.NET_ENABLED));
        assertEquals(false, values.get(ConfigSchema.NET_METRICS_ENABLED));
        assertEquals(true, values.get(ConfigSchema.NET_METRICS_AUTO_RESET));
        // chunk 区块核心抽查
        assertEquals(16, values.get(ConfigSchema.CHUNK_MAX_RENDER_DISTANCE));
        assertEquals(6000, values.get(ConfigSchema.CHUNK_CLEANUP_INTERVAL_TICKS));
        assertEquals(0.3, values.get(ConfigSchema.CHUNK_HOT_SCORE_THRESHOLD));
        // 黑名单 10 项
        assertEquals(10, values.get(ConfigSchema.MASTER_COMPRESSION_BLACKLIST).size());
    }
}
