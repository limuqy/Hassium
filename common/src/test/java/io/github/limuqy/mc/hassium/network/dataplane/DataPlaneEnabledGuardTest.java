package io.github.limuqy.mc.hassium.network.dataplane;

import io.github.limuqy.mc.hassium.config.HassiumConfig;
import io.github.limuqy.mc.hassium.config.HassiumConfigService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * {@code network.dataPlane.enabled=false} 必须不绑定 UDP，并让调用方保留 Primary 回退。
 */
class DataPlaneEnabledGuardTest {

    @Test
    @DisplayName("dataPlane.enabled=false 时 bind() 早退、不占用 UDP listener")
    void bindEarlyExitWhenDisabled() {
        String previousSmokeTest = System.getProperty("hassium.smokeTest");
        System.setProperty("hassium.smokeTest", "true");
        HassiumConfigService service = HassiumConfigService.getInstance();
        HassiumConfig previous = service.getConfig();
        try {
            DataPlaneUdpServer.shutdown();
            service.updateConfig(withDataPlaneEnabled(previous, false));

            DataPlaneServer.bind();

            assertFalse(DataPlaneServer.isBound(), "disabled 时 bind 不应创建 UDP listener");
            assertFalse(DataPlaneServer.tryRouteBulk(new java.util.UUID(0L, 1L),
                    DataPlaneFrame.TYPE_BULK_COMPRESSED_CHUNK, new byte[0]),
                    "disabled 时 router 必须返回 false 以走 Primary 回退");
        } finally {
            DataPlaneUdpServer.shutdown();
            service.updateConfig(previous);
            if (previousSmokeTest == null) {
                System.clearProperty("hassium.smokeTest");
            } else {
                System.setProperty("hassium.smokeTest", previousSmokeTest);
            }
        }
    }

    private static HassiumConfig withDataPlaneEnabled(HassiumConfig config, boolean enabled) {
        HassiumConfig.ServerNetworkConfig network = config.serverNetwork();
        HassiumConfig.DataPlaneConfig dataPlane = network.dataPlane();
        HassiumConfig.DataPlaneConfig replacement = new HassiumConfig.DataPlaneConfig(enabled,
                enabled ? dataPlane.udpListeners() : List.of(), dataPlane.controlStallMs(),
                dataPlane.failoverExpiryMs(), dataPlane.recoveryWindowMs());
        HassiumConfig.ServerNetworkConfig updatedNetwork = new HassiumConfig.ServerNetworkConfig(
                network.enabled(), network.compressionLevel(), network.magiclessZstd(),
                network.globalPacketCompression(), network.globalCompressionLevel(),
                network.globalCompressionThreshold(), network.useContextCompression(),
                network.enablePacketAggregation(), network.aggregationMinBatchSize(),
                network.aggregationMaxWaitTimeMs(), network.aggregationMaxSize(),
                network.enableCompactHeader(), network.compressionBlacklist(), network.metricsEnabled(),
                network.maxChunksPerTick(), network.serverChunkPushThreads(), network.dynamicThreadPoolEnabled(),
                network.minPushThreads(), network.maxPushThreads(), network.lightStrip(),
                network.seedGenEnabled(),
                network.controlReachableEndpoints(), replacement);
        return new HassiumConfig(config.storage(), config.clientCache(), config.clientNetwork(), updatedNetwork,
                config.compat(), config.debug());
    }

    @Test
    @DisplayName("控制可达端点按配置快照投影到握手尾部")
    void advertisesConfiguredControlEndpoints() {
        String previousSmokeTest = System.getProperty("hassium.smokeTest");
        System.setProperty("hassium.smokeTest", "true");
        HassiumConfigService service = HassiumConfigService.getInstance();
        HassiumConfig previous = service.getConfig();
        List<HassiumConfig.ReachableEndpoint> endpoints = List.of(
                new HassiumConfig.ReachableEndpoint("primary.example", 25565, 100),
                new HassiumConfig.ReachableEndpoint("backup.example", 25566, 80));
        try {
            service.updateConfig(withControlReachableEndpoints(previous, endpoints));

            assertEquals(List.of(
                    new UdpDataPlaneHandshakeTail.ControlEndpoint("primary.example", 25565, 100),
                    new UdpDataPlaneHandshakeTail.ControlEndpoint("backup.example", 25566, 80)),
                    DataPlaneUdpServer.advertisedControlEndpoints());
        } finally {
            service.updateConfig(previous);
            if (previousSmokeTest == null) {
                System.clearProperty("hassium.smokeTest");
            } else {
                System.setProperty("hassium.smokeTest", previousSmokeTest);
            }
        }
    }

    private static HassiumConfig withControlReachableEndpoints(HassiumConfig config,
                                                                List<HassiumConfig.ReachableEndpoint> endpoints) {
        HassiumConfig.ServerNetworkConfig network = config.serverNetwork();
        HassiumConfig.ServerNetworkConfig updatedNetwork = new HassiumConfig.ServerNetworkConfig(
                network.enabled(), network.compressionLevel(), network.magiclessZstd(),
                network.globalPacketCompression(), network.globalCompressionLevel(),
                network.globalCompressionThreshold(), network.useContextCompression(),
                network.enablePacketAggregation(), network.aggregationMinBatchSize(),
                network.aggregationMaxWaitTimeMs(), network.aggregationMaxSize(),
                network.enableCompactHeader(), network.compressionBlacklist(), network.metricsEnabled(),
                network.maxChunksPerTick(), network.serverChunkPushThreads(), network.dynamicThreadPoolEnabled(),
                network.minPushThreads(), network.maxPushThreads(), network.lightStrip(),
                network.seedGenEnabled(),
                endpoints, network.dataPlane());
        return new HassiumConfig(config.storage(), config.clientCache(), config.clientNetwork(), updatedNetwork,
                config.compat(), config.debug());
    }

    @Test
    @DisplayName("DataPlaneClientBundle Data 帧计数器可复位（冒烟跨阶段边界保护）")
    void clientBulkCountersReset() {
        try {
            DataPlaneClientBundle.resetDataBulkCounters();
            assertEquals(0L, DataPlaneClientBundle.getBulkFramesData(), "复位后帧计数归零");
            assertEquals(0L, DataPlaneClientBundle.getBulkBytesData(), "复位后字节计数归零");
        } finally {
            DataPlaneClientBundle.resetDataBulkCounters();
        }
    }
}
