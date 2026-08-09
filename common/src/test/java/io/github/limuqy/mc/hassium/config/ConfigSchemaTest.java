package io.github.limuqy.mc.hassium.config;

import org.junit.jupiter.api.Test;

import java.util.HashSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

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
}
