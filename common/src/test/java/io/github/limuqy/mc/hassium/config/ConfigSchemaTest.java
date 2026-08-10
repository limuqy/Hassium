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
    void migrationPolicyKeysRegisteredWithContractScopes() {
        ConfigValues values = ConfigValues.defaults(ConfigSchema.entries());

        // CLIENT scope 6 键（B2 策略参数全链接线；客户端 MigrationEngine 消费，cloth 屏可见）
        assertEquals(ConfigScope.CLIENT, ConfigSchema.MASTER_MIGRATION_MIN_TPS.scope());
        assertEquals(15.0, values.get(ConfigSchema.MASTER_MIGRATION_MIN_TPS));
        assertEquals(ConfigScope.CLIENT, ConfigSchema.MASTER_MIGRATION_MAX_LOAD_AVERAGE.scope());
        assertEquals(4.0, values.get(ConfigSchema.MASTER_MIGRATION_MAX_LOAD_AVERAGE));
        assertEquals(ConfigScope.CLIENT, ConfigSchema.MASTER_MIGRATION_MAINTENANCE_WINDOW.scope());
        assertEquals("", values.get(ConfigSchema.MASTER_MIGRATION_MAINTENANCE_WINDOW));
        assertEquals(ConfigScope.CLIENT, ConfigSchema.MASTER_MIGRATION_HEARTBEAT_INTERVAL_MS.scope());
        assertEquals(5000L, values.get(ConfigSchema.MASTER_MIGRATION_HEARTBEAT_INTERVAL_MS));
        assertEquals(ConfigScope.CLIENT, ConfigSchema.MASTER_MIGRATION_IDLE_WINDOW_MS.scope());
        assertEquals(10000L, values.get(ConfigSchema.MASTER_MIGRATION_IDLE_WINDOW_MS));
        assertEquals(ConfigScope.CLIENT, ConfigSchema.MASTER_MIGRATION_SILENT_TIMEOUT_MS.scope());
        // N2：默认静默超时 ≤15s（失效识别目标）
        assertTrue(values.get(ConfigSchema.MASTER_MIGRATION_SILENT_TIMEOUT_MS) <= 15_000L,
                "默认静默超时必须 ≤15s，实际 " + values.get(ConfigSchema.MASTER_MIGRATION_SILENT_TIMEOUT_MS));

        // SERVER scope：预热 TTL（T4 交付键）+ 既有 faultTimeout 兼容键保留
        assertEquals(ConfigScope.SERVER, ConfigSchema.MASTER_MIGRATION_PREWARM_TTL_MS.scope());
        assertEquals(60_000L, values.get(ConfigSchema.MASTER_MIGRATION_PREWARM_TTL_MS));
        assertEquals(ConfigScope.SERVER, ConfigSchema.MASTER_MIGRATION_FAULT_TIMEOUT_MS.scope());
        assertEquals(60_000L, values.get(ConfigSchema.MASTER_MIGRATION_FAULT_TIMEOUT_MS));
    }
}
