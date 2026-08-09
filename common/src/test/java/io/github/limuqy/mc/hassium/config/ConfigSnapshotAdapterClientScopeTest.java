package io.github.limuqy.mc.hassium.config;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression coverage for reviewer findings on client-side schema routing.
 * <p>
 * These assertions fail against the pre-fix tree because:
 * <ul>
 *   <li>{@code ConfigSnapshotAdapter.toValues} omitted NET_* and CLIENT_DEBUG_*;</li>
 *   <li>{@code fromValues} read only SERVER_DEBUG_* so client debug state silently defaulted.</li>
 * </ul>
 */
class ConfigSnapshotAdapterClientScopeTest {

    @Test
    void clientNetworkSettingsRoundTripThroughValues() {
        HassiumConfig.NetCoreConfig net = new HassiumConfig.NetCoreConfig(false, true, true);
        HassiumConfig original = HassiumConfig.DEFAULT.withNet(net);

        ConfigValues values = ConfigSnapshotAdapter.toValues(original);

        assertEquals(false, values.get(ConfigSchema.NET_ENABLED));
        assertEquals(true, values.get(ConfigSchema.NET_METRICS_ENABLED));
        assertEquals(true, values.get(ConfigSchema.NET_METRICS_AUTO_RESET));

        // fromValues 还原（CLIENT scope）必须带回 net.*
        HassiumConfig restored = ConfigSnapshotAdapter.fromValues(values, true);
        assertEquals(false, restored.net().enabled());
        assertEquals(true, restored.net().metricsEnabled());
        assertEquals(true, restored.net().metricsAutoReset());
    }

    @Test
    void clientSeedGenRoundTripsThroughValues() {
        HassiumConfig original = HassiumConfig.DEFAULT.withNet(HassiumConfig.NetCoreConfig.DEFAULT);
        ConfigValues values = ConfigSnapshotAdapter.toValues(original);

        // 双端同名 chunk.seedGenEnabled：CLIENT 值来自 chunk 记录，SERVER 值同步写入（与 debug 模式一致）
        assertEquals(false, values.get(ConfigSchema.CLIENT_CHUNK_SEED_GEN_ENABLED));
        assertEquals(false, values.get(ConfigSchema.SERVER_CHUNK_SEED_GEN_ENABLED));
        HassiumConfig restored = ConfigSnapshotAdapter.fromValues(values, true);
        assertEquals(false, restored.chunk().seedGenEnabled());
    }

    @Test
    void clientDebugFlagsRoundTripThroughValues() {
        HassiumConfig.DebugConfig debug = new HassiumConfig.DebugConfig(
                true, false, true, false, true, false, true, false, true);
        HassiumConfig original = HassiumConfig.DEFAULT.withDebug(debug);

        ConfigValues values = ConfigSnapshotAdapter.toValues(original);

        assertEquals(true, values.get(ConfigSchema.CLIENT_DEBUG_METADATA));
        assertEquals(false, values.get(ConfigSchema.CLIENT_DEBUG_DISPATCHER));
        assertEquals(true, values.get(ConfigSchema.CLIENT_DEBUG_ASYNC));
        assertEquals(false, values.get(ConfigSchema.CLIENT_DEBUG_COMPRESSION));
        assertEquals(true, values.get(ConfigSchema.CLIENT_DEBUG_CHUNK_APPLY));
        assertEquals(false, values.get(ConfigSchema.CLIENT_DEBUG_NETWORK));
        assertEquals(true, values.get(ConfigSchema.CLIENT_DEBUG_CACHE));
        assertEquals(false, values.get(ConfigSchema.CLIENT_DEBUG_DATAPLANE));
    }

    @Test
    void fromValuesOnClientSideReadsClientDebugFlags() {
        HassiumConfig.DebugConfig debug = new HassiumConfig.DebugConfig(
                true, false, true, false, true, false, true, false, true);
        HassiumConfig original = HassiumConfig.DEFAULT.withDebug(debug);

        ConfigValues values = ConfigSnapshotAdapter.toValues(original);
        HassiumConfig restored = ConfigSnapshotAdapter.fromValues(values, true);

        assertEquals(debug, restored.debug());
    }

    @Test
    void fromValuesOnServerSideReadsServerDebugFlags() {
        HassiumConfig.DebugConfig debug = new HassiumConfig.DebugConfig(
                false, true, false, true, false, true, false, true, false);
        HassiumConfig original = HassiumConfig.DEFAULT.withDebug(debug);

        ConfigValues values = ConfigSnapshotAdapter.toValues(original);
        HassiumConfig restored = ConfigSnapshotAdapter.fromValues(values, false);

        assertEquals(debug, restored.debug());
    }

    @Test
    void clientScopeEntriesAbsentFromServerScope() {
        // Sanity: schema must keep CLIENT_DEBUG_* out of serverEntries(), per design L233.
        assertTrue(ConfigSchema.serverEntries().stream()
                .noneMatch(e -> e.key() == ConfigSchema.CLIENT_DEBUG_METADATA));
        assertTrue(ConfigSchema.clientEntries().stream()
                .noneMatch(e -> e.key() == ConfigSchema.SERVER_DEBUG_METADATA));
        assertFalse(ConfigSchema.clientEntries().isEmpty());
        assertFalse(ConfigSchema.serverEntries().isEmpty());
    }
}
