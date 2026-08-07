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
                        new HassiumConfig.ReachableEndpoint("edge-a.example", 43001, 100)))),
                6_000L, 30_000L, 60_000L);
        HassiumConfig.ServerNetworkConfig network = withEndpoints(
                List.of(
                        new HassiumConfig.ReachableEndpoint("play.example", 25565, 100),
                        new HassiumConfig.ReachableEndpoint("backup.example", 25565, 80)),
                dataPlane);

        FabricTomlConfigIO.saveServer(root, new HassiumConfig(
                HassiumConfig.StorageConfig.DEFAULT,
                HassiumConfig.ClientCacheConfig.DEFAULT,
                HassiumConfig.ClientNetworkConfig.DEFAULT,
                network,
                HassiumConfig.CompatConfig.DEFAULT,
                HassiumConfig.DebugConfig.DEFAULT));
        HassiumConfig.ServerNetworkConfig loaded = FabricTomlConfigIO.loadServer(root).serverNetwork();

        assertEquals(network.controlReachableEndpoints(), loaded.controlReachableEndpoints());
        assertEquals(network.dataPlane(), loaded.dataPlane());
        String toml = Files.readString(root.resolve("hassium/hassium-server.toml"));
        assertTrue(toml.contains("[[network.controlReachableEndpoints]]"));
        assertTrue(toml.contains("[[network.dataPlane.udpListeners]]"));
        assertTrue(toml.contains("[[network.dataPlane.udpListeners.reachableEndpoints]]"));
    }

    @Test
    void invalidTomlEndpointFallsBackOnlyThatField(@TempDir Path root) throws IOException {
        Path config = root.resolve("hassium/hassium-server.toml");
        Files.createDirectories(config.getParent());
        Files.writeString(config, """
                [network]
                enabled = true
                [[network.controlReachableEndpoints]]
                host = "0.0.0.0"
                port = 25565
                priority = 100
                [network.dataPlane]
                enabled = true
                controlStallMs = -1
                """);

        HassiumConfig.ServerNetworkConfig loaded = FabricTomlConfigIO.loadServer(root).serverNetwork();

        assertTrue(loaded.enabled());
        assertEquals(List.of(), loaded.controlReachableEndpoints());
        assertEquals(HassiumConfig.ServerNetworkConfig.DEFAULT.dataPlane().controlStallMs(),
                loaded.dataPlane().controlStallMs());
    }

    @Test
    void disabledDataPlanePreservesAnEmptyListenerList(@TempDir Path root) throws IOException {
        Path config = root.resolve("hassium/hassium-server.toml");
        Files.createDirectories(config.getParent());
        Files.writeString(config, """
                [network.dataPlane]
                enabled = false
                udpListeners = []
                """);

        HassiumConfig.DataPlaneConfig dataPlane = FabricTomlConfigIO.loadServer(root).serverNetwork().dataPlane();

        assertTrue(!dataPlane.enabled());
        assertEquals(List.of(), dataPlane.udpListeners());
    }


    private static HassiumConfig.ServerNetworkConfig withEndpoints(
            List<HassiumConfig.ReachableEndpoint> controlEndpoints,
            HassiumConfig.DataPlaneConfig dataPlane
    ) {
        HassiumConfig.ServerNetworkConfig d = HassiumConfig.ServerNetworkConfig.DEFAULT;
        return new HassiumConfig.ServerNetworkConfig(
                d.enabled(), d.compressionLevel(), d.magiclessZstd(), d.globalPacketCompression(),
                d.globalCompressionLevel(), d.globalCompressionThreshold(), d.useContextCompression(),
                d.enablePacketAggregation(), d.aggregationMinBatchSize(), d.aggregationMaxWaitTimeMs(),
                d.aggregationMaxSize(), d.enableCompactHeader(), d.compressionBlacklist(), d.metricsEnabled(),
                d.maxChunksPerTick(), d.serverChunkPushThreads(), d.dynamicThreadPoolEnabled(),
                d.minPushThreads(), d.maxPushThreads(), d.lightStrip(), d.seedGenEnabled(),
                controlEndpoints, dataPlane);
    }
}
