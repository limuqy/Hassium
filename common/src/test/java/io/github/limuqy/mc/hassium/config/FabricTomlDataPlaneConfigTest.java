package io.github.limuqy.mc.hassium.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FabricTomlDataPlaneConfigTest {

    @Test
    void serverTomlRoundTripsControlAndGroupedUdpEndpoints(@TempDir Path root) throws IOException {
        HassiumConfig.DataPlaneConfig dataPlane = new HassiumConfig.DataPlaneConfig(true, List.of(
                new HassiumConfig.UdpListenerConfig("0.0.0.0", 31001, 60, List.of(
                        new HassiumConfig.ReachableEndpoint("edge-a.example", 41001, 100),
                        new HassiumConfig.ReachableEndpoint("edge-b.example", 42001, 80))),
                new HassiumConfig.UdpListenerConfig("10.0.0.10", 31002, 40, List.of(
                        new HassiumConfig.ReachableEndpoint("edge-a.example", 43001, 100)))));
        HassiumConfig.MasterCoreConfig master = withEndpoints(
                List.of(
                        new HassiumConfig.ReachableEndpoint("play.example", 25565, 100),
                        new HassiumConfig.ReachableEndpoint("backup.example", 25565, 80)),
                dataPlane);

        FabricTomlConfigIO.saveServer(root, new HassiumConfig(
                HassiumConfig.StorageConfig.DEFAULT,
                HassiumConfig.ChunkCoreConfig.DEFAULT,
                HassiumConfig.NetCoreConfig.DEFAULT,
                master,
                HassiumConfig.CompatConfig.DEFAULT,
                HassiumConfig.DebugConfig.DEFAULT));
        HassiumConfig.MasterCoreConfig loaded = FabricTomlConfigIO.loadServer(root).master();

        assertEquals(master.controlReachableEndpoints(), loaded.controlReachableEndpoints());
        assertEquals(master.dataPlane(), loaded.dataPlane());
        String toml = Files.readString(root.resolve("hassium/hassium-server.toml"));
        assertTrue(toml.contains("[[master.controlReachableEndpoints]]"));
        assertTrue(toml.contains("[[dataplane.udpListeners]]"));
        assertTrue(toml.contains("[[dataplane.udpListeners.reachableEndpoints]]"));
    }

    @Test
    void invalidTomlEndpointFallsBackOnlyThatField(@TempDir Path root) throws IOException {
        Path config = root.resolve("hassium/hassium-server.toml");
        Files.createDirectories(config.getParent());
        Files.writeString(config, """
                [master]
                enabled = true
                [[master.controlReachableEndpoints]]
                host = "0.0.0.0"
                port = 25565
                priority = 100
                [dataplane]
                enabled = true
                # 已删键（controlStallMs/failoverExpiryMs）残留不影响加载
                controlStallMs = -1
                """);

        HassiumConfig.MasterCoreConfig loaded = FabricTomlConfigIO.loadServer(root).master();

        assertTrue(loaded.enabled());
        // wildcard host 不可作为可达端点下发 → 回退空表
        assertEquals(List.of(), loaded.controlReachableEndpoints());
        assertEquals(HassiumConfig.MasterCoreConfig.DEFAULT.dataPlane(), loaded.dataPlane());
    }

    @Test
    void disabledDataPlanePreservesAnEmptyListenerList(@TempDir Path root) throws IOException {
        Path config = root.resolve("hassium/hassium-server.toml");
        Files.createDirectories(config.getParent());
        Files.writeString(config, """
                [dataplane]
                enabled = false
                udpListeners = []
                """);

        HassiumConfig.DataPlaneConfig dataPlane = FabricTomlConfigIO.loadServer(root).master().dataPlane();

        assertTrue(!dataPlane.enabled());
        assertEquals(List.of(), dataPlane.udpListeners());
    }


    private static HassiumConfig.MasterCoreConfig withEndpoints(
            List<HassiumConfig.ReachableEndpoint> controlEndpoints,
            HassiumConfig.DataPlaneConfig dataPlane
    ) {
        HassiumConfig.MasterCoreConfig d = HassiumConfig.MasterCoreConfig.DEFAULT;
        return new HassiumConfig.MasterCoreConfig(
                d.enabled(), d.compressionLevel(), d.magiclessZstd(), d.globalPacketCompression(),
                d.globalCompressionLevel(), d.globalCompressionThreshold(), d.useContextCompression(),
                d.enablePacketAggregation(), d.aggregationMinBatchSize(), d.aggregationMaxWaitTimeMs(),
                d.aggregationMaxSize(), d.enableCompactHeader(), d.compressionBlacklist(), d.metricsEnabled(),
                d.maxChunksPerTick(), d.serverChunkPushThreads(),
                d.bindHost(), d.authToken(),
                controlEndpoints, d.migrationFaultTimeoutMs(),
                d.migrationMinTps(), d.migrationMaxLoadAverage(),
                d.migrationMaintenanceWindow(), d.migrationHeartbeatIntervalMs(),
                d.migrationIdleWindowMs(), d.migrationSilentTimeoutMs(),
                d.migrationPrewarmTtlMs(), d.resumeTicketTtlMs(), dataPlane);
    }
}
