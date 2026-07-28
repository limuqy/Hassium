package io.github.limuqy.mc.hassium.config;

import org.junit.jupiter.api.Test;

import java.util.HashSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
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

        assertTrue(values.get(ConfigSchema.STORAGE_ENABLED));
        assertEquals(3, values.get(ConfigSchema.STORAGE_ZSTD_LEVEL));
        assertEquals(3, values.get(ConfigSchema.NETWORK_COMPRESSION_LEVEL));
    }
}
