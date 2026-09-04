package io.github.limuqy.mc.hassium.config;

import org.junit.jupiter.api.Test;

import java.util.HashSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConfigSchemaTest {
    @Test
    void schemaContainsUniqueClientAndServerPaths() {
        assertEquals(
                ConfigSchema.clientEntries().size(),
                new HashSet<>(ConfigSchema.clientEntries().stream().map(ConfigEntry::path).toList()).size()
        );
        assertEquals(
                ConfigSchema.serverEntries().size(),
                new HashSet<>(ConfigSchema.serverEntries().stream().map(ConfigEntry::path).toList()).size()
        );
    }

    @Test
    void defaultsContainCoreConfigurationKeys() {
        ConfigValues values = ConfigValues.defaults(ConfigSchema.entries());

        // storage.enabled 默认关（专用服务器才写 type-126；单人/局域网保持原版格式）
        assertFalse(values.get(ConfigSchema.STORAGE_ENABLED));
        assertEquals(3, values.get(ConfigSchema.STORAGE_ZSTD_LEVEL));
        assertEquals(3, values.get(ConfigSchema.MASTER_COMPRESSION_LEVEL));
    }

    @Test
    void gatewayMigrationDataplaneKeysRetired() {
        ConfigValues values = ConfigValues.defaults(ConfigSchema.entries());
        // 网关拓扑退役：master 网关监听/鉴权/控制面端点/L1 迁移/续流票据与 dataplane.* 键族全删
        String[] retiredPaths = {
                "master.bindHost", "master.authToken", "master.controlReachableEndpoints",
                "master.migrationFaultTimeoutMs", "master.migrationMinTps", "master.migrationMaxLoadAverage",
                "master.migrationMaintenanceWindow", "master.migrationHeartbeatIntervalMs",
                "master.migrationIdleWindowMs", "master.migrationSilentTimeoutMs",
                "master.migrationPrewarmTtlMs", "master.resumeTicketTtlMs",
                "dataplane.enabled", "dataplane.udpListeners"
        };
        for (String path : retiredPaths) {
            assertTrue(ConfigSchema.entries().stream().noneMatch(e -> e.path().equals(path)),
                    "退役键不应再注册: " + path);
        }
        // 聚合键族（保留）默认值抽查
        assertEquals(true, values.get(ConfigSchema.MASTER_PACKET_AGGREGATION));
        assertEquals(4, values.get(ConfigSchema.MASTER_AGGREGATION_MIN_BATCH));
        assertEquals(50L, values.get(ConfigSchema.MASTER_AGGREGATION_MAX_WAIT));
        assertEquals(256 * 1024, values.get(ConfigSchema.MASTER_AGGREGATION_MAX_SIZE));
        assertEquals(true, values.get(ConfigSchema.MASTER_COMPACT_HEADER));
    }
}
